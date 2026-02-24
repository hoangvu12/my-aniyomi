package eu.kanade.tachiyomi.animeextension.vi.anime47best

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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Anime47Best : AnimeHttpSource() {

    override val name = "anime47.best"

    override val baseUrl = "https://anime47.best"

    private val apiUrl = "https://anime47.love/api"

    override val lang = "vi"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override fun popularAnimeRequest(page: Int): Request {
        return GET(
            "$baseUrl/filter?sort=views&page=$page",
            headers,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimesPage(response)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/filter?sort=latest&page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) {
            return popularAnimeRequest(page)
        }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return GET(
            "$baseUrl/tim-kiem?q=$encoded&page=$page",
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body.string()
        runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
            val data = root["data"] as? JsonObject ?: root
            return@runCatching parseAnimeDetailsFromApi(data)
        }.getOrNull()?.let { return it }

        val document = Jsoup.parse(body, response.request.url.toString())
        val info = document.selectFirst("main")?.text().orEmpty()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: title
            thumbnail_url = toAbsoluteUrl(
                document.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: document.selectFirst("img[src]")?.attr("abs:src"),
            )
            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst("[itemprop=description]")?.text()
                ?: document.selectFirst("main")?.selectFirst("p")?.text()
                ?: description
            genre = document.select("a[href*=the-loai], a[href*=genre], a[href*=tag]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()
                .ifBlank { genre }
            status = parseStatus(info)
        }
    }

    private fun parseStatus(text: String): Int {
        return when {
            text.contains("Hoàn thành", ignoreCase = true) || text.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
            text.contains("Đang phát", ignoreCase = true) || text.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = Regex("""/m(\d+)\.html""").find(anime.url)?.groupValues?.get(1)
            ?: return emptyList()
        val animeSlug = Regex("""/phim/(.+)/m\d+\.html""").find(anime.url)?.groupValues?.get(1)
            ?: return emptyList()
        val animeSlugWithId = "$animeSlug-$animeId"

        val episodes = linkedMapOf<String, SEpisode>()

        runCatching {
            val detailHtml = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess().body.string()
            val watchRegex = Regex("""/xem/[^"'\s]+/ep-\d+-\d+""")
            watchRegex.findAll(detailHtml).forEach { match ->
                val relativeUrl = match.value
                val episode = createEpisodeFromWatchUrl(relativeUrl)
                episodes.putIfAbsent(relativeUrl, episode)
            }
        }

        runCatching {
            val response = client.newCall(
                GET(
                    "$apiUrl/anime/$animeId/episodes?lang=vi",
                    apiHeaders("$baseUrl${anime.url}"),
                ),
            ).awaitSuccess()
            val element = json.parseToJsonElement(response.body.string())
            val candidates = mutableListOf<EpisodeCandidate>()
            collectEpisodeCandidates(element, candidates)

            candidates.forEach { candidate ->
                val relativeUrl = when {
                    !candidate.watchUrl.isNullOrBlank() -> normalizeToRelativeUrl(candidate.watchUrl)
                    !candidate.slug.isNullOrBlank() -> {
                        val slugPart = candidate.slug.removePrefix("/")
                        if (slugPart.startsWith("ep-")) {
                            "/xem/$animeSlugWithId/$slugPart"
                        } else {
                            null
                        }
                    }
                    candidate.id != null -> {
                        val epNo = candidate.number?.toInt() ?: 1
                        "/xem/$animeSlugWithId/ep-$epNo-${candidate.id}"
                    }
                    else -> null
                }

                if (!relativeUrl.isNullOrBlank()) {
                    val episode = SEpisode.create().apply {
                        setUrlWithoutDomain(relativeUrl)
                        name = candidate.name?.takeIf { it.isNotBlank() } ?: createEpisodeNameFromUrl(relativeUrl)
                        episode_number = candidate.number
                            ?: Regex("""ep-(\d+)""").find(relativeUrl)?.groupValues?.get(1)?.toFloatOrNull()
                            ?: 0f
                    }
                    episodes.putIfAbsent(relativeUrl, episode)
                }
            }
        }

        return episodes.values.sortedByDescending { it.episode_number }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeId = Regex("""-(\d+)$""").find(episode.url)?.groupValues?.get(1)
            ?: return listOf(debugVideo("missing episode id in url"))

        val response = runCatching {
            client.newCall(
                GET(
                    "$apiUrl/anime/watch/episode/$episodeId?lang=vi",
                    apiHeaders(baseUrl + episode.url),
                ),
            ).awaitSuccess()
        }.getOrElse { return listOf(debugVideo(it.message ?: "watch request failed")) }

        val body = response.body.string()
        val element = runCatching { json.parseToJsonElement(body) }
            .getOrElse { return listOf(debugVideo("invalid watch payload")) }

        val episodeReferer = baseUrl + episode.url
        val candidates = mutableListOf<VideoCandidate>()
        collectVideoCandidates(element, candidates)
        if (candidates.isEmpty()) {
            val fallbackUrls = linkedSetOf<String>()
            collectUrls(element, fallbackUrls)
            fallbackUrls.forEach { candidates.add(VideoCandidate(it, null, emptyMap())) }
        }

        val videosByUrl = linkedMapOf<String, Video>()
        val embedCandidates = mutableListOf<VideoCandidate>()
        val probeCandidates = mutableListOf<VideoCandidate>()
        val videos = mutableListOf<Video>()
        candidates.forEach { candidate ->
            val url = candidate.url.trim()
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return@forEach

            val referer = episodeReferer.ifBlank {
                candidate.headers["Referer"] ?: candidate.headers["referer"] ?: "$baseUrl/"
            }
            val videoHeaders = buildVideoHeaders(
                referer = referer,
                extra = candidate.headers,
            )

            when {
                url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true) -> {
                    val playableUrl = markAsM3u8IfNeeded(url)
                    videosByUrl.putIfAbsent(
                        playableUrl,
                        Video(playableUrl, buildVideoLabel(url, candidate.quality), playableUrl, videoHeaders),
                    )
                }
                url.contains(".mp4", ignoreCase = true) -> {
                    videosByUrl.putIfAbsent(
                        url,
                        Video(url, buildVideoLabel(url, candidate.quality), url, videoHeaders),
                    )
                }
                url.contains("embed", ignoreCase = true) || url.contains("player", ignoreCase = true) -> {
                    embedCandidates.add(candidate)
                }
                else -> {
                    probeCandidates.add(candidate)
                }
            }
        }

        videos.addAll(videosByUrl.values)

        if (videos.isEmpty()) {
            embedCandidates.forEach { embed ->
                val embedRequestHeaders = buildVideoHeaders(
                    referer = episodeReferer.ifBlank {
                        embed.headers["Referer"] ?: embed.headers["referer"] ?: "$baseUrl/"
                    },
                    extra = embed.headers,
                )
                extractVideosFromEmbed(embed.url, embedRequestHeaders)?.let { videos.addAll(it) }
            }
        }

        if (videos.isEmpty()) {
            probeCandidates.forEach { candidate ->
                resolveCandidateVideos(candidate, episodeReferer).forEach { resolved ->
                    videosByUrl.putIfAbsent(resolved.url, resolved)
                }
            }
            videos.addAll(videosByUrl.values)
        }

        if (videos.isEmpty()) {
            val urls = linkedSetOf<String>()
            collectUrls(element, urls)
            urls.filter { it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true) }
                .forEach { streamUrl ->
                    videos.add(
                        Video(
                            streamUrl,
                            buildVideoLabel(streamUrl, null),
                            streamUrl,
                            buildVideoHeaders(referer = episodeReferer),
                        ),
                    )
                }
        }

        return videos.ifEmpty { listOf(debugVideo("no stream url in watch payload")) }
    }

    private fun parseAnimesPage(response: Response): AnimesPage {
        val body = response.body.string()
        parseApiAnimesPage(body)?.let { return it }
        val document = Jsoup.parse(body, response.request.url.toString())
        return parseDocumentAnimesPage(document)
    }

    private fun parseApiAnimesPage(body: String): AnimesPage? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: return null

        val data = root["data"] as? JsonObject
        val posts = data?.get("posts") as? JsonArray
        if (posts != null) {
            val animes = posts.mapNotNull { (it as? JsonObject)?.let(::parseApiAnime) }
            val pagination = data["pagination"] as? JsonObject
            val currentPage = pagination?.let { firstInt(it, listOf("current_page")) }
            val lastPage = pagination?.let { firstInt(it, listOf("last_page")) }
            val hasNextPage = when {
                currentPage != null && lastPage != null -> currentPage < lastPage
                else -> animes.size >= 20
            }
            return AnimesPage(animes, hasNextPage)
        }

        val results = root["results"] as? JsonArray
        if (results != null) {
            val animes = results.mapNotNull { (it as? JsonObject)?.let(::parseApiAnime) }
            val hasMore = firstBoolean(root, listOf("has_more"))
            val currentPage = firstInt(root, listOf("current_page"))
            val totalPages = firstInt(root, listOf("total_pages"))
            val hasNextPage = hasMore ?: when {
                currentPage != null && totalPages != null -> currentPage < totalPages
                else -> animes.size >= 20
            }
            return AnimesPage(animes, hasNextPage)
        }

        return null
    }

    private fun parseDocumentAnimesPage(document: Document): AnimesPage {
        val animes = parseAnimeListFromDocument(document)
        val hasNextPage = documentHasNextPage(document, animes.size)
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseApiAnime(obj: JsonObject): SAnime? {
        val slug = firstString(obj, listOf("slug"))?.trim()
        val id = firstInt(obj, listOf("id", "legacy_id"))
        val link = firstString(obj, listOf("link", "url", "href"))

        val normalizedLink = normalizeToRelativeUrl(link)
        val relativeUrl = when {
            !normalizedLink.isNullOrBlank() -> normalizeAnimeInfoPath(normalizedLink)
            !slug.isNullOrBlank() && id != null -> "/phim/$slug/m$id.html"
            else -> null
        } ?: return null

        val title = firstString(obj, listOf("title", "name"))?.trim().orEmpty()
        val thumbnail = toAbsoluteUrl(
            firstString(obj, listOf("poster", "image", "thumbnail", "cover", "poster_url")),
        )

        return SAnime.create().apply {
            setUrlWithoutDomain(relativeUrl)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    private fun parseAnimeDetailsFromApi(data: JsonObject): SAnime {
        return SAnime.create().apply {
            title = firstString(data, listOf("title", "name")).orEmpty()
            thumbnail_url = toAbsoluteUrl(firstString(data, listOf("poster", "image", "thumbnail", "cover")))
            description = firstString(data, listOf("description", "synopsis"))
            genre = ((data["genres"] as? JsonArray).orEmpty())
                .mapNotNull { it as? JsonObject }
                .mapNotNull { firstString(it, listOf("name", "title"))?.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()
            status = parseStatus(firstString(data, listOf("status")).orEmpty())
        }
    }

    private fun parseAnimeListFromDocument(document: Document): List<SAnime> {
        val animes = linkedMapOf<String, SAnime>()

        document.select("a[href*=/phim/], a[href*=/thong-tin/]").forEach { link ->
            val rawHref = normalizeToRelativeUrl(link.attr("href")) ?: return@forEach
            val href = normalizeAnimeInfoPath(rawHref) ?: return@forEach
            val anime = SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = link.attr("title").ifBlank {
                    link.selectFirst("h1, h2, h3, h4, h5")?.text()
                        ?.ifBlank { link.text() }
                        ?.ifBlank { link.selectFirst("img")?.attr("alt") ?: "" }
                        ?: ""
                }
                thumbnail_url = toAbsoluteUrl(
                    link.selectFirst("img")?.attr("abs:src")
                        ?: link.selectFirst("img")?.attr("src"),
                )
            }
            animes.putIfAbsent(href, anime)
        }

        if (animes.isEmpty()) {
            val html = document.html()
            val regex = Regex("""/phim/[a-zA-Z0-9\-]+/m\d+\.html|/thong-tin/[a-zA-Z0-9\-]+-\d+(?:\.html)?""")
            regex.findAll(html).forEach { match ->
                val href = normalizeAnimeInfoPath(match.value) ?: return@forEach
                if (!animes.containsKey(href)) {
                    val slug = Regex("""/phim/([a-zA-Z0-9\-]+)/m\d+\.html""")
                        .find(href)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                        .replace('-', ' ')
                    animes[href] = SAnime.create().apply {
                        setUrlWithoutDomain(href)
                        title = slug
                    }
                }
            }
        }

        return animes.values.toList()
    }

    private fun documentHasNextPage(document: Document, count: Int): Boolean {
        if (document.select("a[rel=next], link[rel=next]").isNotEmpty()) return true
        if (document.select("a[aria-label*=Trang tiếp theo], button[aria-label*=Trang tiếp theo]:not([disabled])").isNotEmpty()) return true

        val pageText = document.text().replace('\u00A0', ' ')
        val resultRange = Regex("""Hiển thị\s*(\d+)\s*[–-]\s*(\d+)\s*trong\s*(\d+)\s*kết quả""")
            .find(pageText)
        if (resultRange != null) {
            val shownEnd = resultRange.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val total = resultRange.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return shownEnd in 1 until total
        }

        return count >= 20
    }

    private fun createEpisodeFromWatchUrl(relativeUrl: String): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(relativeUrl)
            name = createEpisodeNameFromUrl(relativeUrl)
            episode_number = Regex("""ep-(\d+)""").find(relativeUrl)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        }
    }

    private fun createEpisodeNameFromUrl(relativeUrl: String): String {
        val number = Regex("""ep-(\d+)""").find(relativeUrl)?.groupValues?.get(1)
        return if (number != null) "Tập $number" else "Episode"
    }

    private suspend fun extractVideosFromEmbed(embedUrl: String, requestHeaders: Headers): List<Video>? {
        return runCatching {
            val html = client.newCall(GET(embedUrl, requestHeaders)).awaitSuccess().body.string()
            val m3u8 = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                .findAll(html)
                .map { it.value }
                .toSet()
            val streamHeaders = buildVideoHeaders(referer = embedUrl)
            m3u8.map {
                val playableUrl = markAsM3u8IfNeeded(it)
                Video(playableUrl, "Anime47 - HLS", playableUrl, streamHeaders)
            }
        }.getOrNull()?.ifEmpty { null }
    }

    private suspend fun resolveCandidateVideos(candidate: VideoCandidate, defaultReferer: String): List<Video> {
        val candidateUrl = candidate.url.trim()
        if (!(candidateUrl.startsWith("http://") || candidateUrl.startsWith("https://"))) return emptyList()

        val referer = defaultReferer.ifBlank {
            candidate.headers["Referer"] ?: candidate.headers["referer"] ?: "$baseUrl/"
        }
        val requestHeaders = buildVideoHeaders(
            referer = referer,
            extra = candidate.headers,
        )
        val label = buildVideoLabel(candidateUrl, candidate.quality)

        return runCatching {
            val response = client.newCall(GET(candidateUrl, requestHeaders)).awaitSuccess()
            val contentType = response.header("Content-Type").orEmpty().lowercase()

            if (contentType.contains("mpegurl") || contentType.contains("video/mp4")) {
                val isHls = contentType.contains("mpegurl")
                val playableUrl = if (isHls) markAsM3u8IfNeeded(candidateUrl) else candidateUrl
                return@runCatching listOf(Video(playableUrl, label, playableUrl, requestHeaders))
            }

            val body = response.body.string()
            if (body.contains("#EXTM3U")) {
                val playableUrl = markAsM3u8IfNeeded(candidateUrl)
                return@runCatching listOf(Video(playableUrl, label, playableUrl, requestHeaders))
            }

            val extracted = linkedSetOf<String>()
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(body).forEach { extracted.add(it.value) }
            Regex("""(?m)^/[^#\s]+$""").findAll(body).forEach { relative ->
                candidateUrl.toHttpUrlOrNull()
                    ?.resolve(relative.value.trim())
                    ?.toString()
                    ?.let { extracted.add(it) }
            }

            extracted.map { streamUrl ->
                Video(streamUrl, buildVideoLabel(streamUrl, candidate.quality), streamUrl, requestHeaders)
            }
        }.getOrNull().orEmpty()
    }

    private fun apiHeaders(referer: String): Headers {
        return headersBuilder()
            .set("Referer", referer)
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun normalizeToRelativeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith(baseUrl) -> url.removePrefix(baseUrl)
            url.startsWith("/") -> url
            url.startsWith("http://") || url.startsWith("https://") -> null
            else -> "/$url"
        }
    }

    private fun animeIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return Regex("""/m(\d+)\.html""").find(url)?.groupValues?.get(1)
    }

    private fun normalizeAnimeInfoPath(url: String?): String? {
        if (url.isNullOrBlank()) return null

        Regex("""^/phim/[a-zA-Z0-9\-]+/m\d+\.html$""")
            .find(url)
            ?.let { return it.value }

        val infoMatch = Regex("""^/thong-tin/([a-zA-Z0-9\-]+)-(\d+)(?:\.html)?$""")
            .find(url)
        if (infoMatch != null) {
            val slug = infoMatch.groupValues[1]
            val id = infoMatch.groupValues[2]
            return "/phim/$slug/m$id.html"
        }

        return null
    }

    private fun toAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> url
        }
    }

    private fun buildVideoHeaders(referer: String, extra: Map<String, String> = emptyMap()): Headers {
        val fixedReferer = referer.ifBlank { "$baseUrl/" }
        val origin = extractOrigin(fixedReferer) ?: baseUrl
        val builder = headersBuilder()
            .set("Referer", fixedReferer)
            .set("Origin", origin)
            .set("Accept", "*/*")

        extra.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank()) return@forEach
            val lower = key.lowercase()
            if (lower == "referer" || lower == "origin") return@forEach
            builder.set(key, value)
        }
        return builder.build()
    }

    private fun extractOrigin(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val defaultPort = (httpUrl.scheme == "http" && httpUrl.port == 80) || (httpUrl.scheme == "https" && httpUrl.port == 443)
        val portPart = if (defaultPort) "" else ":${httpUrl.port}"
        return "${httpUrl.scheme}://${httpUrl.host}$portPart"
    }

    private fun buildVideoLabel(url: String, quality: String?): String {
        val q = quality?.trim().orEmpty()
        if (q.isNotBlank()) return "Anime47 - $q"
        return when {
            url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true) -> "Anime47 - HLS"
            url.contains(".mp4", ignoreCase = true) -> "Anime47 - MP4"
            else -> "Anime47 - Video"
        }
    }

    private fun markAsM3u8IfNeeded(url: String): String {
        if (url.contains("m3u8", ignoreCase = true)) return url
        return if (url.contains("#")) "$url&m3u8" else "$url#.m3u8"
    }

    private fun collectUrls(element: JsonElement, result: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.values.forEach { collectUrls(it, result) }
            is JsonArray -> element.forEach { collectUrls(it, result) }
            is JsonPrimitive -> {
                val content = element.contentOrNull ?: return
                if (content.startsWith("http://") || content.startsWith("https://")) {
                    result.add(content)
                }
            }
        }
    }

    private fun collectVideoCandidates(element: JsonElement, out: MutableList<VideoCandidate>) {
        when (element) {
            is JsonObject -> {
                val url = firstString(
                    element,
                    listOf("url", "file", "src", "link", "source", "stream", "playlist", "play_url", "video_url", "m3u8", "mp4", "embed"),
                )
                if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    out.add(
                        VideoCandidate(
                            url = url,
                            quality = firstString(element, listOf("label", "quality", "name", "title", "resolution", "server", "type")),
                            headers = extractHeadersMap(element),
                        ),
                    )
                }
                element.values.forEach { collectVideoCandidates(it, out) }
            }
            is JsonArray -> element.forEach { collectVideoCandidates(it, out) }
            else -> Unit
        }
    }

    private fun extractHeadersMap(obj: JsonObject): Map<String, String> {
        val keys = listOf("headers", "header", "http_headers", "request_headers")
        for (key in keys) {
            val value = obj[key] as? JsonObject ?: continue
            val result = mutableMapOf<String, String>()
            value.forEach { (k, v) ->
                val primitive = v as? JsonPrimitive ?: return@forEach
                val content = primitive.contentOrNull?.trim().orEmpty()
                if (content.isNotBlank()) result[k] = content
            }
            if (result.isNotEmpty()) return result
        }
        return emptyMap()
    }

    private fun collectEpisodeCandidates(element: JsonElement, out: MutableList<EpisodeCandidate>) {
        when (element) {
            is JsonObject -> {
                val watchUrl = firstString(element, listOf("url", "href", "link", "watch_url", "episode_url", "path"))
                val slug = firstString(element, listOf("slug", "episode_slug"))
                val id = firstInt(element, listOf("id", "episode_id", "episodeId"))
                val number = firstFloat(element, listOf("number", "episode", "ep", "episode_number", "episodeNumber"))
                val name = firstString(element, listOf("name", "title", "episode_title", "episodeName"))

                if (watchUrl != null || slug != null || id != null) {
                    out.add(EpisodeCandidate(id, number, name, watchUrl, slug))
                }
                element.values.forEach { collectEpisodeCandidates(it, out) }
            }
            is JsonArray -> element.forEach { collectEpisodeCandidates(it, out) }
            else -> Unit
        }
    }

    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val value = obj[key] as? JsonPrimitive ?: continue
            val content = value.contentOrNull?.trim().orEmpty()
            if (content.isNotBlank()) return content
        }
        return null
    }

    private fun firstInt(obj: JsonObject, keys: List<String>): Int? {
        for (key in keys) {
            val value = obj[key] as? JsonPrimitive ?: continue
            value.contentOrNull?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun firstBoolean(obj: JsonObject, keys: List<String>): Boolean? {
        for (key in keys) {
            val value = obj[key] as? JsonPrimitive ?: continue
            when (value.contentOrNull?.trim()?.lowercase()) {
                "true", "1", "yes" -> return true
                "false", "0", "no" -> return false
            }
        }
        return null
    }

    private fun firstFloat(obj: JsonObject, keys: List<String>): Float? {
        for (key in keys) {
            val value = obj[key] as? JsonPrimitive ?: continue
            value.contentOrNull?.toFloatOrNull()?.let { return it }
            Regex("""\d+(?:\.\d+)?""").find(value.contentOrNull.orEmpty())?.value?.toFloatOrNull()?.let { return it }
        }
        return null
    }

    private fun debugVideo(message: String): Video {
        return Video("$baseUrl/debug", "DEBUG: $message", "$baseUrl/debug")
    }

    private fun Response.asJsoup(): Document {
        return Jsoup.parse(body.string(), request.url.toString())
    }

    private data class EpisodeCandidate(
        val id: Int?,
        val number: Float?,
        val name: String?,
        val watchUrl: String?,
        val slug: String?,
    )

    private data class VideoCandidate(
        val url: String,
        val quality: String?,
        val headers: Map<String, String>,
    )
}
