package com.example.realtimesensorwearos.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Result of speech recognition processing.
 */
data class TranscriptionResult(
    val text: String,
    val confidence: Float,
    val processingLocation: String,  // "device", "cloud", "unavailable"
    val processingTimeMs: Long,
    val alternativeTexts: List<String> = emptyList(),
    val rmsValues: List<Float> = emptyList()  // Audio energy from mic
) {
    companion object {
        val EMPTY = TranscriptionResult(
            text = "",
            confidence = 0f,
            processingLocation = "none",
            processingTimeMs = 0,
            alternativeTexts = emptyList(),
            rmsValues = emptyList()
        )

        val TIMEOUT = TranscriptionResult(
            text = "",
            confidence = 0f,
            processingLocation = "timeout",
            processingTimeMs = 8000,
            alternativeTexts = emptyList(),
            rmsValues = emptyList()
        )

        fun unavailable() = TranscriptionResult(
            text = "",
            confidence = 0f,
            processingLocation = "unavailable",
            processingTimeMs = 0,
            alternativeTexts = emptyList(),
            rmsValues = emptyList()
        )
    }
}

/**
 * Speech Recognition Engine for WearOS using LIVE microphone listening.
 *
 * IMPORTANT: Android's SpeechRecognizer API requires LIVE microphone access.
 * It cannot process pre-recorded audio files. This engine captures both
 * transcription AND RMS audio energy values during listening.
 *
 * Usage:
 * 1. Call startListening() at T=0
 * 2. Speech recognition runs for ~3-4 seconds
 * 3. Get result via callback including RMS energy values
 */
class SpeechRecognitionEngine(private val context: Context) {

    companion object {
        private const val TAG = "SPEECH_ENGINE"
        private const val LISTENING_TIMEOUT_MS = 4000L  // 4 second max listen time for speech
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Result tracking
    private var currentResult: TranscriptionResult? = null
    private var isListening = false
    private var startTime = 0L
    private var resultCallback: ((TranscriptionResult) -> Unit)? = null

    // Accumulated text from partial results
    private val partialTexts = mutableListOf<String>()

    // RMS (audio energy) values captured during listening
    private val rmsValues = mutableListOf<Float>()

    /**
     * Check if speech recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "Speech recognition available: $available")
        return available
    }

    /**
     * Start live speech recognition.
     * This listens to the microphone in real-time.
     *
     * @param callback Called when recognition completes or times out
     */
    fun startListening(callback: (TranscriptionResult) -> Unit) {
        if (isListening) {
            Log.w(TAG, "Already listening, stopping previous session")
            stopListening()
        }

        resultCallback = callback
        partialTexts.clear()
        rmsValues.clear()
        currentResult = null
        startTime = System.currentTimeMillis()

        // Check availability but TRY anyway - the check is sometimes wrong
        val available = isAvailable()
        if (!available) {
            Log.w(TAG, "isRecognitionAvailable() returned FALSE - but we'll try anyway!")
            Log.w(TAG, "  Common reasons for false negative:")
            Log.w(TAG, "  - Google app not fully initialized yet")
            Log.w(TAG, "  - Watch just booted")
            Log.w(TAG, "  - Service temporarily unavailable")
        }

        mainHandler.post {
            try {
                initializeRecognizer()

                val intent = createRecognizerIntent()

                speechRecognizer?.startListening(intent)
                isListening = true

                Log.d(TAG, "▶ Started live speech recognition - SPEAK NOW!")

                // Safety timeout - stop listening after max time
                mainHandler.postDelayed({
                    if (isListening) {
                        Log.d(TAG, "Timeout reached, stopping recognition")
                        forceCompleteWithCurrentResults()
                    }
                }, LISTENING_TIMEOUT_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Message: ${e.message}")

                // Return unavailable result
                callback(TranscriptionResult.unavailable().copy(
                    processingLocation = "init_failed",
                    processingTimeMs = System.currentTimeMillis() - startTime
                ))
            }
        }
    }

    /**
     * Stop listening and return the best result so far.
     */
    fun stopListening() {
        if (!isListening) return
        forceCompleteWithCurrentResults()
    }

    /**
     * Force complete with whatever results we have.
     */
    private fun forceCompleteWithCurrentResults() {
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recognizer", e)
        }

        // If we have partial results but no final result, use partials
        if (currentResult == null) {
            val combinedText = partialTexts.joinToString(" ").trim()
            currentResult = TranscriptionResult(
                text = combinedText,
                confidence = if (combinedText.isNotEmpty()) 0.7f else 0f,
                processingLocation = if (combinedText.isNotEmpty()) "device_partial" else "device",
                processingTimeMs = System.currentTimeMillis() - startTime,
                rmsValues = rmsValues.toList()
            )
            Log.d(TAG, "Using partial/empty results: '$combinedText'")
            resultCallback?.invoke(currentResult!!)
        }
    }

    /**
     * Initialize the speech recognizer with listeners.
     */
    private fun initializeRecognizer() {
        // Clean up any existing recognizer
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying old recognizer", e)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "🎤 Ready for speech - SPEAK NOW!")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "🗣️ Speech started - user is speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Capture RMS values for audio energy analysis
                rmsValues.add(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "🔇 End of speech detected")
            }

            override fun onError(error: Int) {
                val errorMsg = getErrorMessage(error)
                Log.e(TAG, "Recognition error: $errorMsg (code: $error)")

                isListening = false

                // Some errors are "expected" - like no speech detected
                val result = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // No speech detected - this is OK, return empty
                        Log.d(TAG, "No speech detected (this is normal if no one spoke)")
                        TranscriptionResult(
                            text = "",
                            confidence = 0f,
                            processingLocation = "device",
                            processingTimeMs = System.currentTimeMillis() - startTime,
                            rmsValues = rmsValues.toList()
                        )
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.e(TAG, "⚠️ MISSING MICROPHONE/AUDIO PERMISSION!")
                        TranscriptionResult.EMPTY.copy(
                            processingLocation = "permission_denied",
                            processingTimeMs = System.currentTimeMillis() - startTime,
                            rmsValues = rmsValues.toList()
                        )
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        // No network for cloud recognition - use partials if available
                        Log.w(TAG, "Network error - offline recognition may be limited")
                        if (partialTexts.isNotEmpty()) {
                            TranscriptionResult(
                                text = partialTexts.joinToString(" ").trim(),
                                confidence = 0.6f,
                                processingLocation = "device_offline",
                                processingTimeMs = System.currentTimeMillis() - startTime,
                                rmsValues = rmsValues.toList()
                            )
                        } else {
                            TranscriptionResult.EMPTY.copy(
                                processingLocation = "network_error",
                                processingTimeMs = System.currentTimeMillis() - startTime,
                                rmsValues = rmsValues.toList()
                            )
                        }
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        Log.e(TAG, "⚠️ AUDIO ERROR - mic may be in use by another app")
                        TranscriptionResult.EMPTY.copy(
                            processingLocation = "audio_error",
                            processingTimeMs = System.currentTimeMillis() - startTime,
                            rmsValues = rmsValues.toList()
                        )
                    }
                    else -> {
                        TranscriptionResult.EMPTY.copy(
                            processingLocation = "error_$error",
                            processingTimeMs = System.currentTimeMillis() - startTime,
                            rmsValues = rmsValues.toList()
                        )
                    }
                }

