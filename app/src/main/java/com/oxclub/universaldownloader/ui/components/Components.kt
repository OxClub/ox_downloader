@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.domain.model.LiveProgress
import com.oxclub.universaldownloader.utils.Formatters

/** A persisted download plus its in-memory live progress (only set while downloading). */
data class DownloadItem(val entity: DownloadEntity, val live: LiveProgress?) {
    val bytes: Long get() = live?.bytes ?: entity.downloadedBytes
    val total: Long get() = entity.totalBytes
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

enum class DownloadAction { PAUSE, RESUME, CANCEL, RETRY, DELETE, OPEN, SHARE, RENAME, DETAILS, MOVE_TOP }

fun categoryIcon(c: FileCategory): ImageVector = when (c) {
    FileCategory.VIDEO -> Icons.Filled.Movie
    FileCategory.MUSIC -> Icons.Filled.MusicNote
    FileCategory.IMAGE -> Icons.Filled.Image
    FileCategory.DOCUMENT -> Icons.Filled.Description
    FileCategory.COMPRESSED -> Icons.Filled.FolderZip
    FileCategory.PROGRAM -> Icons.Filled.Android
    FileCategory.SUBTITLE -> Icons.Filled.Subtitles
    FileCategory.OTHER -> Icons.Filled.InsertDriveFile
}

@StringRes
fun categoryLabel(c: FileCategory): Int = when (c) {
    FileCategory.VIDEO -> R.string.cat_videos
    FileCategory.MUSIC -> R.string.cat_music
    FileCategory.IMAGE -> R.string.cat_images
    FileCategory.DOCUMENT -> R.string.cat_documents
    FileCategory.COMPRESSED -> R.string.cat_compressed
    FileCategory.PROGRAM -> R.string.cat_programs
    FileCategory.SUBTITLE -> R.string.cat_subtitles
    FileCategory.OTHER -> R.string.cat_others
}

@StringRes
fun statusLabel(s: DownloadStatus): Int = when (s) {
    DownloadStatus.QUEUED -> R.string.status_queued
    DownloadStatus.ANALYZING -> R.string.status_analyzing
    DownloadStatus.DOWNLOADING -> R.string.status_downloading
    DownloadStatus.PAUSED -> R.string.status_paused
    DownloadStatus.COMPLETED -> R.string.status_completed
    DownloadStatus.FAILED -> R.string.status_failed
    DownloadStatus.CANCELLED -> R.string.status_cancelled
    DownloadStatus.VERIFYING -> R.string.status_verifying
}

@Composable
fun CategoryBadge(category: FileCategory, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, @StringRes title: Int, @StringRes message: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun DownloadCard(
    item: DownloadItem,
    onClick: () -> Unit,
    onAction: (DownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val e = item.entity
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(e.category)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.filename, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                StatusLine(item)
                val showBar = e.status == DownloadStatus.DOWNLOADING || e.status == DownloadStatus.PAUSED ||
                    e.status == DownloadStatus.ANALYZING || e.status == DownloadStatus.VERIFYING || e.status == DownloadStatus.QUEUED
                if (showBar) {
                    Spacer(Modifier.height(6.dp))
                    val f = item.fraction
                    if (f != null && e.status != DownloadStatus.ANALYZING) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    } else if (e.status != DownloadStatus.QUEUED && e.status != DownloadStatus.PAUSED) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (e.status == DownloadStatus.DOWNLOADING) {
                    Spacer(Modifier.height(4.dp))
                    val live = item.live
                    val speed = live?.let { Formatters.speed(it.speedBytesPerSec) } ?: ""
                    val eta = live?.etaSeconds?.let { stringResource(R.string.eta_format, Formatters.duration(it)) }
                        ?: stringResource(R.string.eta_unavailable)
                    Text(
                        text = if (live != null) "$speed • $eta" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (a in actionsFor(e.status)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(actionLabel(a))) },
                            onClick = { menu = false; onAction(a) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(item: DownloadItem) {
    val e = item.entity
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text: String = when (e.status) {
        DownloadStatus.COMPLETED -> "${stringResource(R.string.status_completed)} • ${Formatters.size(e.totalBytes)}"
        DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
            e.errorMessage ?: stringResource(statusLabel(e.status))
        DownloadStatus.QUEUED -> e.errorMessage ?: stringResource(R.string.status_queued)
        else -> {
            val sizes = if (item.total > 0) {
                stringResource(R.string.size_of, Formatters.size(item.bytes), Formatters.size(item.total)) +
                    " • " + Formatters.percent(item.bytes, item.total) + "%"
            } else {
                stringResource(R.string.downloaded_unknown, Formatters.size(item.bytes))
            }
            val label = stringResource(statusLabel(e.status))
            if (e.status == DownloadStatus.PAUSED || e.status == DownloadStatus.VERIFYING || e.status == DownloadStatus.ANALYZING) {
                "$label • $sizes"
            } else {
                e.errorMessage ?: sizes
            }
        }
    }
    val isProblem = e.status == DownloadStatus.FAILED || (e.status == DownloadStatus.QUEUED && e.errorMessage != null)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

fun actionsFor(s: DownloadStatus): List<DownloadAction> = when (s) {
    DownloadStatus.QUEUED -> listOf(DownloadAction.PAUSE, DownloadAction.MOVE_TOP, DownloadAction.CANCEL, DownloadAction.DETAILS)
    DownloadStatus.ANALYZING, DownloadStatus.DOWNLOADING ->
        listOf(DownloadAction.PAUSE, DownloadAction.CANCEL, DownloadAction.DETAILS)
    DownloadStatus.VERIFYING -> listOf(DownloadAction.DETAILS)
    DownloadStatus.PAUSED -> listOf(DownloadAction.RESUME, DownloadAction.CANCEL, DownloadAction.DELETE, DownloadAction.DETAILS)
    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> listOf(DownloadAction.RETRY, DownloadAction.DELETE, DownloadAction.DETAILS)
    DownloadStatus.COMPLETED ->
        listOf(DownloadAction.OPEN, DownloadAction.SHARE, DownloadAction.RENAME, DownloadAction.DELETE, DownloadAction.DETAILS)
}

@StringRes
fun actionLabel(a: DownloadAction): Int = when (a) {
    DownloadAction.PAUSE -> R.string.action_pause
    DownloadAction.RESUME -> R.string.action_resume
    DownloadAction.CANCEL -> R.string.action_cancel
    DownloadAction.RETRY -> R.string.action_retry
    DownloadAction.DELETE -> R.string.action_delete
    DownloadAction.OPEN -> R.string.action_open
    DownloadAction.SHARE -> R.string.action_share
    DownloadAction.RENAME -> R.string.action_rename
    DownloadAction.DETAILS -> R.string.action_details
    DownloadAction.MOVE_TOP -> R.string.action_move_top
}

// ------------------------------------------------------------------ dialogs

@Composable
fun TextInputDialog(
    @StringRes title: Int,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    @StringRes label: Int? = null,
    singleLine: Boolean = true,
    keyboardNumber: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                label = if (label != null) {
                    { Text(stringResource(label)) }
                } else null,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (keyboardNumber) androidx.compose.ui.text.input.KeyboardType.Number
                    else androidx.compose.ui.text.input.KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun <T> ChoiceDialog(
    @StringRes title: Int,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                for ((value, label) in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelect(value); onDismiss() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun MessageDialog(@StringRes title: Int, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

@Composable
fun BannerSlot(placement: com.oxclub.universaldownloader.ads.AdPlacement, modifier: Modifier = Modifier) {
    val ads = com.oxclub.universaldownloader.ui.LocalAppContainer.current.ads
    if (ads.isEnabled) ads.Banner(placement, modifier)
}

@Composable
fun countText(count: Int): String = pluralStringResource(R.plurals.items_count, count, count)
