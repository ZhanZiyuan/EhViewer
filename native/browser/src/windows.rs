use super::CookieTransfer;
use std::marker::PhantomData;
use std::rc::Rc;
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use webview2_com::Microsoft::Web::WebView2::Win32::{ICoreWebView2_2, ICoreWebView2_13};
use webview2_com::{GetCookiesCompletedHandler, take_pwstr};
use windows::Win32::Foundation::{E_INVALIDARG, E_UNEXPECTED};
use windows::Win32::UI::WindowsAndMessaging::{
    DispatchMessageW, KillTimer, MSG, PM_REMOVE, PeekMessageW, SetTimer, TranslateMessage,
};
use windows::core::{BOOL, HSTRING, Interface, PCWSTR, PWSTR};
use wry::WebViewExtWindows;

/// Wry waits for initialization callbacks inside blocking GetMessage calls. A sent
/// COM callback may complete its channel without leaving a posted message behind.
/// Keep that wait responsive until construction returns, on this owning STA only.
pub struct StartupMessagePump {
    timer: usize,
    _owner: PhantomData<Rc<()>>,
}

impl StartupMessagePump {
    pub fn new() -> Result<Self, ()> {
        // SAFETY: a thread-owned timer with no callback/pointer. WM_TIMER only wakes
        // the owner's existing message pump; it cannot call into a dropped Rust value.
        let timer = unsafe { SetTimer(None, 0, 25, None) };
        if timer == 0 {
            return Err(());
        }
        Ok(Self {
            timer,
            _owner: PhantomData,
        })
    }
}

impl Drop for StartupMessagePump {
    fn drop(&mut self) {
        // SAFETY: !Send/!Sync keeps removal on the creating thread. Pending timer
        // messages have no TIMERPROC and do not retain a Rust callback or borrow.
        let _ = unsafe { KillTimer(None, self.timer) };
    }
}

