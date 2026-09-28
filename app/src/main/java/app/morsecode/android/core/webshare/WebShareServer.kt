package app.morsecode.android.core.webshare

import android.content.Context
import app.morsecode.android.core.logging.LogStore
import app.morsecode.android.core.media.FileTypes
import app.morsecode.android.core.media.MediaLibrary
import app.morsecode.android.core.model.MediaCategory
import app.morsecode.android.core.model.MediaItem
import app.morsecode.android.core.model.SortOrder
import app.morsecode.android.core.storage.Destinations
import app.morsecode.android.core.storage.ZipUtil
import app.morsecode.android.core.util.Ids
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * §7.1 / §7.2 THE WEBSHARE SERVER — NanoHTTPD bound to 0.0.0.0:33455.
 *
 * Started and stopped ONLY by explicit user action, and INV-4 [KEPT] is the
 * rule that matters most: no idle timeout, no auto-teardown, screen-off is
 * never a reason to close, and it never shuts down while a browser session is
 * connected. There is therefore no timer anywhere in this file.
 *
 * Access control is §7.1's: the URL carries no token, a new browser sees only
 * a waiting screen until the phone accepts, and every call after `/api/hello`
 * carries the issued token or gets 401 (§17.3, A5).
 *
 * Every response carries `Cache-Control: no-store` — §7.1 says a cached copy
 * of an old build silently breaking against new server logic "has bitten us
 * before".
 */
