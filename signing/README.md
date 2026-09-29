# PocketSpeaker release signing

The release signing key is stored in this repository only in encrypted form:

- `pocketspeaker-release.p12.enc`

It is encrypted with AES-256-CBC using PBKDF2 (250,000 iterations).

The decryption/signing password is **not** stored in the repository. GitHub Actions expects it in the repository secret:

`POCKETSPEAKER_SIGNING_PASSWORD`

The decrypted file `pocketspeaker-release.p12` is ignored by Git.

Signing alias: `pocketspeaker`

Certificate SHA-256 fingerprint:

`A8:32:D6:31:96:60:FD:16:90:8F:87:E4:6C:39:7F:42:99:34:5F:CF:31:43:AF:CA:DD:7E:40:D2:01:46:C1:86`

Keep the signing password backed up securely. Future Android updates must use the same signing key.
