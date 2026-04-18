package com.github.alondero.nestlin.compare

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class FrameDifferTest {

    @Test
    fun `identical images return match`() {
        val img = createTestImage(10, 10, Color.BLUE)
        val pathA = tempFile("a.png")
        val pathB = tempFile("b.png")
        ImageIO.write(img, "PNG", pathA.toFile())
        ImageIO.write(img, "PNG", pathB.toFile())

        val result = diff(pathA, pathB)

        assertTrue(result.match)
        assertEquals(0, result.mismatchedPixels)
        assertEquals(100, result.totalPixels)
        assertNull(result.firstMismatch)
    }

    @Test
    fun `single pixel difference returns mismatch with correct location`() {
        val imgA = createTestImage(10, 10, Color.BLUE)
        val imgB = createTestImage(10, 10, Color.BLUE)
        imgB.setRGB(5, 7, Color.RED.rgb)

        val pathA = tempFile("a.png")
        val pathB = tempFile("b.png")
        ImageIO.write(imgA, "PNG", pathA.toFile())
        ImageIO.write(imgB, "PNG", pathB.toFile())

        val result = diff(pathA, pathB)

        assertFalse(result.match)
        assertEquals(1, result.mismatchedPixels)
        assertEquals(100, result.totalPixels)
        assertEquals(5 to 7, result.firstMismatch)
    }

    @Test
    fun `completely different images returns all pixels mismatched`() {
        val imgA = createTestImage(10, 10, Color.BLUE)
        val imgB = createTestImage(10, 10, Color.RED)

        val pathA = tempFile("a.png")
        val pathB = tempFile("b.png")
        ImageIO.write(imgA, "PNG", pathA.toFile())
        ImageIO.write(imgB, "PNG", pathB.toFile())

        val result = diff(pathA, pathB)

        assertFalse(result.match)
        assertEquals(100, result.mismatchedPixels)
        assertEquals(100, result.totalPixels)
    }

    @Test
    fun `different dimensions returns mismatch`() {
        val imgA = createTestImage(10, 10, Color.BLUE)
        val imgB = createTestImage(20, 10, Color.BLUE)

        val pathA = tempFile("a.png")
        val pathB = tempFile("b.png")
        ImageIO.write(imgA, "PNG", pathA.toFile())
        ImageIO.write(imgB, "PNG", pathB.toFile())

        val result = diff(pathA, pathB)

        assertFalse(result.match)
        assertEquals(100, result.mismatchedPixels)
        assertEquals(100, result.totalPixels)
    }

    @Test
    fun `writeDiffImage creates side by side image`() {
        val imgA = createTestImage(3, 2, Color.BLUE)
        val imgB = createTestImage(3, 2, Color.BLUE)
        imgB.setRGB(1, 0, Color.RED.rgb)

        val pathA = tempFile("a.png")
        val pathB = tempFile("b.png")
        val diffPath = tempFile("diff.png")
        ImageIO.write(imgA, "PNG", pathA.toFile())
        ImageIO.write(imgB, "PNG", pathB.toFile())

        writeDiffImage(pathA, pathB, diffPath)

        val diffImg = ImageIO.read(diffPath.toFile())
        assertEquals(6, diffImg.width)
        assertEquals(2, diffImg.height)
        assertEquals(Color.RED.rgb, diffImg.getRGB(4, 0))
    }

    private fun createTestImage(width: Int, height: Int, color: Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, color.rgb)
            }
        }
        return img
    }

    private fun tempFile(name: String): Path {
        return Files.createTempFile(name, ".png")
    }
}
