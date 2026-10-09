# Reader fix scope

Source: user requests and confirmations in this chat on 8 October 2026. Review baseline: `eaecf69`.

1. Meebook M6 must show the last reading page from Mihon and built-in Read, including downloaded PDF/EPUB/comics. Tapping the centre should resume the matching book/page where the reader permits this.
2. Read must use still snapshots rather than continuous screen recording. The user declined a manual Save page button: previews must remain automatic. Keep Watch/TV's capture method.
3. Limit overhead: use reading events where available; permit occasional single-image compatibility checks for Read's silent native canvas. Stop outside eligible pages, avoid rewriting identical pictures, retain privacy/window checks and show actual setup status. Do not claim measured battery/heat savings from CPU/cache checks alone.
4. Reader APK must expose reading functionality only: no Watch switching, TV display features, remote mapping, macros or notification-media access. Universal/TV features remain available in their own editions.
5. Recognise common ebook/document/comic types in user-selected folders. Distinguish built-in PDF/CBZ/image rendering from external reader support; no universal parser claim.
6. Keep single-line `//` comments and detailed class-header `/* ... */` comments. Only MainActivity's main header uses the simple ELI5 analogy.
7. Required verification: all-edition build/lint/unit checks plus physical Mihon and downloaded-book preview/resume tests. Review against `eaecf69`; preserve private distribution and the editable Downloads project.

## Vendor adapter boundary

The inspected installed Read APK is version `2025042216`, certificate SHA-256 `98d7e54a6303ef7b721bada3a98ae292eec02d7b0a6aa9abd65ce9118f6b09d5`. Its exported LibraryService exposes `LibraryInterface.getDoubleOpenDocuments(String)` (transaction 51), returning a book path for Tab1–4. The public ReaderMainActivity resolves generic content URIs by querying their _data column, then opens the returned path with its own storage access. ReaderBookProvider exposes only the saved book identity to the pinned vendor through a temporary read grant; it serves no file contents. The dispatcher does not support a null-data resume call. Private Tab activities are not exported. Never bypass their protection or disable file-URI exposure checks.

This is a version-pinned vendor integration, not a stable documented SDK. Unknown versions and unrecognised paths fall back safely. Internal storage and FAT-style removable volume paths are validated; private paths and traversal are rejected. The SD-card EPUB was verified on 9 October after identifying the old primary-only URI resolver as the resume failure.

## Portrait sizing request — 9 October
Portrait Read gives the preview 72% of the main area (previously 57%). Keep Fit, Crop and Stretch selectable using the shared sizing setting, show the selected choice, and return to the page-preview settings after selection. Watch layout stays unchanged.
