//! JNI callers share the exact archive owner registry used by the Kotlin/Native C ABI.
use super::*;
use ehviewer_capi::{EHV_BUFFER_TOO_SMALL, EHV_OK, archive as api};

fn owned_status(status: i32, handle: u64) -> jlong {
    if status == EHV_OK {
        handle as jlong
    } else {
        -(status as jlong)
    }
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn openNative(mut env: JNIEnv, _: JObject, path: JByteArray) -> jlong {
    jni_throwing(&mut env, |env| {
        let length = env.get_array_length(&path)?;
        ensure!(length > 0 && length <= 65536, "Invalid archive path length");
        let bytes = env.convert_byte_array(path)?;
        let mut handle = 0;
        // SAFETY: bytes and the disjoint output live through this call. The JVM
        // importer retains an immutable private copy until its document is closed.
        let status = unsafe { api::ehv_archive_open(bytes.as_ptr(), bytes.len(), &mut handle) };
        Ok(owned_status(status, handle))
    })
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn infoNative(mut env: JNIEnv, _: JObject, handle: jlong) -> jlong {
    jni_throwing(&mut env, |_| {
        let (mut pages, mut password) = (0, 0);
        // SAFETY: scalar slots are live, disjoint and owned by this call.
        let status = unsafe { api::ehv_archive_info(handle as u64, &mut pages, &mut password) };
        if status != EHV_OK {
            return Ok(-(status as jlong));
        }
        Ok(((password as jlong) << 32) | pages as jlong)
    })
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn passwordNative(mut env: JNIEnv, _: JObject, handle: jlong, password: JByteArray) -> jint {
    jni_throwing(&mut env, |env| {
        ensure!(
            env.get_array_length(&password)? <= 4096,
            "Archive password exceeds limit"
        );
        let bytes = env.convert_byte_array(password)?;
        let mut accepted = 0;
        // SAFETY: bounded bytes are borrowed and accepted is a disjoint output.
        let status = unsafe {
            api::ehv_archive_password(handle as u64, bytes.as_ptr(), bytes.len(), &mut accepted)
        };
        Ok(if status == EHV_OK {
            accepted as jint
        } else {
            -status
        })
    })
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn pageNative(mut env: JNIEnv, _: JObject, handle: jlong, index: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let mut output = 0;
        let index = u32::try_from(index).context("Invalid archive page")?;
        // SAFETY: output is a disjoint live slot; the owner validates the page range.
        let status = unsafe { api::ehv_archive_read(handle as u64, index, &mut output) };
        Ok(owned_status(status, output))
    })
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn extensionNative(mut env: JNIEnv, _: JObject, handle: jlong, index: jint) -> jlong {
    jni_throwing(&mut env, |_| {
        let mut output = 0;
        let index = u32::try_from(index).context("Invalid archive page")?;
        // SAFETY: output is a disjoint live slot; the owner validates the page range.
        let status = unsafe { api::ehv_archive_extension(handle as u64, index, &mut output) };
        Ok(owned_status(status, output))
    })
}

struct OwnedBytes(u64);
impl Drop for OwnedBytes {
    fn drop(&mut self) {
        ehviewer_capi::ehv_buffer_free(self.0);
    }
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn copyAndFreeNative(mut env: JNIEnv, _: JObject, handle: jlong) -> jbyteArray {
    jni_throwing(&mut env, |env| {
        ensure!(handle > 0, "Invalid archive result");
        let owned = OwnedBytes(handle as u64);
        let mut size = 0;
        // SAFETY: this call owns the output slot and requests metadata only.
        let status =
            unsafe { ehviewer_capi::ehv_buffer_copy(owned.0, std::ptr::null_mut(), 0, &mut size) };
        ensure!(
            status == EHV_BUFFER_TOO_SMALL && size > 0 && size <= 32 << 20,
            "Invalid archive result"
        );
        let mut bytes = vec![0; size];
        // SAFETY: the owned vector and size slot are writable and disjoint.
        ensure!(
            unsafe {
                ehviewer_capi::ehv_buffer_copy(owned.0, bytes.as_mut_ptr(), bytes.len(), &mut size)
            } == EHV_OK
                && size == bytes.len(),
            "Archive result copy failed"
        );
        Ok(env.byte_array_from_slice(&bytes)?.into_raw())
        // OwnedBytes also frees the result when validation or Java allocation fails.
    })
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn closeNative(mut env: JNIEnv, _: JObject, handle: jlong) -> jint {
    jni_throwing(&mut env, |_| Ok(api::ehv_archive_close(handle as u64)))
}

#[jni_fn("com.ehviewer.core.network.archive.NativeArchiveBridge")]
pub fn activeNative(mut env: JNIEnv, _: JObject) -> jlong {
    jni_throwing(&mut env, |_| {
        let (mut archives, mut buffers) = (0, 0);
        // SAFETY: both scalar slots are owned by this call.
        ensure!(
            unsafe { api::ehv_active_archives(&mut archives) } == EHV_OK,
            "Archive registry unavailable"
        );
        // SAFETY: the disjoint scalar slot is owned by this call.
        ensure!(
            unsafe { ehviewer_capi::ehv_active_buffers(&mut buffers) } == EHV_OK,
            "Archive results unavailable"
        );
        ensure!(
            archives <= u32::MAX as usize && buffers <= u32::MAX as usize,
            "Archive count exceeds limit"
        );
        Ok(((archives as jlong) << 32) | buffers as jlong)
    })
}
