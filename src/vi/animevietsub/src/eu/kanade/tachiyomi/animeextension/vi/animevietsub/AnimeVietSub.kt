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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeVietSub : AnimeHttpSource() {

    override val name = "AnimeVietSub"

    override val baseUrl = "https://animevietsub.be"

    override val lang = "vi"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // Cache for decrypted m3u8 playlists served via interceptor
    private val m3u8Cache = ConcurrentHashMap<String, String>()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(::m3u8Interceptor)
        .build()

    private fun m3u8Interceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Check if this is a request for a cached m3u8 playlist
        val prefix = "$baseUrl/internal-m3u8/"
        if (url.startsWith(prefix)) {
            val key = url.removePrefix(prefix)
            val content = m3u8Cache.remove(key)
            if (content != null) {
                return Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(content.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            }
        }
        return chain.proceed(request)
    }

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

        val videos = mutableListOf<Video>()
        val serverButtons = epDoc.select("a.btn3dsv[data-href]")
        val errors = mutableListOf<String>()

        for (btn in serverButtons) {
            val dataHref = btn.attr("data-href")
            val serverName = btn.text().trim()
            val play = btn.attr("data-play")

            if (dataHref.isBlank()) continue

            try {
                val formBody = FormBody.Builder()
                    .add("link", dataHref)
                    .add("id", btn.attr("data-id"))
                    .add("play", play)
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
                    errors.add("$serverName: empty response")
                    continue
                }

                val jsonObj = json.parseToJsonElement(playerBody).jsonObject
                val success = jsonObj["success"]?.jsonPrimitive?.content
                if (success != "1") {
                    errors.add("$serverName: success=$success")
                    continue
                }

                val playTech = jsonObj["playTech"]?.jsonPrimitive?.content ?: ""
                val linkElement = jsonObj["link"]
                if (linkElement == null || linkElement.toString() == "null") {
                    errors.add("$serverName: link=null, playTech=$playTech")
                    continue
                }

                if (playTech == "api") {
                    val linkArray = linkElement.jsonArray
                    for (item in linkArray) {
                        val file = item.jsonObject["file"]?.jsonPrimitive?.content ?: continue
                        val m3u8Content = decryptFile(file)
                        if (m3u8Content == null) {
                            errors.add("$serverName: decrypt failed")
                            continue
                        }
                        videos.addAll(extractVideosFromM3u8(m3u8Content, serverName))
                    }
                } else if (playTech == "embed") {
                    val embedUrl = linkElement.jsonPrimitive.content
                    if (embedUrl.isNotBlank()) {
                        extractVideosFromEmbed(embedUrl, serverName)?.let { videos.addAll(it) }
                            ?: errors.add("$serverName: no m3u8 in embed")
                    }
                }
            } catch (e: Exception) {
                errors.add("$serverName: ${e.message}")
            }
        }

        if (videos.isEmpty() && errors.isNotEmpty()) {
            throw Exception("No streams: ${errors.joinToString("; ")}")
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
            inflateRaw(decrypted)
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

        // Check if it's a master playlist with multiple qualities
        val streamRegex = Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+x\d+).*?\n(https?://.+)""")
        val streams = streamRegex.findAll(m3u8Content).toList()

        if (streams.isNotEmpty()) {
            for (stream in streams) {
                val resolution = stream.groupValues[1]
                val url = stream.groupValues[2].trim()
                videos.add(Video(url, "$serverName - $resolution", url))
            }
        } else {
            // Single quality media playlist — serve via interceptor
            val key = "${serverName}-${System.currentTimeMillis()}.m3u8"
            m3u8Cache[key] = m3u8Content
            val fakeUrl = "$baseUrl/internal-m3u8/$key"
            videos.add(Video(fakeUrl, "$serverName - HLS", fakeUrl))
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
        private val AES_KEY = byteArrayOf(
            0x02, 0x56, 0x94.toByte(), 0x68, 0x64, 0xdc.toByte(), 0xf9.toByte(), 0x96.toByte(),
            0x99.toByte(), 0xa6.toByte(), 0xe2.toByte(), 0x16, 0xfe.toByte(), 0x42, 0x31, 0x4b,
            0xe7.toByte(), 0x06, 0xd7.toByte(), 0x44, 0xa0.toByte(), 0x77, 0x0f, 0x94.toByte(),
            0xc7.toByte(), 0xb0.toByte(), 0x2f, 0xd8.toByte(), 0xee.toByte(), 0xfb.toByte(), 0x31, 0xf8.toByte(),
        )
    }
}
