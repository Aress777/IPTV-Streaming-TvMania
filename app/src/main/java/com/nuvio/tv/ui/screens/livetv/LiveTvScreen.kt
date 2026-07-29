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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            .padding(start = 56.dp, top = 40.dp, end = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(text = stringResource(R.string.live_tv_title), style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.sourceUrl,
                onValueChange = viewModel::setSourceUrl,
                singleLine = true,
                label = { androidx.compose.material3.Text(stringResource(R.string.live_tv_source_hint)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = NuvioTheme.colors.Primary
                ),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = viewModel::loadPlaylist, enabled = !state.isLoading) {
                Text(if (state.hasLoaded) stringResource(R.string.live_tv_refresh) else stringResource(R.string.live_tv_load))
            }
        }
        when {
            state.isLoading -> Text(stringResource(R.string.live_tv_loading))
            state.error != null -> Text(stringResource(R.string.live_tv_error, state.error!!), color = NuvioTheme.colors.Error)
            !state.hasLoaded -> Text(stringResource(R.string.live_tv_empty), color = NuvioTheme.colors.TextSecondary)
            else -> Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.width(250.dp).fillMaxHeight().focusGroup(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.groups, key = { it }) { group ->
                        val label = when (group) {
                            LiveTvUiState.ALL_CHANNELS -> stringResource(R.string.live_tv_all_channels)
                            LiveTvUiState.FAVORITES -> stringResource(R.string.live_tv_favorites)
                            else -> group
                        }
                        Card(
                            onClick = { viewModel.selectGroup(group) },
                            colors = CardDefaults.colors(
                                containerColor = if (state.selectedGroup == group) NuvioTheme.colors.Primary.copy(alpha = .45f)
                                    else NuvioTheme.colors.Surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label, modifier = Modifier.padding(16.dp)) }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.visibleChannels.isEmpty()) {
                        item { Text(stringResource(R.string.live_tv_no_channels)) }
                    }
                    items(state.visibleChannels, key = { it.streamUrl }) { channel ->
                        ChannelCard(
                            channel = channel,
                            favorite = channel.streamUrl in state.favoriteUrls,
                            onClick = { onPlayChannel(channel) },
                            onFavorite = { viewModel.toggleFavorite(channel) }
                        )
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
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(54.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.group.isNotBlank()) {
                    Text(channel.group, color = NuvioTheme.colors.TextSecondary)
                }
            }
            Button(onClick = onFavorite) {
                Icon(
                    imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(if (favorite) R.string.live_tv_unfavorite else R.string.live_tv_favorite)
                )
            }
        }
    }
}
