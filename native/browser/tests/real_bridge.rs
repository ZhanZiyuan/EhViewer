use ehviewer_browser_client::{self as client, Error};
use std::path::Path;

#[test]
fn actual_native_cookies_cross_the_private_pipe_and_owned_processes_close() {
    let binary = Path::new(env!("CARGO_BIN_EXE_ehviewer_browser"));
    let mut previous = None;
    for _ in 0..2 {
        let handle = client::open(binary, "EhViewer/real-bridge-regression", true).unwrap();
        assert!(handle > (1 << 62));
        assert_eq!(client::active().unwrap(), 1);
        let pid = client::process_id(handle).unwrap();
        assert_ne!(previous, Some(pid));
        assert!(pid > 0);
        previous = Some(pid);
        let records = client::cookies(handle).unwrap();
        assert_eq!(records.len(), 2);
        let domain = records
            .iter()
            .find(|cookie| cookie.name == "ehviewer-domain")
            .unwrap();
        let host = records
            .iter()
            .find(|cookie| cookie.name == "ehviewer-host")
            .unwrap();
        assert_eq!(domain.domain, ".e-hentai.org");
        assert_eq!(domain.path, "/private");
        assert_eq!(host.domain, "e-hentai.org");
        assert!(records.iter().all(|cookie| cookie.secure
            && cookie.http_only
            && cookie.expires_at.is_some()
            && cookie.value == "synthetic-only"));
        // Repeat on the same actual WebKit instance, not a cached response or a fabricated jar.
        assert_eq!(client::cookies(handle).unwrap().len(), 2);
        assert!(!client::cookies_json(handle).unwrap().is_empty());
        client::close(handle).unwrap();
        assert_eq!(client::active().unwrap(), 0);
        assert_eq!(client::running(handle), Err(Error::InvalidHandle));
        assert!(client::cookies(handle).is_err());
        assert_eq!(client::close(handle), Err(Error::InvalidHandle));
    }
}
