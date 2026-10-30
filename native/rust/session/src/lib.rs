//! Encrypted cookie snapshots. Only a random 32-byte master key enters the OS credential store.
//! Android keeps its existing browser cookie store; this crate serves Desktop and iOS.
#![cfg(not(target_os = "android"))]

use ring::{
    aead,
    rand::{SecureRandom, SystemRandom},
};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::Path,
};
use zeroize::Zeroizing;

const HEADER: &[u8] = b"EHVS\x01";
const MAX_PLAIN: usize = 256 * 1024;
const SERVICE: &str = "moe.tarsin.ehviewer.session";
const SNAPSHOT: &str = "session-v1.bin";

/// Errors intentionally contain no credential, plaintext, path or OS-store error string.
#[derive(Debug, PartialEq, Eq)]
pub enum SessionError {
    Storage,
    Corrupt,
    TooLarge,
    InvalidDirectory,
}

type Result<T> = std::result::Result<T, SessionError>;

#[cfg(not(target_os = "ios"))]
fn platform_key(account: &str, create: bool) -> Result<Zeroizing<Vec<u8>>> {
    let entry = keyring::Entry::new(SERVICE, account).map_err(|_| SessionError::Storage)?;
    let value = match entry.get_secret() {
        Ok(bytes) => bytes,
        Err(keyring::Error::NoEntry) if create => {
            let mut bytes = Zeroizing::new(vec![0; 32]);
            SystemRandom::new()
                .fill(&mut bytes)
                .map_err(|_| SessionError::Storage)?;
            entry
                .set_secret(&bytes)
                .map_err(|_| SessionError::Storage)?;
            return Ok(bytes);
        }
        Err(_) => return Err(SessionError::Storage),
    };
    if value.len() != 32 {
        return Err(SessionError::Corrupt);
    }
    Ok(Zeroizing::new(value))
}

#[cfg(target_os = "ios")]
fn platform_key(account: &str, create: bool) -> Result<Zeroizing<Vec<u8>>> {
    use security_framework::passwords::{get_generic_password, set_generic_password};
    let value = match get_generic_password(SERVICE, account) {
        Ok(bytes) => bytes,
        // errSecItemNotFound. A missing key must never replace one needed by an existing snapshot.
        Err(error) if error.code() == -25300 && create => {
            let mut bytes = Zeroizing::new(vec![0; 32]);
            SystemRandom::new()
                .fill(&mut bytes)
                .map_err(|_| SessionError::Storage)?;
            set_generic_password(SERVICE, account, &bytes).map_err(|_| SessionError::Storage)?;
            return Ok(bytes);
        }
        Err(_) => return Err(SessionError::Storage),
    };
    if value.len() != 32 {
        return Err(SessionError::Corrupt);
    }
    Ok(Zeroizing::new(value))
}

fn key(bytes: &[u8]) -> Result<aead::LessSafeKey> {
    aead::UnboundKey::new(&aead::AES_256_GCM, bytes)
        .map(aead::LessSafeKey::new)
        .map_err(|_| SessionError::Corrupt)
}

fn seal(bytes: &[u8], master: &[u8]) -> Result<Vec<u8>> {
    if bytes.len() > MAX_PLAIN {
        return Err(SessionError::TooLarge);
    }
    let mut nonce = [0; 12];
    SystemRandom::new()
        .fill(&mut nonce)
        .map_err(|_| SessionError::Storage)?;
    let mut encrypted = bytes.to_vec();
    key(master)?
        .seal_in_place_append_tag(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(HEADER),
            &mut encrypted,
        )
        .map_err(|_| SessionError::Storage)?;
    let mut output = Vec::with_capacity(HEADER.len() + nonce.len() + encrypted.len());
    output.extend_from_slice(HEADER);
    output.extend_from_slice(&nonce);
    output.extend_from_slice(&encrypted);
    Ok(output)
}

