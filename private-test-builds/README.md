# Private test builds - 12 October 2026

Source commit: 8e52615. Development builds only; no public release.

- [Universal APK](snapplication-universal-debug.apk)
- [E-reader APK](snapplication-ereader-debug.apk)
- [TV APK](snapplication-tv-debug.apk)

Sign in to the private repository and use Download raw file. Android 8 or newer; automatic still reading-page previews require Android 11 or newer. Packages can coexist. Existing local development signing key retained; the key is not uploaded. SHA256SUMS.txt verifies the files.

Reader now uses automatic still screenshots instead of continuous recording, resumes supported Meebook Read books from internal or SD storage, keeps its settings reading-only, and enlarges the portrait preview to 72% of the main area. Fit, Crop and Stretch are selectable in Reading > Home preview > Picture sizing. Watch capture remains available in Universal/TV.

All three editions compiled and passed lint and 63 unit tests. The 12 October update removes redundant remote focus checks while preserving text-entry protection. Universal was installed and checked on the Mate over USB: a three-step macro recorded/replayed once, and the screen-off control worked. Physical remote/HDMI behavior remains unverified. [USB verification](../docs/mate-usb-qa-2026-10-12.md).

Macro name and timing forms now retain invalid input for correction, explain errors beside the field,
and reacquire accessibility support after a reconnect. Empty-name correction, rejected timing,
zero-delay saving/persistence and Cancel were checked on the Mate using a temporary recording.

Earlier physical Meebook checks covered Mihon, PDF, EPUB and CBZ; these were not repeated for this remote-only change. Read's silent page canvas is checked every five seconds and may retain an older preview if Home is opened immediately after turning a page. Protected/unsupported readers and unknown vendor versions have limitations; no universal parser or battery-life claim is made.

[Device verification](../docs/reader-qa-2026-10-09.md) | [Code review](../docs/reader-review-2026-10-11.md)
