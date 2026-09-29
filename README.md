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

For command-line validation, run `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. The debug APK is for local testing only. No GitHub release or public distribution is authorized.
