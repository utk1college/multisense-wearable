package com.example.realtimesensorwearos.presentation

import android.Manifest
import android.hardware.*
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.sqrt
import kotlin.concurrent.thread
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity(), SensorEventListener, CycleEventListener {

    companion object {
        private const val TAG = "MAIN_ACTIVITY"
        private const val TAG_SENSOR = "SENSOR_UPLOAD"
    }

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var heartRateSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private lateinit var optionalSpo2Manager: OptionalSamsungSpo2Manager

    // Firebase
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    // Unified Recording Cycle (handles audio + speech + fusion)
    private lateinit var unifiedCycle: UnifiedRecordingCycle

    // Legacy audio extractor for manual recording only
    private val audioExtractor = AudioFeatureExtractor()

    // Sensor upload timing (still separate from unified audio cycle)
    private var lastUploadTime = 0L
    private val uploadIntervalMs = 15000L

    // Current sensor values
    private var accelMag: Double? = null
    private var gyroX: Float? = null
    private var gyroY: Float? = null
    private var gyroZ: Float? = null
    private var heartRate: Float? = null
    private var light: Float? = null
    private var spo2: Float? = null
    private var spo2SdkAvailable: Boolean = false
    private var spo2Connected: Boolean = false
    private var spo2Supported: Boolean = false
    private var spo2Status: Int? = null

    // UI State
    private val isRecording = mutableStateOf(false)
    private val lastAgitationScore = mutableStateOf(0f)
    private val lastConfidenceLevel = mutableStateOf("--")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                startSensors()
                startUnifiedCycle()
            } else {
                Log.w(TAG, "Some permissions not granted: $permissions")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        optionalSpo2Manager = OptionalSamsungSpo2Manager(this)

        // Initialize unified recording cycle
        unifiedCycle = UnifiedRecordingCycle(this, this)

        requestPermissions()

        setContent {
            AgitationMonitorScreen(
                isRecording = isRecording.value,
                agitationScore = lastAgitationScore.value,
                confidenceLevel = lastConfidenceLevel.value,
                onRecordClick = {
                    isRecording.value = true
                    captureManualAudio()
                }
            )
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun startSensors() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        optionalSpo2Manager.start()
        Log.d(TAG, "Sensors started")
    }

    private fun startUnifiedCycle() {
        unifiedCycle.start()
        Log.d(TAG, "Unified recording cycle started")
    }

    override fun onResume() {
        super.onResume()
        startSensors()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        optionalSpo2Manager.stop()
        unifiedCycle.stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                accelMag = sqrt((x * x + y * y + z * z).toDouble())
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
            }
            Sensor.TYPE_HEART_RATE -> heartRate = event.values[0]
            Sensor.TYPE_LIGHT -> light = event.values[0]
        }

        optionalSpo2Manager.requestMeasurementIfDue(System.currentTimeMillis())
        spo2 = optionalSpo2Manager.getLatestSpo2()
        spo2SdkAvailable = optionalSpo2Manager.isSdkAvailable()
        spo2Connected = optionalSpo2Manager.isConnected()
        spo2Supported = optionalSpo2Manager.isSpo2Supported()
        spo2Status = optionalSpo2Manager.getLatestStatus()

        // Feed sensor data to unified cycle for motion buffering
        unifiedCycle.addSensorReading(
            accelMag = accelMag?.toFloat(),
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            heartRate = heartRate
        )

        // Check if unified cycle should trigger
        unifiedCycle.checkAndTriggerCycle()

        // Upload raw sensor data periodically (separate from audio cycle)
        maybeUploadSensorSample()
    }

    /**
     * Upload raw sensor data to Firebase (synced with audio cycle).
     */
    private fun maybeUploadSensorSample() {
        val now = System.currentTimeMillis()
        if (now - lastUploadTime < uploadIntervalMs) return
        lastUploadTime = now

        // Get cycle_id from unified cycle for sync
        val cycleId = if (::unifiedCycle.isInitialized) {
            unifiedCycle.getCurrentCycleId()
        } else {
            now - (now % uploadIntervalMs)
        }

        val patientId = "patient_001"
        val deviceId = "watch_001"

        val data = hashMapOf(
            "patient_id" to patientId,
            "cycle_id" to cycleId,
            "device_id" to deviceId,
            "source" to "sensor",
            "timestamp" to now,
            "accelMag" to accelMag,
            "gyroX" to gyroX,
            "gyroY" to gyroY,
            "gyroZ" to gyroZ,
            "heartRate" to heartRate,
            "light" to light,
            "spo2" to spo2,
            "spo2_sdk_available" to spo2SdkAvailable,
            "spo2_connected" to spo2Connected,
            "spo2_supported" to spo2Supported,
            "spo2_status" to spo2Status
        )

        Log.d(TAG_SENSOR, "Uploading sensor sample")

        firestore.collection("sensor_samples")
            .add(data)
            .addOnSuccessListener {
                Log.d(TAG_SENSOR, "Sensor upload SUCCESS")
            }
            .addOnFailureListener { e ->
                Log.e(TAG_SENSOR, "Sensor upload FAILED", e)
            }
    }

    /**
     * Manual audio capture (triggered by button press).
     * Uses legacy audio extractor for quick capture.
     */
    private fun captureManualAudio() {
        Log.d(TAG, "Manual audio capture started")

        thread {
            try {
                val features = audioExtractor.recordAndExtract()

                if (features != null) {
                    val data = hashMapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "audio_energy" to features.energy,
                        "energy_variance" to features.energyVariance,
                        "zcr" to features.zcr,
                        "spectral_centroid" to features.spectralCentroid,
                        "spectral_bandwidth" to features.spectralBandwidth,
                        "spectral_flux" to features.spectralFlux,
                        "pitch" to features.pitch,
                        "speech_ratio" to features.speechRatio,
                        "mfcc" to features.mfcc,
                        "manual_capture" to true
                    )

                    firestore.collection("audio_samples")
                        .add(data)
                        .addOnSuccessListener {
                            Log.d(TAG, "Manual audio upload SUCCESS")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Manual audio upload FAILED", e)
                        }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Manual audio capture failed", e)
            }

            runOnUiThread {
                isRecording.value = false
            }
        }
    }

    // ==================== CycleEventListener Implementation ====================

    override fun onRecordingStarted() {
        runOnUiThread {
            isRecording.value = true
        }
    }

    override fun onRecordingFinished() {
        // Recording done, processing continues in background
    }

    override fun onProcessingComplete(score: CombinedAgitationScore) {
        runOnUiThread {
            isRecording.value = false
            lastAgitationScore.value = score.combined
            lastConfidenceLevel.value = score.confidenceLevel

            // Log high agitation events
            if (score.isHighAgitation()) {
                Log.w(TAG, "HIGH AGITATION DETECTED: ${score.combined} " +
                        "(speech=${score.speechComponent}, acoustic=${score.acousticComponent}, " +
                        "motion=${score.motionComponent})")
            }
        }
    }

    override fun onCycleError(error: String) {
        Log.e(TAG, "Cycle error: $error")
        runOnUiThread {
            isRecording.value = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Minimal UI - just green/red button.
 */
@Composable
fun AgitationMonitorScreen(
    isRecording: Boolean,
    agitationScore: Float,
    confidenceLevel: String,
    onRecordClick: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(120.dp)
                .background(if (isRecording) Color.Red else Color.Green, CircleShape)
                .clickable(enabled = !isRecording) { onRecordClick() }
        )
    }
}
