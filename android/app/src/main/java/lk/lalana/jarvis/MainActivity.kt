package lk.lalana.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

private val BG = 0xFF05080B.toInt()

/** Hosts the Jarvis interface (bundled web app) and exposes Android abilities to it through [JarvisBridge]. */
class MainActivity : Activity() {
    lateinit var web: WebView
    private lateinit var bridge: JarvisBridge

    @Volatile var pageReady = false
    @Volatile var pendingWake = false
    @Volatile var isInFront = false
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    companion object {
        const val REQ_GEO = 11
        const val REQ_PERMS = 12
        const val REQ_MIC = 13
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(BG)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setGeolocationEnabled(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = Pair(origin, callback)
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                        REQ_GEO
                    )
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        bridge = JarvisBridge(this)
        web.addJavascriptInterface(bridge, "JarvisAndroid")
        setContentView(web)
        handleWake(intent)
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWake(intent)
    }

    /** Opened from the assistant gesture or the floating button: start listening straight away. */
    private fun handleWake(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_ASSIST || i.getBooleanExtra("listen", false)) {
            if (pageReady) js("window.jarvisNative&&window.jarvisNative.onWake()") else pendingWake = true
        }
    }

    fun js(code: String) {
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(code, null) }
    }

    override fun onResume() {
        super.onResume()
        isInFront = true
        if (pageReady) js("window.jarvisNative&&window.jarvisNative.onResume&&window.jarvisNative.onResume()")
    }

    override fun onPause() {
        super.onPause()
        isInFront = false
    }

    override fun onDestroy() {
        bridge.shutdown()
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_GEO) {
            val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            pendingGeo?.let { it.second.invoke(it.first, ok, false) }
            pendingGeo = null
        }
        js("window.jarvisNative&&window.jarvisNative.onPermissions&&window.jarvisNative.onPermissions()")
    }
}
