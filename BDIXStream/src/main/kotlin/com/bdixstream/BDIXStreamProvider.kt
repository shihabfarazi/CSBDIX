package com.bdixstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BDIXStreamProvider : MainAPI() {
    override var mainUrl = "http://172.16.50.14"
    override var name = "BDIX Stream"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary
    )

    data class Category(val title: String, val url: String, val kind: String = "Movie")

    data class Entry(
        val title: String,
        val url: String,
        val isDirectory: Boolean,
        val poster: String? = null,
        val video: String? = null,
        val kind: String = "Movie"
    )

    val categories = listOf(
        Category("English Movies", "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/"),
        Category("English Movies 1080p", "http://172.16.50.14/DHAKA-FLIX-14/English%20Movies%20%281080p%29/"),
        Category("IMDb Top 250 Movies", "http://172.16.50.14/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/"),
        Category("Hindi Movies", "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/"),
        Category("South Indian Movies — Hindi Dubbed", "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/"),
        Category("South Indian Movies", "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/South%20Movies/"),
        Category("Foreign Language Movies", "http://172.16.50.7/DHAKA-FLIX-7/Foreign%20Language%20Movies/"),
        Category("Kolkata Bangla Movies", "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/"),
        Category("Satyajit Ray Films", "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/Satyajit%20Ray%20Films/"),
        Category("TV & Web Series", "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/", "TvSeries"),
        Category("Korean TV & Web Series", "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/", "TvSeries"),
        Category("Anime & Cartoon TV Series", "http://172.16.50.10/DHAKA-FLIX-10/Anime%20%26%20Cartoon%20TV%20Series/", "Anime"),
        Category("Animation Movies", "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/"),
        Category("Animation Movies 1080p", "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/"),
        Category("3D Movies", "http://172.16.50.7/DHAKA-FLIX-7/3D%20Movies/"),
        Category("Documentary", "http://172.16.50.10/DHAKA-FLIX-10/Documentary/", "Documentary"),
        Category("WWE & AEW Wrestling", "http://172.16.50.10/DHAKA-FLIX-10/WWE%20%26%20AEW%20Wrestling/", "TvSeries"),
        Category("Awards & TV Shows", "http://172.16.50.10/DHAKA-FLIX-10/Awards%20%26%20TV%20Shows/", "TvSeries")
    )

    private val directoryCache = ConcurrentHashMap<String, List<Entry>>()
    private val searchCache = ConcurrentHashMap<String, List<Entry>>()

    override val mainPage = mainPageOf(
        *categories.mapIndexed { i, c -> Pair("cat:$i", c.title) }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val index = request.data.removePrefix("cat:").toIntOrNull()
            ?: return newHomePageResponse(request.name, emptyList())
        if (index !in categories.indices) return newHomePageResponse(request.name, emptyList())
        val category = categories[index]
        
        val entries = fetchCategoryMedia(category)
        return newHomePageResponse(
            request.name,
            entries.take(50).map { it.asSearchResponse(category.kind) }
        )
    }

    private suspend fun fetchCategoryMedia(category: Category): List<Entry> {
        val topEntries = readDirectory(category.url, category.kind)
        val yearFolders = topEntries.filter { it.isDirectory && isYearFolder(it.title) }
            .sortedByDescending { extractYear(it.title) ?: 0 }

        val rawEntries = if (yearFolders.isNotEmpty()) {
            val result = mutableListOf<Entry>()
            for (yf in yearFolders.take(3)) {
                val media = try { readDirectory(yf.url, category.kind) } catch (_: Exception) { emptyList() }
                result.addAll(media.filter { it.isDirectory || it.video != null })
                if (result.size >= 50) break
            }
            result
        } else {
            topEntries.filter { it.isDirectory || it.video != null }
        }

        // Concurrently fetch posters for movie folders
        return coroutineScope {
            rawEntries.take(40).map { entry ->
                async {
                    if (entry.isDirectory && entry.poster == null) {
                        val subEntries = try { readDirectory(entry.url, entry.kind) } catch (_: Exception) { emptyList() }
                        val poster = subEntries.firstNotNullOfOrNull { it.poster }
                            ?: subEntries.firstOrNull { it.video != null }?.poster
                        val video = subEntries.firstOrNull { it.video != null }?.video
                        entry.copy(poster = poster, video = video ?: entry.video)
                    } else {
                        entry
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val key = q.lowercase(Locale.ROOT)
        searchCache[key]?.let { return it.map { e -> e.asSearchResponse(e.kind) } }

        val matches = mutableListOf<Entry>()
        val queue = ArrayDeque<Pair<Category, String>>()
        categories.forEach { queue.add(it to it.url) }
        val visited = HashSet<String>()
        var pages = 0

        while (queue.isNotEmpty() && pages < 250 && matches.size < 150) {
            val (category, url) = queue.removeFirst()
            if (!visited.add(url)) continue
            val entries = try { readDirectory(url, category.kind) } catch (_: Exception) { emptyList() }
            pages++
            for (entry in entries) {
                if (entry.title.contains(q, true) && (entry.isDirectory || entry.video != null)) {
                    matches.add(entry.copy(kind = category.kind))
                }
                if (entry.isDirectory && depth(entry.url) < 12 && queue.size < 1500) {
                    queue.add(category to entry.url)
                }
                if (matches.size >= 150) break
            }
        }
        val result = matches.distinctBy { it.url }.take(150)
        searchCache[key] = result
        return result.map { it.asSearchResponse(it.kind) }
    }

    override suspend fun load(url: String): LoadResponse {
        val kind = categories.firstOrNull { url.startsWith(it.url) }?.kind ?: inferKind(url)
        val title = displayName(url)
        val entries = readDirectory(url, kind)
        val poster = entries.firstNotNullOfOrNull { it.poster }
        val year = extractYear(title)

        if (kind == "TvSeries" || kind == "Anime") {
            val episodes = collectEpisodes(url, entries, poster)
            if (episodes.isNotEmpty()) {
                val tvType = if (kind == "Anime") TvType.Anime else TvType.TvSeries
                return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = "BDIX $kind library."
                }
            }
        }

        val video = entries.firstOrNull { it.video != null }?.video
        if (video != null) {
            return newMovieLoadResponse(title, url, TvType.Movie, video) {
                this.posterUrl = poster
                this.year = year
                this.plot = "Direct video from BDIX library."
            }
        }

        val episodes = collectEpisodes(url, entries, poster)
        if (episodes.isNotEmpty() && (kind == "TvSeries" || kind == "Anime")) {
            val tvType = if (kind == "Anime") TvType.Anime else TvType.TvSeries
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = "BDIX $kind library."
            }
        }

        val children = entries.filter { it.isDirectory }.take(100).map { it.asSearchResponse(kind) }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = "Folder in BDIX library. Choose a subfolder below to browse."
            this.recommendations = children
        }
    }

    private suspend fun collectEpisodes(
        baseUrl: String,
        initialEntries: List<Entry>,
        defaultPoster: String?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val queue = ArrayDeque<Pair<String, List<Entry>>>()
        queue.add(baseUrl to initialEntries)

        val visited = HashSet<String>()
        var count = 0

        while (queue.isNotEmpty() && count < 60) {
            val (dirUrl, entries) = queue.removeFirst()
            if (!visited.add(dirUrl)) continue
            count++

            val videoEntries = entries.filter { it.video != null }
            val imagePoster = entries.firstNotNullOfOrNull { it.poster } ?: defaultPoster

            for (e in videoEntries) {
                val vUrl = e.video ?: continue
                val epTitle = displayName(vUrl)
                val sNum = parseSeason(vUrl) ?: parseSeason(dirUrl) ?: 1
                val eNum = parseEpisode(vUrl) ?: (episodes.size + 1)

                episodes.add(
                    newEpisode(vUrl) {
                        this.name = epTitle
                        this.season = sNum
                        this.episode = eNum
                        this.posterUrl = imagePoster
                    }
                )
            }

            for (sub in entries.filter { it.isDirectory }) {
                if (depth(sub.url) <= depth(baseUrl) + 4) {
                    val subEntries = try { readDirectory(sub.url, "TvSeries") } catch (_: Exception) { emptyList() }
                    queue.add(sub.url to subEntries)
                }
            }
        }

        return episodes.distinctBy { it.data }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = if (isVideo(data)) data else {
            readDirectory(data, inferKind(data)).firstOrNull { it.video != null }?.video
        } ?: return false

        val title = displayName(videoUrl)
        val parentDir = parent(videoUrl)
        @Suppress("DEPRECATION", "DEPRECATION_ERROR")
        callback(
            ExtractorLink(
                source = name,
                name = title.ifBlank { name },
                url = videoUrl,
                referer = parentDir,
                quality = qualityFrom(videoUrl)
            )
        )
        return true
    }

    private suspend fun readDirectory(url: String, kind: String): List<Entry> {
        directoryCache[url]?.let { return it }
        val doc = try { app.get(url, timeout = 20).document } catch (_: Exception) { return emptyList() }
        val links = doc.select("a[href]").mapNotNull { a ->
            val label = a.text().trim()
            val href = a.attr("href").trim()
            if (label.isBlank() || href.isBlank() || href.startsWith("?") || href.startsWith("#") ||
                label.equals("Parent Directory", true) || label in setOf("Name", "Last modified", "Size", "Description")) {
                null
            } else {
                val absolute = try { URI(url).resolve(href).toString() } catch (_: Exception) { return@mapNotNull null }
                if (absolute.startsWith("http://172.16.50.") || absolute.startsWith("https://172.16.50.")) label to absolute else null
            }
        }

        val dirs = links.filter { it.second.endsWith("/") }
        val videos = links.filter { isVideo(it.second) }
        val images = links.filter { isImage(it.second) }
        val posterUrl = images.firstOrNull()?.second

        val output = mutableListOf<Entry>()

        dirs.forEach { (label, href) ->
            output.add(Entry(title = clean(label), url = href, isDirectory = true, poster = posterUrl, kind = kind))
        }

        videos.forEach { (label, href) ->
            output.add(Entry(title = clean(label), url = href, isDirectory = false, poster = posterUrl, video = href, kind = kind))
        }

        val result = output.distinctBy { it.url to it.video }
            .sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy { it.title.lowercase(Locale.ROOT) })
        directoryCache[url] = result
        return result
    }

    private fun Entry.asSearchResponse(kind: String): SearchResponse {
        val type = when (kind) {
            "TvSeries" -> TvType.TvSeries
            "Anime" -> TvType.Anime
            "Documentary" -> TvType.Documentary
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, url, type, false) { this.posterUrl = poster }
    }

    private fun displayName(url: String): String {
        val segment = url.trimEnd('/').substringAfterLast('/')
        return try {
            URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
                .replace(Regex("""\s+"""), " ").trim().ifBlank { "BDIX folder" }
        } catch (_: Exception) { segment.ifBlank { "BDIX folder" } }
    }

    private fun clean(label: String) = label.removeSuffix("/").replace(Regex("""\s+"""), " ").trim()
    private fun parent(url: String) = url.substringBeforeLast('/') + "/"
    private fun depth(url: String) = url.trimEnd('/').split('/').size
    private fun isVideo(url: String) = url.substringBefore('?').lowercase(Locale.ROOT).let {
        listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".mpg", ".mpeg", ".ts").any(it::endsWith)
    }
    private fun isImage(url: String) = url.substringBefore('?').lowercase(Locale.ROOT).let {
        listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any(it::endsWith)
    }
    private fun inferKind(url: String) = when {
        url.contains("TV-WEB-Series", true) || url.contains("WEB%20Series", true) -> "TvSeries"
        url.contains("Anime", true) || url.contains("Cartoon", true) -> "Anime"
        url.contains("Documentary", true) -> "Documentary"
        else -> "Movie"
    }

    private fun isYearFolder(title: String): Boolean {
        return Regex("""^\(?([12]\d{3})\)?$""").matches(title.trim())
    }

    private fun extractYear(text: String): Int? {
        return Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
    }

    private fun parseSeason(text: String): Int? {
        return Regex("""(?i)\bS(\d{1,2})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)Season\s*(\d{1,2})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseEpisode(text: String): Int? {
        return Regex("""(?i)\bE(\d{1,3})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?i)Episode\s*(\d{1,3})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun qualityFrom(url: String): Int =
        Regex("""(?i)(2160|1080|720|480)p?""").find(url)?.groupValues?.firstOrNull()?.toIntOrNull()
            ?: Qualities.Unknown.value
}
