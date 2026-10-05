# Private test builds — 5 October 2026

Source: `f4222193175dcb321992b6aca62a094f3f79ea17`. Development builds only; not a release.

| Edition | Download | Starts with |
|---|---|---|
| Universal | [Universal APK](snapplication-universal-debug.apk) | Watch; existing preferences preserved |
| E-reader | [E-reader APK](snapplication-ereader-debug.apk) | Read and high-contrast E-paper theme |
| TV | [TV APK](snapplication-tv-debug.apk) | Watch/landscape; Android TV launcher entry |

Sign in to GitHub, open the APK, then click **Download raw file**. Install on Android 8 or newer. These three packages can coexist. The Universal build updates this laptop's prior development build. Files are signed with the existing local debug key; the key is not uploaded. Checksums are in SHA256SUMS.txt.

To add apps: All Apps → Favourites. Tap an app to add/remove it. Drag icons in the live Home preview to reorder, or select an icon with the remote for move actions. The full-height alphabet rail supports touch scrubbing and remote navigation.

Build/lint and ten unit tests pass per edition. Mate 10 Pro Android10 checks verified the alphabet rail, remote navigation, favourites add/remove, live preview drag/drop, tutorial navigation, icon theme switching and cross-app Clock-to-Calculator replay, including immediate cancellation and context-guarded matching. Physical remote Android translation, HDMI/TV and e-ink refresh remain unverified. No provider plugins are included. See [developer handoff](../docs/HANDOFF-2026-10-04.md) for evidence and remaining work.

This update adds default-on Open apps in landscape (Display & input), corrects Home layout after rotation, and broadens detection to video surfaces plus their own media sessions. Grayjay and VLC frame capture were checked on the Mate 10 Pro. Fullscreen and resume still depend on what each app exposes. The setting can be disabled.

