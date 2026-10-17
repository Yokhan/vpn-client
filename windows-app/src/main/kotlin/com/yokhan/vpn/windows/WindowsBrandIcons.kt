package com.yokhan.gates.windows

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal object WindowsBrandIcons {
    val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

    fun load(requestedSize: Int): BufferedImage {
        require(requestedSize > 0) { "Icon size must be positive" }
        val size = sizes.firstOrNull { it >= requestedSize } ?: sizes.last()
        return requireNotNull(javaClass.getResourceAsStream("/branding/gates-$size.png")) {
            "GATES icon resource missing: $size px"
        }.use { input -> requireNotNull(ImageIO.read(input)) { "Invalid GATES icon: $size px" } }
    }
}
