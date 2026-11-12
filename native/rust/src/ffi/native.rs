#![cfg(feature = "jvm")]
//! Stable Kotlin JNI surface. Sessions and algorithms live in ehviewer_core.
mod readonly;
mod registry;
mod sessions;
use super::jvm::{ThrowingHasDefault, jni_throwing};
use anyhow::{Result, anyhow, ensure};
use ehviewer_core::{
    archive::{ArchiveSession, Limits, PageBuffer, ReadOnlyPageBuffer, ZipWriter, write_zip},
    fd::clone_fd,
    gif, hash,
};
use jni::{
    JNIEnv,
    objects::{
        GlobalRef, JByteBuffer, JClass, JIntArray, JMethodID, JObject, JObjectArray, JString,
    },
    signature::ReturnType,
    sys::{jboolean, jint, jlong, jobject},
};
use jni_fn::jni_fn;
use memmap2::MmapOptions;
use registry::Registry;
use sessions::Sessions;
use std::io::Read;
use std::sync::{Arc, Mutex, OnceLock};

// Retain the old single-reader ABI for the historical differential and benchmark tools.
static LEGACY_SESSION: Mutex<Option<Arc<ArchiveSession>>> = Mutex::new(None);
static SESSIONS: OnceLock<Mutex<Sessions<ArchiveSession>>> = OnceLock::new();
static ZIP_WRITERS: OnceLock<Mutex<Sessions<Mutex<Option<ZipWriter>>>>> = OnceLock::new();

fn zip_writers() -> &'static Mutex<Sessions<Mutex<Option<ZipWriter>>>> {
    ZIP_WRITERS.get_or_init(|| Mutex::new(Sessions::new(4)))
}

fn sessions() -> &'static Mutex<Sessions<ArchiveSession>> {
    SESSIONS.get_or_init(|| Mutex::new(Sessions::new(4)))
}

fn session_for(handle: jlong) -> Result<Arc<ArchiveSession>> {
    sessions()
        .lock()
        .map_err(|_| anyhow!("Session registry poisoned"))?
        .get(handle)
}

