# Snapplication design report content

Prepared for the project owner · 30 September 2026

This is the content draft for the selected Design Report template. The formatted Word deliverable is pending template rendering and visual verification. Implementation evidence refers to commit `fc39a227e513f505db2da0476088dc30c6f741da`.

## Executive summary

Snapplication is a private Android launcher project intended to make an existing phone useful for reading and watching media, including use with an external display and remote. V1 focuses on Read and Watch. Its home screen combines a large content panel with favourite applications, while the app drawer and settings remain reachable by touch or directional input.

The native Kotlin foundation builds successfully and includes local video playback, PDF and comic reading, saved progress, configurable favourites, remote button learning, themes, wallpaper controls and device diagnostics. Early emulator checks demonstrated playback and reading progress. Later features have build and code-review evidence but still need interaction testing. V1 is not ready to declare complete or distribute.

## At a glance

- Private repository: MLN-WORK/snapplication-launcher. No public release or store publication is authorized.
- Android Studio project: C:\Users\miche\Downloads\snapplication-launcher. All features remain free, with no advertisements or paid feature gates.
- Primary target: Huawei Mate 10 Pro, generic USB-C HDMI hub and USB remote. The phone's exact Android version and hardware behavior remain unverified.

## Introduction

The supplied launcher idea and Figma Make design establish the product experience. The latest Make source uses a large central content panel, favourites at either side in landscape, and favourites below the panel in portrait. The implementation follows that structure using native Android views and actual installed applications.

User clarifications define the current scope: Read and Watch first, no root requirement, a private repository, and an editable local project. Direct provider plugins and their player integration are deferred beyond V1. GameHub is also deferred. The earlier Adalo reference does not determine the implementation.

## Key findings

### Context and conditions

The visual prototype is a design reference, not proof of working Android features. Its hard-coded television information and simulated controls cannot establish real HDMI CEC, charging bypass or independent display-power support. The native application instead reports Android-accessible device information and retains unsupported features as explicit development work.

The current application provisionally supports Android 8 and newer. It targets Android 15 behavior and compiles with SDK 36. Hardware-dependent features require testing on the user's equipment rather than an assumption based on Android version alone.

### Patterns in the evidence

A local video visibly played in the home panel on the Android 15 emulator on 23 September. A three-page PDF reopened at page two after force-stop and restart. A comic archive containing pages 1, 10 and 2 displayed page 2 before page 10. These checks support local playback, reading restoration and numeric page ordering on that tested build.

Subsequent builds added favourite layouts, reordering, remote mappings, the colour picker, system wallpaper support and diagnostics. Compilation, lint and five existing unit tests pass. Those tests cover numeric ordering and completion-threshold boundaries; they do not verify every new screen. The emulator currently reports an unauthorized debugging connection, so later interaction checks remain pending.

**Key takeaway:** the local media foundation has runtime evidence. The remaining V1 work combines unverified interactions, unfinished requested features and device-specific feasibility checks.

## Implications

The architecture separates the screen host from playback, reading, persisted state and device information. MainActivity coordinates navigation; WatchPlayer owns Media3 playback and its media session; ReaderPanel owns document decoding; LauncherState persists choices and progress. RemoteControls, ColourPicker and DeviceDiagnostics provide focused support for settings. Classes and methods carry responsibility comments for future editing in Android Studio.

| Area | Current observation | Design implication |
| --- | --- | --- |
| Local media | Progress is persisted by content URI | Resume can survive process restarts; folder permission and unavailable-file cases still need testing |
| External applications | Launching apps works; universal state restoration is not established | Integrate only supported external APIs and describe the actual resume behavior |
| Hardware | Displays, inputs and thermal information can be queried | Device readings are useful diagnostics, but do not establish hardware control capabilities |

## Recommendations

1. Restore runtime testing and check every new interaction. Exercise all favourite counts in both orientations; reorder and remove entries; map, replace, cancel and reset remote buttons; save and cancel custom colours; test static and live wallpapers. Confirm focus visibility and usable touch targets.
2. Complete the remaining V1 feature work against the original requirements. This includes foreground thermal alerts, supported external-app resume, e-paper refresh feasibility, screen-control feasibility and opt-in macros with recording and adjustable speed. Unsupported device controls must not be presented as working features.
3. Validate on the Mate 10 Pro with the actual hub and remote. Test folder grants, process death, reboot restoration, screen-off episode progression, display connection changes, remote recovery and thermal readings. Retain reproducible evidence and revisit design fidelity after those flows work.

## Conclusion

Continue development from the private Android Studio project. Keep provider plugins and GameHub outside the current V1 implementation, as requested. Preserve the free-feature model and defer distribution licensing until the owner chooses terms.

A successful build is a development checkpoint. Completion requires the outstanding feature work and direct verification of the requested experience on the target hardware. No release should be created at this stage.

## Appendix

### Notes

The episode threshold stages the next local video on screen-off; it does not authorize autoplay while the user is away. Remote mappings apply to launcher activity screens, while text fields, dialogs and other apps keep their normal controls. Holding physical Back provides a mapping reset path that still needs testing on the real remote.

The e-paper option currently changes colours; it does not prove physical ghosting control. Diagnostics show a refreshable snapshot, not continuous background monitoring. The system wallpaper picker changes the device wallpaper after the user confirms the choice.

### Sources

- User-provided Android launcher idea original.docx, Android Wireframe.make and Android Wireframe.zip; subsequent scope clarifications in this conversation.
- Repository README, docs/development-status.md and Kotlin implementation at the commit identified above. Earlier emulator evidence and current build results are summarized in development-status.md.
- Android activity process lifecycle: https://developer.android.com/guide/components/activities/process-lifecycle
- Android media sessions: https://developer.android.com/reference/android/media/session/MediaSessionManager
- Android input devices: https://developer.android.com/reference/android/view/InputDevice
- Android wallpaper window flag: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER
- Android thermal status: https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()
