package cn.codexremote.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OperationGateTest {
    @Test fun overlappingSameHostIsRejectedNotQueued() = runBlocking {
        val gate = OperationGate(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = launch { gate.run("pc-a") { calls++; entered.complete(Unit); release.await() } }
        entered.await()
        try { gate.run("pc-a") { calls++ }; fail("Second intent was queued or dispatched") }
        catch (_: IllegalStateException) { }
        release.complete(Unit); first.join()
        assertEquals(1, calls)
        gate.run("pc-a") { calls++ }
        assertEquals(2, calls)
    }

    @Test fun onePcDoesNotBlockAnother() = runBlocking {
        val gate = OperationGate()
        gate.run("pc-a") { assertEquals("other-pc", gate.run("pc-b") { "other-pc" }) }
    }

    @Test fun failureAndCancellationReleaseTheGuard() = runBlocking {
        val gate = OperationGate()
        try { gate.run("pc-a") { error("test") } } catch (_: IllegalStateException) { }
        assertEquals("ready", gate.run("pc-a") { "ready" })
        val entered = CompletableDeferred<Unit>()
        val job = launch { gate.run("pc-a") { entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin()
        assertEquals("ready", gate.run("pc-a") { "ready" })
    }
}
