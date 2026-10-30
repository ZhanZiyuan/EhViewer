use std::{env, path::PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=EHVIEWER_IOS_SESSION_TESTS");
    if env::var("EHVIEWER_IOS_SESSION_TESTS").as_deref() != Ok("1") {
        return;
    }
    assert_eq!(env::var("TARGET").unwrap(), "aarch64-apple-ios-sim");
    let entitlements = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap())
        .join("../../../app/ios/Simulator.entitlements")
        .canonicalize()
        .expect("Missing Simulator-only session test identity");
    println!("cargo:rerun-if-changed={}", entitlements.display());
    for argument in ["-sectcreate", "__TEXT", "__entitlements"] {
        println!("cargo:rustc-link-arg=-Xlinker");
        println!("cargo:rustc-link-arg={argument}");
    }
    println!("cargo:rustc-link-arg=-Xlinker");
    println!("cargo:rustc-link-arg={}", entitlements.display());
}
