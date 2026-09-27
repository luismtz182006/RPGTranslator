package org.luismtz.rpgtranslator

/**
 * Configuración centralizada del traductor. Antes estos valores estaban
 * repartidos como números sueltos en varios archivos (parallelism=6,
 * reintentos=4, etc.) — ahora todo vive aquí.
 */
data class TranslatorConfig(
    val sourceLang: String = "auto",
    val targetLang: String = "es",
    val offline: Boolean = false,
    /** Cuántos archivos se procesan a la vez. */
    val fileConcurrency: Int = 8,
    /** Cuántas llamadas de red simultáneas como máximo, sin importar cuántos archivos corran a la vez. */
    val networkConcurrency: Int = 12,
    /** Límite de solicitudes por segundo al endpoint de traducción (token bucket). */
    val requestsPerSecond: Double = 18.0,
    val maxRetries: Int = 4,
    val retryBaseDelayMs: Long = 400L,
    val rateLimitBaseDelayMs: Long = 1500L,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 10_000,
    /** Cada cuántas traducciones nuevas se guarda un checkpoint de la caché en disco. */
    val cacheCheckpointEvery: Int = 50,
    /** Máximo de líneas que se conservan en el log visible en pantalla. */
    val maxLogLines: Int = 200
) {
    companion object {
        val DEFAULT = TranslatorConfig()
    }
}
