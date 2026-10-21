package com.opendroid.ai.core.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.opendroid.ai.core.runtime.AssistantEvent.SensorSample
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in sensor adapter for the always-on event pipeline.
 *
 * No sensors are registered at construction time. Call start() with an explicit allowlist;
 * continuous sensors are down-sampled to at most one event per sensor per second.
 */
@Singleton
class SensorEventMonitor @Inject constructor(
    @ApplicationContext context: Context,
    private val eventRouter: AssistantEventRouter
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val lock = Any()
    private var enabledTypes: Set<Int> = emptySet()
    private val lastSampleElapsedMs = mutableMapOf<Int, Long>()

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val type = event.sensor.type
        val elapsedMs = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (type !in enabledTypes) return
            val previous = lastSampleElapsedMs[type] ?: Long.MIN_VALUE / 2
            if (elapsedMs - previous < MIN_SAMPLE_INTERVAL_MS) return
            lastSampleElapsedMs[type] = elapsedMs
        }

        // Copy the framework-owned mutable array before the callback returns.
        val values = event.values.take(MAX_VALUES).map { it }
        val bucket = elapsedMs / MIN_SAMPLE_INTERVAL_MS
        eventRouter.publish(
            SensorSample(
                eventId = "sensor:$type:$bucket",
                timestampMs = System.currentTimeMillis(),
                sensorType = type,
                values = values
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Returns sensor types present on this device. Does not register or sample them. */
    fun availableSensorTypes(): Set<Int> =
        sensorManager.getSensorList(Sensor.TYPE_ALL).map { it.type }.toSet()

    /**
     * Replaces the active sensor allowlist. Only requested and physically present sensors start.
     * An empty set is the explicit stop operation.
     */
    fun start(sensorTypes: Set<Int>): Set<Int> {
        val available = sensorTypes.filter { type ->
            sensorManager.getDefaultSensor(type) != null
        }.toSet()

        synchronized(lock) {
            if (available == enabledTypes) return enabledTypes.toSet()

            sensorManager.unregisterListener(this)
            enabledTypes = emptySet()
            lastSampleElapsedMs.clear()

            val registered = linkedSetOf<Int>()
            for (type in available) {
                val sensor = sensorManager.getDefaultSensor(type) ?: continue
                if (sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    registered += type
                }
            }
            enabledTypes = registered
            return registered
        }
    }

    fun stop() {
        synchronized(lock) {
            sensorManager.unregisterListener(this)
            enabledTypes = emptySet()
            lastSampleElapsedMs.clear()
        }
    }

    fun activeSensorTypes(): Set<Int> = synchronized(lock) { enabledTypes.toSet() }

    companion object {
        private const val MIN_SAMPLE_INTERVAL_MS = 1_000L
        private const val MAX_VALUES = 4
    }
}
