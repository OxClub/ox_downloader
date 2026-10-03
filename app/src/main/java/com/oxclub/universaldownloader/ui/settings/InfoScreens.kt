@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oxclub.universaldownloader.BuildConfig
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.ui.navigation.Routes

enum class InfoKind { ABOUT, HELP, FAQ, PRIVACY, LICENSES }

@Composable
fun InfoScreen(kind: InfoKind, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val title = when (kind) {
        InfoKind.ABOUT -> R.string.about_title
        InfoKind.HELP -> R.string.help_title
        InfoKind.FAQ -> R.string.faq_title
        InfoKind.PRIVACY -> R.string.privacy_title
        InfoKind.LICENSES -> R.string.licenses_title
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            when (kind) {
                InfoKind.ABOUT -> {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.version_format, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.about_description))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.about_legal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    ListItem(modifier = Modifier.clickable { onNavigate(Routes.HELP) }, headlineContent = { Text(stringResource(R.string.help_title)) })
                    ListItem(modifier = Modifier.clickable { onNavigate(Routes.FAQ) }, headlineContent = { Text(stringResource(R.string.faq_title)) })
                    ListItem(modifier = Modifier.clickable { onNavigate(Routes.LICENSES) }, headlineContent = { Text(stringResource(R.string.licenses_title)) })
                    ListItem(modifier = Modifier.clickable { onNavigate(Routes.PRIVACY) }, headlineContent = { Text(stringResource(R.string.privacy_title)) })
                    if (BuildConfig.CONTACT_EMAIL.isNotBlank()) {
                        ListItem(
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${BuildConfig.CONTACT_EMAIL}")))
                            },
                            headlineContent = { Text(stringResource(R.string.contact_title)) },
                            supportingContent = { Text(BuildConfig.CONTACT_EMAIL) },
                        )
                    }
                }
                InfoKind.HELP -> for (line in stringArrayResource(R.array.help_items)) {
                    Text("•  $line", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                InfoKind.FAQ -> {
                    val q = stringArrayResource(R.array.faq_questions)
                    val a = stringArrayResource(R.array.faq_answers)
                    q.forEachIndexed { i, question ->
                        Column(Modifier.padding(16.dp)) {
                            Text(question, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(a.getOrElse(i) { "" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
                InfoKind.PRIVACY -> for (line in stringArrayResource(R.array.privacy_paragraphs)) {
                    Text(line, modifier = Modifier.padding(16.dp))
                }
                InfoKind.LICENSES -> for (line in stringArrayResource(R.array.licenses)) {
                    ListItem(headlineContent = { Text(line.substringBefore(" — ")) }, supportingContent = { Text(line.substringAfter(" — ", "")) })
                    HorizontalDivider()
                }
            }
        }
    }
}
