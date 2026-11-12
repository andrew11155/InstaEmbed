# InstaEmbed

A simple Android app that downloads Instagram videos or compresses any video file and shares them directly to Discord (or any other app) via the system share sheet. No accounts, no proxy services, no servers — everything runs on your device.

## How It Works

### Instagram Videos
1. Find a reel or video on Instagram
2. Tap **Share** → select **InstaEmbed**
3. The app fetches the video using Instagram's GraphQL API
4. The system share sheet opens — pick Discord and your channel
5. Done. The video uploads from your account. Shared files briefly appear in your gallery under `Movies/InstaEmbed` and are auto-deleted a couple of hours later.

### Any Video Files
1. In your Gallery, Photos, Files, or camera, tap **Share** on any video
2. Select **InstaEmbed** from the share sheet
3. If the video is larger than Discord's limit (20MB), InstaEmbed automatically compresses it on-device
4. The share sheet opens — pick Discord and your channel!

### Long Videos
If a video is too long to look decent squeezed into a single 20MB file, InstaEmbed splits it into equal parts (up to 10), each under 20MB. The share sheet opens for part 1; when you come back to InstaEmbed (or tap the "share part 2" notification) the next part's share sheet opens. Turn this off under **Settings → Split long videos**.

You can also open the app directly to paste an Instagram URL or pick a video file manually.

## Why?

Services like kkinstagram, ddinstagram, vxinstagram, and InstaFix that proxy Instagram embeds for Discord are unreliable and frequently go down. InstaEmbed takes a different approach — instead of proxying embed URLs, it downloads the actual video file and sends it through Discord's normal file upload. This means:

- No dependency on third-party proxy services
- No accounts or API keys required
- Works entirely on-device
- Videos show up as actual file uploads in Discord, not broken embed links
- Compress any oversized video file on-device to fit Discord's 20MB free upload limit

## Tech Stack

- **Flutter** — cross-platform UI (Android only for now)
- **Native Kotlin** — Instagram API calls, video downloading, and sharing are handled entirely in native Android code for speed. The Flutter engine is only used for the manual URL input / video picker screen.
- **Instagram GraphQL API** — fetches video URLs from public posts without authentication
- **Android MediaCodec** — fast, hardware-accelerated video compression targeting <= 20MB. Uses H.265 (HEVC) by default on devices with a hardware HEVC encoder (better quality at the same size), falling back to H.264. Audio is always re-encoded to AAC so its size is budgeted exactly.

## Current Status

This was a fun project built in a day to scratch a personal itch. Most of the core functionality works well:

- Sharing Instagram reels/videos to Discord via the system share sheet
- Sharing local video files from Gallery / Files to Discord with automatic 20MB compression
- Headless mode — share from Instagram or Gallery and never see the app UI
- Shared files go through the system media store (`Movies/InstaEmbed`) so Discord can finish uploading even after Android freezes InstaEmbed in the background; they're auto-deleted ~2 hours later
- Compression runs in a foreground service, so locking the phone or switching apps doesn't pause it
- On-device video compression (MediaCodec) for files over Discord's free upload limit (20MB)
- Automatic splitting of long videos into multiple <20MB parts, shared one at a time
- H.265 (HEVC) or H.264 output, selectable in Settings
- Self-updating — checks GitHub Releases in the background and prompts to install new versions

### Known Issues

- **Public posts only** — private/restricted accounts won't work
- **H.265 playback on Linux** — some Linux Discord desktop setups can't decode HEVC; turn off **Use H.265** in Settings if friends can't play your clips

### Future Improvements
- iOS support
- Photo/carousel post support
- Better error handling for private/restricted posts

## Building

```bash
flutter pub get
flutter build apk --debug
```

Requires:
- Flutter SDK 3.44+
- Android SDK 36+ (compileSdk 37)
- Min SDK 26 (Android 8.0+)

### Testing compression from adb (debug builds only)

Debug builds include `CompressTestActivity`, which runs the compressor on a file in the app's private `files/` dir:

```bash
adb shell run-as com.instaembed.instaembed cp /data/local/tmp/in.mp4 files/in.mp4
adb shell am start -n com.instaembed.instaembed/.CompressTestActivity \
  --es input in.mp4 --es output out.mp4 [--el start US --el end US] [--es mime video/hevc] [--ez plan true]
adb logcat -s CompressTest VideoCompressor
```

## Updates

InstaEmbed checks `github.com/andrew11155/InstaEmbed`'s latest release in the background (at most once every 12 hours — on app open and after each share) and compares it against the installed `versionName`. If a newer tag has an `.apk` asset attached, it shows a notification; tapping it downloads the APK and hands it to the system installer. Since the app isn't distributed through the Play Store, Android still requires the user to approve installing it once ("Install unknown apps" for InstaEmbed) — this can't be skipped.

**When cutting a release:** bump `version:` in `pubspec.yaml` to match the git tag (e.g. `1.3.0+4` for tag `v1.3.0`) *before* building the release APK, and attach the built `.apk` as a release asset. If the embedded version and the tag drift, update detection breaks.

## License

MIT
