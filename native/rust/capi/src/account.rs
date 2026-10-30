use super::*;

/// Parse the signed-in forum profile link (CBOR null for a guest forum page).
/// # Safety
/// input borrows length readable bytes; handle is a disjoint writable u64 slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_parse_profile_link(
    input: *const u8,
    length: usize,
    handle: *mut u64,
) -> i32 {
    // SAFETY: preserve the original bounded, owned-buffer parsing contract.
    unsafe {
        parse_document(input, length, handle, |bytes| {
            ehviewer_core::parser::account::parse_profile_link_cbor(bytes).map_err(|_| EHV_PARSE)
        })
    }
}

/// Parse a forum account profile through the original parser.
/// # Safety
/// input borrows length readable bytes; handle is a disjoint writable u64 slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_parse_account_profile(
    input: *const u8,
    length: usize,
    handle: *mut u64,
) -> i32 {
    // SAFETY: preserve the original bounded, owned-buffer parsing contract.
    unsafe {
        parse_document(input, length, handle, |bytes| {
            ehviewer_core::parser::account::parse_profile_cbor(bytes).map_err(|_| EHV_PARSE)
        })
    }
}

/// Parse the ten authenticated user-config favorite categories.
/// # Safety
/// input borrows length readable bytes; handle is a disjoint writable u64 slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ehv_parse_account_config(
    input: *const u8,
    length: usize,
    handle: *mut u64,
) -> i32 {
    // SAFETY: preserve the original bounded, owned-buffer parsing contract.
    unsafe {
        parse_document(input, length, handle, |bytes| {
            ehviewer_core::parser::account::parse_user_config_cbor(bytes).map_err(|_| EHV_PARSE)
        })
    }
}
