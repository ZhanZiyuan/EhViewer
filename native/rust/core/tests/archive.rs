#[cfg(unix)]
use ehviewer_core::archive::write_zip;
use ehviewer_core::archive::{ArchiveSession, Limits, safe_path, write_zip_file};
#[cfg(unix)]
use std::os::fd::AsRawFd;
use std::{
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    sync::Arc,
};
mod support;
use support::fixture;

fn open(name: &str, sort: bool) -> ArchiveSession {
    let file = File::open(fixture(name)).unwrap();
    let len = file.metadata().unwrap().len();
    unsafe { ArchiveSession::open(file, len, sort, Limits::default()) }.unwrap()
}
#[test]
fn formats_passwords_order_and_random_access() {
    for name in [
        "stored.zip",
        "deflated.zip",
        "pages.tar",
        "pages.7z",
        "password.zip",
        "zipcrypto.zip",
    ] {
        let session = open(name, true);
        assert_eq!(session.len(), 3);
        let encrypted = name == "password.zip" || name == "zipcrypto.zip";
        assert_eq!(session.needs_password(), encrypted);
        if encrypted {
            assert!(!session.provide_password("wrong").unwrap());
            assert!(!session.provide_password("密码🔑").unwrap());
            assert!(session.provide_password("baseline").unwrap());
            assert!(!session.provide_password("wrong-again").unwrap());
        }
        for i in [2, 0, 1, 2, 0] {
            let expected = [b"one".as_slice(), b"two", b"ten"][i];
            assert_eq!(session.extract(i).unwrap().as_mut_slice(), expected);
            let mut output = Vec::new();
            session.extract_to(i, &mut output).unwrap();
            assert_eq!(output, expected);
            assert_eq!(session.extension(i).unwrap(), ["gif", "jpg", "png"][i]);
        }
        assert!(session.extract(3).is_err());
        assert!(session.extension(usize::MAX).is_err());
    }
    assert_eq!(
        open("stored.zip", false).extract(0).unwrap().as_mut_slice(),
        b"ten"
    );
}
#[test]
fn buffers_outlive_session_and_are_independent() {
    for name in ["stored.zip", "deflated.zip"] {
        let session = open(name, true);
        let mut a = session.extract(0).unwrap();
        let mut b = session.extract(0).unwrap();
        a.as_mut_slice()[0] = b'x';
        drop(session);
        assert_eq!(b.as_mut_slice(), b"one");
        assert_eq!(a.as_mut_slice(), b"xne");
    }
}

#[test]
fn readonly_leases_share_only_immutable_bytes_and_outlive_the_session() {
    for name in [
        "stored.zip",
        "deflated.zip",
        "password.zip",
        "pages.7z",
        "stored.rar",
    ] {
        let session = open(name, true);
        if session.needs_password() {
            assert!(session.extract_readonly(0).is_err());
            assert!(!session.provide_password("wrong").unwrap());
            assert!(session.extract_readonly(0).is_err());
            assert!(session.provide_password("baseline").unwrap());
        }
        let first = session.extract_readonly(0).unwrap();
        let other = session.extract_readonly(0).unwrap();
        let expected = first.as_slice().to_vec();
        if name == "stored.zip" {
            assert_eq!(first.as_slice().as_ptr(), other.as_slice().as_ptr());
        }
        let mut writable = session.extract(0).unwrap();
        writable.as_mut_slice()[0] ^= 0xff;
        assert_eq!(first.as_slice(), expected);
        let address = first.as_slice().as_ptr();
        let mut converted = first.into_writable().unwrap();
        if name == "deflated.zip" {
            assert_eq!(address, converted.as_mut_slice().as_ptr());
        }
        converted.as_mut_slice()[0] ^= 0xff;
        assert_eq!(other.as_slice(), expected);
        assert!(session.extract_readonly(session.len()).is_err());
        drop(session);
        assert_eq!(other.as_slice(), expected);
        assert_eq!(converted.as_mut_slice()[0], expected[0] ^ 0xff);
    }
}

#[test]
fn parallel_readonly_and_writable_leases_never_share_writes() {
    for name in ["stored.zip", "deflated.zip", "password.zip"] {
        let session = open(name, true);
        if session.needs_password() {
            assert!(session.provide_password("baseline").unwrap());
        }
        std::thread::scope(|scope| {
            for thread in 0..8 {
                let session = &session;
                scope.spawn(move || {
                    for iteration in 0..100 {
                        let index = (thread + iteration) % 3;
                        let expected = [b"one".as_slice(), b"two", b"ten"][index];
                        let readonly = session.extract_readonly(index).unwrap();
                        let mut writable = session.extract(index).unwrap();
                        writable.as_mut_slice().fill(255);
                        assert_eq!(readonly.as_slice(), expected);
                    }
                });
            }
        });
    }
}

