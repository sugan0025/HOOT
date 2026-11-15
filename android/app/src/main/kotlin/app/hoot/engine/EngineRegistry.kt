package app.hoot.engine

import app.hoot.model.DonutSlice
import java.util.Locale

object EngineRegistry {

    val engines: List<ConversionEngine> = listOf(
        NativeImageEngine(),
        RasterTraceEngine(),
        SvgVectorEngine(),
        NativePdfEngine(),
        NativeZipEngine(),
        NativeTextEngine(),
        NativeAudioEngine()
    )

    fun findEngine(sourceExtension: String, targetFormat: String): ConversionEngine? {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val tgt = targetFormat.lowercase(Locale.ROOT)
        return engines.firstOrNull { it.canConvert(src, tgt) }
    }

    fun getSlicesForSource(sourceExtension: String): Pair<List<DonutSlice>, List<DonutSlice>> {
        val src = sourceExtension.lowercase(Locale.ROOT)
        val isImage = src in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg", "svgz")

        if (isImage) {
            val srcUpper = src.uppercase(Locale.ROOT)
            val isSvg = src == "svg" || src == "svgz"

            // Face 1: Exact 8 slices from Windows HOOT (Screenshot 2)
            // 12 o'clock: Crop, Rotate [Group of 3], BG remove, Merge images, Resizer [Group of 7], Half size, Edit pictures, One ZIP
            val face1 = listOf(
                createCropSlice(src).copy(caption = srcUpper, isEnabled = !isSvg),
                createRotateSlice(src).copy(isEnabled = !isSvg),
                DonutSlice(
                    id = "bg-remove",
                    label = "BG remove",
                    caption = "PNG",
                    targetFormat = "png",
                    groupIndex = 0,
                    isEnabled = !isSvg
                ),
                DonutSlice(
                    id = "merge-images",
                    label = "Merge images",
                    caption = srcUpper,
                    targetFormat = src,
                    groupIndex = 0,
                    isEnabled = !isSvg
                ),
                createResizerSlice(src).copy(isEnabled = !isSvg),
                DonutSlice(
                    id = "half-size",
                    label = "Half size",
                    caption = srcUpper,
                    targetFormat = src,
                    resizePercent = 50,
                    groupIndex = 0,
                    isEnabled = !isSvg
                ),
                DonutSlice(
                    id = "edit-pictures",
                    label = "Edit pictures",
                    caption = srcUpper,
                    targetFormat = src,
                    groupIndex = 0,
                    isEnabled = !isSvg
                ),
                DonutSlice(
                    id = "one-zip",
                    label = "One ZIP",
                    caption = "ZIP",
                    targetFormat = "zip",
                    groupIndex = 0
                )
            )

            // Face 2: Exact 6 format slices from Windows HOOT (Screenshot 4)
            // PNG, JPG, WEBP, GIF, PDF, Trace [SVG]
            val face2 = listOf(
                DonutSlice(
                    id = "fmt-png",
                    label = "PNG",
                    caption = null,
                    targetFormat = "png",
                    groupIndex = 1
                ),
                DonutSlice(
                    id = "fmt-jpg",
                    label = "JPG",
                    caption = null,
                    targetFormat = "jpg",
                    groupIndex = 1
                ),
                DonutSlice(
                    id = "fmt-webp",
                    label = "WEBP",
                    caption = null,
                    targetFormat = "webp",
                    groupIndex = 1
                ),
                DonutSlice(
                    id = "fmt-gif",
                    label = "GIF",
                    caption = if (src == "gif") "First frame" else null,
                    targetFormat = "gif",
                    groupIndex = 1,
                    isEnabled = findEngine(src, "gif") != null
                ),
                DonutSlice(
                    id = "fmt-pdf",
                    label = "PDF",
                    caption = when (src) { "gif" -> "First frame"; "pdf" -> null; else -> "Single image" },
                    targetFormat = "pdf",
                    groupIndex = 1,
                    isEnabled = findEngine(src, "pdf") != null
                ),
                DonutSlice(
                    id = "fmt-trace",
                    label = "Trace",
                    caption = "SVG",
                    targetFormat = "svg",
                    groupIndex = 1,
                    isEnabled = findEngine(src, "svg") != null
                )
            )

            return Pair(face1, face2)
        }

        // Non-image generic fallback
        val allTargets = mutableSetOf<String>()
        for (engine in engines) {
            allTargets.addAll(engine.getSupportedTargets(src))
        }

        val primaryFormats = listOf("png", "jpg", "webp", "pdf", "html", "m4a", "txt")
        val face1Targets = primaryFormats.filter { allTargets.contains(it) }
        val remaining = allTargets.filter { !face1Targets.contains(it) }

        val face1List = face1Targets.map { format ->
            DonutSlice(
                id = format,
                label = format.uppercase(Locale.ROOT),
                caption = when (format) {
                    "png" -> "Lossless image"
                    "jpg" -> "Compressed photo"
                    "webp" -> "Modern web image"
                    "pdf" -> "PDF document"
                    "html" -> "Web document"
                    "m4a" -> "Audio file"
                    "txt" -> "Extract text"
                    else -> null
                },
                targetFormat = format,
                groupIndex = 0
            )
        }

        val toolSlices = mutableListOf<DonutSlice>()
        if (src == "pdf") {
            toolSlices.add(createResizerSlice(src))
        }

        val face1Slices = face1List + toolSlices

        val face2Slices = remaining.map { format ->
            DonutSlice(
                id = format,
                label = format.uppercase(Locale.ROOT),
                caption = when (format) {
                    "zip" -> "Archive"
                    "txt" -> "Plain text"
                    "aac" -> "AAC audio"
                    "extract" -> "Extract all"
                    else -> null
                },
                targetFormat = format,
                groupIndex = 1
            )
        }

        return Pair(face1Slices, face2Slices)
    }

