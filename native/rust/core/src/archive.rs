//! Safe sessions around libarchive. No JNI or Android types occur in this module.
//! Shared caches use short locks; each libarchive handle remains exclusively owned.
mod buffer;
mod stored;
mod sys;
use anyhow::{Result, anyhow, bail, ensure};
pub use buffer::PooledBuffer;
use buffer::{BufferPool, CACHE_BYTES};
use memmap2::{Mmap, MmapMut, MmapOptions};
use std::ffi::{CStr, CString};
use std::fs::File;
use std::io::{Read, Write};
#[cfg(unix)]
use std::os::fd::RawFd;
use std::ptr::NonNull;
use std::sync::{Arc, Condvar, Mutex, Weak};
pub use stored::StoredBuffer;
use stored::StoredPool;
use sys::*;

const OK: i32 = 0;
const EOF: i32 = 1;
const REG: Mode = 0o100000;
const POOL_SIZE: usize = 20;
const MAX_PARALLEL: usize = 4;

#[derive(Clone, Copy)]
pub struct Limits {
    pub archive_bytes: u64,
    pub entry_bytes: u64,
    pub total_bytes: u64,
    pub entries: usize,
}
impl Default for Limits {
    fn default() -> Self {
        Self {
            archive_bytes: 8 << 30,
            entry_bytes: 256 << 20,
            total_bytes: 64 << 30,
            entries: 100_000,
        }
    }
}

struct Source {
    file: File,
    map: Mmap,
    views: Mutex<Vec<(u64, Arc<Mmap>)>>,
}
impl Source {
    fn stored_view(&self, offset: u64, len: usize) -> Result<Arc<Mmap>> {
        let mut views = self
            .views
            .lock()
            .map_err(|_| anyhow!("View cache lock poisoned"))?;
        if let Some(index) = views
            .iter()
            .position(|(start, map)| *start == offset && map.len() == len)
        {
            let cached = views.remove(index);
            let map = Arc::clone(&cached.1);
            views.push(cached);
            return Ok(map);
        }
        ensure!(len <= CACHE_BYTES, "Stored view exceeds cache budget");
        // SAFETY: offset/length come from a validated libarchive block; the same immutable-file
        // contract as Source.map applies. Only immutable slices/read-only Java views escape.
        let map = Arc::new(unsafe { MmapOptions::new().offset(offset).len(len).map(&self.file)? });
        while views.len() >= MAX_PARALLEL
            || views.iter().map(|(_, map)| map.len()).sum::<usize>() > CACHE_BYTES - len
        {
            views.remove(0);
        }
        views.push((offset, Arc::clone(&map)));
        Ok(map)
    }
}
struct Reader {
    handle: NonNull<Archive>,
    source: Arc<Source>,
    next: usize,
}

