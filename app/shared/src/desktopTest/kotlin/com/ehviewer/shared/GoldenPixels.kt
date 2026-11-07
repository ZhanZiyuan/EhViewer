package com.ehviewer.shared

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/** Exact pixels in an explicitly declared app region; no scaling, registration or hidden masks. */
internal data class GoldenPixelResult(val compared: Int, val different: Int, val bounds: Rectangle?) {
    val passed get() = different == 0
}

internal fun compareGoldenPixels(expected: BufferedImage, actual: BufferedImage, region: Rectangle): GoldenPixelResult {
    require(expected.width == actual.width && expected.height == actual.height) { "Viewport sizes differ; capture the same viewport first" }
    require(region.width > 0 && region.height > 0 && Rectangle(0, 0, expected.width, expected.height).contains(region)) { "Region is outside the actual rendered viewport" }
    var count = 0
    var bounds: Rectangle? = null
    for (y in region.y until region.y + region.height) {
        for (x in region.x until region.x + region.width) {
            if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                count++
                val previous = bounds
                bounds = previous?.union(Rectangle(x, y, 1, 1)) ?: Rectangle(x, y, 1, 1)
            }
        }
    }
    return GoldenPixelResult(Math.multiplyExact(region.width, region.height), count, bounds)
}

/** Runs against unedited native screenshots; a failed comparison exits unsuccessfully. */
fun main(args: Array<String>) {
    require(args.size == 7) { "Expected baseline.png candidate.png fresh-output x y width height" }
    val expectedFile = Path.of(args[0]).toRealPath()
    val actualFile = Path.of(args[1]).toRealPath()
    val output = Path.of(args[2]).toAbsolutePath()
    require(expectedFile != actualFile && !Files.exists(output)) { "Use two distinct raw inputs and fresh evidence output" }
    val region = Rectangle(args[3].toInt(), args[4].toInt(), args[5].toInt(), args[6].toInt())
    val expected = requireNotNull(ImageIO.read(expectedFile.toFile())) { "Baseline PNG cannot be decoded" }
    val actual = requireNotNull(ImageIO.read(actualFile.toFile())) { "Candidate PNG cannot be decoded" }
    val result = compareGoldenPixels(expected, actual, region)
    Files.createDirectories(output)
    val difference = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until region.height) {
        for (x in 0 until region.width) {
            val changed = expected.getRGB(x + region.x, y + region.y) != actual.getRGB(x + region.x, y + region.y)
            difference.setRGB(x, y, if (changed) 0xffff0040.toInt() else 0xff000000.toInt())
        }
    }
    check(ImageIO.write(difference, "png", output.resolve("difference.png").toFile()))
    fun digest(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    val report = Properties().apply {
        setProperty("result", if (result.passed) "PASS" else "FAIL")
        setProperty("baseline_sha256", digest(expectedFile))
        setProperty("candidate_sha256", digest(actualFile))
        setProperty("viewport", "${expected.width}x${expected.height}")
        setProperty("region", "${region.x},${region.y},${region.width},${region.height}")
        setProperty("compared_pixels", result.compared.toString())
        setProperty("different_pixels", result.different.toString())
        setProperty("difference_bounds", result.bounds?.let { "${it.x},${it.y},${it.width},${it.height}" } ?: "none")
        setProperty("channel_tolerance", "0")
        setProperty("alignment", "none")
        setProperty("scaling", "none")
        setProperty("outside_region", "NOT_COMPARED; declare OS chrome separately, never hide app layout")
    }
    Files.newOutputStream(output.resolve("result.properties")).use { report.store(it, "Actual native UI pixel comparison") }
    println("${report.getProperty("result")}: ${result.different}/${result.compared} pixels differ; bounds=${report.getProperty("difference_bounds")}")
    if (!result.passed) exitProcess(1)
}
