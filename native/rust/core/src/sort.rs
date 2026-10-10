/* -*- mode: c; c-file-style: "k&r" -*-

  strnatcmp.c -- Perform 'natural order' comparisons of strings in C.
  Copyright (C) 2000, 2004 by Martin Pool <mbp sourcefrog net>

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.
*/

// Altered version: Rust translation of EhViewer's strnatcmp.c (Martin Pool).
use std::cmp::Ordering;

pub fn natural_cmp(a: &[u8], b: &[u8]) -> Ordering {
    // NDK uses unsigned char on ARM, signed char on x86. Keep existing ABI ordering.
    natural_cmp_with_signed_char(
        a,
        b,
        !cfg!(all(
            target_os = "android",
            any(target_arch = "arm", target_arch = "aarch64")
        )),
    )
}

pub fn natural_cmp_with_signed_char(a: &[u8], b: &[u8], signed: bool) -> Ordering {
    fn at(s: &[u8], i: usize) -> u8 {
        s.get(i).copied().unwrap_or(0)
    }
    fn skip(s: &[u8], i: usize) -> bool {
        matches!(at(s, i), b' ' | b'\t' | b'\n' | b'\r' | 11 | 12)
            || at(s, i) == b'0' && at(s, i + 1).is_ascii_digit()
    }
    let (mut i, mut j) = (0, 0);
    loop {
        while skip(a, i) {
            i += 1;
        }
        while skip(b, j) {
            j += 1;
        }
        if at(a, i).is_ascii_digit() && at(b, j).is_ascii_digit() {
            let (mut ai, mut bj, mut bias) = (i, j, Ordering::Equal);
            loop {
                match (at(a, ai).is_ascii_digit(), at(b, bj).is_ascii_digit()) {
                    (false, false) => break,
                    (false, true) => return Ordering::Less,
                    (true, false) => return Ordering::Greater,
                    _ => {
                        if bias == Ordering::Equal {
                            bias = at(a, ai).cmp(&at(b, bj));
                        }
                    }
                }
                ai += 1;
                bj += 1;
            }
            if bias != Ordering::Equal {
                return bias;
            }
            i = ai;
            j = bj;
            continue;
        }
        let (ac, bc) = (at(a, i), at(b, j));
        let cmp = if signed {
            (ac as i8).cmp(&(bc as i8))
        } else {
            ac.cmp(&bc)
        };
        if cmp != Ordering::Equal || ac == 0 {
            return cmp;
        }
        i += 1;
        j += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_oracle_all_pairs() {
        let oracle: serde_json::Value = serde_json::from_slice(
            &std::fs::read(crate::test_fixtures::fixture("sort-oracle.json")).unwrap(),
        )
        .unwrap();
        let inputs = oracle["inputs"].as_array().unwrap();
        for (i, a) in inputs.iter().enumerate() {
            for (j, b) in inputs.iter().enumerate() {
                let result = natural_cmp_with_signed_char(
                    a.as_str().unwrap().as_bytes(),
                    b.as_str().unwrap().as_bytes(),
                    true,
                ) as i32;
                assert_eq!(result as i64, oracle["signs"][i][j].as_i64().unwrap());
            }
        }
        assert_eq!(
            natural_cmp_with_signed_char("页2".as_bytes(), b"2", false),
            Ordering::Greater
        );
    }
}
