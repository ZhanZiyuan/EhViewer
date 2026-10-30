use std::{env, path::PathBuf};

fn main() {
    let root = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/browser.rs");
    println!("cargo:rerun-if-changed=cbindgen.toml");
    let mut config = cbindgen::Config::from_file(root.join("cbindgen.toml"))
        .expect("Cannot read C ABI header configuration");
    // iOS uses Kotlin/Native WebKit, so its generated ABI must not advertise
    // desktop process operations that the linked Apple archive does not export.
    if !matches!(
        env::var("CARGO_CFG_TARGET_OS").as_deref(),
        Ok("macos" | "windows" | "linux")
    ) {
        config.export.exclude.extend(
            [
                "ehv_browser_api_version",
                "ehv_browser_open",
                "ehv_browser_cookies",
                "ehv_browser_close",
                "ehv_browser_process_id",
                "ehv_browser_running",
                "ehv_browser_active",
            ]
            .map(str::to_owned),
        );
    }
    cbindgen::Builder::new()
        .with_crate(root)
        .with_config(config)
        .generate()
        .expect("Cannot generate the Rust C ABI declarations")
        .write_to_file(output.join("ehviewer_capi.h"));
}
