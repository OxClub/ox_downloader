package com.oxclub.universaldownloader.ui.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.downloader.DownloadManager
import com.oxclub.universaldownloader.ui.LocalAppContainer
import com.oxclub.universaldownloader.ui.components.DownloadAction
import com.oxclub.universaldownloader.ui.components.TextInputDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object FileActions {
    /** Opens a finished file with another app. Returns false when no app can open it. */
    fun open(context: Context, e: DownloadEntity): Boolean {
        val uri = e.destinationUri ?: return false
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), e.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (ex: Exception) {
            false
        }
    }

    fun share(context: Context, e: DownloadEntity): Boolean {
        val uri = e.destinationUri ?: return false
        val send = Intent(Intent.ACTION_SEND).setType(e.mimeType ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(send, e.filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (ex: Exception) {
            false
        }
    }
}

/** Shared handler for the per-download menu so every screen behaves the same. */
@Stable
class DownloadActionController(
    private val manager: DownloadManager,
    private val context: Context,
    private val scope: CoroutineScope,
    private val message: (String) -> Unit,
    private val details: (Long) -> Unit,
) {
    var deleteTarget by mutableStateOf<DownloadEntity?>(null)
    var renameTarget by mutableStateOf<DownloadEntity?>(null)

    fun perform(action: DownloadAction, e: DownloadEntity) {
        when (action) {
            DownloadAction.PAUSE -> manager.pause(e.id)
            DownloadAction.RESUME, DownloadAction.RETRY -> manager.resume(e.id)
            DownloadAction.CANCEL -> manager.cancel(e.id)
            DownloadAction.MOVE_TOP -> manager.moveToTop(e.id)
            DownloadAction.OPEN -> if (!FileActions.open(context, e)) message(context.getString(R.string.err_cannot_open))
            DownloadAction.SHARE -> if (!FileActions.share(context, e)) message(context.getString(R.string.err_cannot_open))
            DownloadAction.DELETE -> deleteTarget = e
            DownloadAction.RENAME -> renameTarget = e
            DownloadAction.DETAILS -> details(e.id)
        }
    }

    fun delete(e: DownloadEntity, withFile: Boolean) {
        manager.delete(e.id, withFile)
        deleteTarget = null
    }

    fun rename(e: DownloadEntity, name: String) {
        renameTarget = null
        if (name.isBlank()) return
        scope.launch {
            if (!manager.rename(e.id, name)) message(context.getString(R.string.err_rename_failed))
        }
    }
}

@Composable
fun rememberActionController(onMessage: (String) -> Unit, onDetails: (Long) -> Unit): DownloadActionController {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val msg by rememberUpdatedState(onMessage)
    val det by rememberUpdatedState(onDetails)
    return remember { DownloadActionController(container.downloadManager, context, scope, { msg(it) }, { det(it) }) }
}

@Composable
fun DownloadActionDialogs(c: DownloadActionController) {
    c.deleteTarget?.let { e ->
        val completed = e.status == DownloadStatus.COMPLETED && e.destinationUri != null
        AlertDialog(
            onDismissRequest = { c.deleteTarget = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(e.filename) },
            confirmButton = {
                TextButton(onClick = { c.delete(e, withFile = completed) }) {
                    Text(stringResource(if (completed) R.string.delete_with_file else R.string.action_delete))
                }
            },
            dismissButton = {
                if (completed) {
                    TextButton(onClick = { c.delete(e, withFile = false) }) { Text(stringResource(R.string.delete_list_only)) }
                } else {
                    TextButton(onClick = { c.deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
    c.renameTarget?.let { e ->
        TextInputDialog(
            title = R.string.rename_title,
            initial = e.filename,
            onDismiss = { c.renameTarget = null },
            onConfirm = { c.rename(e, it) },
            label = R.string.rename_label,
        )
    }
}
