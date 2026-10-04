# Mate 10 Pro checkpoint — 3 October 2026

Private work in progress; this is not a V1 completion or release claim.

## Preview

On the physical Huawei BLA-L09 running Android 10, the saved Cloudstream video frame fills the centre with aspect-preserving crop. Previous, Play and Next icons remain visible. Fit and Stretch remain selectable in Content sizing. Previous/Next are disabled when the selected app exposes no corresponding media-session command or notification access is unavailable.

The centre is a locally saved frame, not an embedded third-party activity. Play requests the app's media-session activity when available, otherwise brings its launcher task forward. The phone test found that this installed Cloudstream build releases its player and media session when sent to the background; Play subsequently opened its catalogue. Exact episode resume is therefore **not working in this tested configuration**. Do not describe it as a suspended embedded app or instant video resume.

Screen capture requires Android consent and is stopped by an app reinstall. The Home preview now shows a Preview paused shortcut when capture is off, so an old retained image does not silently imply live updates. After the user reported bandwidth waste, further video tests are limited to at most 10 seconds including loading. Capture was restarted after the latest installation; a watchdog paused playback and returned Home after 8.84 seconds. The Home image visibly changed from the old house exterior to the new video frame, filling the centre with visible controls. Cloudstream's session reported PAUSED afterward, and Previous became available (Next remained unsupported). No further streaming is needed for macro/UI checks.

Unlike the first backgrounding test, pausing before returning Home retained Cloudstream's session in this short test. That is narrower evidence than automatic instant resume: the source app's playing-versus-paused lifecycle needs further investigation without extended streaming.

## Macro investigation

The initial Clock-tab recording test reproduced an unexpected stop. Debug logs identified `AccessibilityService.onInterrupt()` during an ordinary Timer tab change. That callback interrupts accessibility feedback; it does not mean the service was disabled. Recording/replay now remain active through that callback. Actual unbind/destruction still cancels work. A brief foreground transition gets a delayed identity recheck; clicks are never recorded while the selected app cannot be verified.

Reference: [Android AccessibilityService.onInterrupt](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onInterrupt()).

Live recording/replay passed on the phone: the overlay counted two recorded taps, Stop saved the sequence, and Run reproduced Timer → Stopwatch → Timer. A fresh UI hierarchy observed each intermediate screen. The Timer transition generated the same feedback interrupts without destroying the recording or replay. Physical remote mapping, hardware wake and the HDMI terms popup remain unverified because the remote/display are not connected. The current recorder supports bounded tap sequences in one selected app; it does not yet record typing or arbitrary gestures.

## Validation boundary

## Remote priority and clean-preview follow-up

- Mapped Home now explicitly opens Snapplication instead of requesting Android's current default Home. The accessibility filter consumes the corresponding key release even after the foreground app changes. Learned hybrid remote keys no longer fail solely because they report a mouse input source. Navigation/escape assignments remain usable while a text field has focus.
- Settings Search now routes Down into the visible settings list. Physical-phone injection verified Search → Down → Select opens Appearance. The physical remote was disconnected; its cross-app Home/Back behavior awaits the owner's test. Android may reserve hardware Home/Power before accessibility receives them; mapping an ordinary delivered button to Home is supported.
- Cloudstream's visible controls, episode lists and non-player screens now disqualify a new snapshot. The previous clean frame stays instead. Subtitle subtrees are allowed. This app-specific rule is not a promise to remove UI from every third-party app.
- Capture replaces its image-buffer surface after each eligibility transition, so delayed buffers from an earlier window cannot be saved under a new window identity. Existing Cloudstream screenshots made under the older rules are no longer displayed as clean video frames. The first unobstructed video frame replaces them; until then the panel explains that a clean preview is needed.
- All three editions passed assembly, lint and existing unit tests after these changes. A bounded stream check stopped after 8.89 seconds including loading; no playable session was available before the deadline, so fresh clean-frame capture and menu retention are **not yet verified**. Do not claim that this test proved the clean-frame rule.

All three editions passed debug assembly, lint and their existing seven unit tests after the accessibility-interrupt correction and capture-paused indicator (48-second successful build). Existing unit tests cover ordering, episode-completion policy and thermal thresholds, not macro replay; the recording/replay evidence above comes from the physical phone.

The isolated phone QA helper uses `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Stock `uiautomator dump` can suppress the service being tested and is unsuitable for these capture/macro checks. No screenshots, recorded content or private macro labels are included in the repository.
