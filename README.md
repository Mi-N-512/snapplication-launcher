<p align="center"><img src="docs/images/logo.png" width="130" alt="Snapplication finger-snap logo"></p>

# Snapplication · 0.99

**A home screen built around returning to what you were doing.**

Keep your last reading page or video preview in the centre, your favourite apps nearby, and your controls within reach. Snapplication brings Read and Watch together in a launcher for phones, e-readers and television setups.

**0.99 is a private preview on the way to 1.0.** These builds are for testing, not a public store release.

[Choose your edition](#choose-your-edition) · [Download APKs](private-test-builds/) · [Get started](#get-started) · [Screenshots](#see-it-running) · [Build the project](#open-and-build-the-project)

## Why I wanted to create it

I wanted a launcher that lets me see where I left off and get back there quickly. A home screen should show the book or video I was using, with my apps around it, instead of making me search through menus every time.

I also wanted the same idea to work in different situations: reading on an e-paper screen, watching on a phone connected to a TV, or rotating a phone between the two. Those devices need different controls and defaults, but they should still feel like the same application.

My intention is to keep every feature free, with no advertising or feature paywalls, and make the project something people can adapt to their own layouts and needs. A distribution licence still needs to be chosen before public release.

## The design

- **Content comes first.** The central Snapplication area is the largest part of Home. Favourites sit around it without unnecessary headings.
- **Squares, rectangles and clear dividers.** Layouts stay angular, with optional adjustments for people who prefer a different appearance.
- **A background you can see.** Optional transparency carries the wallpaper through Home, All Apps and Settings, using neutral accents.
- **Themes that suit the screen.** Light uses pastel pink; Dark and OLED Black use British racing green. E-paper favours contrast and static screens.
- **One familiar experience, different needs.** Touch, directional navigation and reading each get appropriate layouts. Phone setup can follow rotation or keep the chosen content mode.

## See it running

Actual Android emulator captures from version 0.99. The reading page is an original demonstration PDF; the video is a locally generated motion test. These show the built-in viewers, not a claim that every external app supports the same controls. Installed apps and their icons are examples, not bundled software.

| Read · Light · blocks | Read · E-paper · list |
| --- | --- |
| <img src="docs/images/read-light-blocks.png" width="280" alt="Portrait Read Home showing a PDF above four favourite blocks"> | <img src="docs/images/read-epaper-list.png" width="280" alt="Portrait Read Home with E-paper theme and list favourites"> |

**Watch · Dark · blocks**

<img src="docs/images/watch-dark-blocks.png" width="850" alt="Landscape Watch Home playing a local video between favourite blocks">

**Watch · OLED Black · list**

<img src="docs/images/watch-oled-list.png" width="850" alt="Landscape Watch Home with the OLED Black theme and list layout">

**Transparent Home and All Apps**

<img src="docs/images/watch-transparent.png" width="850" alt="Watch Home with neutral translucent favourite panels">
<img src="docs/images/drawer-transparent.png" width="850" alt="Transparent app drawer with alphabet navigation">

**Settings · Light, Dark and transparent**

<img src="docs/images/settings-light.png" width="280" alt="Light settings with clearly separated categories">
<img src="docs/images/settings-dark.png" width="850" alt="Dark landscape settings with category navigation">
<img src="docs/images/settings-transparent.png" width="850" alt="Transparent landscape settings with neutral accents">

## Choose your edition

All four editions share the same core code. They have separate app identities, so they can be installed together and keep separate settings.

| Edition | Android | Read / Watch | Best fit |
| --- | --- | --- | --- |
| [Universal](private-test-builds/snapplication-universal-debug.apk) | 8+ | Both | Existing installations and older phones. Phone setup can switch Read/Watch with rotation. |
| [Modern Universal](private-test-builds/snapplication-modern-debug.apk) | 11+ | Both | The same combined launcher with Android 11 as its minimum, including access to Android's still-screenshot API. |
| [E-reader](private-test-builds/snapplication-ereader-debug.apk) | 8+ | Read only | A reading-focused interface with E-paper defaults and no Watch, remote or macro settings. |
| [TV](private-test-builds/snapplication-tv-debug.apk) | 8+ | Watch | Landscape, directional navigation, remote controls and an Android TV launcher entry. |

Automatic still-page capture requires **Android 11+**, an enabled accessibility service and a supported reading screen. Universal on Android 11+ has this capability too; it is not exclusive to Modern. On older systems, local reading and returning to reader apps remain available, but automatic still-page screenshots are unavailable.

Read mode does **not** use continuous screen recording. Watch previews use Android screen-capture consent. Android TV launcher support does not mean every television, hub or remote has been certified.

## Get started

1. Open the [private test builds](private-test-builds/) while signed in to the repository. Select your APK and choose **Download raw file**. Install it on a compatible device. Keep the same edition when updating to retain its settings.
2. Choose **Phone**, **Reader** or **TV** where offered, then choose your theme. Follow the guided introduction and enable only the features you want.
3. Open **All Apps** and enter its favourites editor. Tap apps to add them, then drag the preview icons into the order you want. An empty Home block opens this editor too.
4. In **Settings → Home & content**, choose whether Home returns to an app or shows content from a folder. Read and Watch remember separate choices. Built-in playback and reading are optional.
5. Use **Settings → Setup & help → Status & repair** to review the default Home app and preview permissions. Android may require fresh Watch capture consent after an update or stopped session.

In Phone setup, **Follow rotation** can choose Read upright and Watch sideways. You can also keep Read or Watch selected while changing orientation. The dedicated editions retain their intended content mode.

## Main features

### Pick up where you left off

The centre can show a saved frame or reading page from a supported external app. Selecting it returns to the remembered task and requests supported playback actions. The latest eligible Read and Watch app is tracked separately.

Android and the other app still own that task, its playback buffer and its memory. Snapplication cannot freeze another app or guarantee instant resume after Android closes it. Protected content and apps that hide their page or player state can limit previews and controls.

### Read and watch local content

Choose folders through Android's file picker. The built-in reader handles **PDF, CBZ and images**, saving page progress. Recognised formats such as **EPUB** open in a compatible installed reader. Local videos and supported direct media links can play inside Home, with saved progress.

Picture sizing offers **Fit**, **Fill/crop** and **Stretch**. Navigation controls can be enabled when wanted. Local video queues support next-item preparation at the selected completion threshold. External next-episode requests depend on the player's available controls; they are not universal preloading.

### Make Home yours

Choose blocks or lists, symmetrical or asymmetrical arrangements, favourite limits, labels, wallpaper and themes. Optional separate Read/Watch favourite pools keep each activity's apps nearby. The alphabet rail and search help find the rest.

Import your own compatible TTF/OTF font. Installed icon packs are optional; no icon pack or paid font is included.

### Remote buttons and recorded actions

The remote page shows a virtual remote for learning and assigning buttons Android delivers to the launcher. Assign ordinary actions or a saved macro. Android reserves some keys, including Power on many devices, so not every physical button can be intercepted.

Macros can follow recorded screens or run coordinates on the current screen. The editor provides timing, step editing, reference images and a rehearsal view. Image-matched actions can wait for a matching screen before tapping. Optional markers show taps during playback; the running action has a Stop control and a saved result.

A reference screenshot alone does not grant permission to control protected Android screens. Current-screen coordinates can press the wrong place if the layout or orientation changes: rehearse and check the position before assigning a remote button.

### Keep your setup

**Backup & restore** saves portable settings, favourites, remote assignments and macros, including their reference pictures, to a local file. Restore asks before replacing your setup. Permissions, folder access grants, media, preview frames, wallpapers and imported font files are not transferred.

Backups can contain recorded text and reference pictures. Keep them somewhere private.

## Permissions, in plain language

| Permission or access | Why you might enable it |
| --- | --- |
| Default Home app | Open Snapplication when you press Home. |
| Accessibility support | Remote input, recorded actions and supported still reading-page previews. |
| Screen sharing | Watch previews and image-matched macro checks. Android controls the consent and can end a session. |
| Media notification access | Discover supported players and request their playback controls. Optional. |
| Selected files/folders | Read the content you choose through Android's picker. |

Preview pictures and macro reference data are stored locally. No root is required. The launcher cannot bypass secure screens, DRM or Android's permission decisions.

## Open and build the project

Open the folder containing `settings.gradle.kts` in Android Studio. Use **JDK 21** (JDK 17 is also supported), install Android SDK Platform 36 and let Gradle sync. Do not select a Java 25 runtime for this Gradle 8.13 project.

Choose `universalDebug`, `modernDebug`, `ereaderDebug` or `tvDebug` in **Build Variants**. The Gradle wrapper is included.

```powershell
# Build all four development APKs.
.\gradlew.bat :app:assembleDebug

# Build, lint and run unit tests for every edition.
.\scripts\verify.ps1
```

APKs are generated under `app/build/outputs/apk/`. A local debug signing key is used for these private builds and is never committed. Building elsewhere with a different key will not update an existing installation signed with the original key.

Start reading the code at `MainActivity.kt`. Its header maps the screens to their helpers. Class comments explain responsibilities, key variables, real parent/child relationships and where changes belong. [Project checks](CONSTRAINTS.md) describe the verification requirements.

## On the way to 1.0

0.99 includes the combined Read/Watch setup, simplified settings, rotation support, preview repair tools, macro editing, backup/restore and the comment rewrite. All four editions pass build, lint and unit tests. The screenshots above were taken on an emulator; they do not replace physical e-paper, HDMI and remote testing.

Before public 1.0, finish the physical-device regression pass, choose a distribution licence and establish release signing and distribution. Provider plugins and a gaming mode remain future work. There is no public GitHub Release, Play Store or F-Droid listing for this build.
