package com.ehviewer

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/** Rasterize EhViewer's own Android mark; reject unsupported vector changes instead of dropping paths. */
object PlatformIcons {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private data class Mark(val path: Path2D.Double, val fill: Color, val stroke: Color, val width: Float)

    private fun color(value: String): Color = when (value.length) {
        7 -> Color(value.removePrefix("#").toInt(16))
        9 -> Color(value.removePrefix("#").toLong(16).toInt(), true)
        else -> error("Unsupported icon color")
    }

    private fun path(data: String, evenOdd: Boolean): Path2D.Double {
        val token = Regex("[MLHVCZ]|-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")
        require(token.replace(data, "").all { it.isWhitespace() || it == ',' }) { "Unsupported icon path command" }
        val tokens = token.findAll(data).map { it.value }.toList()
        require(tokens.size in 1..4096)
        var index = 0
        var command = ' '
        val result = Path2D.Double(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
        fun number(): Double = tokens[index++].toDouble().also { require(it.isFinite() && it in -100_000.0..100_000.0) }
        while (index < tokens.size) {
            if (tokens[index].singleOrNull()?.isLetter() == true) command = tokens[index++].single()
            when (command) {
                'M' -> {
                    result.moveTo(number(), number())
                    command = 'L'
                }
                'L' -> result.lineTo(number(), number())
                'H' -> result.lineTo(number(), checkNotNull(result.currentPoint).y)
                'V' -> result.lineTo(checkNotNull(result.currentPoint).x, number())
                'C' -> result.curveTo(number(), number(), number(), number(), number(), number())
                'Z' -> {
                    result.closePath()
                    command = ' '
                }
                else -> error("Invalid icon path")
            }
        }
        return result
    }

    fun render(vector: File, size: Int, mobile: Boolean): BufferedImage {
        require(size in 16..1024 && vector.length() in 1..65_536)
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder().parse(vector)
        val root = document.documentElement
        require(root.tagName == "vector")
        val groups = root.getElementsByTagName("group")
        require(groups.length == 1)
        val group = groups.item(0) as Element
        require(group.getAttributeNS(ANDROID, "scaleX").toDouble() == group.getAttributeNS(ANDROID, "scaleY").toDouble())
        require(!group.hasAttributeNS(ANDROID, "rotation") && group.getElementsByTagName("clip-path").length == 0)
        val paths = group.getElementsByTagName("path")
        require(paths.length in 1..64)
        val marks = (0 until paths.length).map { index ->
            val item = paths.item(index) as Element
            val stroke = item.getAttributeNS(ANDROID, "strokeColor")
            require(stroke.isEmpty() || item.getAttributeNS(ANDROID, "strokeLineCap") == "round")
            Mark(
                path(item.getAttributeNS(ANDROID, "pathData"), item.getAttributeNS(ANDROID, "fillType") == "evenOdd"),
                color(item.getAttributeNS(ANDROID, "fillColor")),
                if (stroke.isEmpty()) Color(0, true) else color(stroke),
                item.getAttributeNS(ANDROID, "strokeWidth").takeIf(String::isNotEmpty)?.toFloat() ?: 0f,
            )
        }
        var bounds: Rectangle2D? = null
        for (mark in marks) {
            val outline = if (mark.stroke.alpha > 0) BasicStroke(mark.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER).createStrokedShape(mark.path) else mark.path
            bounds = bounds?.createUnion(outline.bounds2D) ?: outline.bounds2D
        }
        val box = checkNotNull(bounds)
        require(box.width > 0 && box.height > 0)
        val image = BufferedImage(size, size, if (mobile) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            graphics.color = Color.WHITE
            if (mobile) graphics.fillRect(0, 0, size, size) else graphics.fill(RoundRectangle2D.Double(size * .1, size * .1, size * .8, size * .8, size * .36, size * .36))
            val scale = size * (if (mobile) .63 else .53) / maxOf(box.width, box.height)
            graphics.translate(size / 2.0, size / 2.0)
            graphics.scale(scale, scale)
            graphics.translate(-box.centerX, -box.centerY)
            for (mark in marks) {
                graphics.color = mark.fill
                graphics.fill(mark.path)
                if (mark.stroke.alpha > 0) {
                    graphics.color = mark.stroke
                    graphics.stroke = BasicStroke(mark.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER)
                    graphics.draw(mark.path)
                }
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().use {
        check(ImageIO.write(image, "png", it))
        it.toByteArray()
    }

    fun generate(vector: File, output: File) {
        output.mkdirs()
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512, 1024)
        val images = sizes.associateWith { png(render(vector, it, mobile = false)) }
        output.resolve("EhViewer.png").writeBytes(images.getValue(512))
        val icoSizes = sizes.filter { it <= 256 }
        val header = ByteBuffer.allocate(6 + icoSizes.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(icoSizes.size.toShort())
        var offset = header.capacity()
        for (size in icoSizes) {
            val bytes = images.getValue(size)
            header.put((size % 256).toByte()).put((size % 256).toByte()).put(0).put(0)
            header.putShort(1).putShort(32).putInt(bytes.size).putInt(offset)
            offset += bytes.size
        }
        output.resolve("EhViewer.ico").outputStream().use { stream ->
            stream.write(header.array())
            icoSizes.forEach { stream.write(images.getValue(it)) }
        }
        val types = mapOf(16 to "icp4", 32 to "icp5", 64 to "icp6", 128 to "ic07", 256 to "ic08", 512 to "ic09", 1024 to "ic10")
        val total = 8 + types.keys.sumOf { 8 + images.getValue(it).size }
        output.resolve("EhViewer.icns").outputStream().use { stream ->
            stream.write("icns".toByteArray(Charsets.US_ASCII))
            stream.write(ByteBuffer.allocate(4).putInt(total).array())
            for ((size, type) in types) {
                val bytes = images.getValue(size)
                stream.write(type.toByteArray(Charsets.US_ASCII))
                stream.write(ByteBuffer.allocate(4).putInt(bytes.size + 8).array())
                stream.write(bytes)
            }
        }
        val catalog = output.resolve("AppIcons.xcassets/AppIcon.appiconset").also { it.mkdirs() }
        catalog.resolve("AppIcon-1024.png").writeBytes(png(render(vector, 1024, mobile = true)))
        catalog.resolve("Contents.json").writeText("""{"images":[{"filename":"AppIcon-1024.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"EhViewer","version":1}}""" + "\n")
        catalog.parentFile.resolve("Contents.json").writeText("""{"info":{"author":"EhViewer","version":1}}""" + "\n")
    }
}

abstract class GeneratePlatformIcons : DefaultTask() {
    @get:InputFile abstract val vector: RegularFileProperty

    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun generate() = PlatformIcons.generate(vector.get().asFile, destination.get().asFile)
}
