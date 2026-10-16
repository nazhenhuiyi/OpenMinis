package com.openminis.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import com.openminis.app.data.photos.CameraRollRules
import com.openminis.app.data.photos.CameraRollSource

/**
 * [T-today-settings] 今日 and 探索's settings on one page, opened from the
 * gear on 探索 (and 设置 in Today's menu): what used to crowd Today's menu.
 */
@Composable
internal fun TodaySettingsPage(vm: TodayViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    var albums by remember { mutableStateOf(false) }
    var askCpu by remember { mutableStateOf(false) }
    var askSuggest by remember { mutableStateOf(false) }
    FullScreenPage(onDismiss, header = { PageTitle(stringResource(R.string.today_settings_title), Modifier.weight(1f)) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsSection(stringResource(R.string.today_settings_section_record)) {
                SettingsSwitchRow(stringResource(R.string.today_settings_location), checked = state.recordLocation, onCheckedChange = vm::setRecordLocation)
                SettingsSwitchRow(stringResource(R.string.today_settings_weather), checked = state.showWeather, onCheckedChange = vm::setShowWeather)
                SettingsSwitchRow(stringResource(R.string.today_settings_weather_alerts), checked = state.weatherAlerts, onCheckedChange = vm::setWeatherAlerts)
                SettingsRow(
                    stringResource(R.string.today_settings_albums), stringResource(R.string.today_settings_albums_sub),
                    onClick = { albums = true }, showDivider = false,
                )
            }
            // The 碎碎念 model is on this phone (§19).
            if (state.murmurReady) {
                SettingsSection(stringResource(R.string.today_settings_section_murmur)) {
                    SettingsSwitchRow(
                        stringResource(R.string.today_settings_mood_colors), stringResource(R.string.today_settings_mood_colors_sub),
                        checked = state.moodColors, onCheckedChange = vm::setMoodColors,
                    )
                    val canSuggest = state.cloudAvailable || state.smartSuggest
                    SettingsSwitchRow(
                        stringResource(R.string.today_settings_smart_suggest),
                        stringResource(if (canSuggest) R.string.today_settings_smart_suggest_sub else R.string.today_settings_smart_suggest_no_model),
                        checked = state.smartSuggest,
                        // Off → on says first what leaves the phone (§19.3).
                        onCheckedChange = { on -> if (on) askSuggest = true else vm.setSmartSuggest(false) },
                        enabled = canSuggest,
                        showDivider = false,
                    )
                }
            }
            // Only on phones whose NPU can't run the model: the NPU always comes first (§17.1).
            if (state.speciesCpuOffered) {
                SettingsSection(stringResource(R.string.explore_nature)) {
                    SettingsSwitchRow(
                        stringResource(R.string.today_settings_species_cpu), checked = state.speciesCpu,
                        onCheckedChange = { on -> if (on) askCpu = true else vm.setSpeciesCpu(false) },
                        showDivider = false,
                    )
                }
            }
        }
    }
    if (albums) AlbumsDialog(vm = vm, selected = state.includedAlbums, onDone = { vm.setIncludedAlbums(it); albums = false })
    if (askCpu) {
        AlertDialog(
            onDismissRequest = { askCpu = false },
            title = { Text(stringResource(R.string.today_species_cpu_title)) },
            text = { Text(stringResource(R.string.today_species_cpu_body)) },
            confirmButton = { TextButton(onClick = { askCpu = false; vm.setSpeciesCpu(true) }) { Text(stringResource(R.string.today_species_cpu_on)) } },
            dismissButton = { TextButton(onClick = { askCpu = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (askSuggest) {
        AlertDialog(
            onDismissRequest = { askSuggest = false },
            title = { Text(stringResource(R.string.today_suggest_consent_title)) },
            text = { Text(stringResource(R.string.today_suggest_consent_body)) },
            confirmButton = { TextButton(onClick = { askSuggest = false; vm.setSmartSuggest(true) }) { Text(stringResource(R.string.today_suggest_turn_on)) } },
            dismissButton = { TextButton(onClick = { askSuggest = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** "Included albums" (SPEC §5.1): camera folders are always in; others opt in. */
@Composable
private fun AlbumsDialog(vm: TodayViewModel, selected: Set<String>, onDone: (Set<String>) -> Unit) {
    var albums by remember { mutableStateOf<List<CameraRollSource.Album>?>(null) }
    var picked by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) { albums = vm.albums() }
    AlertDialog(
        onDismissRequest = { onDone(picked) },
        title = { Text(stringResource(R.string.today_settings_albums)) },
        text = {
            Column {
                Text(stringResource(R.string.today_albums_body), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                val list = albums
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.bucketId }) { a ->
                            val always = CameraRollRules.classify(a.relativeDir, null, emptySet()) != null
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !always) {
                                    picked = if (a.bucketId in picked) picked - a.bucketId else picked + a.bucketId
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = always || a.bucketId in picked, onCheckedChange = null, enabled = !always)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(a.name)
                                    Text("${a.relativeDir} · ${a.count}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text(stringResource(R.string.today_done)) } },
    )
}
