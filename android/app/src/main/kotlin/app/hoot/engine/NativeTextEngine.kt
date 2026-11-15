package app.hoot.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import app.hoot.model.SharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale

class NativeTextEngine : ConversionEngine {
    override val id = "native_text"
    override val displayName = "Android Text & Markdown Engine"

    private val supportedSources = setOf("txt", "md", "csv", "json", "html")
    private val supportedTargets = listOf("html", "txt", "pdf", "json")

    enum class TextBlockType {
        HEADING_1,
        HEADING_2,
        HEADING_3,
        BULLET,
        CODE,
        PARAGRAPH,
        BLANK
    }

    data class TextBlock(
        val type: TextBlockType,
        val text: String,
        val fontSize: Float,
        val isBold: Boolean,
        val isMonospace: Boolean,
        val indent: Float,
        val spacingBefore: Float,
        val spacingAfter: Float,
        val lineHeight: Float
    )

    data class FormattedLine(
        val text: String,
        val fontSize: Float,
        val isBold: Boolean,
        val isMonospace: Boolean,
        val x: Float,
        val lineHeight: Float,
        val spacingAfter: Float = 0f
    )

    companion object {
        const val PDF_PAGE_WIDTH = 595f   // A4 width in pt
        const val PDF_PAGE_HEIGHT = 842f  // A4 height in pt
        const val MARGIN_HORIZONTAL = 50f
        const val MARGIN_VERTICAL = 50f
        const val CONTENT_WIDTH = PDF_PAGE_WIDTH - (MARGIN_HORIZONTAL * 2) // 495f
        const val CONTENT_HEIGHT = PDF_PAGE_HEIGHT - (MARGIN_VERTICAL * 2) // 742f

        // Typography specifications
        const val FONT_SIZE_H1 = 20f
        const val FONT_SIZE_H2 = 16f
        const val FONT_SIZE_H3 = 13f
        const val FONT_SIZE_BODY = 11f
        const val FONT_SIZE_CODE = 9.5f

        fun escapeHtml(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }

        fun formatInlineMarkdown(text: String): String {
            var result = escapeHtml(text)
            // Bold: **text** or __text__
            result = result.replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
            result = result.replace(Regex("""__(.+?)__"""), "<strong>$1</strong>")
            // Italic: *text* or _text_
            result = result.replace(Regex("""\*(.+?)\*"""), "<em>$1</em>")
            result = result.replace(Regex("""_(.+?)_"""), "<em>$1</em>")
            // Inline code: `text`
            result = result.replace(Regex("""`(.+?)`"""), "<code>$1</code>")
            // Links: [text](url)
            result = result.replace(Regex("""\[(.+?)\]\((.+?)\)"""), """<a href="$2">$1</a>""")
            return result
        }

        fun stripMarkdown(markdown: String): String {
            return markdown.lines().joinToString("\n") { line ->
                var l = line.trimStart()
                if (l.startsWith("#")) l = l.trimStart('#').trimStart()
                if (l.startsWith("- ") || l.startsWith("* ")) l = l.substring(2)
                if (l.startsWith("> ")) l = l.substring(2)
                l.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
                    .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
                    .replace(Regex("__(.*?)__"), "$1")
                    .replace(Regex("\\*(.*?)\\*"), "$1")
                    .replace(Regex("_(.*?)_"), "$1")
                    .replace(Regex("`(.*?)`"), "$1")
            }
        }

        /** Parses CSV quoting, escaped quotes, CRLF and embedded line breaks. */
        fun parseCsvRows(csv: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            val row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var atFieldStart = true
            var index = 0

            while (index < csv.length) {
                val ch = csv[index]
                when {
                    quoted && ch == '"' && index + 1 < csv.length && csv[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    ch == '"' && quoted -> quoted = false
                    ch == '"' && atFieldStart -> quoted = true
                    ch == ',' && !quoted -> {
                        row.add(field.toString())
                        field.setLength(0)
                        atFieldStart = true
                    }
                    (ch == '\n' || ch == '\r') && !quoted -> {
                        if (ch == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                        row.add(field.toString())
                        rows.add(row.toList())
                        row.clear()
                        field.setLength(0)
                        atFieldStart = true
                    }
                    else -> {
                        field.append(ch)
                        atFieldStart = false
                    }
                }
                index++
            }

            require(!quoted) { "CSV contains an unterminated quoted field" }
            if (field.isNotEmpty() || row.isNotEmpty() || csv.isEmpty() || !csv.endsWith('\n') && !csv.endsWith('\r')) {
                row.add(field.toString())
                rows.add(row.toList())
            }
            return rows
        }

        fun csvToJson(csv: String): String {
            val rows = parseCsvRows(csv).filterNot { row -> row.size == 1 && row[0].isEmpty() }
            if (rows.isEmpty()) return "[]"
            val rawHeaders = rows.first()
            val seen = mutableSetOf<String>()
            val headers = rawHeaders.mapIndexed { index, value ->
                val base = value.trim().ifEmpty { "column_${index + 1}" }
                var unique = base
                var suffix = 2
                while (!seen.add(unique)) unique = "${base}_${suffix++}"
                unique
            }
            return rows.drop(1).joinToString(separator = ",", prefix = "[", postfix = "]") { values ->
                headers.indices.joinToString(separator = ",", prefix = "{", postfix = "}") { index ->
                    val value = values.getOrElse(index) { "" }
                    "\"${escapeJsonString(headers[index])}\":\"${escapeJsonString(value)}\""
                }
            }
        }

        private fun escapeJsonString(value: String): String = buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }

        fun parseMarkdownBlocks(text: String): List<TextBlock> {
            val blocks = mutableListOf<TextBlock>()
            var inCodeBlock = false

            text.lines().forEach { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("```")) {
                    inCodeBlock = !inCodeBlock
                    return@forEach
                }

                if (inCodeBlock) {
                    blocks.add(
                        TextBlock(
                            type = TextBlockType.CODE,
                            text = rawLine,
                            fontSize = FONT_SIZE_CODE,
                            isBold = false,
                            isMonospace = true,
                            indent = 12f,
                            spacingBefore = 1f,
                            spacingAfter = 1f,
                            lineHeight = FONT_SIZE_CODE * 1.35f
                        )
                    )
                    return@forEach
                }

                when {
                    trimmed.startsWith("# ") -> {
                        blocks.add(
                            TextBlock(
                                type = TextBlockType.HEADING_1,
                                text = trimmed.removePrefix("# ").trim(),
                                fontSize = FONT_SIZE_H1,
                                isBold = true,
                                isMonospace = false,
                                indent = 0f,
                                spacingBefore = 12f,
                                spacingAfter = 6f,
                                lineHeight = FONT_SIZE_H1 * 1.3f
                            )
                        )
                    }
                    trimmed.startsWith("## ") -> {
                        blocks.add(
                            TextBlock(
                                type = TextBlockType.HEADING_2,
                                text = trimmed.removePrefix("## ").trim(),
                                fontSize = FONT_SIZE_H2,
                                isBold = true,
                                isMonospace = false,
                                indent = 0f,
                                spacingBefore = 10f,
                                spacingAfter = 4f,
                                lineHeight = FONT_SIZE_H2 * 1.3f
                            )
                        )
                    }
                    trimmed.startsWith("### ") -> {
                        blocks.add(
                            TextBlock(
                                type = TextBlockType.HEADING_3,
                                text = trimmed.removePrefix("### ").trim(),
                                fontSize = FONT_SIZE_H3,
                                isBold = true,
                                isMonospace = false,
                                indent = 0f,
                                spacingBefore = 8f,
                                spacingAfter = 3f,
                                lineHeight = FONT_SIZE_H3 * 1.3f
                            )
                        )
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        val content = trimmed.substring(2).trim()
                        blocks.add(
                            TextBlock(
                                type = TextBlockType.BULLET,
                                text = "• $content",
                                fontSize = FONT_SIZE_BODY,
                                isBold = false,
                                isMonospace = false,
                                indent = 16f,
                                spacingBefore = 1f,
                                spacingAfter = 2f,
                                lineHeight = FONT_SIZE_BODY * 1.4f
                            )
                        )
                    }
                    trimmed.isEmpty() -> {
                        blocks.add(
                            TextBlock(
                                type = TextBlockType.BLANK,
                                text = "",
                                fontSize = FONT_SIZE_BODY,
                                isBold = false,
                                isMonospace = false,
                                indent = 0f,
                                spacingBefore = 0f,
                                spacingAfter = 0f,
                                lineHeight = FONT_SIZE_BODY * 0.75f
                            )
                        )
                    }
                    else -> {
                        blocks.add(
                            TextBlock(
                                type = TextBlockType.PARAGRAPH,
                                text = trimmed,
                                fontSize = FONT_SIZE_BODY,
                                isBold = false,
                                isMonospace = false,
                                indent = 0f,
                                spacingBefore = 2f,
                                spacingAfter = 2f,
                                lineHeight = FONT_SIZE_BODY * 1.4f
                            )
                        )
                    }
                }
            }
            return blocks
        }

        fun wrapText(
            text: String,
            maxWidth: Float,
            measureWidth: (String) -> Float
        ): List<String> {
            if (text.isEmpty()) return listOf("")
            if (measureWidth(text) <= maxWidth) return listOf(text)

            val lines = mutableListOf<String>()
            val words = text.split(" ")
            val currentLine = StringBuilder()

            for (word in words) {
                val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (measureWidth(candidate) <= maxWidth) {
                    currentLine.clear()
                    currentLine.append(candidate)
                } else {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                        currentLine.clear()
                    }
                    // Handle single word exceeding maxWidth
                    if (measureWidth(word) > maxWidth) {
                        var partial = StringBuilder()
                        for (ch in word) {
                            if (measureWidth(partial.toString() + ch) <= maxWidth) {
                                partial.append(ch)
                            } else {
                                if (partial.isNotEmpty()) lines.add(partial.toString())
                                partial = StringBuilder().append(ch)
                            }
                        }
                        if (partial.isNotEmpty()) currentLine.append(partial.toString())
                    } else {
                        currentLine.append(word)
                    }
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
            return lines
        }

        fun layoutTextToPages(
            blocks: List<TextBlock>,
            pageWidth: Float = PDF_PAGE_WIDTH,
            pageHeight: Float = PDF_PAGE_HEIGHT,
            marginH: Float = MARGIN_HORIZONTAL,
            marginV: Float = MARGIN_VERTICAL,
            measureText: (String, Float, Boolean, Boolean) -> Float
        ): List<List<FormattedLine>> {
            val contentWidth = pageWidth - (marginH * 2)
            val contentHeight = pageHeight - (marginV * 2)
            val pages = mutableListOf<MutableList<FormattedLine>>()
            var currentPage = mutableListOf<FormattedLine>()
            var currentY = 0f

            for (block in blocks) {
                if (block.type == TextBlockType.BLANK) {
                    currentY += block.lineHeight
                    if (currentY > contentHeight && currentPage.isNotEmpty()) {
                        pages.add(currentPage)
                        currentPage = mutableListOf()
                        currentY = 0f
                    }
                    continue
                }

                currentY += block.spacingBefore
                val availableWidth = contentWidth - block.indent
                val wrapped = wrapText(block.text, availableWidth) { str ->
                    measureText(str, block.fontSize, block.isBold, block.isMonospace)
                }

                for (idx in wrapped.indices) {
                    val line = wrapped[idx]
                    val isLastLine = idx == wrapped.size - 1
                    val spacingAfter = if (isLastLine) block.spacingAfter else 0f
                    val totalLineSpace = block.lineHeight + spacingAfter

                    if (currentY + totalLineSpace > contentHeight && currentPage.isNotEmpty()) {
                        pages.add(currentPage)
                        currentPage = mutableListOf()
                        currentY = 0f
                    }

                    val x = marginH + block.indent
                    currentPage.add(
                        FormattedLine(
                            text = line,
                            fontSize = block.fontSize,
                            isBold = block.isBold,
                            isMonospace = block.isMonospace,
                            x = x,
                            lineHeight = block.lineHeight,
                            spacingAfter = spacingAfter
                        )
                    )
                    currentY += totalLineSpace
                }
            }

            if (currentPage.isNotEmpty() || pages.isEmpty()) {
                pages.add(currentPage)
            }
            return pages
        }
    }

    override fun canConvert(sourceExtension: String, targetFormat: String): Boolean {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        return supportedSources.contains(src) && supportedTargets.contains(tgt) && src != tgt
    }

    override fun getSupportedTargets(sourceExtension: String): List<String> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        if (src !in supportedSources) return emptyList()
        return supportedTargets.filter { it != src }
    }

