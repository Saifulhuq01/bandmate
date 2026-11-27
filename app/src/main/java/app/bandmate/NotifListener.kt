package app.bandmate

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Forwards phone notifications to the band. Needs "Notification access" turned on in Settings. */
class NotifListener : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val client = Band.client ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        // Incoming call: CallWatcher sends the alert; here we only learn the caller's name.
        if (n.category == Notification.CATEGORY_CALL) {
            if (title.isNotBlank()) Band.setCallerName(title)
            return
        }

        if (!Band.forwardNotifications(this)) return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in SKIP_CATEGORIES) return
        if (title.isBlank() && body.isBlank()) return
        val allowed = AppFilter.isAllowed(this, sbn.packageName)

        // Apps often re-post the same notification; skip repeats within 10 seconds.
        val now = System.currentTimeMillis()
        val sig = "${sbn.packageName}|$title|$body"
        recent.entries.removeAll { now - it.value > 10_000 }
        if (recent.containsKey(sig)) return
        recent[sig] = now
        AppFilter.recordSeen(this, sbn.packageName, forwarded = allowed)
        if (!allowed) return

        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        client.sendNotification(sbn.packageName, appName, title, body)
    }

    companion object {
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
        )
    }
}
