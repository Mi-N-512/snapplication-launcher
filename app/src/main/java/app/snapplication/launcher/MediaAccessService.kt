package app.snapplication.launcher

import android.service.notification.NotificationListenerService

/*
 * Supplies Android's opt-in media-session access for ExternalPlaybackPanel; no notification content is read or
 * stored.
 *
 * Related classes: ExternalPlaybackPanel and ExternalPreview use its Android-approved media-session access.
 * UI: No screen; the user grants or revokes access through Android settings.
 * Edit or extend: Keep this service limited to media-session access; notification message collection is not
 * implemented.
 */
class MediaAccessService : NotificationListenerService()
