# Changes

## Unreleased

### Added

- Tap-only viewer setting: no buttons on Home; tap a page to turn it, a video to play and pause, or an app preview to resume it.
- Built-in reader turns pages by tapping, sideways swipes, remote/keyboard page keys and screen-reader scroll actions.
- "Or read a book / play a video from this device" on an empty Home before any app is chosen.

### Fixed

- Choosing "Files from a folder" opens the folder picker when no folder is granted yet.
- Episode advance switches the resume target immediately, so a stop before the player's callback cannot reopen the finished episode.
- All Apps and Home favourites refresh when apps are installed, removed or disabled.
- Settings search finds wallpaper transparency and other common terms; the search box clears when Settings opens and the page keeps its scroll position after a switch.
- Landscape settings categories stay readable over a bright wallpaper.
- Favourite labels shrink to fit narrow cells before being shortened.
- Setup marks the selected device and theme with a tick and outline.
- `scripts/verify.ps1` no longer stops on Gradle warnings when its output is redirected in Windows PowerShell 5.1.
- Home panel edges and favourite dividers share one line colour in every theme.
- Turning a page no longer flashes "Loading…" over the page already shown.

### Removed

- Development notes (`AGENTS.md`, `CONSTRAINTS.md`, `docs/*.md`), the unused `SetupGuide` and `SquareShortcut` classes, and unused logo resources.

## 0.99 — 25 October 2026

Private preview preparing for 1.0.

### Added

- Portable local backup and restore for settings, favourites, remote bindings and macros.
- Status & repair page gathering preview and permission checks.
- Optional separate Read and Watch favourites.
- Custom font import and clearer local-file viewing routes.
- Current-screen and image-based macro editing with timing, tap markers and run feedback.

### Improved

- Phone rotation switches Read and Watch when selected; fixed content choices remain available.
- Settings use categories and shared search, with neutral accents when wallpaper is visible.
- Universal and Modern share Read/Watch features; Reader retains reading-only controls.
- Code comments explain class roles, key state, flow and relationships in plain English.
- README now explains the design, setup and editions with real emulator screenshots.

### Still to do before public 1.0

- Finish the physical-device regression pass, including e-paper and TV/remote combinations.
- Select the distribution licence and establish release signing and publication.
- External-app resume and protected-screen restrictions remain dependent on Android and the source app.
