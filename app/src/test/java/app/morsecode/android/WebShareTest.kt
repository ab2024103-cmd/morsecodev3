package app.morsecode.android

import androidx.test.core.app.ApplicationProvider
import app.morsecode.android.core.media.MediaLibrary
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.storage.ZipUtil
import app.morsecode.android.core.util.DeviceTier
import app.morsecode.android.core.webshare.HttpRange
import app.morsecode.android.core.webshare.WebSessions
import app.morsecode.android.core.webshare.WebShareServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay

/**
 * §7.1's gate, §7.2's Range and archives, §17.3's tokens.
 *
 * The server tests speak real HTTP to a real socket, so "a request without a
 * valid token gets 401" is executed rather than asserted about source code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebShareTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var server: WebShareServer? = null
    private val consentCalls = AtomicInteger()

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    // ----- §7.2 Range -------------------------------------------------------

    @Test
    fun rangeHeadersParseTheThreeFormsAndClamp() {
        val total = 1000L
        assertEquals(HttpRange.Span(0, 499, total), HttpRange.parse("bytes=0-499", total))
        assertEquals(HttpRange.Span(500, 999, total), HttpRange.parse("bytes=500-", total))
        // "bytes=-500" is the LAST 500 bytes, not the first.
        assertEquals(HttpRange.Span(500, 999, total), HttpRange.parse("bytes=-500", total))
        // An end past the file is clamped rather than refused.
        assertEquals(HttpRange.Span(900, 999, total), HttpRange.parse("bytes=900-5000", total))
        assertNull(HttpRange.parse(null, total))
        assertNull(HttpRange.parse("items=0-1", total))
    }

    @Test
    fun anUnsatisfiableRangeIsDetected() {
        assertTrue(HttpRange.isUnsatisfiable("bytes=5000-6000", 1000))
        assertFalse(HttpRange.isUnsatisfiable("bytes=0-10", 1000))
        assertFalse("no header at all is not an error", HttpRange.isUnsatisfiable(null, 1000))
    }

    @Test
    fun theContentRangeHeaderIsExact() {
        val span = HttpRange.parse("bytes=200-1023", 4096)!!
        assertEquals("bytes 200-1023/4096", span.contentRange)
        assertEquals(824L, span.length)
    }

    // ----- §17.3 tokens -----------------------------------------------------

    @Test
    fun aBrowserSeesNothingUntilThePhoneAccepts() {
        val sessions = WebSessions()
        val session = sessions.open("Chrome", "192.168.1.88")
        assertEquals(WebSessions.State.PENDING, session.state)
        assertNull("no token before Accept", session.token)
        assertNull(sessions.authorise(null))

        val accepted = sessions.accept(session.id)!!
        assertNotNull(accepted.token)
        assertEquals(accepted.id, sessions.authorise(accepted.token)?.id)
        assertEquals("Chrome · 192.168.1.88", accepted.label)
    }

    @Test
    fun rejectGetsNothingAndRevokeKillsTheToken() {
        val sessions = WebSessions()
        val rejected = sessions.reject(sessions.open("Chrome", "1.2.3.4").id)!!
        assertNull(rejected.token)
        assertNull(sessions.authorise("anything"))

        val live = sessions.accept(sessions.open("Firefox", "1.2.3.5").id)!!
        assertNotNull(sessions.authorise(live.token))
        sessions.revoke(live.id)
        assertNull("the token dies with the session (§7.1)", sessions.authorise(live.token))
    }

    @Test
    fun stoppingTheServerKillsEveryToken() {
        val sessions = WebSessions()
        val live = sessions.accept(sessions.open("Chrome", "1.2.3.4").id)!!
        sessions.clear()
        assertNull(sessions.authorise(live.token))
    }

    @Test
    fun anExpiredTokenIsRefused() {
        var now = 1_000L
        val sessions = WebSessions(clock = { now })
        val live = sessions.accept(sessions.open("Chrome", "1.2.3.4").id)!!
        now += WebSessions.TOKEN_IDLE_LIMIT_MS + 1
        assertNull(sessions.authorise(live.token))
    }

    // ----- §7.2 archives ----------------------------------------------------

    @Test
    fun aFolderStreamsAsAZipWithItsStructureIntact() {
        val root = File(context.cacheDir, "zip-${System.nanoTime()}/Camera")
        File(root, "sub").mkdirs()
        File(root, "a.txt").writeText("alpha")
        File(root, "sub/b.txt").writeText("beta")

        val sources = ZipUtil.sourcesOf(root)
        assertEquals(listOf("Camera/a.txt", "Camera/sub/b.txt"), sources.map { it.entryPath })

        val out = ByteArrayOutputStream()
        assertEquals(2, ZipUtil.writeTo(out, sources))

        val names = ArrayList<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        // §21.2 case 11: "structure reconstructed exactly".
        assertEquals(listOf("Camera/a.txt", "Camera/sub/b.txt"), names)
        assertEquals("Camera.zip", ZipUtil.archiveNameFor(root.name))
        assertEquals(9L, ZipUtil.recursiveSize(root))
    }

    @Test
    fun nonAsciiNamesSurviveTheArchive() {
        val root = File(context.cacheDir, "zip-utf8-${System.nanoTime()}/Fotos")
        root.mkdirs()
        File(root, "Grüße 日本.txt").writeText("hi")
        val out = ByteArrayOutputStream()
        ZipUtil.writeTo(out, ZipUtil.sourcesOf(root))

        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            val entry = zip.nextEntry
            assertEquals("Fotos/Grüße 日本.txt", entry?.name)
        }
    }

    // ----- §7.1 / A5 over real HTTP ----------------------------------------

    private fun startServer(consent: Boolean, consentDelayMillis: Long = 0): Pair<Int, WebSessions> {
        consentCalls.set(0)
        val port = ServerSocket(0).use { it.localPort }
        val sessions = WebSessions()
        val instance = WebShareServer(
            context = context,
            library = MediaLibrary(context, DeviceTier(context)),
            destinations = Destinations(context),
            sessions = sessions,
            onConsentNeeded = {
                consentCalls.incrementAndGet()
                if (consentDelayMillis > 0) delay(consentDelayMillis)
                consent
            },
            port = port,
        )
        instance.start(2000, false)
        server = instance
        return port to sessions
    }

    private fun request(port: Int, path: String, token: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        token?.let { connection.setRequestProperty("X-Morsecode-Token", it) }
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        val cacheControl = connection.getHeaderField("Cache-Control")
        connection.disconnect()
        return code to (body + "|" + cacheControl)
    }

    /** Browser polling is part of §7.1: consent must never hold its HTTP call. */
    private fun awaitHello(port: Int): Pair<Int, String> {
        repeat(80) {
            val reply = request(port, "/api/hello")
            if (!reply.second.contains("\"state\":\"pending\"")) return reply
            Thread.sleep(25)
        }
        return request(port, "/api/hello")
    }

    @Test
    fun aBrowserPollsPendingThenGetsATokenAfterApproval() {
        val (port, _) = startServer(consent = true, consentDelayMillis = 180)

        val (pendingCode, pendingBody) = request(port, "/api/hello")
        assertEquals(200, pendingCode)
        assertTrue("the waiting page must render before the phone answers", pendingBody.contains("\"state\":\"pending\""))

        val (helloCode, helloBody) = awaitHello(port)
        assertEquals(200, helloCode)
        assertTrue(helloBody.contains("\"state\":\"accepted\""))
        assertEquals("polling may not open duplicate phone dialogs", 1, consentCalls.get())
        val token = Regex("\"token\":\"([a-f0-9]+)\"").find(helloBody)?.groupValues?.get(1)
        assertNotNull(token)

        // A5: the full API only with the token…
        val (authorised, _) = request(port, "/api/info", token)
        assertEquals(200, authorised)

        // …and 401 without it (§17.3).
        val (unauthorised, _) = request(port, "/api/info")
        assertEquals(401, unauthorised)
    }

    @Test
    fun aRejectedBrowserIsRefusedEverything() {
        val (port, _) = startServer(consent = false)
        val (code, body) = awaitHello(port)
        assertEquals(200, code)
        assertTrue("rejected gets nothing", body.contains("\"state\":\"rejected\""))
        assertEquals(401, request(port, "/api/counts").first)
        assertEquals(401, request(port, "/api/files?category=PHOTOS").first)
        assertEquals(401, request(port, "/download?path=/etc/hosts").first)
    }

    @Test
    fun everyResponseCarriesNoStore() {
        val (port, _) = startServer(consent = true)
        val (_, helloBody) = awaitHello(port)
        val token = Regex("\"token\":\"([a-f0-9]+)\"").find(helloBody)?.groupValues?.get(1)
        assertNotNull(token)

        // §7.1: "Every response carries Cache-Control: no-store".
        for (path in listOf("/", "/api/info", "/api/counts", "/nope")) {
            val (_, body) = request(port, path, token)
            assertTrue("$path is missing no-store", body.endsWith("|no-store"))
        }
    }

    @Test
    fun theSiteIsServedFromTheApkWithNoNetworkDependency() {
        val (port, _) = startServer(consent = true)
        val (code, body) = request(port, "/")
        assertEquals(200, code)
        assertTrue(body.contains("Morsecode"))
        // §3.4 / Stage 14: no CDN, no external reference of any kind.
        assertFalse(body.contains("http://"))
        assertFalse(body.contains("https://"))
    }
}
