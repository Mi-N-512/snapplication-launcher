# V1 requirements audit — 1 October 2026

Checkpoint reviewed: source 3666385, private build b50cf2f. This is an incomplete V1 audit, not release approval. The original DOCX was re-extracted in full for this review; latest user instructions override its early design notes.

## Current contract

Read and Watch are in scope. Universal, E-reader and TV packages remain private. Provider plugins, GameHub and Game mode are deferred. Latest Home design follows the Make block arrangement, with user-requested divider lines, hidden optional headings, transparency across screens, immersive landscape, and a smaller drawer handle. Tapping folder-content previews opens an external viewer by default; embedded video and reading are optional.

| Requirement | Evidence / current state | Remaining proof or implementation |
|---|---|---|
| Android without root | Manifest and source use ordinary Android APIs; minSdk 26; Android 15 emulator exercised | Android 8 baseline and Huawei version/device testing |
| Home, drawer, settings; favourites and remote navigation | MainActivity, LauncherState, RemoteControls; emulator add/remove, drawer gestures, D-pad and remapping/recovery checks recorded in development-status | 4/6/8 geometry and reorder/restart checks passed 2 October; physical remote/accessibility pass remains |
| Dark, Light, OLED, Material You, Custom, E-paper | LauncherUi, ColourPicker, WireframePreview; Save/Cancel and process-restart persistence passed 2 October | Physical e-ink contrast and additional device/theme combinations |
| User wallpaper and transparency | System picker and shared Transparency toggle; static wallpaper and Settings on/off checked | Installed live wallpaper lifecycle test |
| Fast return to last external app | Per-mode component stored; Continue uses Android task launch; Clock task continuation checked | No forced suspension, arbitrary app snapshot, or universal post-reboot state restoration implemented |
| Last media visibly represented in Home | Local document/video preview implemented; external app entry shows launch controls | Arbitrary third-party media/book preview not implemented; requires supported integrations or explicit capture design |
| Tap main preview to open selected content | ACTION_VIEW with MIME, ClipData and read grant; Google Photos dispatch observed; missing comic-reader fallback checked | End-to-end chosen third-party reader/player compatibility and app-specific resume integration |
| Optional embedded video/reader | Toggles default off; enabled video plays; PDF/CBZ/image rendering and progress previously checked | Regression matrix across modes, malformed/revoked files and device editions |
| Episode threshold 0–100% | EpisodeResume tests; embedded screen-off stages next item at zero; process/reboot resume checked | External-app next-episode behavior is not implemented; preview-only mode intentionally does not change another app's queue |
| Media controls for supported apps | ExternalPlaybackPanel lists advertised actions with optional notification access; denied-access state checked | Grant/revoke, live external session, session disappearance and transport tests |
| Device/display/thermal diagnostics | DeviceDiagnostics, ThermalMonitor; read-only APIs and synthetic thermal notice test | Real hub/display/input data and real thermal behavior |
| HDMI CEC, display extension/mirroring, independent screen power, bypass charging | Not implemented; no fake success controls | Hardware/API feasibility on actual target; distinguish vendor-supported from unavailable controls |
| Remote battery diagnostic | Input-device information exists | Battery support/availability detection and actual hardware verification |
| Macros with tap recording/speed and physical triggers | Not implemented | Explicit scoped design, consent/recovery model, Android accessibility feasibility, implementation and testing |
| E-paper refresh cadence | Theme exists; no refresh driver | Device model, supported vendor APIs, cadence implementation and ghosting validation |
| Credits/thanks | General project description exists | Dedicated credits/attribution screen and dependency notices |
| Free features, no adverts/paywalls | No billing/ad integration in project | Donation destination and licensing remain undecided; do not invent a license |
| Documented classes and methods | Production Kotlin declaration scan found no named declaration lacking a preceding comment; source was inspected | Maintain responsibility/relationship comments as features grow; scan is not a correctness proof |
| Editable local copy and private distribution | Downloads project and private repository synchronized; three APKs tracked; local IDE edits preserved | Continue preserving user changes; no public release authorized |
| Design Report | Draft content exists in docs/design-report-content.md | Requested template-based Word artifact and rendered inspection still incomplete |

## Interpretation boundaries

Android owns other apps' process lifetimes and private state. A successful task launch or file Intent is not proof of embedded external rendering, automatic page/seek transfer, or a permanent suspend snapshot. Those original ambitions remain unresolved rather than being renamed as completed features.

The emulator is evidence for software paths only. Physical CEC, battery bypass, air-mouse behavior, HDMI screen-off continuity and panel refresh cannot be accepted from emulator screenshots or hard-coded prototype data.

The historical development log contains dated findings that later entries supersede. This table summarizes current gaps; it does not replace the original scope or imply completion.


## Live-testing handoff

The user elected to test the applications independently and return with feedback. Physical-device testing is now user-owned; do not repeatedly request USB debugging, device attachment or emulator substitutes for that feedback. Preserve the open feasibility items above and prioritize polish and reported real-use problems. The live test instructions are in live-test-guide.md.
