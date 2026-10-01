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

Open the `snapplication-launcher` folder containing `settings.gradle.kts`. Use Android Studio's bundled JDK 21 (or JDK 17), install Android SDK Platform 36, and allow Gradle Sync. Select the `app` run configuration and an emulator or USB-debugging-enabled device. The Gradle wrapper is included; no separate Gradle installation is needed.

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
