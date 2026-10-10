//! Restore byte-identical regression inputs locally without tracking generated data.
use std::{fs, path::PathBuf, process::Command};

const BASELINE: &str = "8fdfb6c5550fab506759a5587b1c8ee4050f507e";

pub fn fixture(name: &str) -> PathBuf {
    assert!(!name.contains(['/', '\\']) && !name.starts_with('.'));
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let output = Command::new("git")
        .current_dir(&root)
        .args(["show", &format!("{BASELINE}:native/test-fixtures/{name}")])
        .output()
        .expect("Git is required to restore the immutable regression baseline");
    assert!(
        output.status.success(),
        "Missing test baseline; fetch full Git history: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let directory = root.join(".local-test-fixtures");
    fs::create_dir_all(&directory).unwrap();
    let path = directory.join(name);
    // Gradle and Rust tests can share the existing local copy. Only replace data
    // that differs from the pinned baseline, using an atomic rename.
    if fs::read(&path).ok().as_deref() != Some(output.stdout.as_slice()) {
        use std::sync::atomic::{AtomicUsize, Ordering};
        static SEQUENCE: AtomicUsize = AtomicUsize::new(0);
        let temporary = directory.join(format!(
            ".{name}-{}-{}",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::write(&temporary, output.stdout).unwrap();
        fs::rename(temporary, &path).unwrap();
    }
    path
}
