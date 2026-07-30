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
import androidx.tv.material3.Card
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
    Column(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background)
            .padding(start = 56.dp, top = 40.dp, end = 56.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.live_tv_settings_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.live_tv_settings_description), color = NuvioTheme.colors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(name, { name = it }, stringResource(R.string.live_tv_playlist_name), Modifier.weight(.35f))
            TvTextField(url, { url = it }, stringResource(R.string.live_tv_source_hint), Modifier.weight(.65f))
            Button(
                enabled = !state.isLoading,
                onClick = {
                    viewModel.savePlaylist(name, url)
                    name = ""; url = ""
                }
            ) { Text(stringResource(R.string.live_tv_add_playlist)) }
        }
        if (state.isLoading) Text(stringResource(R.string.live_tv_loading))
        state.error?.let { Text(stringResource(R.string.live_tv_error, it), color = NuvioTheme.colors.Error) }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.playlists, key = { it.id }) { playlist ->
                Card(onClick = {}) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name)
                            Text("${playlist.channels.size} channels", color = NuvioTheme.colors.TextSecondary)
                            Text(playlist.sourceUrl, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                        }
                        Button(onClick = { viewModel.savePlaylist(playlist.name, playlist.sourceUrl, playlist.id) }) {
                            Text(stringResource(R.string.live_tv_refresh))
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
