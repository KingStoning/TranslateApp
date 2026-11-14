package com.translate.app.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import com.translate.app.data.history.HistoryRepository
import com.translate.app.data.model.HistoryItem
import com.translate.app.data.model.TranslationResult
import com.translate.app.ui.common.SpeakButton
import com.translate.app.ui.common.TranslationResultContent
import com.translate.app.ui.common.WordDetailSection
import android.text.format.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    externalText: String? = null,
    onNavigateToSettings: () -> Unit = {},
    viewModel: MainViewModel = viewModel()
) {
    val history by HistoryRepository.items.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    var showClearDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    // 如果有外部传入文本，自动翻译
    LaunchedEffect(externalText) {
        if (!externalText.isNullOrBlank()) {
            viewModel.translateExternal(externalText)
        }
    }

    val filtered = remember(history, query) {
        val q = query.trim()
        if (q.isEmpty()) history
        else history.filter {
            it.originalText.contains(q, ignoreCase = true) ||
                    it.translatedText.contains(q, ignoreCase = true)
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空翻译历史") },
            text = { Text("将删除全部 ${history.size} 条记录，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    HistoryRepository.clear()
                    showClearDialog = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("划词翻译") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空历史")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 输入区域
            item(key = "input") {
                Column {
                    InputSection(
                        text = viewModel.sourceText,
                        onTextChange = { viewModel.onSourceTextChanged(it) },
                        onTranslate = { viewModel.translate() },
                        isLoading = viewModel.isLoading
                    )
                }
            }

            // 错误信息
            viewModel.errorMessage?.let { error ->
                item(key = "error") { ErrorCard(error) }
            }

            // 翻译结果
            val result = viewModel.translationResult
            if (result != null && viewModel.errorMessage == null) {
                item(key = "result") {
                    TranslationResultCard(
                        result = result,
                        onCopy = { clipboardManager.setText(AnnotatedString(it)) }
                    )
                }
            }

            // 历史记录
            if (history.isNotEmpty()) {
                item(key = "history_header") {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "翻译历史（${history.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索历史") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清除")
                                    }
                                }
                            }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "history_empty") {
                        Text(
                            "没有匹配的记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                items(filtered, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        expanded = expandedId == item.id,
                        onToggle = { expandedId = if (expandedId == item.id) null else item.id },
                        onCopy = { clipboardManager.setText(AnnotatedString(item.translatedText)) },
                        onDelete = { HistoryRepository.delete(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun InputSection(
    text: String,
    onTextChange: (String) -> Unit,
    onTranslate: () -> Unit,
    isLoading: Boolean
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("请输入或粘贴要翻译的文本") },
        maxLines = 5,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onTranslate() }),
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清除")
                }
            }
        }
    )

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = onTranslate,
        modifier = Modifier.fillMaxWidth(),
        enabled = text.isNotBlank() && !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(
            Icons.Default.Translate,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(if (isLoading) "翻译中…" else "翻译")
    }
}

@Composable
private fun TranslationResultCard(
    result: TranslationResult,
    onCopy: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        TranslationResultContent(
            result = result,
            onCopy = onCopy,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * 一条历史：默认显示原文 + 译文（或单词释义摘要），点击展开完整内容 / 词典释义。
 */
@Composable
private fun HistoryItemCard(
    item: HistoryItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
            val detail = item.wordDetail
            if (expanded && detail != null) {
                Column(Modifier.padding(end = 8.dp)) {
                    WordDetailSection(
                        detail = detail,
                        mainTranslation = item.translatedText,
                        lang = item.fromLanguage
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = item.originalText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (detail != null) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (!expanded && detail != null) detail.summary(2) else item.translatedText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // 底部：时间 / 次数 + 操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = buildString {
                    append(
                        DateUtils.getRelativeTimeSpanString(
                            item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                        )
                    )
                    if (item.count > 1) append(" · 查过 ${item.count} 次")
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                SpeakButton(item.originalText, item.fromLanguage, size = 36.dp)
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制译文",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
