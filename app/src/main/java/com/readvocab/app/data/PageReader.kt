package com.readvocab.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Reads the article on a web page without showing it; returns what the extraction script returned. Swapped out in tests. */
fun interface PageReader {
    suspend fun read(url: String): String?
}

/**
 * Downloads a page with the in-app browser's cookies (so a site the reader signed in to gives the
 * full text) and runs the same Readability extraction as the browser's import button, in a
 * WebView that is never shown. The page's own scripts are removed and it may not load anything,
 * so this costs one download per article and shows no ads.
 */
class WebPageReader(private val context: Context, private val script: () -> String) : PageReader {
    override suspend fun read(url: String): String? {
        val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android)")
        val html = withContext(Dispatchers.IO) {
            val bytes = HttpFetcher.fetch(url, "text/html,application/xhtml+xml,*/*;q=0.8", ua) { u ->
                runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull()
            }
            stripScripts(bytes.decodeToString())
        }
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) { extract(url, html) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extract(url: String, html: String): String? = suspendCancellableCoroutine { cont ->
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.blockNetworkLoads = true
        web.settings.loadsImagesAutomatically = false
        var done = false
        fun finish(result: String?) {
            if (done) return
            done = true
            Handler(Looper.getMainLooper()).post { web.destroy() }
            if (cont.isActive) cont.resume(result)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String?) {
                view.evaluateJavascript(script()) { finish(it) }
            }
        }
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (!done) { done = true; web.destroy() } } }
        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
    }

    companion object {
        private val SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)

        /** Removes the page's scripts; JSON-LD metadata stays, as Readability reads it. */
        internal fun stripScripts(html: String): String = SCRIPT.replace(html) { m ->
            if (m.value.contains("application/ld+json", ignoreCase = true)) m.value else ""
        }
    }
}
