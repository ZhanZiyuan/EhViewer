use super::CookieTransfer;
use gtk::gio;
use gtk::glib::{
    self,
    translate::{FromGlibPtrContainer, ToGlibPtr, from_glib_full},
};
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::time::{Duration, Instant};
use webkit2gtk::{CookieManagerExt, WebViewExt, WebsiteDataManagerExt};
use wry::WebViewExtUnix;

// WebKitGTK 2.42 exposes this API; gtk-rs 2.0.2 does not yet generate its binding.
// These are Rust declarations for the system ABI, not maintained C implementations.
unsafe extern "C" {
    fn webkit_cookie_manager_get_all_cookies(
        manager: *mut webkit2gtk_sys::WebKitCookieManager,
        cancellable: *mut gio::ffi::GCancellable,
        callback: gio::ffi::GAsyncReadyCallback,
        data: glib::ffi::gpointer,
    );
    fn webkit_cookie_manager_get_all_cookies_finish(
        manager: *mut webkit2gtk_sys::WebKitCookieManager,
        result: *mut gio::ffi::GAsyncResult,
        error: *mut *mut glib::ffi::GError,
    ) -> *mut glib::ffi::GList;
}

fn await_callback<T>(receiver: Receiver<T>, cancel: &gio::Cancellable) -> Result<T, ()> {
    use gio::prelude::CancellableExt;
    let deadline = Instant::now() + Duration::from_secs(15);
    let context = glib::MainContext::ref_thread_default();
    loop {
        match receiver.try_recv() {
            Ok(result) => return Ok(result),
            Err(TryRecvError::Disconnected) => return Err(()),
            Err(TryRecvError::Empty) if Instant::now() >= deadline => {
                cancel.cancel();
                return Err(());
            }
            Err(TryRecvError::Empty) => {}
        }
        context.iteration(false);
        std::thread::sleep(Duration::from_millis(5));
    }
}

pub fn private_store(view: &wry::WebView) -> Result<bool, ()> {
    Ok(view
        .webview()
        .website_data_manager()
        .ok_or(())?
        .is_ephemeral())
}

type CookieResult = Result<Vec<CookieTransfer>, ()>;
unsafe extern "C" fn cookies_ready(
    source: *mut glib::gobject_ffi::GObject,
    result: *mut gio::ffi::GAsyncResult,
    data: glib::ffi::gpointer,
) {
    // SAFETY: data is the unique Box allocated below; GTask invokes its completion
    // once on the owning GLib context and retains source/result for this callback.
    let sender = unsafe { Box::from_raw(data.cast::<mpsc::Sender<CookieResult>>()) };
    let records = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut error = std::ptr::null_mut();
        // SAFETY: source is the same CookieManager and result belongs to that request.
        let raw = unsafe {
            webkit_cookie_manager_get_all_cookies_finish(source.cast(), result, &mut error)
        };
        if !error.is_null() {
            // SAFETY: the finish API transfers the GError to this caller.
            let _: glib::Error = unsafe { from_glib_full(error) };
            return Err(());
        }
        // SAFETY: the API transfers a GList of boxed SoupCookie objects. gtk-rs owns
        // and frees every list node and cookie, including filtered-out domains.
        let cookies: Vec<soup3::Cookie> = unsafe { FromGlibPtrContainer::from_glib_full(raw) };
        if cookies.len() > 4096 {
            return Err(());
        }
        let mut records = Vec::new();
        for mut cookie in cookies {
            let domain = cookie.domain().ok_or(())?.to_string();
            if !matches!(
                domain.trim_start_matches('.'),
                "e-hentai.org" | "exhentai.org" | "forums.e-hentai.org"
            ) {
                continue;
            }
            if records.len() == 128 {
                return Err(());
            }
            records.push(CookieTransfer {
                name: cookie.name().ok_or(())?.to_string(),
                value: cookie.value().ok_or(())?.to_string(),
                domain,
                path: cookie.path().ok_or(())?.to_string(),
                secure: cookie.is_secure(),
                http_only: cookie.is_http_only(),
                expires_at: cookie
                    .expires()
                    .map(|time| i128::from(time.to_unix()) * 1000),
            });
        }
        Ok(records)
    }))
    .unwrap_or(Err(()));
    let _ = sender.send(records);
}

pub fn cookies(view: &wry::WebView) -> CookieResult {
    let manager = view
        .webview()
        .website_data_manager()
        .and_then(|manager| manager.cookie_manager())
        .ok_or(())?;
    let cancel = gio::Cancellable::new();
    let (sender, receiver) = mpsc::channel::<CookieResult>();
    // SAFETY: manager/cancellable are valid GLib references. The request retains them;
    // only the callback consumes its boxed sender, even if this caller times out.
    unsafe {
        webkit_cookie_manager_get_all_cookies(
            manager.to_glib_none().0,
            cancel.to_glib_none().0,
            Some(cookies_ready),
            Box::into_raw(Box::new(sender)).cast(),
        );
    }
    await_callback(receiver, &cancel)?
}

pub fn verify(view: &wry::WebView) -> Result<usize, ()> {
    if !private_store(view)? || !cookies(view)?.is_empty() {
        return Err(());
    }
    let manager = view
        .webview()
        .website_data_manager()
        .and_then(|manager| manager.cookie_manager())
        .ok_or(())?;
    for (name, domain, path) in [
        ("ehviewer-domain", ".e-hentai.org", "/private"),
        ("ehviewer-host", "e-hentai.org", "/"),
    ] {
        let mut cookie = soup3::Cookie::new(name, "synthetic-only", domain, path, 3600);
        cookie.set_http_only(true);
        cookie.set_secure(true);
        let cancel = gio::Cancellable::new();
        let (sender, receiver) = mpsc::channel();
        manager.add_cookie(&mut cookie, Some(&cancel), move |result| {
            let _ = sender.send(result.map_err(|_| ()));
        });
        await_callback(receiver, &cancel)??;
    }
    let records = cookies(view)?;
    let domain = records
        .iter()
        .find(|cookie| cookie.name == "ehviewer-domain")
        .ok_or(())?;
    let host = records
        .iter()
        .find(|cookie| cookie.name == "ehviewer-host")
        .ok_or(())?;
    if records.len() != 2
        || domain.domain != ".e-hentai.org"
        || host.domain != "e-hentai.org"
        || domain.path != "/private"
        || host.path != "/"
        || records.iter().any(|cookie| {
            !cookie.secure
                || !cookie.http_only
                || cookie.expires_at.is_none()
                || cookie.value != "synthetic-only"
        })
    {
        return Err(());
    }
    Ok(4)
}
