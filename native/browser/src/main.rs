use serde::Serialize;
use std::io::{self, BufRead, IsTerminal, Read, Write};
use std::time::{Duration, Instant};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::platform::run_return::EventLoopExtRunReturn;
use tao::window::WindowBuilder;
use url::Url;
use wry::{NewWindowResponse, PermissionResponse, WebViewBuilder};

#[cfg(target_os = "windows")]
#[path = "windows.rs"]
mod windows_backend;

#[cfg(target_os = "linux")]
#[path = "linux.rs"]
mod linux_backend;

#[derive(Debug)]
enum Command {
    Cookies,
    Close,
    Verify,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CookieTransfer {
    name: String,
    value: String,
    domain: String,
    path: String,
    secure: bool,
    http_only: bool,
    expires_at: Option<i128>,
}

#[derive(Serialize)]
struct Response {
    protocol: u8,
    status: &'static str,
    cookies: Vec<CookieTransfer>,
}

fn official_navigation(text: &str) -> bool {
    Url::parse(text).is_ok_and(|url| {
        url.scheme() == "https"
            && url.port_or_known_default() == Some(443)
            && url.username().is_empty()
            && url.password().is_none()
            && matches!(
                url.host_str(),
                // Wry's portable callback has no frame identity. Permit only the
                // official challenge origin as well; its cookies are never exported.
                Some(
                    "e-hentai.org"
                        | "exhentai.org"
                        | "forums.e-hentai.org"
                        | "challenges.cloudflare.com"
                )
            )
    })
}

fn emit(response: Response) -> Result<(), ()> {
    let bytes = serde_json::to_vec(&response).map_err(|_| ())?;
    if bytes.len() > 256 * 1024 {
        return Err(());
    }
    let mut output = io::stdout().lock();
    output.write_all(&bytes).map_err(|_| ())?;
    output.write_all(b"\n").map_err(|_| ())?;
    output.flush().map_err(|_| ())
}

#[cfg(target_os = "macos")]
fn native_cookies(view: &wry::WebView) -> Result<Vec<CookieTransfer>, ()> {
    use objc2_foundation::{NSArray, NSDate, NSHTTPCookie, NSRunLoop};
    use std::ptr::NonNull;
    use std::sync::mpsc::{self, TryRecvError};
    use wry::WebViewExtMacOS;
    let (sender, receiver) = mpsc::channel();
    // WebKit invokes its completion on the main run loop. The callback owns only
    // the channel, so a timeout cannot leave a reference to a returned stack frame.
    unsafe {
        view.webview()
            .configuration()
            .websiteDataStore()
            .httpCookieStore()
            .getAllCookies(&block2::RcBlock::new(
                move |items: NonNull<NSArray<NSHTTPCookie>>| {
                    let cookies = items
                        .as_ref()
                        .to_vec()
                        .iter()
                        .map(|cookie| CookieTransfer {
                            name: cookie.name().to_string(),
                            value: cookie.value().to_string(),
                            domain: cookie.domain().to_string(),
                            path: cookie.path().to_string(),
                            secure: cookie.isSecure(),
                            http_only: cookie.isHTTPOnly(),
                            expires_at: cookie
                                .expiresDate()
                                .map(|date| (date.timeIntervalSince1970() * 1000.0) as i128),
                        })
                        .collect();
                    let _ = sender.send(cookies);
                },
            ));
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            match receiver.try_recv() {
                Ok(cookies) => return Ok(cookies),
                Err(TryRecvError::Disconnected) => return Err(()),
                Err(TryRecvError::Empty) if Instant::now() >= deadline => return Err(()),
                Err(TryRecvError::Empty) => NSRunLoop::currentRunLoop()
                    .runUntilDate(&NSDate::dateWithTimeIntervalSinceNow(0.01)),
            }
        }
    }
}

