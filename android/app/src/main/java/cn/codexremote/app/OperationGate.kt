package cn.codexremote.app

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

class OperationGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(hostId: String, action: suspend () -> T): T {
        val lock = locks.computeIfAbsent(hostId) { Mutex() }
        check(lock.tryLock()) { "这台电脑还有操作正在提交，请等待结果；不会排队重复发送" }
        try { return action() } finally { lock.unlock() }
    }
}
