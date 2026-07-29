package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val httpClient: OkHttpClient
) : ViewModel() {
    private val preferences = context.getSharedPreferences("live_tv_m3u", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        LiveTvUiState(
            sourceUrl = preferences.getString(KEY_SOURCE, "").orEmpty(),
            favoriteUrls = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        )
    )
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    fun setSourceUrl(value: String) = _uiState.update { it.copy(sourceUrl = value) }

    fun selectGroup(group: String) = _uiState.update { it.copy(selectedGroup = group) }

    fun toggleFavorite(channel: LiveTvChannel) {
        val favorites = _uiState.value.favoriteUrls.toMutableSet().apply {
            if (!add(channel.streamUrl)) remove(channel.streamUrl)
        }
        preferences.edit().putStringSet(KEY_FAVORITES, favorites).apply()
        _uiState.update { it.copy(favoriteUrls = favorites) }
    }

    fun loadPlaylist() {
        val source = _uiState.value.sourceUrl.trim()
        if (!source.startsWith("http://") && !source.startsWith("https://")) {
            _uiState.update { it.copy(error = "Enter a valid HTTP or HTTPS URL.") }
            return
        }
        preferences.edit().putString(KEY_SOURCE, source).apply()
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
                _uiState.update {
                    it.copy(
                        channels = channels,
                        selectedGroup = LiveTvUiState.ALL_CHANNELS,
                        isLoading = false,
                        hasLoaded = true,
                        error = if (channels.isEmpty()) "The playlist contains no playable channels." else null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, hasLoaded = true, error = error.message ?: "Unknown error")
                }
            }
        }
    }

    private fun parseM3u(payload: String, sourceUrl: String): List<LiveTvChannel> {
        val lines = payload.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val channels = mutableListOf<LiveTvChannel>()
        var attributes = emptyMap<String, String>()
        var displayName = ""
        var headers = emptyMap<String, String>()
        lines.forEach { line ->
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    attributes = ATTRIBUTE.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    displayName = line.substringAfterLast(',').trim()
                    headers = emptyMap()
                }
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                    headers = headers + ("Referer" to line.substringAfter('='))
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    headers = headers + ("User-Agent" to line.substringAfter('='))
                !line.startsWith("#") -> {
                    val resolved = runCatching { java.net.URI(sourceUrl).resolve(line).toString() }.getOrDefault(line)
                    channels += LiveTvChannel(
                        name = displayName.ifBlank { attributes["tvg-name"].orEmpty().ifBlank { "Live channel" } },
                        streamUrl = resolved,
                        logoUrl = attributes["tvg-logo"]?.takeIf(String::isNotBlank),
                        group = attributes["group-title"].orEmpty(),
                        headers = headers
                    )
                    attributes = emptyMap()
                    displayName = ""
                    headers = emptyMap()
                }
            }
        }
        return channels.distinctBy { it.streamUrl }
    }

    private companion object {
        const val KEY_SOURCE = "source_url"
        const val KEY_FAVORITES = "favorite_urls"
        val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
    }
}
