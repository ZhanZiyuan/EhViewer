#![cfg(feature = "android-26")]

use super::android::use_bitmap_content;
use super::jvm::jni_throwing;
use crate::img::copy_region::copy_region;
use crate::img::core::{CustomPixel, ImageConsumer};
use anyhow::{Result, ensure};
use image::ImageBuffer;
use std::ptr::slice_from_raw_parts_mut;

// Constructed only while the hardware-buffer lock is held. This pointer never enters Core.
struct CopyRegion {
    ptr: *mut !,
    target_dim: (u32, u32),
    src_rect: (u32, u32, u32, u32),
    bytes_per_pixel: usize,
}
impl ImageConsumer<()> for CopyRegion {
    fn apply<P: CustomPixel>(self, src: &ImageBuffer<P, &[P::Subpixel]>) -> Result<()> {
        ensure!(
            P::CHANNEL_COUNT as usize * std::mem::size_of::<P::Subpixel>() == self.bytes_per_pixel,
            "Mismatched hardware pixel format"
        );
        let (w, h) = self.target_dim;
        let size = (w as usize)
            .checked_mul(h as usize)
            .and_then(|n| n.checked_mul(P::CHANNEL_COUNT as usize))
            .ok_or_else(|| anyhow::anyhow!("Image size overflow"))?;
        ensure!(
            !self.ptr.is_null() && size <= isize::MAX as usize / std::mem::size_of::<P::Subpixel>(),
            "Invalid hardware buffer"
        );
        // SAFETY: NDK returned this locked allocation; descriptor stride/height and the checked
        // pixel format bound its length. The slice is used exclusively until the unlock guard drops.
        let pixels = unsafe { &mut *slice_from_raw_parts_mut(self.ptr as *mut P::Subpixel, size) };
        let mut dst = ImageBuffer::from_raw(w, h, pixels)
            .ok_or_else(|| anyhow::anyhow!("Invalid hardware image dimensions"))?;
        copy_region(src, &mut dst, self.src_rect)
    }
}
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jobject};
use jni_fn::jni_fn;
use ndk::hardware_buffer::{HardwareBuffer, HardwareBufferUsage};

struct HardwareUnlock<'a>(&'a HardwareBuffer);
impl Drop for HardwareUnlock<'_> {
    fn drop(&mut self) {
        let _ = self.0.unlock();
    }
}

#[jni_fn("com.hippo.ehviewer.image.ImageKt")]
pub fn copyBitmapToAHB(mut env: JNIEnv, _: JClass, bm: jobject, ahb: jobject, x: jint, y: jint) {
    jni_throwing(&mut env, |env| {
        let ahb = unsafe { HardwareBuffer::from_jni(env.get_raw(), ahb) };
        let desc = ahb.describe();
        let (w, h, stride) = (desc.width, desc.height, desc.stride);
        ensure!(
            desc.layers == 1 && x >= 0 && y >= 0,
            "Invalid hardware region"
        );
        let bytes_per_pixel = match i32::from(desc.format) {
            1 => 4,    // R8G8B8A8_UNORM
            4 => 2,    // R5G6B5_UNORM
            0x16 => 8, // R16G16B16A16_FLOAT
            _ => anyhow::bail!("Unsupported hardware pixel format"),
        };
        let ptr = ahb.lock(HardwareBufferUsage::CPU_WRITE_RARELY, None, None)?;
        let unlock = HardwareUnlock(&ahb);
        let s = CopyRegion {
            ptr: ptr as *mut !,
            target_dim: (stride, h),
            src_rect: (x as u32, y as u32, w, h),
            bytes_per_pixel,
        };
        let result = use_bitmap_content(env, bm, s);
        let unlocked = ahb.unlock();
        std::mem::forget(unlock);
        unlocked?;
        result
    })
}