class WebShareServer(
    context: Context,
    private val library: MediaLibrary,
    private val destinations: Destinations,
    private val sessions: WebSessions,
    private val offers: PushOffers = PushOffers(),
    private val logStore: LogStore? = null,
    /** Asked when a new browser appears; suspends until the owner answers. */
    private val onConsentNeeded: suspend (WebSessions.Session) -> Boolean = { true },
    port: Int = Ids.PORT_WEBSHARE,
) : NanoHTTPD("0.0.0.0", port) {

    private val appContext = context.applicationContext
    private val assets = WebAssets(appContext)

    /** §7.2: sized, server-cached thumbnails keyed on id + mtime. */
    private val thumbnails = ThumbnailStore(appContext)
    private val uploads = ConcurrentHashMap<String, UploadState>()

    /** A held SSE stream, not the former two-second snapshot poll (§7.8). */
    private val sse = SseHub { sessionId -> eventsPayload(sessionId).toString() }

    init {
        // A phone-originated offer reaches its owning browser immediately. The
        // hub scopes each write by session id, so offers cannot leak sideways.
        offers.addListener { sessionId -> sse.publish(sessionId) }
    }

    private class UploadState(val name: String, val target: File) {
        @Volatile
        var receivedChunks: Int = 0

        @Volatile
        var bytesWritten: Long = 0
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return try {
            route(session, uri)
        } catch (error: Exception) {
            logStore?.e("WebShare ${session.method} $uri failed: ${error.javaClass.simpleName}")
            noStore(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON, """{"error":"failed"}"""))
        }
    }

    private fun route(session: IHTTPSession, uri: String): Response = when {
        uri == "/" || uri == "/index.html" -> asset("index.html", "text/html")
        uri.startsWith("/assets/") -> asset(uri.removePrefix("/assets/"), assets.mimeFor(uri))
        uri == "/api/hello" -> hello(session)
        uri == "/api/info" -> guarded(session) { info() }
        uri == "/api/counts" -> guarded(session) { counts() }
        uri == "/api/files" -> guarded(session) { files(session) }
        uri == "/api/fs" -> guarded(session) { filesystem(session) }
        uri == "/thumbnail" -> guarded(session) { thumbnail(session) }
        uri == "/download" || uri == "/stream" -> guarded(session) { download(session) }
        uri == "/download-file" -> guarded(session) { download(session) }
        uri == "/download-folder" -> guarded(session) { downloadFolder(session) }
        uri == "/download-zip" -> guarded(session) { downloadSelection(session) }
        uri.startsWith("/video/") -> asset("index.html", "text/html")
        uri == "/upload" -> guarded(session) { upload(session) }
        uri == "/api/upload-status" -> guarded(session) { uploadStatus(session) }
        uri == "/api/events" -> guarded(session) { web -> events(web) }
        uri == "/api/push-accept" -> guarded(session) { web -> pushAccept(session, web) }
        uri == "/api/push-dismiss" -> guarded(session) { web -> pushDismiss(session, web) }
        uri == "/push-download" -> guarded(session) { web -> pushDownload(session, web) }
        else -> noStore(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON, """{"error":"not found"}"""))
    }

    // ----- Consent and tokens (§7.1, §17.3, A5) -----------------------------

    /**
     * The handshake. A new browser gets `pending` and nothing else; the phone
     * raises §6.16b's dialog; on accept the token is issued here.
     */
    private fun hello(http: IHTTPSession): Response {
        val agent = http.headers["user-agent"]?.let(::shortAgent) ?: "Browser"
        val address = http.headers["http-client-ip"] ?: http.headers["remote-addr"] ?: "unknown"
        val existingToken = tokenOf(http)
        sessions.authorise(existingToken)?.let { live ->
            return json(
                JSONObject()
                    .put("state", "accepted")
                    .put("token", live.token)
                    .put("sessionId", live.id),
            )
        }

        val session = sessions.open(agent, address)
        return when (session.state) {
            WebSessions.State.ACCEPTED -> json(
                JSONObject().put("state", "accepted").put("token", session.token).put("sessionId", session.id),
            )
            WebSessions.State.REJECTED -> json(JSONObject().put("state", "rejected"))
            WebSessions.State.PENDING -> {
                // Nothing — no listing, no thumbnail, no byte — crosses before
                // Accept (§6.16, §17.2). The browser polls and waits.
                val accepted = runBlocking { onConsentNeeded(session) }
                val answered = if (accepted) sessions.accept(session.id) else sessions.reject(session.id)
                when (answered?.state) {
                    WebSessions.State.ACCEPTED -> json(
                        JSONObject()
                            .put("state", "accepted")
                            .put("token", answered.token)
                            .put("sessionId", answered.id),
                    )
                    WebSessions.State.REJECTED -> json(JSONObject().put("state", "rejected"))
                    else -> json(JSONObject().put("state", "pending"))
                }
            }
        }
    }

    /** The single gate: a request without a valid token is refused with 401. */
    private fun guarded(http: IHTTPSession, body: (WebSessions.Session) -> Response): Response {
        val session = sessions.authorise(tokenOf(http))
            ?: return noStore(
                newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_JSON,
                    """{"error":"unauthorised"}""",
                ),
            )
        return body(session)
    }

    private fun tokenOf(http: IHTTPSession): String? =
        http.headers["x-morsecode-token"] ?: http.parameters["token"]?.firstOrNull()

    // ----- Data endpoints ---------------------------------------------------

    private fun info(): Response = json(
        JSONObject()
            .put("device", android.os.Build.MODEL ?: Ids.APP_NAME)
            .put("model", android.os.Build.MODEL ?: "")
            .put("os", "Android ${android.os.Build.VERSION.RELEASE}")
            .put("app", Ids.APP_NAME)
            // §7.3: the browser follows the phone's theme and accent.
            .put("accent", app.morsecode.android.di.AppServices.prefs.accent.value.key)
            .put("dark", isDarkTheme()),
    )

    private fun counts(): Response {
        val counts = runBlocking { library.counts() }
        val json = JSONObject()
        for (count in counts) json.put(count.category.name.lowercase(), count.count)
        val stats = storage()
        json.put("storageUsed", stats.first)
        json.put("storageTotal", stats.second)
        return json(json)
    }

    private fun files(http: IHTTPSession): Response {
        val category = http.parameters["category"]?.firstOrNull()?.uppercase() ?: "PHOTOS"
        val offset = http.parameters["cursor"]?.firstOrNull()?.toIntOrNull() ?: 0
        val pageSize = http.parameters["pageSize"]?.firstOrNull()?.toIntOrNull() ?: DEFAULT_PAGE
        val folder = http.parameters["folder"]?.firstOrNull()
        val search = http.parameters["search"]?.firstOrNull()?.lowercase()

        val mediaCategory = runCatching { MediaCategory.valueOf(category) }.getOrNull()
            ?: MediaCategory.PHOTOS
        val page = runBlocking { library.page(mediaCategory, SortOrder(), offset, pageSize) }
        val filtered = page.items
            .filter { folder == null || it.path?.contains("/$folder/") == true }
            .filter { search == null || it.name.lowercase().contains(search) }

        val array = JSONArray()
        for (item in filtered) array.put(itemJson(item))
        return json(
            JSONObject()
                .put("items", array)
                .put("cursor", offset + page.items.size)
                .put("hasMore", page.hasMore),
        )
    }

    /** §7.5's folder panels come from real bucket names, never a URI authority. */
    private fun itemJson(item: MediaItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("name", item.name)
        .put("size", item.sizeBytes)
        // §7.10: date-taken when present, else date-modified — in millis.
        .put("date", item.dateMillis)
        .put("mime", item.mimeType ?: "")
        .put("type", item.type.name.lowercase())
        .put("duration", item.durationMillis)
        .put("artist", item.artist ?: "")
        .put("path", item.path ?: "")
        .put("folder", item.path?.let { File(it).parentFile?.name } ?: "")

    private fun filesystem(http: IHTTPSession): Response {
        val path = http.parameters["path"]?.firstOrNull()
            ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        val listing = runBlocking { library.list(path) }
        val entries = JSONArray()
        var denied = false
        when (listing) {
            is app.morsecode.android.core.model.DirectoryListing.Ok ->
                for (entry in listing.entries) {
                    entries.put(
                        JSONObject()
                            .put("name", entry.name)
                            .put("path", entry.path)
                            .put("dir", entry.isDirectory)
                            // §7.5: the Size column never shows a dash.
                            .put(
                                "size",
                                if (entry.isDirectory) ZipUtil.recursiveSize(File(entry.path)) else entry.sizeBytes,
                            )
                            .put("modified", entry.dateMillis)
                            .put("type", entry.type.name.lowercase()),
                    )
                }
            // §12.2: denial is its own answer, never an empty folder.
            is app.morsecode.android.core.model.DirectoryListing.AccessDenied -> denied = true
            is app.morsecode.android.core.model.DirectoryListing.Missing -> denied = false
        }
        val crumbs = JSONArray()
        for (segment in app.morsecode.android.core.media.PathSegments.of(path)) {
            crumbs.put(JSONObject().put("label", segment.label).put("path", segment.path))
        }
        return json(
            JSONObject()
                .put("path", path)
                .put("denied", denied)
                .put("breadcrumb", crumbs)
                .put("entries", entries),
        )
    }

    private fun thumbnail(http: IHTTPSession): Response {
        val path = http.parameters["path"]?.firstOrNull()
            ?: return badRequest("path required")
        val file = File(path)
        if (!file.exists()) return notFound()
        // A sized, cached copy — a grid of 140 px tiles must not move the
        // full-resolution originals (§7.2, §20.10).
        val thumb = thumbnails.thumbnail(file)
        return if (thumb != null) {
            streamFile(http, thumb, "image/jpeg")
        } else {
            // No decode (a video, an unsupported format): the original is the
            // honest answer, never a blank tile (§6.19).
            streamFile(http, file, mimeOf(file))
        }
    }

    /** §7.2: /download streams a file and MUST support Range (A32, §7.6). */
    private fun download(http: IHTTPSession): Response {
        val path = http.parameters["path"]?.firstOrNull() ?: return badRequest("path required")
        val file = File(path)
        if (!file.exists()) return notFound()
        return streamFile(http, file, mimeOf(file))
    }

    private fun streamFile(
        http: IHTTPSession,
        file: File,
        mime: String,
        onFullyRead: ((start: Long, length: Long) -> Unit)? = null,
    ): Response {
        val length = file.length()
        val rangeHeader = http.headers["range"]
        val span = HttpRange.parse(rangeHeader, length)

        if (span == null && HttpRange.isUnsatisfiable(rangeHeader, length)) {
            val response = newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                MIME_JSON,
                """{"error":"range"}""",
            )
            response.addHeader("Content-Range", "bytes */$length")
            return noStore(response)
        }

        return if (span == null) {
            val raw = FileInputStream(file)
            val stream = trackRead(raw, start = 0, length = length, onFullyRead = onFullyRead)
            val response = newFixedLengthResponse(Response.Status.OK, mime, stream, length)
            response.addHeader("Accept-Ranges", "bytes")
            noStore(response)
        } else {
            val raw = FileInputStream(file)
            raw.skip(span.start)
            val stream = trackRead(raw, span.start, span.length, onFullyRead)
            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mime,
                stream,
                span.length,
            )
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Range", span.contentRange)
            noStore(response)
        }
    }

    /**
     * Fires [onFullyRead] only after the response consumer read exactly the
     * advertised span. Closing early deliberately does nothing: that browser
     * did not receive the rest of the offered file.
     */
    private fun trackRead(
        source: InputStream,
        start: Long,
        length: Long,
        onFullyRead: ((start: Long, length: Long) -> Unit)?,
    ): InputStream {
        if (onFullyRead == null) return source
        return object : java.io.FilterInputStream(source) {
            private var readBytes = 0L
            private var reported = false

            override fun read(): Int {
                val value = super.read()
                if (value >= 0) consumed(1) else finished()
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                val value = super.read(buffer, offset, count)
                if (value > 0) consumed(value) else if (value < 0) finished()
                return value
            }

            override fun close() {
                finished()
                super.close()
            }

            private fun consumed(count: Int) {
                readBytes += count
                if (readBytes >= length) finished()
            }

            private fun finished() {
                if (!reported && readBytes >= length) {
                    reported = true
                    onFullyRead(start, length)
                }
            }
        }
    }

    /** §7.2: a streamed ZIP64 of a folder, built through a pipe as it is sent. */
    private fun downloadFolder(http: IHTTPSession): Response {
        val path = http.parameters["path"]?.firstOrNull() ?: return badRequest("path required")
        val folder = File(path)
        if (!folder.isDirectory) return notFound()
        return zipResponse(ZipUtil.archiveNameFor(folder.name), ZipUtil.sourcesOf(folder))
    }

    /** §7.2: a streamed ZIP64 of a selection. */
    private fun downloadSelection(http: IHTTPSession): Response {
        val paths = http.parameters["paths"]?.firstOrNull()?.split('\n')?.filter { it.isNotBlank() }
            ?: return badRequest("paths required")
        val sources = ArrayList<ZipUtil.Source>()
        for (path in paths) {
            val file = File(path.trim())
            if (file.isDirectory) {
                sources.addAll(ZipUtil.sourcesOf(file))
            } else if (file.exists()) {
                sources.add(ZipUtil.Source(file.name, file.length()) { file.inputStream() })
            }
        }
        if (sources.isEmpty()) return notFound()
        return zipResponse(ZipUtil.archiveNameFor(Ids.APP_NAME), sources)
    }

    private fun zipResponse(name: String, sources: List<ZipUtil.Source>): Response {
        // Never assembled first: the pipe is written on a worker thread while
        // NanoHTTPD reads the other end (§7.2, §20.10).
        val sink = PipedOutputStream()
        val source = PipedInputStream(sink, PIPE_BUFFER)
        Thread({
            try {
                sink.use { ZipUtil.writeTo(it, sources) }
            } catch (broken: Exception) {
                logStore?.w("Zip aborted: ${broken.javaClass.simpleName}")
            }
        }, "morsecode-zip").start()

        val response = newChunkedResponse(Response.Status.OK, "application/zip", source)
        response.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        return noStore(response)
    }

    // ----- Uploads (§7.7) ---------------------------------------------------

    /**
     * Chunked upload. §7.7 slices client-side into 4 MB chunks with a file id
     * and a chunk index, so "resume" means asking `/api/upload-status` for the
     * last index — never restarting from zero. The response reports the ACTUAL
     * bytes written and the saved path, never a hardcoded success (§7.2).
     */
    private fun upload(http: IHTTPSession): Response {
        val id = http.parameters["id"]?.firstOrNull() ?: return badRequest("id required")
        val name = http.parameters["name"]?.firstOrNull() ?: return badRequest("name required")
        val index = http.parameters["index"]?.firstOrNull()?.toIntOrNull() ?: 0
        val last = http.parameters["last"]?.firstOrNull()?.toBoolean() ?: false

        val state = uploads.getOrPut(id) {
            val dir = destinations.ensureDefaultDirectory()
            UploadState(name, File(dir, destinations.partNameFor(name)))
        }

        val body = HashMap<String, String>()
        http.parseBody(body)
        val temp = body["content"] ?: body.values.firstOrNull()
        val chunk = temp?.let { File(it) }
        val written = if (chunk != null && chunk.exists()) {
            java.io.FileOutputStream(state.target, true).use { out ->
                chunk.inputStream().use { input -> input.copyTo(out) }
            }
            chunk.length()
        } else {
            0L
        }
        state.receivedChunks = index + 1
        state.bytesWritten += written

        if (last) {
            val finalFile = File(state.target.parentFile, state.name)
            state.target.renameTo(finalFile)
            uploads.remove(id)
            return json(
                JSONObject()
                    .put("ok", true)
                    .put("bytes", state.bytesWritten)
                    .put("path", finalFile.absolutePath),
            )
        }
        return json(
            JSONObject()
                .put("ok", true)
                .put("bytes", state.bytesWritten)
                .put("nextIndex", state.receivedChunks),
        )
    }

    /**
     * §7.7: pause means "stop sending chunks" and resume means "ask
     * /api/upload-status for the last index and continue" — the browser drives
     * it, so the server's job is only to report the truth about what landed.
     */
    private fun uploadStatus(http: IHTTPSession): Response {
        val id = http.parameters["id"]?.firstOrNull() ?: return badRequest("id required")
        val state = uploads[id]
        return json(
            JSONObject()
                .put("id", id)
                .put("nextIndex", state?.receivedChunks ?: 0)
                .put("bytes", state?.bytesWritten ?: 0),
        )
    }

    /**
     * §7.2 / §7.8 SSE: one held, chunked stream per accepted browser. The
     * initial snapshot reaches the client immediately and later offer changes
     * are pushed by [PushOffers], without a reconnect timer masquerading as
     * Server-Sent Events.
     */
    private fun events(session: WebSessions.Session): Response {
        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", sse.open(session.id))
        response.addHeader("Connection", "keep-alive")
        response.addHeader("X-Accel-Buffering", "no")
        return noStore(response)
    }

    private fun eventsPayload(sessionId: String): JSONObject {
        val pending = JSONArray()
        for (offer in offers.pendingFor(sessionId)) pending.put(offer.json())
        return JSONObject()
            .put("session", sessionId)
            .put("offers", pending)
            .put("counts", JSONObject().put("stale", false))
    }

    /** §7.8: the browser pressed [Download] on its own incoming card. */
    private fun pushAccept(http: IHTTPSession, web: WebSessions.Session): Response {
        val id = http.parameters["offer"]?.firstOrNull() ?: return badRequest("offer required")
        val pending = offers.get(id) ?: return notFound()
        if (pending.sessionId != web.id) return notFound()
        val offer = offers.accepted(id) ?: return notFound()
        return json(offer.json().put("url", "/push-download?offer=$id"))
    }

    /** §7.8: [Dismiss] cancels the offer — the phone sees Cancelled, not Failed. */
    private fun pushDismiss(http: IHTTPSession, web: WebSessions.Session): Response {
        val id = http.parameters["offer"]?.firstOrNull() ?: return badRequest("offer required")
        val pending = offers.get(id) ?: return notFound()
        if (pending.sessionId != web.id) return notFound()
        offers.dismissed(id) ?: return notFound()
        return json(JSONObject().put("ok", true))
    }

    /**
     * §7.8: "accepting downloads through the same Range-capable endpoint".
     * The stream wrapper reports only bytes NanoHTTPD really reads. An offer is
     * complete when those reported spans cover the full file, so a partial
     * ranged download cannot turn into a false Completed row.
     */
    private fun pushDownload(http: IHTTPSession, web: WebSessions.Session): Response {
        val id = http.parameters["offer"]?.firstOrNull() ?: return badRequest("offer required")
        val offer = offers.get(id) ?: return notFound()
        if (offer.sessionId != web.id || offer.state != PushOffers.State.DOWNLOADING) return notFound()
        val file = File(offer.path)
        if (!file.exists()) return notFound()
        // Empty files have complete [0, 0) coverage as soon as this response
        // is created; there is no body read from which the wrapper can infer it.
        if (file.length() == 0L) offers.delivered(id)

        val response = streamFile(http, file, mimeOf(file)) { start, length ->
            offers.downloadedRange(id, start, length)
        }
        response.addHeader("Content-Disposition", "attachment; filename=\"${offer.name}\"")
        return response
    }

    // ----- Plumbing ---------------------------------------------------------

    /** Called only by the explicit WebShare Stop path (§7.1 INV-4). */
    fun closeEventStreams() {
        sse.closeAll()
    }

    private fun asset(name: String, mime: String): Response {
        val bytes = assets.read(name) ?: return notFound()
        return noStore(
            newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()),
        )
    }

    private fun json(body: JSONObject): Response =
        noStore(newFixedLengthResponse(Response.Status.OK, MIME_JSON, body.toString()))

    private fun badRequest(message: String): Response = noStore(
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, """{"error":"$message"}"""),
    )

    private fun notFound(): Response = noStore(
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON, """{"error":"not found"}"""),
    )

    /** §7.1: EVERY response carries Cache-Control: no-store. */
    private fun noStore(response: Response): Response {
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Pragma", "no-cache")
        return response
    }

    private fun mimeOf(file: File): String = when (FileTypes.typeOf(file.name)) {
        app.morsecode.android.core.model.FileType.IMAGE -> "image/*"
        app.morsecode.android.core.model.FileType.VIDEO -> "video/mp4"
        app.morsecode.android.core.model.FileType.AUDIO -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private fun storage(): Pair<Long, Long> = try {
        val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        (total - free) to total
    } catch (e: Exception) {
        0L to 0L
    }

    private fun isDarkTheme(): Boolean {
        val mode = appContext.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun shortAgent(raw: String): String = when {
        raw.contains("Edg/") -> "Edge"
        raw.contains("Chrome") -> "Chrome"
        raw.contains("Firefox") -> "Firefox"
        raw.contains("Safari") -> "Safari"
        else -> "Browser"
    }

    private companion object {
        const val MIME_JSON = "application/json"
        const val DEFAULT_PAGE = 200
        const val PIPE_BUFFER = 256 * 1024
    }
}
