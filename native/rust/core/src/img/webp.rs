use libwebp_sys::{
    MODE_rgbA, WebPAnimDecoder, WebPAnimDecoderGetInfo, WebPAnimDecoderGetNext,
    WebPAnimDecoderHasMoreFrames, WebPAnimDecoderNew, WebPAnimDecoderOptions, WebPAnimDecoderReset,
    WebPAnimInfo, WebPData, WebPGetInfo,
};
use std::mem::zeroed;
use std::ptr::null_mut;

pub fn create_decoder(data: &[u8]) -> *mut WebPAnimDecoder {
    // Four RGBA canvases (two in libwebp, two in Android) cost up to 256 MiB at this limit.
    const MAX_ANIMATION_PIXELS: i64 = 16 * 1024 * 1024;
    let (mut width, mut height) = (0, 0);
    // SAFETY: both outputs are live and data remains readable through this call.
    let valid = unsafe { WebPGetInfo(data.as_ptr(), data.len(), &mut width, &mut height) };
    if valid == 0
        || width <= 0
        || height <= 0
        || i64::from(width) * i64::from(height) > MAX_ANIMATION_PIXELS
    {
        return null_mut();
    }
    let webp_data = WebPData {
        bytes: data.as_ptr(),
        size: data.len(),
    };
    let dec_options = WebPAnimDecoderOptions {
        color_mode: MODE_rgbA,
        use_threads: 1,
        padding: [0; 7],
    };
    unsafe { WebPAnimDecoderNew(&webp_data, &dec_options) }
}

/// # Safety
/// `dec` must be a live WebP decoder.
pub unsafe fn get_image_info(dec: *const WebPAnimDecoder) -> WebPAnimInfo {
    let mut info = unsafe { zeroed() };
    let _ = unsafe { WebPAnimDecoderGetInfo(dec, &mut info) };
    info
}

pub fn pack_image_info(info: WebPAnimInfo) -> u64 {
    (info.canvas_width as u64) << 40 | (info.canvas_height as u64) << 16 | (info.loop_count as u64)
}

/// # Safety
/// `dec` must be a live, exclusively accessed WebP decoder.
pub unsafe fn decode_next_frame(dec: *mut WebPAnimDecoder, reset: bool) -> (*const u8, i32) {
    let mut buf = null_mut();
    let mut timestamp = 0;
    unsafe {
        if reset || WebPAnimDecoderHasMoreFrames(dec) == 0 {
            WebPAnimDecoderReset(dec);
        }
        let _ = WebPAnimDecoderGetNext(dec, &mut buf, &mut timestamp);
    }
    (buf, timestamp)
}
