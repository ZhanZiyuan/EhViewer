//! Reuse private file views only after comparing them with the immutable source.
//! Released buffers may have arbitrary Java writes; a dirty view is never republished.
use super::{CACHE_BYTES, MAX_PARALLEL, Source};
use anyhow::Result;
use memmap2::{MmapMut, MmapOptions};
use std::sync::{Arc, Mutex, Weak};

struct Entry {
    offset: u64,
    map: MmapMut,
}
struct Cache {
    entries: Vec<Entry>,
    // Bounded admission history: repeatedly modified pages should not pay a full comparison.
    modified: Vec<(u64, usize)>,
}
pub(super) struct StoredPool(Mutex<Cache>);
impl StoredPool {
    pub(super) fn trim(&self) {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entries
            .clear();
    }

    pub(super) fn new() -> Arc<Self> {
        Arc::new(Self(Mutex::new(Cache {
            entries: Vec::with_capacity(MAX_PARALLEL),
            modified: Vec::with_capacity(MAX_PARALLEL),
        })))
    }
    pub(super) fn acquire(
        self: &Arc<Self>,
        source: &Source,
        offset: u64,
        len: usize,
    ) -> Result<StoredBuffer> {
        let key = (offset, len);
        let (cached, mut reusable) = {
            let mut cache = self.0.lock().unwrap_or_else(|e| e.into_inner());
            let reusable = !cache.modified.contains(&key);
            let cached = cache
                .entries
                .iter()
                .position(|e| e.offset == offset && e.map.len() == len)
                .map(|index| cache.entries.swap_remove(index).map);
            (cached, reusable)
        };
        let map = match cached {
            Some(map) if map[..] == source.stored_view(offset, len)?[..] => map,
            dirty_or_missing => {
                if dirty_or_missing.is_some() {
                    reusable = false;
                    let mut cache = self.0.lock().unwrap_or_else(|e| e.into_inner());
                    if !cache.modified.contains(&key) {
                        if cache.modified.len() == MAX_PARALLEL {
                            cache.modified.remove(0);
                        }
                        cache.modified.push(key);
                    }
                }
                drop(dirty_or_missing);
                // SAFETY: validated archive range and the immutable backing-file contract apply.
                // Each outstanding lease owns a distinct writable private mapping.
                unsafe {
                    MmapOptions::new()
                        .offset(offset)
                        .len(len)
                        .map_copy(&source.file)?
                }
            }
        };
        Ok(StoredBuffer {
            map: Some(map),
            offset,
            pool: if reusable {
                Arc::downgrade(self)
            } else {
                Weak::new()
            },
        })
    }
}

