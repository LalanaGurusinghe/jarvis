package lk.lalana.jarvis

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets Jarvis read out current notifications when asked. Nothing is stored. */
class JarvisNotificationService : NotificationListenerService() {
    companion object {
        @Volatile var instance: JarvisNotificationService? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun recentJson(limit: Int): JSONArray {
        val arr = JSONArray()
        val list: List<StatusBarNotification> = try {
            activeNotifications?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (sbn in list.sortedByDescending { it.postTime }) {
            if (arr.length() >= limit) break
            if (sbn.isOngoing || sbn.packageName == packageName) continue
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) continue
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: Exception) {
                sbn.packageName
            }
            arr.put(
                JSONObject().put("app", app).put("title", title).put("text", text.take(300))
                    .put("time", fmt.format(Date(sbn.postTime)))
            )
        }
        return arr
    }
}
