package lk.lalana.jarvis

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** Exact hardware and permission status, read natively. */
object DeviceInfo {
    fun json(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("manufacturer", Build.MANUFACTURER)
        o.put("brand", Build.BRAND)
        o.put("model", Build.MODEL)
        o.put("android", Build.VERSION.RELEASE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("version", try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        })

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        o.put("ram_gb", Math.round(mi.totalMem / 1e9 * 10) / 10.0)
        o.put("cores", Runtime.getRuntime().availableProcessors())

        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (lvl >= 0 && scale > 0) o.put("battery_pct", lvl * 100 / scale)
            o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
        }

        val pm = ctx.packageManager
        o.put("telephony", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        o.put("flash", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
        o.put("whatsapp", installed(ctx, "com.whatsapp") || installed(ctx, "com.whatsapp.w4b"))
        o.put("screen_control", JarvisAccessibilityService.instance != null)
        o.put("notification_access", notificationAccess(ctx))
        o.put("write_settings", Settings.System.canWrite(ctx))
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        o.put("dnd_access", nm.isNotificationPolicyAccessGranted)
        var assistant = false
        if (Build.VERSION.SDK_INT >= 29) {
            assistant = try {
                ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            } catch (_: Exception) {
                false
            }
        }
        o.put("default_assistant", assistant)

        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val perms = JSONObject()
        perms.put("mic", has(Manifest.permission.RECORD_AUDIO))
        perms.put("contacts", has(Manifest.permission.READ_CONTACTS))
        perms.put("calendar", has(Manifest.permission.WRITE_CALENDAR))
        perms.put("sms", has(Manifest.permission.SEND_SMS))
        perms.put("location", has(Manifest.permission.ACCESS_COARSE_LOCATION))
        perms.put("notify", if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.POST_NOTIFICATIONS) else true)
        o.put("permissions", perms)
        return o
    }

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun notificationAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }
}
