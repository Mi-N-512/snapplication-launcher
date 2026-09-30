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
- Optional Android system wallpaper behind Home, with a system picker for static or installed live wallpapers.
- Refreshable device diagnostics: Android/device version, battery level and temperature, charging state, supported thermal status, display modes and input-device sources.
- Foreground thermal notices based on Android severe-to-shutdown statuses, sampled every 15 seconds while the launcher is visible. Monitoring stops when the activity stops.
- Class responsibility and relationship comments and documented methods.

## Evidence

On the Android 15 emulator on 23 September 2026, a local sample video played visibly inside the home panel and the media session reported PLAYING. A three-page PDF reopened at page two after force-stop and restart. A CBZ containing 1.png, 10.png and 2.png displayed page two before page ten. The first visual check exposed system-bar overlap, which was corrected with window insets; the subsequent screenshots showed the corrected layout. The narrow portrait folder button was subsequently shortened to avoid wrapping.

On 29 September 2026, the favourites layout and arrangement changes passed debug compilation and lint (no errors). Their visual and interaction checks remain pending because the dedicated emulator initially started suspended. Restarting it with approved host permissions restored boot, but adb reports unauthorized even after reconnecting and restarting the server. Emulator and host public keys match. Remote settings compile and pass lint; their interaction behavior is not yet verified.

Seven unit tests cover numeric page ordering, long numeric filenames, completion thresholds, unknown-duration media and zero/100-percent boundaries, and thermal warning thresholds including unknown/missing readings. These tests do not prove hardware compatibility or every launcher feature.

## Still required before V1 completion

- Final device testing on the Huawei Mate 10 Pro, USB-C HDMI hub and air mouse.
- End-to-end remote mapping, recovery, directional-navigation and accessibility checks on emulator and physical remote.
- End-to-end screen-off episode transition and reboot resume tests on the final code.
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
