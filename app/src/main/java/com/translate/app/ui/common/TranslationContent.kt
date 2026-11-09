package com.translate.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translate.app.data.model.TranslationResult
import com.translate.app.data.model.WordDetail
import com.translate.app.tts.Speaker

/**
 * 翻译结果内容（浮窗 / 悬浮窗 / 主界面共用）。
 * 单词模式显示词典卡片，否则显示「原文 → 译文」。
 */
@Composable
fun TranslationResultContent(
    result: TranslationResult,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        val detail = result.wordDetail
        if (detail != null) {
            WordDetailSection(
                detail = detail,
                mainTranslation = result.translatedText,
                lang = result.fromLanguage,
                ttsUrl = result.srcTtsUrl
            )
        } else {
            // 原文
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = result.originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 6.dp)
                )
                SpeakButton(result.originalText, result.fromLanguage, fallbackUrl = result.srcTtsUrl)
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            // 译文
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = result.translatedText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(top = 6.dp)
                )
                SpeakButton(result.translatedText, result.toLanguage, fallbackUrl = result.dstTtsUrl)
            }

            if (result.isWordQuery) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "未获取到词典释义。在百度翻译开放平台开通「词典」资源后，查单词会显示音标、词性和全部释义。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onCopy(result.translatedText) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制译文", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 单词卡片：单词 + 英/美音标朗读 + 考试标签 + 词性释义 + 词形变化 + 英英释义与例句。
 * 列出每个词性下的全部释义，「熟词生义」（如 book v. 预订、fine n. 罚款）也能一眼看到。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordDetailSection(
    detail: WordDetail,
    mainTranslation: String?,
    lang: String?,
    ttsUrl: String? = null
) {
    val isEnglish = lang == null || lang == "en" || lang == "auto"

    // 单词
    Text(
        text = detail.word,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    // 音标 + 朗读
    FlowRow(verticalArrangement = Arrangement.Center) {
        if (isEnglish && (detail.phoneticUk != null || detail.phoneticUs != null)) {
            detail.phoneticUk?.let {
                PhoneticChip("英", it) {
                    SpeakButton(detail.word, "en", Speaker.Accent.UK, ttsUrl, size = 32.dp)
                }
            }
            detail.phoneticUs?.let {
                PhoneticChip("美", it) {
                    SpeakButton(detail.word, "en", Speaker.Accent.US, ttsUrl, size = 32.dp)
                }
            }
        } else {
            PhoneticChip(null, detail.phoneticOther) {
                SpeakButton(detail.word, lang, fallbackUrl = ttsUrl, size = 32.dp)
            }
        }
    }

    // 考试标签
    if (detail.tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        ) {
            detail.tags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    // 主译文（百度 dst）—— 与第一条释义重复时不再显示
    val firstMean = detail.parts.firstOrNull()?.means?.firstOrNull()
    if (!mainTranslation.isNullOrBlank() && mainTranslation != firstMean &&
        detail.parts.none { p -> p.means.any { it == mainTranslation } }
    ) {
        Text(
            mainTranslation,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }

    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(8.dp))

    // 词性 + 全部释义
    detail.parts.forEach { part ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            if (part.part.isNotBlank()) {
                Text(
                    part.part,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 40.dp).padding(end = 8.dp)
                )
            }
            Text(
                part.means.joinToString("；"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }

    // 词形变化
    if (detail.exchanges.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            detail.exchanges.joinToString("   ") { "${it.label} ${it.forms.joinToString(" / ")}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // 英英释义 + 例句（默认折叠）
    if (detail.englishDefinitions.isNotEmpty()) {
        var expanded by remember(detail.word) { mutableStateOf(false) }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
        ) {
            Text(
                "英英释义 · 例句",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                detail.englishDefinitions.forEach { def ->
                    if (def.pos.isNotBlank()) {
                        Text(
                            def.pos,
                            style = MaterialTheme.typography.labelMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    def.definitions.forEachIndexed { i, d ->
                        Text(
                            "${i + 1}. $d",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    def.examples.take(3).forEach { ex ->
                        Text(
                            "“$ex”",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                        )
                    }
                    if (def.similarWords.isNotEmpty()) {
                        Text(
                            "近义词：" + def.similarWords.take(6).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneticChip(label: String?, phonetic: String?, speak: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp)
    ) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(2.dp))
        }
        if (!phonetic.isNullOrBlank()) {
            Text(
                "/$phonetic/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        speak()
    }
}

/** 喇叭按钮：点击朗读 */
@Composable
fun SpeakButton(
    text: String,
    lang: String?,
    accent: Speaker.Accent = Speaker.Accent.DEFAULT,
    fallbackUrl: String? = null,
    size: Dp = 36.dp
) {
    val ctx = LocalContext.current
    IconButton(
        onClick = { Speaker.speak(ctx, text, lang, accent, fallbackUrl) },
        modifier = Modifier.size(size)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "朗读",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}
