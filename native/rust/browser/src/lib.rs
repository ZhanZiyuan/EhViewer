//! Owned desktop companion client. This will sit behind JNI/C ABI, never behind a shell.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io::{self, BufReader, Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_FRAME: usize = 256 * 1024;

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CookieRecord {
    pub name: String,
    pub value: String,
    pub domain: String,
    pub path: String,
    pub secure: bool,
    pub http_only: bool,
    pub expires_at: Option<i64>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Reply {
    protocol: u8,
    status: String,
    cookies: Vec<CookieRecord>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    InvalidInput,
    Unavailable,
    Protocol,
    TimedOut,
    Closed,
    InvalidHandle,
    Limit,
}

const HANDLE_TAG: u64 = 1 << 62;
struct Registry {
    next: u64,
    clients: BTreeMap<u64, Arc<BrowserClient>>,
}
static CLIENTS: Mutex<Registry> = Mutex::new(Registry {
    next: HANDLE_TAG + 1,
    clients: BTreeMap::new(),
});

pub fn open(binary: &Path, user_agent: &str, regression: bool) -> Result<u64, Error> {
    let mut registry = CLIENTS.lock().map_err(|_| Error::Unavailable)?;
    if registry.clients.len() >= 4 || registry.next >= i64::MAX as u64 {
        return Err(Error::Limit);
    }
    // Reserve capacity under the same lock as spawn: concurrent opens cannot
    // briefly create unbounded companion processes before checking the limit.
    let client = Arc::new(BrowserClient::open(binary, user_agent, regression)?);
    let handle = registry.next;
    registry.next += 1;
    registry.clients.insert(handle, client);
    Ok(handle)
}

fn client(handle: u64) -> Result<Arc<BrowserClient>, Error> {
    CLIENTS
        .lock()
        .map_err(|_| Error::Unavailable)?
        .clients
        .get(&handle)
        .cloned()
        .ok_or(Error::InvalidHandle)
}

pub fn cookies(handle: u64) -> Result<Vec<CookieRecord>, Error> {
    client(handle)?.cookies()
}
pub fn cookies_json(handle: u64) -> Result<Vec<u8>, Error> {
    let bytes = serde_json::to_vec(&cookies(handle)?).map_err(|_| Error::Protocol)?;
    if bytes.len() > MAX_FRAME {
        return Err(Error::Protocol);
    }
    Ok(bytes)
}
pub fn process_id(handle: u64) -> Result<u32, Error> {
    client(handle)?.process_id()
}
pub fn running(handle: u64) -> Result<bool, Error> {
    Ok(client(handle)?.running())
}
pub fn active() -> Result<usize, Error> {
    Ok(CLIENTS
        .lock()
        .map_err(|_| Error::Unavailable)?
        .clients
        .len())
}

pub fn close(handle: u64) -> Result<(), Error> {
    client(handle)?.close()?;
    if CLIENTS
        .lock()
        .map_err(|_| Error::Unavailable)?
        .clients
        .remove(&handle)
        .is_none()
    {
        return Err(Error::InvalidHandle);
    }
    Ok(())
}

pub struct BrowserClient {
    child: Mutex<Child>,
    input: Mutex<ChildStdin>,
    output: Mutex<BufReader<TimedPipe>>,
    operation: Mutex<()>,
    closed: Arc<AtomicBool>,
}

// Read synchronously with OS readiness and deadlines: no background Rust thread can
// survive its JNI/C ABI library owner and execute code after a DLL is unloaded.
struct TimedPipe {
    output: ChildStdout,
    deadline: Instant,
    closed: Arc<AtomicBool>,
}

impl Read for TimedPipe {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        if bytes.is_empty() {
            return Ok(0);
        }
        loop {
            if self.closed.load(Ordering::Acquire) {
                return Err(io::ErrorKind::ConnectionAborted.into());
            }
            if Instant::now() >= self.deadline {
                return Err(io::ErrorKind::TimedOut.into());
            }
            #[cfg(unix)]
            {
                use std::os::fd::AsRawFd;
                let mut item = libc::pollfd {
                    fd: self.output.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                };
                // SAFETY: ChildStdout owns this valid fd for the entire locked read.
                let status = unsafe { libc::poll(&mut item, 1, 10) };
                if status < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(error);
                }
                if status > 0 {
                    return self.output.read(bytes);
                }
            }
            #[cfg(windows)]
            {
                use std::os::windows::io::AsRawHandle;
                use windows_sys::Win32::System::Pipes::PeekNamedPipe;
                let mut available = 0;
                // SAFETY: the borrowed pipe handle remains owned by ChildStdout; only
                // this operation reads it, and available is a writable u32 output slot.
                let success = unsafe {
                    PeekNamedPipe(
                        self.output.as_raw_handle(),
                        std::ptr::null_mut(),
                        0,
                        std::ptr::null_mut(),
                        &mut available,
                        std::ptr::null_mut(),
                    )
                };
                if success == 0 {
                    return Err(io::Error::last_os_error());
                }
                if available > 0 {
                    let count = bytes.len().min(available as usize);
                    return self.output.read(&mut bytes[..count]);
                }
                std::thread::sleep(Duration::from_millis(5));
            }
        }
    }
}

