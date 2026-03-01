package eu.kanade.tachiyomi.animeextension.vi.animehay

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class AnimeHay : AnimeHttpSource() {

    override val name = "AnimeHay"

    override val baseUrl = "https://animehay.vin"

    // Dynamically resolve actual domain from animehay.tv redirect page
    private val currentBaseUrl: String by lazy {
        runCatching {
            val html = OkHttpClient().newCall(GET("https://animehay.tv")).execute().use { it.body.string() }
            val doc = Jsoup.parse(html)
            doc.selectFirst(".bt-link")?.attr("href")?.trimEnd('/') ?: baseUrl
        }.getOrDefault(baseUrl)
    }

    override val lang = "vi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$currentBaseUrl/")

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$currentBaseUrl/phim-moi-cap-nhap/trang-$page.html", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return parseAnimesPage(document, response.request.url.toString())
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
        return GET("$currentBaseUrl/tim-kiem/$encoded/trang-$page.html", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return parseAnimesPage(document, response.request.url.toString())
    }

    private fun parseAnimesPage(document: Document, requestUrl: String): AnimesPage {
        val animeRegex = Regex("""/thong-tin-phim/[a-z0-9\-]+-\d+\.html""", RegexOption.IGNORE_CASE)
        val animesByUrl = linkedMapOf<String, SAnime>()

        document.select("a[href]").forEach { link ->
            val href = normalizeToRelativeUrl(link.attr("href")) ?: return@forEach
            val animeUrl = animeRegex.find(href)?.value ?: return@forEach

            val title = link.attr("title").trim().ifBlank {
                link.text().trim().ifBlank {
                    link.selectFirst("img")?.attr("alt")
                        ?.removePrefix("Phim ")
                        ?.trim()
                        .orEmpty()
                }
            }

            val thumbnail = link.selectFirst("img")?.attr("abs:src")
                ?.ifBlank { link.selectFirst("img")?.attr("src") }

            animesByUrl.putIfAbsent(
                animeUrl,
                SAnime.create().apply {
                    setUrlWithoutDomain(animeUrl)
                    this.title = title
                    thumbnail_url = toAbsoluteUrl(thumbnail)
                },
            )
        }

        val animes = animesByUrl.values.toList()
        val page = Regex("""/trang-(\d+)\.html""").find(requestUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nextPageToken = "trang-${page + 1}.html"
        val hasNextPage = document.select("a[href]").any { it.attr("href").contains(nextPageToken) } || animes.size >= 20

        return AnimesPage(animes, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val pageText = document.text().replace('\u00A0', ' ')

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""
        val title = rawTitle.removePrefix("Phim ").substringBefore("||").trim().ifBlank { rawTitle }

        val altTitle = Regex(
            """Tên khác\s*(.+?)\s*(?:Thể loại|Trạng thái|Điểm|Phát hành|Thời lượng|Danh sách tập|Nội dung)""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(pageText)?.groupValues?.get(1)?.trim()

        val description = extractDescription(document, pageText)
        val genres = extractGenres(document)
        val statusText = Regex(
            """Trạng thái\s*(.+?)\s*(?:Điểm|Phát hành|Thời lượng|Danh sách tập|Nội dung)""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(pageText)?.groupValues?.get(1).orEmpty()

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = toAbsoluteUrl(
                document.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: document.selectFirst("img[src]")?.attr("abs:src"),
            )
            this.description = buildString {
                if (!altTitle.isNullOrBlank()) appendLine("Alternative: $altTitle")
                append(description)
            }.trim()
            genre = genres
            status = parseStatus(statusText)
        }
    }

    private fun extractDescription(document: Document, pageText: String): String {
        val contentFromBody = Regex(
            """Nội dung\s*(.+?)\s*Bình luận""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(pageText)?.groupValues?.get(1)?.trim()

        return contentFromBody
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: ""
    }

    private fun extractGenres(document: Document): String {
        val h1 = document.selectFirst("h1") ?: return ""
        val scoped = h1.parents().firstOrNull {
            val count = it.select("a[href*=/the-loai/]").size
            count in 1..12
        }

        val genres = scoped
            ?.select("a[href*=/the-loai/]")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() && !it.startsWith("Thể loại", ignoreCase = true) }
            ?.distinct()
            .orEmpty()

        return genres.joinToString()
    }

    private fun parseStatus(statusText: String): Int {
        return when {
            statusText.contains("Hoàn thành", ignoreCase = true) ||
                statusText.contains("Hoàn tất", ignoreCase = true) ||
                statusText.contains("Full", ignoreCase = true) -> SAnime.COMPLETED
            statusText.contains("Đang", ignoreCase = true) ||
                statusText.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET(currentBaseUrl + anime.url, headers)).awaitSuccess().asJsoup()
        val episodeRegex = Regex("""/xem-phim/[a-z0-9\-]+-tap-[^/"']+-\d+\.html""", RegexOption.IGNORE_CASE)
        val episodesByUrl = linkedMapOf<String, SEpisode>()

        document.select("a[href]").forEach { link ->
            val href = normalizeToRelativeUrl(link.attr("href")) ?: return@forEach
            val episodeUrl = episodeRegex.find(href)?.value ?: return@forEach

            val numberFromUrl = Regex("""-tap-(\d+(?:\.\d+)?)-\d+\.html""", RegexOption.IGNORE_CASE)
                .find(episodeUrl)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
            val numberFromName = Regex("""(\d+(?:\.\d+)?)""")
                .find(link.text())
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
            val episodeNumber = numberFromUrl ?: numberFromName ?: 0f

            episodesByUrl.putIfAbsent(
                episodeUrl,
                SEpisode.create().apply {
                    setUrlWithoutDomain(episodeUrl)
                    name = link.text().trim().ifBlank {
                        if (episodeNumber > 0f) "Tập ${episodeNumber.toInt()}" else "Episode"
                    }
                    episode_number = episodeNumber
                },
            )
        }

        if (episodesByUrl.isEmpty()) {
            episodeRegex.findAll(document.html()).forEach { match ->
                val episodeUrl = match.value
                if (episodesByUrl.containsKey(episodeUrl)) return@forEach
                val episodeNumber = Regex("""-tap-(\d+(?:\.\d+)?)-\d+\.html""", RegexOption.IGNORE_CASE)
                    .find(episodeUrl)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
                    ?: 0f
                episodesByUrl[episodeUrl] = SEpisode.create().apply {
                    setUrlWithoutDomain(episodeUrl)
                    name = if (episodeNumber > 0f) "Tập ${episodeNumber.toInt()}" else "Episode"
                    episode_number = episodeNumber
                }
            }
        }

        return episodesByUrl.values.sortedByDescending { it.episode_number }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = currentBaseUrl + episode.url
        val html = client.newCall(GET(episodeUrl, headers)).awaitSuccess().body.string()

        val videosByUrl = linkedMapOf<String, Video>()
        val directM3u8 = collectM3u8Urls(html)
        directM3u8.forEach { url ->
            videosByUrl.putIfAbsent(
                url,
                Video(url, "AnimeHay - HLS", url, buildVideoHeaders(episodeUrl)),
            )
        }

        val serverEmbeds = mutableListOf<Pair<String, String>>()

        Regex("""case\s*'([A-Z0-9]+)'[\s\S]*?src=\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                val server = match.groupValues[1].trim()
                val url = decodeEscapes(match.groupValues[2].trim())
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    serverEmbeds.add(server to url)
                }
            }

        Regex("""https?://(?:ssplay\.net|playhydrax\.com)/[^\s"']+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                val url = decodeEscapes(match.value)
                if (serverEmbeds.none { it.second == url }) {
                    val server = when {
                        url.contains("ssplay.net", ignoreCase = true) -> "SS"
                        url.contains("playhydrax.com", ignoreCase = true) -> "HY"
                        else -> "EMB"
                    }
                    serverEmbeds.add(server to url)
                }
            }

        for ((server, embedUrl) in serverEmbeds) {
            extractVideosFromEmbed(embedUrl, episodeUrl, server).forEach { video ->
                videosByUrl.putIfAbsent(video.url, video)
            }
        }

        return videosByUrl.values.toList().ifEmpty {
            listOf(Video("$currentBaseUrl/debug", "DEBUG: no video found", "$currentBaseUrl/debug"))
        }
    }

    private suspend fun extractVideosFromEmbed(embedUrl: String, episodeUrl: String, server: String): List<Video> {
        val embedHeaders = buildVideoHeaders(episodeUrl)
        val html = runCatching {
            client.newCall(GET(embedUrl, embedHeaders)).awaitSuccess().body.string()
        }.getOrNull() ?: return emptyList()

        val videos = linkedSetOf<Video>()
        collectM3u8Urls(html).forEach { streamUrl ->
            videos.add(
                Video(
                    streamUrl,
                    "AnimeHay - $server",
                    streamUrl,
                    buildVideoHeaders(embedUrl),
                ),
            )
        }

        return videos.toList()
    }

    private fun collectM3u8Urls(text: String): Set<String> {
        val normalized = decodeEscapes(text)
        val urls = linkedSetOf<String>()

        Regex("""https?://[^\s"']+\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.value.trim()) }

        return urls
    }

    private fun decodeEscapes(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    private fun buildVideoHeaders(referer: String): Headers {
        val safeReferer = referer.ifBlank { "$currentBaseUrl/" }
        val origin = extractOrigin(safeReferer) ?: currentBaseUrl
        return headersBuilder()
            .set("Referer", safeReferer)
            .set("Origin", origin)
            .set("Accept", "*/*")
            .build()
    }

    private fun extractOrigin(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val isDefaultPort =
            (httpUrl.scheme == "http" && httpUrl.port == 80) ||
                (httpUrl.scheme == "https" && httpUrl.port == 443)
        val portPart = if (isDefaultPort) "" else ":${httpUrl.port}"
        return "${httpUrl.scheme}://${httpUrl.host}$portPart"
    }

    private fun normalizeToRelativeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith(currentBaseUrl) -> url.removePrefix(currentBaseUrl)
            url.startsWith("/") -> url
            url.startsWith("http://") || url.startsWith("https://") -> null
            else -> "/$url"
        }
    }

    private fun toAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$currentBaseUrl$url"
            else -> "$currentBaseUrl/$url"
        }
    }

    private fun Response.asJsoup(): Document {
        return Jsoup.parse(body.string(), request.url.toString())
    }
}
