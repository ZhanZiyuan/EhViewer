//! M1 owned-buffer C ABI. SHA-1 preserves existing cache identities; it is not authentication.
//! Input/output pointers are borrowed only for a call. Rust buffers stay behind monotonic IDs.
use std::{collections::BTreeMap, panic::AssertUnwindSafe, sync::Mutex};

pub const EHV_OK: i32 = 0;
pub const EHV_INVALID_ARGUMENT: i32 = 1;
pub const EHV_INVALID_HANDLE: i32 = 2;
pub const EHV_BUFFER_TOO_SMALL: i32 = 3;
pub const EHV_LIMIT: i32 = 4;
pub const EHV_INTERNAL: i32 = 5;
pub const EHV_PANIC: i32 = 6;
const MAX_INPUT: usize = 8 << 20;
const MAX_BUFFERS: usize = 4096;

struct Registry {
    next: u64,
    buffers: BTreeMap<u64, Box<[u8]>>,
}

static BUFFERS: Mutex<Registry> = Mutex::new(Registry {
    next: 1,
    buffers: BTreeMap::new(),
});

fn boundary(action: impl FnOnce() -> Result<(), i32>) -> i32 {
    match std::panic::catch_unwind(AssertUnwindSafe(action)) {
        Ok(Ok(())) => EHV_OK,
        Ok(Err(code)) => code,
        Err(_) => EHV_PANIC,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ehv_abi_version() -> u32 {
    1
}

/// Hash borrowed bytes with the existing Core implementation and return a Rust-owned buffer ID.
/// Empty input may be null. Input is limited to 8 MiB; at most 4096 40-byte outputs may be live.
/// On any failure, a valid output slot is reset to zero. Free each successful handle once.
///
/// # Safety
/// Nonempty `input` must be readable for `length` bytes for this call, without concurrent writes.
/// `handle` must point to one writable, aligned u64, disjoint from the input.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_hash_sha1(input: *const u8, length: usize, handle: *mut u64) -> i32 {
    if handle.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller provides one writable output slot under the ABI contract.
    unsafe { *handle = 0 };
    boundary(|| {
        if length > MAX_INPUT {
            return Err(EHV_LIMIT);
        }
        let input = if length == 0 {
            &[][..]
        } else {
            if input.is_null() {
                return Err(EHV_INVALID_ARGUMENT);
            }
            // SAFETY: checked non-null and bounded; validity is the caller's borrowed-input contract.
            unsafe { std::slice::from_raw_parts(input, length) }
        };
        let hash = ehviewer_core::hash::sha1(input).map_err(|_| EHV_INTERNAL)?;
        let mut registry = BUFFERS.lock().map_err(|_| EHV_INTERNAL)?;
        if registry.buffers.len() >= MAX_BUFFERS {
            return Err(EHV_LIMIT);
        }
        let id = registry.next;
        registry.next = id.checked_add(1).ok_or(EHV_LIMIT)?;
        registry
            .buffers
            .insert(id, hash.into_bytes().into_boxed_slice());
        // SAFETY: the borrowed output slot remains valid until this function returns.
        unsafe { *handle = id };
        Ok(())
    })
}

/// Copy an owned result into caller memory. `written` receives the required byte count.
/// Query with null output/capacity=0; EHV_BUFFER_TOO_SMALL leaves the output bytes untouched.
/// This does not release the handle, including on errors. IDs are never exposed as pointers.
///
/// # Safety
/// `written` must be a caller-owned writable aligned usize. With enough capacity, `output`
/// must be caller-owned and writable for `capacity` bytes, disjoint from `written` and all
/// Rust-managed storage; neither output may be concurrently accessed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_buffer_copy(
    handle: u64,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> i32 {
    if written.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller provides a valid output length slot.
    unsafe { *written = 0 };
    boundary(|| {
        let registry = BUFFERS.lock().map_err(|_| EHV_INTERNAL)?;
        let bytes = registry.buffers.get(&handle).ok_or(EHV_INVALID_HANDLE)?;
        // SAFETY: the caller's output length slot remains valid for this call.
        unsafe { *written = bytes.len() };
        if capacity < bytes.len() {
            return Err(EHV_BUFFER_TOO_SMALL);
        }
        if output.is_null() {
            return Err(EHV_INVALID_ARGUMENT);
        }
        // SAFETY: output capacity was checked; Rust storage is private and held by the lock.
        // The caller's writable region cannot alias this unexposed Rust allocation.
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), output, bytes.len()) };
        Ok(())
    })
}

/// Release one owned result. Zero, stale and double-freed IDs return EHV_INVALID_HANDLE.
#[unsafe(no_mangle)]
pub extern "C" fn ehv_buffer_free(handle: u64) -> i32 {
    boundary(|| {
        BUFFERS
            .lock()
            .map_err(|_| EHV_INTERNAL)?
            .buffers
            .remove(&handle)
            .ok_or(EHV_INVALID_HANDLE)?;
        Ok(())
    })
}

