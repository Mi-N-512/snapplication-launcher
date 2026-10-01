# Development status

This is a private development checkpoint, not a completed V1 or a release.

## Implemented

- Native Android launcher entry point, installed-app drawer and app search.
- Read and Watch modes with independent last-item state.
- Local folder access through the Android system picker, including persisted read grants.
- Actual Media3 video playback in the home panel, media-session controls, saved playback position and ordered local episode queues.
- Configurable completion threshold to stage the next local episode when the screen turns off.
- Local PDF, CBZ and image reading with saved page position, natural comic page ordering, bounded decoding and one cached document copy per selection.
- Favourite app shortcuts, list/grid choices, 4/6/8 visible items and touch/remote-accessible reorder and removal controls; dark/light/OLED/Material You/custom/e-paper colour choices.
- Per-device remote button learning for directions, Select, Back, Home and Apps; normal text/dialog input, paired press handling, and a physical hold-Back recovery reset.
- Visual custom-colour picker with hue/saturation/brightness gradients and preview; Save commits both colours, Cancel preserves the current theme.
- Optional wallpaper behind Home, drawer and Settings through the shared Transparency toggle, with a system picker for static or installed live wallpapers.
- Refreshable device diagnostics: Android/device version, battery level and temperature, charging state, supported thermal status, display modes and input-device sources.
- Foreground thermal notices based on Android severe-to-shutdown statuses, sampled every 15 seconds while the launcher is visible. Monitoring stops when the activity stops.
- Class responsibility and relationship comments and documented methods.

## Evidence

On the Android 15 emulator on 23 September 2026, a local sample video played visibly inside the home panel and the media session reported PLAYING. A three-page PDF reopened at page two after force-stop and restart. A CBZ containing 1.png, 10.png and 2.png displayed page two before page ten. The first visual check exposed system-bar overlap, which was corrected with window insets; the subsequent screenshots showed the corrected layout. The narrow portrait folder button was subsequently shortened to avoid wrapping.

On 29 September 2026, the favourites layout and arrangement changes passed debug compilation and lint (no errors). At that checkpoint their visual and interaction checks were pending because the dedicated emulator initially started suspended. Restarting it with approved host permissions restored boot, but adb reports unauthorized even after reconnecting and restarting the server. Emulator and host public keys match. Remote settings compiled and passed lint at that checkpoint; subsequent 1 October entries record successful emulator interaction checks.

Seven unit tests cover numeric page ordering, long numeric filenames, completion thresholds, unknown-duration media and zero/100-percent boundaries, and thermal warning thresholds including unknown/missing readings. These tests do not prove hardware compatibility or every launcher feature.

See [the current requirements audit](requirements-audit.md) for explicit implementation gaps and evidence boundaries.

## Still required before V1 completion

- Final device testing on the Huawei Mate 10 Pro, USB-C HDMI hub and air mouse.
- End-to-end remote mapping, recovery, directional-navigation and accessibility checks on emulator and physical remote.
- Physical-device validation of screen-off episode transition and reboot resume; emulator transition and process-restart checks passed on 1 October.
- Emulator verification of favourite reordering and 4/6/8-item layouts, followed by closer final design fidelity.
- Device/emulator interaction and visual checks for the custom colour picker and static/live wallpaper integration.
- External-app resume behavior where those apps expose supported APIs; no universal process suspension guarantee.
- E-paper refresh capability detection and supported vendor integration; colour changes alone do not clear physical ghosting.
- Physical validation of thermal/display diagnostics and foreground temperature alerts, screen-control feasibility and opt-in macro support.
- Cleanup of lint warnings, broader error-case tests and the requested Design Report artifact.

HDMI CEC, bypass charging and independent phone/display power control must be tested for actual device support. No simulated success controls are included. The project does not require root.

## Deferred by the user

Direct provider plugins and their playback integration are excluded from V1. Keiyoushi/Mihon, Cloudstream, YouTube and Twitch integration research is retained for later. GameHub and the separate Game mode are deferred. Distribution licensing remains undecided. No public repository, GitHub release or store publication is authorized.

## Remote input implementation references