fn open_session(fd: jint, size: jlong, sort: jboolean) -> Result<ArchiveSession> {
    ensure!(size > 0, "Invalid archive length");
    let file = unsafe { clone_fd(fd)? };
    // SAFETY: callers keep files stable for the lifetime of all sessions and page leases.
    unsafe { ArchiveSession::open(file, size as u64, sort != 0, Limits::default()) }
}
struct Buffer {
    object: GlobalRef,
    data: BufferLease,
    archive: bool,
}
enum BufferData {
    Writable(PageBuffer),
    ReadOnly(ReadOnlyPageBuffer),
}
enum BufferLease {
    Writable(Arc<Mutex<PageBuffer>>),
    // Immutable data needs ownership, not a second allocation and a write-serialization mutex.
    ReadOnly { _data: ReadOnlyPageBuffer },
}
const BUFFER_BUDGET: usize = 512 << 20;
static BUFFERS: Mutex<Registry<Buffer>> = Mutex::new(Registry::new(128, BUFFER_BUDGET));
struct ReadOnlyMethod {
    // Pin the declaring class for the complete lifetime of the cached method ID.
    _class: GlobalRef,
    id: JMethodID,
}
static READONLY_METHOD: OnceLock<ReadOnlyMethod> = OnceLock::new();
fn readonly_view<'local>(
    env: &mut JNIEnv<'local>,
    direct: &JObject,
) -> Result<JByteBuffer<'local>> {
    if READONLY_METHOD.get().is_none() {
        let class = env.find_class("java/nio/ByteBuffer")?;
        let id = env.get_method_id(&class, "asReadOnlyBuffer", "()Ljava/nio/ByteBuffer;")?;
        let method = ReadOnlyMethod {
            _class: env.new_global_ref(class)?,
            id,
        };
        // Concurrent initializers may race; each computes the same method and pins its class.
        let _ = READONLY_METHOD.set(method);
    }
    let method = READONLY_METHOD.get().unwrap();
    // SAFETY: id belongs to the pinned java.nio.ByteBuffer class; direct is a JNI-constructed
    // ByteBuffer or an internal read-only template derived from one. The exact no-argument
    // signature returns a ByteBuffer object. JNI errors
    // still propagate; only redundant signature parsing/class/method lookup is skipped.
    let object =
        unsafe { env.call_method_unchecked(direct, method.id, ReturnType::Object, &[])? }.l()?;
    Ok(JByteBuffer::from(object))
}
fn session() -> Result<Arc<ArchiveSession>> {
    LEGACY_SESSION
        .lock()
        .map_err(|_| anyhow!("Session lock poisoned"))?
        .as_ref()
        .map(Arc::clone)
        .ok_or_else(|| anyhow!("No open archive"))
}
// Preserve C's sentinel results for recoverable archive errors. No password/error content is logged.
fn sentinel<R: ThrowingHasDefault>(f: impl FnOnce() -> Result<R>) -> R {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(Ok(value)) => value,
        _ => R::default(),
    }
}
fn publish(env: &mut JNIEnv, mut data: BufferData, archive: bool) -> Result<jobject> {
    let (address, len, retained_bytes) = match &mut data {
        BufferData::Writable(data) => {
            let retained = data.retained_bytes();
            let bytes = data.as_mut_slice();
            (bytes.as_mut_ptr(), bytes.len(), retained)
        }
        BufferData::ReadOnly(data) => (
            data.as_slice().as_ptr().cast_mut(),
            data.as_slice().len(),
            data.retained_bytes(),
        ),
    };
    // Allocation/map ownership stays in data until release of this exact Java object, independent
    // of the session. Read-only templates never escape or own native bytes; each lease gets a fresh
    // real read-only Java object. Native writers also reject read-only registry entries.
    let object = match &data {
        BufferData::ReadOnly(data) => readonly::view(env, data)?,
        // SAFETY: stable allocation/map ownership remains in data until explicit release.
        BufferData::Writable(_) => unsafe { env.new_direct_byte_buffer(address, len)? },
    };
    let global = env.new_global_ref(&object)?;
    let buffer = Buffer {
        object: global,
        data: match data {
            BufferData::Writable(data) => BufferLease::Writable(Arc::new(Mutex::new(data))),
            BufferData::ReadOnly(data) => BufferLease::ReadOnly { _data: data },
        },
        archive,
    };
    // JNI allocation and global-reference creation do not hold the registry lock.
    BUFFERS
        .lock()
        .map_err(|_| anyhow!("Buffer lock poisoned"))?
        .insert(address as usize, len, retained_bytes, buffer)?;
    Ok(object.into_raw())
}
fn release(env: &JNIEnv, buffer: &JByteBuffer, archive: bool) -> Result<()> {
    let address = env.get_direct_buffer_address(buffer)? as usize;
    let removed = BUFFERS
        .lock()
        .map_err(|_| anyhow!("Buffer lock poisoned"))?
        .remove(address, |b| {
            Ok(b.archive == archive && env.is_same_object(buffer, b.object.as_obj())?)
        })?;
    // Dropping a global reference or returning storage to its pool can run outside the registry.
    drop(removed);
    Ok(())
}
#[jni_fn("com.hippo.ehviewer.jni.HashKt")]
pub fn sha1(mut env: JNIEnv, _: JClass, fd: jint) -> jobject {
    jni_throwing(&mut env, |env| {
        // SAFETY: Java owns fd through this call; clone retains caller offset and ownership.
        let file = unsafe { clone_fd(fd)? };
        Ok(env.new_string(hash::sha1(file)?)?.into_raw())
    })
}
#[jni_fn("com.hippo.ehviewer.jni.GifUtilsKt")]
pub fn isGif(_: JNIEnv, _: JClass, fd: jint) -> jboolean {
    sentinel(|| {
        let mut file = unsafe { clone_fd(fd)? };
        let mut header = [0; 6];
        Ok((file.read_exact(&mut header).is_ok() && gif::is_gif(&header)) as jboolean)
    })
}
#[jni_fn("com.hippo.ehviewer.jni.GifUtilsKt")]
pub fn rewriteGifSource(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) {
    jni_throwing(&mut env, |env| {
        ensure!(
            !env.call_method(&buffer, "isReadOnly", "()Z", &[])?.z()?,
            "Read-only GIF buffer"
        );
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let cap = env.get_direct_buffer_capacity(&buffer)?;
        ensure!(
            cap <= isize::MAX as usize && !ptr.is_null(),
            "Invalid direct buffer"
        );
        // Retain the containing allocation, including sliced views, then serialize writes to this
        // allocation only. A concurrent release cannot free it or return its storage to the pool.
        let registered = {
            let buffers = BUFFERS
                .lock()
                .map_err(|_| anyhow!("Buffer lock poisoned"))?;
            let entry = buffers.covering(ptr as usize, cap);
            match entry.map(|b| &b.data) {
                Some(BufferLease::ReadOnly { .. }) => {
                    return Err(anyhow!("Read-only native allocation"));
                }
                Some(BufferLease::Writable(data)) => Some(Arc::clone(data)),
                None => None,
            }
        };
        let _lease = registered
            .as_ref()
            .map(|data| data.lock().map_err(|_| anyhow!("Buffer lock poisoned")))
            .transpose()?;
        // SAFETY: Java owns this writable direct buffer for the duration of the call and must not
        // mutate it concurrently; the retained lease keeps app-owned allocations alive.
        gif::rewrite(unsafe { std::slice::from_raw_parts_mut(ptr, cap) });
        Ok(())
    })
}
#[jni_fn("com.hippo.ehviewer.jni.GifUtilsKt")]
pub fn mmap(mut env: JNIEnv, _: JClass, fd: jint) -> jobject {
    sentinel(|| {
        let file = unsafe { clone_fd(fd)? };
        let size = file.metadata()?.len();
        ensure!(
            size > 0 && size <= i32::MAX as u64,
            "Invalid GIF mapping size"
        );
        // SAFETY: caller must keep file contents stable while mapped; map_copy never modifies file.
        let map = unsafe { MmapOptions::new().len(size as usize).map_copy(&file)? };
        publish(
            &mut env,
            BufferData::Writable(PageBuffer::Mapped(map)),
            false,
        )
    })
}
#[jni_fn("com.hippo.ehviewer.jni.GifUtilsKt")]
pub fn munmap(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) {
    jni_throwing(&mut env, |env| release(env, &buffer, false))
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn openArchive(_: JNIEnv, _: JClass, fd: jint, size: jlong, sort: jboolean) -> jint {
    sentinel(|| {
        // Serialise open/close replacement, but extraction uses an Arc outside this lock.
        let mut slot = LEGACY_SESSION
            .lock()
            .map_err(|_| anyhow!("Session lock poisoned"))?;
        *slot = None;
        let new = open_session(fd, size, sort)?;
        let count = i32::try_from(new.len())?;
        if count > 0 {
            *slot = Some(Arc::new(new));
        }
        Ok(count)
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn closeArchive(mut env: JNIEnv, _: JClass) {
    jni_throwing(&mut env, |_| {
        *LEGACY_SESSION
            .lock()
            .map_err(|_| anyhow!("Session lock poisoned"))? = None;
        Ok(())
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn needPassword(_: JNIEnv, _: JClass) -> jboolean {
    sentinel(|| Ok(session()?.needs_password() as jboolean))
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn providePassword(mut env: JNIEnv, _: JClass, password: JString) -> jboolean {
    sentinel(|| {
        let password: String = env.get_string(&password)?.into();
        Ok(session()?.provide_password(&password)? as jboolean)
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn extractToByteBuffer(mut env: JNIEnv, _: JClass, index: jint) -> jobject {
    sentinel(|| {
        ensure!(index >= 0, "Invalid page index");
        publish(
            &mut env,
            BufferData::Writable(session()?.extract(index as usize)?),
            true,
        )
    })
}
/// Internal reader API: immutable pages unless the API28-33 GIF workaround needs private writes.
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn extractForReading(
    mut env: JNIEnv,
    _: JClass,
    index: jint,
    rewrite_gif: jboolean,
) -> jobject {
    sentinel(|| extract_for_reading(&mut env, session()?.as_ref(), index, rewrite_gif))
}

fn extract_for_reading(
    env: &mut JNIEnv,
    session: &ArchiveSession,
    index: jint,
    rewrite_gif: jboolean,
) -> Result<jobject> {
    ensure!(index >= 0, "Invalid page index");
    let data = session.extract_readonly(index as usize)?;
    let data = if rewrite_gif != 0 && gif::is_gif(data.as_slice()) {
        BufferData::Writable(data.into_writable()?)
    } else {
        BufferData::ReadOnly(data)
    };
    publish(env, data, true)
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn openArchiveSession(
    mut env: JNIEnv,
    _: JClass,
    fd: jint,
    size: jlong,
    sort: jboolean,
) -> jlong {
    jni_throwing(&mut env, |_| {
        let archive = Arc::new(open_session(fd, size, sort)?);
        ensure!(!archive.is_empty(), "Archive has no content");
        sessions()
            .lock()
            .map_err(|_| anyhow!("Session registry poisoned"))?
            .add(archive)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn closeArchiveSession(mut env: JNIEnv, _: JClass, handle: jlong) {
    jni_throwing(&mut env, |_| {
        let archive = sessions()
            .lock()
            .map_err(|_| anyhow!("Session registry poisoned"))?
            .remove(handle);
        drop(archive);
        Ok(())
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveSessionPageCount(mut env: JNIEnv, _: JClass, handle: jlong) -> jint {
    jni_throwing(&mut env, |_| Ok(i32::try_from(session_for(handle)?.len())?))
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveSessionNeedsPassword(mut env: JNIEnv, _: JClass, handle: jlong) -> jboolean {
    jni_throwing(&mut env, |_| {
        Ok(session_for(handle)?.needs_password() as jboolean)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveSessionProvidePassword(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    password: JString,
) -> jboolean {
    sentinel(|| {
        let password: String = env.get_string(&password)?.into();
        Ok(session_for(handle)?.provide_password(&password)? as jboolean)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveSessionExtension(mut env: JNIEnv, _: JClass, handle: jlong, index: jint) -> jobject {
    jni_throwing(&mut env, |env| {
        ensure!(index >= 0, "Invalid page index");
        Ok(env
            .new_string(session_for(handle)?.extension(index as usize)?)?
            .into_raw())
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveSessionRead(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    index: jint,
    rewrite_gif: jboolean,
) -> jobject {
    sentinel(|| extract_for_reading(&mut env, session_for(handle)?.as_ref(), index, rewrite_gif))
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveSessionCopyTo(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
    index: jint,
    fd: jint,
) -> jboolean {
    sentinel(|| {
        ensure!(index >= 0, "Invalid page index");
        let file = unsafe { clone_fd(fd)? };
        session_for(handle)?.extract_to(index as usize, file)?;
        Ok(1)
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn trimArchiveCaches(mut env: JNIEnv, _: JClass) {
    jni_throwing(&mut env, |_| {
        let active = sessions()
            .lock()
            .map_err(|_| anyhow!("Session registry poisoned"))?
            .snapshot();
        for session in active {
            session.trim_caches();
        }
        Ok(())
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn releaseByteBuffer(mut env: JNIEnv, _: JClass, buffer: JByteBuffer) {
    jni_throwing(&mut env, |env| release(env, &buffer, true))
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn extractToFd(_: JNIEnv, _: JClass, index: jint, fd: jint) -> jboolean {
    sentinel(|| {
        ensure!(index >= 0, "Invalid page index");
        let file = unsafe { clone_fd(fd)? };
        session()?.extract_to(index as usize, file)?;
        Ok(1)
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn getExtension(mut env: JNIEnv, _: JClass, index: jint) -> jobject {
    jni_throwing(&mut env, |env| {
        ensure!(index >= 0, "Invalid page index");
        Ok(env
            .new_string(session()?.extension(index as usize)?)?
            .into_raw())
    })
}
#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn archiveFdBatch(
    mut env: JNIEnv,
    _: JClass,
    fds: JIntArray,
    names: JObjectArray,
    fd: jint,
    size: jint,
) {
    jni_throwing(&mut env, |env| {
        ensure!(
            size >= 0
                && size as usize <= Limits::default().entries
                && size <= env.get_array_length(&fds)?
                && size <= env.get_array_length(&names)?,
            "Invalid batch size"
        );
        let mut descriptors = vec![0; size as usize];
        env.get_int_array_region(&fds, 0, &mut descriptors)?;
        let mut inputs = Vec::with_capacity(size as usize);
        for (i, fd) in descriptors.into_iter().enumerate() {
            let object = env.get_object_array_element(&names, i as jint)?;
            let string = JString::from(object);
            let name: String = env.get_string(&string)?.into();
            env.delete_local_ref(string)?;
            inputs.push((unsafe { clone_fd(fd)? }, name));
        }
        let output = unsafe { clone_fd(fd)? };
        use std::os::fd::AsRawFd;
        write_zip(&mut inputs, output.as_raw_fd())
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn beginZipWriter(mut env: JNIEnv, _: JClass, fd: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let output = unsafe { clone_fd(fd)? };
        let writer = Arc::new(Mutex::new(Some(ZipWriter::new(output)?)));
        zip_writers()
            .lock()
            .map_err(|_| anyhow!("ZIP registry poisoned"))?
            .add(writer)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn appendZipEntry(mut env: JNIEnv, _: JClass, handle: jlong, fd: jint, name: JString) {
    jni_throwing(&mut env, |env| {
        let name: String = env.get_string(&name)?.into();
        let mut input = unsafe { clone_fd(fd)? };
        let writer = zip_writers()
            .lock()
            .map_err(|_| anyhow!("ZIP registry poisoned"))?
            .get(handle)?;
        writer
            .lock()
            .map_err(|_| anyhow!("ZIP writer poisoned"))?
            .as_mut()
            .ok_or_else(|| anyhow!("ZIP writer is closed"))?
            .append(&mut input, &name)
    })
}

#[jni_fn("com.hippo.ehviewer.jni.ArchiveKt")]
pub fn closeZipWriter(mut env: JNIEnv, _: JClass, handle: jlong, commit: jboolean) {
    jni_throwing(&mut env, |_| {
        let writer = zip_writers()
            .lock()
            .map_err(|_| anyhow!("ZIP registry poisoned"))?
            .remove(handle);
        if let Some(writer) = writer {
            let writer = writer
                .lock()
                .map_err(|_| anyhow!("ZIP writer poisoned"))?
                .take();
            if commit != 0 {
                writer
                    .ok_or_else(|| anyhow!("ZIP writer is closed"))?
                    .finish()?;
            }
        } else {
            ensure!(commit == 0, "ZIP writer is closed");
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn panics_and_errors_stay_inside_boundary() {
        assert_eq!(sentinel::<jint>(|| panic!("regression panic")), 0);
        assert_eq!(sentinel::<jint>(|| Err(anyhow!("regression error"))), 0);
        assert_eq!(sentinel::<jint>(|| Ok(7)), 7);
    }
}
