package app.hoot

import androidx.exifinterface.media.ExifInterface
import app.hoot.engine.EngineRegistry
import app.hoot.engine.NativeAudioEngine
import app.hoot.engine.NativeImageEngine
import app.hoot.engine.NativePdfEngine
import app.hoot.engine.NativeTextEngine
import app.hoot.engine.NativeZipEngine
import app.hoot.model.BlobTone
import app.hoot.ui.blob.BlobatarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HootEnginesTest {

    @Test
    fun testNativeImageEngineSupportedFormats() {
        val engine = NativeImageEngine()

        assertTrue(engine.canConvert("jpg", "png"))
        assertTrue(engine.canConvert("jpg", "webp"))
        assertTrue(engine.canConvert("jpg", "gif"))
        assertTrue(engine.canConvert("jpg", "jpg")) // In-place resize, rotate, crop
        assertFalse(engine.canConvert("jpg", "exe"))

        assertTrue(engine.canConvert("png", "jpg"))
        assertTrue(engine.canConvert("png", "webp"))

        assertTrue(engine.canConvert("heic", "png"))
        assertTrue(engine.canConvert("heic", "jpg"))
        assertTrue(engine.canConvert("heic", "webp"))

        val targetsForJpg = engine.getSupportedTargets("jpg")
        assertTrue(targetsForJpg.contains("png"))
        assertTrue(targetsForJpg.contains("webp"))
        assertFalse(targetsForJpg.contains("jpg"))
    }

    @Test
    fun testNativeImageEngineExifTransformations() {
        // Test all standard EXIF orientations
        val normal = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_NORMAL)
        assertEquals(0f, normal.rotation, 0.001f)
        assertFalse(normal.flipH)
        assertFalse(normal.flipV)

        val flipH = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(0f, flipH.rotation, 0.001f)
        assertTrue(flipH.flipH)
        assertFalse(flipH.flipV)

        val rot180 = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(180f, rot180.rotation, 0.001f)
        assertFalse(rot180.flipH)
        assertFalse(rot180.flipV)

        val flipV = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertEquals(0f, flipV.rotation, 0.001f)
        assertFalse(flipV.flipH)
        assertTrue(flipV.flipV)

        val transpose = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(90f, transpose.rotation, 0.001f)
        assertTrue(transpose.flipH)
        assertFalse(transpose.flipV)

        val rot90 = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(90f, rot90.rotation, 0.001f)
        assertFalse(rot90.flipH)
        assertFalse(rot90.flipV)

        val transverse = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_TRANSVERSE)
        assertEquals(270f, transverse.rotation, 0.001f)
        assertTrue(transverse.flipH)
        assertFalse(transverse.flipV)

        val rot270 = NativeImageEngine.getExifTransform(ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(270f, rot270.rotation, 0.001f)
        assertFalse(rot270.flipH)
        assertFalse(rot270.flipV)
    }

    @Test
    fun testNativeImageEngineMatteAndTransparencyDecisions() {
        // JPG with alpha requires clean white matte
        assertTrue(NativeImageEngine.shouldApplyWhiteMatte("jpg", hasAlpha = true))
        assertTrue(NativeImageEngine.shouldApplyWhiteMatte("jpeg", hasAlpha = true))
        // JPG without alpha does not need compositing
        assertFalse(NativeImageEngine.shouldApplyWhiteMatte("jpg", hasAlpha = false))

        // PNG and WebP preserve transparent background without matting
        assertFalse(NativeImageEngine.shouldApplyWhiteMatte("png", hasAlpha = true))
        assertFalse(NativeImageEngine.shouldApplyWhiteMatte("webp", hasAlpha = true))
    }

    @Test
    fun testNativePdfEngineSupportedFormats() {
        val engine = NativePdfEngine()

        assertTrue(engine.canConvert("jpg", "pdf"))
        assertTrue(engine.canConvert("png", "pdf"))
        assertTrue(engine.canConvert("webp", "pdf"))
        assertTrue(engine.canConvert("heic", "pdf"))

        val targetsForPng = engine.getSupportedTargets("png")
        assertTrue(targetsForPng.contains("pdf"))
    }

    @Test
    fun testNativePdfEngineFitImageAspectRatioCalculation() {
        // Test FitImage mode: aspect ratio strictly preserved, zero letterbox margin
        // 1. Large Landscape image (4000x3000, 4:3) -> scaled down to max 1400pt
        val (wLand, hLand) = NativePdfEngine.calculateFitImageDimensions(4000f, 3000f)
        assertEquals(1400, wLand)
        assertEquals(1050, hLand)
        val origRatioLand = 4000.0 / 3000.0
        val pdfRatioLand = wLand.toDouble() / hLand.toDouble()
        assertEquals(origRatioLand, pdfRatioLand, 0.001)

        // 2. Large Portrait image (1080x1920, 9:16) -> scaled down to max 1400pt
        val (wPort, hPort) = NativePdfEngine.calculateFitImageDimensions(1080f, 1920f)
        assertEquals(1400, hPort)
        val expectedWPort = (1080f * (1400f / 1920f)).toInt()
        assertEquals(expectedWPort, wPort)
        val origRatioPort = 1080.0 / 1920.0
        val pdfRatioPort = wPort.toDouble() / hPort.toDouble()
        assertEquals(origRatioPort, pdfRatioPort, 0.005)

        // 3. Smaller image within bounds (800x600) -> 1.0 scale (no scaling)
        val (wSmall, hSmall) = NativePdfEngine.calculateFitImageDimensions(800f, 600f)
        assertEquals(800, wSmall)
        assertEquals(600, hSmall)

        // 4. Square image (2048x2048) -> scaled to 1400x1400 (1:1 aspect ratio)
        val (wSq, hSq) = NativePdfEngine.calculateFitImageDimensions(2048f, 2048f)
        assertEquals(1400, wSq)
        assertEquals(1400, hSq)
    }

    @Test
    fun testNativeZipEngineSupportedFormats() {
        val engine = NativeZipEngine()

        assertTrue(engine.canConvert("jpg", "zip"))
        assertTrue(engine.canConvert("pdf", "zip"))
        assertTrue(engine.canConvert("txt", "zip"))
        assertFalse(engine.canConvert("zip", "zip"))
    }

    @Test
    fun testNativeZipEngineZipSlipTraversalRejectionAndSafety() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "hoot_zip_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Safe paths
            assertTrue(NativeZipEngine.isPathSafe(tempDir, "document.pdf"))
            assertTrue(NativeZipEngine.isPathSafe(tempDir, "subfolder/image.png"))
            assertTrue(NativeZipEngine.isPathSafe(tempDir, "a/b/c/nested.txt"))

            // Malicious traversal attacks (Zip-Slip rejection)
            assertFalse(NativeZipEngine.isPathSafe(tempDir, "../evil.sh"))
            assertFalse(NativeZipEngine.isPathSafe(tempDir, "../../etc/passwd"))
            assertFalse(NativeZipEngine.isPathSafe(tempDir, "subfolder/../../outside.txt"))
            assertFalse(NativeZipEngine.isPathSafe(tempDir, "../" + tempDir.name + "_sibling/evil.exe"))

            // Entry sanitization
            assertEquals("folder/file.txt", NativeZipEngine.sanitizeEntryName("folder\\file.txt"))
            assertEquals("folder/file.txt", NativeZipEngine.sanitizeEntryName("/folder/file.txt"))

            // Security constants
            assertEquals(10_000, NativeZipEngine.MAX_ZIP_ENTRIES)
            assertEquals(500L * 1024 * 1024, NativeZipEngine.MAX_UNCOMPRESSED_SIZE_BYTES)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNativeTextEngineSupportedFormats() {
        val engine = NativeTextEngine()

        assertTrue(engine.canConvert("md", "html"))
        assertTrue(engine.canConvert("md", "txt"))
        assertTrue(engine.canConvert("md", "pdf"))
        assertTrue(engine.canConvert("txt", "pdf"))
        assertTrue(engine.canConvert("csv", "txt"))
        assertTrue(engine.canConvert("json", "html"))
        assertTrue(engine.canConvert("html", "json"))

        val targetsForMd = engine.getSupportedTargets("md")
        assertTrue(targetsForMd.contains("html"))
        assertTrue(targetsForMd.contains("txt"))
        assertTrue(targetsForMd.contains("pdf"))
        assertTrue(targetsForMd.contains("json"))
    }

    @Test
    fun testCsvParserAndJsonConversionHandlesQuotedFieldsAndHeaderCollisions() {
        val csv = "name,name,notes\r\n\"Ada, Lovelace\",Count,\"line one\nline two\"\r\n"
        assertEquals(
            listOf(listOf("name", "name", "notes"), listOf("Ada, Lovelace", "Count", "line one\nline two")),
            NativeTextEngine.parseCsvRows(csv)
        )
        assertEquals(
            "[{\"name\":\"Ada, Lovelace\",\"name_2\":\"Count\",\"notes\":\"line one\\nline two\"}]",
            NativeTextEngine.csvToJson(csv)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCsvParserRejectsUnterminatedQuotedField() {
        NativeTextEngine.parseCsvRows("name,notes\nAda,\"unfinished")
    }

    @Test
    fun testNativeTextEngineMarkdownParsingAndTypographyFormatting() {
        val md = """
            # Project Overview
            ## Architecture
            ### Component Engine
            - Fast native speed
            * High quality rendering
            ```
            val engine = NativeImageEngine()
            ```
            Standard body paragraph with details.
        """.trimIndent()

        val blocks = NativeTextEngine.parseMarkdownBlocks(md)

        // H1 typography
        val h1 = blocks.first { it.type == NativeTextEngine.TextBlockType.HEADING_1 }
        assertEquals("Project Overview", h1.text)
        assertEquals(NativeTextEngine.FONT_SIZE_H1, h1.fontSize, 0.001f)
        assertTrue(h1.isBold)
        assertEquals(0f, h1.indent, 0.001f)

        // H2 typography
        val h2 = blocks.first { it.type == NativeTextEngine.TextBlockType.HEADING_2 }
        assertEquals("Architecture", h2.text)
        assertEquals(NativeTextEngine.FONT_SIZE_H2, h2.fontSize, 0.001f)
        assertTrue(h2.isBold)

        // H3 typography
        val h3 = blocks.first { it.type == NativeTextEngine.TextBlockType.HEADING_3 }
        assertEquals("Component Engine", h3.text)
        assertEquals(NativeTextEngine.FONT_SIZE_H3, h3.fontSize, 0.001f)
        assertTrue(h3.isBold)

        // Bullet items typography & indent
        val bullets = blocks.filter { it.type == NativeTextEngine.TextBlockType.BULLET }
        assertEquals(2, bullets.size)
        assertEquals("• Fast native speed", bullets[0].text)
        assertEquals(16f, bullets[0].indent, 0.001f)
        assertEquals(NativeTextEngine.FONT_SIZE_BODY, bullets[0].fontSize, 0.001f)

        // Code block typography & monospace
        val code = blocks.first { it.type == NativeTextEngine.TextBlockType.CODE }
        assertTrue(code.isMonospace)
        assertEquals(NativeTextEngine.FONT_SIZE_CODE, code.fontSize, 0.001f)
        assertEquals(12f, code.indent, 0.001f)

        // Body paragraph
        val para = blocks.first { it.type == NativeTextEngine.TextBlockType.PARAGRAPH }
        assertEquals(NativeTextEngine.FONT_SIZE_BODY, para.fontSize, 0.001f)
        assertFalse(para.isBold)
        assertFalse(para.isMonospace)
    }

    @Test
    fun testNativeTextEngineInlineMarkdownAndHtmlFormatting() {
        val text = "Support **bold**, *italic*, and `val x = 42` plus [Hoot](https://hoot.app)"
        val formatted = NativeTextEngine.formatInlineMarkdown(text)

        assertTrue(formatted.contains("<strong>bold</strong>"))
        assertTrue(formatted.contains("<em>italic</em>"))
        assertTrue(formatted.contains("<code>val x = 42</code>"))
        assertTrue(formatted.contains("""<a href="https://hoot.app">Hoot</a>"""))

        // HTML escaping
        val dangerous = "Tom & <Jerry> \"friends\" 'forever'"
        val escaped = NativeTextEngine.escapeHtml(dangerous)
        assertEquals("Tom &amp; &lt;Jerry&gt; &quot;friends&quot; &#39;forever&#39;", escaped)

        // Strip markdown
        val md = "# Heading 1\n- Bullet point\n**Bold text**"
        val stripped = NativeTextEngine.stripMarkdown(md)
        assertEquals("Heading 1\nBullet point\nBold text", stripped)
    }

    @Test
    fun testNativeTextEngineLineWrappingAndLayoutAlignment() {
        // Test word wrapping with 8pt per character estimator
        val charWidth = 8f
        val maxWidth = 160f // allows 20 chars per line

        val longLine = "The quick brown fox jumps over the lazy dog and runs away"
        val wrapped = NativeTextEngine.wrapText(longLine, maxWidth) { str -> str.length * charWidth }

        assertTrue(wrapped.size > 1)
        for (line in wrapped) {
            assertTrue(line.length * charWidth <= maxWidth)
        }

        // Test layout alignment: Margins and Content Widths
        assertEquals(595f, NativeTextEngine.PDF_PAGE_WIDTH, 0.001f)
        assertEquals(842f, NativeTextEngine.PDF_PAGE_HEIGHT, 0.001f)
        assertEquals(50f, NativeTextEngine.MARGIN_HORIZONTAL, 0.001f)
        assertEquals(50f, NativeTextEngine.MARGIN_VERTICAL, 0.001f)
        assertEquals(495f, NativeTextEngine.CONTENT_WIDTH, 0.001f)
        assertEquals(742f, NativeTextEngine.CONTENT_HEIGHT, 0.001f)

        // Test pagination on overflowing text
        val blocks = (1..60).map { i ->
            NativeTextEngine.TextBlock(
                type = NativeTextEngine.TextBlockType.PARAGRAPH,
                text = "Paragraph line $i with sufficient text to fill vertical space.",
                fontSize = 11f,
                isBold = false,
                isMonospace = false,
                indent = 0f,
                spacingBefore = 2f,
                spacingAfter = 2f,
                lineHeight = 16f
            )
        }

        val pages = NativeTextEngine.layoutTextToPages(
            blocks = blocks,
            pageWidth = NativeTextEngine.PDF_PAGE_WIDTH,
            pageHeight = NativeTextEngine.PDF_PAGE_HEIGHT,
            marginH = NativeTextEngine.MARGIN_HORIZONTAL,
            marginV = NativeTextEngine.MARGIN_VERTICAL
        ) { str, size, _, _ -> str.length * 7f }

        // 60 lines * 18pt = 1080pt > 742pt, must paginate across at least 2 pages
        assertTrue(pages.size >= 2)
        // First line of every page must start with left margin 50f
        for (page in pages) {
            assertTrue(page.isNotEmpty())
            assertEquals(50f, page.first().x, 0.001f)
        }
    }

    @Test
    fun testNativeAudioEngineSupportedFormats() {
        val engine = NativeAudioEngine()

        assertTrue(engine.canConvert("mp4", "m4a"))
        assertFalse(engine.canConvert("mp4", "aac"))
        assertFalse(engine.canConvert("wav", "m4a"))
        assertTrue(engine.canConvert("m4a", "aac"))
        assertTrue(engine.canConvert("aac", "m4a"))
        assertFalse(engine.canConvert("m4a", "m4a"))

        val targetsForMp4 = engine.getSupportedTargets("mp4")
        assertTrue(targetsForMp4.contains("m4a"))
        assertFalse(targetsForMp4.contains("aac"))
    }

    @Test
    fun testNativeAudioEngineLosslessDemuxingVerification() {
        // Video sources that demux to audio
        for (videoExt in listOf("mp4", "mkv", "mov", "webm", "3gp", "avi")) {
            assertTrue(NativeAudioEngine.isLosslessDemux(videoExt, "m4a"))
            assertFalse(NativeAudioEngine.isLosslessDemux(videoExt, "aac"))
        }

        // Audio sources
        assertTrue(NativeAudioEngine.isLosslessDemux("m4a", "aac"))
        assertTrue(NativeAudioEngine.isLosslessDemux("aac", "m4a"))
        assertFalse(NativeAudioEngine.isLosslessDemux("mp3", "m4a"))
        assertFalse(NativeAudioEngine.isLosslessDemux("wav", "aac"))

        // Invalid / unsupported conversions
        assertFalse(NativeAudioEngine.isLosslessDemux("m4a", "m4a"))
        assertFalse(NativeAudioEngine.isLosslessDemux("jpg", "m4a"))
        assertFalse(NativeAudioEngine.isLosslessDemux("mp4", "mp3"))
    }

    @Test
    fun testEngineRegistryRoutingAndSliceSplitting() {
        val (face1, face2) = EngineRegistry.getSlicesForSource("jpg")
        assertEquals(8, face1.size)
        assertEquals(6, face2.size)

        val face1Ids = face1.map { it.id }
        val face2Targets = face2.map { it.targetFormat }

        assertTrue(face1Ids.contains("crop-tool"))
        assertTrue(face1Ids.contains("rotate-group"))
        assertTrue(face1Ids.contains("bg-remove"))
        assertTrue(face1Ids.contains("merge-images"))
        assertTrue(face1Ids.contains("resizer-group"))
        assertTrue(face1Ids.contains("half-size"))
        assertTrue(face1Ids.contains("edit-pictures"))
        assertTrue(face1Ids.contains("one-zip"))

        assertTrue(face2Targets.contains("png"))
        assertTrue(face2Targets.contains("jpg"))
        assertTrue(face2Targets.contains("webp"))
        assertTrue(face2Targets.contains("gif"))
        assertTrue(face2Targets.contains("pdf"))
        assertTrue(face2Targets.contains("svg"))

        val imgEngine = EngineRegistry.findEngine("jpg", "png")
        assertNotNull(imgEngine)
        assertEquals("native_image", imgEngine?.id)

        val pdfEngine = EngineRegistry.findEngine("png", "pdf")
        assertNotNull(pdfEngine)
        assertEquals("native_pdf", pdfEngine?.id)

        val textEngine = EngineRegistry.findEngine("md", "html")
        assertNotNull(textEngine)
        assertEquals("native_text", textEngine?.id)

        val audioEngine = EngineRegistry.findEngine("mp4", "m4a")
        assertNotNull(audioEngine)
        assertEquals("native_audio", audioEngine?.id)
    }

    @Test
    fun testBlobatarLayoutConstants() {
        assertEquals(52.55f, BlobatarLayout.FaceX, 0.001f)
        assertEquals(51.18f, BlobatarLayout.BodyCy, 0.001f)
        assertEquals(1.08f, BlobatarLayout.PlumpX, 0.001f)
        assertEquals(0.92f, BlobatarLayout.PlumpY, 0.001f)
        assertEquals(BlobatarLayout.EyeRestY, 44.8f, 0.001f)

        for (tone in BlobTone.entries) {
            val (hi, mid, lo) = BlobatarLayout.getToneColors(tone)
            assertNotNull(hi)
            assertNotNull(mid)
            assertNotNull(lo)
        }
    }

    @Test
    fun testSvgVectorEngineCapabilities() {
        val engine = app.hoot.engine.SvgVectorEngine()
        assertEquals("svg_vector", engine.id)

        assertTrue(engine.canConvert("svg", "png"))
        assertTrue(engine.canConvert("svg", "jpg"))
        assertTrue(engine.canConvert("svg", "webp"))
        assertTrue(engine.canConvert("svg", "pdf"))
        assertTrue(engine.canConvert("svgz", "png"))

        val targets = engine.getSupportedTargets("svg")
        assertTrue(targets.contains("png"))
        assertTrue(targets.contains("jpg"))
        assertTrue(targets.contains("webp"))
        assertTrue(targets.contains("pdf"))
    }

    @Test
    fun testRasterGifAndTraceRoutesAreRegistered() {
        assertNotNull(EngineRegistry.findEngine("png", "gif"))
        assertEquals("native_image", EngineRegistry.findEngine("png", "gif")?.id)
        assertNotNull(EngineRegistry.findEngine("jpg", "svg"))
        assertEquals("raster_trace", EngineRegistry.findEngine("jpg", "svg")?.id)
        assertFalse(EngineRegistry.findEngine("pdf", "svg")?.id == "raster_trace")
    }

    @Test
    fun testGifToPdfIsUnavailableAndImagePdfSemanticsAreVisible() {
        val (_, gifSlices) = EngineRegistry.getSlicesForSource("gif")
        assertFalse(gifSlices.first { it.id == "fmt-pdf" }.isEnabled)
        assertEquals("First frame", gifSlices.first { it.id == "fmt-pdf" }.caption)
        assertEquals(null, EngineRegistry.findEngine("gif", "pdf"))
        val (_, pngSlices) = EngineRegistry.getSlicesForSource("png")
        assertEquals("Single image", pngSlices.first { it.id == "fmt-pdf" }.caption)
    }

    @Test
    fun testPdfEngineTextExtractionCapability() {
        val pdfEngine = NativePdfEngine()
        assertTrue(pdfEngine.canConvert("pdf", "txt"))
        assertTrue(pdfEngine.canConvert("pdf", "png"))
        assertTrue(pdfEngine.canConvert("pdf", "jpg"))

        val targets = pdfEngine.getSupportedTargets("pdf")
        assertTrue(targets.contains("txt"))
        assertTrue(targets.contains("png"))
        assertTrue(targets.contains("jpg"))
    }

    @Test
    fun testRotateAndCropToolSlices() {
        val (face1, _) = EngineRegistry.getSlicesForSource("png")
        val rotateSlice = face1.firstOrNull { it.id == "rotate-group" }
        assertNotNull(rotateSlice)
        assertTrue(rotateSlice!!.isGroup)
        assertEquals(3, rotateSlice.subSlices.size)
        assertEquals("Group of 3", rotateSlice.caption)
        assertTrue(rotateSlice.subSlices.any { it.label == "Right" && it.rotationDegrees == 90 })
        assertTrue(rotateSlice.subSlices.any { it.label == "180°" && it.rotationDegrees == 180 })
        assertTrue(rotateSlice.subSlices.any { it.label == "Left" && it.rotationDegrees == 270 })

        val cropSlice = face1.firstOrNull { it.id == "crop-tool" }
        assertNotNull(cropSlice)
        assertEquals("Crop", cropSlice!!.label)
        assertTrue(cropSlice.isGroup)
        assertEquals(5, cropSlice.subSlices.size)

        val resizerSlice = face1.firstOrNull { it.id == "resizer-group" }
        assertNotNull(resizerSlice)
        assertTrue(resizerSlice!!.isGroup)
        assertEquals(7, resizerSlice.subSlices.size)
        assertEquals("Group of 7", resizerSlice.caption)
        val tenMbCount = resizerSlice.subSlices.count { it.label == "10 MB" }
        assertEquals(1, tenMbCount)
    }
}