/// Observe live owned results so the interop smoke can verify cleanup.
///
/// # Safety
/// `count` must point to one writable, aligned usize for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_active_buffers(count: *mut usize) -> i32 {
    if count.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller supplies the writable count slot.
    unsafe { *count = 0 };
    boundary(|| {
        let registry = BUFFERS.lock().map_err(|_| EHV_INTERNAL)?;
        // SAFETY: the borrowed count slot remains valid for the call.
        unsafe { *count = registry.buffers.len() };
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ptr::{null, null_mut};
    static SERIAL: Mutex<()> = Mutex::new(());

    fn acquire(bytes: &[u8]) -> u64 {
        let mut handle = 0;
        assert_eq!(
            unsafe { ehv_hash_sha1(bytes.as_ptr(), bytes.len(), &mut handle) },
            EHV_OK
        );
        assert_ne!(handle, 0);
        handle
    }

    fn copy(handle: u64) -> String {
        let mut size = 0;
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, null_mut(), 0, &mut size) },
            EHV_BUFFER_TOO_SMALL
        );
        let mut bytes = vec![0; size];
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, bytes.as_mut_ptr(), bytes.len(), &mut size) },
            EHV_OK
        );
        String::from_utf8(bytes).unwrap()
    }

    fn empty() {
        let mut count = usize::MAX;
        assert_eq!(unsafe { ehv_active_buffers(&mut count) }, EHV_OK);
        assert_eq!(count, 0);
    }

    #[test]
    fn core_vectors_copy_and_release() {
        let _serial = SERIAL.lock().unwrap();
        for (bytes, expected) in [
            (&b""[..], "da39a3ee5e6b4b0d3255bfef95601890afd80709"),
            (&b"abc"[..], "a9993e364706816aba3e25717850c26c9cd0d89d"),
        ] {
            let handle = acquire(bytes);
            assert_eq!(copy(handle), expected);
            assert_eq!(ehv_buffer_free(handle), EHV_OK);
            assert_eq!(ehv_buffer_free(handle), EHV_INVALID_HANDLE);
        }
        empty();
    }

    #[test]
    fn invalid_borrowed_arguments_never_allocate() {
        let _serial = SERIAL.lock().unwrap();
        let mut handle = 999;
        assert_eq!(
            unsafe { ehv_hash_sha1(null(), 1, &mut handle) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(handle, 0);
        assert_eq!(
            unsafe { ehv_hash_sha1(b"x".as_ptr(), MAX_INPUT + 1, &mut handle) },
            EHV_LIMIT
        );
        assert_eq!(
            unsafe { ehv_hash_sha1(null(), 0, null_mut()) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(
            unsafe { ehv_active_buffers(null_mut()) },
            EHV_INVALID_ARGUMENT
        );
        empty();
    }

    #[test]
    fn short_output_is_untouched_and_invalid_ids_are_rejected() {
        let _serial = SERIAL.lock().unwrap();
        let handle = acquire(b"abc");
        let mut output = [255; 4];
        let mut size = 0;
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, output.as_mut_ptr(), output.len(), &mut size) },
            EHV_BUFFER_TOO_SMALL
        );
        assert_eq!(size, 40);
        assert_eq!(output, [255; 4]);
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, null_mut(), 40, &mut size) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, null_mut(), 0, null_mut()) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(ehv_buffer_free(handle), EHV_OK);
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, output.as_mut_ptr(), 4, &mut size) },
            EHV_INVALID_HANDLE
        );
        assert_eq!(size, 0);
        empty();
    }

    #[test]
    fn bounded_registry_does_not_reuse_stale_ids() {
        let _serial = SERIAL.lock().unwrap();
        let mut ids: Vec<_> = (0..MAX_BUFFERS).map(|_| acquire(b"x")).collect();
        let mut rejected = 99;
        assert_eq!(
            unsafe { ehv_hash_sha1(null(), 0, &mut rejected) },
            EHV_LIMIT
        );
        assert_eq!(rejected, 0);
        let old = ids.remove(0);
        assert_eq!(ehv_buffer_free(old), EHV_OK);
        let replacement = acquire(b"abc");
        assert!(replacement > *ids.last().unwrap());
        assert_eq!(ehv_buffer_free(old), EHV_INVALID_HANDLE);
        assert_eq!(
            copy(replacement),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        for id in ids.into_iter().chain([replacement]) {
            assert_eq!(ehv_buffer_free(id), EHV_OK);
        }
        empty();
    }

    #[test]
    fn concurrent_owners_release_every_result() {
        let _serial = SERIAL.lock().unwrap();
        std::thread::scope(|scope| {
            let workers: Vec<_> = (0..8)
                .map(|_| {
                    scope.spawn(|| {
                        for _ in 0..100 {
                            let id = acquire(b"abc");
                            assert_eq!(copy(id), "a9993e364706816aba3e25717850c26c9cd0d89d");
                            assert_eq!(ehv_buffer_free(id), EHV_OK);
                        }
                    })
                })
                .collect();
            for worker in workers {
                worker.join().unwrap();
            }
        });
        empty();
    }

    #[test]
    fn panic_is_contained_at_the_boundary() {
        assert_eq!(boundary(|| panic!("intentional boundary fault")), EHV_PANIC);
    }
}
