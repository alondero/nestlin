package com.github.alondero.nestlin.compare

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

data class DiffResult(
    val match: Boolean,
    val mismatchedPixels: Int,
    val totalPixels: Int,
    val firstMismatch: Pair<Int, Int>?,
    val matchPercentage: Double
)

fun diff(expected: Path, actual: Path, threshold: Double = 0.0): DiffResult {
    val expectedImg = ImageIO.read(expected.toFile())
    val actualImg = ImageIO.read(actual.toFile())

    if (expectedImg.width != actualImg.width || expectedImg.height != actualImg.height) {
        return DiffResult(
            match = false,
            mismatchedPixels = expectedImg.width * expectedImg.height,
            totalPixels = expectedImg.width * expectedImg.height,
            firstMismatch = null,
            matchPercentage = 0.0
        )
    }

    val width = expectedImg.width
    val height = expectedImg.height
    var mismatched = 0
    var firstMismatch: Pair<Int, Int>? = null

    for (y in 0 until height) {
        for (x in 0 until width) {
            if (expectedImg.getRGB(x, y) != actualImg.getRGB(x, y)) {
                mismatched++
                if (firstMismatch == null) {
                    firstMismatch = x to y
                }
            }
        }
    }

    val totalPixels = width * height
    val matchPercentage = ((totalPixels - mismatched).toDouble() / totalPixels) * 100

    return DiffResult(
        match = matchPercentage >= (100.0 - threshold),
        mismatchedPixels = mismatched,
        totalPixels = totalPixels,
        firstMismatch = firstMismatch,
        matchPercentage = matchPercentage
    )
}

fun writeDiffImage(expected: Path, actual: Path, out: Path) {
    val expectedImg = ImageIO.read(expected.toFile())
    val actualImg = ImageIO.read(actual.toFile())

    val width = expectedImg.width
    val height = expectedImg.height

    val diffImg = BufferedImage(width * 2, height, BufferedImage.TYPE_INT_RGB)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val expectedPixel = expectedImg.getRGB(x, y)
            val actualPixel = actualImg.getRGB(x, y)
            diffImg.setRGB(x, y, expectedPixel)
            diffImg.setRGB(x + width, y, if (expectedPixel != actualPixel) Color.RED.rgb else actualPixel)
        }
    }

    Files.createDirectories(out.parent)
    ImageIO.write(diffImg, "PNG", out.toFile())
}
