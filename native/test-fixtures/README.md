# Native regression fixtures

These inputs are shared by Rust Core integration tests and Android JNI tests.
Keep the archives byte-for-byte stable: encrypted ZIPs, invalid headers, path
traversal, Unicode passwords, large declared sizes and random-access payloads
exercise behavior that plain text fixtures cannot reproduce.

`sort-oracle.json` records the original C natural-sort comparator's signed-char
results. ARM unsigned-char behavior has separate Rust assertions. `rar-expected.json`
contains expected RAR page digests; `sha256.json` records the additional fixtures.
The RAR5 inputs derive from libarchive's test suite; see `RAR5-LICENSE.txt`.
The other archive inputs were generated for this project's regression tests.
ZIP password fixtures use `baseline`, `密码`, or `密码🔑`; these are test passwords.

These files are test data and are not shipped in release APKs. Migration reports,
raw logs, generated APKs and diagnostic symbols do not belong in this directory.
