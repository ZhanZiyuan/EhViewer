//! JSON stdin/stdout probe used by scripts/native_differential.py (host only).
use ehviewer_core::{gif, sort::natural_cmp_with_signed_char};
use std::io::{self, Read};
fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).unwrap();
    let request: serde_json::Value = serde_json::from_str(&input).unwrap();
    let names = request["names"].as_array().unwrap();
    let signed = request["signed"].as_bool().unwrap();
    let signs: Vec<Vec<i32>> = names
        .iter()
        .map(|a| {
            names
                .iter()
                .map(|b| {
                    natural_cmp_with_signed_char(
                        a.as_str().unwrap().as_bytes(),
                        b.as_str().unwrap().as_bytes(),
                        signed,
                    ) as i32
                })
                .collect()
        })
        .collect();
    let gifs: Vec<Vec<u8>> = request["gifs"]
        .as_array()
        .unwrap()
        .iter()
        .map(|g| {
            let mut data: Vec<u8> = g
                .as_array()
                .unwrap()
                .iter()
                .map(|b| b.as_u64().unwrap() as u8)
                .collect();
            gif::rewrite(&mut data);
            data
        })
        .collect();
    println!("{}", serde_json::json!({"signs":signs,"gifs":gifs}));
}
