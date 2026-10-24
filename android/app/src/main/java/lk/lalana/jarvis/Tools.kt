package lk.lalana.jarvis

import android.Manifest
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

class ToolError(msg: String) : Exception(msg)

/** Device abilities Jarvis's brain can call. Each returns a small JSON result or throws ToolError with a fix-it message. */
object Tools {
    fun run(act: MainActivity, name: String, a: JSONObject): JSONObject = when (name) {
        "open_app" -> openApp(act, a.optString("name"))
        "list_apps" -> listApps(act)
        "open_link" -> openLink(act, a.optString("url"))
        "find_contact" -> findContact(act, a.optString("name"))
        "send_message" -> sendMessage(act, a)
        "create_calendar_event" -> createEvent(act, a)
        "list_calendar_events" -> listEvents(act, a)
        "set_alarm" -> setAlarm(act, a)
        "set_volume" -> setVolume(act, a)
        "set_brightness" -> setBrightness(act, a)
        "flashlight" -> flashlight(act, a.optBoolean("on", true))
        "media_control" -> media(act, a.optString("action", "play_pause"))
        "do_not_disturb" -> dnd(act, a.optBoolean("on", true))
        "open_settings" -> openSettings(act, a.optString("page", "main"))
        "read_notifications" -> readNotifications()
        "device_info" -> DeviceInfo.json(act)
        "read_screen", "tap", "type_text", "scroll", "press" -> {
            val svc = JarvisAccessibilityService.instance
                ?: throw ToolError("Screen control is off. Open Systems in Jarvis and tap Enable screen control.")
            svc.handle(name, a)
        }
        else -> throw ToolError("Unknown device tool $name")
    }

