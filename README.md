# Insta-recording

Windows and Android app that records your screen and turns it into a 1080×1920 MP4 for Instagram Reels. Empty space is filled with an enlarged, blurred copy of the recording.

## Features

- Record the full screen, or drag to choose an area
- 3-2-1-0 countdown before recording
- System audio recording (not the microphone)
- Optional 59-second limit
- Built-in updates from GitHub Releases

## Download

Get the app from the [latest release](https://github.com/Futurehouser/Insta-recording/releases/latest):

- **Windows:** `Insta-recording.exe`. It needs no installation. The exe isn't signed, so Windows SmartScreen may warn you the first time you run it.
- **Android 10 or newer:** `Insta-recording.apk`. Android will ask you to allow installing apps from your browser or file manager.

## Using the Android app

1. Tap **Full screen** or **Select area**, and accept the screen capture prompt. On Android 14 or newer, choose to share the entire screen.
2. Open what you want to record, then tap **Start** or **Select area** on the floating panel.
3. After the countdown, recording starts. Stop it from the notification, or with the Stop button when recording an area.

Videos are saved to `Movies/Insta-recording`. Apps can block their sound from being captured, so some apps may record silently.

## Build

Windows:

```powershell
pip install -r requirements.txt
.\build.ps1
```

Android (needs JDK 17 and the Android SDK):

```powershell
cd android
.\gradlew.bat assembleRelease
```

Release builds are signed with `android/keystore/insta-recording.jks`, described by `android/keystore.properties`. Neither is in this repository. Keep a backup: without that key, phones can't update to new versions.

## Publishing a new version

1. Raise `APP_VERSION` in `insta_recording.py`, and `versionName` and `versionCode` in `android/app/build.gradle`.
2. Build both apps.
3. Create a GitHub release tagged `vX.Y.Z` with `Insta-recording.exe` and `Insta-recording.apk` attached under exactly those names.

The apps then offer the update on their next start.
