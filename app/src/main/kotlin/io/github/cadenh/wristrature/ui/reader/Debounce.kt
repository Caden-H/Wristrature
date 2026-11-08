package io.github.cadenh.wristrature.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [action] once, [quietMs] after the last [poke]. Poked on every scroll step (up to 60 times a second during auto
 * scroll), so a poke only stores a timestamp. A single coroutine waits and is restarted only after it has run, which
 * avoids launching and cancelling a coroutine per step.
 */
class Debounce(private val scope: CoroutineScope, private val quietMs: Long, private val action: suspend () -> Unit) {
    @Volatile private var last = 0L
    private var job: Job? = null

    fun poke() {
        last = nowMs()
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                while (true) {
                    val wait = last + quietMs - nowMs()
                    if (wait <= 0) break
                    delay(wait)
                }
                val ran = last
                action()
                // A poke that arrived while the action ran is still pending: wait out its quiet time and run again.
                if (last == ran) break
            }
        }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L

    fun cancel() {
        job?.cancel()
    }
}
