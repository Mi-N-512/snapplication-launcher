# Provider integration decisions

Status: deferred beyond V1 by the user on 23 September 2026. These notes preserve future research for Keiyoushi/Mihon reading extensions, Cloudstream video extensions, YouTube and Twitch. No compatibility engine is included in V1.

## Playback ownership

Local media and resolved streams should use the launcher's Media3 player. The home panel displays actual video, saves position, and supports remote media controls. Reading content uses an embedded reader with persistent chapter and page identifiers. Reopening Cloudstream or Mihon is a fallback, not proof of embedded integration.

## Extension ecosystems

Keiyoushi's extension library supplies compile-time interfaces, not a standalone working host. A compatible implementation needs source discovery, package signatures and explicit trust, the expected network and dependency environment, chapter/page resolution, cookies, preferences and error handling. A source must be tested end-to-end before declaring it supported. See https://github.com/keiyoushi/extensions-lib and https://github.com/keiyoushi/extensions-source.

Cloudstream uses its own plugin lifecycle, provider APIs and extractor registry. Loading its extension file alone is insufficient. Compatibility needs a pinned engine/API version, lifecycle management, source discovery, search, details, episode resolution, stream headers/subtitles and extension update rollback. See https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt.

Cloudstream's current repository is GPL-3.0. Reusing its engine must retain the license and notices, and distribution obligations must be assessed before a release. No Cloudstream source has yet been incorporated. The user's distribution license remains undecided. Reference: https://github.com/recloudstream/cloudstream/blob/master/LICENSE.

## Online video services

YouTube's supported IFrame player can be embedded, but does not supply an arbitrary direct video URL to Media3. Twitch's official embed has origin/parent requirements. An official embed and an extractor-based plugin are different implementations with different behavior and maintenance needs. Do not claim universal direct-stream playback based on a WebView demonstration.

References:
- https://developers.google.com/youtube/iframe_api_reference
- https://dev.twitch.tv/docs/embed/video-and-clips/

The launcher will not add advertising or feature paywalls. Third-party content, subscriptions and advertisements are controlled by providers; an ad-free launcher is not a guarantee that every external service is ad-free.

## Acceptance evidence required

For each ecosystem, test install/enable/disable, explicit trust, search and pagination, title details, chapter or episode selection, content resolution, actual in-panel playback/reading, resume after force-stop, offline failure, expired links, extension update failure and rollback. Verify that disabling a provider stops its work. Never silently grant downloaded code access to all launcher data.

## Current delivery boundary

The native launcher foundation compiles. Provider controls have been removed from V1. Revisit this document when the user restarts provider integration work.
