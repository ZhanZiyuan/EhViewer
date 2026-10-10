//! Minimal libarchive 3.x ABI. Audited against the pinned 3.8.7 headers.
use std::ffi::{c_char, c_int, c_void};
#[repr(C)]
pub struct Archive {
    _private: [u8; 0],
}
#[repr(C)]
pub struct Entry {
    _private: [u8; 0],
}
#[cfg_attr(not(target_os = "android"), link(name = "archive"))]
unsafe extern "C" {
    pub fn archive_read_new() -> *mut Archive;
    pub fn archive_read_free(a: *mut Archive) -> c_int;
    pub fn archive_read_support_format_tar(a: *mut Archive) -> c_int;
    pub fn archive_read_support_format_7zip(a: *mut Archive) -> c_int;
    pub fn archive_read_support_format_rar5(a: *mut Archive) -> c_int;
    pub fn archive_read_support_format_zip(a: *mut Archive) -> c_int;
    pub fn archive_read_support_filter_gzip(a: *mut Archive) -> c_int;
    pub fn archive_read_support_filter_xz(a: *mut Archive) -> c_int;
    pub fn archive_read_set_option(
        a: *mut Archive,
        module: *const c_char,
        option: *const c_char,
        value: *const c_char,
    ) -> c_int;
    pub fn archive_read_add_passphrase(a: *mut Archive, p: *const c_char) -> c_int;
    pub fn archive_read_open_memory(a: *mut Archive, p: *const c_void, len: usize) -> c_int;
    pub fn archive_read_next_header(a: *mut Archive, e: *mut *mut Entry) -> c_int;
    pub fn archive_read_has_encrypted_entries(a: *mut Archive) -> c_int;
    pub fn archive_read_data(a: *mut Archive, p: *mut c_void, len: usize) -> isize;
    pub fn archive_read_data_block(
        a: *mut Archive,
        p: *mut *const c_void,
        len: *mut usize,
        offset: *mut i64,
    ) -> c_int;
    pub fn archive_entry_pathname(e: *mut Entry) -> *const c_char;
    pub fn archive_entry_size(e: *mut Entry) -> i64;
    pub fn archive_entry_filetype(e: *mut Entry) -> libc::mode_t;
    pub fn archive_entry_is_encrypted(e: *mut Entry) -> c_int;
    pub fn archive_error_string(a: *mut Archive) -> *const c_char;
    pub fn archive_write_new() -> *mut Archive;
    pub fn archive_write_free(a: *mut Archive) -> c_int;
    pub fn archive_write_close(a: *mut Archive) -> c_int;
    pub fn archive_write_set_format_zip(a: *mut Archive) -> c_int;
    pub fn archive_write_zip_set_compression_store(a: *mut Archive) -> c_int;
    pub fn archive_write_open_fd(a: *mut Archive, fd: c_int) -> c_int;
    pub fn archive_write_header(a: *mut Archive, e: *mut Entry) -> c_int;
    pub fn archive_write_data(a: *mut Archive, p: *const c_void, len: usize) -> isize;
    pub fn archive_write_finish_entry(a: *mut Archive) -> c_int;
    pub fn archive_entry_new() -> *mut Entry;
    pub fn archive_entry_free(e: *mut Entry);
    pub fn archive_entry_set_pathname(e: *mut Entry, name: *const c_char);
    pub fn archive_entry_set_size(e: *mut Entry, len: i64);
    pub fn archive_entry_set_filetype(e: *mut Entry, kind: libc::mode_t);
    pub fn archive_entry_set_perm(e: *mut Entry, perm: libc::mode_t);
}
