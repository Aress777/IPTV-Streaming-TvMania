package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
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
                        })
                    }
                })
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
                    headersJson.keys().asSequence().associateWith { headersJson.getString(it) }
                )
            }
            LiveTvPlaylist(item.getString("id"), item.getString("name"), item.getString("url"), channels, item.optLong("updatedAt"))
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

    private companion object {
        const val KEY_PLAYLISTS = "playlists_json_v2"
        const val KEY_FAVORITES = "favorite_urls"
        val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
    }
}
