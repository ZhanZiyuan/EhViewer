fn main() {
    // Android dependencies are built and linked by CMake/Corrosion. Host JNI regression uses
    // the installed development libraries; Core itself has no Android dependencies.
    if std::env::var("CARGO_CFG_TARGET_OS").unwrap() != "android" {
        // GNU ld scans static archives in order: demux references decoder symbols in webp.
        println!("cargo:rustc-link-lib=webpdemux");
        println!("cargo:rustc-link-lib=webp");
    }
}
