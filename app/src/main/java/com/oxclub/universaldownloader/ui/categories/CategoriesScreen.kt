@file:OptIn(ExperimentalMaterial3Api::class)

package com.oxclub.universaldownloader.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oxclub.universaldownloader.AppContainer
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.ui.appViewModel
import com.oxclub.universaldownloader.ui.components.categoryIcon
import com.oxclub.universaldownloader.ui.components.categoryLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CategoryCounts(val total: Int, val byCategory: Map<FileCategory, Int>)

class CategoriesViewModel(c: AppContainer) : ViewModel() {
    val counts: StateFlow<CategoryCounts> = c.repository.observeAll()
        .map { list -> CategoryCounts(list.size, list.groupingBy { it.category }.eachCount()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoryCounts(0, emptyMap()))
}

@Composable
fun CategoriesScreen(onOpenCategory: (String?) -> Unit) {
    val vm = appViewModel { CategoriesViewModel(it) }
    val counts by vm.counts.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_categories)) }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    modifier = Modifier.clickable { onOpenCategory(null) },
                    leadingContent = { Icon(Icons.Filled.Category, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(R.string.cat_everything)) },
                    trailingContent = { Text("(${counts.total})") },
                )
                HorizontalDivider()
            }
            items(FileCategory.entries.toList(), key = { it.name }) { cat ->
                ListItem(
                    modifier = Modifier.clickable { onOpenCategory(cat.name) },
                    leadingContent = { Icon(categoryIcon(cat), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(categoryLabel(cat))) },
                    trailingContent = { Text("(${counts.byCategory[cat] ?: 0})") },
                )
                HorizontalDivider()
            }
        }
    }
}
