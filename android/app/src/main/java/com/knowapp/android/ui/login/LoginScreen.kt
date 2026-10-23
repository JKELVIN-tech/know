package com.knowapp.android.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.R
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
) {
    val state = viewModel.uiState
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var showPassword by remember { mutableStateOf(false) }
    val glow = if (dark) Color(0xFF16423D) else Color(0xFF0F766E).copy(alpha = 0.22f)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(Brush.verticalGradient(listOf(glow, MaterialTheme.colorScheme.background))),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Image(painter = painterResource(R.drawable.know_logo), contentDescription = "KNOW", modifier = Modifier.size(96.dp))
            Text(
                "Welcome back",
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "Sign in to your institution account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(20.dp),
            ) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text("Username") },
                    singleLine = true,
                    shape = SmallRadius,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    shape = SmallRadius,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.login(onLoginSuccess) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), SmallRadius)
                            .padding(12.dp),
                    )
                }

                Button(
                    onClick = { viewModel.login(onLoginSuccess) },
                    enabled = !state.isLoading,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 0.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Sign in", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
            }

            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            androidx.compose.material3.TextButton(onClick = { uriHandler.openUri(com.knowapp.android.BuildConfig.API_BASE_URL + "/api/auth/forgot-redirect") }) {
                Text("Forgot password?")
            }

            androidx.compose.material3.TextButton(onClick = { uriHandler.openUri(com.knowapp.android.BuildConfig.API_BASE_URL + "/api/auth/signup-redirect") }) {
                Text("Create a staff or personal account")
            }

            Text(
                text = "Part of an institution? Ask your owner for your login.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp, bottom = 32.dp),
            )
        }
    }
}
