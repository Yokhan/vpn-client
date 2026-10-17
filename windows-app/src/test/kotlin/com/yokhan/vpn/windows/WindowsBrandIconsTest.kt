package com.yokhan.gates.windows

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsBrandIconsTest {
    @Test
    fun allFramesHaveCorrectDimensionsTransparentCornersAndReadableShuttle() {
        for (size in WindowsBrandIcons.sizes) {
            val image = WindowsBrandIcons.load(size)
            assertEquals(size, image.width)
            assertEquals(size, image.height)
            assertEquals(0, image.getRGB(0, 0) ushr 24)
            assertEquals(255, image.getRGB(size / 2, size / 2) ushr 24)
            var whitePixels = 0
            var flamePixels = 0
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = image.getRGB(x, y)
                val mean = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
                val red = pixel ushr 16 and 255
                val green = pixel ushr 8 and 255
                val blue = pixel and 255
                if (pixel ushr 24 > 200 && mean > 140) whitePixels++
                if (pixel ushr 24 > 200 && red > 95 && red > green * 1.25 && green > blue * 1.25) flamePixels++
            }
            assertTrue(whitePixels >= size * size / 40, "No readable white shuttle at $size px")
            assertTrue(flamePixels >= size * size / 100, "Missing orange engine fire at $size px")
        }
    }

    @Test
    fun unsupportedSizeSelectsNextLargerFrameAndInvalidSizeIsRejected() {
        assertEquals(24, WindowsBrandIcons.load(21).width)
        assertEquals(256, WindowsBrandIcons.load(512).width)
        assertFailsWith<IllegalArgumentException> { WindowsBrandIcons.load(0) }
    }

    @Test
    fun windowsIcoHasValidIndependentlyDecodablePngFramesAtEverySize() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/branding/gates.ico")).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.short.toInt())
        assertEquals(1, buffer.short.toInt())
        assertEquals(WindowsBrandIcons.sizes.size, buffer.short.toInt())
        for (size in WindowsBrandIcons.sizes) {
            assertEquals(size % 256, buffer.get().toInt() and 255)
            assertEquals(size % 256, buffer.get().toInt() and 255)
            assertEquals(0, buffer.get().toInt())
            assertEquals(0, buffer.get().toInt())
            assertEquals(1, buffer.short.toInt())
            assertEquals(32, buffer.short.toInt())
            val length = buffer.int
            val offset = buffer.int
            assertTrue(offset >= 6 + 16 * WindowsBrandIcons.sizes.size)
            assertTrue(length > 0 && offset.toLong() + length <= bytes.size)
            val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes, offset, length)))
            assertEquals(size, image.width)
            assertEquals(size, image.height)
        }
    }
}
