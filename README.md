# PocketSpeaker

Use an Android phone as a wireless speaker for Android TV devices over your local network.

The same normal APK is installed everywhere:

- On Android TV / Google TV / NVIDIA Shield / onn. devices it acts as the **sender**.
- On a regular Android phone it acts as the **receiver/speaker**.

## First MVP (0.1.0)

- One universal APK; no split APKs and no native libraries.
- Android 10 (API 29) or newer.
- Automatic TV discovery on the same local network.
- TV audio capture using Android's official `AudioPlaybackCapture` + `MediaProjection` APIs.
- Raw PCM audio streamed directly over the LAN; no cloud, account, or Internet service.
- Phone receiver runs as a foreground media-playback service so it can keep playing when the app is not in front.

## How to use

### TV / Shield / onn.

1. Install the APK normally.
2. Open **Pocket Speaker**.
3. Select **START TV AUDIO**.
4. Grant the audio permission and approve Android's screen/audio sharing prompt.
5. Switch to the app or media you want to hear. Pocket Speaker keeps running in the background.

### Phone

1. Install the same APK.
2. Open **Pocket Speaker** while connected to the same LAN/Wi-Fi.
3. TVs that have **START TV AUDIO** running should appear automatically.
4. Tap the TV name/IP.
5. Audio should begin playing through the phone speaker.

## Important Android limitation

Android only allows playback capture when the app producing the audio permits it. Media/game apps targeting modern Android generally allow capture by default, but an app can explicitly block capture. DRM/protected apps may therefore produce silence.

## Network ports

- UDP 50005: discovery/control
- TCP 50008: PCM audio stream

## Build

The included GitHub Actions workflow builds `app-debug.apk` and uploads it as the `PocketSpeaker-debug` artifact.
