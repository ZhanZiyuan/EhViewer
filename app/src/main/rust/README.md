# Rust native boundary

This Cargo workspace builds **Android only** for the app. Host builds below are regression tools, not additional app platform targets. KMP/CMP and Android bytecode settings remain in Gradle.

- `core/` (`ehviewer_core`): parser/DTOs, image slices and WebP codec FFI, SHA-1, natural sort, GIF bytes, descriptor I/O and safe libarchive sessions. No JNI, `ndk`, `android_logger`, Bitmap or HardwareBuffer dependency.
- Root package (`ehviewer_rust`): static library containing the existing JNI exports. `src/ffi/` translates Java types/errors and owns direct-buffer registrations and Android locks. Feature names `jvm`, `android`, `android-26` are retained.
- `../cpp/CMakeLists.txt`: pinned libarchive/liblzma/Nettle/WebP and Corrosion builds, then links `libehviewer.so`. The generated empty assembly unit is only a CMake/AGP link anchor; no app C algorithm or C JNI wrapper remains.

`ArchiveSession::open` is unsafe only at the file-mapping boundary: the file must remain unmodified/untruncated until the session **and returned mapped page buffers** are gone. Closing/unlinking the caller's descriptor/file is safe. Core sessions are independent; the unchanged Kotlin JNI API still exposes one active session. Concurrent reads retain their own `Arc`. Native libarchive readers move exclusively through a mutex-protected pool; `Reader: Send` is justified against libarchive's thread-safety contract and does not imply `Sync`.

Release uses `panic=unwind` and builds std with `panic-unwind`, so JNI guards can catch Rust panics. Results become the existing null/false/zero archive sentinels, or Java RuntimeException for throwing APIs. Allocation failure or a third-party C fault is not a catchable Rust panic.

A returned ByteBuffer stays alive until `releaseByteBuffer`/`munmap` receives **the original returned object**. Release is idempotent for that object. After release, callers must not access it or its views. Stored pages use independent private COW views; compressed pages use owned allocations. Close/reopen does not invalidate outstanding buffers. Do not truncate a mapped file, including after `closeArchive` while an output view remains live.

Defaults: archive <=8 GiB (also `isize::MAX`), image <=256 MiB, declared regular-file expansion <=64 GiB, <=100,000 headers, filename/password <=4096 bytes, <=4 simultaneous decoders, <=20 cached contexts, <=128/512 MiB published JNI buffers. The latter is an outstanding-buffer budget, not a hard process-RSS cap; in-flight allocations and third-party decoder windows consume additional memory. Unsafe paths and unsupported/oversized inputs fail explicitly.

```sh
# Host development libraries: libarchive + libwebp; macOS may require LIBRARY_PATH.
cargo fmt --all -- --check
cargo test --locked -p ehviewer_core -- --skip test_parse_info --skip test_parse_profile --skip test_parse_gallery_detail
cargo test --locked --features jvm --lib
for target in aarch64-linux-android thumbv7neon-linux-androideabi x86_64-linux-android; do
  cargo clippy --locked --workspace --all-features --target "$target" -- -D warnings
done
# Host-only dynamic JNI library for the unchanged JDK25 clients:
cargo rustc --locked --release --features jvm --lib --crate-type cdylib
```

See `docs/modernization/p5-p7/` from the repository root for API equivalence, executed tests, limitations and reversible changes. Native directory relocation and Kotlin module restructuring remain outside P5–P7.
