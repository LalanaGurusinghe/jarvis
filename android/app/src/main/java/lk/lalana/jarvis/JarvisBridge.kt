package lk.lalana.jarvis

import android.Manifest
import android.os.Build
import android.view.WindowManager
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.concurrent.Executors

/** The object the web interface sees as window.JarvisAndroid. */
class JarvisBridge(private val act: MainActivity) {
    private val exec = Executors.newCachedThreadPool()
    private val speech = Speech(act)

    fun shutdown() {
        speech.shutdown()
        exec.shutdownNow()
    }

    @JavascriptInterface
    fun ready(): Boolean {
        act.pageReady = true
        val wake = act.pendingWake
        act.pendingWake = false
        return wake
    }

    @JavascriptInterface
    fun info(): String = DeviceInfo.json(act).toString()

    @JavascriptInterface
    fun listen(lang: String) {
        speech.listen(lang)
    }

    @JavascriptInterface
    fun stopListen() {
        speech.stopListen()
    }

    @JavascriptInterface
    fun speak(text: String, rate: Double, pitch: Double, id: String) {
        speech.speak(text, rate, pitch, id)
    }

    @JavascriptInterface
    fun stopSpeak() {
        speech.stopSpeak()
    }

    @JavascriptInterface
    fun keepScreenOn(on: Boolean) {
        act.runOnUiThread {
            if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @JavascriptInterface
    fun setFloating(on: Boolean) {
        act.getSharedPreferences("jarvis", 0).edit().putBoolean("floating", on).apply()
        JarvisAccessibilityService.instance?.updateFloating()
    }

    @JavascriptInterface
    fun requestPermissions(csv: String) {
        val perms = mutableListOf<String>()
        for (k in csv.split(",")) {
            when (k.trim()) {
                "mic" -> perms.add(Manifest.permission.RECORD_AUDIO)
                "contacts" -> perms.add(Manifest.permission.READ_CONTACTS)
                "calendar" -> {
                    perms.add(Manifest.permission.READ_CALENDAR)
                    perms.add(Manifest.permission.WRITE_CALENDAR)
                }
                "sms" -> perms.add(Manifest.permission.SEND_SMS)
                "location" -> {
                    perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                "notify" -> if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) act.runOnUiThread { act.requestPermissions(perms.toTypedArray(), MainActivity.REQ_PERMS) }
    }

    @JavascriptInterface
    fun openSetup(which: String) {
        act.runOnUiThread { Setup.open(act, which) }
    }

    /** Runs a device tool off the UI thread and answers through window.jarvisNative.onTool(id, json). */
    @JavascriptInterface
    fun tool(name: String, args: String, id: String) {
        exec.execute {
            val res: JSONObject = try {
                Tools.run(act, name, if (args.isBlank()) JSONObject() else JSONObject(args))
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: e.toString())
            }
            act.js("window.jarvisNative&&window.jarvisNative.onTool(${JSONObject.quote(id)}, ${JSONObject.quote(res.toString())})")
        }
    }
}
