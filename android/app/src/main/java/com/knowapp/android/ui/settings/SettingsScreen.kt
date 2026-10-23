package com.knowapp.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.BuildConfig
import com.knowapp.android.data.ThemeMode
import com.knowapp.android.data.ThemeStore
import com.knowapp.android.data.UpdateManager
import com.knowapp.android.data.UpdateState
import com.knowapp.android.ui.components.AppCard
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.PillShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(updateManager: UpdateManager, themeStore: ThemeStore, profile: ProfileViewModel, onSignOut: suspend () -> Unit) {
    val profileState by profile.state.collectAsState()
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> if (uri != null) profile.onPhotoPicked(uri) }
    val themeMode by themeStore.mode.collectAsState()
    val state by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val installed = "Installed: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    val text = when (val s = state) {
        is UpdateState.Idle -> installed
        is UpdateState.Checking -> "Checking for updates…"
        is UpdateState.UpToDate -> "$installed. You are on the latest version."
        is UpdateState.Available -> "$installed. A newer version is available."
        is UpdateState.Unknown -> "$installed. Could not check the latest version, but you can still download it."
        is UpdateState.Downloading -> "Downloading update… ${s.percent}%"
        is UpdateState.NeedPermission -> "Allow KNOW to install apps in the settings screen that just opened, come back, then tap again."
        is UpdateState.Installing -> "Download complete. Confirm the install on the next screen."
        is UpdateState.Error -> "$installed. ${s.message}"
    }
    val canInstall = state is UpdateState.Available || state is UpdateState.Unknown || state is UpdateState.NeedPermission || state is UpdateState.Installing
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("Your profile", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your photo and bio show beside your posts on the news feed, which everyone can see.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    com.knowapp.android.ui.components.UserAvatar(
                        name = profileState.name.ifBlank { "You" },
                        avatarUrl = if (profileState.removePhoto) null else (profileState.newPhoto ?: profileState.avatarUrl),
                        size = 76.dp,
                    )
                    Column(modifier = Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(profileState.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        androidx.compose.material3.TextButton(onClick = { photoPicker.launch("image/*") }) { Text("Change photo") }
                        if (profileState.avatarUrl != null || profileState.newPhoto != null) {
                            androidx.compose.material3.TextButton(onClick = profile::removePhoto) { Text("Remove photo") }
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = profileState.bio,
                    onValueChange = profile::onBioChange,
                    label = { Text("Bio") },
                    supportingText = { Text("${profileState.bio.length}/300") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                )
                profileState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
                Button(onClick = profile::save, enabled = !profileState.saving, shape = PillShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(if (profileState.saving) "Saving…" else "Save profile", fontWeight = FontWeight.SemiBold)
                }
            }
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (mode, label) ->
                        FilterChip(selected = themeMode == mode, onClick = { themeStore.set(mode) }, label = { Text(label) })
                    }
                }
            }
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("App update", style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                Button(
                    onClick = { scope.launch { if (canInstall) updateManager.install() else updateManager.check() } },
                    enabled = !busy,
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (canInstall) "Download and install" else "Check for updates", fontWeight = FontWeight.SemiBold) }
            }
            OutlinedButton(
                onClick = { scope.launch { onSignOut() } },
                shape = PillShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign out", fontWeight = FontWeight.SemiBold) }
        }
    }
}
