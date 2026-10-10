//! Bounds-checked translation of gifutils.c. Preserve the first-normal-frame shortcut.
pub fn is_gif(data: &[u8]) -> bool {
    data.starts_with(b"GIF87a") || data.starts_with(b"GIF89a")
}

pub fn rewrite(data: &mut [u8]) {
    if !is_gif(data) {
        return;
    }
    for i in 0..data.len().saturating_sub(8) {
        if data[i..i + 4] == [0, 0x21, 0xf9, 4] && data[i + 8] == 0 {
            // C's signed-byte calculation is intentionally preserved for malformed high bytes.
            let delay = ((data[i + 6] as i8 as i32) << 8) | data[i + 5] as i8 as i32;
            if delay >= 2 {
                break;
            }
            data[i + 5] = 10;
            data[i + 6] = 0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn short_and_malformed() {
        for len in 0..4096 {
            let mut data = vec![0xff; len];
            if len >= 6 {
                data[..6].copy_from_slice(b"GIF89a");
            }
            let expected = data.clone();
            rewrite(&mut data);
            assert_eq!(data, expected);
        }
        let mut data = b"GIF89a\0\x21\xf9\x04\0\x01\0\0\0".to_vec();
        rewrite(&mut data);
        assert_eq!(data[11], 10);
        data[11] = 2;
        rewrite(&mut data);
        assert_eq!(data[11], 2);
    }
}
