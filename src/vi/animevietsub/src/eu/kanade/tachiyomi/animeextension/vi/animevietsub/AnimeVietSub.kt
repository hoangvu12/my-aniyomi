package eu.kanade.tachiyomi.animeextension.vi.animevietsub

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class AnimeVietSub : AnimeHttpSource() {

    override val name = "AnimeVietSub"

    override val baseUrl = "https://animevietsub.be"

    override val lang = "vi"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/phim-moi/trang-$page.html", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.TPost").map { element ->
            SAnime.create().apply {
                val linkEl = element.selectFirst("a[href]")!!
                setUrlWithoutDomain(linkEl.attr("href"))
                title = element.selectFirst(".Title")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        return AnimesPage(animes, animes.size >= 20)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/phim-moi/trang-$page.html", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "+")
        return GET("$baseUrl/tim-kiem/$encodedQuery/trang-$page.html", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.Title")?.text() ?: ""
            thumbnail_url = document.selectFirst(".Image img")?.attr("abs:src")
            description = buildString {
                document.selectFirst("h2.SubTitle")?.text()?.let { alt ->
                    if (alt.isNotBlank()) appendLine("Alternative: $alt")
                }
                document.selectFirst(".Description")?.text()?.let { desc ->
                    append(desc)
                }
            }
            genre = document.select(".mvici-right a[href*=the-loai]")
                .joinToString { it.text() }
            status = parseStatus(document.selectFirst(".mvici-right")?.text() ?: "")
        }
    }

    private fun parseStatus(text: String): Int {
        return when {
            text.contains("Hoàn Tất", ignoreCase = true) -> SAnime.COMPLETED
            text.contains("Đang phát", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes =============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val detailDoc = client.newCall(
            GET(baseUrl + anime.url, headers),
        ).awaitSuccess().asJsoup()

        val filmId = Regex("""-a(\d+)/?$""").find(anime.url)?.groupValues?.get(1)

        val episodes = detailDoc.select("ul.list-episode a.btn-episode").map { el ->
            SEpisode.create().apply {
                setUrlWithoutDomain(el.attr("href"))
                name = el.attr("title").ifBlank { "Tập ${el.text().trim()}" }
                episode_number = el.text().trim().toFloatOrNull() ?: 0f
            }
        }.toMutableList()

        if (episodes.isEmpty()) {
            val firstEpLink = detailDoc.selectFirst("a[href*=tap-], a.btn-episode")
                ?: detailDoc.selectFirst("a[href\$=.html][href*=${anime.url.trimEnd('/')}]")

            if (firstEpLink != null) {
                val epDoc = client.newCall(
                    GET(baseUrl + firstEpLink.attr("href").removePrefix(baseUrl), headers),
                ).awaitSuccess().asJsoup()

                episodes.addAll(
                    epDoc.select("ul.list-episode a.btn-episode").map { el ->
                        SEpisode.create().apply {
                            setUrlWithoutDomain(el.attr("href"))
                            name = el.attr("title").ifBlank { "Tập ${el.text().trim()}" }
                            episode_number = el.text().trim().toFloatOrNull() ?: 0f
                        }
                    },
                )
            }

            if (episodes.isEmpty() && filmId != null) {
                val ajaxResp = client.newCall(
                    GET("$baseUrl/ajax/get_episode?filmId=$filmId&episodeId=0", headers),
                ).awaitSuccess()
                val rssDoc = Jsoup.parse(ajaxResp.body.string(), "", Parser.xmlParser())
                episodes.addAll(
                    rssDoc.select("item").map { item ->
                        SEpisode.create().apply {
                            val link = item.selectFirst("link")?.text() ?: ""
                            setUrlWithoutDomain(link.removePrefix(baseUrl))
                            name = item.selectFirst("title")?.text() ?: ""
                            val epNum = Regex("""(\d+)""").find(name)?.value
                            episode_number = epNum?.toFloatOrNull() ?: 0f
                        }
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================ Video URLs =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epDoc = client.newCall(
            GET(baseUrl + episode.url, headers),
        ).awaitSuccess().asJsoup()

        val html = epDoc.html()
        val videos = mutableListOf<Video>()
        val errors = mutableListOf<String>()

        // Extract the hash from AnimeVsub('hash', filmInfo.filmID) in inline script
        val hash = Regex("""AnimeVsub\('([^']+)'""").find(html)?.groupValues?.get(1)
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(html)?.groupValues?.get(1)

        if (hash == null || filmId == null) {
            return listOf(Video(
                "$baseUrl/debug",
                "No video data found (hash=${hash != null}, filmId=${filmId != null})",
                "$baseUrl/debug",
            ))
        }

        // POST the hash to /ajax/player to get the encrypted video data
        try {
            val formBody = FormBody.Builder()
                .add("link", hash)
                .add("id", filmId)
                .add("play", "api")
                .add("backuplinks", "1")
                .build()

            val playerHeaders = headersBuilder()
                .set("Referer", baseUrl + episode.url)
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Accept", "*/*")
                .build()

            val playerResp = client.newCall(
                POST("$baseUrl/ajax/player", headers = playerHeaders, body = formBody),
            ).awaitSuccess()

            val playerBody = playerResp.body.string()
            if (playerBody.isBlank()) {
                errors.add("empty player response")
            } else {
                val jsonObj = json.parseToJsonElement(playerBody).jsonObject
                val success = jsonObj["success"]?.jsonPrimitive?.content
                if (success != "1") {
                    errors.add("success=$success")
                } else {
                    val playTech = jsonObj["playTech"]?.jsonPrimitive?.content ?: ""
                    val linkElement = jsonObj["link"]
                    if (linkElement == null || linkElement.toString() == "null") {
                        errors.add("link=null, playTech=$playTech")
                    } else if (playTech == "api") {
                        val linkArray = linkElement.jsonArray
                        for (item in linkArray) {
                            val file = item.jsonObject["file"]?.jsonPrimitive?.content ?: continue
                            val m3u8Content = decryptFile(file)
                            if (m3u8Content == null) {
                                errors.add("decrypt failed")
                                continue
                            }
                            videos.addAll(extractVideosFromM3u8(m3u8Content, "AnimeVietSub"))
                        }
                    } else if (playTech == "embed") {
                        val embedUrl = linkElement.jsonPrimitive.content
                        if (embedUrl.isNotBlank()) {
                            extractVideosFromEmbed(embedUrl, "AnimeVietSub")?.let { videos.addAll(it) }
                                ?: errors.add("no m3u8 in embed")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errors.add(e.message ?: "unknown error")
        }

        if (videos.isEmpty()) {
            val errMsg = if (errors.isNotEmpty()) errors.joinToString("; ") else "no videos found"
            return listOf(Video("$baseUrl/debug", "DEBUG: $errMsg", "$baseUrl/debug"))
        }

        return videos
    }

    private fun decryptFile(file: String): String? {
        return try {
            val data = Base64.decode(file, Base64.DEFAULT)
            val iv = data.copyOfRange(0, 16)
            val ciphertext = data.copyOfRange(16, data.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            val inflated = inflateRaw(decrypted)
            // The inflated result is a JSON string (wrapped in quotes with escaped newlines)
            if (inflated.startsWith("\"")) {
                json.parseToJsonElement(inflated).jsonPrimitive.content
            } else {
                inflated
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun inflateRaw(data: ByteArray): String {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            output.write(buffer, 0, count)
        }
        inflater.end()
        return output.toString("UTF-8")
    }

    private fun extractVideosFromM3u8(m3u8Content: String, serverName: String): List<Video> {
        val videos = mutableListOf<Video>()

        if (!m3u8Content.contains("#EXTM3U")) {
            // Some responses can include a direct m3u8 URL wrapped in other text.
            val directM3u8 = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                .find(m3u8Content)
                ?.value
            if (directM3u8 != null) {
                return listOf(Video(directM3u8, "$serverName - HLS", directM3u8))
            }
        }

        // Check if it's a master playlist with multiple qualities
        val streamRegex = Regex("""#EXT-X-STREAM-INF:[^\r\n]*RESOLUTION=(\d+x\d+)[^\r\n]*\r?\n([^\r\n]+)""")
        val streams = streamRegex.findAll(m3u8Content).toList()

        if (streams.isNotEmpty()) {
            for (stream in streams) {
                val resolution = stream.groupValues[1]
                val url = stream.groupValues[2].trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    videos.add(Video(url, "$serverName - $resolution", url))
                }
            }
        }

        if (videos.isEmpty()) {
            // Single quality media playlist — serve via localhost server.
            val localUrl = cacheM3u8AndBuildLocalUrl(m3u8Content)
            if (localUrl != null) {
                videos.add(Video(localUrl, "$serverName - HLS", localUrl))
            }
        }

        return videos
    }

    private suspend fun extractVideosFromEmbed(embedUrl: String, serverName: String): List<Video>? {
        return try {
            val iframeDoc = client.newCall(
                GET(embedUrl, headers),
            ).awaitSuccess().asJsoup()
            val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                .find(iframeDoc.html())
            if (m3u8 != null) {
                listOf(Video(m3u8.value, "$serverName - HLS", m3u8.value))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ============================== Helpers ==============================

    private fun Response.asJsoup(): Document {
        return Jsoup.parse(body.string(), request.url.toString())
    }

    companion object {
        private val localM3u8Cache = ConcurrentHashMap<String, CachedPlaylist>()
        private val localServerLock = Any()
        @Volatile private var localServerSocket: ServerSocket? = null
        @Volatile private var localServerPort: Int = -1
        @Volatile private var localServerThread: Thread? = null

        private data class CachedPlaylist(
            val content: String,
            val createdAt: Long = System.currentTimeMillis(),
        )

        private fun cacheM3u8AndBuildLocalUrl(content: String): String? {
            val port = ensureLocalServerStarted() ?: return null
            val key = "${System.currentTimeMillis()}-${UUID.randomUUID()}.m3u8"
            localM3u8Cache[key] = CachedPlaylist(content)
            pruneLocalM3u8Cache()
            return "http://127.0.0.1:$port/m3u8/$key"
        }

        private fun ensureLocalServerStarted(): Int? {
            synchronized(localServerLock) {
                val socket = localServerSocket
                if (socket != null && !socket.isClosed && localServerPort > 0) {
                    return localServerPort
                }

                return try {
                    val server = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                    }
                    localServerSocket = server
                    localServerPort = server.localPort
                    localServerThread = thread(
                        name = "AnimeVietSub-M3U8Server",
                        isDaemon = true,
                    ) {
                        runLocalServer(server)
                    }
                    localServerPort
                } catch (_: Exception) {
                    null
                }
            }
        }

        private fun runLocalServer(server: ServerSocket) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    thread(
                        name = "AnimeVietSub-M3U8Client",
                        isDaemon = true,
                    ) {
                        handleLocalRequest(socket)
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    // Keep serving after transient failures.
                }
            }
        }

        private fun handleLocalRequest(socket: Socket) {
            socket.use { client ->
                client.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                val output = client.getOutputStream()

                val requestLine = reader.readLine() ?: return
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeHttpResponse(output, 400, "Bad Request", "Bad request".toByteArray())
                    return
                }

                val method = parts[0]
                val target = parts[1].substringBefore('?')
                if (method != "GET" && method != "HEAD") {
                    writeHttpResponse(output, 405, "Method Not Allowed", ByteArray(0))
                    return
                }

                val key = target.removePrefix("/m3u8/")
                if (!target.startsWith("/m3u8/") || key.isBlank()) {
                    writeHttpResponse(output, 404, "Not Found", "Not found".toByteArray())
                    return
                }

                val playlist = localM3u8Cache[key]?.content
                if (playlist == null) {
                    writeHttpResponse(output, 404, "Not Found", "Playlist expired".toByteArray())
                    return
                }

                val body = playlist.toByteArray(Charsets.UTF_8)
                writeHttpResponse(
                    output = output,
                    code = 200,
                    message = "OK",
                    body = body,
                    contentType = "application/vnd.apple.mpegurl; charset=utf-8",
                    writeBody = method == "GET",
                )
            }
        }

        private fun writeHttpResponse(
            output: OutputStream,
            code: Int,
            message: String,
            body: ByteArray,
            contentType: String = "text/plain; charset=utf-8",
            writeBody: Boolean = true,
        ) {
            val headers = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(Charsets.US_ASCII))
            if (writeBody && body.isNotEmpty()) output.write(body)
            output.flush()
        }

        private fun pruneLocalM3u8Cache() {
            val now = System.currentTimeMillis()
            val expiredKeys = localM3u8Cache.entries
                .filter { now - it.value.createdAt > M3U8_CACHE_TTL_MS }
                .map { it.key }
            expiredKeys.forEach(localM3u8Cache::remove)

            if (localM3u8Cache.size <= M3U8_CACHE_MAX_SIZE) return

            val keysToRemove = localM3u8Cache.entries
                .sortedBy { it.value.createdAt }
                .take(localM3u8Cache.size - M3U8_CACHE_MAX_SIZE)
                .map { it.key }
            keysToRemove.forEach(localM3u8Cache::remove)
        }

        private const val M3U8_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val M3U8_CACHE_MAX_SIZE = 32

        private val AES_KEY = byteArrayOf(
            0x02, 0x56, 0x94.toByte(), 0x68, 0x64, 0xdc.toByte(), 0xf9.toByte(), 0x96.toByte(),
            0x99.toByte(), 0xa6.toByte(), 0xe2.toByte(), 0x16, 0xfe.toByte(), 0x42, 0x31, 0x4b,
            0xe7.toByte(), 0x06, 0xd7.toByte(), 0x44, 0xa0.toByte(), 0x77, 0x0f, 0x94.toByte(),
            0xc7.toByte(), 0xb0.toByte(), 0x2f, 0xd8.toByte(), 0xee.toByte(), 0xfb.toByte(), 0x31, 0xf8.toByte(),
        )
    }
}