fn frame(input: &mut impl Read) -> Result<Option<Vec<u8>>, Error> {
    let mut bytes = Vec::new();
    loop {
        let mut byte = [0u8; 1];
        match input.read(&mut byte) {
            Ok(0) if bytes.is_empty() => return Ok(None),
            Ok(0) => return Err(Error::Protocol),
            Ok(_) if byte[0] == b'\n' => return Ok(Some(bytes)),
            Ok(_) if bytes.len() == MAX_FRAME => return Err(Error::Protocol),
            Ok(_) => bytes.push(byte[0]),
            Err(error) => {
                return Err(match error.kind() {
                    io::ErrorKind::TimedOut => Error::TimedOut,
                    io::ErrorKind::ConnectionAborted | io::ErrorKind::BrokenPipe => Error::Closed,
                    _ => Error::Unavailable,
                });
            }
        }
    }
}

fn decode(bytes: &[u8]) -> Result<Reply, Error> {
    let reply: Reply = serde_json::from_slice(bytes).map_err(|_| Error::Protocol)?;
    if reply.protocol != 1
        || !matches!(reply.status.as_str(), "ready" | "cookies" | "closed")
        || reply.cookies.len() > 128
        || reply.status != "cookies" && !reply.cookies.is_empty()
    {
        return Err(Error::Protocol);
    }
    if reply.cookies.iter().any(|cookie| {
        cookie.name.is_empty()
            || cookie.name.len() > 256
            || cookie.value.len() > 16_384
            || cookie.path.len() > 2048
            || !cookie.path.starts_with('/')
            || !matches!(
                cookie.domain.trim_start_matches('.'),
                "e-hentai.org" | "exhentai.org" | "forums.e-hentai.org"
            )
            || cookie
                .name
                .chars()
                .chain(cookie.value.chars())
                .chain(cookie.path.chars())
                .any(char::is_control)
    }) {
        return Err(Error::Protocol);
    }
    Ok(reply)
}

