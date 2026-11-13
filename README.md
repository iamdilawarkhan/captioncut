# CaptionCut

Free Android app: auto captions (**Urdu / English / Roman Urdu**) for your
videos — with styles, drag-to-place captions, and one-tap export.

## Features

- Import a video from your gallery
- Auto captions in Urdu (Urdu script), English, or Roman Urdu, using your
  own free Gemini API key (enter it in app Settings)
- Caption styles: size, 10 colours, background, position (top / centre /
  bottom), bold, word highlight
- Drag captions anywhere on the video by hand — the position is saved and
  used in the export too
- Caption modes: Single word / Duo word / Single line (3–4 words)
- One-tap export: burned-in captions MP4, saved to Movies/CaptionCut
- In-app updates: the app checks GitHub Releases and shows an update
  banner when a newer version is out (or use Settings → Check for updates)

## Install

1. Download the APK from the latest release
   (Releases → `CaptionCut-vX.Y-debug.apk`).
2. On your phone, allow "Install unknown apps" for your browser or file
   manager when asked.
3. Install — updates install over the old version, no uninstall needed
   (same signing key).
4. Open Settings (⚙) and paste your free Gemini API key to generate
   captions.

## Build from source

Needs Android SDK 34 and JDK 17. Signing needs the owner's private
keystore, which is **never committed to this repo**:

```sh
export CAPTIONCUT_KS_PASS=<keystore password>
bash tools/build.sh
```

The script fails if the env var is unset. The APK lands in `deliver/`.
