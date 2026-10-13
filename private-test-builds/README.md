# Private test builds - 14 October 2026

Source commit: 0faab1e. Development builds only; no public release.

- [Universal APK](snapplication-universal-debug.apk)
- [E-reader APK](snapplication-ereader-debug.apk)
- [TV APK](snapplication-tv-debug.apk)

Sign in to the private repository and use Download raw file. Android 8 or newer; automatic still reading-page previews require Android 11 or newer. Packages can coexist. Existing local development signing key retained; the key is not uploaded. SHA256SUMS.txt verifies the files.

Reader now uses automatic still screenshots instead of continuous recording, resumes supported Meebook Read books from internal or SD storage, keeps its settings reading-only, and enlarges the portrait preview to 72% of the main area. Fit, Crop and Stretch are selectable in Reading > Home preview > Picture sizing. Watch capture remains available in Universal/TV.

All three editions compiled and passed lint and 63 unit tests. The 12 October update removes redundant remote focus checks while preserving text-entry protection. Universal was installed and checked on the Mate over USB: a three-step macro recorded/replayed once, and the screen-off control worked. Physical remote/HDMI behavior remains unverified. [USB verification](../docs/mate-usb-qa-2026-10-12.md).

Macro name and timing forms now retain invalid input for correction, explain errors beside the field,
and reacquire accessibility support after a reconnect. Empty-name correction, rejected timing,
zero-delay saving/persistence and Cancel were checked on the Mate using a temporary recording.
Both macro deletion entry points now ask for confirmation and support saved-data deletion without
an accessibility connection. List Cancel and confirmed deletion were checked on the Mate; the
disconnected-service path was reviewed without changing the user's accessibility permission.

Earlier physical Meebook checks covered Mihon, PDF, EPUB and CBZ; these were not repeated for this remote-only change. Read's silent page canvas is checked every five seconds and may retain an older preview if Home is opened immediately after turning a page. Protected/unsupported readers and unknown vendor versions have limitations; no universal parser or battery-life claim is made.

[Device verification](../docs/reader-qa-2026-10-09.md) | [Code review](../docs/reader-review-2026-10-11.md)

13 October: Settings subpages and remote-learning dialogs survive activity recreation. Watch previews reject visible loading indicators, track the latest eligible media app, and retry warm playback after opening the retained app task. Completion-threshold handling on explicit Play now guards against repeated Next commands and supports CloudStream's visible player controls.

All three editions passed build, lint and 72 unit tests. On the Mate, Family Guy produced a fresh clean Home frame and resumed the same episode near the saved position with its buffer retained. Playback checks were under ten seconds each. Settings/remote-learning restoration passed a recreation check. CloudStream next-episode advancement remains unverified on-device: the tested episode advertised no Next action. Physical remote/HDMI behavior remains unverified over the USB-only connection. Android controls capture consent; apps control their own buffer retention.

14 October: Touch macros now resolve the same guarded top-window owner during recording and replay, allowing a vendor dialog to differ from Android's active app. Screen identity, orientation, private-input and protected-window checks remain enforced. The Home Favourites heading inherits its panel's glass surface. All three editions passed build/lint and 81 tests; reverting the owner fix caused two regression tests to fail. On the Mate a temporary three-step recording replayed once to Settings and stopped. Actual Huawei Wired Projection dialog replay awaits the hub retest. The user confirmed HDMI recovered after power-cycling the hub and normal remote mappings work; physical Power already sleeps/wakes the phone directly through Android.
