package recloudstream

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import recloudstream.migration.ProviderDomainMigration
import recloudstream.migration.normalizeSiteUrl
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DỰNG LẠI TỪ classes.dex của Animevietsub.cs3 (v47) bằng jadx 1.5.6, rồi chuyển tay sang Kotlin.
 * Không phải mã nguồn gốc: tên biến/cấu trúc có thể khác, nhưng thuật toán, hằng số, selector, URL
 * và header lấy đúng từ mã decompile.
 *
 */
class AnimeVietsubProvider : MainAPI() {
    override var mainUrl = "https://bit.ly/animevietsubtv"
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.Movie)
    override var lang = "vi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/anime-moi/" to "Mới Cập Nhật",
        "/anime-sap-chieu/" to "Sắp Chiếu",
        "/bang-xep-hang/day.html" to "Xem Nhiều Trong Ngày",
    )

    private val avsTokenDeclRegex =
        Regex("""const\s+avsToken\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\s*;""")
    private val avsTokenChunkRegex = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val avsTokenSimpleRegex = Regex("""const\s+avsToken\s*=\s*"([^"]+)""")

    companion object {
        var activeDomain: String? = null
        var globalCookies = ""
        const val entryUrl = "https://bit.ly/animevietsubtv"
        private const val FALLBACK_DOMAIN = "https://animevietsub.site"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        val redirectRegex = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
        val yearRegex = Regex("""\b(19|20)\d{2}\b""")
        val durationRegex = Regex("""\d+""")
        val epNumRegex = Regex("""\d+([.,]\d+)?""")
        private val bgRegex = Regex("""url\s*\(\s*['"]?([^'")]+)['"]?\s*\)""")

        private const val AVS_TS_SYNC = 0x47 // 71, 'G'
        private const val AVS_TS_PACKET = 188
        private const val AVS_TS_SYNC_CHAIN = 8
        private const val AVS_PROBE_BYTES = 65536L
        private const val AVS_MAX_PREFIX = 4096

        val decryptedPlaylists = ConcurrentHashMap<String, String>()
        val cfUa = AtomicReference<String?>(null)
        val cfCookies = ConcurrentHashMap<String, String>()
        val cfGotBytes = ConcurrentHashMap<String, ByteArray>()
        val cfFailedAt = ConcurrentHashMap<String, Long>()
        val cfWarmupUrl = ConcurrentHashMap<String, String>()
        val cfWarmupHash = AtomicReference<String?>(null)
        private var avsStripLoggedOnce = false

        fun mergeCookies(existing: String, newCookies: List<String>): String {
            val map = linkedMapOf<String, String>()
            existing.split(";").forEach {
                val p = it.split("=", limit = 2)
                if (p.size == 2) map[p[0].trim()] = p[1].trim()
            }
            newCookies.forEach {
                val p = it.substringBefore(";").split("=", limit = 2)
                if (p.size == 2) map[p[0].trim()] = p[1].trim()
            }
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    data class PlaylistResult(val url: String, val text: String)
    private data class ServerData(val groupName: String, val hash: String, val id: String, val href: String)

    // ---------- helpers ----------

    private suspend fun getBaseUrl(): String {
        activeDomain?.let { return it }
        return try {
            val res = app.get(entryUrl, headers = getBypassHeaders(null), allowRedirects = true, timeout = 10000)
            val finalUrl = res.url
            if (finalUrl.startsWith("http") && !finalUrl.contains("bit.ly")) {
                val u = URL(finalUrl)
                "${u.protocol}://${u.host}".also { activeDomain = it }
            } else {
                FALLBACK_DOMAIN.also { activeDomain = it }
            }
        } catch (_: Exception) {
            FALLBACK_DOMAIN.also { activeDomain = it }
        }
    }

    private fun getBypassHeaders(referer: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "vi,en-US;q=0.9,en;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to UA,
        )
        if (referer != null) h["Referer"] = referer
        if (globalCookies.isNotEmpty()) h["Cookie"] = globalCookies
        return h
    }

    /** GET + gộp Set-Cookie + đi theo window.location.href (tối đa 3 lần, nghỉ 300ms). */
    private suspend fun fetchWithBypass(initialUrl: String, referer: String? = null): Document {
        val headers = getBypassHeaders(referer).toMutableMap()
        var currentUrl = initialUrl
        var res = app.get(currentUrl, headers = headers)
        var html = res.text
        res.okhttpResponse.headers("Set-Cookie").takeIf { it.isNotEmpty() }?.let {
            globalCookies = mergeCookies(globalCookies, it)
            headers["Cookie"] = globalCookies
        }
        var attempts = 0
        while (html.contains("window.location.href") && attempts < 3) {
            attempts++
            val next = redirectRegex.find(html)?.groupValues?.get(1) ?: break
            currentUrl = if (next.startsWith("http")) next else URL(URL(currentUrl), next).toString()
            delay(300)
            res = app.get(currentUrl, headers = headers)
            html = res.text
            res.okhttpResponse.headers("Set-Cookie").takeIf { it.isNotEmpty() }?.let {
                globalCookies = mergeCookies(globalCookies, it)
                headers["Cookie"] = globalCookies
            }
        }
        return Jsoup.parse(html)
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        val it = url?.takeIf { u -> u.isNotBlank() } ?: return null
        if (it.startsWith("http")) return it
        if (it.startsWith("//")) return "https:$it"
        return URL(URL(baseUrl), it).toString()
    }

    private fun fixUrlWith(url: String?, base: String): String = fixUrl(url, base) ?: ""

    private fun encodeUri(s: String?): String = try {
        s?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
    } catch (_: Exception) {
        s ?: ""
    }

    private fun parseStyleBackground(style: String): String? =
        bgRegex.find(style)?.groupValues?.get(1)

    // ---------- trang chủ ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        ProviderDomainMigration.migrate(this, base)
        val isRank = request.data.contains("bang-xep-hang")
        val url = if (page > 1 && !isRank) base + request.data.removeSuffix("/") + "/trang-$page.html"
        else base + request.data

        val doc = fetchWithBypass(url, base)
        val items = doc.select("ul.bxh-movie-phimletv li.group, ul.MovieList li.TPostMv").mapNotNull { el ->
            if (el.hasClass("group")) {
                val a = el.selectFirst("h3.title-item a") ?: return@mapNotNull null
                val title = a.text().trim()
                val href = fixUrl(a.attr("href"), base) ?: return@mapNotNull null
                val thumbImg = el.selectFirst("a.thumb img")
                val poster = fixUrl(thumbImg?.attr("src") ?: el.selectFirst("img")?.attr("data-src"), base)
                newMovieSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            } else {
                toSearchResponse(el, base)
            }
        }
        val hasNext = !isRank &&
            doc.selectFirst("div.wp-pagenavi a.next, div.wp-pagenavi span.current + a") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    private fun toSearchResponse(el: Element, base: String): SearchResponse? {
        return try {
            val href = fixUrl(el.selectFirst("a")?.attr("href"), base) ?: return null
            val title = el.selectFirst("h2.Title, .Title, h1.Title")?.text()?.trim() ?: return null
            val img = el.selectFirst("div.Image img, div.TPost img, img")
            val rawPoster = img?.attr("data-src")?.let { if (it.isBlank()) img.attr("src") else it }
            val poster = fixUrl(rawPoster, base)
            val label = el.selectFirst("span.mli-eps, span.mli-quality, span.label")?.text()
            val isSeries = (label != null && label.contains("TẬP", true)) ||
                (label != null && label.contains("HOÀN TẤT", true))
            val epCount = durationRegex.find(label ?: "")?.value?.toIntOrNull()
            newAnimeSearchResponse(title, href, if (isSeries) TvType.Anime else TvType.Movie) {
                this.posterUrl = poster
                if (epCount != null) addDubStatus(DubStatus.Subbed, epCount)
                else addDubStatus(DubStatus.Subbed)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- tìm kiếm ----------

    private suspend fun searchViaSuggest(base: String, query: String): List<SearchResponse> {
        val headers = getBypassHeaders(base).toMutableMap().apply {
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "*/*")
            put("Origin", base)
        }
        val res = app.post(
            "$base/ajax/suggest",
            headers = headers,
            data = mapOf("ajaxSearch" to "1", "keysearch" to query)
        )
        val doc = Jsoup.parse(res.text)
        return doc.select("li").mapNotNull { li ->
            if (li.hasClass("ss-bottom")) return@mapNotNull null
            val thumb = li.selectFirst("a.thumb") ?: return@mapNotNull null
            val href = fixUrl(thumb.attr("href"), base) ?: return@mapNotNull null
            val title = li.selectFirst(".ss-title")?.text()?.trim() ?: return@mapNotNull null
            val poster = parseStyleBackground(thumb.attr("style"))?.let { fixUrl(it, base) }
            val statusText = li.selectFirst(".ss-info p")?.text()?.trim() ?: ""
            val isSeries = statusText.contains("TẬP", true) || statusText.contains("VietSub", true)
            newAnimeSearchResponse(title, href, if (isSeries) TvType.Anime else TvType.AnimeMovie) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, Regex("(\\d+)").find(statusText)?.value?.toIntOrNull())
            }
        }.distinctBy { it.url }
    }

    private fun containsAny(text: String, list: List<String>): Boolean = list.any { text.contains(it) }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val base = getBaseUrl()
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return emptyList()
            val suggest = searchViaSuggest(base, trimmed)
            if (suggest.isNotEmpty()) return suggest
            try {
                val tokens = Regex("[^a-z0-9]+").split(trimmed.lowercase()).filter { it.length > 1 }
                val doc = fetchWithBypass("$base/tim-kiem/${encodeUri(query)}/", base)
                doc.select("ul.MovieList li.TPostMv").mapNotNull { toSearchResponse(it, base) }
                    .filter { r ->
                        tokens.isEmpty() || containsAny(r.name.lowercase() + ' ' + r.url.lowercase(), tokens)
                    }
            } catch (_: Exception) {
                suggest
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- chi tiết phim ----------

    private fun parseEpisodes(document: Document): List<Episode> {
        val episodesMap = linkedMapOf<String, MutableList<ServerData>>()
        val epSelector = "ul.list-episode > li > a.btn-episode, div.list-episode a, .list-episode a"
        fun collect(groupName: String, epEl: Element) {
            val title = epEl.attr("title")
            val epNum = (if (title.isBlank()) epEl.text() else title).trim()
            val hash = epEl.attr("data-hash")
            if (hash.isBlank()) return
            episodesMap.getOrPut(epNum) { mutableListOf() }
                .add(ServerData(groupName, hash, epEl.attr("data-id"), epEl.attr("href")))
        }
        val serverGroups = document.select("div.server-group, li.backup-server")
        if (serverGroups.isNotEmpty()) {
            for (group in serverGroups) {
                val groupName = group.selectFirst("h3.server-name, h3.server-title, .server-name")
                    ?.text()?.trim() ?: "VIP"
                group.select(epSelector).forEach { collect(groupName, it) }
            }
        } else {
            document.select(epSelector).forEach { collect("VIP", it) }
        }
        return episodesMap.entries.map { (epName, list) ->
            val num = epNumRegex.findAll(epName).lastOrNull()?.value?.replace(',', '.')?.toFloatOrNull()
                ?: Float.MAX_VALUE
            Triple(epName, list, num)
        }.sortedBy { it.third }.map { (epName, list, _) ->
            val json = JSONArray().apply {
                list.forEach {
                    put(
                        JSONObject().put("groupName", it.groupName).put("hash", it.hash)
                            .put("id", it.id).put("href", it.href)
                    )
                }
            }.toString()
            newEpisode(json) { this.name = epName }
        }
    }

    private data class InfoMeta(
        val genres: List<String>, val year: Int?, val rating: Float?,
        val duration: Int?, val status: ShowStatus?, val country: String
    )

    private fun extractInfoMeta(doc: Document): InfoMeta {
        var genres: List<String> = emptyList()
        var year: Int? = null
        var duration: Int? = null
        var status: ShowStatus? = null
        var country = ""
        for (item in doc.select("ul.InfoList li")) {
            val text = item.text()
            val lower = text.lowercase()
            when {
                lower.contains("thể loại:") -> genres = item.select("a").map { it.text().trim() }
                lower.contains("năm:") || lower.contains("season:") ->
                    year = yearRegex.find(text)?.value?.toIntOrNull()
                lower.contains("thời lượng:") -> duration = durationRegex.find(text)?.value?.toIntOrNull()
                lower.contains("trạng thái:") -> status = when {
                    lower.contains("đang chiếu") || lower.contains("cập nhật") -> ShowStatus.Ongoing
                    lower.contains("hoàn thành") || lower.contains("full") -> ShowStatus.Completed
                    else -> null
                }
                lower.contains("quốc gia:") -> country = item.select("a").text().lowercase()
            }
        }
        val rating = doc.selectFirst("div#star")?.attr("data-score")?.toFloatOrNull()
        return InfoMeta(genres, year, rating, duration, status, country)
    }

    private fun extractPosterUrl(doc: Document, base: String): String? {
        val fig = doc.selectFirst("div.TPost.Single div.Image figure.Objf img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
        val meta = doc.select("meta[property=og:image], meta[itemprop=image]")
            .map { it.attr("content") }.firstOrNull { it.isNotBlank() }
        return fixUrl(fig ?: meta, base)
    }

    private fun extractBannerUrl(doc: Document, base: String, fallback: String?): String? {
        val banner = doc.selectFirst("div.TPostBg.Objf img.TPostBg")?.attr("src")
        return if (banner.isNullOrBlank() || banner.contains("default/images/bg", true)) fallback
        else fixUrl(banner, base)
    }

    private fun extractRecommendations(doc: Document, base: String): List<SearchResponse> =
        doc.select(
            "div.MovieListRelated .TPostMv, section#single-home .TPostMv, div.Wdgt div.MovieListRelated .TPostMv"
        ).mapNotNull { toSearchResponse(it, base) }

    override suspend fun load(url: String): LoadResponse? {
        val base = getBaseUrl()
        val infoUrl = normalizeSiteUrl(url, base).removeSuffix("/xem-phim.html")
        val watchUrl = if (infoUrl.contains("xem-phim.html")) infoUrl else infoUrl.removeSuffix("/") + "/xem-phim.html"

        val (infoDoc, watchDoc) = coroutineScope {
            val i = async { try { fetchWithBypass(infoUrl, base) } catch (_: Exception) { null } }
            val w = async { try { fetchWithBypass(watchUrl, base) } catch (_: Exception) { null } }
            i.await() to w.await()
        }
        val doc = infoDoc ?: watchDoc ?: return null
        return try {
            val meta = extractInfoMeta(doc)
            val watch = watchDoc ?: doc
            val title = doc.selectFirst("h1.Title, h1, .Title, header h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title], meta[name=title]")?.attr("content")
                    ?.substringBefore("|")?.trim()
                ?: return null
            val poster = extractPosterUrl(doc, base)
            val banner = extractBannerUrl(doc, base, poster)
            val plot = doc.selectFirst("div.Description")?.text()?.trim()
            val actors = doc.select("div#MvTb-Cast ul.ListCast li a, div.Cast-list li a").mapNotNull { a ->
                val name = a.attr("title").removePrefix("Nhân vật ").trim()
                if (name.isBlank()) null
                else ActorData(Actor(name, fixUrl(a.selectFirst("img")?.attr("src"), base)))
            }
            val recs = extractRecommendations(doc, base)
            val episodes = watchDoc?.let { parseEpisodes(it) } ?: emptyList()
            runCatching { ProviderDomainMigration.repairShow(this, url, infoUrl, episodes.size) }
            val isUpcoming = meta.genres.any { it.equals("Anime sắp chiếu", true) }
            val type = when {
                title.contains("movie", true) || title.contains("phim lẻ", true) -> TvType.AnimeMovie
                meta.country.contains("nhật bản") -> TvType.Anime
                meta.genres.any { it.contains("Anime", true) } -> TvType.Anime
                meta.genres.any { it.contains("hoạt hình", true) } -> TvType.Cartoon
                else -> TvType.Anime
            }
            if (isUpcoming) {
                newAnimeLoadResponse(title, infoUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner
                    this.plot = plot
                    this.tags = meta.genres
                    this.year = meta.year
                    this.score = meta.rating?.let { Score.from10(it) }
                    this.actors = actors
                    this.recommendations = recs
                    this.comingSoon = true
                }
            } else {
                newAnimeLoadResponse(title, infoUrl, type) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner
                    this.plot = plot
                    this.tags = meta.genres
                    this.year = meta.year
                    this.score = meta.rating?.let { Score.from10(it) }
                    this.actors = actors
                    this.recommendations = recs
                    meta.duration?.let { this.duration = it }
                    this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                    meta.status?.let { this.showStatus = it }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- lấy link phát ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = getBaseUrl()
        val servers = ArrayList<ServerData>()
        try {
            val arr = JSONArray(data)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                servers.add(
                    ServerData(
                        o.optString("groupName", "VIP"), o.optString("hash", ""),
                        o.optString("id", ""), o.optString("href", "")
                    )
                )
            }
        } catch (_: Exception) {
            // data không phải JSON -> là URL trang xem phim: lấy server của tập đang active
            try {
                val realData = normalizeSiteUrl(data, base)
                val doc = fetchWithBypass(realData, base)
                doc.select("a.btn-episode.active, a.episode-link.active, .list-episode a.active").forEach { el ->
                    val hash = el.attr("data-hash")
                    val id = el.attr("data-id")
                    val groupName = el.closest(".server-group")
                        ?.selectFirst("h3.server-name, h3.server-title, .server-name")
                        ?.text()?.trim() ?: "VIP"
                    if (hash.isNotBlank()) servers.add(ServerData(groupName, hash, id, realData))
                }
            } catch (_: Exception) {
                return false
            }
        }
        if (servers.isEmpty()) return false

        coroutineScope {
            servers.map { target ->
                async { loadServer(target, base, callback) }
            }.awaitAll()
        }
        return true
    }

    private suspend fun loadServer(target: ServerData, base: String, callback: (ExtractorLink) -> Unit) {
        try {
            val ajaxHeaders = getBypassHeaders(base).toMutableMap().apply {
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                put("X-Requested-With", "XMLHttpRequest")
            }
            val res = app.post(
                "$base/ajax/player",
                headers = ajaxHeaders,
                data = mapOf("link" to target.hash, "id" to target.id)
            )
            val json = try { JSONObject(res.text) } catch (_: Exception) { null } ?: return
            val link = json.optString("link")
            val playTech = json.optString("playTech")
            if (link.isNullOrBlank()) return

            if (playTech == "iframe" && link.contains("googleapiscdn.com")) {
                val playlist = decryptGoogleApisCdn(link)
                if (playlist != null) {
                    decryptedPlaylists[playlist.url] = playlist.text
                    runCatching { cfWarmupUrl[URL(playlist.url).host] = link }
                    runCatching {
                        cfWarmupHash.set(Regex("/playlist/([0-9a-f]+)/").find(playlist.url)?.groupValues?.get(1))
                    }
                    println("AVS: emit playlist CDN ${playlist.url.substringBefore("?")} (${playlist.text.length} bytes)")
                    callback(
                        newExtractorLink("AnimeVietsub", target.groupName, playlist.url, ExtractorLinkType.M3U8) {
                            quality = Qualities.Unknown.value
                            referer = "https://stream.googleapiscdn.com/"
                            headers = getBypassHeaders("https://stream.googleapiscdn.com/")
                        }
                    )
                } else {
                    println("AVS: không lấy được playlist cho ${target.groupName}")
                }
            } else if (link.startsWith("http")) {
                callback(
                    newExtractorLink(
                        "AnimeVietsub", target.groupName, link,
                        if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { quality = Qualities.Unknown.value }
                )
            }
        } catch (_: Exception) {
        }
    }

    // ---------- lấy & giải mã playlist ----------

    private fun b64UrlDecode(str: String): ByteArray {
        var s = str.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += '='
        return Base64.decode(s, 0)
    }

    private fun lcgNext(state: Int): Int =
        ((state.toLong() * 1664525L + 1013904223L) and 0xFFFFFFFFL).toInt()

    private fun deriveSeed(sk: String): Int {
        val sub = sk.substring(0, minOf(AVS_TS_SYNC_CHAIN, sk.length))
        val hex = sub.takeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return if (hex.isNotEmpty()) java.lang.Long.parseLong(hex, 16).toInt() else 0
    }

    private fun stringUnshuffle(str: String, seed: String): String {
        val chars = str.toCharArray()
        var state = deriveSeed(seed)
        val swaps = ArrayList<Pair<Int, Int>>()
        for (i in chars.size - 1 downTo 1) {
            state = lcgNext(state)
            val j = ((state.toLong() and 0xFFFFFFFFL) % (i + 1).toLong()).toInt()
            swaps.add(i to j)
        }
        for (k in swaps.indices.reversed()) {
            val (a, b) = swaps[k]
            val t = chars[a]; chars[a] = chars[b]; chars[b] = t
        }
        return String(chars)
    }

    private fun createPrng(seed: String): () -> Long {
        var hash = 2166136261L
        for (ch in seed) hash = (16777619L * ((ch.code.toLong() xor hash) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
        var state = hash.toInt()
        if (state == 0) state = 1
        return {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toLong() and 0xFFFFFFFFL
        }
    }

    private fun descramble(data: ByteArray, permKey: String, permSalt: String): ByteArray {
        val len = data.size
        if (len == 0) return ByteArray(0)
        val prng = createPrng("$permKey|$permSalt")
        val perm = IntArray(len) { it }
        for (i in len - 1 downTo 1) {
            val j = (prng() % (i + 1).toLong()).toInt()
            val t = perm[i]; perm[i] = perm[j]; perm[j] = t
        }
        val out = ByteArray(len)
        var xorState = 0L
        for (i in 0 until len) {
            if ((i and 3) == 0) xorState = prng()
            out[perm[i]] = (data[i].toInt() xor ((xorState ushr ((i and 3) * AVS_TS_SYNC_CHAIN)) and 255L).toInt()).toByte()
        }
        return out
    }

    /** Envelope: "USDK" 0x01, độ dài payload (2 byte), payload url-encoded JSON, 4 byte đuôi. */
    private fun parseEnvelope(envB64: String): JSONObject? = try {
        val b = b64UrlDecode(envB64)
        if (b.size < 11 || b[0].toInt() != 85 || b[1].toInt() != 83 || b[2].toInt() != 68 ||
            b[3].toInt() != 75 || b[4].toInt() != 1
        ) null
        else {
            val payloadLen = ((b[5].toInt() and 255) shl 8) or (b[6].toInt() and 255)
            if (b.size < payloadLen + 7 + 4) null
            else {
                val payload = b.copyOfRange(7, payloadLen + 7)
                JSONObject(URLDecoder.decode(String(payload, Charsets.ISO_8859_1), "UTF-8"))
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun extractAvsToken(html: String): String? {
        val decl = avsTokenDeclRegex.find(html)?.groupValues?.get(1)
        if (decl != null) {
            val joined = avsTokenChunkRegex.findAll(decl).joinToString("") { it.groupValues[1] }
            if (joined.isNotBlank()) return joined
        }
        return avsTokenSimpleRegex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private suspend fun decryptGoogleApisCdn(playerUrl: String): PlaylistResult? {
        val videoHash = Regex("/player/([0-9a-f]+)").find(playerUrl)?.groupValues?.get(1) ?: return null
        val cdnBase = Regex("^(https?://[^/]+)").find(playerUrl)?.groupValues?.get(1) ?: return null
        var attempt = 1
        while (attempt < 4) {
            var token: String? = null
            try {
                val res = app.get(playerUrl, headers = getBypassHeaders(playerUrl))
                val html = res.text
                token = extractAvsToken(html)
                if (token == null) {
                    println("AVS: player page không có avsToken (lần $attempt, HTTP ${res.code}, html ${html.length} bytes)")
                } else {
                    println("AVS: token ok (lần $attempt, len=${token.length}, html ${html.length} bytes)")
                }
            } catch (e: Exception) {
                println("AVS: lấy token lần $attempt lỗi: ${e.message}")
            }
            if (token != null) {
                try {
                    val m3u8Url = "$cdnBase/playlist/$videoHash/playlist.m3u8?token=" + URLEncoder.encode(token, "UTF-8")
                    val decrypted = fetchDecryptPlaylist(m3u8Url, token, playerUrl)
                    if (!decrypted.isNullOrBlank()) return PlaylistResult(m3u8Url, decrypted)
                } catch (e: Exception) {
                    println("AVS: lần $attempt lỗi: ${e.message}")
                }
            }
            delay(700)
            attempt++
        }
        return null
    }

    private suspend fun fetchDecryptPlaylist(playlistUrl: String, avsToken: String, referer: String? = null): String? {
        // Giữ đúng thứ tự gốc: header mặc định (không có Referer) ghi đè lên {Referer: referer}
        val headers = if (referer != null) mapOf("Referer" to referer) + getBypassHeaders(null) else getBypassHeaders(null)
        val res = app.get(playlistUrl, headers = headers)
        val text = res.text
        println("AVS: playlist HTTP ${res.code}, ${text.length} bytes")
        val result = processEncryptedM3u8(text, res.okhttpResponse.headers, avsToken)
        if (result.isNullOrBlank()) {
            println("AVS: decrypt FAILED")
        } else {
            val segs = result.split("\n").count { it.trim().startsWith("http") }
            println("AVS: decrypt OK, $segs segments")
        }
        return result
    }

    private fun processEncryptedM3u8(m3u8Text: String, headers: Headers, avsToken: String): String? {
        val jwt = avsToken.split(".")
        if (jwt.size < 2) return null
        val payloadJson = String(b64UrlDecode(jwt[1]), Charsets.UTF_8)
        val jti = JSONObject(payloadJson).optString("jti", "") ?: ""
        if (jti.isEmpty()) return null
        val jtiOdd = buildString { jti.forEachIndexed { i, c -> if (i % 2 == 1) append(c) } }

        val envHeader = headers["x-envelope"] ?: headers["x-avs-envelope"] ?: headers["x-stream-envelope"] ?: ""
        var sk = ""
        var ts = "0"
        var uid = "anon"
        var cn = ""
        if (envHeader.isNotEmpty()) {
            parseEnvelope(envHeader)?.let {
                cn = it.optString("cn", "")
                sk = it.optString("sk", "")
                ts = it.optString("ts", "0")
                uid = it.optString("uid", "anon")
            }
        }
        if (cn.isEmpty()) cn = headers["x-edge-tag"] ?: ""
        if (sk.isEmpty()) sk = headers["x-cache-node"] ?: ""
        if (ts == "0") ts = headers["x-request-trace"] ?: "0"
        if (uid == "anon") {
            headers["x-proxy-digest"]?.let { pd -> uid = try { URLDecoder.decode(pd, "UTF-8") } catch (_: Exception) { uid } }
        }
        if (cn.isEmpty() || sk.isEmpty()) return null

        val headerLines = ArrayList<String>()
        val tValues = ArrayList<String>()
        val tRegex = Regex("[?&]_t=([^&\\s]+)")
        for (line in m3u8Text.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) {
                if (!line.startsWith("#EXTINF:") && !line.startsWith("#EXT-X-ENDLIST") && !line.startsWith("#EXT-X-KEY")) {
                    headerLines.add(line)
                }
            } else {
                tRegex.find(line)?.let { tValues.add(it.groupValues[1]) }
            }
        }
        if (tValues.isEmpty()) return null
        val concatenated = tValues.joinToString("")
        val cnBytes = b64UrlDecode(cn)
        val iv = cnBytes.copyOfRange(0, minOf(12, cnBytes.size))
        val skFinal = sk
        val unshuffleMethods = listOf<(String) -> String>({ stringUnshuffle(it, skFinal) }, { it })
        val hmacFormats = listOf("$uid:$ts:$sk:0", "$uid:$ts:$sk")

        for (unshuffle in unshuffleMethods) {
            for (hmacData in hmacFormats) {
                try {
                    val blob = b64UrlDecode(unshuffle(concatenated))
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(cnBytes, "HmacSHA256"))
                    val gcmKey = mac.doFinal(hmacData.toByteArray(Charsets.UTF_8))
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(gcmKey, "AES"), GCMParameterSpec(128, iv))
                    val raw = cipher.doFinal(blob)
                    var body = String(raw, Charsets.UTF_8)
                    if (!body.contains("#EXTINF") && !body.contains("/hls/")) {
                        body = String(descramble(raw, sk, ts), Charsets.UTF_8)
                    }
                    var full = headerLines.joinToString("\n") + "\n" + body
                    if (!full.contains("#EXT-X-ENDLIST")) full += "\n#EXT-X-ENDLIST"
                    if (Regex("/hls/[0-9a-f]{24}\\.ts\\?e=").containsMatchIn(full)) {
                        return decryptM3u8SegmentUrls(full, jtiOdd)
                    }
                    if (body.contains("#EXTINF")) return full
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    /** Giải mã URL segment (tham số e=, i=) bằng AES-CTR với khoá HMAC(jtiOdd, "url-cipher|<fileId>"). */
    private fun decryptM3u8SegmentUrls(intermediateM3u8: String, jtiOdd: String): String {
        val lines = intermediateM3u8.split("\n")
        val hlsRe = Regex("/hls/([0-9a-f]{24})\\.ts")
        val out = lines.toMutableList()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#") || line.isEmpty()) continue
            val m = hlsRe.find(line) ?: continue
            try {
                val fileId = m.groupValues[1]
                val qIdx = line.indexOf('?')
                val params = linkedMapOf<String, String>()
                if (qIdx >= 0) {
                    line.substring(qIdx + 1).split("&").forEach { p ->
                        val eq = p.indexOf('=')
                        if (eq >= 0) params[p.substring(0, eq)] = p.substring(eq + 1)
                    }
                }
                val e = params["e"] ?: continue
                val idx = params["i"]?.toIntOrNull() ?: 0
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(jtiOdd.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                val aesKey = mac.doFinal("url-cipher|$fileId".toByteArray(Charsets.UTF_8))
                val counter = ByteArray(16)
                counter[12] = ((idx shr 24) and 255).toByte()
                counter[13] = ((idx shr 16) and 255).toByte()
                counter[14] = ((idx shr 8) and 255).toByte()
                counter[15] = (idx and 255).toByte()
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(counter))
                val url = String(cipher.doFinal(b64UrlDecode(e)), Charsets.UTF_8)
                if (url.startsWith("http")) out[i] = url
            } catch (_: Exception) {
            }
        }
        return out.filter { !(it.contains("urn:avs:shield") || Regex("/hls/[0-9a-f]{24}\\.ts").containsMatchIn(it)) }
            .joinToString("\n")
    }

    // ---------- giải Cloudflare bằng WebView ----------

    private suspend fun evalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            try {
                webView.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * Mở WebView thật để Cloudflare cấp cf_clearance, rồi dùng fetch() trong WebView lấy byte của [url].
     * Pha A: nạp lần lượt các trang warm-up, đợi tối đa 20s mỗi trang cho tới khi có cookie cf_clearance.
     * Pha B: loadDataWithBaseURL(origin) để né service worker, chạy fetch(url) và đọc window.__avsKey
     * ("<status>|<base64>") tối đa 10 lần, cách nhau 1,5s.
     */
    private fun fetchViaWebView(url: String, host: String, hash: String?): ByteArray? = runBlocking {
        val ctx = CloudStreamApp.context ?: return@runBlocking null
        var result: ByteArray? = null
        var webView: WebView? = null
        val origin = "https://$host"
        val warmups = ArrayList<String>()
        if (!hash.isNullOrBlank()) warmups.add("$origin/status/$hash")
        warmups.add("$origin/api/canary")
        warmups.add("$origin/")
        cfWarmupUrl[host]?.let { warmups.add(it) }
        try {
            withContext(Dispatchers.Main) {
                val wv = WebView(ctx)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = (wv.settings.userAgentString ?: "")
                    .replace("; wv", "").replace("Version/4.0 ", "")
                cfUa.set(wv.settings.userAgentString)
                wv.webChromeClient = WebChromeClient()
                wv.webViewClient = WebViewClient()
                println("AVS-CF: UA=${wv.settings.userAgentString}")

                // Pha A: warm-up đến khi có cf_clearance
                var hasCf = false
                for (w in warmups) {
                    if (hasCf) break
                    println("AVS-CF: warm-up $w")
                    runCatching { wv.loadUrl(w) }
                    var t = 0
                    while (t < 20000) {
                        delay(1500)
                        t += 1500
                        val cookies = runCatching { CookieManager.getInstance().getCookie("$origin/") }.getOrNull() ?: ""
                        hasCf = cookies.contains("cf_clearance")
                        if (hasCf) cfCookies[host] = cookies
                        val title = runCatching { evalJs(wv, "document.title") }.getOrNull()
                        println("AVS-CF: warm-up ${t / 1000}s cf=$hasCf title=${title?.take(40)}")
                        if (hasCf) break
                    }
                }

                // Pha B: fetch(url) trong WebView
                val startFetch = "(async()=>{try{const r=await fetch(" + JSONObject.quote(url) +
                    ",{credentials:'include',cache:'no-store'});const b=new Uint8Array(await r.arrayBuffer());" +
                    "let s='';for(let i=0;i<b.length;i++)s+=String.fromCharCode(b[i]);" +
                    "window.__avsKey=r.status+'|'+btoa(s);}catch(e){window.__avsKey='ERR|'+(e&&e.message||e);}})();0"
                println("AVS-CF: pha B — loadDataWithBaseURL($origin) để né service worker")
                runCatching {
                    wv.loadDataWithBaseURL(
                        "$origin/", "<html><head><meta charset='utf-8'></head><body>avs</body></html>",
                        "text/html", "utf-8", null
                    )
                }
                delay(1200)
                var n = 0
                while (n < 10 && result == null) {
                    n++
                    runCatching { evalJs(wv, startFetch) }
                    delay(1500)
                    val v = runCatching { evalJs(wv, "window.__avsKey||''") }.getOrNull()
                    val value = v?.trim('"') ?: ""
                    println("AVS-CF: phaB js[$n] -> ${value.take(24)}")
                    if (value.startsWith("200|")) {
                        val decoded = runCatching { Base64.decode(value.substringAfter("|"), 0) }.getOrNull()
                        if (decoded != null && decoded.isNotEmpty()) {
                            result = decoded
                            println("AVS-CF: OK, ${decoded.size} byte lấy từ WebView (pha B)")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            println("AVS-CF: lỗi WebView: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            runCatching { webView?.destroy() }
        }
        result
    }

    // ---------- interceptor phát video ----------

    private fun syntheticBytes(request: Request, bytes: ByteArray): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", bytes.size.toString())
            .body(bytes.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
            .build()

    /** Segment bị bọc trong "vỏ PNG": tìm offset đầu tiên mà byte 0x47 lặp 8 lần cách nhau 188 byte. */
    private fun findAvsPngPrefixLength(body: ResponseBody): Long {
        val source = body.source()
        source.request(AVS_PROBE_BYTES)
        val head = source.buffer.clone().readByteArray()
        if (head.size < 4) return 0
        if (head[0].toInt() != -119 || head[1].toInt() != 80 || head[2].toInt() != 78 || head[3].toInt() != AVS_TS_SYNC) return 0
        var i = 0
        while (i < head.size && i <= AVS_MAX_PREFIX) {
            if (head[i].toInt() == AVS_TS_SYNC) {
                var ok = true
                for (k in 1 until AVS_TS_SYNC_CHAIN) {
                    val idx = k * AVS_TS_PACKET + i
                    if (idx >= head.size || head[idx].toInt() != AVS_TS_SYNC) { ok = false; break }
                }
                if (ok) return i.toLong()
            }
            i++
        }
        return 0
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val segmentRegex = Regex("^https?://[^/]*googleusercontent\\.com/", RegexOption.IGNORE_CASE)
        val provider = this
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val host = request.url.host.lowercase()

            // 1) playlist trên googleapiscdn.com: trả bản đã giải mã từ cache (hoặc giải mã tại chỗ)
            if (host.endsWith("googleapiscdn.com") && request.url.encodedPath.startsWith("/playlist/")) {
                var text = decryptedPlaylists[url]
                    ?: decryptedPlaylists.entries.firstOrNull { it.key.substringBefore("?") == url.substringBefore("?") }?.value
                if (text == null) {
                    text = runCatching {
                        val token = request.url.queryParameter("token") ?: ""
                        if (token.isEmpty()) null
                        else runBlocking { provider.fetchDecryptPlaylist(url, token) }
                    }.getOrNull()
                    if (text != null) decryptedPlaylists[url] = text
                }
                if (!text.isNullOrBlank()) {
                    println("AVS: interceptor trả playlist đã giải mã (${text.length} bytes)")
                    return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .header("Content-Type", "application/vnd.apple.mpegurl")
                        .body(text.toResponseBody("application/vnd.apple.mpegurl".toMediaTypeOrNull()))
                        .build()
                }
                println("AVS: interceptor không có bản giải mã cho $url")
            }

            // 2) segment trên googleusercontent.com: cắt vỏ PNG
            if (host != "googleapiscdn.com" && !host.endsWith(".googleapiscdn.com")) {
                if (!segmentRegex.containsMatchIn(url)) return@Interceptor chain.proceed(request)
                val range = request.header("Range")
                if (range != null && !Regex("^\\s*bytes\\s*=\\s*0\\s*-").containsMatchIn(range)) {
                    return@Interceptor chain.proceed(request)
                }
                val response = chain.proceed(request.newBuilder().removeHeader("Range").removeHeader("If-Range").build())
                val body = response.body ?: return@Interceptor response
                try {
                    val originalLength = body.contentLength()
                    if (originalLength <= 0) return@Interceptor response
                    val prefix = provider.findAvsPngPrefixLength(body)
                    if (prefix <= 0 || prefix >= originalLength) return@Interceptor response
                    val payload = originalLength - prefix
                    val src: BufferedSource = body.source()
                    val skipped = object : ForwardingSource(src) {
                        private var remaining = prefix
                        private val skipBuf = Buffer()
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            while (remaining > 0) {
                                val n = super.read(skipBuf, minOf(remaining, byteCount))
                                if (n == -1L) return -1L
                                remaining -= n
                                skipBuf.clear()
                            }
                            return super.read(sink, byteCount)
                        }
                    }
                    val newBody = object : ResponseBody() {
                        override fun contentType(): MediaType? = body.contentType()
                        override fun contentLength(): Long = payload
                        override fun source(): BufferedSource = (skipped as Source).buffer()
                    }
                    if (!avsStripLoggedOnce) {
                        avsStripLoggedOnce = true
                        println("AVS: cắt vỏ PNG $prefix byte / segment $payload byte")
                    }
                    return@Interceptor response.newBuilder()
                        .removeHeader("Content-Range").removeHeader("Content-Length")
                        .header("Content-Length", payload.toString())
                        .body(newBody).build()
                } catch (e: Exception) {
                    println("AVS: cắt vỏ PNG lỗi: ${e.message}")
                    return@Interceptor response
                }
            }

            // 3) các request còn lại tới googleapiscdn.com (key, segment...)
            cfGotBytes[url]?.let { return@Interceptor provider.syntheticBytes(request, it) }
            val failedAt = cfFailedAt[url] ?: 0L
            if (System.currentTimeMillis() - failedAt < 60000) return@Interceptor chain.proceed(request)
            val cookie = cfCookies[host]
            val direct = if (cookie.isNullOrBlank()) chain.proceed(request)
            else chain.proceed(
                request.newBuilder().header("Cookie", cookie)
                    .header("User-Agent", cfUa.get() ?: request.header("User-Agent") ?: "").build()
            )
            if (direct.code != 403 && direct.code != 503) return@Interceptor direct
            try {
                runCatching {
                    val bodyText = direct.body?.string() ?: ""
                    println(
                        "AVS-CF: ${direct.code} chi tiết — cf-mitigated=${direct.header("cf-mitigated")}, " +
                            "server=${direct.header("Server")}, body=${bodyText.length} bytes"
                    )
                }
                direct.close()
                println("AVS-CF: 403 challenge tại $url → nhờ WebView giải")
                val bytes = provider.fetchViaWebView(url, host, cfWarmupHash.get())
                if (bytes != null && bytes.isNotEmpty()) {
                    cfGotBytes[url] = bytes
                    return@Interceptor provider.syntheticBytes(request, bytes)
                }
                val solved = cfCookies[host]
                if (!solved.isNullOrBlank()) {
                    println("AVS-CF: thử lại OkHttp bằng cf_clearance vừa lấy")
                    val retry = chain.proceed(
                        request.newBuilder().header("Cookie", solved)
                            .header("User-Agent", cfUa.get() ?: request.header("User-Agent") ?: "")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Upgrade-Insecure-Requests", "1")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Dest", "document")
                            .header("Sec-Fetch-Site", "same-origin")
                            .build()
                    )
                    println("AVS-CF: OkHttp+cookie -> HTTP ${retry.code}")
                    if (retry.code != 403 && retry.code != 503) return@Interceptor retry
                    retry.close()
                }
                println("AVS-CF: WebView không lấy được key (xem log AVS-CF phía trên)")
                cfFailedAt[url] = System.currentTimeMillis()
                chain.proceed(request)
            } catch (t: Throwable) {
                println("AVS-CF: lỗi bất ngờ: ${t.javaClass.simpleName} ${t.message}")
                chain.proceed(request)
            }
        }
    }
}