impl BrowserClient {
    pub fn process_id(&self) -> Result<u32, Error> {
        self.child
            .lock()
            .map(|child| child.id())
            .map_err(|_| Error::Unavailable)
    }
    /// Starts the real native companion; no cookie data appears in process arguments.
    pub fn open(binary: &Path, user_agent: &str, regression: bool) -> Result<Self, Error> {
        if user_agent.is_empty()
            || user_agent.len() > 1024
            || user_agent.chars().any(char::is_control)
        {
            return Err(Error::InvalidInput);
        }
        let binary = binary.canonicalize().map_err(|_| Error::Unavailable)?;
        if !binary.is_file()
            || !matches!(
                binary.file_name().and_then(|name| name.to_str()),
                Some("ehviewer_browser" | "ehviewer_browser.exe")
            )
        {
            return Err(Error::InvalidInput);
        }
        let mut command = Command::new(binary);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            // Keep the private pipes, without an extra console beside WebView2.
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        if regression {
            command.arg("--bridge-regression");
        } else {
            command.args(["--user-agent", user_agent]);
        }
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            // Explicit regression loads only our static fixture/synthetic cookies.
            // Retain native sandbox/display diagnostics there; interactive login
            // still discards browser stderr so account content never enters logs.
            .stderr(if regression {
                Stdio::inherit()
            } else {
                Stdio::null()
            })
            .spawn()
            .map_err(|_| Error::Unavailable)?;
        let Some(input) = child.stdin.take() else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(Error::Unavailable);
        };
        let Some(output) = child.stdout.take() else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(Error::Unavailable);
        };
        let closed = Arc::new(AtomicBool::new(false));
        let output = BufReader::new(TimedPipe {
            output,
            deadline: Instant::now(),
            closed: Arc::clone(&closed),
        });
        Ok(Self {
            child: Mutex::new(child),
            input: Mutex::new(input),
            output: Mutex::new(output),
            operation: Mutex::new(()),
            closed,
        })
    }

    pub fn running(&self) -> bool {
        !self.closed.load(Ordering::Acquire)
            && self
                .child
                .lock()
                .is_ok_and(|mut child| child.try_wait().is_ok_and(|exit| exit.is_none()))
    }

    /// Serial requests cannot accept a late prior result: any timeout closes this client.
    pub fn cookies(&self) -> Result<Vec<CookieRecord>, Error> {
        let _operation = self.operation.lock().map_err(|_| Error::Unavailable)?;
        if !self.running() {
            return Err(Error::Closed);
        }
        let result = (|| {
            let mut input = self.input.lock().map_err(|_| Error::Unavailable)?;
            input
                .write_all(b"cookies\n")
                .and_then(|_| input.flush())
                .map_err(|_| Error::Unavailable)?;
            drop(input);
            let deadline = Instant::now() + Duration::from_secs(20);
            let mut output = self.output.lock().map_err(|_| Error::Unavailable)?;
            output.get_mut().deadline = deadline;
            loop {
                if self.closed.load(Ordering::Acquire) {
                    return Err(Error::Closed);
                }
                let bytes = frame(&mut *output)?.ok_or(Error::Closed)?;
                match decode(&bytes)? {
                    Reply {
                        status, cookies, ..
                    } if status == "cookies" => return Ok(cookies),
                    Reply { status, .. } if status == "ready" => continue,
                    _ => return Err(Error::Closed),
                }
            }
        })();
        if result.is_err() {
            let _ = self.close();
        }
        result
    }

    pub fn close(&self) -> Result<(), Error> {
        self.closed.store(true, Ordering::Release);
        let mut child = self.child.lock().map_err(|_| Error::Unavailable)?;
        if child.try_wait().map_err(|_| Error::Unavailable)?.is_some() {
            return Ok(());
        }
        // Kill is required even if the reader already saw EOF: the process may still exist.
        child.kill().map_err(|_| Error::Unavailable)?;
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if child.try_wait().map_err(|_| Error::Unavailable)?.is_some() {
                return Ok(());
            }
            if Instant::now() >= deadline {
                return Err(Error::TimedOut);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

impl Drop for BrowserClient {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn framing_rejects_unbounded_truncated_and_invalid_protocol_data() {
        assert_eq!(frame(&mut &b""[..]), Ok(None));
        assert_eq!(frame(&mut &b"{}\n"[..]), Ok(Some(b"{}".to_vec())));
        assert_eq!(frame(&mut &b"{}"[..]), Err(Error::Protocol));
        assert_eq!(
            frame(&mut vec![b'x'; MAX_FRAME + 1].as_slice()),
            Err(Error::Protocol)
        );
        for data in [
            r#"{"protocol":2,"status":"cookies","cookies":[]}"#,
            r#"{"protocol":1,"status":"fake-success","cookies":[]}"#,
            r#"{"protocol":1,"status":"ready","cookies":[],"unknown":"secret"}"#,
        ] {
            assert!(decode(data.as_bytes()).is_err());
        }
    }
    #[test]
    fn cookie_values_and_metadata_are_exact_and_foreign_scopes_are_rejected() {
        let good = br#"{"protocol":1,"status":"cookies","cookies":[{"name":"identity","value":"synthetic+%/=","domain":".e-hentai.org","path":"/private","secure":true,"httpOnly":true,"expiresAt":1234567890000}]}"#;
        let reply = decode(good).unwrap();
        assert_eq!(reply.cookies[0].value, "synthetic+%/=");
        assert_eq!(reply.cookies[0].domain, ".e-hentai.org");
        assert!(reply.cookies[0].http_only && reply.cookies[0].secure);
        for bad in [
            String::from_utf8_lossy(good).replace(".e-hentai.org", ".evil.example"),
            String::from_utf8_lossy(good).replace("/private", "relative"),
            String::from_utf8_lossy(good)
                .replace("1234567890000", "999999999999999999999999999999999999999"),
        ] {
            assert!(decode(bad.as_bytes()).is_err());
        }
    }
}
