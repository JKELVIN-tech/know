package com.knowapp.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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

private val HeroTop = Color(0xFF0F766E)
private val HeroBottom = Color(0xFF134E4A)
private val Mint = Color(0xFF5EEAD4)

private fun roleLabel(role: String, staffType: String?): String = when (role) {
    "super_admin" -> "Super admin"
    "institution_admin" -> "Owner"
    "agent" -> "Partner agent"
    "staff" -> if (staffType == "teacher") "Personal account" else "Staff"
    else -> role.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun memberSinceText(iso: String?): String? = runCatching {
    "Member since " + OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(updateManager: UpdateManager, themeStore: ThemeStore, profile: ProfileViewModel, onSignOut: suspend () -> Unit, onOpenSubscription: (() -> Unit)? = null) {
    val profileState by profile.state.collectAsState()
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> if (uri != null) profile.onPhotoPicked(uri) }
    val themeMode by themeStore.mode.collectAsState()
    val state by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val installed = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    val text = when (val s = state) {
        is UpdateState.Idle -> "Installed: $installed"
        is UpdateState.Checking -> "Checking for updates…"
        is UpdateState.UpToDate -> "Installed: $installed. You are on the latest version."
        is UpdateState.Available -> "Installed: $installed. A newer version is available."
        is UpdateState.Unknown -> "Installed: $installed. Could not check the latest version, but you can still download it."
        is UpdateState.Downloading -> "Downloading update… ${s.percent}%"
        is UpdateState.NeedPermission -> "Allow KNOW to install apps in the settings screen that just opened, come back, then tap again."
        is UpdateState.Installing -> "Download complete. Confirm the install on the next screen."
        is UpdateState.Error -> "Installed: $installed. ${s.message}"
    }
    val canInstall = state is UpdateState.Available || state is UpdateState.Unknown || state is UpdateState.NeedPermission || state is UpdateState.Installing
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    val hasPhoto = !profileState.removePhoto && (profileState.newPhoto != null || profileState.avatarUrl != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Profile card: cover, overlapping photo, name, headline and actions
            val business = profileState.institutionName?.takeIf { it.isNotBlank() && !it.contains("(@") }
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().height(124.dp).background(Brush.linearGradient(listOf(HeroTop, HeroBottom)))) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(color = Color.White.copy(alpha = 0.07f), radius = size.height * 0.9f, center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.1f))
                            drawCircle(color = Color.White.copy(alpha = 0.06f), radius = size.height * 0.55f, center = androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 1.05f))
                            drawCircle(color = Mint.copy(alpha = 0.10f), radius = size.height * 0.35f, center = androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height * 0.2f))
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp).offset(y = 48.dp).size(104.dp).clickable { photoPicker.launch("image/*") }) {
                        Box(modifier = Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface, CircleShape).padding(4.dp)) {
                            com.knowapp.android.ui.components.UserAvatar(
                                name = profileState.name.ifBlank { "You" },
                                avatarUrl = if (profileState.removePhoto) null else (profileState.newPhoto ?: profileState.avatarUrl),
                                size = 96.dp,
                            )
                        }
                        Box(modifier = Modifier.align(Alignment.BottomEnd).size(32.dp).background(HeroTop, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Edit, contentDescription = "Change photo", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Box(modifier = Modifier.height(58.dp))
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(profileState.name.ifBlank { " " }, fontFamily = DisplayFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                    Text(
                        listOfNotNull(roleLabel(profileState.role, profileState.staffType), business).joinToString("  ·  "),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                    memberSinceText(profileState.memberSince)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp, bottom = 20.dp)) {
                        OutlinedButton(onClick = { photoPicker.launch("image/*") }, shape = PillShape) { Text("Change photo", fontWeight = FontWeight.SemiBold) }
                        if (hasPhoto) OutlinedButton(onClick = profile::removePhoto, shape = PillShape) { Text("Remove") }
                    }
                }
            }

            // Profile strength: nudges people to add a photo and a bio
            val hasBio = profileState.bio.isNotBlank()
            val doneCount = (if (hasPhoto) 1 else 0) + (if (hasBio) 1 else 0)
            if (doneCount < 2) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Complete your profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("$doneCount of 2", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    androidx.compose.material3.LinearProgressIndicator(progress = { doneCount / 2f }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Text(
                        if (!hasPhoto && !hasBio) "Add a photo and write a short bio so people know who is behind your posts."
                        else if (!hasPhoto) "Add a photo so people recognise you on the news feed."
                        else "Write a short bio so people know who is behind your posts.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            // Account details
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DetailRow(Icons.Filled.Person, "Username", profileState.username.ifBlank { "-" })
                    DetailRow(Icons.Filled.Email, "Email", profileState.email ?: "Not added")
                    profileState.institutionName?.takeIf { it.isNotBlank() && !it.contains("(@") }?.let { DetailRow(Icons.Filled.Home, "Business", it) }
                }
            }

            // Bio
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("About you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Your photo and bio show beside your posts on the news feed, which everyone can see.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = profileState.bio,
                    onValueChange = profile::onBioChange,
                    label = { Text("Bio") },
                    supportingText = { Text("${profileState.bio.length}/300") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                )
                profileState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
                Button(onClick = profile::save, enabled = !profileState.saving, shape = PillShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(if (profileState.saving) "Saving…" else "Save profile", fontWeight = FontWeight.SemiBold)
                }
            }

            if (onOpenSubscription != null) {
                androidx.compose.material3.Card(onClick = onOpenSubscription, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(44.dp).background(Brush.linearGradient(listOf(HeroTop, HeroBottom)), CircleShape), contentAlignment = Alignment.Center) {
                            Text("KES", color = Mint, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("Subscription", fontWeight = FontWeight.Bold)
                            Text("Free trial, plan and billing dates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Appearance
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (mode, label) ->
                        FilterChip(selected = themeMode == mode, onClick = { themeStore.set(mode) }, label = { Text(label) })
                    }
                }
            }

            // App update
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("App update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Sign out", fontWeight = FontWeight.SemiBold) }
            Box(modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}
