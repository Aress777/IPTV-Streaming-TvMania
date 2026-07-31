package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val httpClient: OkHttpClient
) : ViewModel() {
    private val preferences = context.getSharedPreferences("live_tv_m3u", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        LiveTvUiState(
            playlists = readPlaylists(),
            favoriteUrls = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        )
    )
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    fun selectPlaylist(id: String) {
        _uiState.update {
            it.copy(selectedPlaylistId = id, selectedGroup = LiveTvUiState.ALL_CHANNELS)
        }
        refreshVisibleEpg()
    }
    fun selectGroup(group: String) {
        _uiState.update { it.copy(selectedGroup = group) }
        refreshVisibleEpg()
    }
    fun clearError() = _uiState.update { it.copy(error = null) }

    fun toggleFavorite(channel: LiveTvChannel) {
        val favorites = _uiState.value.favoriteUrls.toMutableSet().apply {
            if (!add(channel.streamUrl)) remove(channel.streamUrl)
        }
        preferences.edit().putStringSet(KEY_FAVORITES, favorites).apply()
        _uiState.update { it.copy(favoriteUrls = favorites) }
    }

    fun savePlaylist(name: String, sourceUrl: String, existingId: String? = null) {
        val source = sourceUrl.trim()
        if (!source.startsWith("http://") && !source.startsWith("https://")) {
            _uiState.update { it.copy(error = "Enter a valid HTTP or HTTPS URL.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    httpClient.newCall(Request.Builder().url(source).build()).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        parseM3u(response.body?.string().orEmpty(), source)
                    }
                }
            }.onSuccess { channels ->
                if (channels.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, error = "The playlist contains no playable channels.") }
                    return@onSuccess
                }
                val id = existingId ?: UUID.randomUUID().toString()
                val playlist = LiveTvPlaylist(id, name.trim().ifBlank { "Playlist ${_uiState.value.playlists.size + 1}" }, source, channels)
                val updated = _uiState.value.playlists.filterNot { it.id == id } + playlist
                persist(updated)
                _uiState.update { it.copy(playlists = updated, selectedPlaylistId = id, isLoading = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Unknown error") }
            }
        }
    }

    fun saveStalkerPlaylist(name: String, portalUrl: String, macAddress: String, existingId: String? = null) {
        val portal = normalizePortalUrl(portalUrl.trim())
        val mac = macAddress.trim().uppercase()
        if ((!portal.startsWith("http://") && !portal.startsWith("https://")) ||
            !MAC_PATTERN.matches(mac)) {
            _uiState.update { it.copy(error = "Enter a valid portal URL and MAC address (00:1A:79:XX:XX:XX).") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { loadStalkerChannels(portal, mac) } }
                .onSuccess { channels ->
                    if (channels.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = "The portal returned no TV channels.") }
                        return@onSuccess
                    }
                    val id = existingId ?: UUID.randomUUID().toString()
                    val playlist = LiveTvPlaylist(
                        id, name.trim().ifBlank { "Stalker ${_uiState.value.playlists.size + 1}" },
                        portal, channels, type = LiveTvPlaylistType.STALKER, macAddress = mac
                    )
                    val updated = _uiState.value.playlists.filterNot { it.id == id } + playlist
                    persist(updated)
                    _uiState.update { it.copy(playlists = updated, selectedPlaylistId = id, isLoading = false) }
                }.onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "Stalker portal error") }
                }
        }
    }

    fun saveXtreamPlaylist(
        name: String,
        portalUrl: String,
        username: String,
        password: String,
        existingId: String? = null
    ) {
        val portal = portalUrl.trim().trimEnd('/')
        val user = username.trim()
        val pass = password.trim()
        if ((!portal.startsWith("http://") && !portal.startsWith("https://")) || user.isBlank() || pass.isBlank()) {
            _uiState.update { it.copy(error = "Enter a valid portal URL, username and password.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { loadXtreamChannels(portal, user, pass) } }
                .onSuccess { channels ->
                    if (channels.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = "The Xtream portal returned no live channels.") }
                        return@onSuccess
                    }
                    val id = existingId ?: UUID.randomUUID().toString()
                    val playlist = LiveTvPlaylist(
                        id = id,
                        name = name.trim().ifBlank { "Xtream ${_uiState.value.playlists.size + 1}" },
                        sourceUrl = portal,
                        channels = channels,
                        type = LiveTvPlaylistType.XTREAM,
                        username = user,
                        password = pass
                    )
                    val updated = _uiState.value.playlists.filterNot { it.id == id } + playlist
                    persist(updated)
                    _uiState.update { it.copy(playlists = updated, selectedPlaylistId = id, isLoading = false) }
                }.onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "Xtream portal error") }
                }
        }
    }

    fun resolveForPlayback(channel: LiveTvChannel, onReady: (LiveTvChannel) -> Unit) {
        val portal = channel.stalkerPortalUrl
        val mac = channel.stalkerMac
        val command = channel.stalkerCommand
        if (portal == null || mac == null || command == null) {
            onReady(channel)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val directUrl = normalizeStalkerCommand(command)
                    if (isDirectStalkerUrl(directUrl)) {
                        return@withContext channel.copy(streamUrl = directUrl, headers = emptyMap())
                    }
                    val session = openStalkerSession(portal, mac)
                    val response = stalkerRequest(session, mapOf(
                        "type" to "itv", "action" to "create_link", "cmd" to command,
                        "series" to "0", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0"
                    ))
                    val responseData = response.optJSONObject("js") ?: JSONObject()
                    val responseCommand = responseData.optString("url")
                        .ifBlank { responseData.optString("cmd") }
                    val url = resolveStalkerPlaybackUrl(portal, command, responseCommand)
                    check(url.startsWith("http://") || url.startsWith("https://")) {
                        "Portal did not return a playable stream."
                    }
                    channel.copy(streamUrl = url, headers = buildStalkerPlaybackHeaders(session, url))
                }
            }.onSuccess {
                _uiState.update { state -> state.copy(isLoading = false) }
                onReady(it)
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Could not open channel") }
            }
        }
    }

    fun deletePlaylist(id: String) {
        val updated = _uiState.value.playlists.filterNot { it.id == id }
        persist(updated)
        _uiState.update { it.copy(playlists = updated, selectedPlaylistId = updated.firstOrNull()?.id) }
    }

    private fun persist(playlists: List<LiveTvPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(JSONObject().apply {
                put("id", playlist.id); put("name", playlist.name); put("url", playlist.sourceUrl); put("updatedAt", playlist.updatedAt)
                put("channels", JSONArray().apply {
                    playlist.channels.forEach { channel ->
                        put(JSONObject().apply {
                            put("name", channel.name); put("url", channel.streamUrl); put("logo", channel.logoUrl)
                            put("group", channel.group); put("headers", JSONObject(channel.headers))
                            put("stalkerPortal", channel.stalkerPortalUrl); put("stalkerMac", channel.stalkerMac)
                            put("stalkerCommand", channel.stalkerCommand)
                            put("remoteId", channel.remoteId); put("epgNow", channel.epgNow); put("epgNext", channel.epgNext)
                        })
                    }
                })
                put("type", playlist.type.name); put("mac", playlist.macAddress)
                put("username", playlist.username); put("password", playlist.password)
            })
        }
        preferences.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    private fun readPlaylists(): List<LiveTvPlaylist> = runCatching {
        val array = JSONArray(preferences.getString(KEY_PLAYLISTS, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val channelsJson = item.getJSONArray("channels")
            val channels = (0 until channelsJson.length()).map { channelIndex ->
                val channel = channelsJson.getJSONObject(channelIndex)
                val headersJson = channel.optJSONObject("headers") ?: JSONObject()
                LiveTvChannel(
                    channel.getString("name"), channel.getString("url"),
                    channel.optString("logo").takeIf { it.isNotBlank() && it != "null" },
                    channel.optString("group"),
                    headersJson.keys().asSequence().associateWith { headersJson.getString(it) },
                    channel.optString("stalkerPortal").takeIf { it.isNotBlank() && it != "null" },
                    channel.optString("stalkerMac").takeIf { it.isNotBlank() && it != "null" },
                    channel.optString("stalkerCommand").takeIf { it.isNotBlank() && it != "null" },
                    channel.optString("remoteId").takeIf { it.isNotBlank() && it != "null" },
                    channel.optString("epgNow").takeIf { it.isNotBlank() && it != "null" },
                    channel.optString("epgNext").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            LiveTvPlaylist(
                item.getString("id"), item.getString("name"), item.getString("url"), channels,
                item.optLong("updatedAt"),
                runCatching { LiveTvPlaylistType.valueOf(item.optString("type", "M3U")) }.getOrDefault(LiveTvPlaylistType.M3U),
                item.optString("mac").takeIf { it.isNotBlank() && it != "null" },
                item.optString("username").takeIf { it.isNotBlank() && it != "null" },
                item.optString("password").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }.getOrDefault(emptyList())

    private fun parseM3u(payload: String, sourceUrl: String): List<LiveTvChannel> {
        val channels = mutableListOf<LiveTvChannel>()
        var attributes = emptyMap<String, String>(); var displayName = ""; var headers = emptyMap<String, String>()
        payload.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            when {
                line.startsWith("#EXTINF", true) -> {
                    attributes = ATTRIBUTE.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    displayName = line.substringAfterLast(',').trim(); headers = emptyMap()
                }
                line.startsWith("#EXTVLCOPT:http-referrer=", true) -> headers = headers + ("Referer" to line.substringAfter('='))
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> headers = headers + ("User-Agent" to line.substringAfter('='))
                !line.startsWith("#") -> {
                    val resolved = runCatching { URI(sourceUrl).resolve(line).toString() }.getOrDefault(line)
                    channels += LiveTvChannel(displayName.ifBlank { attributes["tvg-name"].orEmpty().ifBlank { "Live channel" } },
                        resolved, attributes["tvg-logo"]?.takeIf(String::isNotBlank), attributes["group-title"].orEmpty(), headers)
                    attributes = emptyMap(); displayName = ""; headers = emptyMap()
                }
            }
        }
        return channels.distinctBy { it.streamUrl }
    }

    private fun loadStalkerChannels(portal: String, mac: String): List<LiveTvChannel> {
        val session = openStalkerSession(portal, mac)
        stalkerRequest(session, mapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "auth_second_step" to "1", "not_valid_token" to "0",
            "metrics" to JSONObject(mapOf("mac" to mac, "model" to "MAG250", "type" to "STB", "random" to session.random)).toString(),
            "prehash" to sha1(mac)
        ))
        val genresResponse = stalkerRequest(session, mapOf("type" to "itv", "action" to "get_genres"))
        val genreNames = mutableMapOf<String, String>()
        val genreArray = genresResponse.optJSONArray("js") ?: JSONArray()
        for (i in 0 until genreArray.length()) {
            val genre = genreArray.optJSONObject(i) ?: continue
            genreNames[genre.optString("id")] = genre.optString("title", "Other")
        }
        val channelsResponse = stalkerRequest(session, mapOf("type" to "itv", "action" to "get_all_channels"))
        var data = channelsResponse.optJSONObject("js")?.optJSONArray("data")
        if (data == null || data.length() == 0) {
            val fallback = stalkerRequest(session, mapOf(
                "type" to "itv", "action" to "get_ordered_list", "genre" to "*", "category" to "*", "p" to "1"
            ))
            data = fallback.optJSONObject("js")?.optJSONArray("data") ?: JSONArray()
        }
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val cmd = item.optString("cmd").ifBlank { return@mapNotNull null }
            LiveTvChannel(
                name = item.optString("name").ifBlank { "Live channel" },
                streamUrl = "stalker://${item.optString("id", index.toString())}",
                logoUrl = item.optString("logo").takeIf(String::isNotBlank),
                group = genreNames[item.optString("tv_genre_id")] ?: "Other",
                stalkerPortalUrl = portal, stalkerMac = mac, stalkerCommand = cmd
            )
        }.distinctBy { it.streamUrl }
    }

    private fun loadXtreamChannels(portal: String, username: String, password: String): List<LiveTvChannel> {
        val auth = xtreamRequest(portal, username, password)
        val userInfo = auth.optJSONObject("user_info") ?: error("Invalid Xtream response.")
        check(userInfo.optString("auth") == "1" || userInfo.optInt("auth") == 1) {
            userInfo.optString("message").ifBlank { "Xtream login was rejected." }
        }

        val categories = xtreamRequest(portal, username, password, "get_live_categories")
        val categoryNames = mutableMapOf<String, String>()
        val categoryArray = categories.optJSONArray("items") ?: JSONArray()
        for (i in 0 until categoryArray.length()) {
            val item = categoryArray.optJSONObject(i) ?: continue
            categoryNames[item.optString("category_id")] = item.optString("category_name", "Other")
        }
        val streams = xtreamRequest(portal, username, password, "get_live_streams").optJSONArray("items") ?: JSONArray()
        return (0 until streams.length()).mapNotNull { index ->
            val item = streams.optJSONObject(index) ?: return@mapNotNull null
            val streamId = item.optString("stream_id").ifBlank { return@mapNotNull null }
            val extension = item.optString("container_extension").ifBlank { "ts" }
            LiveTvChannel(
                name = item.optString("name").ifBlank { "Live channel" },
                streamUrl = "$portal/live/${urlEncode(username)}/${urlEncode(password)}/$streamId.$extension",
                logoUrl = item.optString("stream_icon").takeIf(String::isNotBlank),
                group = categoryNames[item.optString("category_id")] ?: "Other",
                remoteId = streamId
            )
        }.distinctBy { it.streamUrl }
    }

    private fun xtreamRequest(
        portal: String,
        username: String,
        password: String,
        action: String? = null,
        streamId: String? = null
    ): JSONObject {
        val builder = "$portal/player_api.php".toHttpUrlOrNull()?.newBuilder() ?: error("Invalid Xtream URL.")
        builder.addQueryParameter("username", username).addQueryParameter("password", password)
        action?.let { builder.addQueryParameter("action", it) }
        streamId?.let { builder.addQueryParameter("stream_id", it) }
        httpClient.newCall(Request.Builder().url(builder.build()).build()).execute().use { response ->
            check(response.isSuccessful) { "Xtream HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            return if (body.trimStart().startsWith("[")) {
                JSONObject().put("items", JSONArray(body))
            } else JSONObject(body)
        }
    }

    private fun refreshVisibleEpg() {
        val playlist = _uiState.value.selectedPlaylist ?: return
        if (playlist.type != LiveTvPlaylistType.XTREAM) return
        val username = playlist.username ?: return
        val password = playlist.password ?: return
        val targets = _uiState.value.visibleChannels.take(80).filter { it.remoteId != null }
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val updates = targets.mapNotNull { channel ->
                runCatching {
                    val response = xtreamRequest(
                        playlist.sourceUrl, username, password, "get_short_epg", channel.remoteId
                    )
                    val listings = response.optJSONArray("epg_listings") ?: JSONArray()
                    val now = listings.optJSONObject(0)?.optString("title")?.let(::decodeEpgText)
                    val next = listings.optJSONObject(1)?.optString("title")?.let(::decodeEpgText)
                    channel.streamUrl to (now to next)
                }.getOrNull()
            }.toMap()
            if (updates.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val refreshed = _uiState.value.playlists.map { saved ->
                    if (saved.id != playlist.id) saved else saved.copy(
                        channels = saved.channels.map { channel ->
                            updates[channel.streamUrl]?.let { (now, next) ->
                                channel.copy(epgNow = now, epgNext = next)
                            } ?: channel
                        }
                    )
                }
                persist(refreshed)
                _uiState.update { it.copy(playlists = refreshed) }
            }
        }
    }

    private fun decodeEpgText(value: String): String {
        if (value.isBlank()) return ""
        return runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }
            .getOrDefault(value)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun openStalkerSession(portal: String, mac: String): StalkerSession {
        val session = StalkerSession(portal = portal, mac = mac)
        val response = stalkerRequest(session, mapOf(
            "type" to "stb", "action" to "handshake", "token" to "", "prehash" to sha1(mac)
        ))
        val js = response.optJSONObject("js") ?: error("Invalid handshake response.")
        val token = js.optString("token")
        check(token.isNotBlank()) { "Portal rejected the MAC address." }
        session.token = token
        session.random = js.optString("random")
        return session
    }

    private fun stalkerRequest(session: StalkerSession, params: Map<String, String>): JSONObject {
        val url = session.portal.toHttpUrlOrNull()?.newBuilder() ?: error("Invalid portal URL.")
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        url.addQueryParameter("JsHttpRequest", "1-xml")
        val portalHttpUrl = session.portal.toHttpUrlOrNull() ?: error("Invalid portal URL.")
        val portalOrigin = "${portalHttpUrl.scheme}://${portalHttpUrl.host}:${portalHttpUrl.port}"
        val request = Request.Builder().url(url.build())
            .header("Accept", "*/*")
            .header("Cookie", session.cookieHeader())
            .header("User-Agent", STALKER_ALT_USER_AGENT)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", "$portalOrigin/stalker_portal/c/index.html")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Pragma", "no-cache")
            .header("Connection", "Close")
            .apply { if (session.token.isNotBlank()) header("Authorization", "Bearer ${session.token}") }
            .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Portal HTTP ${response.code}" }
            Cookie.parseAll(response.request.url, response.headers).forEach { cookie ->
                session.serverCookies[cookie.name] = cookie.value
            }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun buildStalkerPlaybackHeaders(session: StalkerSession, url: String): Map<String, String> {
        val target = url.toHttpUrlOrNull()
        val path = target?.encodedPath.orEmpty().lowercase()
        return buildMap {
            put("User-Agent", STALKER_PLAYER_USER_AGENT)
            put("Referer", session.referer())
            put("Accept", "*/*")
            put("Connection", "keep-alive")
            target?.let { put("Host", if (it.port == 80 || it.port == 443) it.host else "${it.host}:${it.port}") }
            put("Cookie", session.cookieHeader())
            put("X-User-Agent", "Model: MAG250; Link: WiFi")
            if (!path.endsWith("/play/live.php") && !path.endsWith("/play/movie.php")) {
                put("Authorization", "Bearer ${session.token}")
            }
        }
    }

    private fun normalizePortalUrl(raw: String): String {
        val url = raw.trimEnd('/')
        return when {
            url.endsWith("/stalker_portal/c") -> url.removeSuffix("/c") + "/server/load.php"
            url.endsWith("/c") -> url.removeSuffix("/c") + "/portal.php"
            url.endsWith("/stalker_portal") -> "$url/server/load.php"
            else -> url
        }
    }

    private fun normalizeStalkerCommand(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        val prefixRemoved = trimmed.replace(Regex("(?i)^ffmpeg\\s*"), "")
            .replace(Regex("(?i)^ffrt\\s*"), "")
            .trim()
        if (prefixRemoved != trimmed) return prefixRemoved
        val space = trimmed.indexOf(' ')
        if (space > 0) {
            val candidate = trimmed.substring(space + 1).trim()
            if (candidate.startsWith("http://") || candidate.startsWith("https://") ||
                candidate.startsWith("/") || candidate.startsWith("?")) return candidate
        }
        return trimmed
    }

    private fun resolveStalkerPlaybackUrl(portal: String, originalCommand: String, responseCommand: String): String {
        var resolved = normalizeStalkerCommand(responseCommand)
        if (resolved.isBlank()) resolved = normalizeStalkerCommand(originalCommand)
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) return resolved
        val portalUrl = portal.toHttpUrlOrNull() ?: return resolved
        val origin = "${portalUrl.scheme}://${portalUrl.host}:${portalUrl.port}"
        val path = portalUrl.encodedPath
        val basePath = when {
            "/stalker_portal/" in path -> path.substringBefore("/stalker_portal/") + "/stalker_portal"
            "/portal/" in path -> path.substringBefore("/portal/") + "/portal"
            else -> ""
        }
        return when {
            resolved.startsWith("?") -> {
                val original = normalizeStalkerCommand(originalCommand)
                if (original.startsWith("http")) original + resolved else origin + basePath + original + resolved
            }
            resolved.startsWith("/") -> origin + basePath + resolved
            else -> origin + "/vod4/" + resolved.trimStart('/')
        }
    }

    /**
     * Some Ministra portals return an already authenticated playback URL in the channel command.
     * Sending that URL through create_link again can erase its stream id and produce a URL that
     * connects but never yields media. Localhost and bare /ch/ commands are portal placeholders.
     */
    private fun isDirectStalkerUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        if (url.host.equals("localhost", ignoreCase = true) || url.host == "127.0.0.1") return false
        return url.querySize > 0 || url.encodedPath.substringAfterLast('/').contains('.')
    }

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.uppercase().toByteArray()).joinToString("") { "%02X".format(it) }

    private data class StalkerSession(
        val portal: String,
        val mac: String,
        var token: String = "",
        var random: String = "",
        val serverCookies: LinkedHashMap<String, String> = linkedMapOf()
    ) {
        fun cookieHeader(): String = buildList {
            add("mac=${java.net.URLEncoder.encode(mac, "UTF-8")}")
            add("stb_lang=en")
            add("timezone=${java.net.URLEncoder.encode("Europe/Bucharest", "UTF-8")}")
            serverCookies.forEach { (name, value) ->
                if (name !in setOf("mac", "stb_lang", "timezone")) add("$name=$value")
            }
        }.joinToString("; ")

        fun referer(): String {
            val url = portal.toHttpUrlOrNull() ?: return portal
            return "${url.scheme}://${url.host}:${url.port}/stalker_portal/c/index.html"
        }
    }

    private companion object {
        const val KEY_PLAYLISTS = "playlists_json_v2"
        const val KEY_FAVORITES = "favorite_urls"
        val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
        val MAC_PATTERN = Regex("""^([0-9A-F]{2}:){5}[0-9A-F]{2}$""")
        const val MAG_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250"
        const val STALKER_ALT_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val STALKER_PLAYER_USER_AGENT = "Lavf53.32.100"
    }
}
