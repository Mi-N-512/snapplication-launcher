# Version 0.99 · private test builds

These are development APKs for the private 1.0 preparation cycle. No public release has been published.

| Download | Minimum Android | Content |
| --- | --- | --- |
| [Universal](snapplication-universal-debug.apk) | 8 | Read and Watch |
| [Modern Universal](snapplication-modern-debug.apk) | 11 | Read and Watch |
| [E-reader](snapplication-ereader-debug.apk) | 8 | Reading only |
| [TV](snapplication-tv-debug.apk) | 8 | Watching and remote controls |

All packages have version name **0.99** and version code **99**. Automatic still-page previews require Android 11+, accessibility support and an eligible reading screen. Both Universal editions include that implementation on supported Android versions. Read does not use continuous recording.

Sign in to GitHub, open the APK and select **Download raw file**. Install the same edition over its previous development build to keep its settings. The original local development signing key is retained; it is not uploaded. Use [SHA256SUMS.txt](SHA256SUMS.txt) to verify downloaded files.

## What was checked for this build

- All four editions built and passed Android lint with no new warnings.
- 68 unit tests passed per edition (272 across the four editions), including EPUB packaging and page-turn input.
- Android 15 emulator: EPUB and PDF reading inside Home and full screen, with the same place kept between the two; tap, swipe and Page Up/Down page turning; Tap-only viewer for books, videos and app previews.
- Local videos: progress bar, five-second next-episode countdown, and moving on after the completion threshold (tested at 90%, including screen off).
- A third-party video player paused on Home showed its saved frame after the screen turned off and on, and tapping it reopened the app at the same moment.
- Separate Read and Watch themes, the Circles favourites style, and Home with no panel outlines.

Physical HDMI, remote Power/Back, third-party streaming buffers and e-paper refresh were not re-tested for this build; the Huawei test install follows separately. Earlier device checks do not replace the final 1.0 hardware regression pass. Android and other apps can restrict previews, protected-screen actions and resume.

See the [main README](../README.md) for setup, screenshots, permissions, design goals and edition differences.
