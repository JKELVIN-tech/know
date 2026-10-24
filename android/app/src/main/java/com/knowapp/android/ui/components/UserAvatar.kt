package com.knowapp.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

private val AvatarColors = listOf(Color(0xFF0F766E), Color(0xFF2563EB), Color(0xFF9333EA), Color(0xFFD97706), Color(0xFFDB2777), Color(0xFF475569))

private fun initialsOf(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

// The person's own photo when they have one, otherwise their initials on a colour
@Composable
fun UserAvatar(name: String, avatarUrl: Any?, size: Dp = 44.dp) {
    val color = AvatarColors[(name.hashCode() and 0x7fffffff) % AvatarColors.size]
    val initials: @Composable () -> Unit = {
        Box(modifier = Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(initialsOf(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
        }
    }
    if (avatarUrl != null) {
        SubcomposeAsyncImage(
            model = avatarUrl,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(color),
        ) {
            if (painter.state is AsyncImagePainter.State.Success) SubcomposeAsyncImageContent() else initials()
        }
    } else {
        initials()
    }
}
