package com.shilapi.xcertplay.media

/** Copy big-endian network PCM into the worker's reusable little-endian scratch buffer. */
internal object Pcm16Payload {
    fun size(packet: ByteArray, offset: Int): Int = (packet.size - offset).coerceAtLeast(0) and -2

    fun copy(packet: ByteArray, offset: Int, output: ByteArray): Int {
        require(offset >= 0)
        val length = size(packet, offset)
        require(output.size >= length)
        var index = 0
        while (index < length) {
            output[index] = packet[offset + index + 1]
            output[index + 1] = packet[offset + index]
            index += 2
        }
        return length
    }
}

/** Draining codec output can cross a navigation tail deadline; recheck it before taking input. */
internal object AudioInputPump {
    fun acquire(shouldContinue: () -> Boolean, drain: () -> Unit, dequeue: () -> Int): Int {
        while (shouldContinue()) {
            drain()
            if (!shouldContinue()) return -1
            val index = dequeue()
            if (index >= 0) return index
        }
        return -1
    }
}
