package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LiveTvScreen(
    onPlayChannel: (LiveTvChannel) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(start = 36.dp, top = 24.dp, end = 30.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = stringResource(R.string.live_tv_title), style = MaterialTheme.typography.headlineLarge)
        when {
            state.isLoading -> Text(stringResource(R.string.live_tv_loading))
            state.error != null -> Text(stringResource(R.string.live_tv_error, state.error!!), color = NuvioTheme.colors.Error)
            state.playlists.isEmpty() -> Text(stringResource(R.string.live_tv_empty), color = NuvioTheme.colors.TextSecondary)
            else -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.width(150.dp).fillMaxHeight().focusGroup(),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = LiveTvUiState.GLOBAL_FAVORITES) {
                        Card(
                            onClick = viewModel::selectGlobalFavorites,
                            colors = CardDefaults.colors(
                                containerColor = if (state.isGlobalFavoritesSelected)
                                    NuvioTheme.colors.Primary.copy(alpha = .65f) else NuvioTheme.colors.Surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(stringResource(R.string.live_tv_favorites), fontSize = 12.sp)
                                Text("${state.favoriteUrls.size} channels", fontSize = 9.sp, color = NuvioTheme.colors.TextSecondary)
                            }
                        }
                    }
                    items(state.playlists, key = { it.id }) { playlist ->
                        Card(
                            onClick = { viewModel.selectPlaylist(playlist.id) },
                            colors = CardDefaults.colors(
                                containerColor = if (!state.isGlobalFavoritesSelected && state.selectedPlaylist?.id == playlist.id)
                                    NuvioTheme.colors.Primary.copy(alpha = .65f) else NuvioTheme.colors.Surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(playlist.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.channels.size} channels", fontSize = 9.sp, color = NuvioTheme.colors.TextSecondary)
                            }
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.width(165.dp).fillMaxHeight().focusGroup(),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.groups, key = { it }) { group ->
                        val label = when (group) {
                            LiveTvUiState.ALL_CHANNELS -> stringResource(R.string.live_tv_all_channels)
                            else -> group
                        }
                        Card(
                            onClick = { viewModel.selectGroup(group) },
                            colors = CardDefaults.colors(
                                containerColor = if (state.selectedGroup == group) NuvioTheme.colors.Primary.copy(alpha = .45f)
                                    else NuvioTheme.colors.Surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                        contentPadding = PaddingValues(2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.visibleChannels.isEmpty()) {
                            item { Text(stringResource(R.string.live_tv_no_channels)) }
                        }
                        items(state.visibleChannels, key = { it.streamUrl }) { channel ->
                            ChannelCard(
                                channel = channel,
                                favorite = channel.streamUrl in state.favoriteUrls,
                                onClick = { viewModel.resolveForPlayback(channel, onPlayChannel) },
                                onFavorite = { viewModel.toggleFavorite(channel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: LiveTvChannel,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                    onFavorite()
                    true
                } else false
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.group.isNotBlank()) {
                    Text(channel.group, fontSize = 9.sp, color = NuvioTheme.colors.TextSecondary)
                }
            }
            Button(
                onClick = onFavorite,
                modifier = Modifier.size(34.dp),
                contentPadding = PaddingValues(6.dp)
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(if (favorite) R.string.live_tv_unfavorite else R.string.live_tv_favorite),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
