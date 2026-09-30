# Pocket Speaker Windows sender

First Windows test build. This app captures Windows system audio with WASAPI loopback and presents the PC as a normal Pocket Speaker source to the existing Android phone receiver.

Protocol compatibility:
- discovery: UDP 50005, `PS1|DISCOVER` / `PS1|SENDER|<name>`
- connect: `PS2|CONNECT|...`
- low latency audio: UDP 50009, `PSU1` header
- stable mode: TCP 50008, `PSK4` stream header
- audio: PCM 16-bit little-endian, 48 kHz stereo

The phone should discover the Windows PC alongside Android TV senders.

## Build

Use the **Build Pocket Speaker Windows** GitHub Actions workflow and download the `PocketSpeaker-Windows-x64` artifact.


Placeholder icon file is not committed here because the GitHub text connector cannot create binary ICO content. The project references app.ico; the Windows workflow creates a temporary fallback icon before build until the Hi-Res Pocket Speaker ICO is added.
