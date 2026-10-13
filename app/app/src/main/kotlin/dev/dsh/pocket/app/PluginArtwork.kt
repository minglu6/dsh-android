package dev.dsh.pocket.app

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.util.Base64
import android.util.Xml
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import java.io.StringReader
import java.net.URLDecoder
import org.xmlpull.v1.XmlPullParser
import kotlin.math.ceil

/**
 * Fixed-palette, 36×36 artwork from deepseek-harness's
 * packages/client/ui-primitives/src/plugin-artwork.tsx.
 * Paths, gradients and paints are cached; no theme tint is applied.
 */
@Composable
fun PluginArtwork(kind: String, modifier: Modifier = Modifier) {
    val shapes = remember(kind) { pluginArtworkShapes(kind) }
    Canvas(modifier.size(36.dp)) {
        val scale = size.minDimension / 36f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val checkpoint = native.save()
            try {
                native.translate((size.width - 36f * scale) / 2f, (size.height - 36f * scale) / 2f)
                native.scale(scale, scale)
                for (index in shapes.indices) {
                    val shape = shapes[index]
                    native.drawPath(shape.path, shape.paint)
                }
            } finally {
                native.restoreToCount(checkpoint)
            }
        }
    }
}

/**
 * Renders Host metadata artwork as a cached, fixed-palette SVG picture.
 * Only bounded inline SVG data URIs are accepted; no external resource loader
 * is registered. Missing, invalid or unsupported artwork uses the official default.
 */
@Composable
fun PluginMetadataArtwork(icon: String?, modifier: Modifier = Modifier) {
    val picture = remember(icon) { pluginMetadataPicture(icon) }
    if (picture == null) {
        PluginArtwork("default", modifier)
        return
    }
    Spacer(modifier.size(36.dp).drawWithCache {
        val side = size.minDimension
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val destination = RectF(left, top, left + side, top + side)
        // Hardware Canvas supports Picture from API 28. Rasterize once at the
        // actual output size on API 26/27, not on each frame.
        val bitmap = if (Build.VERSION.SDK_INT < 28 && size.width > 0f && size.height > 0f) {
            Bitmap.createBitmap(
                ceil(size.width).toInt(),
                ceil(size.height).toInt(),
                Bitmap.Config.ARGB_8888,
            ).also { android.graphics.Canvas(it).drawPicture(picture, destination) }
        } else {
            null
        }
        onDrawBehind {
            if (size.width > 0f && size.height > 0f) {
                drawIntoCanvas { canvas ->
                    if (bitmap != null) {
                        canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
                    } else {
                        canvas.nativeCanvas.drawPicture(picture, destination)
                    }
                }
            }
        }
    })
}

// Matches the Host package-metadata limit, before parsing or rendering.
private const val PLUGIN_SVG_MAX_BYTES = 256 * 1024
private val pluginSvgDeclaration = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
private val pluginSvgCssUrl = Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
private val pluginSvgUnsupportedElements = setOf(
    "script", "foreignobject", "image", "iframe", "object", "embed",
    "animate", "animatetransform", "animatemotion", "set", "filter",
)

private fun pluginMetadataPicture(icon: String?): Picture? {
    if (icon == null || icon.length > PLUGIN_SVG_MAX_BYTES * 3 + 64) return null
    return try {
        val xml = when {
            icon.startsWith("data:image/svg+xml;base64,", ignoreCase = true) -> {
                val bytes = Base64.decode(icon.substringAfter(','), Base64.DEFAULT)
                if (bytes.size > PLUGIN_SVG_MAX_BYTES) return null
                bytes.toString(Charsets.UTF_8)
            }
            icon.startsWith("data:image/svg+xml,", ignoreCase = true) -> {
                // A plus in a data URI is literal, not a form-encoded space.
                URLDecoder.decode(icon.substringAfter(',').replace("+", "%2B"), "UTF-8")
            }
            else -> return null
        }
        if (xml.toByteArray(Charsets.UTF_8).size > PLUGIN_SVG_MAX_BYTES ||
            !isSupportedPluginSvg(xml)
        ) return null
        SVG.getFromString(xml).renderToPicture(36, 36)
    } catch (_: Exception) {
        null
    }
}