#[cfg(target_os = "macos")]
fn verify_native_store(view: &wry::WebView) -> Result<usize, ()> {
    use objc2_foundation::{NSDate, NSDictionary, NSHTTPCookie, NSRunLoop, NSString, NSURL};
    use wry::WebViewExtMacOS;
    if !native_cookies(view)?.is_empty() {
        return Err(());
    }
    unsafe {
        let store = view.webview().configuration().websiteDataStore();
        if store.isPersistent() {
            return Err(());
        }
        for header in [
            "ehviewer-domain=synthetic-only; Domain=.e-hentai.org; Path=/private; Secure; HttpOnly; Max-Age=3600",
            "ehviewer-host=synthetic-only; Path=/; Secure; HttpOnly; Max-Age=3600",
        ] {
            let headers = NSDictionary::from_slices(
                &[&*NSString::from_str("Set-Cookie")],
                &[&*NSString::from_str(header)],
            );
            let url =
                NSURL::URLWithString(&NSString::from_str("https://e-hentai.org/")).ok_or(())?;
            let cookies = NSHTTPCookie::cookiesWithResponseHeaderFields_forURL(&headers, &url);
            if cookies.len() != 1 {
                return Err(());
            }
            let (sender, receiver) = std::sync::mpsc::channel();
            store.httpCookieStore().setCookie_completionHandler(
                &cookies.objectAtIndex(0),
                Some(&block2::RcBlock::new(move || {
                    let _ = sender.send(());
                })),
            );
            let deadline = Instant::now() + Duration::from_secs(15);
            while receiver.try_recv().is_err() {
                if Instant::now() >= deadline {
                    return Err(());
                }
                NSRunLoop::currentRunLoop()
                    .runUntilDate(&NSDate::dateWithTimeIntervalSinceNow(0.01));
            }
        }
    }
    let cookies = native_cookies(view)?;
    let domain = cookies
        .iter()
        .find(|cookie| cookie.name == "ehviewer-domain")
        .ok_or(())?;
    let host = cookies
        .iter()
        .find(|cookie| cookie.name == "ehviewer-host")
        .ok_or(())?;
    if cookies.len() != 2
        || domain.domain != ".e-hentai.org"
        || host.domain != "e-hentai.org"
        || domain.path != "/private"
        || host.path != "/"
        || cookies.iter().any(|cookie| {
            !cookie.secure
                || !cookie.http_only
                || cookie.value != "synthetic-only"
                || cookie.expires_at.is_none()
        })
    {
        return Err(());
    }
    Ok(4)
}

#[cfg(target_os = "windows")]
fn verify_native_store(view: &wry::WebView) -> Result<usize, ()> {
    windows_backend::verify(view)
}

#[cfg(target_os = "linux")]
fn verify_native_store(view: &wry::WebView) -> Result<usize, ()> {
    linux_backend::verify(view)
}

#[cfg(target_os = "windows")]
fn native_cookies(view: &wry::WebView) -> Result<Vec<CookieTransfer>, ()> {
    windows_backend::cookies(view)
}

#[cfg(target_os = "linux")]
fn native_cookies(view: &wry::WebView) -> Result<Vec<CookieTransfer>, ()> {
    linux_backend::cookies(view)
}