fn unseal(mut bytes: Vec<u8>, master: &[u8]) -> Result<Vec<u8>> {
    let offset = HEADER.len() + 12;
    if bytes.len() < offset + aead::AES_256_GCM.tag_len()
        || bytes.len() > MAX_PLAIN + offset + aead::AES_256_GCM.tag_len()
        || !bytes.starts_with(HEADER)
    {
        return Err(SessionError::Corrupt);
    }
    let nonce: [u8; 12] = bytes[HEADER.len()..offset]
        .try_into()
        .map_err(|_| SessionError::Corrupt)?;
    let plaintext = key(master)?
        .open_in_place(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(HEADER),
            &mut bytes[offset..],
        )
        .map_err(|_| SessionError::Corrupt)?;
    Ok(plaintext.to_vec())
}

fn locked<T>(root: &Path, operation: impl FnOnce(&Path, &str) -> Result<T>) -> Result<T> {
    if !root.is_absolute() {
        return Err(SessionError::InvalidDirectory);
    }
    fs::create_dir_all(root).map_err(|_| SessionError::Storage)?;
    let metadata = fs::symlink_metadata(root).map_err(|_| SessionError::Storage)?;
    if metadata.is_symlink() || !metadata.is_dir() {
        return Err(SessionError::InvalidDirectory);
    }
    let root = root.canonicalize().map_err(|_| SessionError::Storage)?;
    let mut options = OpenOptions::new();
    options.create(true).read(true).write(true).truncate(false);
    #[cfg(unix)]
    {
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        fs::set_permissions(&root, fs::Permissions::from_mode(0o700))
            .map_err(|_| SessionError::Storage)?;
        options.mode(0o600);
    }
    let file = options
        .open(root.join(".session.lock"))
        .map_err(|_| SessionError::Storage)?;
    file.lock().map_err(|_| SessionError::Storage)?;
    let identity = ehviewer_core::hash::sha1(root.as_os_str().to_string_lossy().as_bytes())
        .map_err(|_| SessionError::Storage)?;
    let account = format!("v1-{identity}");
    let result = operation(&root.join(SNAPSHOT), &account);
    file.unlock().map_err(|_| SessionError::Storage)?;
    result
}

fn read_with(
    path: &Path,
    account: &str,
    provider: impl Fn(&str, bool) -> Result<Zeroizing<Vec<u8>>>,
) -> Result<Option<Vec<u8>>> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(SessionError::Storage),
    };
    if !metadata.is_file() || metadata.is_symlink() || metadata.len() > (MAX_PLAIN + 64) as u64 {
        return Err(SessionError::Corrupt);
    }
    let master = provider(account, false)?;
    let plaintext = unseal(fs::read(path).map_err(|_| SessionError::Storage)?, &master)?;
    Ok(if plaintext.is_empty() {
        None
    } else {
        Some(plaintext)
    })
}

fn write_with(
    path: &Path,
    account: &str,
    bytes: &[u8],
    provider: impl Fn(&str, bool) -> Result<Zeroizing<Vec<u8>>>,
) -> Result<()> {
    if bytes.len() > MAX_PLAIN {
        return Err(SessionError::TooLarge);
    }
    let master = provider(account, !path.exists())?;
    let encrypted = seal(bytes, &master)?;
    let mut random = [0; 12];
    SystemRandom::new()
        .fill(&mut random)
        .map_err(|_| SessionError::Storage)?;
    let temporary = path.with_extension(format!(
        "{}.pending",
        random
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    ));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options
            .open(&temporary)
            .map_err(|_| SessionError::Storage)?;
        file.write_all(&encrypted)
            .map_err(|_| SessionError::Storage)?;
        file.sync_all().map_err(|_| SessionError::Storage)?;
        drop(file);
        fs::rename(&temporary, path).map_err(|_| SessionError::Storage)?;
        #[cfg(unix)]
        File::open(path.parent().ok_or(SessionError::InvalidDirectory)?)
            .and_then(|directory| directory.sync_all())
            .map_err(|_| SessionError::Storage)?;
        Ok(())
    })();
    if temporary.exists() {
        fs::remove_file(&temporary).map_err(|_| SessionError::Storage)?;
    }
    result
}

