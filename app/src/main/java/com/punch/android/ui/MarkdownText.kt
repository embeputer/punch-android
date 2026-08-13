package com.punch.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.punch.android.ui.theme.PunchCharcoal
import com.punch.android.ui.theme.PunchIvory
import com.punch.android.ui.theme.PunchMint
import com.punch.android.ui.theme.PunchMuted
import com.punch.android.ui.theme.PunchStroke

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = PunchIvory,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val uriHandler = LocalUriHandler.current
    val linkListener: (LinkAnnotation) -> Unit = { link ->
        if (link is LinkAnnotation.Clickable && isSupportedUrl(link.tag)) {
            uriHandler.openUri(link.tag)
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    text = inlineMarkdown(block.text, linkListener),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = headingSize(block.level),
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                    ),
                )
                is Block.Paragraph -> Text(
                    text = inlineMarkdown(block.text, linkListener),
                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                )
                is Block.Bullet -> Text(
                    text = prefixedMarkdown("•  ", block.text, linkListener),
                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                )
                is Block.Numbered -> Text(
                    text = prefixedMarkdown("${block.number}.  ", block.text, linkListener),
                    style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                )
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(PunchMint.copy(alpha = 0.7f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = inlineMarkdown(block.text, linkListener),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontStyle = FontStyle.Italic,
                            color = PunchMuted,
                        ),
                    )
                }
                is Block.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PunchCharcoal)
                        .padding(12.dp),
                ) {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                        ),
                    )
                }
                is Block.Divider -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(PunchStroke),
                )
            }
        }
    }
}

private fun headingSize(level: Int): TextUnit = when (level) {
    1 -> 26.sp
    2 -> 22.sp
    3 -> 19.sp
    else -> 17.sp
}

internal fun isSupportedUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
}

private fun prefixedMarkdown(prefix: String, text: String, linkListener: (LinkAnnotation) -> Unit): AnnotatedString =
    buildAnnotatedString {
        append(prefix)
        appendInline(this, text, linkListener)
    }

internal fun inlineMarkdown(text: String, linkListener: (LinkAnnotation) -> Unit): AnnotatedString =
    buildAnnotatedString { appendInline(this, text, linkListener) }

private fun appendInline(builder: AnnotatedString.Builder, text: String, linkListener: (LinkAnnotation) -> Unit) {
    var start = 0
    for (match in INLINE_TOKEN.findAll(text)) {
        if (match.range.first > start) {
            builder.append(text.substring(start, match.range.first))
        }
        val raw = match.value
        when {
            raw.startsWith("**") -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                builder.append(raw.substring(2, raw.length - 2))
            }
            raw.startsWith("`") -> builder.withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x33FFFFFF),
                ),
            ) {
                builder.append(raw.substring(1, raw.length - 1))
            }
            raw.startsWith("~~") -> builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                builder.append(raw.substring(2, raw.length - 2))
            }
            raw.startsWith("[") -> {
                val close = raw.indexOf("](")
                val label = raw.substring(1, close)
                val url = raw.substring(close + 2, raw.length - 1)
                if (isSupportedUrl(url)) {
                    val link = LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = PunchMint,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = linkListener,
                    )
                    val start = builder.length
                    builder.append(label)
                    builder.addLink(link, start, builder.length)
                } else {
                    builder.append(label)
                }
            }
            else -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                builder.append(raw.substring(1, raw.length - 1))
            }
        }
        start = match.range.last + 1
    }
    if (start < text.length) {
        builder.append(text.substring(start))
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Numbered(val number: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data object Divider : Block
}

private val HEADING = Regex("\\s{0,3}(#{1,6})\\s+(.*)")
private val BULLET = Regex("\\s*[-*+]\\s+(.*)")
private val NUMBERED = Regex("\\s*(\\d+)\\.\\s+(.*)")
private val QUOTE = Regex("\\s*>\\s?(.*)")
private val HORIZONTAL = Regex("\\s*([-*_])\\s*(\\1\\s*){2,}")
private val INLINE_TOKEN = Regex("""(\*\*(.+?)\*\*|\*[^*]+\*|`[^`]+`|~~[^~]+~~|\[[^\]\n]+]\([^)\n]+\))""")

private fun parseMarkdown(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trim().startsWith("```") -> {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code += lines[i]
                    i++
                }
                i++
                blocks += Block.Code(code.joinToString("\n"))
            }
            line.isBlank() -> i++
            else -> {
                val heading = HEADING.matchEntire(line)
                if (heading != null) {
                    blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                    i++
                    continue
                }
                val bullet = if (HORIZONTAL.matches(line)) null else BULLET.matchEntire(line)
                if (bullet != null) {
                    blocks += Block.Bullet(bullet.groupValues[1].trim())
                    i++
                    continue
                }
                val numbered = NUMBERED.matchEntire(line)
                if (numbered != null) {
                    blocks += Block.Numbered(numbered.groupValues[1], numbered.groupValues[2].trim())
                    i++
                    continue
                }
                val quote = QUOTE.matchEntire(line)
                if (quote != null) {
                    blocks += Block.Quote(quote.groupValues[1].trim())
                    i++
                    continue
                }
                if (HORIZONTAL.matches(line)) {
                    blocks += Block.Divider
                    i++
                    continue
                }
                val paragraph = mutableListOf(line.trim())
                i++
                while (i < lines.size && !isBlockStart(lines[i])) {
                    paragraph += lines[i].trim()
                    i++
                }
                blocks += Block.Paragraph(paragraph.joinToString("\n"))
            }
        }
    }
    return blocks
}

private fun isBlockStart(line: String): Boolean {
    if (line.isBlank()) return true
    val trimmed = line.trimStart()
    if (trimmed.startsWith("```")) return true
    return HEADING.matches(line) ||
        BULLET.matches(line) ||
        NUMBERED.matches(line) ||
        QUOTE.matches(line) ||
        HORIZONTAL.matches(line)
}