fn await_callback<T>(receiver: Receiver<T>) -> Result<T, ()> {
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        match receiver.try_recv() {
            Ok(result) => return Ok(result),
            Err(TryRecvError::Disconnected) => return Err(()),
            Err(TryRecvError::Empty) if Instant::now() >= deadline => return Err(()),
            Err(TryRecvError::Empty) => {}
        }
        // SAFETY: this is the owning WebView2 STA thread; MSG is writable and dispatch
        // consumes only messages retrieved from this thread's own Win32 message queue.
        unsafe {
            let mut message = MSG::default();
            while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

pub fn private_store(view: &wry::WebView) -> Result<bool, ()> {
    let view = view.webview().cast::<ICoreWebView2_13>().map_err(|_| ())?;
    let mut private = BOOL::default();
    // SAFETY: valid COM interface on its owner thread and one writable BOOL slot.
    unsafe {
        view.Profile()
            .and_then(|profile| profile.IsInPrivateModeEnabled(&mut private))
            .map_err(|_| ())?;
    }
    Ok(private.as_bool())
}

pub fn cookies(view: &wry::WebView) -> Result<Vec<CookieTransfer>, ()> {
    let view = view.webview().cast::<ICoreWebView2_2>().map_err(|_| ())?;
    let (sender, receiver) = mpsc::channel();
    // SAFETY: the callback owns its sender, not stack borrows. Native strings are copied
    // and released with the SDK's CoTaskMem helper; no COM cookie pointer escapes.
    unsafe {
        view.CookieManager()
            .and_then(|manager| {
                manager.GetCookies(
                    PCWSTR::null(),
                    &GetCookiesCompletedHandler::create(Box::new(move |status, cookies| {
                        let result = (|| {
                            status?;
                            let list = cookies
                                .ok_or_else(|| windows::core::Error::from_hresult(E_UNEXPECTED))?;
                            let mut count = 0;
                            list.Count(&mut count)?;
                            if count > 4096 {
                                return Err(windows::core::Error::from_hresult(E_INVALIDARG));
                            }
                            let mut records = Vec::new();
                            for index in 0..count {
                                let cookie = list.GetValueAtIndex(index)?;
                                let mut domain = PWSTR::null();
                                cookie.Domain(&mut domain)?;
                                let domain = take_pwstr(domain);
                                if !matches!(
                                    domain.trim_start_matches('.'),
                                    "e-hentai.org" | "exhentai.org" | "forums.e-hentai.org"
                                ) {
                                    continue;
                                }
                                if records.len() == 128 {
                                    return Err(windows::core::Error::from_hresult(E_INVALIDARG));
                                }
                                let mut name = PWSTR::null();
                                cookie.Name(&mut name)?;
                                let name = take_pwstr(name);
                                let mut value = PWSTR::null();
                                cookie.Value(&mut value)?;
                                let value = take_pwstr(value);
                                let mut path = PWSTR::null();
                                cookie.Path(&mut path)?;
                                let path = take_pwstr(path);
                                let mut http_only = BOOL::default();
                                cookie.IsHttpOnly(&mut http_only)?;
                                let mut secure = BOOL::default();
                                cookie.IsSecure(&mut secure)?;
                                let mut session = BOOL::default();
                                cookie.IsSession(&mut session)?;
                                let mut expires = 0.0;
                                cookie.Expires(&mut expires)?;
                                if !session.as_bool() && !expires.is_finite() {
                                    return Err(windows::core::Error::from_hresult(E_INVALIDARG));
                                }
                                records.push(CookieTransfer {
                                    name,
                                    value,
                                    domain,
                                    path,
                                    secure: secure.as_bool(),
                                    http_only: http_only.as_bool(),
                                    expires_at: (!session.as_bool() && expires >= 0.0)
                                        .then_some((expires * 1000.0) as i128),
                                });
                            }
                            Ok(records)
                        })();
                        let _ = sender.send(result);
                        Ok(())
                    })),
                )
            })
            .map_err(|_| ())?;
    }
    await_callback(receiver)?.map_err(|_| ())
}

pub fn verify(view: &wry::WebView) -> Result<usize, ()> {
    if !private_store(view)? || !cookies(view)?.is_empty() {
        return Err(());
    }
    let view2 = view.webview().cast::<ICoreWebView2_2>().map_err(|_| ())?;
    let expires = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| ())?
        .as_secs_f64()
        + 3600.0;
    // SAFETY: native cookie creation remains on the WebView2 owner STA. SDK owns every
    // returned interface; HSTRING arguments remain alive for their synchronous calls.
    unsafe {
        let manager = view2.CookieManager().map_err(|_| ())?;
        for (name, domain, path) in [
            ("ehviewer-domain", ".e-hentai.org", "/private"),
            ("ehviewer-host", "e-hentai.org", "/"),
        ] {
            let cookie = manager
                .CreateCookie(
                    &HSTRING::from(name),
                    &HSTRING::from("synthetic-only"),
                    &HSTRING::from(domain),
                    &HSTRING::from(path),
                )
                .map_err(|_| ())?;
            cookie.SetIsHttpOnly(true).map_err(|_| ())?;
            cookie.SetIsSecure(true).map_err(|_| ())?;
            cookie.SetExpires(expires).map_err(|_| ())?;
            manager.AddOrUpdateCookie(&cookie).map_err(|_| ())?;
        }
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

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::Foundation::{LPARAM, WPARAM};
    use windows::Win32::System::Threading::GetCurrentThreadId;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetMessageW, PostThreadMessageW, WM_APP, WM_TIMER,
    };

    /// A posted fallback makes an actual broken message wait fail instead of hanging.
    fn bounded_queue_wait<T>(action: impl FnOnce() -> T) -> (T, bool) {
        // SAFETY: only drain/create this test thread's queue; it owns no application window.
        unsafe {
            let mut message = MSG::default();
            while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {}
        }
        let (done, finished) = mpsc::channel();
        // SAFETY: the current thread remains alive through the worker join below.
        let owner = unsafe { GetCurrentThreadId() };
        let worker = std::thread::spawn(move || {
            let fallback = finished.recv_timeout(Duration::from_secs(3)).is_err();
            if fallback {
                // SAFETY: post an inert thread message to the known live owner,
                // without a window pointer or data crossing the thread boundary.
                unsafe { PostThreadMessageW(owner, WM_APP, WPARAM(0), LPARAM(0)) }.unwrap();
            }
            fallback
        });
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(action));
        let _ = done.send(());
        let fallback = worker.join().unwrap();
        match result {
            Ok(value) => (value, fallback),
            Err(panic) => std::panic::resume_unwind(panic),
        }
    }

    #[test]
    fn the_startup_pulse_posts_a_timer_message_to_its_owner_thread() {
        let pump = StartupMessagePump::new().unwrap();
        let (message, fallback) = bounded_queue_wait(|| {
            let mut message = MSG::default();
            // SAFETY: one writable MSG on this owner thread, without a window filter.
            assert!(unsafe { GetMessageW(&mut message, None, 0, 0) }.0 > 0);
            message
        });
        assert!(!fallback);
        assert_eq!(message.message, WM_TIMER);
        assert_eq!(message.wParam.0, pump.timer);
    }

    #[test]
    fn the_startup_pulse_wakes_completion_and_releases_its_owned_timer() {
        let pump = StartupMessagePump::new().unwrap();
        let (sender, receiver) = mpsc::channel();
        let callback = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(50));
            sender.send(()).unwrap();
        });
        let (result, fallback) = bounded_queue_wait(|| webview2_com::wait_with_pump(receiver));
        callback.join().unwrap();
        result.unwrap();
        assert!(!fallback);
        let timer = pump.timer;
        drop(pump);
        // SAFETY: this is the creating thread. Removal must now fail because Drop
        // already removed this exact timer; no other thread owns or reuses it here.
        assert!(unsafe { KillTimer(None, timer) }.is_err());
    }
}
