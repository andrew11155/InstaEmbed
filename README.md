# InstaEmbed

A simple Android app that downloads Instagram videos and shares them directly to Discord (or any other app) via the system share sheet. No accounts, no proxy services, no servers — everything runs on your device.

## How It Works

1. Find a reel or video on Instagram
2. Tap **Share** → select **InstaEmbed**
3. The app fetches the video using Instagram's GraphQL API
4. The system share sheet opens — pick Discord and your channel
5. Done. The video uploads from your account and temp files are auto-deleted.

You can also open the app directly and paste an Instagram URL manually.

## Why?

Services like kkinstagram, ddinstagram, vxinstagram, and InstaFix that proxy Instagram embeds for Discord are unreliable and frequently go down. InstaEmbed takes a different approach — instead of proxying embed URLs, it downloads the actual video file and sends it through Discord's normal file upload. This means:

- No dependency on third-party proxy services
- No accounts or API keys required
- Works entirely on-device
- Videos show up as actual file uploads in Discord, not broken embed links

## Tech Stack

- **Flutter** — cross-platform UI (Android only for now)
- **Native Kotlin** — Instagram API calls, video downloading, and sharing are handled entirely in native Android code for speed. The Flutter engine is only used for the manual URL input screen.
- **Instagram GraphQL API** — fetches video URLs from public posts without authentication
- **Android MediaCodec** — experimental video compression for files that exceed Discord's upload limits

## Current Status

This was a fun project built in a day to scratch a personal itch. Most of the core functionality works well:

- Sharing Instagram reels/videos to Discord via the system share sheet
- Headless mode — share from Instagram and never see the app UI
- Temp file auto-cleanup after sharing

### Known Issues

- **Video compression is experimental** — the MediaCodec-based compressor can handle some videos but isn't reliable yet. Videos under 10MB (most reels) work fine without compression. Longer or higher-resolution videos may exceed Discord's file limit.

### Future Improvements

- Fix video compression reliability
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

## License

MIT
