package com.vibenav.data.ride

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Estimates the motorcycle's lean angle.
 *
 * An accelerometer can't see lean: in a steady turn the apparent gravity tilts with the bike. But
 * physics gives it from speed and turn rate: tan(lean) = v·ω_world / g. The gyroscope measures the
 * turn rate about the *bike's* vertical axis, which is ω_world·cos(lean), so
 * sin(lean) = v·ω_measured / g — no calibration or mount alignment needed. The bike's vertical axis
 * is the (low-pass filtered) apparent gravity direction, which follows the bike in turns.
 */
class LeanSensor(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val up = floatArrayOf(0f, 0f, 0f)
    private var turnRate = 0f // rad/s about the bike's vertical axis, smoothed
    @Volatile var speedMps = 0f

    /** Positive = leaning right, degrees. Null without a gyroscope. */
    val leanDeg: Float?
        get() {
            if (gyro == null) return null
            if (speedMps < 3f) return 0f
            val s = (speedMps * turnRate / 9.81f).coerceIn(-0.95f, 0.95f)
            return Math.toDegrees(asin(s.toDouble())).toFloat()
        }

    val available get() = gyro != null && accel != null

    fun start() {
        if (!available) return
        sensors!!.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sensors?.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> for (i in 0..2) up[i] += 0.02f * (e.values[i] - up[i]) // ~1 s low-pass
            Sensor.TYPE_GYROSCOPE -> {
                val n = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
                if (n < 1f) return
                // Right turn = clockwise seen from above = negative rotation about "up".
                val w = -(e.values[0] * up[0] + e.values[1] * up[1] + e.values[2] * up[2]) / n
                turnRate += 0.1f * (w - turnRate)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
