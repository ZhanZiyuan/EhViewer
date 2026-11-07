use super::*;
use ehviewer_browser_client as client;

fn error(error: client::Error) -> i32 {
    match error {
        client::Error::InvalidInput => EHV_INVALID_ARGUMENT,
        client::Error::InvalidHandle => EHV_INVALID_HANDLE,
        client::Error::Limit => EHV_LIMIT,
        _ => EHV_INTERNAL,
    }
}

unsafe fn text<'a>(input: *const u8, length: usize, maximum: usize) -> Result<&'a str, i32> {
    if input.is_null() || length == 0 || length > maximum {
        return Err(EHV_INVALID_ARGUMENT);
    }
    // SAFETY: each caller borrows a readable byte slice only for this ABI call.
    let bytes = unsafe { std::slice::from_raw_parts(input, length) };
    let text = std::str::from_utf8(bytes).map_err(|_| EHV_INVALID_ARGUMENT)?;
    if text.contains('\0') {
        return Err(EHV_INVALID_ARGUMENT);
    }
    Ok(text)
}

#[unsafe(no_mangle)]
pub extern "C" fn ehv_browser_api_version() -> u32 {
    1
}

/// Open a desktop native browser. Mode 0 is interactive; mode 1 is explicit native
/// regression only, with synthetic non-identity cookies and no external requests.
/// # Safety
/// Input slices are readable for their lengths; handle is a disjoint writable u64.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_browser_open(
    binary: *const u8,
    binary_length: usize,
    user_agent: *const u8,
    user_agent_length: usize,
    mode: u32,
    handle: *mut u64,
) -> i32 {
    if handle.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller supplies one writable output slot.
    unsafe {
        *handle = 0;
    }
    boundary(|| {
        if mode > 1 {
            return Err(EHV_INVALID_ARGUMENT);
        }
        // SAFETY: input contracts are validated before borrowing each slice.
        let binary = unsafe { text(binary, binary_length, 65536)? };
        let user_agent = unsafe { text(user_agent, user_agent_length, 1024)? };
        let opened =
            client::open(std::path::Path::new(binary), user_agent, mode == 1).map_err(error)?;
        // SAFETY: the output remains writable for this call, with no Rust pointer escaping.
        unsafe {
            *handle = opened;
        }
        Ok(())
    })
}

/// Read real browser metadata as bounded JSON through the existing owned-buffer ABI.
/// # Safety
/// output is a writable u64 slot disjoint from all borrowed call inputs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_browser_cookies(handle: u64, output: *mut u64) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller supplies one writable output slot.
    unsafe {
        *output = 0;
    }
    boundary(|| {
        let bytes = client::cookies_json(handle).map_err(error)?;
        let retained = retain(bytes)?;
        // SAFETY: only the owned numeric buffer ID is returned, not its pointer.
        unsafe {
            *output = retained;
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn ehv_browser_close(handle: u64) -> i32 {
    boundary(|| client::close(handle).map_err(error))
}

/// # Safety
/// output must be one writable u64 slot for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_browser_process_id(handle: u64, output: *mut u64) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller supplies one writable output slot.
    unsafe {
        *output = 0;
    }
    boundary(|| {
        let pid = client::process_id(handle).map_err(error)?;
        // SAFETY: the output remains writable and PID is a value, not a process handle.
        unsafe {
            *output = u64::from(pid);
        }
        Ok(())
    })
}

/// # Safety
/// output must be one writable u32 slot for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_browser_running(handle: u64, output: *mut u32) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller supplies one writable output slot.
    unsafe {
        *output = 0;
    }
    boundary(|| {
        let running = client::running(handle).map_err(error)?;
        // SAFETY: only the scalar status is written to the caller's slot.
        unsafe {
            *output = u32::from(running);
        }
        Ok(())
    })
}

/// # Safety
/// output must be one writable size_t slot for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_browser_active(output: *mut usize) -> i32 {
    if output.is_null() {
        return EHV_INVALID_ARGUMENT;
    }
    // SAFETY: the caller supplies one writable output slot.
    unsafe {
        *output = 0;
    }
    boundary(|| {
        let active = client::active().map_err(error)?;
        // SAFETY: the output remains writable for this call.
        unsafe {
            *output = active;
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_browser_borrows_clear_outputs_without_starting_a_process() {
        let mut handle = 77;
        // SAFETY: the output is valid; null inputs exercise rejection before borrowing.
        unsafe {
            assert_eq!(
                ehv_browser_open(std::ptr::null(), 1, std::ptr::null(), 1, 0, &mut handle),
                EHV_INVALID_ARGUMENT
            );
            assert_eq!(handle, 0);
            assert_eq!(
                ehv_browser_open(b"unused".as_ptr(), 6, b"test".as_ptr(), 4, 2, &mut handle),
                EHV_INVALID_ARGUMENT
            );
            assert_eq!(handle, 0);
            assert_eq!(
                ehv_browser_cookies(1, std::ptr::null_mut()),
                EHV_INVALID_ARGUMENT
            );
            assert_eq!(
                ehv_browser_active(std::ptr::null_mut()),
                EHV_INVALID_ARGUMENT
            );
        }
        assert_eq!(client::active(), Ok(0));
    }

    #[test]
    fn stale_and_wrong_resource_ids_never_return_cookie_bytes_or_close_an_owner() {
        let mut output = 99;
        let mut running = 99;
        // SAFETY: each scalar slot is writable and disjoint for the duration of its call.
        unsafe {
            assert_eq!(ehv_browser_cookies(1, &mut output), EHV_INVALID_HANDLE);
            assert_eq!(output, 0);
            output = 99;
            assert_eq!(ehv_browser_process_id(1, &mut output), EHV_INVALID_HANDLE);
            assert_eq!(output, 0);
            assert_eq!(ehv_browser_running(1, &mut running), EHV_INVALID_HANDLE);
            assert_eq!(running, 0);
        }
        assert_eq!(ehv_browser_close(1), EHV_INVALID_HANDLE);
        assert_eq!(ehv_browser_api_version(), 1);
        assert_eq!(client::active(), Ok(0));
    }
}
