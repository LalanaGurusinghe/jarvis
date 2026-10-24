package lk.lalana.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Screen control: reads what's on screen and taps, types, scrolls or presses system buttons in other apps
 * when the brain asks. Taps on anything that sends, pays, posts or deletes need the user's OK first.
 * Also shows the floating button that calls Jarvis from any app.
 */
class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        private val RISKY = Regex(
            "\\b(send|pay|buy|purchase|order|checkout|delete|remove|uninstall|transfer|post|publish|share|confirm|subscribe|book|reset|erase|sign out|log out)\\b",
            RegexOption.IGNORE_CASE
        )
    }

    private class Elem(val label: String, val kind: String, val bounds: Rect, val node: AccessibilityNodeInfo)

    private val main = Handler(Looper.getMainLooper())
    private var floatView: View? = null
    @Volatile private var autoSendPkg: String? = null
    @Volatile private var autoSendUntil = 0L
    @Volatile private var last: List<Elem> = emptyList()
    @Volatile private var currentPkg = ""

    override fun onServiceConnected() {
        instance = this
        updateFloating()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        removeFloating()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeFloating()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- WhatsApp: press send once, right after the user confirmed in Jarvis ----------
    fun armAutoSend(pkg: String) {
        autoSendPkg = pkg
        autoSendUntil = SystemClock.uptimeMillis() + 15_000
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = autoSendPkg ?: return
        if (SystemClock.uptimeMillis() > autoSendUntil) {
            autoSendPkg = null
            return
        }
        if (event?.packageName?.toString() != pkg) return
        val root = rootInActiveWindow ?: return
        val btn = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull() ?: findByDesc(root, "send", 0)
        if (btn != null && btn.isEnabled) {
            autoSendPkg = null
            main.postDelayed({ clickNode(btn) }, 400)
        }
    }

    private fun findByDesc(n: AccessibilityNodeInfo?, want: String, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30) return null
        if (n.isClickable && n.contentDescription?.toString()?.equals(want, true) == true) return n
        for (i in 0 until n.childCount) {
            val r = findByDesc(n.getChild(i), want, depth + 1)
            if (r != null) return r
        }
        return null
    }

    // ---------- tools ----------
    fun handle(name: String, a: JSONObject): JSONObject = when (name) {
        "read_screen" -> JSONObject().put("screen", describe(scan()))
        "tap" -> tap(a)
        "type_text" -> type(a)
        "scroll" -> scroll(a.optString("direction", "down"))
        "press" -> press(a.optString("button", "back"))
        else -> throw ToolError("Unknown screen action $name")
    }

    private fun scan(): List<Elem> {
        val root = rootInActiveWindow ?: throw ToolError("I can't see the screen right now. Try again in a moment.")
        val out = mutableListOf<Elem>()
        walk(root, out, 0)
        currentPkg = root.packageName?.toString() ?: ""
        last = out
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<Elem>, depth: Int) {
        if (n == null || out.size >= 120 || depth > 40) return
        if (!n.isVisibleToUser) return
        var own = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
        if (own.isBlank() && Build.VERSION.SDK_INT >= 26) own = n.hintText?.toString() ?: ""
        own = own.trim()
        val clickable = n.isClickable || n.isCheckable
        val kind = when {
            n.isEditable -> "field"
            n.isCheckable -> if (n.isChecked) "switch(on)" else "switch(off)"
            clickable -> "button"
            n.isScrollable -> "list"
            else -> "text"
        }
        val label = if (own.isEmpty() && clickable) childText(n, 0).trim() else own
        if (label.isNotEmpty() || n.isEditable || n.isScrollable) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out.add(Elem(label.take(80), kind, r, n))
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun childText(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 3) return ""
        val sb = StringBuilder()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val t = c.text?.toString() ?: c.contentDescription?.toString()
            if (!t.isNullOrBlank()) sb.append(t).append(' ') else sb.append(childText(c, depth + 1))
            if (sb.length > 80) break
        }
        return sb.toString()
    }

    private fun describe(list: List<Elem>): String {
        val sb = StringBuilder("app: ").append(currentPkg).append('\n')
        for ((i, e) in list.withIndex()) {
            val line = "[$i] ${e.kind}: ${e.label.replace('\n', ' ')}\n"
            if (sb.length + line.length > 5000) break
            sb.append(line)
        }
        return sb.toString()
    }

    private fun safeDescribe(): String = try {
        describe(scan()).take(2500)
    } catch (_: Exception) {
        ""
    }

    private fun appLabel(): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(currentPkg, 0)).toString()
    } catch (_: Exception) {
        currentPkg
    }

    private fun tap(a: JSONObject): JSONObject {
        val e: Elem
        if (a.has("index")) {
            val list = if (last.isNotEmpty()) last else scan()
            e = list.getOrNull(a.optInt("index", -1)) ?: throw ToolError("No element with that number. Read the screen again.")
        } else {
            val t = a.optString("text").trim()
            if (t.isEmpty()) throw ToolError("Say which element to tap, by index or text")
            val list = scan()
            e = list.firstOrNull { it.label.equals(t, true) }
                ?: list.firstOrNull { it.label.contains(t, true) && it.kind != "text" }
                ?: list.firstOrNull { it.label.contains(t, true) }
                ?: throw ToolError("I can't see \"$t\" on screen")
        }
        if (RISKY.containsMatchIn(e.label)) {
            if (!askOverlay("Allow Jarvis to tap?", "“${e.label}” in ${appLabel()}", "Tap")) {
                return JSONObject().put("tapped", false).put("reason", "The user declined")
            }
        }
        if (!clickNode(e.node)) gestureTap(e.bounds.exactCenterX(), e.bounds.exactCenterY())
        SystemClock.sleep(900)
        return JSONObject().put("tapped", e.label).put("screen_after", safeDescribe())
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && !n.isClickable && hops < 5) {
            n = n.parent
            hops++
        }
        return try {
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val latch = CountDownLatch(1)
        main.post {
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun gestureTap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        gesture(p, 80)
    }

    private fun type(a: JSONObject): JSONObject {
        val text = a.optString("text")
        val target: AccessibilityNodeInfo = if (a.has("index")) {
            (last.getOrNull(a.optInt("index", -1)) ?: throw ToolError("No element with that number. Read the screen again.")).node
        } else {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) focused
            else scan().firstOrNull { it.kind == "field" }?.node ?: throw ToolError("There's no text box on screen")
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        SystemClock.sleep(500)
        if (!ok) throw ToolError("That box didn't accept text")
        return JSONObject().put("typed", text).put("screen_after", safeDescribe())
    }

    private fun scroll(dir: String): JSONObject {
        val list = scan()
        val sc = list.firstOrNull { it.node.isScrollable }
        val action = if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val done = try {
            sc?.node?.performAction(action) == true
        } catch (_: Exception) {
            false
        }
        if (!done) {
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val top = dm.heightPixels * 0.3f
            val bottom = dm.heightPixels * 0.75f
            val p = Path()
            if (dir == "up") {
                p.moveTo(x, top); p.lineTo(x, bottom)
            } else {
                p.moveTo(x, bottom); p.lineTo(x, top)
            }
            gesture(p, 350)
        }
        SystemClock.sleep(800)
        return JSONObject().put("scrolled", dir).put("screen_after", safeDescribe())
    }

    private fun press(b: String): JSONObject {
        val action = when (b) {
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> GLOBAL_ACTION_BACK
        }
        performGlobalAction(action)
        SystemClock.sleep(700)
        return JSONObject().put("pressed", b).put("screen_after", safeDescribe())
    }

    // ---------- on-screen confirmation over other apps ----------
    private fun askOverlay(title: String, msg: String, yes: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        confirmOverlay(title, msg, yes) { r ->
            ok = r
            latch.countDown()
        }
        latch.await(90, TimeUnit.SECONDS)
        return ok
    }

    fun confirmOverlay(title: String, msg: String, yes: String, cb: (Boolean) -> Unit) {
        main.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val d = resources.displayMetrics.density
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            val pad = (20 * d).toInt()
            box.setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#F20A1016"))
            bg.cornerRadius = 18 * d
            bg.setStroke((2 * d).toInt(), Color.parseColor("#58D3E8"))
            box.background = bg

            val t1 = TextView(this)
            t1.text = title
            t1.setTextColor(Color.WHITE)
            t1.textSize = 18f
            t1.setTypeface(t1.typeface, Typeface.BOLD)
            box.addView(t1)
            val t2 = TextView(this)
            t2.text = msg
            t2.setTextColor(Color.parseColor("#D5E3E9"))
            t2.textSize = 15f
            t2.setPadding(0, (8 * d).toInt(), 0, (14 * d).toInt())
            box.addView(t2)

            var finished = false
            fun finish(r: Boolean) {
                if (finished) return
                finished = true
                try {
                    wm.removeView(box)
                } catch (_: Exception) {
                }
                cb(r)
            }

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.END
            val no = Button(this)
            no.text = "Cancel"
            no.setOnClickListener { finish(false) }
            val go = Button(this)
            go.text = yes
            go.setOnClickListener { finish(true) }
            row.addView(no)
            row.addView(go)
            box.addView(row)

            val width = (minOf(resources.displayMetrics.widthPixels, (520 * d).toInt()) * 0.92).toInt()
            val lp = WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.CENTER
            try {
                wm.addView(box, lp)
                main.postDelayed({ finish(false) }, 60_000)
            } catch (_: Exception) {
                cb(false)
            }
        }
    }

    // ---------- floating Jarvis button ----------
    fun updateFloating() {
        main.post {
            val want = getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("floating", true)
            if (want && floatView == null) addFloating() else if (!want) removeFloating()
        }
    }

    private fun removeFloating() {
        val v = floatView ?: return
        floatView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFloating() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val v = ImageView(this)
        v.setImageResource(R.mipmap.ic_launcher)
        v.alpha = 0.9f
        v.contentDescription = "Talk to Jarvis"
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = (6 * d).toInt()
        lp.y = (220 * d).toInt()
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = (ox - dx).toInt()
                        lp.y = (oy + dy).toInt()
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val i = Intent(this, MainActivity::class.java).putExtra("listen", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                    }
                }
            }
            true
        }
        try {
            wm.addView(v, lp)
            floatView = v
        } catch (_: Exception) {
        }
    }
}
