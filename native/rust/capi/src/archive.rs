//! Independently owned local comic archives, reusing the original Rust/libarchive implementation.
use super::*;
use ehviewer_core::archive::{ArchiveSession, Limits};
use std::{fs::File, path::Path, sync::Arc};

const MAX_ARCHIVES: usize = 8;
const MAX_PAGE_BYTES: u64 = 32 << 20;

struct Archives {
    next: u64,
    sessions: BTreeMap<u64, Arc<ArchiveSession>>,
}

static ARCHIVES: Mutex<Archives> = Mutex::new(Archives {
    next: FIRST_ARCHIVE_HANDLE,
    sessions: BTreeMap::new(),
});

fn session(handle: u64) -> Result<Arc<ArchiveSession>, i32> {
    ARCHIVES
        .lock()
        .map_err(|_| EHV_INTERNAL)?
        .sessions
        .get(&handle)
        .cloned()
        .ok_or(EHV_INVALID_HANDLE)
}

/// Open a private, immutable UTF-8 archive path with the original natural page ordering.
/// The path is never extracted to disk. Pages use the existing bounded owned-buffer ABI.
///
/// # Safety
/// `path` borrows `length` readable bytes; `output` is a disjoint writable u64 slot.
/// The caller must keep the backing file unmodified/untruncated until this handle and all
/// concurrent calls using it are released. Importers must copy external documents first.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_archive_open(path: *const u8, length: usize, output: *mut u64) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: caller owns the output slot, including on failure.
    unsafe { *output = 0 };
    boundary(|| {
        if path.is_null() || length == 0 || length > 65536 {
            return Err(EHV_INVALID_ARGUMENT);
        }
        // SAFETY: the caller borrows the validated input length for this call.
        let bytes = unsafe { std::slice::from_raw_parts(path, length) };
        let text = std::str::from_utf8(bytes).map_err(|_| EHV_INVALID_ARGUMENT)?;
        let path = Path::new(text);
        if text.contains('\0') || !path.is_absolute() {
            return Err(EHV_INVALID_ARGUMENT);
        }
        let metadata = std::fs::symlink_metadata(path).map_err(|_| EHV_STORAGE)?;
        if !metadata.is_file() || metadata.is_symlink() {
            return Err(EHV_INVALID_ARGUMENT);
        }
        let limits = Limits {
            entry_bytes: MAX_PAGE_BYTES,
            ..Limits::default()
        };
        if metadata.len() == 0 || metadata.len() > limits.archive_bytes {
            return Err(EHV_LIMIT);
        }
        let file = File::open(path).map_err(|_| EHV_STORAGE)?;
        // SAFETY: private immutable backing-file lifetime is part of this function's contract.
        let archive = unsafe { ArchiveSession::open(file, metadata.len(), true, limits) }
            .map_err(|_| EHV_PARSE)?;
        if archive.is_empty() {
            return Err(EHV_PARSE);
        }
        let mut owners = ARCHIVES.lock().map_err(|_| EHV_INTERNAL)?;
        if owners.sessions.len() >= MAX_ARCHIVES {
            return Err(EHV_LIMIT);
        }
        let handle = owners.next;
        owners.next = handle
            .checked_add(1)
            .filter(|next| *next <= i64::MAX as u64)
            .ok_or(EHV_LIMIT)?;
        owners.sessions.insert(handle, Arc::new(archive));
        // SAFETY: output is still a live caller-owned slot.
        unsafe { *output = handle };
        Ok(())
    })
}

/// Return the original engine's page count and encrypted-entry flag.
/// # Safety
/// Both output slots must be writable and disjoint.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_archive_info(
    handle: u64,
    pages: *mut u32,
    needs_password: *mut u8,
) -> i32 {
    if pages.is_null() || needs_password.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: initialize both caller-owned slots on every lookup failure.
    unsafe {
        *pages = 0;
        *needs_password = 0;
    }
    boundary(|| {
        let archive = session(handle)?;
        let count = u32::try_from(archive.len()).map_err(|_| EHV_LIMIT)?;
        // SAFETY: the output slots remain valid and page count has been checked.
        unsafe {
            *pages = count;
            *needs_password = u8::from(archive.needs_password());
        }
        Ok(())
    })
}

