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

- All four editions built and passed Android lint.
- 61 unit tests passed per edition (244 across the four editions).
- Android 15 emulator: local PDF rendering and local video playback; portrait/landscape, blocks/list, light, dark, OLED, E-paper and transparent screens.
- Backup/restore retained settings, favourites, mappings, macros and reference pictures; malformed backups were rejected.
- Profile checks covered portrait Read after TV setup, independent orientation choices and reading-only restore behaviour.
- Recorded taps replayed once, including current-screen coordinates and faster timing; no repeat after five seconds. Mapping-window lifecycle checks passed.

Physical HDMI, remote Power/Back, third-party streaming buffers and e-paper refresh were not re-tested for this packaging update. Earlier device checks do not replace the final 1.0 hardware regression pass. Android and other apps can restrict previews, protected-screen actions and resume.

See the [main README](../README.md) for setup, screenshots, permissions, design goals and edition differences.
