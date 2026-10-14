package com.knowapp.android.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.ui.components.SimpleDropdown
import com.knowapp.android.ui.components.generatePassword
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.IncomeGreen
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius

val ROLE_LABELS = mapOf("staff" to "Staff", "institution_admin" to "Owner", "super_admin" to "Super admin")

// Shared by Accounts and Institutions: issues a login and then shows the details to hand over
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountSheet(
    title: String,
    roleOptions: List<String>,
    institutions: List<Pair<Int, String>>,
    fixedInstitutionId: Int?,
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (username: String, fullName: String, password: String, role: String, staffType: String?, institutionId: Int?, onResult: (Boolean) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(generatePassword()) }
    var role by remember { mutableStateOf(roleOptions.first()) }
    var staffType by remember { mutableStateOf("general") }
    var institutionName by remember { mutableStateOf(institutions.firstOrNull { it.first == fixedInstitutionId }?.second ?: "") }
    var created by remember { mutableStateOf<Pair<String, String>?>(null) }

    val chosenInstitution = fixedInstitutionId ?: institutions.firstOrNull { it.second == institutionName }?.first
    val needsInstitution = role != "super_admin" && fixedInstitutionId == null && institutions.isNotEmpty()
    val canSubmit = username.trim().length >= 3 && fullName.isNotBlank() && password.trim().length >= 8 && (!needsInstitution || chosenInstitution != null)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val done = created
            if (done != null) {
                Text("Account created", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Give these login details to ${fullName.trim()}. The password is shown only now, so copy it before you close this.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(modifier = Modifier.fillMaxWidth().background(IncomeGreen.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Username", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(done.first, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Temporary password", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Text(done.second, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Button(
                    onClick = { clipboard.setText(AnnotatedString("KNOW login\nUsername: ${done.first}\nPassword: ${done.second}")) },
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Copy login details", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onDismiss, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
            } else {
                Text(title, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
                OutlinedTextField(value = username, onValueChange = { username = it.replace(" ", "") }, label = { Text("Username") }, supportingText = { Text("Letters and numbers, no spaces") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
                if (roleOptions.size > 1) {
                    Text("ROLE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        roleOptions.forEach { r -> FilterChip(selected = role == r, onClick = { role = r }, label = { Text(ROLE_LABELS[r] ?: r) }) }
                    }
                }
                if (role == "staff") {
                    Text("STAFF TYPE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = staffType == "general", onClick = { staffType = "general" }, label = { Text("General") })
                        FilterChip(selected = staffType == "teacher", onClick = { staffType = "teacher" }, label = { Text("Personal") })
                    }
                }
                if (needsInstitution) {
                    SimpleDropdown("Institution", institutions.map { it.second }, institutionName, { institutionName = it })
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Temporary password") },
                    supportingText = { Text("At least 8 characters. They can change it after signing in") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = SmallRadius,
                )
                TextButton(onClick = { password = generatePassword() }) { Text("Generate a new password") }
                error?.let {
                    Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(DangerRed.copy(alpha = 0.12f), SmallRadius).padding(12.dp))
                }
                Button(
                    onClick = {
                        onSubmit(username.trim(), fullName.trim(), password.trim(), role, if (role == "staff") staffType else null, chosenInstitution) { ok ->
                            if (ok) created = username.trim() to password.trim()
                        }
                    },
                    enabled = !submitting && canSubmit,
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text(if (submitting) "Creating…" else "Create account", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}
