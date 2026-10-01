# Private test builds — 1 October 2026

Source: `64649835194df1254d526d0b49a677ef0a964e80`. Development builds only; not a release.

| Edition | Download | Starts with |
|---|---|---|
| Universal | [Universal APK](snapplication-universal-debug.apk) | Watch; existing preferences preserved |
| E-reader | [E-reader APK](snapplication-ereader-debug.apk) | Read and high-contrast E-paper theme |
| TV | [TV APK](snapplication-tv-debug.apk) | Watch/landscape; Android TV launcher entry |

Sign in to GitHub, open the APK, then click **Download raw file**. Install on Android 8 or newer. These three packages can coexist. The Universal build updates this laptop's prior development build. Files are signed with the existing local debug key; the key is not uploaded. Checksums are in SHA256SUMS.txt.

To add apps to Home: All Apps → outlined star beside an app. Filled stars remove favourites. Newly added favourites occupy the first slot.

Build/lint and seven unit tests pass per edition. Android 15 emulator checks verified Universal favourite add/remove/restart persistence, E-reader defaults, TV landscape/launcher registration and D-pad favourite addition. Actual TV hardware and vendor e-ink refresh remain unverified. No provider plugins are included.
