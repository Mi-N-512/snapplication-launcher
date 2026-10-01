# Snapplication design report content

Prepared for the project owner · 1 October 2026

Content draft for the selected Design Report template. The formatted Word deliverable remains pending template rendering and visual verification. Product source checkpoint: 3666385; packaged private builds: b50cf2f; live-testing handoff: 50fede1.

## Executive summary

Snapplication reuses an Android device as a reading and viewing launcher. The current private build combines a large media preview with favourite apps, a searchable drawer and Settings. The owner will test the application independently on real devices and return with feedback. The immediate priority is a polished everyday experience; this checkpoint is not a completed V1 or public release.

Read and Watch are implemented in three private editions: Universal, E-reader and TV. Tapping a selected local video or book preview opens it in a compatible external app by default. Separate toggles enable playback or reading directly inside Home. Android and the receiving app control external resume state; the launcher does not force arbitrary applications to remain suspended or embed their private screens.

## At a glance

- Private repository: Mi-N-512/snapplication-launcher. No public release or store publication authorized.
- Editable project: C:\Users\miche\Downloads\snapplication-launcher, with all three APKs in private-test-builds.
- Android baseline: minSdk 26, targetSdk 35, compileSdk 36. Java 21 is configured for the local Gradle environment; bytecode target remains Java 17.
- All features remain free, without advertisements or paid feature gates. Distribution licensing is undecided.

## Introduction

The original document, Figma Make source and later user corrections define the experience. Portrait Home follows the Make layout with a 57 percent viewer and favourites below. Landscape places the viewer between two side columns in a 15/70/15 split. Four, six or eight grid slots remain visible; unassigned slots open the app picker. Divider lines separate cells without inset icon borders. Favourites headings are optional and hidden by default.

Landscape Home fills the display, and edge swipes reveal Android navigation. The All Apps bar is reduced to 32 dp in landscape and 40 dp in portrait. A shared Transparency setting reveals the chosen system wallpaper through Home, drawer and Settings. The effect uses translucent gradients and contrast surfaces; it is not real-time optical refraction or background blur.

## Key findings

### Local content and external handoff

Media3 supplies embedded video; ReaderPanel renders PDFs, CBZ comics and images. Progress is stored per content URI. Local video playback, PDF restart restoration and natural comic-page ordering have emulator evidence. A screen-off threshold test staged the next embedded episode at zero; process restart retained that target. Full emulator reboot restored a saved video position exactly.

External file opening uses Android ACTION_VIEW with a MIME type and temporary read grant. Activity logs show dispatch to Google Photos. Missing comic-reader handling was exercised. This proves dispatch and fallback behavior, not complete compatibility or exact resume in every third-party app. Provider plugins remain deferred.

### Navigation and appearance

Emulator checks cover favourite addition/removal and persistence, drawer gestures, directional input, remote remapping/recovery, heading toggles, static wallpaper and Transparency on/off. The final embedded-playback toggle displays its controls and plays. Further live use should focus on awkward transitions, focus visibility, readability, folder access and how external viewers return to Home.

All three editions compile and pass lint without errors. Seven unit tests per edition cover numeric ordering, episode-threshold boundaries and thermal-warning thresholds. Deprecation and other lint warnings remain; these checks do not establish compatibility with every Android device.

### Hardware and feasibility

The original visual prototype contains simulated device controls. The native app reports available display, input, battery and thermal information without pretending to provide unsupported hardware control. Huawei HDMI behavior, CEC, independent phone/display power, charging bypass, remote battery information and physical e-ink refresh remain unresolved or unverified.

The owner elected to conduct hardware tests independently. Device attachment and USB-debugging requests should not be repeated as a prerequisite for feedback. E-paper currently supplies a high-contrast theme, not a vendor refresh driver or a guarantee against ghosting.

## Implications

MainActivity coordinates navigation and file/app launching. LauncherState stores choices and progress. WatchPlayer owns video playback and media-session lifecycle; ReaderPanel owns bounded document decoding. LauncherUi centralizes theme and transparency rendering. RemoteControls, ColourPicker, DeviceDiagnostics and ThermalMonitor provide focused support. Named production classes, objects and methods have preceding responsibility or behavior comments.

| Area | Current behavior | Implication |
| --- | --- | --- |
| Embedded content | Launcher persists local reading/playback progress | Local resume is testable independently of another app's process |
| External content | Android opens an exact file or returns to an app task | Private third-party seek/page state requires app-specific support |
| Preview-only mode | Embedded playback and completion staging stay disabled | Opening another viewer does not silently advance the launcher's local queue |
| Hardware | Read-only diagnostics and supported thermal notices | Actual device control requires separate feasibility evidence |

## Recommendations

1. Use the supplied private build in normal daily use and report concrete friction with device, edition, action and expected outcome. Preserve the tested build so feedback maps to a known checkpoint.
2. Resolve the remaining software work against docs/requirements-audit.md. Open items include broader file-error cases, supported external media-session tests, live wallpapers, custom-colour interaction coverage, credits/attribution, macros and external media previews.
3. Treat physical device observations as the basis for hardware decisions. Implement supported vendor features explicitly and describe unavailable capabilities plainly. Do not substitute theme changes for real e-ink refresh or file dispatch for universal suspension.

## Conclusion

The private build is ready for owner-led live evaluation of its implemented flows. Continue polishing from that feedback while retaining the original unresolved requirements. Keep provider plugins and Game mode deferred, preserve free features and private distribution, and leave licensing undecided until the owner chooses terms.

## Appendix

### Evidence boundaries

The embedded completion rule does not control another application's episode queue. Android owns app process lifetime; the project does not provide a permanent suspend snapshot. External viewer playback position can differ from the local preview. The three editions share features but keep independent settings. Physical hardware tests and a template-rendered Word report remain incomplete.

### Sources

- Original Android launcher idea DOCX, Android Wireframe.make, Android Wireframe.zip and subsequent owner instructions.
- README.md, docs/development-status.md, docs/requirements-audit.md and docs/live-test-guide.md at the checkpoints stated above.
- Source files in app/src/main/java/app/snapplication/launcher and the associated build, lint and emulator evidence summarized in development-status.md.
