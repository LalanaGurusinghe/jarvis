package lk.lalana.jarvis

import android.app.AlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Blocks a tool until the user taps Send/Cancel on screen. No answer within 90 s counts as Cancel. */
object Confirm {
    fun ask(act: MainActivity, title: String, message: String, yes: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val svc = JarvisAccessibilityService.instance
        if (!act.isInFront && svc != null) {
            svc.confirmOverlay(title, message, yes) { r ->
                ok = r
                latch.countDown()
            }
        } else {
            act.runOnUiThread {
                AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(yes) { _, _ ->
                        ok = true
                        latch.countDown()
                    }
                    .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            }
        }
        latch.await(90, TimeUnit.SECONDS)
        return ok
    }
}