    private fun createResizerSlice(src: String): DonutSlice {
        // Windows HOOT Resizer Group: 7 subwheels (Screenshot 3)
        // 25 MB, 15 MB, 10 MB, 5 MB, 2 MB, 1 MB, Compress
        val subSlices = listOf(
            DonutSlice(
                id = "size-compress",
                label = "Compress",
                caption = null,
                targetFormat = src,
                resizePercent = 100
            ),
            DonutSlice(
                id = "size-1mb",
                label = "1 MB",
                caption = null,
                targetFormat = src,
                targetMaxMb = 1
            ),
            DonutSlice(
                id = "size-2mb",
                label = "2 MB",
                caption = null,
                targetFormat = src,
                targetMaxMb = 2
            ),
            DonutSlice(
                id = "size-5mb",
                label = "5 MB",
                caption = null,
                targetFormat = src,
                targetMaxMb = 5
            ),
            DonutSlice(
                id = "size-10mb",
                label = "10 MB",
                caption = null,
                targetFormat = src,
                targetMaxMb = 10
            ),
            DonutSlice(
                id = "size-15mb",
                label = "15 MB",
                caption = null,
                targetFormat = src,
                targetMaxMb = 15
            ),
            DonutSlice(
                id = "size-25mb",
                label = "25 MB",
                caption = null,
                targetFormat = src,
                targetMaxMb = 25
            )
        )

        return DonutSlice(
            id = "resizer-group",
            label = "Resizer",
            caption = "Group of 7",
            targetFormat = src,
            isGroup = true,
            subSlices = subSlices
        )
    }

    private fun createRotateSlice(src: String): DonutSlice {
        // Windows HOOT Rotate Group: 3 subwheels (Screenshot 1)
        // Left, Right, 180°
        val subSlices = listOf(
            DonutSlice(
                id = "rot-left",
                label = "Left",
                caption = null,
                targetFormat = src,
                rotationDegrees = 270
            ),
            DonutSlice(
                id = "rot-right",
                label = "Right",
                caption = null,
                targetFormat = src,
                rotationDegrees = 90
            ),
            DonutSlice(
                id = "rot-180",
                label = "180°",
                caption = null,
                targetFormat = src,
                rotationDegrees = 180
            )
        )

        return DonutSlice(
            id = "rotate-group",
            label = "Rotate",
            caption = "Group of 3",
            targetFormat = src,
            isGroup = true,
            subSlices = subSlices
        )
    }

    private fun createCropSlice(src: String): DonutSlice {
        val subSlices = listOf(
            DonutSlice(
                id = "crop-1-1",
                label = "1:1",
                caption = "Square post",
                targetFormat = src,
                cropAspectRatio = 1.0f
            ),
            DonutSlice(
                id = "crop-16-9",
                label = "16:9",
                caption = "Widescreen",
                targetFormat = src,
                cropAspectRatio = 16f / 9f
            ),
            DonutSlice(
                id = "crop-4-3",
                label = "4:3",
                caption = "Standard photo",
                targetFormat = src,
                cropAspectRatio = 4f / 3f
            ),
            DonutSlice(
                id = "crop-9-16",
                label = "9:16",
                caption = "Story & reel",
                targetFormat = src,
                cropAspectRatio = 9f / 16f
            ),
            DonutSlice(
                id = "crop-3-2",
                label = "3:2",
                caption = "Classic 35mm",
                targetFormat = src,
                cropAspectRatio = 3f / 2f
            )
        )

        return DonutSlice(
            id = "crop-tool",
            label = "Crop",
            caption = "Choose ratio",
            targetFormat = src,
            isGroup = true,
            subSlices = subSlices
        )
    }
}