                currentResult = result
                resultCallback?.invoke(result)
            }

            override fun onResults(results: Bundle?) {
                val processingTime = System.currentTimeMillis() - startTime
                isListening = false

                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                ) ?: arrayListOf()

                val confidences = results?.getFloatArray(
                    SpeechRecognizer.CONFIDENCE_SCORES
                ) ?: floatArrayOf()

                if (matches.isNotEmpty()) {
                    val topResult = matches[0]
                    val confidence = if (confidences.isNotEmpty()) {
                        confidences[0]
                    } else {
                        0.85f  // Default confidence
                    }

                    Log.d(TAG, "")
                    Log.d(TAG, "╔════════════════════════════════════════╗")
                    Log.d(TAG, "║ ✓ SPEECH RECOGNIZED: '$topResult'")
                    Log.d(TAG, "║   Confidence: ${String.format("%.1f", confidence * 100)}%")
                    Log.d(TAG, "╚════════════════════════════════════════╝")

                    currentResult = TranscriptionResult(
                        text = topResult,
                        confidence = confidence,
                        processingLocation = "device",
                        processingTimeMs = processingTime,
                        alternativeTexts = matches.drop(1),
                        rmsValues = rmsValues.toList()
                    )
                } else {
                    Log.d(TAG, "Recognition completed but no matches returned")
                    currentResult = TranscriptionResult.EMPTY.copy(
                        processingLocation = "device",
                        processingTimeMs = processingTime,
                        rmsValues = rmsValues.toList()
                    )
                }

                resultCallback?.invoke(currentResult!!)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partialMatches = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                ) ?: return

                if (partialMatches.isNotEmpty()) {
                    val partial = partialMatches[0]
                    if (partial.isNotBlank()) {
                        partialTexts.clear()  // Replace with latest partial
                        partialTexts.add(partial)
                        Log.d(TAG, "Partial: '$partial'")
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /**
     * Create the speech recognizer intent with optimal settings for WearOS.
     */
    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Free form allows any speech, not just commands
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // English language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")

            // Get multiple results for better keyword matching
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)

            // Enable partial results for faster feedback
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Try offline first - important for WearOS standalone
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // Tell it we want speech recognition
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    /**
     * Get human-readable error message.
     */
    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSION DENIED"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error ($errorCode)"
        }
    }

    /**
     * Get the last recognition result.
     */
    fun getLastResult(): TranscriptionResult? = currentResult

    /**
     * Release all resources.
     */
    fun release() {
        try {
            isListening = false
            mainHandler.removeCallbacksAndMessages(null)
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Release error", e)
        }
    }
}
