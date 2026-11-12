# Rust native boundary

This Cargo workspace builds **Android only** for the app. Host builds below are regression tools, not additional app platform targets. KMP/CMP and Android bytecode settings remain in Gradle.

- `core/` (`ehviewer_core`): parser/DTOs, image slices and WebP codec FFI, SHA-1, natural sort, GIF bytes, descriptor I/O and safe libarchive sessions. No JNI, `ndk`, `android_logger`, Bitmap or HardwareBuffer dependency.
- Root package (`ehviewer_rust`): static library containing the existing JNI exports. `src/ffi/` translates Java types/errors and owns direct-buffer registrations and Android locks. Feature names `jvm`, `android`, `android-26` are retained.
- `../third-party/CMakeLists.txt`: pinned libarchive/liblzma/Nettle/WebP and Corrosion builds, then links `libehviewer.so`. The generated empty assembly unit is only a CMake/AGP link anchor; no app C algorithm or C JNI wrapper remains.

`ArchiveSession::open` is unsafe only at the file-mapping boundary: the file must remain unmodified/untruncated until the session **and returned mapped page buffers** are gone. Closing/unlinking the caller's descriptor/file is safe. The app uses explicit, non-repeating JNI handles for up to four independent readers. Each read takes its own `Arc`; the Kotlin reader lets reads overlap and coordinates close and password changes with a write lock. The old singleton entry points remain for the historical JNI comparison tools. Native libarchive readers move exclusively through a mutex-protected pool; `Reader: Send` is justified against libarchive's thread-safety contract and does not imply `Sync`.

Release uses `panic=unwind` and builds std with `panic-unwind`, so JNI guards can catch Rust panics. Results become the existing null/false/zero archive sentinels, or Java RuntimeException for throwing APIs. Allocation failure or a third-party C fault is not a catchable Rust panic.

A returned ByteBuffer stays alive until `releaseByteBuffer`/`munmap` receives **the original returned object**. Release is idempotent for that object, including after its native address is reused. After release, callers must not access it or its views. Close/reopen does not invalidate outstanding buffers. Do not truncate a mapped file, including after `closeArchive` while an output view remains live.

`ArchiveSession::extract_readonly` returns a `ReadOnlyPageBuffer` with no mutable slice API.
For archives up to 16 MiB, stored pages borrow their validated range in the existing input
mapping and pin its `Arc<Source>`: the complete input is small enough to keep this fast path.
Larger archives use independent immutable page mappings. A bounded input-view cache retains
at most four views/16 MiB; each page also holds a weak slot, so overlapping live leases reuse
the same mapping without pinning every previously visited page. Weak-slot metadata is bounded
by the accepted entry count and is released with the session. Entries larger than 16 MiB
are not cached, but overlapping leases still share their immutable mapping. Eviction releases
only cache ownership; live views survive session/fd close and unlink without keeping the full
input mapping alive. Writable conversion copies these immutable bytes into exclusive storage.
Compressed pages still decode directly into exclusively owned pooled storage. JNI's internal
`extractForReading(index, rewriteGif)` publishes only a real `asReadOnlyBuffer()` result; its
temporary writable constructor wrapper never escapes native code. Duplicates and slices remain
read-only, and native GIF writers also reject read-only registry entries. The cached no-argument
ByteBuffer method ID is pinned by a declaring-class GlobalRef and tested with concurrent first use.

JNI also caches at most four internal read-only Java templates for mapped pages. Each extraction
duplicates a template into a **fresh** read-only Java object, so caller position/limit/order changes
cannot affect another lease. This avoids constructing a new writable JNI wrapper on cache hits.
Templates hold only an opaque weak allocation/range identity from Core, never a native page lease.
A hit is allowed only against the identity of a currently owned `ReadOnlyPageBuffer`; weak control
blocks prevent identity reuse after destruction. Expired templates may retain Java metadata but
are never accessed as data or reused for a different mapping. Closing the reader and releasing
all leases therefore still unmaps the file even while templates remain cached. JNI object creation,
method calls and eviction cleanup happen outside the template-cache lock. Exact-object release
checks and published-buffer accounting remain independent of this cache. Decoded buffers bypass
template caching, and writable GIF consumers retain their original private-storage path.

The large-archive policy limits cached payload residency rather than retaining a growing working
set in the full input mapping. It trades hot page-table reuse for mapping/minor-fault cost on
cache misses; it cannot promise historical C latency for a working set larger than the cache.
Cache byte limits count visible payload, excluding OS mapping/alignment overhead.
Live leases remain pinned, so neither 16 MiB nor the published-buffer budget is a process-RSS
bound. Libarchive metadata/decoder accesses still use the full source mapping. Compare RSS,
clean mapped pages and private/physical footprint separately. All mappings are released after
their last owner, and the existing file-size and published-buffer limits remain unchanged.

