//! Borrow caller descriptors without closing them. Clones share the current file offset.
use std::fs::File;
use std::io;
use std::os::fd::{FromRawFd, RawFd};

/// # Safety
/// `fd` must remain open for this call. A negative descriptor is rejected.
pub unsafe fn clone_fd(fd: RawFd) -> io::Result<File> {
    if fd < 0 {
        return Err(io::Error::from_raw_os_error(libc::EBADF));
    }
    // SAFETY: fcntl validates the raw descriptor in the kernel, including stale positive fds.
    // Only a successfully created duplicate is converted to an owned Rust File.
    let duplicate = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if duplicate < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { File::from_raw_fd(duplicate) })
}
