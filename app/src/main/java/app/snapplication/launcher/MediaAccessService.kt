package app.snapplication.launcher

import android.service.notification.NotificationListenerService

/** Supplies Android's opt-in media-session access for ExternalPlaybackPanel; no notification content is read or stored. */
class MediaAccessService : NotificationListenerService()