/// Validate a UTF-8 password using the real engine; rejected passwords return OK and accepted=0.
/// No password is logged, returned, persisted or placed in an owned result buffer.
/// # Safety
/// Input borrows `length` readable bytes (NULL allowed only for length=0);
/// `accepted` is a disjoint writable u8 slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_archive_password(
    handle: u64,
    input: *const u8,
    length: usize,
    accepted: *mut u8,
) -> i32 {
    if accepted.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: caller owns this slot.
    unsafe { *accepted = 0 };
    boundary(|| {
        if length > 4096 || input.is_null() && length != 0 {
            return Err(EHV_INVALID_ARGUMENT);
        }
        let bytes = if length == 0 {
            &[]
        } else {
            // SAFETY: the non-null input borrows a bounded length for this call.
            unsafe { std::slice::from_raw_parts(input, length) }
        };
        let password = std::str::from_utf8(bytes).map_err(|_| EHV_INVALID_ARGUMENT)?;
        if password.contains('\0') {
            return Err(EHV_INVALID_ARGUMENT);
        }
        let accepted_password = session(handle)?
            .provide_password(password)
            .map_err(|_| EHV_PARSE)?;
        // SAFETY: output is still valid; keep credentials inside the live archive engine.
        unsafe { *accepted = u8::from(accepted_password) };
        Ok(())
    })
}

/// Return a real page's file extension through the existing owned copy/free contract.
/// # Safety
/// `output` is a writable u64 slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_archive_extension(handle: u64, index: u32, output: *mut u64) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: caller owns this slot.
    unsafe { *output = 0 };
    boundary(|| {
        let archive = session(handle)?;
        if index as usize >= archive.len() {
            return Err(EHV_INVALID_ARGUMENT);
        }
        let extension = archive.extension(index as usize).map_err(|_| EHV_PARSE)?;
        let owned = retain(extension.as_bytes().to_vec())?;
        // SAFETY: output remains live for this call.
        unsafe { *output = owned };
        Ok(())
    })
}

/// Copy one actual image into a bounded owned result, independent of the archive's lifetime.
/// Closing an archive rejects later calls; already admitted concurrent calls retain their owner.
/// # Safety
/// `output` is a writable u64 slot. The archive's immutable-file contract must still hold.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_archive_read(handle: u64, index: u32, output: *mut u64) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: caller owns this slot, including on failure.
    unsafe { *output = 0 };
    boundary(|| {
        let archive = session(handle)?;
        if index as usize >= archive.len() {
            return Err(EHV_INVALID_ARGUMENT);
        }
        let page = archive
            .extract_readonly(index as usize)
            .map_err(|_| EHV_PARSE)?;
        let bytes = page.as_slice();
        if bytes.is_empty() || bytes.len() as u64 > MAX_PAGE_BYTES {
            return Err(EHV_LIMIT);
        }
        let owned = retain(bytes.to_vec())?;
        // SAFETY: the page is now an independently owned byte copy.
        unsafe { *output = owned };
        Ok(())
    })
}

/// Release exactly one archive owner. Stale/double-close handles are rejected, never reused.
#[unsafe(no_mangle)]
pub extern "C" fn ehv_archive_close(handle: u64) -> i32 {
    boundary(|| {
        let removed = ARCHIVES
            .lock()
            .map_err(|_| EHV_INTERNAL)?
            .sessions
            .remove(&handle)
            .ok_or(EHV_INVALID_HANDLE)?;
        // Drop engine readers/mappings outside the registry lock.
        drop(removed);
        Ok(())
    })
}

/// Measure live archive owners separately from the original owned byte-buffer counter.
/// # Safety
/// `count` is a writable size_t slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_active_archives(count: *mut usize) -> i32 {
    if count.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: initialize the caller-owned slot even if registry access fails.
    unsafe { *count = 0 };
    boundary(|| {
        let live = ARCHIVES.lock().map_err(|_| EHV_INTERNAL)?.sessions.len();
        // SAFETY: count remains writable for this call.
        unsafe { *count = live };
        Ok(())
    })
}

#[cfg(test)]
#[path = "../../core/tests/support/mod.rs"]
mod support;

#[cfg(test)]
mod tests {
    use super::*;
    use std::ptr::{null, null_mut};

    fn open(name: &str) -> u64 {
        let path = support::fixture(name);
        let bytes = path.to_str().unwrap().as_bytes();
        let mut handle = 0;
        assert_eq!(
            unsafe { ehv_archive_open(bytes.as_ptr(), bytes.len(), &mut handle) },
            EHV_OK
        );
        assert!(handle >= FIRST_ARCHIVE_HANDLE);
        handle
    }