    override suspend fun convert(
        context: Context,
        source: SharedFile,
        outputStream: OutputStream,
        targetFormat: String,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        resizePercent: Int?,
        targetWidth: Int?,
        targetMaxMb: Int?,
        rotationDegrees: Int?,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        cropAspectRatio: Float?,
        removeBackground: Boolean
    ) = withContext(Dispatchers.IO) {
        onProgress(0.1f)
        if (isCancelled()) return@withContext

        val textContent = buildString {
            context.contentResolver.openInputStream(source.uri)?.use { inStream ->
                BufferedReader(InputStreamReader(inStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        appendLine(line)
                        line = reader.readLine()
                    }
                }
            }
        }

        onProgress(0.4f)
        if (isCancelled()) return@withContext

        val src = source.extension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        val plainContent = if (src == "html") {
            android.text.Html.fromHtml(textContent, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } else if (src == "md") {
            stripMarkdown(textContent)
        } else textContent

        when (tgt) {
            "html" -> {
                val html = if (src == "md") markdownToHtml(textContent, source.displayName) else textToHtml(textContent, source.displayName)
                outputStream.write(html.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                onProgress(1.0f)
            }
            "txt" -> {
                outputStream.write(plainContent.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                onProgress(1.0f)
            }
            "pdf" -> {
                renderTextToPdf(plainContent, outputStream, onProgress, isCancelled)
            }
            "json" -> {
                val serialized = if (src == "csv") csvToJson(textContent)
                else "\"${escapeJsonString(plainContent)}\""
                outputStream.write(serialized.toByteArray(Charsets.UTF_8))
                outputStream.flush()
                onProgress(1.0f)
            }
            else -> throw UnsupportedOperationException("Unsupported target format: $tgt")
        }
    }

    fun markdownToHtml(markdown: String, title: String): String {
        val body = StringBuilder()
        var inList = false
        var inCodeBlock = false

        markdown.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    body.append("</code></pre>\n")
                    inCodeBlock = false
                } else {
                    if (inList) { body.append("</ul>\n"); inList = false }
                    body.append("<pre><code>")
                    inCodeBlock = true
                }
                return@forEach
            }
            if (inCodeBlock) {
                body.append(escapeHtml(rawLine)).append("\n")
                return@forEach
            }

            val isListItem = trimmed.startsWith("- ") || trimmed.startsWith("* ")
            if (isListItem) {
                if (!inList) {
                    body.append("<ul>\n")
                    inList = true
                }
                val itemContent = trimmed.substring(2)
                body.append("  <li>").append(formatInlineMarkdown(itemContent)).append("</li>\n")
                return@forEach
            } else if (inList) {
                body.append("</ul>\n")
                inList = false
            }

            when {
                trimmed.startsWith("### ") -> body.append("<h3>").append(formatInlineMarkdown(trimmed.removePrefix("### "))).append("</h3>\n")
                trimmed.startsWith("## ") -> body.append("<h2>").append(formatInlineMarkdown(trimmed.removePrefix("## "))).append("</h2>\n")
                trimmed.startsWith("# ") -> body.append("<h1>").append(formatInlineMarkdown(trimmed.removePrefix("# "))).append("</h1>\n")
                trimmed.startsWith("> ") -> body.append("<blockquote>").append(formatInlineMarkdown(trimmed.removePrefix("> "))).append("</blockquote>\n")
                trimmed.isEmpty() -> body.append("<br/>\n")
                else -> body.append("<p>").append(formatInlineMarkdown(trimmed)).append("</p>\n")
            }
        }

        if (inList) body.append("</ul>\n")
        if (inCodeBlock) body.append("</code></pre>\n")

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${escapeHtml(title)}</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; padding: 24px; color: #222; max-width: 800px; margin: 0 auto; }
                    h1, h2, h3 { color: #111; margin-top: 1.2em; margin-bottom: 0.5em; }
                    code { background: #f4f4f4; padding: 2px 5px; border-radius: 4px; font-family: monospace; }
                    pre { background: #f4f4f4; padding: 12px; border-radius: 6px; overflow-x: auto; }
                    blockquote { border-left: 4px solid #ddd; margin: 0; padding-left: 16px; color: #666; }
                    ul { padding-left: 24px; }
                </style>
            </head>
            <body>
                $body
            </body>
            </html>
        """.trimIndent()
    }

    private fun textToHtml(text: String, title: String): String {
        val escaped = escapeHtml(text).replace("\n", "<br/>\n")
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="utf-8"><title>${escapeHtml(title)}</title>
                <style>body { font-family: monospace; white-space: pre-wrap; padding: 20px; }</style>
            </head>
            <body>$escaped</body>
            </html>
        """.trimIndent()
    }

    private fun renderTextToPdf(
        text: String,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val document = PdfDocument()
        val blocks = parseMarkdownBlocks(text)

        val regularPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            isFakeBoldText = true
        }
        val monoPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val pages = layoutTextToPages(
            blocks = blocks,
            pageWidth = PDF_PAGE_WIDTH,
            pageHeight = PDF_PAGE_HEIGHT,
            marginH = MARGIN_HORIZONTAL,
            marginV = MARGIN_VERTICAL
        ) { str, size, isBold, isMono ->
            val p = when {
                isMono -> monoPaint
                isBold -> boldPaint
                else -> regularPaint
            }
            p.textSize = size
            p.measureText(str)
        }

        val totalPages = pages.size
        for (pageNum in 0 until totalPages) {
            if (isCancelled()) {
                document.close()
                return
            }

            val pageInfo = PdfDocument.PageInfo.Builder(
                PDF_PAGE_WIDTH.toInt(),
                PDF_PAGE_HEIGHT.toInt(),
                pageNum + 1
            ).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            var y = MARGIN_VERTICAL
            for (line in pages[pageNum]) {
                val paint = when {
                    line.isMonospace -> monoPaint
                    line.isBold -> boldPaint
                    else -> regularPaint
                }
                paint.textSize = line.fontSize
                y += line.lineHeight
                canvas.drawText(line.text, line.x, y, paint)
                y += line.spacingAfter
            }

            document.finishPage(page)
            onProgress(0.4f + 0.55f * ((pageNum + 1).toFloat() / totalPages))
        }

        document.writeTo(outputStream)
        outputStream.flush()
        document.close()
        onProgress(1.0f)
    }
}
