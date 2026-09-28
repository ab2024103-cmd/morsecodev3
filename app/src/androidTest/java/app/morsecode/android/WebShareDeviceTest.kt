package app.morsecode.android

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.android.core.media.MediaLibrary
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.util.Ids
import app.morsecode.android.core.webshare.WebSessions
import app.morsecode.android.core.webshare.WebShareServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * §21.3: "the WebShare server answering 127.0.0.1:33455 in the emulator's own
 * browser".
 *
 * The AOSP emulator images have no browser app, so the page is loaded in a
 * WebView — the same engine a browser would use — and the test asserts the
 * document actually rendered, not merely that bytes arrived.
 */
@RunWith(AndroidJUnit4::class)
class WebShareDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var server: WebShareServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun start(consent: Boolean, port: Int = 0): Pair<Int, WebSessions> {
        val chosen = if (port != 0) port else ServerSocket(0).use { it.localPort }
        val sessions = WebSessions()
        val instance = WebShareServer(
            context = context,
            library = MediaLibrary(context, DeviceTier(context)),
            destinations = Destinations(context),
            sessions = sessions,
            onConsentNeeded = { consent },
            port = chosen,
        )
        instance.start(4000, false)
        server = instance
        return chosen to sessions
    }

    private fun get(port: Int, path: String, token: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        token?.let { connection.setRequestProperty("X-Morsecode-Token", it) }
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()
        return code to body
    }

    private fun awaitAcceptedHello(port: Int): Pair<Int, String> {
        repeat(80) {
            val reply = get(port, "/api/hello")
            if (!reply.second.contains("\"state\":\"pending\"")) return reply
            Thread.sleep(25)
        }
        return get(port, "/api/hello")
    }

    @Test
    fun theServerAnswersOnTheRealPortAndGuardsItsApi() {
        // The prescribed port, on the device, not an ephemeral one (§7.1).
        val (port, _) = start(consent = true, port = Ids.PORT_WEBSHARE)
        assertEquals(33455, port)

        val (helloCode, helloBody) = awaitAcceptedHello(port)
        assertEquals(200, helloCode)
        val token = Regex("\"token\":\"([a-f0-9]+)\"").find(helloBody)?.groupValues?.get(1)
        assertTrue("no token issued: $helloBody", !token.isNullOrEmpty())

        assertEquals(200, get(port, "/api/counts", token).first)
        assertEquals("§17.3: no token, no data", 401, get(port, "/api/counts").first)

        val (indexCode, indexBody) = get(port, "/", token)
        assertEquals(200, indexCode)
        assertTrue(indexBody.contains("Morsecode"))
    }

    @Test
    fun everyBrowserAssetPackagedWithTheHomePageAvoids404() {
        val (port, _) = start(consent = true)
        val (_, home) = get(port, "/")
        val paths = Regex("(?:src|href)=\"(/assets/[^\"]+)\"")
            .findAll(home)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("home page must name its packaged browser assets", paths.isNotEmpty())
        for (path in paths) {
            val (code, body) = get(port, path)
            assertEquals("missing browser asset $path", 200, code)
            assertTrue("empty browser asset $path", body.isNotEmpty())
        }
    }

    @Test
    fun theSiteRendersInAWebViewOnTheDevice() {
        val (port, _) = start(consent = true)
        val latch = CountDownLatch(1)
        var title: String? = null
        var error: String? = null

        instrumentation.runOnMainSync {
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("document.title") { value ->
                        title = value
                        latch.countDown()
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    err: android.webkit.WebResourceError,
                ) {
                    error = err.description?.toString()
                    latch.countDown()
                }
            }
            view.loadUrl("http://127.0.0.1:$port/")
        }

        val finished = latch.await(30, TimeUnit.SECONDS)
        assertTrue("the page never finished loading (error=$error)", finished)
        assertTrue("unexpected document title: $title", title.orEmpty().contains("Morsecode"))
    }

    @Test
    fun aZipOfASelectionStreamsFromTheDevice() {
        val (port, _) = start(consent = true)
        val (_, hello) = awaitAcceptedHello(port)
        val token = Regex("\"token\":\"([a-f0-9]+)\"").find(hello)?.groupValues?.get(1)

        val folder = java.io.File(context.cacheDir, "zip-src-${System.nanoTime()}").apply { mkdirs() }
        java.io.File(folder, "a.txt").writeText("alpha")
        java.io.File(folder, "b.txt").writeText("beta")

        val url = URL(
            "http://127.0.0.1:$port/download-folder?path=" +
                java.net.URLEncoder.encode(folder.absolutePath, "UTF-8") +
                "&token=$token",
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        assertEquals(200, connection.responseCode)
        assertEquals("no-store", connection.getHeaderField("Cache-Control"))

        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(connection.inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        connection.disconnect()
        assertEquals(listOf(folder.name + "/a.txt", folder.name + "/b.txt"), names)
    }
}
