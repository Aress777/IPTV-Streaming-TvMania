package com.nuvio.tv.ui.screens.livetv

data class LiveTvChannel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class LiveTvUiState(
    val sourceUrl: String = "",
    val channels: List<LiveTvChannel> = emptyList(),
    val favoriteUrls: Set<String> = emptySet(),
    val selectedGroup: String = ALL_CHANNELS,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null
) {
    val groups: List<String>
        get() = listOf(ALL_CHANNELS, FAVORITES) +
            channels.map { it.group.ifBlank { "Other" } }.distinct().sorted()

    val visibleChannels: List<LiveTvChannel>
        get() = when (selectedGroup) {
            ALL_CHANNELS -> channels
            FAVORITES -> channels.filter { it.streamUrl in favoriteUrls }
            else -> channels.filter { it.group.ifBlank { "Other" } == selectedGroup }
        }

    companion object {
        const val ALL_CHANNELS = "__all__"
        const val FAVORITES = "__favorites__"
    }
}
