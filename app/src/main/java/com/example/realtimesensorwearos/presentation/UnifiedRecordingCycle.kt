package com.example.realtimesensorwearos.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*

/**
 * Listener interface for cycle events.
 */
interface CycleEventListener {
    fun onRecordingStarted()
    fun onRecordingFinished()
    fun onProcessingComplete(score: CombinedAgitationScore)
    fun onCycleError(error: String)
}

/**
 * Unified Recording Cycle Manager with Vosk Speech Recognition.
 *
 * Orchestrates the synchronized 60-second cycle:
 * - T=0s:   START 5-second audio recording
 * - T=5s:   STOP recording, run Vosk transcription + extract features
 * - T=6s:   Run keyword analysis
 * - T=7s:   Calculate fusion score
 * - T=8s:   Upload to Firebase
 * - T=60s:  Cycle repeats (all data within 60s window shares same cycle_id)
 *
 * Uses Vosk for 100% offline, free speech recognition.
 */
class UnifiedRecordingCycle(
    private val context: Context,
    private val listener: CycleEventListener? = null
) {
    companion object {
        private const val TAG = "UNIFIED_CYCLE"
        const val CYCLE_INTERVAL_MS = 15_000L    // 15 seconds = 4 uploads per 60s window
        const val RECORDING_DURATION_MS = 5_000L // 5 seconds recording
        const val SENSOR_WINDOW_SIZE = 50        // Max sensor readings to buffer
    }

    // Core components
    private val audioExtractor = AudioFeatureExtractor()
    private val voskEngine = VoskSpeechEngine(context)
    private val bagOfWordsAnalyzer = BagOfWordsAnalyzer()
    private val fusionScorer = AudioMovementFusionScorer()
    private val firestore = FirebaseFirestore.getInstance()

    // Scheduling
    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State
    private var isRunning = false
    private var cycleStartTime = 0L
    private var lastCycleTime = 0L
    private var voskReady = false

    // Motion data buffer (rolling window)
    private val accelReadings = mutableListOf<Float>()
    private val gyroReadings = mutableListOf<Triple<Float, Float, Float>>()
    private var latestHeartRate: Float? = null

    // Latest results for fusion
    private var pendingAudioResult: AudioRecordingResult? = null
    private var pendingTranscription: TranscriptionResult? = null
    private var pendingKeywordAnalysis: KeywordAnalysisResult? = null

    /**
     * Start the unified recording cycle.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Cycle already running")
            return
        }

        isRunning = true
        lastCycleTime = System.currentTimeMillis()

        Log.d(TAG, "")
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║     UNIFIED RECORDING CYCLE WITH VOSK STARTING             ║")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════╣")
        Log.d(TAG, "║  Cycle interval: ${CYCLE_INTERVAL_MS}ms")
        Log.d(TAG, "║  Recording duration: ${RECORDING_DURATION_MS}ms")
        Log.d(TAG, "║  Speech engine: VOSK (offline)")
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

        // Initialize Vosk and wait before starting first cycle
        coroutineScope.launch {
            Log.d(TAG, "Initializing Vosk speech engine...")
            voskReady = voskEngine.initialize()
            if (voskReady) {
                Log.d(TAG, "✓ Vosk is ready for speech recognition")
            } else {
                Log.e(TAG, "✗ Vosk initialization failed - check model.zip in assets")
            }
            // NOW start first cycle after Vosk is ready
            startCycle()
        }
    }

    /**
     * Stop the cycle and release resources.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        coroutineScope.cancel()
        voskEngine.release()
        clearBuffers()
        Log.d(TAG, "Unified recording cycle stopped")
    }

    /**
     * Check if a new cycle should be triggered.
     */
    fun checkAndTriggerCycle(): Boolean {
        if (!isRunning) return false

        val now = System.currentTimeMillis()
        if (now - lastCycleTime >= CYCLE_INTERVAL_MS) {
            lastCycleTime = now
            startCycle()
            return true
        }
        return false
    }

    /**
     * Get the current cycle ID (cycle start timestamp).
     */
    fun getCurrentCycleId(): Long = cycleStartTime

    /**
     * Add sensor reading to the motion buffer.
     */
    fun addSensorReading(
        accelMag: Float?,
        gyroX: Float?,
        gyroY: Float?,
        gyroZ: Float?,
        heartRate: Float?
    ) {
        accelMag?.let {
            synchronized(accelReadings) {
                accelReadings.add(it)
                if (accelReadings.size > SENSOR_WINDOW_SIZE) {
                    accelReadings.removeAt(0)
                }
            }
        }

        if (gyroX != null && gyroY != null && gyroZ != null) {
            synchronized(gyroReadings) {
                gyroReadings.add(Triple(gyroX, gyroY, gyroZ))
                if (gyroReadings.size > SENSOR_WINDOW_SIZE) {
                    gyroReadings.removeAt(0)
                }
            }
        }

        heartRate?.let { latestHeartRate = it }
    }

    /**
     * Start a new recording cycle.
     */
    private fun startCycle() {
        cycleStartTime = System.currentTimeMillis() / 60000 * 60000

        Log.d(TAG, "")
        Log.d(TAG, "===============================================================")
        Log.d(TAG, "NEW CYCLE  cycle_id=$cycleStartTime")
        Log.d(TAG, "WAIT FOR: SPEAK NOW")
        Log.d(TAG, "===============================================================")

        listener?.onRecordingStarted()
        startRecordingAndTranscribe()
    }

    /**
     * Record audio, extract features, and run Vosk transcription.
     */
    private fun startRecordingAndTranscribe() {
        // Capture cycle ID at the time of recording start
        val recordingCycleId = cycleStartTime
        
        Log.d(TAG, "SPEAK NOW  recording started for ${RECORDING_DURATION_MS / 1000}s")

        coroutineScope.launch {
            try {
                // STEP 1: Record audio with raw PCM
                val audioResult = audioExtractor.recordAndExtractWithRawAudio(RECORDING_DURATION_MS)

                if (audioResult == null) {
                    Log.e(TAG, "Audio recording failed")
                    withContext(Dispatchers.Main) {
                        listener?.onRecordingFinished()
                        performFusionAndUpload(recordingCycleId, null, null, null)
                    }
                    return@launch
                }

                pendingAudioResult = audioResult
                Log.d(TAG, "RECORDING COMPLETE  t+${getElapsedSeconds()}s")
                Log.d(TAG, "AUDIO SNAPSHOT  pcm_bytes=${audioResult.rawPcmBytes.size} energy=${String.format("%.1f", audioResult.features.energy)} pitch=${String.format("%.1f", audioResult.features.pitch)} speech_ratio=${String.format("%.2f", audioResult.features.speechRatio)}")

                withContext(Dispatchers.Main) {
                    listener?.onRecordingFinished()
                }

                // STEP 2: Run Vosk transcription on PCM bytes
                if (voskReady && audioResult.rawPcmBytes.isNotEmpty()) {
                    Log.d(TAG, "TRANSCRIBING  t+${getElapsedSeconds()}s")
                    pendingTranscription = voskEngine.transcribe(audioResult.rawPcmBytes)

                    val transcriptText = pendingTranscription?.text?.trim().orEmpty()
                    Log.d(TAG, "TRANSCRIPT  '${if (transcriptText.isNotEmpty()) transcriptText else "<empty>"}'")

                // STEP 3: Run keyword analysis
                    val text = pendingTranscription?.text ?: ""
                    if (text.isNotBlank()) {
                        pendingKeywordAnalysis = bagOfWordsAnalyzer.analyzeTranscription(text)
                        logKeywordSummary(pendingKeywordAnalysis ?: KeywordAnalysisResult.EMPTY)
                    } else {
                        pendingKeywordAnalysis = KeywordAnalysisResult.EMPTY
                        Log.d(TAG, "KEYWORDS  none detected")
                    }
                } else {
                    if (!voskReady) {
                        Log.w(TAG, "Vosk not ready - skipping transcription")
                    }
                    pendingTranscription = TranscriptionResult.EMPTY.copy(
                        processingLocation = if (voskReady) "vosk_no_audio" else "vosk_not_ready"
                    )
                    pendingKeywordAnalysis = KeywordAnalysisResult.EMPTY
                }

                // STEP 4: Perform fusion and upload
                withContext(Dispatchers.Main) {
                    performFusionAndUpload(recordingCycleId, audioResult, pendingTranscription, pendingKeywordAnalysis)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording/transcription error", e)
                withContext(Dispatchers.Main) {
                    performFusionAndUpload(recordingCycleId, null, null, null)
                }
            }
        }
    }

    /**
     * Perform fusion scoring and upload to Firebase.
     */
    private fun performFusionAndUpload(
        cycleId: Long,
        audioResult: AudioRecordingResult?,
        transcription: TranscriptionResult?,
        keywordAnalysis: KeywordAnalysisResult?
    ) {
        try {
            Log.d(TAG, "PREPARING UPLOAD  t+${getElapsedSeconds()}s")

            // Get motion stats
            val motionStats = synchronized(this) {
                fusionScorer.createMotionStats(
                    accelReadings = accelReadings.toList(),
                    gyroReadings = gyroReadings.toList(),
                    heartRate = latestHeartRate
                )
            }

            // Get audio features from the recorded result
            val audioFeatures = audioResult?.features ?: AudioFeatures(
                energy = 0.0, energyVariance = 0.0, zcr = 0.0,
                spectralCentroid = 0.0, spectralBandwidth = 0.0, spectralFlux = 0.0,
                pitch = 0.0, speechRatio = 0.0, mfcc = List(13) { 0.0 }
            )

            // Get keyword analysis
            val kwordAnalysis = keywordAnalysis ?: KeywordAnalysisResult.EMPTY
            val transcriptionResult = transcription ?: TranscriptionResult.EMPTY

            val combinedScore = CombinedAgitationScore.EMPTY

            Log.d(TAG, "SCORING MODE  dashboard-side score reconstruction")

            // Upload to Firebase
            uploadToFirebase(cycleId, audioFeatures, transcriptionResult, kwordAnalysis, motionStats)

            // Notify listener
            listener?.onProcessingComplete(combinedScore)

        } catch (e: Exception) {
            Log.e(TAG, "Fusion scoring error", e)
            handleCycleError("Fusion error: ${e.message}")
        }
    }

    /**
     * Upload data to Firebase.
     */
    private fun uploadToFirebase(
        cycleId: Long,
        audioFeatures: AudioFeatures,
        transcription: TranscriptionResult,
        keywordAnalysis: KeywordAnalysisResult,
        motionStats: MotionStats
    ) {
        val timestamp = cycleId
        val patientId = "patient_001"
        val deviceId = "watch_001"

        val audioData = hashMapOf(
            "patient_id" to patientId,
            "cycle_id" to cycleId,
            "device_id" to deviceId,
            "source" to "audio",
            "timestamp" to timestamp,
            "audio_energy" to audioFeatures.energy,
            "energy_variance" to audioFeatures.energyVariance,
            "zcr" to audioFeatures.zcr,
            "spectral_centroid" to audioFeatures.spectralCentroid,
            "spectral_bandwidth" to audioFeatures.spectralBandwidth,
            "spectral_flux" to audioFeatures.spectralFlux,
            "pitch" to audioFeatures.pitch,
            "speech_ratio" to audioFeatures.speechRatio,
            "mfcc" to audioFeatures.mfcc,
            "transcription_confidence" to transcription.confidence,
            "processing_location" to transcription.processingLocation,
            "processing_latency_ms" to transcription.processingTimeMs,
            "speech_recognition_available" to voskReady,
            "detected_categories" to keywordAnalysis.detectedCategories.map { (cat, conf) ->
                mapOf("category" to cat.name, "confidence" to conf)
            },
            "top_keywords" to keywordAnalysis.detectedKeywords.take(5).map {
                mapOf("keyword" to it.keyword, "confidence" to it.confidence, "category" to it.category.name)
            },
            "has_repetition" to keywordAnalysis.hasRepetition,
            "accel_magnitude" to motionStats.accelerationMagnitude,
            "accel_variance" to motionStats.accelerationVariance,
            "gyro_magnitude" to motionStats.gyroMagnitude,
            "heart_rate" to motionStats.heartRate
        )

        logUploadPayload(audioData)

        firestore.collection("audio_samples")
            .add(audioData)
            .addOnSuccessListener { Log.d(TAG, "UPLOAD SUCCESS  audio_samples cycle_id=$cycleId") }
            .addOnFailureListener { Log.e(TAG, "✗ Firebase upload failed", it) }
    }

    private fun logKeywordSummary(keywordAnalysis: KeywordAnalysisResult) {
        val keywordSummary = if (keywordAnalysis.detectedKeywords.isEmpty()) {
            "none"
        } else {
            keywordAnalysis.detectedKeywords.take(5).joinToString(" | ") {
                "${it.keyword}:${it.category.name}:${String.format("%.2f", it.confidence)}"
            }
        }
        Log.d(TAG, "KEYWORDS  count=${keywordAnalysis.detectedKeywords.size}  repetition=${keywordAnalysis.hasRepetition}  top=${keywordAnalysis.topCategory?.name ?: "none"}")
        Log.d(TAG, "KEYWORD LIST  $keywordSummary")
    }

    private fun logUploadPayload(audioData: Map<String, Any?>) {
        val keywordSummary = (audioData["top_keywords"] as? List<*>)?.joinToString(" | ") { entry ->
            val item = entry as? Map<*, *> ?: return@joinToString "invalid"
            val keyword = item["keyword"] ?: ""
            val category = item["category"] ?: ""
            val confidence = item["confidence"] ?: ""
            "$keyword:$category:$confidence"
        } ?: "[]"

        val categorySummary = (audioData["detected_categories"] as? List<*>)?.joinToString(" | ") { entry ->
            val item = entry as? Map<*, *> ?: return@joinToString "invalid"
            val category = item["category"] ?: ""
            val confidence = item["confidence"] ?: ""
            "$category:$confidence"
        } ?: "[]"

        Log.d(TAG, "-------------------- FIRESTORE UPLOAD --------------------")
        Log.d(TAG, "patient_id=${audioData["patient_id"]} cycle_id=${audioData["cycle_id"]} device_id=${audioData["device_id"]}")
        Log.d(TAG, "timestamp=${audioData["timestamp"]} source=${audioData["source"]}")
        Log.d(TAG, "audio_energy=${audioData["audio_energy"]} energy_variance=${audioData["energy_variance"]} zcr=${audioData["zcr"]}")
        Log.d(TAG, "spectral_centroid=${audioData["spectral_centroid"]} spectral_bandwidth=${audioData["spectral_bandwidth"]} spectral_flux=${audioData["spectral_flux"]}")
        Log.d(TAG, "pitch=${audioData["pitch"]} speech_ratio=${audioData["speech_ratio"]} transcription_confidence=${audioData["transcription_confidence"]}")
        Log.d(TAG, "processing_location=${audioData["processing_location"]} processing_latency_ms=${audioData["processing_latency_ms"]} speech_recognition_available=${audioData["speech_recognition_available"]}")
        Log.d(TAG, "has_repetition=${audioData["has_repetition"]} top_keywords=$keywordSummary")
        Log.d(TAG, "detected_categories=$categorySummary")
        Log.d(TAG, "accel_magnitude=${audioData["accel_magnitude"]} accel_variance=${audioData["accel_variance"]} gyro_magnitude=${audioData["gyro_magnitude"]} heart_rate=${audioData["heart_rate"]}")
        Log.d(TAG, "----------------------------------------------------------")
    }

    private fun handleCycleError(error: String) {
        Log.e(TAG, "Cycle error: $error")
        listener?.onCycleError(error)
    }

    private fun clearBuffers() {
        synchronized(accelReadings) { accelReadings.clear() }
        synchronized(gyroReadings) { gyroReadings.clear() }
        latestHeartRate = null
    }

    private fun getElapsedSeconds(): Long = (System.currentTimeMillis() - cycleStartTime) / 1000

    fun addFamilyNames(names: List<String>) {
        bagOfWordsAnalyzer.addFamilyNames(names)
    }
}
