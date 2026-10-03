@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.oxclub.universaldownloader.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.preferences.Keys
import com.oxclub.universaldownloader.data.repository.FileStore
import com.oxclub.universaldownloader.ui.LocalAppContainer
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: Int, val body: Int)

@Composable
fun OnboardingScreen() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = listOf(
        Page(Icons.Filled.Download, R.string.onb1_title, R.string.onb1_body),
        Page(Icons.Filled.ContentPaste, R.string.onb2_title, R.string.onb2_body),
        Page(Icons.Filled.Speed, R.string.onb3_title, R.string.onb3_body),
        Page(Icons.Filled.Folder, R.string.onb4_title, R.string.onb4_body),
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    var folderLabel by remember { mutableStateOf(FileStore.DEFAULT_LABEL) }

    fun finish() {
        scope.launch { container.settings.set(Keys.ONBOARDING, true) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch { container.settings.set(Keys.TREE_URI, uri.toString()) }
            folderLabel = container.fileStore.describeTree(uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(page.icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(page.body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (index == pages.lastIndex) {
                    Spacer(Modifier.height(24.dp))
                    Text(folderLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { folderLauncher.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val last = pager.currentPage == pages.lastIndex
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pages.lastIndex) } }, enabled = !last) {
                Text(stringResource(R.string.action_skip))
            }
            Button(onClick = {
                if (!last) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                } else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    finish()
                }
            }) {
                Text(stringResource(if (last) R.string.get_started else R.string.action_next))
            }
        }
    }
}
