# Insta-recording

Portable Windows app that records any area of your screen and turns it into a 1080×1920 MP4 for Instagram Reels. Empty space is filled with an enlarged, blurred copy of the recording.

## Features

- Drag to choose the area, then a 3-2-1-0 countdown
- System audio recording (not the microphone)
- Optional 59-second limit
- Built-in updates from GitHub Releases

## Download

Get `Insta-recording.exe` from the [latest release](https://github.com/Futurehouser/Insta-recording/releases/latest). It needs no installation. The exe isn't signed, so Windows SmartScreen may warn you the first time you run it.

## Build

```powershell
pip install -r requirements.txt
.\build.ps1
```

## Publishing a new version

1. Raise `APP_VERSION` in `insta_recording.py`.
2. Run `.\build.ps1`.
3. Create a GitHub release tagged `vX.Y.Z` with `dist\Insta-recording.exe` attached under exactly that name.

Running copies of the app then offer the update on their next start.
