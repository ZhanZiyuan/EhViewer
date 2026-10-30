use super::jni_throwing;
use anyhow::{Result, ensure};
use ehviewer_browser_client as client;
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni_fn::jni_fn;

fn text(env: &mut JNIEnv, input: &JString, maximum: usize) -> Result<String> {
    let length = env.call_method(input, "length", "()I", &[])?.i()?;
    ensure!(
        length > 0 && length as usize <= maximum,
        "Invalid browser input size"
    );
    let text: String = env.get_string(input)?.into();
    ensure!(
        text.len() <= maximum && !text.contains('\0'),
        "Invalid browser input size"
    );
    Ok(text)
}

fn failure(_: client::Error) -> anyhow::Error {
    anyhow::anyhow!("The native sign-in browser is unavailable")
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn open(
    mut env: JNIEnv,
    _: JObject,
    binary: JString,
    user_agent: JString,
    regression: jboolean,
) -> jlong {
    jni_throwing(&mut env, |env| {
        let binary = text(env, &binary, 65536)?;
        let user_agent = text(env, &user_agent, 1024)?;
        client::open(std::path::Path::new(&binary), &user_agent, regression != 0)
            .map(|id| id as jlong)
            .map_err(failure)
    })
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn cookies(mut env: JNIEnv, _: JObject, handle: jlong) -> jbyteArray {
    jni_throwing(&mut env, |env| {
        let bytes = client::cookies_json(handle as u64).map_err(failure)?;
        Ok(env.byte_array_from_slice(&bytes)?.into_raw())
    })
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn close(mut env: JNIEnv, _: JObject, handle: jlong) {
    jni_throwing(&mut env, |_| client::close(handle as u64).map_err(failure))
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn running(mut env: JNIEnv, _: JObject, handle: jlong) -> jboolean {
    jni_throwing(&mut env, |_| {
        client::running(handle as u64)
            .map(u8::from)
            .map_err(failure)
    })
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn processId(mut env: JNIEnv, _: JObject, handle: jlong) -> jlong {
    jni_throwing(&mut env, |_| {
        client::process_id(handle as u64)
            .map(jlong::from)
            .map_err(failure)
    })
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn active(mut env: JNIEnv, _: JObject) -> jint {
    jni_throwing(&mut env, |_| {
        client::active().map(|count| count as jint).map_err(failure)
    })
}

#[jni_fn("com.hippo.ehviewer.desktop.NativeBrowserBridge")]
pub fn protocolVersion(_: JNIEnv, _: JObject) -> jint {
    1
}
