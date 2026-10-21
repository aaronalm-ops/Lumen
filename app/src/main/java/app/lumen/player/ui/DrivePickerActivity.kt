package app.lumen.player.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.lumen.player.BuildConfig
import app.lumen.player.diag.RingLog

/**
 * The Drive folder picker: a full-screen WebView showing Google's Picker (JS API) with the
 * access token MainActivity already obtained through the AuthorizationClient and the API key
 * from BuildConfig. The page is bundled (assets/picker.html) and served under an https base
 * so the Picker's origin check has something to hold on to. The picked folder comes back as
 * RESULT_OK with [EXTRA_FOLDER_ID] and [EXTRA_FOLDER_NAME]; cancel and failure are RESULT_CANCELED
 * with [EXTRA_REASON] set on a failure. No dialogs: a one-line status at the bottom of the page.
 */
class DrivePickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOKEN = "app.lumen.player.drive.token"
        const val EXTRA_FOLDER_ID = "app.lumen.player.drive.folderId"
        const val EXTRA_FOLDER_NAME = "app.lumen.player.drive.folderName"
        const val EXTRA_REASON = "app.lumen.player.drive.reason"

        /** The origin the page runs under; the API key's HTTP referrer restriction must allow it. */
        const val BASE_URL = "https://appassets.androidplatform.net/picker/"

        fun createIntent(context: Context, token: String): Intent =
            Intent(context, DrivePickerActivity::class.java).putExtra(EXTRA_TOKEN, token)
    }

    private var webView: WebView? = null
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (token.isBlank() || BuildConfig.PICKER_API_KEY.isBlank()) {
            finishWith(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, "Drive not configured"))
            return
        }
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(Color.BLACK)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.loadWithOverviewMode = true
        view.settings.useWideViewPort = true
        view.webViewClient = WebViewClient()
        view.webChromeClient = WebChromeClient()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Bridge(token), "LumenPicker")
        setContentView(view)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWith(Activity.RESULT_CANCELED, Intent())
                }
            },
        )
        val html = try {
            assets.open("picker.html").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            RingLog.e("drive", "picker page missing", t)
            finishWith(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, "Picker page missing"))
            return
        }
        view.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
        RingLog.i("drive", "picker opened")
    }

    private fun finishWith(result: Int, data: Intent) {
        if (finished) return
        finished = true
        setResult(result, data)
        finish()
    }

    override fun onDestroy() {
        webView?.let { v ->
            v.removeJavascriptInterface("LumenPicker")
            v.loadUrl("about:blank")
            v.destroy()
        }
        webView = null
        super.onDestroy()
    }

    /** Called from the page's JavaScript thread; results are posted back to the main thread. */
    private inner class Bridge(private val token: String) {
        @JavascriptInterface
        fun token(): String = token

        @JavascriptInterface
        fun apiKey(): String = BuildConfig.PICKER_API_KEY

        @JavascriptInterface
        fun picked(id: String?, name: String?) {
            val folderId = id.orEmpty().trim()
            runOnUiThread {
                if (folderId.isBlank()) {
                    finishWith(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, "Nothing picked"))
                } else {
                    RingLog.i("drive", "picker returned folder $folderId")
                    finishWith(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_FOLDER_ID, folderId).putExtra(EXTRA_FOLDER_NAME, name.orEmpty().trim()),
                    )
                }
            }
        }

        @JavascriptInterface
        fun cancelled() {
            runOnUiThread { finishWith(Activity.RESULT_CANCELED, Intent()) }
        }

        @JavascriptInterface
        fun failed(reason: String?) {
            RingLog.w("drive", "picker failed: $reason")
            runOnUiThread { finishWith(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_REASON, reason ?: "Picker failed")) }
        }
    }
}
