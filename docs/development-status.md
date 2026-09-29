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
- Class responsibility and relationship comments and documented methods.

## Evidence

On the Android 15 emulator on 23 September 2026, a local sample video played visibly inside the home panel and the media session reported PLAYING. A three-page PDF reopened at page two after force-stop and restart. A CBZ containing 1.png, 10.png and 2.png displayed page two before page ten. The first visual check exposed system-bar overlap, which was corrected with window insets; the subsequent screenshots showed the corrected layout. The narrow portrait folder button was subsequently shortened to avoid wrapping.

On 29 September 2026, the favourites layout and arrangement changes passed debug compilation and lint (no errors). Their visual and interaction checks remain pending because the dedicated emulator initially started suspended. Restarting it with approved host permissions restored boot, but adb reports unauthorized even after reconnecting and restarting the server. Emulator and host public keys match. Remote settings compile and pass lint; their interaction behavior is not yet verified.

Five unit tests cover numeric page ordering, long numeric filenames, completion thresholds, unknown-duration media and zero/100-percent boundaries. These tests do not prove hardware compatibility or every launcher feature.

## Still required before V1 completion

- Final device testing on the Huawei Mate 10 Pro, USB-C HDMI hub and air mouse.
- End-to-end remote mapping, recovery, directional-navigation and accessibility checks on emulator and physical remote.
- End-to-end screen-off episode transition and reboot resume tests on the final code.
- Emulator verification of favourite reordering and 4/6/8-item layouts, followed by closer final design fidelity.
- Custom colour picker UI and user-supplied wallpaper integration.
- External-app resume behavior where those apps expose supported APIs; no universal process suspension guarantee.
- E-paper refresh capability detection and supported vendor integration; colour changes alone do not clear physical ghosting.
- Thermal/display diagnostics, screen-control feasibility and opt-in macro support.
- Cleanup of lint warnings, broader error-case tests and the requested Design Report artifact.

HDMI CEC, bypass charging and independent phone/display power control must be tested for actual device support. No simulated success controls are included. The project does not require root.

## Deferred by the user

Direct provider plugins and their playback integration are excluded from V1. Keiyoushi/Mihon, Cloudstream, YouTube and Twitch integration research is retained for later. GameHub and the separate Game mode are deferred. Distribution licensing remains undecided. No public repository, GitHub release or store publication is authorized.

## Remote input implementation references

Device-specific persistence uses Android [InputDevice descriptors](https://developer.android.com/reference/android/view/InputDevice#getDescriptor()). Translated controls preserve [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent) press/release metadata. Mappings are local to launcher activity screens; they do not intercept other apps or system dialogs.