    private fun ok(vararg kv: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    private fun need(ctx: Context, perm: String, what: String) {
        if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
            throw ToolError("Jarvis needs $what permission. Open Systems in Jarvis and tap Grant permissions.")
    }

    private fun start(ctx: Context, i: Intent) {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- apps ----------
    private fun launchables(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.second }
    }

    private fun openApp(ctx: Context, name: String): JSONObject {
        val q = name.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) throw ToolError("Which app should I open?")
        val apps = launchables(ctx)
        val hit = apps.firstOrNull { it.first.lowercase(Locale.ROOT) == q }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(q) }
            ?: apps.firstOrNull { it.second.lowercase(Locale.ROOT).contains(q.replace(" ", "")) }
            ?: throw ToolError("No app called $name is installed. Use list_apps to see what is.")
        val launch = ctx.packageManager.getLaunchIntentForPackage(hit.second)
            ?: throw ToolError("${hit.first} can't be opened")
        start(ctx, launch)
        SystemClock.sleep(1500)
        return ok("opened" to hit.first, "package" to hit.second)
    }

    private fun listApps(ctx: Context): JSONObject =
        ok("apps" to JSONArray(launchables(ctx).map { it.first }.sorted().take(250)))

    private fun openLink(ctx: Context, url: String): JSONObject {
        if (url.isBlank()) throw ToolError("Which link?")
        val u = if (url.startsWith("http")) url else "https://$url"
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return ok("opened" to u)
    }

    // ---------- contacts and messages ----------
    private fun contacts(ctx: Context, name: String): List<Triple<String, String, String>> {
        need(ctx, Manifest.permission.READ_CONTACTS, "contacts")
        val out = mutableListOf<Triple<String, String, String>>()
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        ctx.contentResolver.query(
            phone, proj, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { c ->
            while (c.moveToNext() && out.size < 8) {
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), "").toString()
                out.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", type))
            }
        }
        return out
    }

    private fun findContact(ctx: Context, name: String): JSONObject {
        if (name.isBlank()) throw ToolError("Whose contact?")
        val list = contacts(ctx, name)
        if (list.isEmpty()) throw ToolError("No contact matches $name")
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().put("name", c.first).put("number", c.second).put("type", c.third))
        return ok("contacts" to arr)
    }

    private fun countryIso(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() } ?: "LK"
        return iso.uppercase(Locale.ROOT)
    }

    private fun sendMessage(act: MainActivity, a: JSONObject): JSONObject {
        val to = a.optString("to").trim()
        val text = a.optString("text").trim()
        if (to.isEmpty() || text.isEmpty()) throw ToolError("I need a recipient and a message")
        val country = a.optString("country").trim().ifEmpty { countryIso(act) }.uppercase(Locale.ROOT)

        var name = to
        var number = to
        if (to.count { it.isDigit() } < 6) {
            val c = contacts(act, to)
            if (c.isEmpty()) throw ToolError("No contact named $to")
            name = c[0].first
            number = c[0].second
        }
        val digits = number.filter { it.isDigit() || it == '+' }
        val intl = if (digits.startsWith("+")) digits else (PhoneNumberUtils.formatNumberToE164(digits, country) ?: digits)

        val wa = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { DeviceInfo.installed(act, it) }
        val canSms = act.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val via = when (a.optString("app", "auto").lowercase(Locale.ROOT)) {
            "sms" -> if (canSms) "sms" else throw ToolError("This tablet can't send SMS (no SIM messaging). Try WhatsApp.")
            "whatsapp" -> if (wa != null) "whatsapp" else throw ToolError("WhatsApp isn't installed")
            else -> if (wa != null) "whatsapp" else if (canSms) "sms" else throw ToolError("No way to send messages here: no SIM and no WhatsApp")
        }
        val label = if (via == "sms") "SMS" else "WhatsApp"
        if (!Confirm.ask(act, "Send $label to $name?", "$intl\n\n“$text”", "Send")) {
            return ok("sent" to false, "reason" to "The user cancelled")
        }

        if (via == "sms") {
            need(act, Manifest.permission.SEND_SMS, "SMS")
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                act.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(intl, null, sms.divideMessage(text), null, null)
            return ok("sent" to true, "via" to "SMS", "to" to name)
        }

        val pkg = wa ?: throw ToolError("WhatsApp isn't installed")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${intl.removePrefix("+")}&text=" + Uri.encode(text)))
            .setPackage(pkg)
        val svc = JarvisAccessibilityService.instance
        svc?.armAutoSend(pkg)
        start(act, i)
        return if (svc != null) {
            ok("status" to "WhatsApp chat opened; Jarvis is pressing send", "to" to name)
        } else {
            ok("status" to "WhatsApp opened with the message typed. The user must tap send, or turn on screen control so Jarvis can.", "to" to name)
        }
    }

    // ---------- calendar ----------
    private fun parseMillis(s: String, zone: ZoneId): Long {
        val t = s.trim()
        try { return OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return LocalDateTime.parse(t.replace(" ", "T")).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return LocalDate.parse(t.take(10)).atStartOfDay(zone).toInstant().toEpochMilli() } catch (_: Exception) {}
        throw ToolError("I couldn't read the time '$s'. Use a format like 2026-10-25T15:00")
    }

    private fun writableCalendar(ctx: Context): Pair<Long, String> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val list = mutableListOf<Triple<Long, String, Int>>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(2) == 1) 2 else 0) + (if ((c.getString(3) ?: "") == "com.google") 1 else 0)
                list.add(Triple(c.getLong(0), c.getString(1) ?: "Calendar", score))
            }
        }
        val best = list.maxByOrNull { it.third }
            ?: throw ToolError("There's no calendar I can write to. Add a Google account in Settings, Accounts.")
        return Pair(best.first, best.second)
    }

    private fun createEvent(act: MainActivity, a: JSONObject): JSONObject {
        need(act, Manifest.permission.WRITE_CALENDAR, "calendar")
        val title = a.optString("title").trim()
        if (title.isEmpty()) throw ToolError("The event needs a title")
        val startStr = a.optString("start").trim()
        if (startStr.isEmpty()) throw ToolError("When does it start?")
        val zone = ZoneId.systemDefault()
        val allDay = a.optBoolean("all_day", false)
        val cal = writableCalendar(act)

        val v = ContentValues()
        if (allDay) {
            val d = LocalDate.parse(startStr.take(10))
            val s = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            v.put(CalendarContract.Events.DTSTART, s)
            v.put(CalendarContract.Events.DTEND, s + 86_400_000L)
            v.put(CalendarContract.Events.ALL_DAY, 1)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            val s = parseMillis(startStr, zone)
            val endStr = a.optString("end").trim()
            val e = if (endStr.isEmpty()) s + 3_600_000L else parseMillis(endStr, zone)
            v.put(CalendarContract.Events.DTSTART, s)
            v.put(CalendarContract.Events.DTEND, maxOf(e, s + 60_000L))
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.CALENDAR_ID, cal.first)
        val loc = a.optString("location").trim()
        if (loc.isNotEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, loc)
        val notes = a.optString("notes").trim()
        if (notes.isNotEmpty()) v.put(CalendarContract.Events.DESCRIPTION, notes)

        val uri = act.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            ?: throw ToolError("The calendar refused the event")
        val id = ContentUris.parseId(uri)
        try {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, id)
            r.put(CalendarContract.Reminders.MINUTES, 10)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            act.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        } catch (_: Exception) {
        }
        return ok("created" to title, "calendar" to cal.second, "start" to startStr, "reminder_minutes_before" to 10)
    }

    private fun listEvents(act: MainActivity, a: JSONObject): JSONObject {
        need(act, Manifest.permission.READ_CALENDAR, "calendar")
        val zone = ZoneId.systemDefault()
        val sd = a.optString("start_date").trim()
        val startDay = if (sd.isEmpty()) LocalDate.now(zone) else LocalDate.parse(sd.take(10))
        val days = a.optInt("days", 1).coerceIn(1, 31)
        val begin = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = startDay.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, begin)
        ContentUris.appendId(b, end)
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        val arr = JSONArray()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        act.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && arr.length() < 40) {
                val st = Instant.ofEpochMilli(c.getLong(1)).atZone(zone)
                val whenText = if (c.getInt(3) == 1) "all day " + st.toLocalDate() else fmt.format(st)
                arr.put(JSONObject().put("title", c.getString(0) ?: "(no title)").put("when", whenText).put("location", c.getString(2) ?: ""))
            }
        }
        return ok("from" to startDay.toString(), "days" to days, "events" to arr)
    }

    // ---------- alarms ----------
    private fun setAlarm(act: MainActivity, a: JSONObject): JSONObject {
        val h = a.optInt("hour", -1)
        val m = a.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) throw ToolError("Give the alarm time as hour 0-23 and minute 0-59")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = a.optString("label").trim()
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val daysArr = a.optJSONArray("days")
        if (daysArr != null) {
            val map = mapOf(
                "sun" to java.util.Calendar.SUNDAY, "mon" to java.util.Calendar.MONDAY, "tue" to java.util.Calendar.TUESDAY,
                "wed" to java.util.Calendar.WEDNESDAY, "thu" to java.util.Calendar.THURSDAY, "fri" to java.util.Calendar.FRIDAY,
                "sat" to java.util.Calendar.SATURDAY
            )
            val days = ArrayList<Int>()
            for (k in 0 until daysArr.length()) {
                val d = map[daysArr.optString(k).lowercase(Locale.ROOT).take(3)]
                if (d != null) days.add(d)
            }
            if (days.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
        }
        try {
            start(act, i)
        } catch (e: Exception) {
            throw ToolError("No clock app on this tablet accepts alarms")
        }
        return ok("alarm" to String.format(Locale.ROOT, "%02d:%02d", h, m), "repeat" to (daysArr?.toString() ?: "once"))
    }

    // ---------- device controls ----------
    private fun setVolume(act: MainActivity, a: JSONObject): JSONObject {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val name = a.optString("stream", "media")
        val stream = when (name) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val pct = a.optDouble("level", 50.0).coerceIn(0.0, 100.0)
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, Math.round(max * pct / 100.0).toInt(), AudioManager.FLAG_SHOW_UI)
        return ok("volume_percent" to pct.toInt(), "stream" to name)
    }

    private fun setBrightness(act: MainActivity, a: JSONObject): JSONObject {
        if (!Settings.System.canWrite(act)) {
            act.runOnUiThread { Setup.open(act, "write_settings") }
            throw ToolError("Jarvis needs permission to change system settings. I've opened that page: turn on the switch, then ask again.")
        }
        val pct = a.optDouble("level", 50.0).coerceIn(0.0, 100.0)
        Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS, Math.round(pct * 2.55).toInt().coerceIn(1, 255))
        return ok("brightness_percent" to pct.toInt())
    }

    private fun flashlight(act: MainActivity, on: Boolean): JSONObject {
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw ToolError("This tablet has no flashlight")
        cm.setTorchMode(id, on)
        return ok("flashlight" to if (on) "on" else "off")
    }

    private fun media(act: MainActivity, action: String): JSONObject {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok("media" to action)
    }

    private fun dnd(act: MainActivity, on: Boolean): JSONObject {
        val nm = act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            act.runOnUiThread { Setup.open(act, "dnd") }
            throw ToolError("Jarvis needs Do Not Disturb access. I've opened that page: allow Jarvis, then ask again.")
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ok("do_not_disturb" to on)
    }

    private fun openSettings(act: MainActivity, page: String): JSONObject {
        val action = when (page) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        start(act, Intent(action))
        SystemClock.sleep(1200)
        return ok("opened" to page, "note" to "The settings page is open. Use read_screen and tap to change a switch if needed.")
    }

    private fun readNotifications(): JSONObject {
        val svc = JarvisNotificationService.instance
            ?: throw ToolError("Notification access is off. Open Systems in Jarvis and tap Notification access.")
        return ok("notifications" to svc.recentJson(15))
    }
}
