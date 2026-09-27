package org.luismtz.rpgtranslator

import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Limitador de solicitudes tipo "token bucket": permite ráfagas cortas pero
 * mantiene un promedio de [ratePerSecond] solicitudes por segundo a largo
 * plazo. Se comparte una sola instancia entre todas las corrutinas que hacen
 * llamadas de red, para no tumbar el servicio de traducción por volumen.
 */
class RateLimiter(private val ratePerSecond: Double, private val burst: Int = (ratePerSecond).toInt().coerceAtLeast(1)) {

    private var tokens: Double = burst.toDouble()
    private var lastRefillNanos: Long = System.nanoTime()
    private val lock = Any()

    /** Espera lo necesario para respetar la tasa configurada, y luego consume un token. */
    suspend fun acquire() {
        while (true) {
            val waitMs = synchronized(lock) {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    // Cuánto falta para tener un token disponible
                    ((1.0 - tokens) / ratePerSecond * 1000).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSec > 0) {
            tokens = min(burst.toDouble(), tokens + elapsedSec * ratePerSecond)
            lastRefillNanos = now
        }
    }
}