Device-specific persistence uses Android [InputDevice descriptors](https://developer.android.com/reference/android/view/InputDevice#getDescriptor()). Translated controls preserve [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent) press/release metadata. Mappings are local to launcher activity screens; they do not intercept other apps or system dialogs.

## Appearance checkpoint — 30 September 2026

The colour picker, cancel behavior and wallpaper controls pass debug compilation, unit tests and lint without errors. The picker uses generated view IDs and Android 8-compatible touch sizing. Visual/device verification remains pending. Wallpaper rendering uses Android’s [FLAG_SHOW_WALLPAPER](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER) with a transparent window background and a tinted Home surface; picking wallpaper is a user-confirmed system action that changes the device wallpaper.

## Device diagnostics checkpoint — 30 September 2026

Debug compilation, existing unit tests and lint pass. Runtime sensor values still require device validation. The diagnostics screen takes an on-demand snapshot. A separate foreground monitor now samples system thermal status while launcher screens are visible; it stops when the launcher activity stops. Android battery data comes from [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager); system thermal status uses [PowerManager](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()) on Android 10 and later. Display mode availability does not prove hub/CEC compatibility.

## Thermal notices checkpoint — 30 September 2026

Debug build and lint pass, and all seven unit tests pass. Two new tests check the severe threshold through shutdown and ensure missing, unknown and non-severe states do not produce overheating notices. Code review found no further issues. Runtime warning appearance, sensor reporting and lifecycle behavior remain to be tested on Android hardware. The application does not promise thermal protection or monitor other apps in the background.
## External media controls under development

An optional Other app playback screen is being added to the app drawer. It uses Android media sessions from installed apps after the user grants notification access. Provider plugins remain deferred. The screen does not embed another application's video or promise restoration of a closed session. It reads media-session metadata but has no implementation that reads, saves or forwards notification message content. Runtime access, revocation and transport-control checks remain pending.

The external-media checkpoint passes debug compilation, lint and the existing seven unit tests. Those tests do not exercise other apps' sessions. Code review identified toggle-only sessions; paired play/pause media-button support was added and the checks rerun successfully. Runtime verification remains pending. Build tooling can run without escalation by placing the compiler temporary files under the workspace; the command-line override is not committed as a machine-specific project setting.

On 30 September, the authenticated emulator console showed an actual USB debugging approval dialog. Enabling keyboard and directional input on the isolated AVD did not make console key events navigate that dialog. Reopening a visible emulator was not executed because automatic approval review hit its usage limit (retry time 4:33 PM). The latest external-media code is saved locally; GitHub and Downloads synchronization is pending.


## Wireframe and editions checkpoint — 1 October 2026

Reworked Home to remove extra toolbars and follow Make's 57% portrait content area and 15/70/15 landscape columns. Added flat app/settings rows, grouped settings, theme previews, separate theme/favourite pages, portrait four-row list, 4/6/8 grid choices, orientation/label preferences and swipe-up navigation from favourite areas. Header, Android and mapped Back now share the same parent routing. Native status-bar icons adapt to light themes. Colour and completion sliders have explicit 48 dp touch areas.

App drawer now has labelled, focusable add/remove stars. New favourites go first to avoid disappearing behind the visible limit. Emulator QA verified add, Home visibility, persistence after force-stop/reopen, removal and re-add. TV edition also passed arrow-key/Select addition and Back-to-Home verification. Existing Universal data survived the update.

Three separate debug editions build: Universal, E-reader and TV. All three pass lint without errors (warnings remain) and seven existing unit tests each. All three installed on the Android 15 phone emulator; E-reader started in Read/high-contrast, TV in Watch/landscape, and TV's LEANBACK entry resolved. These smoke checks do not establish physical TV/e-ink compatibility or full hardware acceptance. Sensor, CEC, refresh, macros and complete source-scope work remain outstanding.

The emulator authorization issue described above is resolved. External-session runtime coverage remains pending. The optional external-playback screen is now reached from Settings. Provider engines remain deferred. Private test APKs are supplied in private-test-builds; no GitHub Release or public/store publication is created.

Development signing uses an ignored .local/debug.keystore on this laptop so Android Studio updates remain compatible. The key is never committed. A fresh checkout elsewhere uses Android's normal debug key and may need a separate install if signed differently.


## Remote, thermal and sparse-grid verification — 1 October 2026

On the Android 15 emulator, learning virtual F1 as Apps opened the drawer and persisted across force-stop/restart. Holding physical Back invoked the recovery reset. These checks cover the virtual input device; physical remote pairing/device descriptors still need hardware testing.

A temporary emulator thermal-service override to severe displayed the overheating banner. Resetting the override and reopening removed it. The override was cleaned up; no actual overheating was induced and real sensor accuracy is not established.

Sparse portrait grid testing exposed stretched tiles. SquareShortcut now centres square buttons within reserved two-row slots, including empty slots. Three favourites in a six-slot grid each measured 346 × 346 pixels after the fix, rather than stretching to the entire favourites area's height. All three edition builds, lint checks and seven existing tests per edition pass again.


## Larger viewer and app continuation — 1 October 2026

At the user's request, the prior source/APK checkpoint was pushed and verified at da25cf75127f8ffd8858f35dd3777a331430392e before these edits. Added visible cell dividers, flat square shortcut styling, normalized 32 dp icon sizing, and larger viewer proportions (75% portrait body, 80% landscape width). Local page image area measured 1080 × 1171 pixels versus 1080 × 942 before these changes on the test emulator, despite adding source/scale selectors.

Home defaults to Continue app, saving the last component launched through Snapplication per Read/Watch mode. Folder content is explicit and retains previous folder selections. Opening a local item switches to Folder automatically. No sample comic is bundled in the APK; comic-test.cbz exists only as an emulator folder fixture.

Fit/Fill/Stretch map to native image/video aspect modes and persist per mode. Review caught a reloading implementation; it was corrected to update existing ReaderPanel/WatchPlayer surfaces without rebuilding Home. Emulator interaction cycled through all three document modes. External task resumption was verified using Clock: Stopwatch remained the selected screen after Continue Clock. This does not prove universal third-party state retention or embedded app support.

All edition builds, lint checks and seven unit tests per edition pass. Physical device and broader multimedia checks remain open. Android's [task model](https://developer.android.com/guide/components/activities/tasks-and-back-stack) and [process lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle) govern external-app retention; Snapplication does not force suspension or bypass those rules.


## Clean Home controls — 1 October 2026

Moved Main viewer source and Content sizing choices into Settings → Read & Watch. Previous/Next reading buttons default to hidden; a persisted Settings toggle enables them. Emulator UI testing verified source selection in Settings, no source/fit/page buttons on the default local-content Home, enabling page buttons, and disabling them again. Continue app remains the default Home source.

Removed the inner normal-state border/background around Home app icons. Only the grid-cell divider lines remain; remote focus still receives a visible outline when selected. Full source and private debug builds are synchronized to the editable Downloads project.


## Drawer gesture verification — 1 October 2026

The Make reference's downward drawer gesture was missing. Added close-on-downward-swipe from the drawer handle, and replaced Home's approximate fractional hit regions with actual visible shortcut/footer bounds. Multi-touch and cancelled gestures do not trigger navigation. Emulator checks verified upward Home opening, downward handle closing, and ordinary downward list scrolling staying inside the drawer. All three debug builds, lint checks and existing seven unit tests per edition pass. No visual layout or default control visibility was changed.


## Wallpaper and reference-layout correction — 1 October 2026

Android Studio's Downloads project selected Java 25 through `.gradle/config.properties`; changed that ignored local setting to installed Java 21 and successfully built the Downloads project. Source remains compatible with Java 17 bytecode.

Re-read recovered Make source. Home now uses its 57/43 portrait proportions, two grid rows for 4/6/8 blocks, and 15/70/15 landscape columns. Unassigned blocks show an Add app action instead of disappearing. User-requested divider lines remain, without inset icon borders. Favourites heading is hidden by default and can be enabled in Settings. App continuation controls are compact at the bottom of the viewer. They resume an external app, not embed it.

Wallpaper shows through translucent gradient surfaces, with contrast backgrounds behind text. This is a glass-style treatment, not optical refraction or real-time background blur. Wallpaper-capable theme is declared before the window is created. Emulator wallpaper replaced with a blue gradient at the user's request; no wallpaper forced on users' devices. Video content remains opaque so it is readable.

Android 15 emulator: local Big Buck Bunny video played inside Home from 46,869 ms to 66,264 ms and paused through its media session. Screenshot confirmed the video stays in the central panel. The wallpaper was independently checked against Pixel Launcher; the previous screenshot-like background was the selected system wallpaper, not retained rendering. Adaptive shortcut icons now use a centred ImageView instead of Button foreground to survive rotation.

Final checks: all three edition builds/lint and seven tests per edition pass. Heading toggle on/off passed. Portrait shows two rows with three assigned apps and one Add slot. Screen-off retained 66,276 ms; force-stop/relaunch restored exactly 66,276 ms. Near-completion episode advancement and physical-device checks remain open.


## Completion-threshold runtime check — 1 October 2026

On source 19605d1 / packaged checkpoint 7e08727, loaded the first local episode at 570,000 ms with a 95 percent threshold. A real emulator screen-off event staged episode two at exactly zero, and a force-stop/relaunch retained episode two at zero. Restored the original content, progress and completion setting afterward. This exercises WatchPlayer and saved preferences, beyond the EpisodeResume predicate unit tests.

A full emulator reboot subsequently restored the original video's saved position to exactly 71,438 ms, confirmed in both preferences and the live media session. The emulator completed boot before this assertion. Physical Huawei/hub behavior remains unverified.


## Preview handoff and shared transparency — 1 October 2026

Folder-content Home now launches ACTION_VIEW for the exact selected URI with a temporary read grant and ClipData. CBZ files use the comic-book MIME type even when the provider labels them ZIP. Separate persisted video/reading toggles default off and retain embedded features when enabled. Preview-only mode rejects embedded play requests and does not stage the embedded queue at screen-off. Home app-resume entries continue to reopen the saved app task.

Landscape Home is immersive and fills the full 2400×1080 emulator display; the viewer occupies x=360..2040 and the 32 dp footer occupies y=996..1080. System navigation remains available by edge swipe. Portrait footer reduced to 40 dp.

Transparency replaces the Home-only wallpaper label and reuses its saved preference. Wallpaper-backed surfaces now apply to drawer/settings and their controls; turning it off restores opaque theme surfaces. This is translucent styling, not real-time blur/refraction.

Verification: all three edition builds/lint and seven unit tests per edition pass. Android's activity log confirms video handoff to Google Photos with video/mp4, the selected content URI and read grant; this confirms dispatch, not universal third-party playback/resume. CBZ missing-reader fallback passed. Final build's embedded toggle shows controls and plays. Transparency off/on assertions and visual Settings inspection passed; user's current Read/E-paper choices restored after QA. Physical device and full external-reader compatibility remain unverified.