private fun isSupportedPluginSvg(xml: String): Boolean {
    // Reject declarations before handing the string to either XML parser.
    if (pluginSvgDeclaration.containsMatchIn(xml)) return false
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(StringReader(xml))
    var elements = 0
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val name = parser.name.lowercase()
                if (++elements > 8192 || parser.depth > 64 ||
                    (elements == 1 && (name != "svg" ||
                        parser.namespace !in setOf("", "http://www.w3.org/2000/svg"))) ||
                    name in pluginSvgUnsupportedElements
                ) return false
                for (index in 0 until parser.attributeCount) {
                    val attribute = parser.getAttributeName(index).lowercase()
                    val value = parser.getAttributeValue(index).trim()
                    if (attribute.startsWith("on") ||
                        (attribute == "href" && !value.startsWith("#")) ||
                        !isLocalPluginSvgCss(value)
                    ) return false
                }
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                if (!isLocalPluginSvgCss(parser.text)) return false
            }
            XmlPullParser.DOCDECL, XmlPullParser.PROCESSING_INSTRUCTION -> return false
        }
        event = parser.nextToken()
    }
    return elements > 0
}

private fun isLocalPluginSvgCss(value: String): Boolean {
    // CSS escapes could conceal @import or a resource URL. They are not needed
    // by these static artwork assets; reject instead of interpreting them.
    if ('\\' in value || value.contains("@import", ignoreCase = true)) return false
    return pluginSvgCssUrl.findAll(value).all { match ->
        match.groupValues[1].trim().trim('\'', '"').startsWith("#")
    }
}

private class PluginArtworkShape(val path: Path, val paint: Paint)

private fun artworkPath(data: String): Path = PathParser().parsePathString(data).toPath().asAndroidPath()

private fun artworkPaint(color: Long, strokeWidth: Float = 0f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toInt()
    if (strokeWidth > 0f) {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        strokeMiter = 4f
    }
}

private fun artworkGradient(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    first: Long,
    last: Long,
    opacity: Int = 255,
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    shader = LinearGradient(x1, y1, x2, y2, first.toInt(), last.toInt(), Shader.TileMode.CLAMP)
    alpha = opacity
}

private fun artworkRect(x: Float, y: Float, width: Float, height: Float, radius: Float): Path = Path().apply {
    addRoundRect(x, y, x + width, y + height, radius, radius, Path.Direction.CW)
}

