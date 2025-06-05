package no.nav.dagpenger.events.duckdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

fun interface TriggerAction {
    suspend fun invoke()
}

class PeriodicTrigger(
    private val batchSize: Int,
    private val interval: Duration,
    private val action: TriggerAction,
) : DuckDbObserver {
    private val counter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flushJob: Job? = null

    internal fun start() {
        logger.info { "Starter å regelmessig flushe events som er færre en batch-størrelse" }
        scheduleIntervalFlush()
    }

    internal fun stop() {
        logger.info { "Avslutter regelmessig flushing" }
        flushJob?.cancel()
        if (counter.get() > 0) {
            runBlocking {
                flushSafely()
            }
        }
        scope.cancel()
    }

    private fun increment() {
        val newValue = counter.addAndGet(1)
        if (newValue >= batchSize) {
            // Atomically claim all current events for flushing
            val eventsToFlush = counter.getAndSet(0)
            if (eventsToFlush > 0) {
                logger.info { "Flusher data etter batch har nådd $eventsToFlush events" }
                scope.launch {
                    flushSafely()
                }
            }
        }
    }

    override fun onInsert() = increment()

    fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutdown hook triggered. Cleaning up...")
                stop()
                logger.info("Cleanup complete.")
            },
        )
    }

    private fun scheduleIntervalFlush() {
        flushJob?.cancel() // Cancel any previous timer
        flushJob = scope.launch 
        {
            while (true) {
                delay(interval)
                if (counter.get() > 0) {
                    logger.info { "Flusher data etter interval=$interval med ${counter.get()} events" }
                    val eventsToFlush = counter.getAndSet(0)
                    try {
                        action.invoke()
                    } catch (e: Exception) {
                        logger.error(e) { "Feilet å flushe data: ${e.message}" }
                        // Don't throw here, or the loop will end
                    }
                }
                // No else branch needed - we just continue the loop
            }
        }
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
