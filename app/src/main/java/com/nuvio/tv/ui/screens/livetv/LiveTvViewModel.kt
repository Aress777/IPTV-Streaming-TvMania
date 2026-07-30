package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
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

    fun selectPlaylist(id: String) = _uiState.update {
        it.copy(selectedPlaylistId = id, selectedGroup = LiveTvUiState.ALL_CHANNELS)
    }
    fun selectGroup(group: String) = _uiState.update { it.copy(selectedGroup = group) }
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
                    val token = stalkerHandshake(portal, mac).first
                    val response = stalkerRequest(portal, mac, token, mapOf(
                        "type" to "itv", "action" to "create_link", "cmd" to command,
                        "series" to "0", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0"
                    ))
                    val responseCommand = response.optJSONObject("js")?.optString("cmd").orEmpty()
                    val url = resolveStalkerPlaybackUrl(portal, command, responseCommand)
                    check(url.startsWith("http://") || url.startsWith("https://")) {
                        "Portal did not return a playable stream."
                    }
                    val streamOrigin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
                    val portalOrigin = portal.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
                    val playbackHeaders = if (streamOrigin != null && portalOrigin != null && streamOrigin != portalOrigin) {
                        mapOf(
                            "User-Agent" to STALKER_STREAM_USER_AGENT,
                            "Accept" to "*/*",
                            "Range" to "bytes=0-",
                            "Icy-MetaData" to "1",
                            "Connection" to "keep-alive"
                        )
                    } else {
                        mapOf(
                            "Cookie" to "mac=$mac; stb_lang=en_US@rg=dezzzz; timezone=Europe/Bucharest",
                            "User-Agent" to MAG_USER_AGENT,
                            "X-User-Agent" to MAG_USER_AGENT,
                            "Authorization" to "Bearer $token",
                            "Origin" to portalOrigin.orEmpty(),
                            "Referer" to portalOrigin.orEmpty()
                        ).filterValues(String::isNotBlank)
                    }
                    channel.copy(
                        streamUrl = url,
                        headers = channel.headers + playbackHeaders
                    )
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
                        })
                    }
                })
                put("type", playlist.type.name); put("mac", playlist.macAddress)
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
                    channel.optString("stalkerCommand").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            LiveTvPlaylist(
                item.getString("id"), item.getString("name"), item.getString("url"), channels,
                item.optLong("updatedAt"),
                runCatching { LiveTvPlaylistType.valueOf(item.optString("type", "M3U")) }.getOrDefault(LiveTvPlaylistType.M3U),
                item.optString("mac").takeIf { it.isNotBlank() && it != "null" }
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
        val (token, random) = stalkerHandshake(portal, mac)
        stalkerRequest(portal, mac, token, mapOf(
            "type" to "stb", "action" to "get_profile", "hd" to "1",
            "auth_second_step" to "1", "not_valid_token" to "0",
            "metrics" to JSONObject(mapOf("mac" to mac, "model" to "MAG250", "type" to "STB", "random" to random)).toString(),
            "prehash" to sha1(mac)
        ))
        val genresResponse = stalkerRequest(portal, mac, token, mapOf("type" to "itv", "action" to "get_genres"))
        val genreNames = mutableMapOf<String, String>()
        val genreArray = genresResponse.optJSONArray("js") ?: JSONArray()
        for (i in 0 until genreArray.length()) {
            val genre = genreArray.optJSONObject(i) ?: continue
            genreNames[genre.optString("id")] = genre.optString("title", "Other")
        }
        val channelsResponse = stalkerRequest(portal, mac, token, mapOf("type" to "itv", "action" to "get_all_channels"))
        var data = channelsResponse.optJSONObject("js")?.optJSONArray("data")
        if (data == null || data.length() == 0) {
            val fallback = stalkerRequest(portal, mac, token, mapOf(
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

    private fun stalkerHandshake(portal: String, mac: String): Pair<String, String> {
        val response = stalkerRequest(portal, mac, null, mapOf(
            "type" to "stb", "action" to "handshake", "token" to "", "prehash" to sha1(mac)
        ))
        val js = response.optJSONObject("js") ?: error("Invalid handshake response.")
        val token = js.optString("token")
        check(token.isNotBlank()) { "Portal rejected the MAC address." }
        return token to js.optString("random")
    }

    private fun stalkerRequest(portal: String, mac: String, token: String?, params: Map<String, String>): JSONObject {
        val url = portal.toHttpUrlOrNull()?.newBuilder() ?: error("Invalid portal URL.")
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        url.addQueryParameter("JsHttpRequest", "1-xml")
        val request = Request.Builder().url(url.build())
            .header("Cookie", "mac=$mac; stb_lang=en_US; timezone=Europe/Bucharest")
            .header("User-Agent", MAG_USER_AGENT)
            .header("X-User-Agent", MAG_USER_AGENT)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Portal HTTP ${response.code}" }
            return JSONObject(response.body?.string().orEmpty())
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
            else -> resolved
        }
    }

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.uppercase().toByteArray()).joinToString("") { "%02X".format(it) }

    private companion object {
        const val KEY_PLAYLISTS = "playlists_json_v2"
        const val KEY_FAVORITES = "favorite_urls"
        val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
        val MAC_PATTERN = Regex("""^([0-9A-F]{2}:){5}[0-9A-F]{2}$""")
        const val MAG_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250"
        const val STALKER_STREAM_USER_AGENT = "KSPlayer"
    }
}
