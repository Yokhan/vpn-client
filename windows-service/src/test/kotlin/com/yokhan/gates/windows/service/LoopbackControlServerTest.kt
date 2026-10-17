package com.yokhan.gates.windows.service

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoopbackControlServerTest {

    @Test
    fun readsOnlyBoundedLengthPrefixedUtf8Frames() {
        val payload = "2|${"a".repeat(64)}|STATUS|".toByteArray()
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        assertEquals(String(payload), readLengthPrefixedFrame(DataInputStream(ByteArrayInputStream(frame))))
    }

    @Test
    fun rejectsOversizedLengthPrefixedFramesWithoutAllocatingPayload() {
        val header = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(MAX_FRAME_BYTES + 1).array()

        assertFailsWith<InvalidFrameException> {
            readLengthPrefixedFrame(DataInputStream(ByteArrayInputStream(header)))
        }
    }
}
