package com.curveon.data.ride

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

    private var listening = false

    /**
     * Same 50 Hz as before, but the sensor hub holds readings for up to half a second and delivers
     * them in one go, so the CPU wakes far less often. The lean is only read once a second.
     */
    fun start() {
        if (!available || listening) return
        listening = true
        sensors!!.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, BATCH_US)
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, BATCH_US)
    }

    fun stop() {
        listening = false
        sensors?.unregisterListener(this)
    }

    /**
     * Lean is 0 below 3 m/s anyway, so the sensors rest while standing still and wake at 1.5 m/s,
     * which leaves the gravity filter time to settle before the bike is fast enough to lean.
     */
    fun onSpeed(mps: Float) {
        speedMps = mps
        if (mps >= WAKE_MPS) start() else if (mps < REST_MPS) stop()
    }

    private companion object {
        const val BATCH_US = 500_000
        const val WAKE_MPS = 1.5f
        const val REST_MPS = 0.5f
    }

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
