package io.github.cadenh.wristrature.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private fun remote(pct: Double, progress: String = "/body/DocFragment[3]/body/p[9]/text().0", deviceId: String = "other") =
        KoProgress("doc", progress, pct, "Boox", deviceId)

    private val here = LocalPlace("/body/DocFragment[2]/body/p[1]/text().0", 0.30)

    @Test fun `no remote place means nothing to do`() =
        assertEquals(PullDecision.None, SyncPolicy.decidePull(here, null, "me", JumpMode.ASK, false))

    @Test fun `a place written by this watch is ignored`() =
        assertEquals(PullDecision.None, SyncPolicy.decidePull(here, remote(0.9, deviceId = "me"), "me", JumpMode.AUTO, false))

    @Test fun `the same place is ignored`() =
        assertEquals(PullDecision.None, SyncPolicy.decidePull(here, remote(0.9, progress = here.progress), "me", JumpMode.AUTO, false))

    @Test fun `a remote place behind us never moves us back`() =
        assertEquals(PullDecision.None, SyncPolicy.decidePull(here, remote(0.10), "me", JumpMode.AUTO, false))

    @Test fun `a place within rounding is ignored`() =
        assertEquals(PullDecision.None, SyncPolicy.decidePull(here, remote(0.3005), "me", JumpMode.AUTO, false))

    @Test fun `a newer place from another device is offered even when its percent is lower`() {
        // The e-ink reader counts pages, so the same spot is a smaller percent there.
        val mine = LocalPlace(here.progress, 0.30, changedAtMs = 1_000_000_000_000)
        val other = KoProgress("doc", "/body/DocFragment[3]/body", 0.27, "Crosspoint", "x4", timestamp = 1_000_000_600)
        assertTrue(SyncPolicy.decidePull(mine, other, "me", JumpMode.ASK, false) is PullDecision.Offer)
        // Never a jump on its own: it may be behind.
        assertTrue(SyncPolicy.decidePull(mine, other, "me", JumpMode.AUTO, false) is PullDecision.Offer)
    }

    @Test fun `an older place from another device that is behind is ignored`() {
        val mine = LocalPlace(here.progress, 0.30, changedAtMs = 1_000_000_000_000)
        val older = KoProgress("doc", "/body/DocFragment[1]/body", 0.10, "Crosspoint", "x4", timestamp = 999_999_000)
        assertEquals(PullDecision.None, SyncPolicy.decidePull(mine, older, "me", JumpMode.ASK, false))
    }

    @Test fun `ahead in ask mode is an offer`() {
        val d = SyncPolicy.decidePull(here, remote(0.58), "me", JumpMode.ASK, false)
        assertTrue(d is PullDecision.Offer)
    }

    @Test fun `ahead in auto mode jumps if the user has not scrolled`() {
        val d = SyncPolicy.decidePull(here, remote(0.58), "me", JumpMode.AUTO, userHasScrolled = false)
        assertTrue(d is PullDecision.Jump)
    }

    @Test fun `ahead in auto mode only offers once the user scrolled`() {
        val d = SyncPolicy.decidePull(here, remote(0.58), "me", JumpMode.AUTO, userHasScrolled = true)
        assertTrue(d is PullDecision.Offer)
    }

    @Test fun `a book with no local place treats the remote place as ahead`() {
        val d = SyncPolicy.decidePull(null, remote(0.42), "me", JumpMode.AUTO, false)
        assertTrue(d is PullDecision.Jump)
    }

    @Test fun `push throttle needs movement and time`() {
        var now = 1_000_000L
        val t = PushThrottle(minScreens = 3, minIntervalMs = 60_000) { now }
        assertFalse(t.onScreenScrolled())
        assertFalse(t.onScreenScrolled())
        assertTrue(t.onScreenScrolled()) // first send needs no wait
        t.onPushed()
        assertFalse(t.hasUnsentMovement())
        repeat(3) { t.onScreenScrolled() }
        assertFalse(t.onScreenScrolled()) // 4 screens but only 0 ms since the last send
        now += 61_000
        assertTrue(t.onScreenScrolled())
        assertTrue(t.hasUnsentMovement())
    }
}
