package io.github.cadenh.wristrature.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DebounceTest {
    @Test fun manyPokesRunTheActionOnceAfterTheQuietTime() = runBlocking {
        val runs = AtomicInteger()
        val d = Debounce(CoroutineScope(Dispatchers.Default), 120) { runs.incrementAndGet() }
        repeat(20) { d.poke(); delay(10) }
        assertEquals("still inside the quiet time", 0, runs.get())
        delay(400)
        assertEquals(1, runs.get())
        d.poke()
        delay(400)
        assertEquals("a later poke starts a new wait", 2, runs.get())
    }

    @Test fun cancelStopsAPendingRun() = runBlocking {
        val runs = AtomicInteger()
        val d = Debounce(CoroutineScope(Dispatchers.Default), 100) { runs.incrementAndGet() }
        d.poke()
        d.cancel()
        delay(300)
        assertEquals(0, runs.get())
    }
}
