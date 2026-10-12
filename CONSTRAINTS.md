# Active task — Mate USB remote checks, 12 October 2026

The user has explicitly moved work to the directly connected Mate 10 Pro. The earlier Reader-only device
restriction below applies to the completed Reader task, not this work. Preserve Reader functionality.
Run all three editions' build/lint/unit tests using `scripts/verify.ps1` (optionally `-Offline`). Install
Universal only on the Mate for this task. Verify macro record/save/replay/stop and screen off through USB;
physical remote Back/Power and Huawei TV connection dialogs remain unverified without that hardware.
Do not infer real remote key delivery from virtual ADB key events. Retain saved user macros and mappings.

# Completed Reader fix acceptance — 8 October 2026

User-approved baseline: `eaecf69`. Scope: Meebook M6 Reader edition, Mihon streaming pages and Read downloaded books. Do not change or install on the Mate 10 Pro during this task.

## Required gates

- All three editions compile: `gradlew.bat :app:assembleDebug`.
- Android lint passes for all editions: `gradlew.bat :app:lintUniversalDebug :app:lintEreaderDebug :app:lintTvDebug`.
- Existing unit tests pass without deleting/skipping tests or weakening assertions: `gradlew.bat :app:testUniversalDebugUnitTest :app:testEreaderDebugUnitTest :app:testTvDebugUnitTest`.
- On the physical M6, verify Mihon and Read: open a page, capture a fresh preview, return Home, select the preview, confirm the same book/page. Repeat after a page change and after switching reading apps. A stored JPEG alone is not a resume pass.
- Verify menus/library do not replace a clean page. Report PDF, EPUB and comic checks separately; untested formats are not claimed as verified.
- Reader settings/setup have no Watch, remote mapping, macro, media notification access or TV display controls. Universal/TV retain their features.
- No public release. Private source/APKs and Downloads copies only after verification. Keep personal book images, APKs from other apps, credentials and local signing keys out of Git.

## Engineering floor

No new lint suppression, hidden failures, placeholder behavior, secret material, skipped tests or weakened acceptance thresholds to make this change pass. Existing Android lint is the external check; review the diff for regressions and compare against the baseline. Class headers use detailed `/* ... */` comments; single-line comments use `//`.

Build/lint/tests run at task verification, not after every edit. Physical checks run after installation and user-enabled accessibility still screenshots. Missing permission or a vendor resume restriction is an explicit incomplete gate, never a successful test.

## Read power requirement added by the user

Read mode must not run MediaProjection or a virtual display. Watch/TV keeps its existing capture method. The user declined a manual save button: previews must remain automatic. Use page events where supported and a bounded five-second still-image check only for Meebook Read's event-silent canvas. Stop those checks outside a visible reading page; compare pixels and avoid re-encoding/writing unchanged pages. Verify no MediaProjection and no changed cache writes on a still page. Report sampling delay and do not claim measured battery/temperature savings without evidence.

No coverage percentage has been measured or invented. No exceptions approved.
