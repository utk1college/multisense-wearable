package com.example.realtimesensorwearos.presentation

import android.content.Context
import android.util.Log
import android.hardware.SensorEvent
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samsung SpO2 Manager – direct SDK integration.
 *
 * Replaces the previous reflection/Proxy-based approach with direct Samsung
 * Health Sensor SDK imports and concrete anonymous-class listeners.
 *
 * Why direct imports instead of reflection?
 *   - Dynamic Proxy listeners often fail to register with Samsung's Binder-based
 *     service, causing silent callback drops.
 *   - Direct class references let the compiler verify the API surface and produce
 *     clear errors if the SDK version changes.
 *   - Samsung's HealthTrackingService requires properly typed ConnectionListener
 *     and TrackerEventListener implementations, not runtime Proxy wrappers.
 *
 * Requirements:
 *   • samsung-health-sensor-api-*.aar in app/libs/
 *   • Samsung Health app installed on the watch
 *   • Galaxy Watch 4 / 5 / 6 or newer (supports SPO2_ON_DEMAND)
 *
 * Degrades gracefully on non-Samsung devices: if the .aar is absent from the
 * APK, the JVM throws NoClassDefFoundError when this class is first loaded.
 * MainActivity should catch that and skip SpO2 monitoring.
 *
 * Written for Samsung Health Sensor SDK 1.4.1.
 */
