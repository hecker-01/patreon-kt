package com.patreonkt.auth

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

/**
 * Uses a hidden [WebView] to retrieve a Patreon page and harvest session cookies.
 *
 * This is a last-resort fallback for pages that return HTTP 403 to direct requests.
 * **Must be called from the main thread** (the [Context] must be an Activity/Fragment context).
 *
 * @param context Activity or Fragment context; must be the main-thread context.
 * @param cookieJar The [PatreonCookieJar] to populate with harvested cookies.
 */
class WebViewFallback(
    private val context: Context,
    private val cookieJar: PatreonCookieJar
) {

    /**
     * Loads [url] in a hidden WebView and returns the page HTML after the JavaScript bridge
     * signals that the `__NEXT_DATA__` object is available.
     *
     * Suspends until the page finishes loading or a 15-second timeout elapses.
     *
     * @return The raw page HTML, or null if loading failed or timed out.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchPageHtml(url: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        val bridge = object {
            @JavascriptInterface
            fun onPageReady(html: String) {
                harvestCookies(url)
                if (cont.isActive) cont.resume(html)
            }
        }
        webView.addJavascriptInterface(bridge, "PatreonKt")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                view?.evaluateJavascript(
                    "PatreonKt.onPageReady(document.documentElement.outerHTML);",
                    null
                )
            }
        }

        webView.loadUrl(url)
        cont.invokeOnCancellation { webView.destroy() }
    }

    private fun harvestCookies(url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val rawCookies = CookieManager.getInstance().getCookie(url) ?: return
        val cookies = rawCookies.split(";").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            Cookie.Builder()
                .domain(httpUrl.host)
                .path("/")
                .name(name)
                .value(value)
                .build()
        }
        cookieJar.addCookies(httpUrl.host, cookies)
    }
}
