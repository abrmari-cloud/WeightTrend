package com.weighttrend.garmin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.weighttrend.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows Garmin's own sign-in page (with its captcha and two-factor steps, if any)
 * and catches the one-time service ticket it produces. The password stays
 * between the user and Garmin's page.
 */
class GarminLoginActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val handled = AtomicBoolean(false)

    /** Watches the page's network responses for the ticket (the mobile page gets it via fetch/XHR). */
    private val hookScript = """
        (function () {
          if (window.__wtHooked) return; window.__wtHooked = true;
          function check(t) {
            try {
              if (!t) return;
              var m = /"serviceTicketId"\s*:\s*"(ST-[^"]+)"/.exec(t) || /[?&]ticket=(ST-[A-Za-z0-9._\-]+)/.exec(t);
              if (m) WTGarmin.onTicket(m[1]);
            } catch (e) {}
          }
          check(location.href);
          var f = window.fetch;
          if (f) window.fetch = function () {
            return f.apply(this, arguments).then(function (r) {
              try { r.clone().text().then(check); } catch (e) {}
              return r;
            });
          };
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            this.addEventListener('load', function () { try { check(this.responseText); } catch (e) {} });
            return send.apply(this, arguments);
          };
        })();
    """.trimIndent()

    private val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Вход в Garmin"
        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "WTGarmin")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    interceptUrl(request.url)

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (interceptUrl(Uri.parse(url))) { view.stopLoading(); return }
                    if (!documentStart) view.evaluateJavascript(hookScript, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!documentStart) view.evaluateJavascript(hookScript, null)
                }
            }
        }
        if (documentStart) {
            WebViewCompat.addDocumentStartJavaScript(web, hookScript, setOf("https://sso.garmin.com"))
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Keep the page clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(web)
        web.loadUrl(GarminApi.LOGIN_URL)
    }

    /** The page finally sends the browser to the app's service URL with ?ticket=ST-… */
    private fun interceptUrl(uri: Uri): Boolean {
        val ticket = uri.getQueryParameter("ticket")
        if (ticket != null && ticket.startsWith("ST-")) { onTicket(ticket); return true }
        return uri.host == GarminApi.SERVICE_HOST
    }

    private fun onTicket(ticket: String) {
        if (!handled.compareAndSet(false, true)) return
        runOnUiThread { web.stopLoading() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GarminApi.exchangeTicket(ticket) }
            }
            result.onSuccess { tokens ->
                val store = GarminStore(this@GarminLoginActivity)
                val firstConnect = !store.isConnected && store.connectedAtMs == 0L
                store.tokens = tokens
                store.needsLogin = false
                store.lastStatus = "Вход выполнен"
                if (firstConnect) {
                    store.connectedAtMs = System.currentTimeMillis()
                    // History is uploaded by file once; only weigh-ins from now on go automatically.
                    withContext(Dispatchers.IO) {
                        val repo = Repository.get(applicationContext)
                        repo.markGarminExported(GarminSync.pending(repo).map { it.id })
                    }
                }
                GarminSync.schedule(applicationContext, delaySeconds = 0)
                Toast.makeText(this@GarminLoginActivity, "Garmin подключён", Toast.LENGTH_LONG).show()
                finish()
            }.onFailure { e ->
                handled.set(false)
                Toast.makeText(this@GarminLoginActivity, "Не удалось войти: ${e.message}", Toast.LENGTH_LONG).show()
                web.loadUrl(GarminApi.LOGIN_URL)
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onTicket(ticket: String) {
            if (ticket.startsWith("ST-")) this@GarminLoginActivity.onTicket(ticket)
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
