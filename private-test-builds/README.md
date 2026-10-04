# Private test builds — 4 October 2026

Source: `867de944f574334e8b75030aba469de70a78a4f0`. Development builds only; not a release.

| Edition | Download | Starts with |
|---|---|---|
| Universal | [Universal APK](snapplication-universal-debug.apk) | Watch; existing preferences preserved |
| E-reader | [E-reader APK](snapplication-ereader-debug.apk) | Read and high-contrast E-paper theme |
| TV | [TV APK](snapplication-tv-debug.apk) | Watch/landscape; Android TV launcher entry |

Sign in to GitHub, open the APK, then click **Download raw file**. Install on Android 8 or newer. These three packages can coexist. The Universal build updates this laptop's prior development build. Files are signed with the existing local debug key; the key is not uploaded. Checksums are in SHA256SUMS.txt.

To add apps: All Apps → Favourites. Tap an app to add/remove it. Drag icons in the live Home preview to reorder, or select an icon with the remote for move actions. The full-height alphabet rail supports touch scrubbing and remote navigation.

Build/lint and ten unit tests pass per edition. Mate 10 Pro Android10 checks verified the alphabet rail, remote navigation, favourites add/remove, live preview drag/drop, tutorial navigation, icon theme switching and cross-app Clock-to-Calculator replay, including immediate cancellation and context-guarded matching. Physical remote Android translation, HDMI/TV and e-ink refresh remain unverified. No provider plugins are included. See [developer handoff](../docs/HANDOFF-2026-10-04.md) for evidence and remaining work.