pub struct StoredBuffer {
    map: Option<MmapMut>,
    offset: u64,
    pool: Weak<StoredPool>,
}
impl StoredBuffer {
    pub(super) fn as_mut_slice(&mut self) -> &mut [u8] {
        self.map.as_mut().unwrap()
    }
    pub(super) fn len(&self) -> usize {
        self.map.as_ref().unwrap().len()
    }
}
impl Drop for StoredBuffer {
    fn drop(&mut self) {
        let Some(pool) = self.pool.upgrade() else {
            return;
        };
        let map = self.map.take().unwrap();
        let mut cache = pool.0.lock().unwrap_or_else(|e| e.into_inner());
        if cache.modified.contains(&(self.offset, map.len())) {
            return;
        }
        let entries = &mut cache.entries;
        if map.len() > CACHE_BYTES {
            return;
        }
        let mut evicted = Vec::new();
        while entries.len() >= MAX_PARALLEL
            || map.len() > CACHE_BYTES - entries.iter().map(|e| e.map.len()).sum::<usize>()
        {
            evicted.push(entries.remove(0));
        }
        entries.push(Entry {
            offset: self.offset,
            map,
        });
        drop(cache);
        // Unmapping an evicted view does not hold the pool lock.
        drop(evicted);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;

    fn source(name: &str) -> (std::path::PathBuf, Source) {
        let path =
            std::env::temp_dir().join(format!("ehviewer-stored-{name}-{}", std::process::id()));
        File::create(&path).unwrap().set_len(32 << 20).unwrap();
        let file = File::open(&path).unwrap();
        let map = unsafe { MmapOptions::new().map(&file) }.unwrap();
        (
            path,
            Source {
                file,
                map,
                views: Mutex::new(Vec::new()),
            },
        )
    }

    #[test]
    fn pristine_views_reuse_addresses_but_last_byte_mutations_are_discarded() {
        let (path, source) = source("reuse");
        let pool = StoredPool::new();
        let mut first = pool.acquire(&source, 7, 5 << 20).unwrap();
        let address = first.as_mut_slice().as_ptr();
        drop(first);
        let mut reused = pool.acquire(&source, 7, 5 << 20).unwrap();
        assert_eq!(address, reused.as_mut_slice().as_ptr());
        *reused.as_mut_slice().last_mut().unwrap() = 99;
        drop(reused);
        let mut clean = pool.acquire(&source, 7, 5 << 20).unwrap();
        assert!(clean.as_mut_slice().iter().all(|&byte| byte == 0));
        assert_eq!(pool.0.lock().unwrap().modified, [(7, 5 << 20)]);
        drop(clean);
        assert!(pool.0.lock().unwrap().entries.is_empty());
        assert_eq!(source.map[7 + (5 << 20) - 1], 0);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn eviction_is_bounded_without_invalidating_live_private_views() {
        let (path, source) = source("evict");
        let pool = StoredPool::new();
        let mut first = pool.acquire(&source, 0, 5 << 20).unwrap();
        let mut other = pool.acquire(&source, 0, 5 << 20).unwrap();
        assert_ne!(first.as_mut_slice().as_ptr(), other.as_mut_slice().as_ptr());
        first.as_mut_slice()[0] = 42;
        for offset in (0..20).map(|i| i * (1 << 20)) {
            drop(pool.acquire(&source, offset, 5 << 20).unwrap());
            let cache = pool.0.lock().unwrap();
            assert!(cache.entries.len() <= MAX_PARALLEL);
            assert!(cache.entries.iter().map(|e| e.map.len()).sum::<usize>() <= CACHE_BYTES);
        }
        drop(source);
        drop(pool);
        std::fs::remove_file(path).unwrap();
        assert_eq!(first.as_mut_slice()[0], 42);
        assert_eq!(other.as_mut_slice()[0], 0);
    }

    #[test]
    fn concurrent_dirty_returns_never_contaminate_the_next_lease() {
        let (path, source) = source("parallel");
        let pool = StoredPool::new();
        std::thread::scope(|scope| {
            for thread in 1..=8 {
                let (source, pool) = (&source, &pool);
                scope.spawn(move || {
                    for iteration in 0..30 {
                        let mut buffer = pool
                            .acquire(source, (iteration % 3) * 65536, 65536)
                            .unwrap();
                        assert!(buffer.as_mut_slice().iter().all(|&byte| byte == 0));
                        buffer.as_mut_slice()[32768] = thread;
                    }
                });
            }
        });
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn modified_page_admission_history_is_bounded() {
        let (path, source) = source("modified-history");
        let pool = StoredPool::new();
        for offset in (0..10).map(|i| i * 65536) {
            let mut dirty = pool.acquire(&source, offset, 65536).unwrap();
            dirty.as_mut_slice()[32768] = 1;
            drop(dirty);
            drop(pool.acquire(&source, offset, 65536).unwrap());
            let cache = pool.0.lock().unwrap();
            assert!(cache.modified.contains(&(offset, 65536)));
            assert!(cache.modified.len() <= MAX_PARALLEL);
            assert!(cache.entries.is_empty());
        }
        std::fs::remove_file(path).unwrap();
    }
}
