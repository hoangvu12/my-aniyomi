package eu.kanade.tachiyomi.animeextension.vi.kkphim

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

class KkPhim : AnimeHttpSource() {

    override val name = "KKPhim"

    override val baseUrl = "https://phimapi.com"

    override val lang = "vi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/danh-sach/phim-moi-cap-nhat?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val root = response.asJsonObject()
        return parseAnimeList(root)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val keyword = encodeQueryValue(query.trim())
        return GET("$baseUrl/v1/api/tim-kiem?keyword=$keyword&page=$page&limit=24", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val root = response.asJsonObject()
        return parseAnimeList(root)
    }

    private fun parseAnimeList(root: JsonObject): AnimesPage {
        val data = root.getObject("data")
        val items = data?.getArray("items") ?: root.getArray("items")

        val animes = items.mapNotNull { item ->
            item.asObject()?.toSAnime()
        }

        val pagination = data
            ?.getObject("params")
            ?.getObject("pagination")
            ?: root.getObject("pagination")

        val currentPage = pagination?.getInt("currentPage") ?: 1
        val totalPages = pagination?.getInt("totalPages") ?: currentPage
        return AnimesPage(animes, currentPage < totalPages)
    }

    private fun JsonObject.toSAnime(): SAnime? {
        val slug = getString("slug")
        val title = getString("name").ifBlank { getString("origin_name") }
        if (slug.isBlank() || title.isBlank()) return null

        return SAnime.create().apply {
            setUrlWithoutDomain("/phim/$slug")
            this.title = title
            thumbnail_url = toAbsoluteImageUrl(
                getString("poster_url").ifBlank { getString("thumb_url") },
            )
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val root = response.asJsonObject()
        val movie = root.getObject("movie") ?: JsonObject(emptyMap())

        val title = movie.getString("name").ifBlank { movie.getString("origin_name") }
        val altTitle = movie.getString("origin_name")
        val content = movie.getString("content")
        val year = movie.getInt("year")
        val duration = movie.getString("time")
        val current = movie.getString("episode_current")
        val language = movie.getString("lang")
        val countries = movie.getArray("country")
            .mapNotNull { country ->
                country.asObject()
                    ?.getString("name")
                    ?.takeIf { it.isNotBlank() }
            }
            .joinToString()

        val metadata = listOfNotNull(
            year?.let { "Year: $it" },
            duration.takeIf(String::isNotBlank)?.let { "Duration: $it" },
            language.takeIf(String::isNotBlank)?.let { "Language: $it" },
            current.takeIf(String::isNotBlank)?.let { "Status: $it" },
            countries.takeIf(String::isNotBlank)?.let { "Country: $countries" },
        )

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = toAbsoluteImageUrl(
                movie.getString("poster_url").ifBlank { movie.getString("thumb_url") },
            )
            description = buildString {
                if (altTitle.isNotBlank()) appendLine("Alternative: $altTitle")
                if (metadata.isNotEmpty()) appendLine(metadata.joinToString(" | "))
                append(content)
            }.trim()
            genre = movie.getArray("category")
                .mapNotNull { category ->
                    category.asObject()
                        ?.getString("name")
                        ?.takeIf { it.isNotBlank() }
                }
                .joinToString()
            status = parseStatus(
                statusText = movie.getString("status"),
                episodeCurrent = current,
            )
        }
    }

