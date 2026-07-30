package com.nuvio.tv.ui.screens.livetv

data class LiveTvChannel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class LiveTvPlaylist(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val channels: List<LiveTvChannel>,
    val updatedAt: Long = System.currentTimeMillis()
)

data class LiveTvUiState(
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val selectedGroup: String = ALL_CHANNELS,
    val favoriteUrls: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val selectedPlaylist: LiveTvPlaylist?
        get() = playlists.firstOrNull { it.id == selectedPlaylistId } ?: playlists.firstOrNull()
    val groups: List<String>
        get() = listOf(ALL_CHANNELS, FAVORITES) +
            selectedPlaylist?.channels.orEmpty().map { it.group.ifBlank { "Other" } }.distinct().sorted()
    val visibleChannels: List<LiveTvChannel>
        get() = when (selectedGroup) {
            ALL_CHANNELS -> selectedPlaylist?.channels.orEmpty()
            FAVORITES -> selectedPlaylist?.channels.orEmpty().filter { it.streamUrl in favoriteUrls }
            else -> selectedPlaylist?.channels.orEmpty().filter { it.group.ifBlank { "Other" } == selectedGroup }
        }

    companion object {
        const val ALL_CHANNELS = "__all__"
        const val FAVORITES = "__favorites__"
    }
}