pub fn read(root: &Path) -> Result<Option<Vec<u8>>> {
    locked(root, |path, account| read_with(path, account, platform_key))
}

pub fn write(root: &Path, bytes: &[u8]) -> Result<()> {
    locked(root, |path, account| {
        write_with(path, account, bytes, platform_key)
    })
}

pub fn clear(root: &Path) -> Result<()> {
    locked(root, |path, account| {
        if path.exists() {
            write_with(path, account, &[], platform_key)
        } else {
            Ok(())
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn authenticated_snapshots_use_random_nonces_and_reject_tampering_wrong_keys_and_versions() {
        let master = [7; 32];
        let first = seal(b"test-only-secret", &master).unwrap();
        let second = seal(b"test-only-secret", &master).unwrap();
        assert_ne!(first, second);
        assert!(!first.windows(16).any(|part| part == b"test-only-secret"));
        assert_eq!(unseal(first.clone(), &master).unwrap(), b"test-only-secret");
        assert_eq!(unseal(first.clone(), &[8; 32]), Err(SessionError::Corrupt));
        for index in [0, HEADER.len(), first.len() - 1] {
            let mut corrupted = first.clone();
            corrupted[index] ^= 1;
            assert_eq!(unseal(corrupted, &master), Err(SessionError::Corrupt));
        }
        assert_eq!(
            seal(&vec![0; MAX_PLAIN + 1], &master),
            Err(SessionError::TooLarge)
        );
    }

    #[test]
    fn disk_restore_logout_and_missing_key_failure_preserve_the_original_snapshot() {
        let mut random = [0; 12];
        SystemRandom::new().fill(&mut random).unwrap();
        let root = std::env::temp_dir().join(format!("ehviewer-session-test-{:x?}", random));
        let provider = |_: &str, _: bool| Ok(Zeroizing::new(vec![7; 32]));
        locked(&root, |path, account| {
            assert_eq!(read_with(path, account, provider)?, None);
            write_with(path, account, b"persistent-test-data", provider)?;
            assert_eq!(
                read_with(path, account, provider)?,
                Some(b"persistent-test-data".to_vec())
            );
            let original = fs::read(path).unwrap();
            assert_eq!(
                write_with(path, account, b"replacement", |_, _| Err(
                    SessionError::Storage
                )),
                Err(SessionError::Storage)
            );
            assert_eq!(fs::read(path).unwrap(), original);
            write_with(path, account, &[], provider)?;
            assert_eq!(read_with(path, account, provider)?, None);
            assert_eq!(fs::read_dir(path.parent().unwrap()).unwrap().count(), 2);
            Ok(())
        })
        .unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn real_platform_credential_store_restores_an_owned_encrypted_session() {
        let mut random = [0; 12];
        SystemRandom::new().fill(&mut random).unwrap();
        let root = std::env::temp_dir().join(format!("ehviewer-session-os-test-{:x?}", random));
        fs::create_dir_all(&root).unwrap();
        let identity = ehviewer_core::hash::sha1(
            root.canonicalize()
                .unwrap()
                .as_os_str()
                .to_string_lossy()
                .as_bytes(),
        )
        .unwrap();
        let account = format!("v1-{identity}");
        let result = (|| {
            assert_eq!(read(&root)?, None);
            write(&root, b"owned-test-session")?;
            assert_eq!(read(&root)?, Some(b"owned-test-session".to_vec()));
            assert!(
                !fs::read(root.join(SNAPSHOT))
                    .unwrap()
                    .windows(18)
                    .any(|part| part == b"owned-test-session")
            );
            assert_eq!(platform_key(&account, false)?.len(), 32);
            clear(&root)?;
            assert_eq!(read(&root)?, None);
            Ok::<(), SessionError>(())
        })();
        #[cfg(not(target_os = "ios"))]
        let removed = keyring::Entry::new(SERVICE, &account)
            .unwrap()
            .delete_credential();
        #[cfg(target_os = "ios")]
        let removed = security_framework::passwords::delete_generic_password(SERVICE, &account);
        fs::remove_dir_all(root).unwrap();
        result.unwrap();
        removed.unwrap();
    }
}
