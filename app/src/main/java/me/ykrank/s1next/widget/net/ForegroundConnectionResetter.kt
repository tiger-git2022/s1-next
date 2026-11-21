package me.ykrank.s1next.widget.net

import android.os.SystemClock
import okhttp3.ConnectionPool
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fork（tiger-git2022）：App 退到后台或切换网络后，OkHttp 连接池里的空闲 TCP/HTTP2 连接容易变成半开死连接；
 * 回到前台直接复用会在 readTimeout 上卡住，先触发 2.5 秒的「数据来自缓存」降级，等超时后才重连拿到新数据。
 *
 * 缩短连接池空闲存活时间，并在从后台回到前台或网络变化时主动清理空闲连接，同时提供给拦截器在首个请求前同步兜底。
 */
class ForegroundConnectionResetter(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val backgroundEvictThresholdMs: Long = DEFAULT_BACKGROUND_EVICT_THRESHOLD_MS,
) {
    private val pools = CopyOnWriteArrayList<ConnectionPool>()
    private val evictPending = AtomicBoolean(false)
    private val evictLock = Any()
    private val evictExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "s1-conn-evict").apply { isDaemon = true }
    }

    @Volatile
    private var backgroundSinceMs: Long = 0L

    fun createPool(
        maxIdleConnections: Int = 5,
        keepAliveSeconds: Long = DEFAULT_KEEP_ALIVE_SECONDS,
    ): ConnectionPool {
        return ConnectionPool(maxIdleConnections, keepAliveSeconds, TimeUnit.SECONDS).also {
            pools.add(it)
        }
    }

    fun registerPool(pool: ConnectionPool) {
        pools.addIfAbsent(pool)
    }

    fun onEnterBackground() {
        backgroundSinceMs = clock()
    }

    fun onEnterForeground() {
        val since = backgroundSinceMs
        backgroundSinceMs = 0L
        if (since > 0L && clock() - since >= backgroundEvictThresholdMs) {
            scheduleEvict()
        }
    }

    fun onNetworkChanged() {
        scheduleEvict()
    }

    /** 在 IO 线程发请求前调用：若后台清理尚未跑完，等待或立刻清完死连接再发包 */
    fun evictIfPending() {
        if (!evictPending.get()) return
        synchronized(evictLock) {
            if (evictPending.get()) {
                evictAllPools()
                evictPending.set(false)
            }
        }
    }

    private fun scheduleEvict() {
        evictPending.set(true)
        evictExecutor.execute { evictIfPending() }
    }

    private fun evictAllPools() {
        for (pool in pools) {
            runCatching { pool.evictAll() }
        }
    }

    companion object {
        const val DEFAULT_KEEP_ALIVE_SECONDS = 60L
        const val DEFAULT_BACKGROUND_EVICT_THRESHOLD_MS = 2_000L
    }
}
