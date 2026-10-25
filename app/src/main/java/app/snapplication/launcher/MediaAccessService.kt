package app.snapplication.launcher

import android.service.notification.NotificationListenerService

/*
Supplies Android's opt-in media-session access for ExternalPlaybackPanel; no notification content is
read or stored.

MediaAccessService is a child of Android's NotificationListenerService. Its registered component
identifies the user-approved media access.

Android manages this service after the user enables notification access. It has no app-defined
fields or callbacks. ExternalPlaybackPanel, ExternalPreview and ExternalWatchPlayback use its
component name when asking Android for media sessions. The service does not itself read or save
notification messages.

Used by or used with: ExternalPlaybackPanel and ExternalPreview use its Android-approved
media-session access. Screen controls: No screen; the user grants or revokes access through Android
settings. Changes belong here: Keep this service limited to media-session access; notification
message collection is not implemented.
*/
class MediaAccessService : NotificationListenerService()
