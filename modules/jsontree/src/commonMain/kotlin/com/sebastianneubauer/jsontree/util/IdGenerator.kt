package com.sebastianneubauer.jsontree.util

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
internal class IdGenerator {
    private val atomicInt = AtomicInt(0)

    fun incrementAndGet(): Int {
        return atomicInt.incrementAndFetch()
    }
}
