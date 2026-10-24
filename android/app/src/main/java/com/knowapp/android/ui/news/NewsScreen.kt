package com.knowapp.android.ui.news

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.knowapp.android.data.model.PostOut
import com.knowapp.android.data.model.resolveMediaUrl
import com.knowapp.android.ui.components.UserAvatar
import com.knowapp.android.ui.components.relativeDay
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius

@Composable
private fun Avatar(name: String, size: Int = 44, url: String? = null) {
    UserAvatar(name = name, avatarUrl = url, size = size.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(viewModel: NewsViewModel, onBack: () -> Unit, showBack: Boolean = true) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var composing by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<PostOut?>(null) }
    var deleting by remember { mutableStateOf<PostOut?>(null) }
    var viewingPhoto by remember { mutableStateOf<String?>(null) }
    var viewingAuthor by remember { mutableStateOf<PostOut?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("News", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (viewModel.canPost) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(viewModel.userName, size = 46, url = state.myAvatarUrl)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                        .clip(PillShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                        .clickable { composing = true }
                                        .padding(horizontal = 18.dp, vertical = 13.dp),
                                ) {
                                    Text(
                                        "What's happening, ${viewModel.userName.trim().substringBefore(' ').ifBlank { "there" }}?",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ComposerAction(Icons.Outlined.Image, "Photo") { composing = true }
                                ComposerAction(Icons.Outlined.Article, "Announcement") { composing = true }
                            }
                        }
                    }
                }
            }
            if (state.posts.isNotEmpty()) {
                item {
                    Text("Latest news", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                }
            }
            when {
                state.loading && state.posts.isEmpty() -> item { com.knowapp.android.ui.components.SkeletonRows(count = 3, modifier = Modifier.padding(horizontal = 16.dp)) }
                state.error != null && state.posts.isEmpty() -> item {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: "", color = DangerRed)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                state.posts.isEmpty() -> item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Article, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                            }
                            Text("No news yet", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
                            Text(
                                if (viewModel.canPost) "Be the first to share an update with everyone on KNOW." else "Updates from other businesses will show up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            if (viewModel.canPost) Button(onClick = { composing = true }, shape = PillShape, modifier = Modifier.padding(top = 18.dp)) { Text("Write a post", fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                else -> items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        mine = post.authorId == viewModel.userId,
                        canAct = viewModel.canPost,
                        onDelete = { deleting = post },
                        onReport = { reporting = post },
                        onAuthor = { viewingAuthor = post },
                        canComment = viewModel.canComment,
                        onComments = { viewModel.openComments(post) },
                        onViewPhoto = { viewingPhoto = it },
                    )
                }
            }
        }
    }

    if (composing) {
        ComposeSheet(
            name = viewModel.userName,
            avatarUrl = state.myAvatarUrl,
            posting = state.posting,
            onDismiss = { composing = false },
            onPublish = { title, body, photo -> viewModel.publish(title, body, photo) { ok -> if (ok) composing = false } },
        )
    }

    reporting?.let { post ->
        ReportSheet(
            onDismiss = { reporting = null },
            onSend = { reason -> viewModel.report(post, reason) { reporting = null } },
        )
    }

    deleting?.let { post ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this post?") },
            text = { Text(post.title) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(post) }) { Text("Delete", color = DangerRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    state.commentsFor?.let { post ->
        CommentsSheet(
            post = post,
            comments = state.comments,
            loading = state.commentsLoading,
            sending = state.sendingComment,
            canComment = viewModel.canComment,
            myId = viewModel.userId,
            isAdmin = viewModel.isAdmin,
            onDismiss = viewModel::closeComments,
            onSend = { text, done -> viewModel.sendComment(text, done) },
            onDelete = viewModel::deleteComment,
        )
    }

    viewingAuthor?.let { post -> AuthorSheet(post) { viewingAuthor = null } }

    viewingPhoto?.let { url ->
        Dialog(onDismissRequest = { viewingPhoto = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).clickable { viewingPhoto = null }, contentAlignment = Alignment.Center) {
                AsyncImage(model = url, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                IconButton(onClick = { viewingPhoto = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ComposerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(PillShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun PostCard(post: PostOut, mine: Boolean, canAct: Boolean, canComment: Boolean, onComments: () -> Unit, onDelete: () -> Unit, onReport: () -> Unit, onAuthor: () -> Unit, onViewPhoto: (String) -> Unit) {
    val author = post.authorName ?: "Staff member"
    val photoUrl = resolveMediaUrl(post.imagePath)
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onAuthor), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(author, size = 46, url = resolveMediaUrl(post.authorAvatarPath))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(author, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            post.authorInstitution?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(relativeDay(post.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                if (canAct) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (mine) {
                                DropdownMenuItem(
                                    text = { Text("Delete post", color = DangerRed) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = DangerRed) },
                                    onClick = { menuOpen = false; onDelete() },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Report post") },
                                    leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                                    onClick = { menuOpen = false; onReport() },
                                )
                            }
                        }
                    }
                }
            }

            Text(post.title, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
            Text(
                post.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
            if (overflowing || expanded) {
                Text(
                    if (expanded) "Show less" else "…see more",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = "Photo attached to ${post.title}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 520.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onViewPhoto(photoUrl) },
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(PillShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable(enabled = canComment || post.commentCount > 0) { onComments() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        when (post.commentCount) { 0 -> "Comment"; 1 -> "1 comment"; else -> "${post.commentCount} comments" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeSheet(name: String, avatarUrl: String?, posting: Boolean, onDismiss: () -> Unit, onPublish: (String, String, Uri?) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) photo = uri }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, url = avatarUrl)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("Posting to everyone on KNOW", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Headline") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("What do you want to share?") }, minLines = 5, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            if (photo != null) {
                AsyncImage(model = photo, contentDescription = "Selected photo", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(14.dp)))
                TextButton(onClick = { photo = null }) { Text("Remove photo") }
            } else {
                OutlinedButton(onClick = { picker.launch("image/*") }, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add a photo", modifier = Modifier.padding(start = 8.dp))
                }
            }
            Button(onClick = { onPublish(title, body, photo) }, enabled = !posting, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                Text(if (posting) "Publishing…" else "Post", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reason by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Report post", style = MaterialTheme.typography.titleLarge, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("What's wrong with this post? (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            Button(onClick = { onSend(reason) }, shape = PillShape, modifier = Modifier.fillMaxWidth()) { Text("Send report", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(
    post: PostOut,
    comments: List<com.knowapp.android.data.model.PostCommentOut>,
    loading: Boolean,
    sending: Boolean,
    canComment: Boolean,
    myId: Int?,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onSend: (String, (Boolean) -> Unit) -> Unit,
    onDelete: (com.knowapp.android.data.model.PostCommentOut) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Comments", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(post.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    loading -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    comments.isEmpty() -> Text("No comments yet. Start the conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> comments.forEach { c ->
                        Row(verticalAlignment = Alignment.Top) {
                            Avatar(c.authorName ?: "Staff member", size = 36, url = resolveMediaUrl(c.authorAvatarPath))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.authorName ?: "Staff member", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(relativeDay(c.createdAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(c.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                                if (c.authorId == myId || isAdmin) {
                                    Text(
                                        "Delete",
                                        color = DangerRed,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable { onDelete(c) }.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (canComment) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Add a comment…") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                    )
                    IconButton(
                        onClick = { onSend(text) { ok -> if (ok) text = "" } },
                        enabled = !sending && text.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorSheet(post: PostOut, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Avatar(post.authorName ?: "Staff member", size = 96, url = resolveMediaUrl(post.authorAvatarPath))
            Text(post.authorName ?: "Staff member", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
            post.authorInstitution?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                post.authorBio ?: "No bio yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (post.authorBio == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
