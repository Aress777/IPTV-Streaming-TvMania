package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LiveTvSettingsScreen(
    onBackPress: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBackPress)
    val state by viewModel.uiState.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var sourceType by remember { mutableStateOf(LiveTvPlaylistType.M3U) }
    Column(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background)
            .padding(start = 56.dp, top = 40.dp, end = 56.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.live_tv_settings_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.live_tv_settings_description), color = NuvioTheme.colors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { sourceType = LiveTvPlaylistType.M3U }) {
                Text(if (sourceType == LiveTvPlaylistType.M3U) "✓ M3U" else "M3U")
            }
            Button(onClick = { sourceType = LiveTvPlaylistType.STALKER }) {
                Text(if (sourceType == LiveTvPlaylistType.STALKER) "✓ Stalker Portal" else "Stalker Portal")
            }
            Button(onClick = { sourceType = LiveTvPlaylistType.XTREAM }) {
                Text(if (sourceType == LiveTvPlaylistType.XTREAM) "✓ Portal Login" else "Portal Login")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(name, { name = it }, stringResource(R.string.live_tv_playlist_name), Modifier.weight(.25f))
            TvTextField(
                url, { url = it },
                stringResource(if (sourceType == LiveTvPlaylistType.M3U) R.string.live_tv_source_hint else R.string.live_tv_stalker_portal),
                Modifier.weight(.5f)
            )
            if (sourceType == LiveTvPlaylistType.STALKER) {
                TvTextField(mac, { mac = it }, stringResource(R.string.live_tv_stalker_mac), Modifier.weight(.3f))
            }
            if (sourceType == LiveTvPlaylistType.XTREAM) {
                TvTextField(username, { username = it }, "Username", Modifier.weight(.25f))
                TvTextField(password, { password = it }, "Password", Modifier.weight(.25f))
            }
            Button(
                enabled = !state.isLoading,
                onClick = {
                    when (sourceType) {
                        LiveTvPlaylistType.M3U -> viewModel.savePlaylist(name, url, editingId)
                        LiveTvPlaylistType.STALKER -> viewModel.saveStalkerPlaylist(name, url, mac, editingId)
                        LiveTvPlaylistType.XTREAM -> viewModel.saveXtreamPlaylist(name, url, username, password, editingId)
                    }
                    editingId = null
                }
            ) { Text(if (editingId == null) stringResource(R.string.live_tv_add_playlist) else "Save") }
        }
        if (state.isLoading) Text(stringResource(R.string.live_tv_loading))
        state.error?.let { Text(stringResource(R.string.live_tv_error, it), color = NuvioTheme.colors.Error) }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.playlists, key = { it.id }) { playlist ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(NuvioTheme.colors.Surface)
                        .padding(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name)
                            Text("${playlist.type.name} • ${playlist.channels.size} channels", color = NuvioTheme.colors.TextSecondary)
                            Text(playlist.sourceUrl, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                        }
                        Button(onClick = {
                            when (playlist.type) {
                                LiveTvPlaylistType.M3U ->
                                    viewModel.savePlaylist(playlist.name, playlist.sourceUrl, playlist.id)
                                LiveTvPlaylistType.STALKER -> viewModel.saveStalkerPlaylist(
                                    playlist.name, playlist.sourceUrl, playlist.macAddress.orEmpty(), playlist.id
                                )
                                LiveTvPlaylistType.XTREAM -> viewModel.saveXtreamPlaylist(
                                    playlist.name, playlist.sourceUrl, playlist.username.orEmpty(),
                                    playlist.password.orEmpty(), playlist.id
                                )
                            }
                        }) {
                            Text(stringResource(R.string.live_tv_refresh))
                        }
                        Button(onClick = {
                            editingId = playlist.id
                            sourceType = playlist.type
                            name = playlist.name
                            url = playlist.sourceUrl
                            mac = playlist.macAddress.orEmpty()
                            username = playlist.username.orEmpty()
                            password = playlist.password.orEmpty()
                        }) {
                            Text("Edit")
                        }
                        Button(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                            Text(stringResource(R.string.live_tv_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = { androidx.compose.material3.Text(label) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = NuvioTheme.colors.Primary
        ),
        modifier = modifier
    )
}