class OptionalSamsungSpo2Manager(
    private val context: Context
) {
    companion object {
        private const val TAG = "SPO2_MGR"

        // 60 seconds between on-demand measurements.
        // Samsung SDK internally rate-limits; 60s balances freshness vs. battery.
        private const val MEASUREMENT_INTERVAL_MS = 60_000L

        // Samsung SDK data-point status value meaning "measurement complete"
        private const val STATUS_COMPLETE = 2
    }

    // ────── Measurement results ──────────────────────────────────────
    @Volatile private var _latestSpo2: Float? = null
    @Volatile private var _latestStatus: Int? = null
    private var lastMeasurementTs = 0L
    private val inFlight = AtomicBoolean(false)

    // ────── SDK handles ─────────────────────────────────────────────
    private var healthService: HealthTrackingService? = null
    private var spo2Tracker: HealthTracker? = null

    // ────── State flags ─────────────────────────────────────────────
    @Volatile private var _sdkAvailable = false
    @Volatile private var _connected = false
    @Volatile private var _spo2Supported = false

    // ════════════════════════════════════════════════════════════════
    //  Public API – names match the original interface so
    //  MainActivity.kt requires ZERO changes.
    // ════════════════════════════════════════════════════════════════

    fun getLatestSpo2(): Float? = _latestSpo2
    fun getLatestStatus(): Int? = _latestStatus
    fun isSdkAvailable(): Boolean = _sdkAvailable
    fun isConnected(): Boolean = _connected
    fun isSpo2Supported(): Boolean = _spo2Supported

    /**
     * Begin connecting to Samsung Health Tracking Service.
     *
     * Safe to call on any device: catches [NoClassDefFoundError] if the
     * Samsung SDK .aar was not bundled in the APK (e.g. non-Samsung build).
     */
    fun start() {
        try {
            doConnect()
            _sdkAvailable = true
            Log.d(TAG, "Samsung Health SDK found — connecting to service …")
        } catch (e: NoClassDefFoundError) {
            _sdkAvailable = false
            Log.i(TAG, "Samsung Health SDK classes not on classpath → SpO2 disabled")
        } catch (e: Exception) {
            _sdkAvailable = false
            Log.w(TAG, "Samsung Health SDK init error → SpO2 disabled", e)
        }
    }

    /**
     * Disconnect and release all tracker resources.
     */
    fun stop() {
        try { spo2Tracker?.unsetEventListener() } catch (_: Exception) {}
        try { healthService?.disconnectService() } catch (_: Exception) {}
        _connected = false
        inFlight.set(false)
        spo2Tracker = null
        healthService = null
        Log.d(TAG, "SpO2 manager stopped")
    }

    /**
     * Trigger an on-demand SpO2 measurement if enough time has elapsed.
     *
     * Designed to be called very frequently (e.g. on every [SensorEvent]).
     * Short-circuits immediately when conditions are not met.
     *
     * @param nowMs current [System.currentTimeMillis]
     */
    fun requestMeasurementIfDue(nowMs: Long) {
        if (!_sdkAvailable || !_connected || !_spo2Supported) return
        if (inFlight.get()) return
        if (nowMs - lastMeasurementTs < MEASUREMENT_INTERVAL_MS) return
        val tracker = spo2Tracker ?: return

        inFlight.set(true)
        lastMeasurementTs = nowMs

        try {
            // For on-demand tracker types, must unset the previous listener
            // before setting a new one — otherwise the SDK ignores the call.
            try { tracker.unsetEventListener() } catch (_: Exception) {}

            // Setting the event listener triggers the SpO2 measurement.
            tracker.setEventListener(trackerEventListener)

            Log.d(TAG, "▶ SpO2 measurement STARTED")
        } catch (e: Exception) {
            inFlight.set(false)
            Log.w(TAG, "Could not start SpO2 measurement", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Private — service connection
    // ════════════════════════════════════════════════════════════════

    /**
     * Create the [HealthTrackingService] and initiate the connection.
     * Connection is asynchronous; the [connectionListener] callbacks
     * fire once the service is ready (or fails).
     */
    private fun doConnect() {
        healthService = HealthTrackingService(connectionListener, context.applicationContext)
        healthService!!.connectService()
    }

    /**
     * Concrete [ConnectionListener] implementation.
     *
     * Using an anonymous object — NOT a dynamic Proxy — so Samsung's
     * Binder can properly identify and invoke the interface methods.
     */
    private val connectionListener = object : ConnectionListener {

        override fun onConnectionSuccess() {
            _connected = true
            Log.d(TAG, "✓ Samsung Health service CONNECTED")
            discoverSpo2Tracker()
        }

        override fun onConnectionEnded() {
            _connected = false
            Log.d(TAG, "Samsung Health connection ended")
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            _connected = false
            Log.w(TAG, "✗ Samsung Health connection FAILED: $e")
        }
    }

    /**
     * After a successful connection, query the device's tracking
     * capabilities and acquire the SPO2_ON_DEMAND tracker handle.
     */
    private fun discoverSpo2Tracker() {
        try {
            val service = healthService ?: return
            val supported = service.trackingCapability.supportHealthTrackerTypes

            Log.d(TAG, "Device supports: ${supported.joinToString { it.name }}")

            _spo2Supported = supported.contains(HealthTrackerType.SPO2_ON_DEMAND)

            if (_spo2Supported) {
                spo2Tracker = service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
                Log.d(TAG, "✓ SPO2_ON_DEMAND tracker acquired — ready for measurements")
            } else {
                Log.i(TAG, "SPO2_ON_DEMAND not in supported types on this watch")
            }
        } catch (e: Exception) {
            _spo2Supported = false
            Log.e(TAG, "Error querying SpO2 capability", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Private — measurement data handling
    // ════════════════════════════════════════════════════════════════

    /**
     * Concrete [HealthTracker.TrackerEventListener] implementation.
     *
     * Re-used across measurements: [requestMeasurementIfDue] sets/unsets
     * this same instance rather than creating a new Proxy each time.
     */
    private val trackerEventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(data: List<DataPoint>) {
            if (data.isEmpty()) {
                Log.d(TAG, "onDataReceived: empty list (sensor warming up)")
                return
            }

            for (dp in data) {
                try {
                    val status: Int = dp.getValue(ValueKey.SpO2Set.STATUS)
                    _latestStatus = status

                    if (status == STATUS_COMPLETE) {
                        val spo2: Int = dp.getValue(ValueKey.SpO2Set.SPO2)
                        _latestSpo2 = spo2.toFloat()
                        inFlight.set(false)

                        Log.d(TAG, "╔═══════════════════════════════════╗")
                        Log.d(TAG, "║  ✓ SpO2 MEASURED: ${_latestSpo2}%")
                        Log.d(TAG, "╚═══════════════════════════════════╝")

                        // Unset listener — required before the next on-demand measurement
                        try { spo2Tracker?.unsetEventListener() } catch (_: Exception) {}
                    } else {
                        Log.d(TAG, "  SpO2 in progress … status=$status")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SpO2 data-point parse error", e)
                }
            }
        }

        override fun onFlushCompleted() {
            Log.d(TAG, "SpO2 tracker flush completed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            inFlight.set(false)
            Log.w(TAG, "SpO2 tracker ERROR: $error")
        }
    }
}
