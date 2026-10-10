//! Region copying over checked image slices. Android raw pointers stay in the JNI crate.
use super::core::CustomPixel;
use anyhow::{Result, ensure};
use image::{GenericImage, GenericImageView, ImageBuffer};

pub fn copy_region<P: CustomPixel>(
    src: &ImageBuffer<P, &[P::Subpixel]>,
    dst: &mut ImageBuffer<P, &mut [P::Subpixel]>,
    (x, y, w, h): (u32, u32, u32, u32),
) -> Result<()> {
    ensure!(
        x.checked_add(w).is_some_and(|end| end <= src.width())
            && y.checked_add(h).is_some_and(|end| end <= src.height()),
        "Region outside source image"
    );
    ensure!(
        w <= dst.width() && h <= dst.height(),
        "Region outside destination image"
    );
    Ok(dst.copy_from(&*src.view(x, y, w, h), 0, 0)?)
}

#[cfg(test)]
mod tests {
    use super::super::core::Rgba8888;
    use super::*;
    #[test]
    fn checked_region_copy() {
        let source = [7u8; 4 * 4 * 4];
        let mut target = [0u8; 2 * 2 * 4];
        let src = ImageBuffer::<Rgba8888, _>::from_raw(4, 4, &source[..]).unwrap();
        let mut dst = ImageBuffer::<Rgba8888, _>::from_raw(2, 2, &mut target[..]).unwrap();
        copy_region(&src, &mut dst, (1, 1, 2, 2)).unwrap();
        assert_eq!(&dst.as_raw()[..], &[7u8; 16]);
        assert!(copy_region(&src, &mut dst, (3, 3, 2, 2)).is_err());
        assert!(copy_region(&src, &mut dst, (u32::MAX, 0, 2, 2)).is_err());
    }
}
