# Private test builds — 7 October 2026

Source commit: 56df996. Development builds only; no public release.

- [Universal APK](snapplication-universal-debug.apk)
- [E-reader APK](snapplication-ereader-debug.apk)
- [TV APK](snapplication-tv-debug.apk)

Sign in to the private GitHub repository and use Download raw file. Android 8 or newer. Packages can coexist. Signed with the existing local development key; signing key is not uploaded. SHA256SUMS.txt verifies the delivered files.

This update refines remote mapping and diagnostics, adds macro touch markers and a blank timing preview, pauses recording on excluded screens, improves wallpaper cropping, opens favourites editing directly from Home plus, and avoids unnecessary player orientation changes. All three builds, lint checks and 30 unit tests passed. See [update and verification notes](../docs/UPDATE-2026-10-07.md).

Play Protect may block downloaded APKs because of the optional accessibility and notification services. That Google-side block is not cleared by a successful developer installation. Keep protection enabled. Physical remote wake behaviour, Huawei HDMI terms and third-party buffer retention require further hardware/app-specific tests.