#[test]
fn readonly_mapping_survives_fd_close_unlink_and_cache_eviction() {
    let directory = std::env::temp_dir().join(format!("ehviewer-readonly-{}", std::process::id()));
    fs::create_dir_all(&directory).unwrap();
    let payload = directory.join("input");
    File::create(&payload).unwrap().set_len(17 << 20).unwrap();
    let archive = directory.join("pages.zip");
    let mut output = File::create(&archive).unwrap();
    write_zip_file(
        &mut [(File::open(&payload).unwrap(), "page.jpg".into())],
        &mut output,
    )
    .unwrap();
    drop(output);
    let file = File::open(&archive).unwrap();
    let session = unsafe {
        ArchiveSession::open(
            file,
            fs::metadata(&archive).unwrap().len(),
            true,
            Limits::default(),
        )
    }
    .unwrap();
    let held = session.extract_readonly(0).unwrap();
    let address = held.as_slice().as_ptr();
    for _ in 0..100 {
        let other = session.extract_readonly(0).unwrap();
        assert_eq!(other.as_slice().as_ptr(), address);
    }
    drop(session);
    fs::remove_dir_all(directory).unwrap();
    assert_eq!(held.as_slice().len(), 17 << 20);
    assert!(held.as_slice().iter().all(|&byte| byte == 0));
}

#[test]
fn recycled_buffers_never_publish_previous_mutations() {
    for name in ["stored.zip", "deflated.zip", "password.zip"] {
        let session = open(name, true);
        if session.needs_password() {
            assert!(session.provide_password("baseline").unwrap());
        }
        for index in (0..3).cycle().take(100) {
            let expected = [b"one".as_slice(), b"two", b"ten"][index];
            let mut buffer = session.extract(index).unwrap();
            assert_eq!(buffer.as_mut_slice(), expected);
            buffer.as_mut_slice().fill(0xff);
            drop(buffer);
            assert_eq!(session.extract(index).unwrap().as_mut_slice(), expected);
        }
    }
}

#[test]
fn large_stored_mappings_survive_close_and_unlink() {
    let directory =
        std::env::temp_dir().join(format!("ehviewer-large-stored-{}", std::process::id()));
    fs::create_dir_all(&directory).unwrap();
    let payload = directory.join("input");
    File::create(&payload).unwrap().set_len(17 << 20).unwrap();
    let archive = directory.join("large.zip");
    let mut output = File::create(&archive).unwrap();
    write_zip_file(
        &mut [(File::open(&payload).unwrap(), "large.jpg".into())],
        &mut output,
    )
    .unwrap();
    drop(output);
    let file = File::open(&archive).unwrap();
    let size = file.metadata().unwrap().len();
    let session = unsafe { ArchiveSession::open(file, size, true, Limits::default()) }.unwrap();
    let mut first = session.extract(0).unwrap();
    let mut other = session.extract(0).unwrap();
    first.as_mut_slice()[0] = 123;
    drop(session);
    fs::remove_file(&archive).unwrap();
    assert_eq!(first.as_mut_slice()[0], 123);
    assert_eq!(other.as_mut_slice()[0], 0);
    assert_eq!(other.as_mut_slice().len(), 17 << 20);
    drop(first);
    drop(other);
    fs::remove_dir_all(directory).unwrap();
}
#[test]
fn independent_and_shared_sessions_parallel() {
    let shared = Arc::new(open("deflated.zip", true));
    std::thread::scope(|scope| {
        for thread in 0..12 {
            let session = shared.clone();
            scope.spawn(move || {
                let own = open("pages.7z", true);
                for n in 0..100 {
                    let i = (thread + n) % 3;
                    let expect = [b"one".as_slice(), b"two", b"ten"][i];
                    assert_eq!(session.extract(i).unwrap().as_mut_slice(), expect);
                    assert_eq!(own.extract(i).unwrap().as_mut_slice(), expect);
                }
            });
        }
    });
}

