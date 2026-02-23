package eu.kanade.tachiyomi.animeextension.vi.animevietsub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class AnimeVietSub : AnimeHttpSource() {

    override val name = "AnimeVietSub"

    override val baseUrl = "https://animevietsub.be"

    override val lang = "vi"

    override val supportsLatest = true

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
        // No traditional pagination on listing pages
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

        // Try to get filmId from the URL pattern: /phim/{slug}-a{id}/
        val filmId = Regex("""-a(\d+)/?$""").find(anime.url)?.groupValues?.get(1)

        // Try to get episodes from the detail page directly
        val episodes = detailDoc.select("ul.list-episode a.btn-episode").map { el ->
            SEpisode.create().apply {
                setUrlWithoutDomain(el.attr("href"))
                name = el.attr("title").ifBlank { "Tập ${el.text().trim()}" }
                episode_number = el.text().trim().toFloatOrNull() ?: 0f
            }
        }.toMutableList()

        // If no episodes on detail page, navigate to the first episode page
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

            // If still empty, try the AJAX endpoint
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

        // Get all server buttons with their hashes
        val serverButtons = epDoc.select("a.btn3dsv[data-href]")

        for (btn in serverButtons) {
            val hash = btn.attr("data-href")
            val serverName = btn.text().trim()
            val play = btn.attr("data-play")

            if (hash.isBlank()) continue

            try {
                val formBody = FormBody.Builder()
                    .add("hash", hash)
                    .add("id", btn.attr("data-id"))
                    .add("play", play)
                    .build()

                val playerResp = client.newCall(
                    POST(
                        "$baseUrl/ajax/player",
                        headers = Headers.Builder()
                            .add("Referer", baseUrl + episode.url)
                            .add("X-Requested-With", "XMLHttpRequest")
                            .build(),
                        body = formBody,
                    ),
                ).awaitSuccess()

                val playerBody = playerResp.body.string()
                if (playerBody.isBlank()) continue

                // Try parsing as JSON
                val linkMatch = Regex(""""link"\s*:\s*"([^"]+)"""").find(playerBody)
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(playerBody)
                val m3u8Match = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(playerBody)

                val videoUrl = linkMatch?.groupValues?.get(1)
                    ?: srcMatch?.groupValues?.get(1)
                    ?: m3u8Match?.groupValues?.get(1)

                if (videoUrl != null) {
                    if (videoUrl.contains(".m3u8")) {
                        videos.add(Video(videoUrl, "$serverName - HLS", videoUrl))
                    } else {
                        // Might be an iframe URL - fetch it
                        try {
                            val iframeDoc = client.newCall(
                                GET(videoUrl, headers),
                            ).awaitSuccess().asJsoup()
                            val iframeM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                                .find(iframeDoc.html())
                            if (iframeM3u8 != null) {
                                videos.add(Video(iframeM3u8.value, "$serverName - HLS", iframeM3u8.value))
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback: extract from episode page embedded player
        if (videos.isEmpty()) {
            val iframes = epDoc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("abs:src")
                if (src.isBlank() || src.contains("facebook") || src.contains("ads")) continue
                try {
                    val iframeDoc = client.newCall(
                        GET(src, headers),
                    ).awaitSuccess().asJsoup()
                    val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                        .find(iframeDoc.html())
                    if (m3u8 != null) {
                        videos.add(Video(m3u8.value, "Default", m3u8.value))
                    }
                } catch (_: Exception) {}
            }
        }

        return videos
    }

    // ============================== Helpers ==============================

    private fun Response.asJsoup(): Document {
        return Jsoup.parse(body.string(), request.url.toString())
    }
}
