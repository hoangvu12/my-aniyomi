package eu.kanade.tachiyomi.animeextension.vi.hentaiz

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HentaiZ : AnimeHttpSource() {

    override val name = "HentaiZ"

    override val baseUrl = "https://hentaiz.dog"

    override val lang = "vi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/browse/__data.json?sort=publishedAt_desc&page=$page&limit=$PAGE_SIZE" +
            "&animationType=TWO_D&contentRating=ALL&isTrailer=false&year=ALL",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val bodyStr = response.body.string()
        val (entries, totalPages) = parseBrowse(bodyStr)
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val animes = entries.map { entry ->
            SAnime.create().apply {
                setUrlWithoutDomain("/watch/${entry.slug}")
                title = entry.title
                thumbnail_url = entry.posterUrl
            }
        }
        return AnimesPage(animes, currentPage < totalPages)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        return GET("$baseUrl/browse/__data.json?q=$encoded&page=$page&limit=$PAGE_SIZE", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val obj = doc.extractLdJson() ?: return SAnime.create().apply { title = "Unknown" }

        return SAnime.create().apply {
            title = obj.str("name") ?: "Unknown"
            description = buildString {
                obj.str("description")?.let { append(it) }
                obj.str("contentRating")?.let { nl(); append("Rating: $it") }
                (obj["publisher"] as? JsonObject)?.str("name")?.let { nl(); append("Studio: $it") }
            }
            genre = (obj["genre"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.joinToString()
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes =============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET(baseUrl + anime.url, headers))
            .awaitSuccess().asJsoup()
        val obj = doc.extractLdJson()
        val title = obj?.str("name")

        if (title != null) {
            val encoded = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
            val searchBody = client.newCall(
                GET("$baseUrl/browse/__data.json?q=$encoded&limit=100", headers),
            ).awaitSuccess().body.string()

            val (entries, _) = parseBrowse(searchBody)
            val episodes = entries
                .filter { it.title.equals(title, ignoreCase = true) }
                .sortedByDescending { it.episodeNumber }
                .map { entry ->
                    SEpisode.create().apply {
                        setUrlWithoutDomain("/watch/${entry.slug}")
                        name = "Episode ${entry.episodeNumber}"
                        episode_number = entry.episodeNumber.toFloat()
                    }
                }
            if (episodes.isNotEmpty()) return episodes
        }

        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(anime.url)
                name = "Episode 1"
                episode_number = 1f
            },
        )
    }

    // ============================== Videos ===============================

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val doc = client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess().asJsoup()
        val obj = doc.extractLdJson() ?: return emptyList()
        val embedUrl = obj.str("embedUrl") ?: return emptyList()

        val uuid = Regex("""[?&]v=([a-f0-9-]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .add("Referer", "https://play.sonar-cdn.com/")
            .build()

        val encryptedText = client.newCall(
            GET("https://x.mimix.cc/watch/$uuid", videoHeaders),
        ).awaitSuccess().body.string().trim()

        val decryptedJson = decrypt(encryptedText, uuid) ?: return emptyList()
        val videoData = json.parseToJsonElement(decryptedJson).jsonObject

        val segmentDomains = (videoData["segmentDomains"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val cdnDomain = segmentDomains?.firstOrNull()
            ?: videoData.str("domain")
            ?: return emptyList()

        val masterM3u8 = (videoData["defaultM3u8"] as? JsonObject)
            ?.str("master")
            ?: return emptyList()

        val streamHeaders = Headers.Builder()
            .add("Referer", "https://play.sonar-cdn.com/")
            .build()

        val videos = STREAM_INFO_REGEX.findAll(masterM3u8).map { match ->
            val resolution = match.groupValues[1]
            val variantPath = match.groupValues[2].trim()
            val fullUrl = "$cdnDomain/$uuid/$variantPath"
            Video(fullUrl, "HentaiZ - $resolution", fullUrl, streamHeaders)
        }.toList()

        if (videos.isNotEmpty()) return videos

        return masterM3u8.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { index, path ->
                val fullUrl = "$cdnDomain/$uuid/${path.trim()}"
                Video(fullUrl, "HentaiZ - Stream ${index + 1}", fullUrl, streamHeaders)
            }
    }

    // ============================== SvelteKit Data Parsing ===============

    private data class BrowseEntry(
        val title: String,
        val slug: String,
        val episodeNumber: Int,
        val posterUrl: String?,
    )

    private fun parseBrowse(bodyStr: String): Pair<List<BrowseEntry>, Int> {
        val root = json.parseToJsonElement(bodyStr).jsonObject
        val nodes = root["nodes"] as? JsonArray
            ?: return emptyList<BrowseEntry>() to 1
        val browseNode = findBrowseNode(nodes)
            ?: return emptyList<BrowseEntry>() to 1
        val data = browseNode["data"] as? JsonArray
            ?: return emptyList<BrowseEntry>() to 1
        val schema = data.getOrNull(0) as? JsonObject
            ?: return emptyList<BrowseEntry>() to 1

        val entries = parseEntries(data, schema)
        val totalPages = resolveInt(data, schema["totalPages"]) ?: 1
        return entries to totalPages
    }

    private fun findBrowseNode(nodes: JsonArray): JsonObject? {
        (nodes.getOrNull(2) as? JsonObject)?.let { node ->
            val data = node["data"] as? JsonArray ?: return@let
            if ((data.getOrNull(0) as? JsonObject)?.containsKey("episodes") == true) return node
        }
        return nodes.filterIsInstance<JsonObject>().firstOrNull { node ->
            val data = node["data"] as? JsonArray ?: return@firstOrNull false
            (data.getOrNull(0) as? JsonObject)?.containsKey("episodes") == true
        }
    }

    private fun parseEntries(data: JsonArray, schema: JsonObject): List<BrowseEntry> {
        val episodesIdx = (schema["episodes"] as? JsonPrimitive)?.intOrNull
            ?: return emptyList()
        val episodeRefs = data.getOrNull(episodesIdx) as? JsonArray
            ?: return emptyList()

        return episodeRefs.mapNotNull { ref ->
            val idx = (ref as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val epSchema = data.getOrNull(idx) as? JsonObject ?: return@mapNotNull null

            val title = resolveString(data, epSchema["title"]) ?: return@mapNotNull null
            val slug = resolveString(data, epSchema["slug"]) ?: return@mapNotNull null
            val episodeNumber = resolveInt(data, epSchema["episodeNumber"]) ?: 1
            val posterUrl = resolvePosterUrl(data, epSchema["posterImage"])

            BrowseEntry(title, slug, episodeNumber, posterUrl)
        }
    }

    private fun resolvePosterUrl(data: JsonArray, element: JsonElement?): String? {
        val idx = (element as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        val obj = data.getOrNull(idx) as? JsonObject ?: return null
        val filePath = resolveString(data, obj["filePath"]) ?: return null
        return "$STORAGE_URL$filePath"
    }

    private fun resolveString(data: JsonArray, element: JsonElement?): String? {
        val idx = (element as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        return (data.getOrNull(idx) as? JsonPrimitive)?.contentOrNull
    }

    private fun resolveInt(data: JsonArray, element: JsonElement?): Int? {
        val idx = (element as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
        return (data.getOrNull(idx) as? JsonPrimitive)?.intOrNull
    }

    // ============================== Crypto ===============================

    private fun decrypt(encrypted: String, uuid: String): String? {
        return try {
            val key = MessageDigest.getInstance("SHA-256").digest(uuid.toByteArray())
            val (ivHex, ctHex) = encrypted.split(":")
            val iv = ivHex.hexToBytes()
            val ct = ctHex.hexToBytes()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct))
        } catch (_: Exception) {
            null
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ============================== Helpers ==============================

    private fun Response.asJsoup(): Document =
        Jsoup.parse(body.string(), request.url.toString())

    private fun Document.extractLdJson(): JsonObject? {
        val script = selectFirst("script[type=application/ld+json]")?.data() ?: return null
        return try {
            json.parseToJsonElement(script).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun StringBuilder.nl() { if (isNotEmpty()) appendLine() }

    companion object {
        private const val PAGE_SIZE = 24
        private const val STORAGE_URL = "https://storage.haiten.org"
        private val STREAM_INFO_REGEX = Regex(
            """#EXT-X-STREAM-INF:[^\n]*RESOLUTION=(\d+x\d+)[^\n]*\n([^\n]+)""",
        )
    }
}