    private fun parseStatus(statusText: String, episodeCurrent: String): Int {
        val merged = normalizeText("$statusText $episodeCurrent")
        return when {
            merged.contains("completed") ||
                merged.contains("hoan tat") ||
                merged.contains("hoan thanh") ||
                merged.contains("full") -> SAnime.COMPLETED
            merged.contains("ongoing") ||
                merged.contains("dang cap nhat") ||
                merged.contains("tap") -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val movieSlug = anime.url.substringAfterLast("/").substringBefore("?")
        val root = client.newCall(GET("$baseUrl/phim/$movieSlug", headers))
            .awaitSuccess()
            .asJsonObject()

        val episodes = mutableListOf<SEpisode>()
        val groups = root.getArray("episodes")
        groups.forEach { serverElement ->
            val serverObject = serverElement.asObject() ?: return@forEach
            val serverName = serverObject.getString("server_name").ifBlank { "Server" }
            val serverData = serverObject.getArray("server_data")

            serverData.forEachIndexed { index, episodeElement ->
                val episodeObject = episodeElement.asObject() ?: return@forEachIndexed
                val episodeSlug = episodeObject.getString("slug")
                if (episodeSlug.isBlank()) return@forEachIndexed

                val episodeName = episodeObject.getString("name")
                    .ifBlank { "Episode ${index + 1}" }
                val episodeNumber = parseEpisodeNumber(
                    episodeName = episodeName,
                    episodeSlug = episodeSlug,
                    fallback = index + 1,
                )

                val episodeUrl = buildEpisodeUrl(
                    movieSlug = movieSlug,
                    episodeSlug = episodeSlug,
                    serverName = serverName,
                )

                episodes.add(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episodeUrl)
                        name = "$episodeName [$serverName]"
                        episode_number = episodeNumber
                    },
                )
            }
        }

