#![cfg(feature = "jvm")]
//! Stable Kotlin JNI surface. Sessions and algorithms live in ehviewer_core.
use super::jvm::{ThrowingHasDefault, jni_throwing};
use anyhow::{Result, anyhow, ensure};
use ehviewer_core::{
    archive::{ArchiveSession, Limits, PageBuffer, write_zip},
    fd::clone_fd,
    gif, hash,
};
use jni::{
    JNIEnv,
    objects::{GlobalRef, JByteBuffer, JClass, JIntArray, JObjectArray, JString},
    sys::{jboolean, jint, jlong, jobject},
};
use jni_fn::jni_fn;
use memmap2::MmapOptions;
use std::io::Read;
use std::sync::{Arc, Mutex};

static SESSION: Mutex<Option<Arc<ArchiveSession>>> = Mutex::new(None);
struct Buffer {
    object: GlobalRef,
    data: PageBuffer,
    archive: bool,
}
static BUFFERS: Mutex<Vec<Buffer>> = Mutex::new(Vec::new());
const BUFFER_BUDGET: usize = 512 << 20;
fn session() -> Result<Arc<ArchiveSession>> {
    SESSION
        .lock()
        .map_err(|_| anyhow!("Session lock poisoned"))?
        .clone()
        .ok_or_else(|| anyhow!("No open archive"))
}
// Preserve C's sentinel results for recoverable archive errors. No password/error content is logged.
fn sentinel<R: ThrowingHasDefault>(f: impl FnOnce() -> Result<R>) -> R {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(Ok(value)) => value,
        _ => R::default(),
    }
}
fn publish(env: &mut JNIEnv, mut data: PageBuffer, archive: bool) -> Result<jobject> {
    let mut buffers = BUFFERS
        .lock()
        .map_err(|_| anyhow!("Buffer lock poisoned"))?;
    let used: usize = buffers
        .iter_mut()
        .map(|b| b.data.as_mut_slice().len())
        .sum();
    let bytes = data.as_mut_slice();
    ensure!(
        buffers.len() < 128 && bytes.len() <= BUFFER_BUDGET.saturating_sub(used),
        "Native buffer budget exceeded"
    );
    // SAFETY: allocation/map has stable address and is retained until explicit release of this
    // exact Java object. It is independent of SESSION and protected against duplicate releases.
    let object = unsafe { env.new_direct_byte_buffer(bytes.as_mut_ptr(), bytes.len())? };
    let global = env.new_global_ref(&object)?;
    buffers.push(Buffer {
        object: global,
        data,
        archive,
    });
    Ok(object.into_raw())
}
fn release(env: &JNIEnv, buffer: &JByteBuffer, archive: bool) -> Result<()> {
    let mut buffers = BUFFERS
        .lock()
        .map_err(|_| anyhow!("Buffer lock poisoned"))?;
    let mut found = None;
    for (i, b) in buffers.iter().enumerate() {
        if b.archive == archive && env.is_same_object(buffer, b.object.as_obj())? {
            found = Some(i);
            break;
        }
    }
    if let Some(i) = found {
        buffers.swap_remove(i);
    }
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
        // Prevent concurrent native release while operating on a registered mapping.
        let _buffers = BUFFERS
            .lock()
            .map_err(|_| anyhow!("Buffer lock poisoned"))?;
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let cap = env.get_direct_buffer_capacity(&buffer)?;
        ensure!(
            cap <= isize::MAX as usize && !ptr.is_null(),
            "Invalid direct buffer"
        );
        // SAFETY: Java owns this writable direct buffer for the duration of the call and must not
        // mutate it concurrently; registry lock keeps app-owned allocations alive.
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
        publish(&mut env, PageBuffer::Mapped(map), false)
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
        let mut slot = SESSION
            .lock()
            .map_err(|_| anyhow!("Session lock poisoned"))?;
        *slot = None;
        ensure!(size > 0, "Invalid archive length");
        let file = unsafe { clone_fd(fd)? };
        // SAFETY: the archive loader opens a read-only stable file; mapped pages retain their
        // own view after close. Callers must not truncate/modify a file with live page buffers.
        let new = unsafe { ArchiveSession::open(file, size as u64, sort != 0, Limits::default())? };
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
        *SESSION
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
        publish(&mut env, session()?.extract(index as usize)?, true)
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