#[test]
fn stored_streaming_transfers_obey_concurrency_limit() {
    use std::io::{self, Write};
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct SlowWriter(Arc<AtomicUsize>, Arc<AtomicUsize>);
    impl Write for SlowWriter {
        fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
            let active = self.0.fetch_add(1, Ordering::SeqCst) + 1;
            self.1.fetch_max(active, Ordering::SeqCst);
            std::thread::sleep(std::time::Duration::from_millis(10));
            self.0.fetch_sub(1, Ordering::SeqCst);
            Ok(bytes.len())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
    let session = open("stored.zip", true);
    let active = Arc::new(AtomicUsize::new(0));
    let peak = Arc::new(AtomicUsize::new(0));
    std::thread::scope(|scope| {
        for index in 0..12 {
            let writer = SlowWriter(active.clone(), peak.clone());
            let session = &session;
            scope.spawn(move || session.extract_to(index % 3, writer).unwrap());
        }
    });
    assert!(peak.load(Ordering::SeqCst) <= 4);
    assert_eq!(active.load(Ordering::SeqCst), 0);
}
#[test]
fn limits_malformed_and_paths() {
    let file = File::open(fixture("broken.zip")).unwrap();
    let len = file.metadata().unwrap().len();
    assert!(unsafe { ArchiveSession::open(file, len, true, Limits::default()) }.is_err());
    for limits in [
        Limits {
            entry_bytes: 2,
            ..Limits::default()
        },
        Limits {
            entries: 2,
            ..Limits::default()
        },
        Limits {
            total_bytes: 3,
            ..Limits::default()
        },
        Limits {
            archive_bytes: 4,
            ..Limits::default()
        },
    ] {
        let file = File::open(fixture("stored.zip")).unwrap();
        let len = file.metadata().unwrap().len();
        assert!(unsafe { ArchiveSession::open(file, len, true, limits) }.is_err());
    }
    for name in [
        "../x.jpg",
        "/x.jpg",
        "a/../../x.jpg",
        "C:/x.jpg",
        "a\\x.jpg",
        "",
    ] {
        assert!(!safe_path(name.as_bytes()));
    }
    assert!(safe_path("相册/page2.jpg".as_bytes()));
}
#[test]
#[cfg(unix)]
fn zip_writer_preserves_fds_and_offsets() {
    let dir = std::env::temp_dir().join(format!("ehviewer-rust-write-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let input = dir.join("input");
    let output = dir.join("result.zip");
    fs::write(&input, b"xxxabc").unwrap();
    let mut file = File::open(&input).unwrap();
    file.seek(SeekFrom::Start(3)).unwrap();
    let mut inputs = vec![(file, "相册/page1.jpg".to_string())];
    let dest = File::create(&output).unwrap();
    write_zip(&mut inputs, dest.as_raw_fd()).unwrap();
    assert_eq!(inputs[0].0.stream_position().unwrap(), 6);
    assert!(dest.metadata().unwrap().len() > 0);
    let arc = File::open(&output).unwrap();
    let len = arc.metadata().unwrap().len();
    let session = unsafe { ArchiveSession::open(arc, len, true, Limits::default()) }.unwrap();
    assert_eq!(session.extract(0).unwrap().as_mut_slice(), b"abc");
    assert!(
        write_zip(
            &mut [(File::open(&input).unwrap(), "../escape.jpg".into())],
            dest.as_raw_fd()
        )
        .is_err()
    );
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn zip_file_writer_preserves_ownership_offsets_and_reports_output_errors() {
    let directory =
        std::env::temp_dir().join(format!("ehviewer-file-write-{}", std::process::id()));
    fs::create_dir_all(&directory).unwrap();
    let input = directory.join("input");
    let archive = directory.join("result.zip");
    fs::write(&input, b"xxxabc").unwrap();
    {
        let mut source = File::open(&input).unwrap();
        source.seek(SeekFrom::Start(3)).unwrap();
        let mut inputs = [(source, "相册/page1.jpg".into())];
        let mut output = File::options()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&archive)
            .unwrap();
        output.write_all(b"prefix").unwrap();
        write_zip_file(&mut inputs, &mut output).unwrap();
        assert_eq!(inputs[0].0.stream_position().unwrap(), 6);
        assert_eq!(
            output.stream_position().unwrap(),
            output.metadata().unwrap().len()
        );
        output.rewind().unwrap();
        let mut prefix = [0; 6];
        output.read_exact(&mut prefix).unwrap();
        assert_eq!(&prefix, b"prefix");
        let session = unsafe {
            ArchiveSession::open(
                File::open(&archive).unwrap(),
                output.metadata().unwrap().len(),
                true,
                Limits::default(),
            )
        }
        .unwrap();
        assert_eq!(session.len(), 1);
        assert_eq!(session.extract(0).unwrap().as_mut_slice(), b"abc");
        let mut readonly = File::open(&archive).unwrap();
        let error = write_zip_file(
            &mut [(File::open(&input).unwrap(), "page.jpg".into())],
            &mut readonly,
        )
        .unwrap_err();
        assert!(
            error.downcast_ref::<std::io::Error>().is_some(),
            "{error:#}"
        );
        assert_eq!(readonly.stream_position().unwrap(), 0);
        assert!(readonly.metadata().unwrap().len() > 6);
        let before = fs::read(&archive).unwrap();
        output.seek(SeekFrom::End(0)).unwrap();
        assert!(
            write_zip_file(
                &mut [(File::open(&input).unwrap(), "../escape.jpg".into())],
                &mut output
            )
            .is_err()
        );
        assert_eq!(fs::read(&archive).unwrap(), before);
        drop(session);
        // Both handles are still caller-owned and usable after success and failure.
        readonly.read_exact(&mut prefix).unwrap();
        output.write_all(b"owned").unwrap();
    }
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn rar_unicode_and_real_limits() {
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
        let file = File::open(fixture(name)).unwrap();
        let len = file.metadata().unwrap().len();
        let session = unsafe { ArchiveSession::open(file, len, true, Limits::default()) }.unwrap();
        assert_eq!(session.len(), 1);
        if let Some(password) = password {
            assert!(!session.provide_password("wrong").unwrap());
            assert!(session.provide_password(password).unwrap());
        }
        assert_eq!(session.extract(0).unwrap().as_mut_slice(), expected);
    }
    for name in ["traversal.zip", "oversized.tar"] {
        let file = File::open(fixture(name)).unwrap();
        let len = file.metadata().unwrap().len();
        assert!(unsafe { ArchiveSession::open(file, len, true, Limits::default()) }.is_err());
    }
}

#[test]
fn tar_filters_and_malformed_mutations() {
    for name in ["pages.tgz", "pages.txz"] {
        let file = File::open(fixture(name)).unwrap();
        let size = file.metadata().unwrap().len();
        let session = unsafe { ArchiveSession::open(file, size, true, Limits::default()) }.unwrap();
        assert_eq!(session.len(), 3);
        assert_eq!(session.extract(2).unwrap().as_mut_slice(), b"ten");
    }
    let original = fs::read(fixture("stored.zip")).unwrap();
    let path = std::env::temp_dir().join(format!("ehviewer-mutated-{}.zip", std::process::id()));
    for n in 0..512 {
        let mut data = original.clone();
        if n < 256 {
            data.truncate(n.min(data.len()));
        } else {
            let i = (n * 37) % data.len();
            data[i] ^= 0xff;
        }
        fs::write(&path, &data).unwrap();
        let file = File::open(&path).unwrap();
        if let Ok(session) = unsafe {
            ArchiveSession::open(
                file,
                data.len() as u64,
                true,
                Limits {
                    entry_bytes: 1 << 20,
                    entries: 100,
                    total_bytes: 1 << 20,
                    ..Limits::default()
                },
            )
        } {
            for index in 0..session.len() {
                let _ = session.extract(index);
                let _ = session.extract_readonly(index);
            }
        }
    }
    fs::remove_file(path).unwrap();
}

#[test]
fn rar5_compressed_solid_and_backward_seek() {
    let oracle: serde_json::Value =
        serde_json::from_slice(&fs::read(fixture("rar-expected.json")).unwrap()).unwrap();
    for (name, value) in oracle.as_object().unwrap() {
        let file = File::open(fixture(name)).unwrap();
        let len = file.metadata().unwrap().len();
        let session = unsafe { ArchiveSession::open(file, len, true, Limits::default()) }.unwrap();
        assert_eq!(session.len(), value["count"].as_u64().unwrap() as usize);
        for _ in 0..8 {
            for index in (0..session.len()).rev().chain(0..session.len()) {
                let mut buffer = session.extract(index).unwrap();
                let bytes = buffer.as_mut_slice();
                assert_eq!(
                    bytes.len(),
                    value["pages"][index]["size"].as_u64().unwrap() as usize
                );
                assert_eq!(
                    ehviewer_core::hash::sha1(&*bytes).unwrap(),
                    value["pages"][index]["sha1"].as_str().unwrap()
                );
                let mut output = Vec::new();
                session.extract_to(index, &mut output).unwrap();
                assert_eq!(output, bytes);
            }
        }
    }
}
