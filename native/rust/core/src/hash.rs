//! Streaming SHA-1 for existing cache identities (not authentication).
use sha1::{Digest, Sha1};
use std::io::{self, Read};

pub fn sha1(mut input: impl Read) -> io::Result<String> {
    let mut state = Sha1::new();
    let mut buffer = [0; 8192];
    loop {
        match input.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => state.update(&buffer[..n]),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(format!("{:x}", state.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn known_vectors_and_errors() {
        assert_eq!(
            sha1(&b""[..]).unwrap(),
            "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        );
        assert_eq!(
            sha1(&b"abc"[..]).unwrap(),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(
            sha1(&vec![b'a'; 1_000_000][..]).unwrap(),
            "34aa973cd4c4daa4f61eeb2bdbad27316534016f"
        );
        struct Broken;
        impl Read for Broken {
            fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
                Err(io::Error::other("read failed"))
            }
        }
        assert!(sha1(Broken).is_err());
    }
}