fn run() -> Result<(), ()> {
    // This companion returns secrets only to the application's private process pipe.
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    let self_test = arguments.as_slice() == ["--self-test"];
    let bridge_regression = arguments.as_slice() == ["--bridge-regression"];
    let regression = self_test || bridge_regression;
    let started = Instant::now();
    let phase = |name| {
        if regression {
            eprintln!(
                "Native fixture phase={name} elapsed_ms={} pid={}",
                started.elapsed().as_millis(),
                std::process::id()
            );
        }
    };
    if !regression
        && (io::stdout().is_terminal()
            || arguments.len() != 2
            || arguments[0] != "--user-agent"
            || arguments[1].is_empty()
            || arguments[1].len() > 1024
            || arguments[1].chars().any(char::is_control))
    {
        return Err(());
    }
    phase("event-loop-start");
    let mut events = EventLoopBuilder::<Command>::with_user_event().build();
    phase("event-loop-ready");
    let window = WindowBuilder::new()
        .with_title("EhViewer — Browser sign-in")
        .with_inner_size(tao::dpi::LogicalSize::new(900.0, 720.0))
        .build(&events)
        .map_err(|_| ())?;
    phase("window-ready");
    let builder = WebViewBuilder::new()
        .with_incognito(true)
        .with_user_agent(if regression {
            "EhViewer/native-regression".to_owned()
        } else {
            arguments[1].clone()
        })
        .with_navigation_handler(move |url| {
            official_navigation(&url) || regression && url == "about:blank"
        })
        .with_permission_handler(|_| PermissionResponse::Deny)
        .with_download_started_handler(|_, _| false)
        .with_new_window_req_handler(|_, _| NewWindowResponse::Deny);
    let builder = if regression {
        builder.with_html("<!DOCTYPE html><html><head><title>EhViewer native regression</title></head><body style=\"margin:0;background:#1a4d80\">EhViewer native cookie regression</body></html>")
    } else {
        builder.with_url("https://forums.e-hentai.org/index.php?act=Login&CODE=00")
    };
    #[cfg(target_os = "linux")]
    let view = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder
            .build_gtk(window.default_vbox().ok_or(())?)
            .map_err(|_| ())?
    };
    #[cfg(not(target_os = "linux"))]
    let view = builder.build(&window).map_err(|_| ())?;
    phase("webview-ready");
    #[cfg(target_os = "windows")]
    if !windows_backend::private_store(&view)? {
        return Err(());
    }
    #[cfg(target_os = "linux")]
    if !linux_backend::private_store(&view)? {
        return Err(());
    }
    if !regression {
        window.set_focus();
    }

    let proxy = events.create_proxy();
    if regression {
        proxy.send_event(Command::Verify).map_err(|_| ())?;
    }
    if !self_test {
        if !bridge_regression {
            emit(Response {
                protocol: 1,
                status: "ready",
                cookies: vec![],
            })?;
        }
        std::thread::spawn(move || {
            let mut input = io::stdin().lock();
            loop {
                let mut line = Vec::new();
                // No unbounded read_line allocation from a broken or malicious peer.
                let read = input.by_ref().take(65).read_until(b'\n', &mut line);
                let command = match read {
                    Ok(0) | Err(_) => Command::Close,
                    Ok(_) if line == b"cookies\n" => Command::Cookies,
                    _ => Command::Close,
                };
                let stop = matches!(command, Command::Close);
                if proxy.send_event(command).is_err() || stop {
                    break;
                }
            }
        });
    }
    let deadline = Instant::now() + Duration::from_secs(if regression { 45 } else { 15 * 60 });
    let mut failed = false;
    let mut verified = false;
    phase("event-dispatch-start");
    events.run_return(|event, _, flow| {
        *flow = ControlFlow::WaitUntil(deadline);
        if Instant::now() >= deadline {
            *flow = ControlFlow::Exit;
        }
        match event {
            Event::UserEvent(Command::Verify) => {
                phase("verify-start");
                verified = verify_native_store(&view) == Ok(4);
                phase(if verified {
                    "verify-pass"
                } else {
                    "verify-fail"
                });
                failed = !verified;
                if bridge_regression && verified {
                    if emit(Response {
                        protocol: 1,
                        status: "ready",
                        cookies: vec![],
                    })
                    .is_err()
                    {
                        failed = true;
                        *flow = ControlFlow::Exit;
                    }
                } else {
                    *flow = ControlFlow::Exit;
                }
            }
            Event::UserEvent(Command::Close)
            | Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => *flow = ControlFlow::Exit,
            Event::UserEvent(Command::Cookies) => {
                phase("cookies-start");
                let result = native_cookies(&view).and_then(|cookies| {
                    let scoped = cookies
                        .into_iter()
                        .filter(|cookie| {
                            matches!(
                                cookie.domain.trim_start_matches('.'),
                                "e-hentai.org" | "exhentai.org" | "forums.e-hentai.org"
                            )
                        })
                        .collect::<Vec<_>>();
                    if scoped.len() > 128 {
                        return Err(());
                    }
                    emit(Response {
                        protocol: 1,
                        status: "cookies",
                        cookies: scoped,
                    })
                });
                phase(if result.is_ok() {
                    "cookies-pass"
                } else {
                    "cookies-fail"
                });
                if result.is_err() {
                    failed = true;
                    *flow = ControlFlow::Exit;
                }
            }
            _ => {}
        }
    });
    drop(view);
    drop(window);
    if failed || regression && !verified {
        Err(())
    } else if self_test {
        let version = wry::webview_version().map_err(|_| ())?;
        println!(
            "{}",
            serde_json::json!({"protocol":1,"status":"verified","checks":4,"architecture":std::env::consts::ARCH,"os":std::env::consts::OS,"engine_version":version})
        );
        Ok(())
    } else {
        emit(Response {
            protocol: 1,
            status: "closed",
            cookies: vec![],
        })
    }
}

fn main() {
    if run().is_err() {
        eprintln!("The native sign-in browser is unavailable");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn navigation_never_leaves_official_https_origins() {
        for url in [
            "https://e-hentai.org/",
            "https://exhentai.org/s/token/123-1",
            "https://forums.e-hentai.org/index.php?act=Login&CODE=00",
            "https://challenges.cloudflare.com/turnstile/",
        ] {
            assert!(official_navigation(url));
        }
        for url in [
            "https://e-hentai.org.evil.example/",
            "http://e-hentai.org/",
            "https://e-hentai.org:444/",
            "https://member:secret@e-hentai.org/",
            "file:///private",
            "javascript:alert(1)",
        ] {
            assert!(!official_navigation(url));
        }
    }
}
