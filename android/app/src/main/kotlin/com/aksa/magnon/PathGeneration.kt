package com.aksa.magnon

import java.util.concurrent.atomic.AtomicLong

/** A hello may publish only while the generation that started it is still current. */
class PathGeneration {
    private val current = AtomicLong(0)

    fun next(): Long = current.incrementAndGet()

    fun accept(started: Long): Boolean = started == current.get()
}