private fun pluginArtworkShapes(kind: String): List<PluginArtworkShape> = when (kind) {
    "terminal" -> {
        val paint = artworkPaint(0xFF679EFE, strokeWidth = 3.5f)
        listOf(
            PluginArtworkShape(
                artworkPath("M10 11L16.606 17.606C16.6841 17.6841 16.6841 17.8107 16.606 17.8888L10 24.4948"),
                paint,
            ),
            PluginArtworkShape(artworkPath("M20.1211 24.4946H26.8685"), paint),
        )
    }
    "loop" -> listOf(
        PluginArtworkShape(
            artworkPath("M18.0486 28.4901C12.0858 28.4901 7.25195 23.6562 7.25195 17.6934C13.2148 17.6934 18.0486 22.5272 18.0486 28.4901Z"),
            artworkPaint(0xFFA797FC),
        ),
        PluginArtworkShape(
            artworkPath("M18.0486 6.89667C12.0858 6.89667 7.25195 11.7305 7.25195 17.6934C13.2148 17.6934 18.0486 12.8595 18.0486 6.89667Z"),
            artworkGradient(16.7911f, 16.1655f, 8.98192f, 8.74289f, 0xFFA23AE7, 0xFFE2E2E2),
        ),
        PluginArtworkShape(
            artworkPath("M18.0485 28.4901C24.0114 28.4901 28.8452 23.6562 28.8452 17.6934C22.8824 17.6934 18.0485 22.5272 18.0485 28.4901Z"),
            artworkPaint(0xFF4561EE),
        ),
        PluginArtworkShape(
            artworkPath("M18.0485 6.89667C24.0114 6.89667 28.8452 11.7305 28.8452 17.6934C22.8824 17.6934 18.0485 12.8595 18.0485 6.89667Z"),
            artworkPaint(0xFF658EFF),
        ),
    )
    "subagent" -> listOf(
        PluginArtworkShape(
            artworkRect(14.9893f, 15.1877f, 12.2365f, 12.2365f, 2f),
            artworkGradient(21.1075f, 15.1877f, 21.1075f, 27.4243f, 0xFF45E7A4, 0xFF05909D, opacity = 204),
        ),
        PluginArtworkShape(
            artworkRect(8.87109f, 9.0697f, 12.2365f, 12.2365f, 2f),
            artworkGradient(14.9894f, 9.0697f, 14.9894f, 21.3063f, 0xFF69B9FF, 0xFF324DE2, opacity = 204),
        ),
    )
    "search" -> {
        // CSS conic-gradient(from 90deg) and Android SweepGradient both start
        // at the positive X axis and sweep clockwise. Preserve the SVG group's
        // complete transform on the shader, without transforming the ring path.
        val ringGradient = SweepGradient(
            0f,
            0f,
            intArrayOf(0xFF41E1AC.toInt(), 0xFF658EFF.toInt(), 0xFF41E1AC.toInt()),
            floatArrayOf(0f, 62.0619f / 360f, 1f),
        ).apply {
            setLocalMatrix(Matrix().apply {
                setValues(floatArrayOf(
                    0.0119394f, 0.00173904f, 15.7661f,
                    -0.00173904f, 0.0119394f, 16.2159f,
                    0f, 0f, 1f,
                ))
            })
        }
        listOf(
            PluginArtworkShape(
                artworkPath("M26.5362 26.9865L22.3813 22.8317"),
                artworkPaint(0xFF658EFF, strokeWidth = 3f),
            ),
            PluginArtworkShape(
                artworkPath(
                    "M21.9717 16.2159H19.9717C19.9717 18.5386 18.0888 20.4215 15.7661 20.4215V22.4215V24.4215C20.2979 24.4215 23.9717 20.7478 23.9717 16.2159H21.9717Z" +
                        "M15.7661 22.4215V20.4215C13.4434 20.4215 11.5605 18.5386 11.5605 16.2159H9.56055H7.56055C7.56055 20.7478 11.2343 24.4215 15.7661 24.4215V22.4215Z" +
                        "M9.56055 16.2159H11.5605C11.5605 13.8933 13.4434 12.0104 15.7661 12.0104V10.0104V8.01038C11.2343 8.01038 7.56055 11.6841 7.56055 16.2159H9.56055Z" +
                        "M15.7661 10.0104V12.0104C18.0888 12.0104 19.9717 13.8933 19.9717 16.2159H21.9717H23.9717C23.9717 11.6841 20.2979 8.01038 15.7661 8.01038V10.0104Z",
                ),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = ringGradient },
            ),
        )
    }
    else -> listOf(
        PluginArtworkShape(
            artworkPath(
                "M24.6294 8.63696C26.2862 8.63705 27.6294 9.98016 27.6294 11.637V12.7825C27.6292 14.4391 26.2861 15.7824 24.6294 15.7825H23.4839C22.6519 15.7825 21.5454 16.3459 21.5454 17.1778V18.1573C21.5454 18.7757 22.216 19.1966 22.8345 19.1965H24.6294C26.2861 19.1965 27.6292 20.5398 27.6294 22.1965V23.9924C27.6292 25.6491 26.2861 26.9924 24.6294 26.9924H22.8345C21.1778 26.9924 19.8347 25.649 19.8345 23.9924V22.1965C19.8345 21.7148 19.4957 21.2327 19.014 21.2327H16.4792C15.7975 21.2327 15.3374 22.0061 15.3374 22.6877V23.8333C15.3373 25.49 13.9942 26.8333 12.3374 26.8333H11.1919C9.53509 26.8333 8.19198 25.49 8.19189 23.8333V22.6877C8.19189 21.0309 9.53504 19.6877 11.1919 19.6877H12.3374C13.1964 19.6877 14.3999 19.0959 14.3999 18.2369V16.0185C14.3999 14.9518 15.2646 14.0872 16.3312 14.0872H19.435C20.0596 14.0872 20.484 13.4071 20.4839 12.7825V11.637C20.4839 9.98011 21.827 8.63696 23.4839 8.63696H24.6294Z" +
                    "M13.4116 11.2468C13.4116 12.6882 12.2431 13.8567 10.8018 13.8567C9.36037 13.8567 8.19189 12.6882 8.19189 11.2468C8.19189 9.80544 9.36037 8.63696 10.8018 8.63696C12.2431 8.63696 13.4116 9.80544 13.4116 11.2468Z",
            ),
            artworkGradient(15.1481f, 9.94188f, 15.1481f, 13.6375f, 0xFF54ECE7, 0xFF658EFF),
        ),
    )
}