// SAFETY: libarchive's README documents independent objects as thread safe but not thread aware.
// This handle owns its input mapping, uses no callbacks/thread-local state or write_disk APIs,
// and moves only with exclusive ownership. A pool transfers ownership under a Mutex; no Sync
// implementation is provided. All operations (including Drop) require exclusive access.
unsafe impl Send for Reader {}
impl Drop for Reader {
    fn drop(&mut self) {
        unsafe {
            archive_read_free(self.handle.as_ptr());
        }
    }
}
fn native_error(a: *mut Archive) -> anyhow::Error {
    // SAFETY: error_string belongs to the live handle and is copied before any subsequent call.
    let p = unsafe { archive_error_string(a) };
    if p.is_null() {
        anyhow!("libarchive operation failed")
    } else {
        anyhow!(unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
    }
}
impl Reader {
    fn check(&self, status: i32) -> Result<()> {
        if status < OK {
            Err(native_error(self.handle.as_ptr()))
        } else {
            Ok(())
        }
    }
    fn new(source: Arc<Source>, password: Option<&CString>) -> Result<Self> {
        // SAFETY: all subsequent FFI uses a live exclusively owned handle. The mapping is pinned
        // by source until after archive_read_free, including on every error/early return.
        let handle = NonNull::new(unsafe { archive_read_new() })
            .ok_or_else(|| anyhow!("archive allocation failed"))?;
        let reader = Self {
            handle,
            source,
            next: 0,
        };
        let a = handle.as_ptr();
        unsafe {
            reader.check(archive_read_support_format_tar(a))?;
            reader.check(archive_read_support_format_7zip(a))?;
            reader.check(archive_read_support_format_rar5(a))?;
            reader.check(archive_read_support_format_zip(a))?;
            reader.check(archive_read_support_filter_gzip(a))?;
            reader.check(archive_read_support_filter_xz(a))?;
            reader.check(archive_read_set_option(
                a,
                c"zip".as_ptr(),
                c"ignorecrc32".as_ptr(),
                c"1".as_ptr(),
            ))?;
            if let Some(p) = password {
                reader.check(archive_read_add_passphrase(a, p.as_ptr()))?;
            }
            reader.check(archive_read_open_memory(
                a,
                reader.source.map.as_ptr().cast(),
                reader.source.map.len(),
            ))?;
        }
        Ok(reader)
    }
    fn header(&mut self) -> Result<Option<*mut sys::Entry>> {
        let mut entry = std::ptr::null_mut();
        let status = unsafe { archive_read_next_header(self.handle.as_ptr(), &mut entry) };
        if status == EOF {
            return Ok(None);
        }
        self.check(status)?;
        ensure!(!entry.is_null(), "Missing archive header");
        self.next += 1;
        Ok(Some(entry))
    }
    fn seek(&mut self, index: usize) -> Result<()> {
        ensure!(self.next <= index, "Reader cannot seek backwards");
        while self.next <= index {
            ensure!(self.header()?.is_some(), "Missing archive entry");
        }
        Ok(())
    }
    fn read(&mut self, out: &mut [u8]) -> Result<usize> {
        let n =
            unsafe { archive_read_data(self.handle.as_ptr(), out.as_mut_ptr().cast(), out.len()) };
        ensure!(n >= 0, "{}", native_error(self.handle.as_ptr()));
        ensure!(n as usize <= out.len(), "Invalid archive read size");
        Ok(n as usize)
    }
    fn copy_to(&mut self, size: u64, mut output: impl Write) -> Result<()> {
        let mut buffer = [0; 65536];
        let mut remaining = size;
        while remaining > 0 {
            let n = self.read(&mut buffer[..remaining.min(65536) as usize])?;
            ensure!(n != 0, "Truncated archive entry");
            output.write_all(&buffer[..n])?;
            remaining -= n as u64;
        }
        // Verify EOF/authentication tag and prohibit expansion beyond the declared size.
        ensure!(
            self.read(&mut buffer[..1])? == 0,
            "Entry exceeds declared size"
        );
        Ok(())
    }
    fn read_into(&mut self, size: usize, output: &mut Vec<u8>) -> Result<()> {
        output.clear();
        ensure!(output.capacity() >= size, "Decode buffer is too small");
        while output.len() < size {
            let start = output.len();
            let remaining = size - start;
            // SAFETY: the reserved spare capacity has a stable address and no live u8 references.
            // libarchive only writes the supplied region. Expose exactly the initialized prefix
            // after validating its reported count; on failure the Vec length stays unchanged.
            let n = unsafe {
                archive_read_data(
                    self.handle.as_ptr(),
                    output.spare_capacity_mut().as_mut_ptr().cast(),
                    remaining,
                )
            };
            ensure!(n >= 0, "{}", native_error(self.handle.as_ptr()));
            ensure!(
                n > 0 && n as usize <= remaining,
                "Truncated/invalid archive read"
            );
            unsafe { output.set_len(start + n as usize) };
        }
        // Preserve the authentication trailer and declared expansion-size check.
        ensure!(self.read(&mut [0])? == 0, "Entry exceeds declared size");
        Ok(())
    }
}

struct Page {
    name: Vec<u8>,
    header: usize,
    size: u64,
    direct: Option<(u64, usize)>,
    // Weak ownership avoids pinning every visited page; live leases still share one immutable view.
    readonly: Mutex<Weak<Mmap>>,
    encrypted: bool,
}
struct State {
    password: Option<Arc<CString>>,
    generation: u64,
    pool: Vec<Reader>,
}
pub struct ArchiveSession {
    source: Arc<Source>,
    pages: Vec<Page>,
    encrypted: bool,
    last_header: usize,
    state: Mutex<State>,
    running: Mutex<usize>,
    available: Condvar,
    buffers: Arc<BufferPool>,
    stored: Arc<StoredPool>,
}
struct Slot<'a>(&'a ArchiveSession);
impl Drop for Slot<'_> {
    fn drop(&mut self) {
        *self.0.running.lock().unwrap_or_else(|e| e.into_inner()) -= 1;
        self.0.available.notify_one();
    }
}

/// Exclusively owned output leases survive session closure. Large stored pages use independent
/// COW views; bounded reusable storage avoids mapping faults for smaller pages.
pub enum PageBuffer {
    Owned(PooledBuffer),
    Mapped(MmapMut),
    Stored(StoredBuffer),
}

