package app.morsecode.android.core.webshare

import android.content.Context

/**
 * §7.1 / Stage 14: "the web UI is compiled into the APK" and §3.4 forbids any
 * CDN — the site must work with no internet at all, which is the whole point
 * of the product.
 *
 * Assets live under `assets/web/` and are read straight out of the APK.
 */
class WebAssets(context: Context) {

    private val assets = context.applicationContext.assets

    fun read(name: String): ByteArray? = try {
        assets.open("web/$name").use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }
}