On API 28–33, actual GIF magic (including a GIF renamed to .jpg) selects independent writable storage for the existing decoder workaround. A decoded allocation transfers ownership without copying; a stored GIF is copied before modification. Other images are read-only and skip that mutator. The original `extractToByteBuffer` ABI and writable isolation remain available: stored pages use owned copies while a 4-MiB outstanding-copy budget permits, then independent private COW mappings. Released COW views up to 16 MiB are reused only after comparing their entire contents with the immutable source; modified views are discarded. No live writable mapping is shared between leases.

Each session caches at most four decode allocations totaling 16 MiB, four released private views totaling 16 MiB, and a separate read-only input-view cache limited to four views/16 MiB. The input cache is shared by large-archive read-only leases and the original writable copy/comparison paths. Cache lookups refresh recency; a live weak-slot hit needs no cache lookup or recency update. These input views avoid making every copied/read page resident in the full input mapping. Cached bytes, live copies and third-party decoder windows are separate memory costs; these limits are not a process-RSS bound. Mutable decode bytes are refilled before every reused lease is published. Pages larger than the view-cache budget retain the uncached mapping path. The copied path remains preferable for a single small outstanding page; comparison-based private-view reuse targets retained-page pressure rather than claiming zero-copy C parity.

Android memory pressure clears Coil's memory cache, drops idle reader images from the Sieve cache, and trims idle Rust decoder allocations, reader contexts and views. Displayed pages retain their image pins; in-flight native reads and published JNI leases retain their existing ownership. Reader requests bypass Coil's image memory cache to avoid duplicate full-page ownership. A decoded bitmap's allocation (including hardware-backed pixels), two Android animation frame bitmaps and the WebP decoder's canvases are separate from Java object heap. Native pool capacity counts reserved allocations, while mapped virtual size counts address ranges; neither is equivalent to physical RSS/PSS or filesystem page cache.

Ownership is transferred into and out of caches. Source bytes are borrowed for copying/comparison; `Arc::clone` only pins sessions, views or password storage across a lock boundary and does not copy their data. Passwords are no longer deep-cloned when constructing a reader.
Once a private view is found modified, a four-entry admission history temporarily
bypasses reuse for that page. Repeated writers therefore use fresh private maps
instead of repeatedly paying a full comparison before remapping. This is a
bounded optimization hint; correctness always requires either a complete
comparison or a fresh private mapping.

The JNI registry indexes allocation addresses, accounts published bytes incrementally and verifies Java object identity on release. Multiple read-only leases can share one source address; the most recently published identity is checked first, with exact identity checks retained for every older lease. Immutable leases are owned directly by the registration; only writable leases need an extra Arc/mutex for retaining and serializing native mutation. Each published view is conservatively charged its full visible length even when source bytes are shared. The source mapping's virtual length and codec/pool memory are not part of this published-buffer budget. GIF rewrites retain the containing allocation (including sliced views) under a per-allocation mutex; independent allocations do not hold the global registry lock while rewriting. Callers still must not mutate or release their own buffer concurrently with other accesses.

Defaults: archive <=8 GiB (also `isize::MAX`), image <=256 MiB, declared regular-file expansion <=64 GiB, <=100,000 headers, filename/password <=4096 bytes, <=4 simultaneous decoders, <=20 cached contexts, <=128/512 MiB published JNI buffers (pooled allocation capacity is charged, not only the visible slice). The latter is an outstanding-buffer budget, not a hard process-RSS cap; in-flight allocations and third-party decoder windows consume additional memory. Unsafe paths and unsupported/oversized inputs fail explicitly.

```sh
# Host development libraries: libarchive + libwebp; macOS may require LIBRARY_PATH.
cargo fmt --all -- --check
cargo test --locked -p ehviewer_core
cargo test --locked --features jvm --lib
for target in aarch64-linux-android thumbv7neon-linux-androideabi x86_64-linux-android; do
  cargo clippy --locked --workspace --all-features --target "$target" -- -D warnings
done
# Host-only dynamic JNI library for the unchanged JDK25 clients:
cargo rustc --locked --release --features jvm --lib --crate-type cdylib
```

Archive regression fixtures and their licenses are restored locally into the ignored `.local-test-fixtures/` directory from immutable baseline `8fdfb6c5550fab506759a5587b1c8ee4050f507e`. Rust tests restore their inputs automatically; Android test assets are prepared by Gradle. A full Git history is required (`git fetch --unshallow` for shallow clones). Reproducible Kotlin verification tools are documented in `../../tools/verification/README.md`. Migration logs and temporary C comparison drivers are retained locally outside the repository.