/// Immutable output with an ownership pin, rather than a borrowed session lifetime. Stored pages
/// share the validated input mapping; decoded pages retain their exclusively owned allocation.
/// No mutable view of either representation is exposed by this type.
pub struct ReadOnlyPageBuffer(ReadOnlyStorage);
enum ReadOnlyStorage {
    Source {
        source: Arc<Source>,
        offset: usize,
        len: usize,
    },
    View(Arc<Mmap>),
    Decoded(PooledBuffer),
}
/// Weak allocation/range identity, never a data pointer or an owner of mapped bytes.
/// Its control block prevents allocation-identity reuse while cached metadata survives.
pub struct ReadOnlyIdentity(IdentityStorage);
enum IdentityStorage {
    Source(Weak<Source>, usize, usize),
    View(Weak<Mmap>),
}
impl ReadOnlyIdentity {
    /// Equality observes identity only. Access still requires a live ReadOnlyPageBuffer lease.
    pub fn same_backing(&self, other: &Self) -> bool {
        match (&self.0, &other.0) {
            (IdentityStorage::Source(a, ao, al), IdentityStorage::Source(b, bo, bl)) => {
                Weak::ptr_eq(a, b) && ao == bo && al == bl
            }
            (IdentityStorage::View(a), IdentityStorage::View(b)) => Weak::ptr_eq(a, b),
            _ => false,
        }
    }
}
impl ReadOnlyPageBuffer {
    /// Identity for metadata caches. Decoded allocations are exclusive and have no shared key.
    pub fn mapping_identity(&self) -> Option<ReadOnlyIdentity> {
        match &self.0 {
            ReadOnlyStorage::Source {
                source,
                offset,
                len,
            } => Some(ReadOnlyIdentity(IdentityStorage::Source(
                Arc::downgrade(source),
                *offset,
                *len,
            ))),
            ReadOnlyStorage::View(map) => {
                Some(ReadOnlyIdentity(IdentityStorage::View(Arc::downgrade(map))))
            }
            ReadOnlyStorage::Decoded(_) => None,
        }
    }
    pub fn as_slice(&self) -> &[u8] {
        match &self.0 {
            ReadOnlyStorage::Source {
                source,
                offset,
                len,
            } => &source.map[*offset..*offset + *len],
            ReadOnlyStorage::View(map) => map,
            ReadOnlyStorage::Decoded(buffer) => &buffer.bytes,
        }
    }
    pub fn retained_bytes(&self) -> usize {
        match &self.0 {
            ReadOnlyStorage::Source { len, .. } => *len,
            ReadOnlyStorage::View(map) => map.len(),
            ReadOnlyStorage::Decoded(buffer) => buffer.bytes.capacity(),
        }
    }
    /// GIF patching is the exceptional writable consumer. Decoded storage transfers ownership;
    /// a mapped source is copied into an independent allocation before any writes are possible.
    pub fn into_writable(self) -> Result<PageBuffer> {
        match self.0 {
            ReadOnlyStorage::Decoded(buffer) => Ok(PageBuffer::Owned(buffer)),
            ReadOnlyStorage::Source {
                source,
                offset,
                len,
            } => {
                let pool = BufferPool::new();
                let mut output = pool.acquire(len)?;
                output
                    .bytes
                    .extend_from_slice(&source.map[offset..offset + len]);
                Ok(PageBuffer::Owned(output))
            }
            ReadOnlyStorage::View(map) => {
                let pool = BufferPool::new();
                let mut output = pool.acquire(map.len())?;
                output.bytes.extend_from_slice(&map);
                Ok(PageBuffer::Owned(output))
            }
        }
    }
}
impl PageBuffer {
    pub fn retained_bytes(&self) -> usize {
        match self {
            Self::Owned(buffer) => buffer.bytes.capacity(),
            Self::Mapped(map) => map.len(),
            Self::Stored(buffer) => buffer.len(),
        }
    }
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        match self {
            Self::Owned(b) => &mut b.bytes,
            Self::Mapped(b) => b,
            Self::Stored(b) => b.as_mut_slice(),
        }
    }
}
fn playable(name: &[u8]) -> bool {
    name.rsplit(|&b| b == b'.').next().is_some_and(|ext| {
        [
            b"jpeg".as_slice(),
            b"jpg",
            b"png",
            b"gif",
            b"webp",
            b"bmp",
            b"ico",
            b"wbmp",
            b"heic",
            b"heif",
            b"avif",
        ]
        .contains(&ext)
    }) && name.contains(&b'.')
}
pub fn safe_path(name: &[u8]) -> bool {
    !name.is_empty()
        && name.len() <= 4096
        && !name.contains(&0)
        && !name.contains(&b'\\')
        && !name.contains(&b':')
        && !name.starts_with(b"/")
        && !name
            .split(|&b| b == b'/')
            .any(|p| p == b".." || p.is_empty())
}
impl ArchiveSession {
    /// # Safety
    /// The backing file must remain unmodified/untruncated until this session and every returned
    /// mapped lease (writable or read-only) has been dropped. Closing/unlinking the caller's fd is safe.
    pub unsafe fn open(file: File, size: u64, sort: bool, limits: Limits) -> Result<Self> {
        ensure!(
            size > 0 && size <= limits.archive_bytes && size <= isize::MAX as u64,
            "Archive size exceeds limit"
        );
        ensure!(
            size <= file.metadata()?.len(),
            "Archive exceeds file length"
        );
        // SAFETY: read-only mmap; the application must not truncate/modify its archive while open,
        // the same file stability contract as the original mmap JNI. File is retained by Source.
        let map = unsafe { MmapOptions::new().len(size as usize).map(&file)? };
        let source = Arc::new(Source {
            file,
            map,
            views: Mutex::new(Vec::with_capacity(MAX_PARALLEL)),
        });
        let mut reader = Reader::new(Arc::clone(&source), None)?;
        let mut pages = Vec::new();
        let (mut count, mut total, mut probe) = (0usize, 0u64, true);
        while let Some(entry) = reader.header()? {
            count += 1;
            ensure!(count <= limits.entries, "Too many archive entries");
            // SAFETY: header fields remain valid until the next header operation. Name is copied.
            let (kind, name, length) = unsafe {
                (
                    archive_entry_filetype(entry),
                    archive_entry_pathname(entry),
                    archive_entry_size(entry),
                )
            };
            if kind != REG || name.is_null() {
                continue;
            }
            let name = unsafe { CStr::from_ptr(name) }.to_bytes();
            ensure!(safe_path(name), "Unsafe archive path");
            ensure!(length >= 0, "Unknown/negative entry length");
            total = total
                .checked_add(length as u64)
                .ok_or_else(|| anyhow!("Archive size overflow"))?;
            ensure!(
                total <= limits.total_bytes,
                "Archive expansion exceeds limit"
            );
            if !playable(name) {
                continue;
            }
            ensure!(
                length as u64 <= limits.entry_bytes && length as u64 <= i32::MAX as u64,
                "Page exceeds size limit"
            );
            let mut page = Page {
                name: name.to_vec(),
                header: reader.next - 1,
                size: length as u64,
                direct: None,
                readonly: Mutex::new(Weak::new()),
                encrypted: unsafe { archive_entry_is_encrypted(entry) } != 0,
            };
            if probe && unsafe { archive_entry_is_encrypted(entry) } == 0 && length > 0 {
                let (mut ptr, mut len, mut offset) = (std::ptr::null(), 0, 0);
                let status = unsafe {
                    archive_read_data_block(reader.handle.as_ptr(), &mut ptr, &mut len, &mut offset)
                };
                let base = source.map.as_ptr() as usize;
                if status == OK
                    && offset == 0
                    && len == length as usize
                    && (ptr as usize) >= base
                    && (ptr as usize - base)
                        .checked_add(len)
                        .is_some_and(|end| end <= source.map.len())
                {
                    page.direct = Some(((ptr as usize - base) as u64, len));
                } else {
                    probe = false;
                }
            }
            pages.push(page);
        }
        let encrypted = unsafe { archive_read_has_encrypted_entries(reader.handle.as_ptr()) } == 1;
        let last_header = pages.last().map_or(0, |page| page.header);
        if sort {
            pages.sort_by(|a, b| crate::sort::natural_cmp(&a.name, &b.name));
        }
        Ok(Self {
            source,
            pages,
            encrypted,
            last_header,
            state: Mutex::new(State {
                password: None,
                generation: 0,
                pool: Vec::new(),
            }),
            running: Mutex::new(0),
            available: Condvar::new(),
            buffers: BufferPool::new(),
            stored: StoredPool::new(),
        })
    }
    pub fn len(&self) -> usize {
        self.pages.len()
    }
    pub fn is_empty(&self) -> bool {
        self.pages.is_empty()
    }
    pub fn needs_password(&self) -> bool {
        self.encrypted
    }
    pub fn extension(&self, index: usize) -> Result<&str> {
        let page = self
            .pages
            .get(index)
            .ok_or_else(|| anyhow!("Invalid page index"))?;
        Ok(std::str::from_utf8(
            page.name.rsplit(|&b| b == b'.').next().unwrap_or_default(),
        )?)
    }
    fn slot(&self) -> Result<Slot<'_>> {
        let mut running = self
            .running
            .lock()
            .map_err(|_| anyhow!("Archive lock poisoned"))?;
        while *running >= MAX_PARALLEL {
            running = self
                .available
                .wait(running)
                .map_err(|_| anyhow!("Archive lock poisoned"))?;
        }
        *running += 1;
        Ok(Slot(self))
    }
    fn with_reader<T>(&self, page: &Page, f: impl FnOnce(&mut Reader) -> Result<T>) -> Result<T> {
        let _slot = self.slot()?;
        let (cached, password, generation) = {
            let mut state = self
                .state
                .lock()
                .map_err(|_| anyhow!("Archive lock poisoned"))?;
            let best = state
                .pool
                .iter()
                .enumerate()
                .filter(|(_, r)| r.next <= page.header)
                .max_by_key(|(_, r)| r.next)
                .map(|(i, _)| i);
            let cached = best.map(|i| state.pool.swap_remove(i));
            // A new reader needs a password lease outside this lock, not a copied CString.
            let password = cached
                .is_none()
                .then(|| state.password.as_ref().map(Arc::clone))
                .flatten();
            (cached, password, state.generation)
        };
        let mut reader = match cached {
            Some(r) => r,
            None => Reader::new(Arc::clone(&self.source), password.as_deref())?,
        };
        reader.seek(page.header)?;
        let result = f(&mut reader);
        // A forward-only reader past the last playable header can never serve another page.
        // Drop it now instead of retaining unusable decoder windows in the context pool.
        if result.is_ok() && reader.next <= self.last_header {
            let mut state = self
                .state
                .lock()
                .map_err(|_| anyhow!("Archive lock poisoned"))?;
            if state.generation == generation {
                if state.pool.len() >= POOL_SIZE {
                    state.pool.remove(0);
                }
                state.pool.push(reader);
            }
        }
        result
    }
    pub fn extract(&self, index: usize) -> Result<PageBuffer> {
        let page = self
            .pages
            .get(index)
            .ok_or_else(|| anyhow!("Invalid page index"))?;
        if let Some((offset, len)) = page.direct {
            if let Some(mut output) = self.buffers.acquire_stored(len)? {
                output
                    .bytes
                    .extend_from_slice(&self.source.stored_view(offset, len)?);
                return Ok(PageBuffer::Owned(output));
            }
            if len <= CACHE_BYTES {
                return Ok(PageBuffer::Stored(self.stored.acquire(
                    &self.source,
                    offset,
                    len,
                )?));
            }
            // SAFETY: private independent view with retained file, validated bounds from libarchive.
            return Ok(PageBuffer::Mapped(unsafe {
                MmapOptions::new()
                    .offset(offset)
                    .len(len)
                    .map_copy(&self.source.file)?
            }));
        }
        Ok(PageBuffer::Owned(self.decode(page)?))
    }
    fn decode(&self, page: &Page) -> Result<PooledBuffer> {
        self.with_reader(page, |reader| {
            let mut output = self.buffers.acquire(page.size as usize)?;
            reader.read_into(page.size as usize, &mut output.bytes)?;
            Ok(output)
        })
    }
    pub fn extract_readonly(&self, index: usize) -> Result<ReadOnlyPageBuffer> {
        let page = self
            .pages
            .get(index)
            .ok_or_else(|| anyhow!("Invalid page index"))?;
        if let Some((offset, len)) = page.direct {
            if self.source.map.len() > CACHE_BYTES {
                // A full-source borrow makes every touched payload stay mapped in one growing
                // working set. Bound cached views for large archives; weak per-page ownership
                // still reuses live leases without retaining pages after cache eviction.
                let mut slot = page
                    .readonly
                    .lock()
                    .map_err(|_| anyhow!("Read-only view lock poisoned"))?;
                let map = match slot.upgrade() {
                    Some(map) => map,
                    None => {
                        let map = if len <= CACHE_BYTES {
                            self.source.stored_view(offset, len)?
                        } else {
                            // SAFETY: this is a validated immutable file range. The map owns its
                            // OS mapping independently of the reader/fd; only immutable slices escape.
                            Arc::new(unsafe {
                                MmapOptions::new()
                                    .offset(offset)
                                    .len(len)
                                    .map(&self.source.file)?
                            })
                        };
                        *slot = Arc::downgrade(&map);
                        map
                    }
                };
                return Ok(ReadOnlyPageBuffer(ReadOnlyStorage::View(map)));
            }
            // The range was validated against this exact mapping during open. Arc pins the file
            // and mapping after close/unlink; readers share bytes but cannot obtain &mut [u8].
            return Ok(ReadOnlyPageBuffer(ReadOnlyStorage::Source {
                source: Arc::clone(&self.source),
                offset: offset as usize,
                len,
            }));
        }
        Ok(ReadOnlyPageBuffer(ReadOnlyStorage::Decoded(
            self.decode(page)?,
        )))
    }
    pub fn extract_to(&self, index: usize, mut output: impl Write) -> Result<()> {
        let page = self
            .pages
            .get(index)
            .ok_or_else(|| anyhow!("Invalid page index"))?;
        if let Some((offset, len)) = page.direct {
            if len <= CACHE_BYTES {
                let _slot = self.slot()?;
                output.write_all(&self.source.stored_view(offset, len)?)?;
            } else {
                // Large copies stream through libarchive rather than growing the view cache.
                self.with_reader(page, |reader| reader.copy_to(page.size, &mut output))?;
            }
            Ok(())
        } else {
            self.with_reader(page, |reader| reader.copy_to(page.size, output))
        }
    }
    pub fn provide_password(&self, password: &str) -> Result<bool> {
        ensure!(password.len() <= 4096, "Password too long");
        let candidate = CString::new(password)?;
        if !self.encrypted {
            return Ok(true);
        }
        let _slot = self.slot()?;
        let mut reader = Reader::new(Arc::clone(&self.source), Some(&candidate))?;
        // Validate one complete encrypted page, including its authentication trailer. Traversing
        // every encrypted image here would make password entry proportional to the entire gallery.
        if let Some(page) = self
            .pages
            .iter()
            .filter(|p| p.encrypted)
            .min_by_key(|p| p.header)
        {
            reader.seek(page.header)?;
            if reader.copy_to(page.size, std::io::sink()).is_err() {
                return Ok(false);
            }
        }
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow!("Archive lock poisoned"))?;
        state.password = Some(Arc::new(candidate));
        state.generation = state.generation.wrapping_add(1);
        state.pool.clear();
        Ok(true)
    }
}

