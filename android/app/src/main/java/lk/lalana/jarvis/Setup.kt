package lk.lalana.jarvis

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Opens the system pages where special access is granted. */
object Setup {
    fun open(act: Activity, which: String) {
        val pkg = Uri.parse("package:" + act.packageName)
        val intent = when (which) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "app_info" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            act.startActivity(intent)
        } catch (_: Exception) {
            val fallback = if (which == "assistant") Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS else Settings.ACTION_SETTINGS
            try {
                act.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }
}
