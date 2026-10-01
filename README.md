# Snapplication launcher

Private development project for an Android launcher with Read and Watch modes, based on the supplied document and Figma Make designs. No public release is authorized. All planned features are free, without advertising or paid feature gates. Distribution licensing is undecided; no third-party source has been copied into this project.

Native Kotlin Android implementation. Android 8 or newer is the provisional compatibility baseline; the primary physical target is a Huawei Mate 10 Pro with a generic USB-C HDMI hub and air mouse. Its Android version and hardware behavior remain unverified.

## Product contract

Home contains a real video player or reading surface, favourites, and an accessible app drawer. Watch prefers landscape. Home, drawer, and settings share a consistent layout, with dark, light, OLED, Material You, and custom themes. Both touch and directional input must work.

Local files are accessed through Android's user-approved document picker. Built-in playback saves progress and does not depend on keeping another application's process alive. External applications can be launched, but their private state and embedded playback are not universally accessible.

Provider plugins and their player integration are explicitly deferred beyond V1 by the user on 23 September 2026. This includes Keiyoushi/Mihon, Cloudstream, YouTube and Twitch compatibility engines. V1 supports opening installed applications, local Read/Watch content and direct HTTPS media links. GameHub integration is also deferred.

## Source documentation

Every class must explain its responsibility and related classes in a leading comment. Methods and UI functions must explain their behavior. Comments should describe constraints and intent, not repeat individual statements.

## Development status

Work in progress. A compiled APK alone is not acceptance. Playback, reading progress, launcher selection, remote navigation, themes, process restart and permissions require testing. Hardware-specific CEC, charging bypass and e-paper refresh remain feasibility work, not simulated successes.

## Open in Android Studio

Open the `snapplication-launcher` folder containing `settings.gradle.kts`. Set the Gradle JDK to an installed JDK 21 (or JDK 17); do not select a bundled Java 25 runtime for this Gradle 8.13 project, install Android SDK Platform 36, and allow Gradle Sync. Select the `app` run configuration and an emulator or USB-debugging-enabled device. The Gradle wrapper is included; no separate Gradle installation is needed.

For all three debug APKs run `gradlew.bat :app:assembleDebug`. Validate each edition using `:app:lintUniversalDebug :app:lintEreaderDebug :app:lintTvDebug :app:testUniversalDebugUnitTest :app:testEreaderDebugUnitTest :app:testTvDebugUnitTest`. The debug APK is for local testing only. No GitHub release or public distribution is authorized.


## Private test editions

- **Universal**: original package `app.snapplication.launcher`; Read and Watch, configurable appearance and orientation. Updates the existing development installation while preserving preferences.
- **E-reader**: separate package `app.snapplication.launcher.ereader`; starts in Read with the high-contrast E-paper theme. Keeps its own favourites and reading progress. Vendor-specific e-ink refresh is not implemented or claimed.
- **TV**: separate package `app.snapplication.launcher.tv`; starts in Watch/landscape, includes an Android TV launcher entry/banner, discovers TV-only apps, and supports directional controls without requiring touch. Physical TV/CEC validation remains outstanding.

All editions share the same free features and can be installed alongside each other. Choose `universalDebug`, `ereaderDebug`, or `tvDebug` in Android Studio's Build Variants window. These are private development builds, not store releases.

### Add apps to Home

Open **All Apps** and tap the outlined star beside an app. A filled star means it is saved. Newly added apps go first so they remain visible when the chosen Home limit is full. Tap a filled star to remove it. Long-press a Home shortcut for reordering/removal; Settings → Arrange favourites also manages hidden entries. Portrait List follows the wireframe's four-row layout; Grid supports 4, 6 or 8 blocks.

### Get the test APKs

The [private test builds folder](private-test-builds/) contains the three installable debug APKs and checksums. Sign in to the GitHub account with repository access, open an APK, then choose **Download raw file**. No GitHub Release or store publication is created. Android may ask you to allow installation from the browser/files app you use.


### Main viewer

Home defaults to **Continue app**. Choose an installed app; launching it records a per-mode return target. Continue reopens its Android task where supported. It is not a live embedded window and does not force Android to retain another app in memory. Android or the app can discard/recreate its state.

Choose **Settings → Read & Watch → Main viewer → Folder content** to display a selected local PDF, CBZ, image or video. Fit shows the entire page/video, Fill crops edges to cover the panel, and Stretch fills both dimensions with possible distortion. Use **Settings → Read & Watch → Content sizing** to choose Fit, Fill or Stretch. Previous/Next page buttons are hidden by default; enable **Previous / Next buttons** in the same Settings section when needed. Home has no source or sizing toolbar. The viewer follows the supplied Make layout: 57% of the portrait body and 70% of landscape width. Portrait grid uses two rows; landscape uses two side columns. The configured 4/6/8 blocks remain visible, with + actions for unassigned slots and divider lines between cells. Favourites headings are hidden by default; enable them under Settings → Favourites heading. Settings → Transparency enables translucent glass-style surfaces on Home, the app drawer and Settings; it does not perform real-time optical refraction or background blur.


### Preview or play inside the launcher

For Folder content, tapping the main preview opens that exact video/book in an installed compatible app by default. Android uses its chosen/default viewer; a missing viewer produces a clear message. External apps manage their own playback/page position; a generic file handoff cannot transfer every app's private resume state.

Enable **Settings → Read & Watch → Play videos in launcher** for embedded playback, or **Read in launcher** for the built-in reader. Both default off. Landscape Home fills the display; swipe from an edge to reveal Android navigation. The All Apps bar is 32 dp in landscape and 40 dp in portrait.