struct Writer(NonNull<Archive>);
impl Drop for Writer {
    fn drop(&mut self) {
        unsafe {
            archive_write_free(self.0.as_ptr());
        }
    }
}
struct WriteEntry(NonNull<sys::Entry>);
impl Drop for WriteEntry {
    fn drop(&mut self) {
        unsafe {
            archive_entry_free(self.0.as_ptr());
        }
    }
}
impl Writer {
    fn check(&self, status: i32) -> Result<()> {
        if status < OK {
            Err(native_error(self.0.as_ptr()))
        } else {
            Ok(())
        }
    }
}
/// ZIP/store writer. Caller owns fd; reads each input from its current offset and never closes it.
#[cfg(unix)]
pub fn write_zip(inputs: &mut [(File, String)], output: RawFd) -> Result<()> {
    ensure!(output >= 0, "Invalid ZIP output descriptor");
    write_zip_to(inputs, |a| unsafe { archive_write_open_fd(a, output) })
}

struct FileOutput<'a> {
    file: &'a mut File,
    error: Option<std::io::Error>,
}

unsafe extern "C" fn write_file(
    _: *mut Archive,
    data: *mut std::ffi::c_void,
    bytes: *const std::ffi::c_void,
    len: usize,
) -> isize {
    // SAFETY: write_zip_file keeps this exclusive context alive until archive_write_free returns.
    let output = unsafe { &mut *data.cast::<FileOutput<'_>>() };
    if output.error.is_some() {
        return -1;
    }
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if len > isize::MAX as usize || (len > 0 && bytes.is_null()) {
            return Err(std::io::Error::other("Invalid libarchive output buffer"));
        }
        if len > 0 {
            // SAFETY: libarchive supplies its live read-only buffer for this callback only.
            output
                .file
                .write_all(unsafe { std::slice::from_raw_parts(bytes.cast(), len) })?;
        }
        Ok(())
    }));
    match result {
        Ok(Ok(())) => len as isize,
        Ok(Err(error)) => {
            output.error = Some(error);
            -1
        }
        Err(_) => {
            output.error = Some(std::io::Error::other("ZIP output callback panicked"));
            -1
        }
    }
}

