package com.nuvio.tv.ui.screens.livetv

data class LiveTvChannel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap(),
    val stalkerPortalUrl: String? = null,
    val stalkerMac: String? = null,
    val stalkerCommand: String? = null,
    val remoteId: String? = null,
    val epgNow: String? = null,
    val epgNext: String? = null
)

data class LiveTvPlaylist(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val channels: List<LiveTvChannel>,
    val updatedAt: Long = System.currentTimeMillis(),
    val type: LiveTvPlaylistType = LiveTvPlaylistType.M3U,
    val macAddress: String? = null,
    val username: String? = null,
    val password: String? = null
)

enum class LiveTvPlaylistType { M3U, STALKER, XTREAM }

data class LiveTvUiState(
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val selectedGroup: String = ALL_CHANNELS,
    val favoriteUrls: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val isGlobalFavoritesSelected: Boolean
        get() = selectedPlaylistId == GLOBAL_FAVORITES
    val selectedPlaylist: LiveTvPlaylist?
        get() = if (isGlobalFavoritesSelected) null
        else playlists.firstOrNull { it.id == selectedPlaylistId } ?: playlists.firstOrNull()
    val groups: List<String>
        get() = if (isGlobalFavoritesSelected) listOf(ALL_CHANNELS) else listOf(ALL_CHANNELS) +
            selectedPlaylist?.channels.orEmpty().map { it.group.ifBlank { "Other" } }.distinct().sorted()
    val visibleChannels: List<LiveTvChannel>
        get() = if (isGlobalFavoritesSelected) {
            playlists.flatMap { it.channels }.filter { it.streamUrl in favoriteUrls }.distinctBy { it.streamUrl }
        } else when (selectedGroup) {
            ALL_CHANNELS -> selectedPlaylist?.channels.orEmpty()
            else -> selectedPlaylist?.channels.orEmpty().filter { it.group.ifBlank { "Other" } == selectedGroup }
        }

    companion object {
        const val ALL_CHANNELS = "__all__"
        const val GLOBAL_FAVORITES = "__global_favorites__"
    }
}
