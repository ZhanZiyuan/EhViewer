//! Bounded reuse of exclusively owned decode storage. Java writes never escape into another lease.
use anyhow::Result;
use std::sync::{
    Arc, Mutex, Weak,
    atomic::{AtomicUsize, Ordering},
};

pub(super) const CACHE_BYTES: usize = 16 << 20;
const CACHE_ENTRIES: usize = 4;
const STORED_COPY_BYTES: usize = CACHE_BYTES / CACHE_ENTRIES;

struct Cache {
    buffers: Vec<Vec<u8>>,
    bytes: usize,
}
pub(super) struct BufferPool {
    cache: Mutex<Cache>,
    stored_bytes: AtomicUsize,
}
impl BufferPool {
    pub(super) fn trim(&self) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache.buffers.clear();
        cache.bytes = 0;
    }

    pub(super) fn new() -> Arc<Self> {
        Arc::new(Self {
            cache: Mutex::new(Cache {
                buffers: Vec::with_capacity(CACHE_ENTRIES),
                bytes: 0,
            }),
            stored_bytes: AtomicUsize::new(0),
        })
    }
    pub(super) fn acquire(self: &Arc<Self>, size: usize) -> Result<PooledBuffer> {
        let mut bytes = {
            let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
            let best = cache
                .buffers
                .iter()
                .enumerate()
                .filter(|(_, bytes)| bytes.capacity() >= size)
                .min_by_key(|(_, bytes)| bytes.capacity())
                .map(|(index, _)| index);
            best.map(|index| {
                let bytes = cache.buffers.swap_remove(index);
                cache.bytes -= bytes.capacity();
                bytes
            })
            .unwrap_or_default()
        };
        bytes.clear();
        bytes.try_reserve_exact(size)?;
        Ok(PooledBuffer {
            bytes,
            pool: Arc::downgrade(self),
            stored_bytes: 0,
        })
    }
    pub(super) fn acquire_stored(self: &Arc<Self>, size: usize) -> Result<Option<PooledBuffer>> {
        if self
            .stored_bytes
            .try_update(Ordering::Relaxed, Ordering::Relaxed, |used| {
                used.checked_add(size)
                    .filter(|&total| total <= STORED_COPY_BYTES)
            })
            .is_err()
        {
            return Ok(None);
        }
        match self.acquire(size) {
            Ok(mut buffer) => {
                buffer.stored_bytes = size;
                Ok(Some(buffer))
            }
            Err(error) => {
                self.stored_bytes.fetch_sub(size, Ordering::Relaxed);
                Err(error)
            }
        }
    }
}

pub struct PooledBuffer {
    pub(super) bytes: Vec<u8>,
    pool: Weak<BufferPool>,
    stored_bytes: usize,
}
impl Drop for PooledBuffer {
    fn drop(&mut self) {
        let Some(pool) = self.pool.upgrade() else {
            return;
        };
        pool.stored_bytes
            .fetch_sub(self.stored_bytes, Ordering::Relaxed);
        let capacity = self.bytes.capacity();
        if capacity == 0 || capacity > CACHE_BYTES {
            return;
        }
        let mut cache = pool.cache.lock().unwrap_or_else(|e| e.into_inner());
        if cache.buffers.len() < CACHE_ENTRIES && capacity <= CACHE_BYTES - cache.bytes {
            self.bytes.clear();
            cache.bytes += capacity;
            cache.buffers.push(std::mem::take(&mut self.bytes));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exclusive_reuse_and_expired_pool() {
        let pool = BufferPool::new();
        let mut first = pool.acquire(4096).unwrap();
        first.bytes.extend_from_slice(b"private mutation");
        let address = first.bytes.as_ptr();
        let second = pool.acquire(4096).unwrap();
        assert_ne!(address, second.bytes.as_ptr());
        drop(first);
        let reused = pool.acquire(4096).unwrap();
        assert_eq!(address, reused.bytes.as_ptr());
        assert!(reused.bytes.is_empty());
        drop(pool);
        drop(reused);
        drop(second);
    }

    #[test]
    fn cache_is_bounded_and_best_fit() {
        let pool = BufferPool::new();
        let mut leases = (0..8)
            .map(|_| pool.acquire(CACHE_BYTES / 4).unwrap())
            .collect::<Vec<_>>();
        leases.clear();
        {
            let cache = pool.cache.lock().unwrap();
            assert_eq!(cache.buffers.len(), CACHE_ENTRIES);
            assert_eq!(cache.bytes, CACHE_BYTES);
        }
        drop(pool.acquire(CACHE_BYTES + 1).unwrap());
        assert_eq!(pool.cache.lock().unwrap().bytes, CACHE_BYTES);
        let small = BufferPool::new();
        let a = small.acquire(100).unwrap();
        let b = small.acquire(200).unwrap();
        let address = a.bytes.as_ptr();
        drop(a);
        drop(b);
        assert_eq!(small.acquire(50).unwrap().bytes.as_ptr(), address);
    }

    #[test]
    fn outstanding_stored_copies_are_bounded() {
        let pool = BufferPool::new();
        let first = pool.acquire_stored(STORED_COPY_BYTES).unwrap().unwrap();
        assert!(pool.acquire_stored(1).unwrap().is_none());
        drop(first);
        assert!(pool.acquire_stored(STORED_COPY_BYTES).unwrap().is_some());
        assert!(
            pool.acquire_stored(STORED_COPY_BYTES + 1)
                .unwrap()
                .is_none()
        );
        assert_eq!(pool.stored_bytes.load(Ordering::Relaxed), 0);
    }
}