/// Portable ZIP/store writer. Borrows the output file, preserves its ownership, and writes from
/// its current offset. Inputs retain the same current-offset semantics as the Unix fd API.
pub fn write_zip_file(inputs: &mut [(File, String)], output: &mut File) -> Result<()> {
    let mut context = FileOutput {
        file: output,
        error: None,
    };
    let result = write_zip_to(inputs, |a| unsafe {
        archive_write_open2(
            a,
            (&mut context as *mut FileOutput<'_>).cast(),
            None,
            Some(write_file),
            None,
            None,
        )
    });
    // write_zip_to has freed the writer (including any final callback) before context is inspected
    // or dropped. Preserve the actual I/O error instead of libarchive's generic callback failure.
    match context.error {
        Some(error) => Err(error.into()),
        None => result,
    }
}

fn write_zip_to(
    inputs: &mut [(File, String)],
    open: impl FnOnce(*mut Archive) -> i32,
) -> Result<()> {
    ensure!(
        inputs.len() <= Limits::default().entries,
        "Invalid ZIP writer arguments"
    );
    for (file, name) in inputs.iter() {
        ensure!(
            safe_path(name.as_bytes()) && file.metadata()?.is_file(),
            "Unsafe ZIP input"
        );
    }
    let writer = Writer(
        NonNull::new(unsafe { archive_write_new() })
            .ok_or_else(|| anyhow!("archive allocation failed"))?,
    );
    let a = writer.0.as_ptr();
    unsafe {
        writer.check(archive_write_set_format_zip(a))?;
        writer.check(archive_write_zip_set_compression_store(a))?;
        writer.check(open(a))?;
    }
    for (file, name) in inputs {
        let name = CString::new(name.as_bytes())?;
        // SEEK_CUR yields the size remaining, preserving fd stream semantics for partially read files.
        let position = std::io::Seek::stream_position(file)?;
        let length = file
            .metadata()?
            .len()
            .checked_sub(position)
            .ok_or_else(|| anyhow!("Input offset beyond EOF"))?;
        ensure!(length <= i64::MAX as u64, "ZIP input too large");
        let e = WriteEntry(
            NonNull::new(unsafe { archive_entry_new() })
                .ok_or_else(|| anyhow!("entry allocation failed"))?,
        );
        unsafe {
            archive_entry_set_pathname(e.0.as_ptr(), name.as_ptr());
            archive_entry_set_size(e.0.as_ptr(), length as i64);
            // The setter takes unsigned int even where the filetype getter returns a u16 mode.
            archive_entry_set_filetype(e.0.as_ptr(), 0o100000);
            archive_entry_set_perm(e.0.as_ptr(), 0o644);
            writer.check(archive_write_header(a, e.0.as_ptr()))?;
        }
        let mut remaining = length;
        let mut buffer = [0; 65536];
        while remaining > 0 {
            let n = file.read(&mut buffer[..remaining.min(65536) as usize])?;
            ensure!(n > 0, "ZIP input truncated");
            let mut done = 0;
            while done < n {
                let count =
                    unsafe { archive_write_data(a, buffer[done..n].as_ptr().cast(), n - done) };
                if count <= 0 {
                    bail!("{}", native_error(a));
                }
                ensure!(count as usize <= n - done, "Invalid ZIP write size");
                done += count as usize;
            }
            remaining -= n as u64;
        }
        writer.check(unsafe { archive_write_finish_entry(a) })?;
    }
    writer.check(unsafe { archive_write_close(a) })
}

#[cfg(test)]
mod view_tests {
    use super::*;

    #[test]
    fn weak_identities_include_range_and_do_not_retain_source_or_allow_owner_reuse() {
        let path =
            std::env::temp_dir().join(format!("ehviewer-readonly-id-{}", std::process::id()));
        File::create(&path).unwrap().set_len(32).unwrap();
        let source = || {
            let file = File::open(&path).unwrap();
            Arc::new(Source {
                // SAFETY: owned test file remains stable until every mapping is dropped.
                map: unsafe { MmapOptions::new().map(&file) }.unwrap(),
                file,
                views: Mutex::new(Vec::new()),
            })
        };
        let first = source();
        let weak = Arc::downgrade(&first);
        let lease = |source: &Arc<Source>, offset, len| {
            ReadOnlyPageBuffer(ReadOnlyStorage::Source {
                source: Arc::clone(source),
                offset,
                len,
            })
        };
        let key = lease(&first, 1, 8).mapping_identity().unwrap();
        assert!(key.same_backing(&lease(&first, 1, 8).mapping_identity().unwrap()));
        assert!(!key.same_backing(&lease(&first, 2, 8).mapping_identity().unwrap()));
        assert!(!key.same_backing(&lease(&first, 1, 7).mapping_identity().unwrap()));
        drop(first);
        assert!(weak.upgrade().is_none());
        let replacement = source();
        assert!(!key.same_backing(&lease(&replacement, 1, 8).mapping_identity().unwrap()));
        // SAFETY: same owned stable file; this independent read-only map is dropped before unlink.
        let view = Arc::new(unsafe { MmapOptions::new().map(&replacement.file) }.unwrap());
        let weak_view = Arc::downgrade(&view);
        let mapped = ReadOnlyPageBuffer(ReadOnlyStorage::View(view));
        let view_key = mapped.mapping_identity().unwrap();
        assert!(view_key.same_backing(&mapped.mapping_identity().unwrap()));
        assert!(!view_key.same_backing(&key));
        drop(mapped);
        assert!(weak_view.upgrade().is_none());
        assert!(
            ReadOnlyPageBuffer(ReadOnlyStorage::Decoded(
                BufferPool::new().acquire(8).unwrap()
            ))
            .mapping_identity()
            .is_none()
        );
        drop(replacement);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn stored_input_views_are_bounded_and_survive_eviction() {
        let path = std::env::temp_dir().join(format!("ehviewer-view-cache-{}", std::process::id()));
        File::create(&path).unwrap().set_len(32 << 20).unwrap();
        let file = File::open(&path).unwrap();
        let map = unsafe { MmapOptions::new().map(&file) }.unwrap();
        let source = Source {
            file,
            map,
            views: Mutex::new(Vec::new()),
        };
        let first = source.stored_view(0, 4 << 20).unwrap();
        for index in 1..8 {
            let view = source.stored_view(index * (4 << 20), 4 << 20).unwrap();
            assert_eq!(view[0], 0);
            let views = source.views.lock().unwrap();
            assert!(views.len() <= MAX_PARALLEL);
            assert!(views.iter().map(|(_, map)| map.len()).sum::<usize>() <= CACHE_BYTES);
        }
        drop(source);
        std::fs::remove_file(&path).unwrap();
        assert_eq!(first[0], 0);
    }

    #[test]
    fn readonly_large_archive_leases_do_not_pin_evicted_pages_or_the_source() {
        let directory =
            std::env::temp_dir().join(format!("ehviewer-readonly-window-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let archive = directory.join("pages.zip");
        let mut output = File::create(&archive).unwrap();
        let mut inputs = (0..12)
            .map(|index| {
                use std::io::{Seek, SeekFrom};
                let path = directory.join(format!("input-{index}"));
                let mut file = File::create(&path).unwrap();
                file.set_len(2 << 20).unwrap();
                file.write_all(&[index]).unwrap();
                file.seek(SeekFrom::End(-1)).unwrap();
                file.write_all(&[index + 1]).unwrap();
                (File::open(path).unwrap(), format!("{index:02}.jpg"))
            })
            .collect::<Vec<_>>();
        write_zip_file(&mut inputs, &mut output).unwrap();
        drop(output);
        let file = File::open(&archive).unwrap();
        let len = file.metadata().unwrap().len();
        // SAFETY: this test owns the file; bytes remain unchanged until all mappings are gone.
        let session = unsafe { ArchiveSession::open(file, len, true, Limits::default()) }.unwrap();
        assert!(session.source.map.len() > CACHE_BYTES);
        let source = Arc::downgrade(&session.source);
        let held = session.extract_readonly(0).unwrap();
        let address = held.as_slice().as_ptr() as usize;
        for index in 1..12 {
            let buffer = session.extract_readonly(index).unwrap();
            assert!(matches!(buffer.0, ReadOnlyStorage::View(_)));
            assert_eq!(buffer.as_slice()[0], index as u8);
            assert_eq!(*buffer.as_slice().last().unwrap(), index as u8 + 1);
            let views = session.source.views.lock().unwrap();
            assert!(views.len() <= MAX_PARALLEL);
            assert!(views.iter().map(|(_, map)| map.len()).sum::<usize>() <= CACHE_BYTES);
        }
        // Eviction drops cache ownership, not a live lease. A weak page slot cannot keep an
        // otherwise unused mapping resident, nor can it create a Source/map reference cycle.
        assert!(
            session.pages[1]
                .readonly
                .lock()
                .unwrap()
                .upgrade()
                .is_none()
        );
        assert!(
            session.pages[0]
                .readonly
                .lock()
                .unwrap()
                .upgrade()
                .is_some()
        );
        std::thread::scope(|scope| {
            for _ in 0..8 {
                let session = &session;
                scope.spawn(move || {
                    for _ in 0..100 {
                        let buffer = session.extract_readonly(0).unwrap();
                        assert_eq!(buffer.as_slice().as_ptr() as usize, address);
                        assert_eq!(buffer.as_slice()[0], 0);
                        assert_eq!(*buffer.as_slice().last().unwrap(), 1);
                    }
                });
            }
        });
        let other = session.extract_readonly(0).unwrap();
        let mut writable = held.into_writable().unwrap();
        writable.as_mut_slice()[0] = 42;
        assert_eq!(other.as_slice()[0], 0);
        let weak_map = session.pages[0].readonly.lock().unwrap().clone();
        drop(session);
        assert!(source.upgrade().is_none());
        std::fs::remove_dir_all(directory).unwrap();
        assert_eq!(other.as_slice()[0], 0);
        assert_eq!(writable.as_mut_slice()[0], 42);
        drop(other);
        assert!(weak_map.upgrade().is_none());
    }
}
