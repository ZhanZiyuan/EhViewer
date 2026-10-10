use ehviewer_core::archive::{ArchiveSession, Limits, safe_path, write_zip};
use std::{
    fs::{self, File},
    io::{Seek, SeekFrom},
    os::fd::AsRawFd,
    path::PathBuf,
    sync::Arc,
};
fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../test-fixtures")
        .join(name)
}
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

fn extra(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../test-fixtures")
        .join(name)
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
        let file = File::open(extra(name)).unwrap();
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
        let file = File::open(extra(name)).unwrap();
        let len = file.metadata().unwrap().len();
        assert!(unsafe { ArchiveSession::open(file, len, true, Limits::default()) }.is_err());
    }
}

#[test]
fn tar_filters_and_malformed_mutations() {
    for name in ["pages.tgz", "pages.txz"] {
        let file = File::open(extra(name)).unwrap();
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
            }
        }
    }
    fs::remove_file(path).unwrap();
}

#[test]
fn rar5_compressed_solid_and_backward_seek() {
    let oracle: serde_json::Value =
        serde_json::from_slice(&fs::read(extra("rar-expected.json")).unwrap()).unwrap();
    for (name, value) in oracle.as_object().unwrap() {
        let file = File::open(extra(name)).unwrap();
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