    fn bytes(handle: u64) -> Vec<u8> {
        let mut size = 0;
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, null_mut(), 0, &mut size) },
            EHV_BUFFER_TOO_SMALL
        );
        let mut result = vec![0; size];
        assert_eq!(
            unsafe { ehv_buffer_copy(handle, result.as_mut_ptr(), result.len(), &mut size) },
            EHV_OK
        );
        assert_eq!(ehv_buffer_free(handle), EHV_OK);
        result
    }

    fn empty() {
        let mut count = usize::MAX;
        assert_eq!(unsafe { ehv_active_archives(&mut count) }, EHV_OK);
        assert_eq!(count, 0);
        assert_eq!(unsafe { ehv_active_buffers(&mut count) }, EHV_OK);
        assert_eq!(count, 0);
    }

    #[test]
    fn real_formats_natural_order_and_owned_pages_survive_archive_close() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        for name in ["stored.zip", "deflated.zip", "pages.tar", "pages.7z"] {
            let archive = open(name);
            let (mut count, mut encrypted) = (0, 2);
            assert_eq!(
                unsafe { ehv_archive_info(archive, &mut count, &mut encrypted) },
                EHV_OK
            );
            assert_eq!((count, encrypted), (3, 0));
            for (index, extension) in ["gif", "jpg", "png"].iter().enumerate() {
                let mut owned = 0;
                assert_eq!(
                    unsafe { ehv_archive_extension(archive, index as u32, &mut owned) },
                    EHV_OK
                );
                assert_eq!(bytes(owned), extension.as_bytes());
            }
            let mut owned = 0;
            assert_eq!(unsafe { ehv_archive_read(archive, 1, &mut owned) }, EHV_OK);
            assert_eq!(ehv_buffer_free(archive), EHV_INVALID_HANDLE);
            assert_eq!(ehv_archive_close(owned), EHV_INVALID_HANDLE);
            assert_eq!(ehv_archive_close(archive), EHV_OK);
            assert_eq!(bytes(owned), b"two");
            assert_eq!(ehv_archive_close(archive), EHV_INVALID_HANDLE);
            assert_eq!(
                unsafe { ehv_archive_read(archive, 0, &mut owned) },
                EHV_INVALID_HANDLE
            );
            assert_eq!(owned, 0);
            empty();
        }
    }

    #[test]
    fn rar_and_unicode_password_fixtures_retain_the_original_page_bytes() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        for (name, expected, password) in [
            (
                "stored.rar",
                b"hello libarchive test suite!\n".as_slice(),
                None,
            ),
            ("unicode.zip", b"unicode password".as_slice(), Some("密码")),
            (
                "unicode-emoji.zip",
                b"unicode password".as_slice(),
                Some("密码🔑"),
            ),
        ] {
            let archive = open(name);
            let (mut count, mut encrypted) = (0, 2);
            assert_eq!(
                unsafe { ehv_archive_info(archive, &mut count, &mut encrypted) },
                EHV_OK
            );
            assert_eq!((count, encrypted), (1, u8::from(password.is_some())));
            if let Some(password) = password {
                let mut accepted = 0;
                assert_eq!(
                    unsafe {
                        ehv_archive_password(
                            archive,
                            password.as_ptr(),
                            password.len(),
                            &mut accepted,
                        )
                    },
                    EHV_OK
                );
                assert_eq!(accepted, 1);
            }
            let mut output = 0;
            assert_eq!(unsafe { ehv_archive_read(archive, 0, &mut output) }, EHV_OK);
            assert_eq!(bytes(output), expected);
            assert_eq!(ehv_archive_close(archive), EHV_OK);
            empty();
        }
    }

    #[test]
    fn utf8_paths_with_spaces_and_emoji_open_real_private_files() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!(
            "ehviewer-capi-archive-{}-{nonce}",
            std::process::id()
        ));
        std::fs::create_dir(&directory).unwrap();
        let file = directory.join("漫画 文件📚.cbz");
        std::fs::copy(support::fixture("stored.zip"), &file).unwrap();
        let path = file.to_str().unwrap().as_bytes();
        let mut archive = 0;
        assert_eq!(
            unsafe { ehv_archive_open(path.as_ptr(), path.len(), &mut archive) },
            EHV_OK
        );
        let mut output = 0;
        assert_eq!(unsafe { ehv_archive_read(archive, 2, &mut output) }, EHV_OK);
        assert_eq!(bytes(output), b"ten");
        assert_eq!(ehv_archive_close(archive), EHV_OK);
        std::fs::remove_dir_all(directory).unwrap();
        empty();
    }

    #[test]
    fn encrypted_archives_validate_real_passwords_without_discarding_a_valid_one() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        for name in ["password.zip", "zipcrypto.zip"] {
            let archive = open(name);
            let (mut count, mut encrypted) = (0, 0);
            assert_eq!(
                unsafe { ehv_archive_info(archive, &mut count, &mut encrypted) },
                EHV_OK
            );
            assert_eq!((count, encrypted), (3, 1));
            let mut output = 42;
            assert_eq!(
                unsafe { ehv_archive_read(archive, 0, &mut output) },
                EHV_PARSE
            );
            assert_eq!(output, 0);
            for (password, expected) in [
                ("wrong", 0),
                ("密码🔑", 0),
                ("baseline", 1),
                ("wrong-again", 0),
            ] {
                let mut accepted = 2;
                assert_eq!(
                    unsafe {
                        ehv_archive_password(
                            archive,
                            password.as_ptr(),
                            password.len(),
                            &mut accepted,
                        )
                    },
                    EHV_OK
                );
                assert_eq!(accepted, expected);
            }
            assert_eq!(unsafe { ehv_archive_read(archive, 0, &mut output) }, EHV_OK);
            assert_eq!(bytes(output), b"one");
            assert_eq!(ehv_archive_close(archive), EHV_OK);
            empty();
        }
    }

    #[test]
    fn invalid_borrows_paths_indexes_and_malicious_archives_never_retain_results() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        let mut output = 42;
        for (pointer, length) in [(null(), 0), (null(), 1), (b"x".as_ptr(), 65537)] {
            assert_eq!(
                unsafe { ehv_archive_open(pointer, length, &mut output) },
                EHV_INVALID_ARGUMENT
            );
            assert_eq!(output, 0);
        }
        for path in [b"relative.zip".as_slice(), b"\xff", b"/x\0secret"] {
            assert_eq!(
                unsafe { ehv_archive_open(path.as_ptr(), path.len(), &mut output) },
                EHV_INVALID_ARGUMENT
            );
        }
        for name in ["traversal.zip", "oversized.tar"] {
            let path = support::fixture(name);
            let path = path.to_str().unwrap().as_bytes();
            assert_eq!(
                unsafe { ehv_archive_open(path.as_ptr(), path.len(), &mut output) },
                EHV_PARSE
            );
            assert_eq!(output, 0);
        }
        let archive = open("stored.zip");
        assert_eq!(
            unsafe { ehv_archive_read(archive, u32::MAX, &mut output) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(output, 0);
        assert_eq!(
            unsafe { ehv_archive_extension(archive, 3, &mut output) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(
            unsafe { ehv_archive_read(archive, 0, null_mut()) },
            EHV_INVALID_ARGUMENT
        );
        let mut accepted = 2;
        assert_eq!(
            unsafe { ehv_archive_password(archive, null(), 1, &mut accepted) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(accepted, 0);
        assert_eq!(
            unsafe { ehv_archive_password(archive, b"x".as_ptr(), 4097, &mut accepted) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(
            unsafe { ehv_active_archives(null_mut()) },
            EHV_INVALID_ARGUMENT
        );
        assert_eq!(ehv_archive_close(archive), EHV_OK);
        let (mut count, mut encrypted) = (42, 2);
        assert_eq!(
            unsafe { ehv_archive_info(archive, &mut count, &mut encrypted) },
            EHV_INVALID_HANDLE
        );
        assert_eq!((count, encrypted), (0, 0));
        empty();
    }

    #[test]
    fn bounded_archive_registry_rejects_capacity_and_never_reuses_closed_handles() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        let handles: Vec<_> = (0..MAX_ARCHIVES).map(|_| open("stored.zip")).collect();
        let path = support::fixture("stored.zip");
        let path = path.to_str().unwrap().as_bytes();
        let mut output = 42;
        assert_eq!(
            unsafe { ehv_archive_open(path.as_ptr(), path.len(), &mut output) },
            EHV_LIMIT
        );
        assert_eq!(output, 0);
        let stale = handles[0];
        for handle in handles {
            assert_eq!(ehv_archive_close(handle), EHV_OK);
        }
        let fresh = open("stored.zip");
        assert!(fresh > stale);
        assert_eq!(
            unsafe { ehv_archive_read(stale, 0, &mut output) },
            EHV_INVALID_HANDLE
        );
        assert_eq!(ehv_archive_close(fresh), EHV_OK);
        empty();
    }

    #[test]
    fn concurrent_real_reads_release_every_archive_and_owned_result() {
        let _serial = crate::tests::SERIAL.lock().unwrap();
        let archive = open("deflated.zip");
        std::thread::scope(|scope| {
            for _ in 0..4 {
                scope.spawn(|| {
                    for index in (0..3).cycle().take(60) {
                        let mut output = 0;
                        assert_eq!(
                            unsafe { ehv_archive_read(archive, index, &mut output) },
                            EHV_OK
                        );
                        assert_eq!(
                            bytes(output),
                            [b"one".as_slice(), b"two", b"ten"][index as usize]
                        );
                    }
                });
            }
        });
        assert_eq!(ehv_archive_close(archive), EHV_OK);
        empty();
    }
}