        return episodes.sortedWith(
            compareByDescending<SEpisode> { it.episode_number }
                .thenByDescending { it.name },
        )
    }

    private fun buildEpisodeUrl(movieSlug: String, episodeSlug: String, serverName: String): String {
        val episode = encodeQueryValue(episodeSlug)
        val server = encodeQueryValue(serverName)
        return "/phim/$movieSlug?episode=$episode&server=$server"
    }

    private fun parseEpisodeNumber(episodeName: String, episodeSlug: String, fallback: Int): Float {
        EPISODE_NUMBER_REGEX.find(episodeName)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        EPISODE_NUMBER_REGEX.find(episodeSlug)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        return if (episodeName.contains("full", ignoreCase = true)) 1f else fallback.toFloat()
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = (baseUrl + episode.url).toHttpUrlOrNull()
            ?: return emptyList()

        val movieSlug = episodeUrl.pathSegments.lastOrNull().orEmpty()
        val targetEpisodeSlug = episodeUrl.queryParameter("episode").orEmpty()
        val targetServerName = episodeUrl.queryParameter("server").orEmpty()
        if (movieSlug.isBlank() || targetEpisodeSlug.isBlank()) return emptyList()

        val root = client.newCall(GET("$baseUrl/phim/$movieSlug", headers))
            .awaitSuccess()
            .asJsonObject()

        val videosByUrl = linkedMapOf<String, Video>()
        val entries = findEpisodeEntries(
            root = root,
            targetEpisodeSlug = targetEpisodeSlug,
            targetServerName = targetServerName,
        )

        entries.forEach { (serverName, episodeData) ->
            val referer = "$baseUrl/phim/$movieSlug"
            collectVideosFromEpisodeData(
                episodeData = episodeData,
                serverName = serverName,
                referer = referer,
                videosByUrl = videosByUrl,
            )
        }

        return videosByUrl.values.toList().ifEmpty {
            listOf(Video("$baseUrl/debug", "KKPhim - no video found", "$baseUrl/debug"))
        }
    }

    private fun findEpisodeEntries(
        root: JsonObject,
        targetEpisodeSlug: String,
        targetServerName: String,
    ): List<Pair<String, JsonObject>> {
        val groups = root.getArray("episodes")
        val entries = mutableListOf<Pair<String, JsonObject>>()

        fun collect(ignoreServer: Boolean) {
            groups.forEach { groupElement ->
                val groupObject = groupElement.asObject() ?: return@forEach
                val serverName = groupObject.getString("server_name")
                if (!ignoreServer && targetServerName.isNotBlank() && !serverName.equals(targetServerName, ignoreCase = true)) {
                    return@forEach
                }

                groupObject.getArray("server_data").forEach { episodeElement ->
                    val episodeObject = episodeElement.asObject() ?: return@forEach
                    if (episodeObject.getString("slug") != targetEpisodeSlug) return@forEach
                    entries.add(serverName to episodeObject)
                }
            }
        }

        collect(ignoreServer = false)
        if (entries.isEmpty()) collect(ignoreServer = true)
        return entries
    }

    private suspend fun collectVideosFromEpisodeData(
        episodeData: JsonObject,
        serverName: String,
        referer: String,
        videosByUrl: MutableMap<String, Video>,
    ) {
        val directUrls = linkedSetOf<String>()
        val m3u8Url = episodeData.getString("link_m3u8")
        if (m3u8Url.startsWith("http://") || m3u8Url.startsWith("https://")) {
            directUrls.add(m3u8Url)
        }

        val embedUrl = episodeData.getString("link_embed")
        directUrls.addAll(extractM3u8FromText(embedUrl))
        embedUrl.toHttpUrlOrNull()?.queryParameter("url")
            ?.takeIf { it.contains(".m3u8", ignoreCase = true) }
            ?.let { directUrls.add(it) }

        directUrls.forEach { videoUrl ->
            videosByUrl.putIfAbsent(
                videoUrl,
                Video(
                    videoUrl,
                    "KKPhim - $serverName",
                    videoUrl,
                    buildVideoHeaders(embedUrl.ifBlank { referer }),
                ),
            )
        }

        if (embedUrl.isNotBlank() && directUrls.isEmpty()) {
            extractVideosFromEmbed(embedUrl, serverName).forEach { video ->
                videosByUrl.putIfAbsent(video.url, video)
            }
        }
    }

    private suspend fun extractVideosFromEmbed(embedUrl: String, serverName: String): List<Video> {
        val embedHtml = runCatching {
            client.newCall(GET(embedUrl, buildVideoHeaders("$baseUrl/")))
                .awaitSuccess()
                .body
                .string()
        }.getOrNull() ?: return emptyList()

        val streamUrls = extractM3u8FromText(embedHtml)
        return streamUrls.map { streamUrl ->
            Video(
                streamUrl,
                "KKPhim - $serverName",
                streamUrl,
                buildVideoHeaders(embedUrl),
            )
        }
    }

    private fun extractM3u8FromText(text: String): Set<String> {
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return M3U8_REGEX.findAll(normalized)
            .map { it.value.trim() }
            .toCollection(linkedSetOf())
    }

    private fun buildVideoHeaders(referer: String): Headers {
        val safeReferer = referer.ifBlank { "$baseUrl/" }
        val origin = extractOrigin(safeReferer) ?: baseUrl
        return headersBuilder()
            .set("Referer", safeReferer)
            .set("Origin", origin)
            .set("Accept", "*/*")
            .build()
    }

    private fun extractOrigin(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val isDefaultPort =
            (parsed.scheme == "http" && parsed.port == 80) ||
                (parsed.scheme == "https" && parsed.port == 443)
        val portPart = if (isDefaultPort) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$portPart"
    }

    private fun toAbsoluteImageUrl(url: String): String? {
        if (url.isBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$CDN_URL$url"
            else -> "$CDN_URL/$url"
        }
    }

    private fun encodeQueryValue(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private fun normalizeText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
    }

    private fun Response.asJsonObject(): JsonObject {
        return json.parseToJsonElement(body.string()).asObject() ?: JsonObject(emptyMap())
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    private fun JsonObject.getObject(key: String): JsonObject? = this[key].asObject()

    private fun JsonObject.getArray(key: String): JsonArray = this[key].asArray() ?: JsonArray(emptyList())

    private fun JsonObject.getString(key: String): String {
        return (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    }

    private fun JsonObject.getInt(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    companion object {
        private const val CDN_URL = "https://phimimg.com"
        private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
        private val M3U8_REGEX = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE)
    }
}
