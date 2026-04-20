package com.example.realtimesensorwearos.presentation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Vosk-based offline speech recognition engine.
 *
 * Vosk is completely free, open source, and works offline.
 * Model is ~40MB and stored in app's internal storage.
 */
class VoskSpeechEngine(private val context: Context) {

    companion object {
        private const val TAG = "VOSK_ENGINE"
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000f
    }

    private var model: Model? = null
    private var isModelLoaded = false
    private var isModelLoading = false

    /**
     * Initialize and load the Vosk model.
     * Call this early (e.g., during app startup) to avoid delay during first recognition.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true
        if (isModelLoading) return@withContext false

        isModelLoading = true
        Log.d(TAG, "Initializing Vosk model...")

        try {
            val modelPath = getModelPath()

            if (!modelPath.exists()) {
                Log.d(TAG, "Model not found, extracting from assets...")
                extractModelFromAssets(modelPath)
            }

            if (modelPath.exists()) {
                Log.d(TAG, "Loading model from: ${modelPath.absolutePath}")
                model = Model(modelPath.absolutePath)
                isModelLoaded = true
                Log.d(TAG, "✓ Vosk model loaded successfully")
                true
            } else {
                Log.e(TAG, "Model extraction failed - path doesn't exist")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vosk model", e)
            isModelLoading = false
            false
        }
    }

    /**
     * Check if the model is ready for recognition.
     */
    fun isReady(): Boolean = isModelLoaded && model != null

    /**
     * Transcribe PCM audio bytes to text.
     *
     * @param pcmBytes Raw 16-bit PCM audio at 16kHz mono
     * @return TranscriptionResult with text and confidence
     */
    suspend fun transcribe(pcmBytes: ByteArray): TranscriptionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (!isReady()) {
            Log.w(TAG, "Model not ready, attempting to initialize...")
            if (!initialize()) {
                return@withContext TranscriptionResult(
                    text = "",
                    confidence = 0f,
                    processingLocation = "vosk_not_ready",
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)

            // Feed audio to recognizer in chunks
            val chunkSize = 4096
            var offset = 0
            while (offset < pcmBytes.size) {
                val end = minOf(offset + chunkSize, pcmBytes.size)
                val chunk = pcmBytes.copyOfRange(offset, end)
                recognizer.acceptWaveForm(chunk, chunk.size)
                offset = end
            }

            // Get final result
            val resultJson = recognizer.finalResult
            recognizer.close()

            val processingTime = System.currentTimeMillis() - startTime

            // Parse JSON result
            val result = parseVoskResult(resultJson)

            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════╗")
            Log.d(TAG, "║ ✓ VOSK TRANSCRIPTION: '${result.first}'")
            Log.d(TAG, "║   Confidence: ${String.format("%.1f", result.second * 100)}%")
            Log.d(TAG, "║   Time: ${processingTime}ms")
            Log.d(TAG, "╚════════════════════════════════════════╝")

            TranscriptionResult(
                text = result.first,
                confidence = result.second,
                processingLocation = "vosk_device",
                processingTimeMs = processingTime
            )

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            TranscriptionResult(
                text = "",
                confidence = 0f,
                processingLocation = "vosk_error",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Parse Vosk JSON result.
     * Returns pair of (text, confidence)
     */
    private fun parseVoskResult(jsonString: String): Pair<String, Float> {
        return try {
            val json = JSONObject(jsonString)
            val text = json.optString("text", "").trim()

            // Calculate confidence from word-level results if available
            val confidence = if (json.has("result")) {
                val results = json.getJSONArray("result")
                if (results.length() > 0) {
                    var totalConf = 0.0
                    for (i in 0 until results.length()) {
                        totalConf += results.getJSONObject(i).optDouble("conf", 0.8)
                    }
                    (totalConf / results.length()).toFloat()
                } else {
                    if (text.isNotEmpty()) 0.8f else 0f
                }
            } else {
                if (text.isNotEmpty()) 0.8f else 0f
            }

            Pair(text, confidence)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk result: $jsonString", e)
            Pair("", 0f)
        }
    }

    /**
     * Get the model directory path.
     */
    private fun getModelPath(): File {
        return File(context.filesDir, MODEL_NAME)
    }

    /**
     * Extract the Vosk model from assets.
     * The model should be placed as a zip file in assets/model.zip
     */
    private fun extractModelFromAssets(targetDir: File) {
        try {
            Log.d(TAG, "Extracting model to: ${targetDir.absolutePath}")

            // Try to extract from assets
            context.assets.open("vosk-model-small-en-us-0.15.zip").use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val file = File(targetDir.parent, entry.name)

                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output ->
                                zipStream.copyTo(output)
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }

            Log.d(TAG, "✓ Model extracted successfully")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract model from assets", e)
            Log.e(TAG, "Make sure 'vosk-model-small-en-us-0.15.zip' exists in app/src/main/assets/")
            throw e
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        try {
            model?.close()
            model = null
            isModelLoaded = false
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Vosk model", e)
        }
    }
}
