use super::gallery_document;
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::jbyteArray,
};
use jni_fn::jni_fn;

#[jni_fn("com.ehviewer.core.network.gallery.RustGalleryDecoder")]
pub fn decodeProfileLinkNative(env: JNIEnv, _: JObject, input: JByteArray) -> jbyteArray {
    gallery_document(
        env,
        input,
        ehviewer_core::parser::account::parse_profile_link_cbor,
    )
}

#[jni_fn("com.ehviewer.core.network.gallery.RustGalleryDecoder")]
pub fn decodeProfileNative(env: JNIEnv, _: JObject, input: JByteArray) -> jbyteArray {
    gallery_document(
        env,
        input,
        ehviewer_core::parser::account::parse_profile_cbor,
    )
}

#[jni_fn("com.ehviewer.core.network.gallery.RustGalleryDecoder")]
pub fn decodeUserConfigNative(env: JNIEnv, _: JObject, input: JByteArray) -> jbyteArray {
    gallery_document(
        env,
        input,
        ehviewer_core::parser::account::parse_user_config_cbor,
    )
}
