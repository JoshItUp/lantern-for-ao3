package app.ao3reader.net

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Briefly hosts a WebView so Cloudflare's check can complete and cookies get shared. */
class ChallengeActivity : Activity() {
    private var webView: WebView? = null
    private var delivered = false
    private var token = -1L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getLongExtra(EXTRA_TOKEN, -1L)
        if (!ChallengeRegistry.isCurrent(token)) {
            delivered = true
            finish()
            return
        }
        current = WeakReference(this)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            deliver(false)
        }
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            deliver(false)
            return
        }
        val bg = Color.parseColor("#0B0B0C")
        val header = TextView(this).apply {
            text = "Checking AO3…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 32, 32, 32)
        }
        val wv = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            intent.getStringExtra(EXTRA_UA)?.let { settings.userAgentString = it }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    checkPage(view)
                }
            }
        }
        webView = wv
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bg)
                addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        wv.loadUrl(url)
    }

    private fun checkPage(view: WebView) {
        view.evaluateJavascript(
            "(function(){return JSON.stringify([document.title, document.documentElement.outerHTML]);})()",
        ) { raw ->
            if (delivered || isDestroyed) return@evaluateJavascript
            val (title, html) = decodeTitleAndHtml(raw) ?: return@evaluateJavascript
            val challenge = isChallengePage(title, html)
            if (!challenge) {
                CookieManager.getInstance().flush()
                deliver(true)
            }
        }
    }

    private fun deliver(ok: Boolean) {
        if (delivered) return
        delivered = true
        ChallengeRegistry.complete(token, ok)
        finish()
    }

    override fun onDestroy() {
        if (!delivered) {
            delivered = true
            ChallengeRegistry.complete(token, false)
        }
        if (current?.get() === this) current = null
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_UA = "ua"
        const val EXTRA_TOKEN = "token"
        private var current: WeakReference<ChallengeActivity>? = null

        /** Closes a lingering window (e.g. after timeout). */
        internal fun requestFinish(token: Long) {
            current?.get()?.takeIf { it.token == token }?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }
}
