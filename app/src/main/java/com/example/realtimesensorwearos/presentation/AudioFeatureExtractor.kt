package com.example.realtimesensorwearos.presentation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.*

/**
 * Result containing both extracted features and raw PCM audio bytes.
 * Raw audio is needed for speech-to-text processing.
 */
data class AudioRecordingResult(
    val features: AudioFeatures,
    val rawPcmBytes: ByteArray,
    val sampleRate: Int = 16000,
    val durationMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioRecordingResult
        return rawPcmBytes.contentEquals(other.rawPcmBytes) && features == other.features
    }
    override fun hashCode(): Int = 31 * features.hashCode() + rawPcmBytes.contentHashCode()
}

/**
 * Audio feature data class containing all extracted acoustic features.
 * All features are mathematically defined and provable (no ML-based representations).
 */
data class AudioFeatures(
    // Time-domain features
    val energy: Double,              // RMS energy (loudness)
    val energyVariance: Double,      // Variance of frame energies (log-scale)
    val zcr: Double,                 // Zero-Crossing Rate [0, 1]

    // Frequency-domain features
    val spectralCentroid: Double,    // Center of spectral mass (Hz)
    val spectralBandwidth: Double,   // Spread around centroid (Hz)
    val spectralFlux: Double,        // Frame-to-frame spectral change

    // Pitch and voice features
    val pitch: Double,               // Fundamental frequency F0 (Hz)
    val speechRatio: Double,         // Voice activity ratio [0, 1]

    // Cepstral features
    val mfcc: List<Double>           // 13 Mel-Frequency Cepstral Coefficients
)

/**
 * AudioFeatureExtractor: Extracts mathematically-defined acoustic features
 * for dementia behavioral monitoring (CMAI-based agitation detection).
 *
 * Mathematical Definitions:
 * - ZCR = (1/2N) × Σ|sign(x[n]) - sign(x[n-1])|
 * - Spectral Centroid = Σ(f[k] × |X[k]|) / Σ|X[k]|
 * - Spectral Bandwidth = sqrt(Σ((f[k] - SC)² × |X[k]|) / Σ|X[k]|)
 * - Spectral Flux = Σ(|X_t[k]| - |X_{t-1}[k]|)²
 * - MFCC: FFT → Mel Filterbank → Log → DCT
 */
class AudioFeatureExtractor {

    private val sampleRate = 16000
    private val recordingDurationMs = 5000L   // Extended to 5 seconds for speech recognition
    private val frameSize = 512          // ~32ms at 16kHz (power of 2 for FFT)
    private val hopSize = 256            // 50% overlap
    private val numMelFilters = 26       // Standard mel filterbank size
    private val numMfccCoeffs = 13       // Standard MFCC count

    // Mel filterbank parameters
    private val melLowFreq = 300.0       // Hz
    private val melHighFreq = 8000.0     // Hz

    // Store previous frame spectrum for spectral flux calculation
    private var previousSpectrum: DoubleArray? = null

    @SuppressLint("MissingPermission")
    fun recordAndExtract(): AudioFeatures? {
        val result = recordAndExtractWithRawAudio()
        return result?.features
    }

    /**
     * Records audio for a specified duration and returns extracted features.
     * @param durationMs Recording duration in milliseconds (default: 5000ms)
     */
    @SuppressLint("MissingPermission")
    fun recordAndExtract(durationMs: Int): AudioFeatures? {
        val result = recordAndExtractWithRawAudio(durationMs.toLong())
        return result?.features
    }

    /**
     * Records audio and returns both extracted features AND raw PCM bytes.
     * Raw PCM is needed for speech-to-text processing.
     * Format: 16-bit PCM mono at 16kHz
     * @param durationMs Recording duration in milliseconds (default: recordingDurationMs)
     */
    @SuppressLint("MissingPermission")
    fun recordAndExtractWithRawAudio(durationMs: Long = recordingDurationMs): AudioRecordingResult? {
        return try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e("WEAR_SENSOR", "Invalid buffer size: $minBufferSize")
                return null
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("WEAR_SENSOR", "AudioRecord failed to initialize")
                return null
            }

            val allSamples = ArrayList<Short>()
            val readBuffer = ShortArray(minBufferSize)
            val startTime = System.currentTimeMillis()

            recorder.startRecording()

            while (System.currentTimeMillis() - startTime < durationMs) {
                val readCount = recorder.read(readBuffer, 0, minBufferSize)
                if (readCount > 0) {
                    for (i in 0 until readCount) allSamples.add(readBuffer[i])
                }
            }

            recorder.stop()
            recorder.release()

            val actualDuration = System.currentTimeMillis() - startTime

            if (allSamples.isEmpty()) return null

            // Convert to raw PCM bytes (16-bit little-endian)
            val rawPcmBytes = ByteArray(allSamples.size * 2)
            for (i in allSamples.indices) {
                val sample = allSamples[i]
                rawPcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
                rawPcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }

            val samples = allSamples.map { it.toDouble() }
            val features = extractFeatures(samples)

            AudioRecordingResult(
                features = features,
                rawPcmBytes = rawPcmBytes,
                sampleRate = sampleRate,
                durationMs = actualDuration
            )
        } catch (e: SecurityException) {
            Log.e("WEAR_SENSOR", "Microphone permission denied", e)
            null
        } catch (e: Exception) {
            Log.e("WEAR_SENSOR", "Audio recording failed", e)
            null
        }
    }

    /**
     * Extract all audio features from raw samples.
     * Returns safe default values if extraction fails.
     */
    fun extractFeatures(samples: List<Double>): AudioFeatures {
        return try {
            val totalSamples = samples.size
            if (totalSamples == 0) return createDefaultFeatures()

            // === TIME-DOMAIN FEATURES ===

            // 1. RMS Energy: E_rms = sqrt(1/N × Σx[n]²)
            val meanSquare = samples.sumOf { it * it } / totalSamples
            val rms = sqrt(meanSquare)

            // 2. Zero-Crossing Rate: ZCR = (1/2N) × Σ|sign(x[n]) - sign(x[n-1])|
            val zcr = calculateZCR(samples)

            // 3. Energy Variance across frames
            val energyVariance = calculateEnergyVariance(samples)

            // === FREQUENCY-DOMAIN FEATURES ===

            // Get a representative frame from the middle of the recording
            val midFrameStart = maxOf(0, (totalSamples - frameSize) / 2)
            val frame = if (totalSamples >= frameSize) {
                samples.subList(midFrameStart, midFrameStart + frameSize)
            } else {
                // Pad with zeros to reach frameSize
                samples + List(frameSize - totalSamples) { 0.0 }
            }

            // Apply Hamming window
            val windowedFrame = applyHammingWindow(frame)

            // Compute FFT magnitude spectrum (with fallback)
            val spectrum = computeFFTSafe(windowedFrame)

            // 4. Spectral Centroid: SC = Σ(f[k] × |X[k]|) / Σ|X[k]|
            val spectralCentroid = calculateSpectralCentroid(spectrum)

            // 5. Spectral Bandwidth: SB = sqrt(Σ((f[k] - SC)² × |X[k]|) / Σ|X[k]|)
            val spectralBandwidth = calculateSpectralBandwidth(spectrum, spectralCentroid)

            // 6. Spectral Flux: SF = Σ(|X_t[k]| - |X_{t-1}[k]|)²
            val spectralFlux = calculateSpectralFlux(spectrum)

            // === PITCH ESTIMATION ===

            // 7. Pitch using autocorrelation (80-400 Hz range)
            val pitch = estimatePitch(samples)

            // === MFCC WITH MEL FILTERBANK ===

            // 8. Proper MFCC: FFT → Mel Filterbank → Log → DCT
            val mfcc = computeMFCC(spectrum)

            // === VOICE ACTIVITY DETECTION ===

            // 9. Improved VAD using energy + ZCR + spectral features
            val speechRatio = calculateSpeechRatio(samples, rms, zcr, spectralCentroid)

            Log.d(
                "WEAR_SENSOR",
                "Audio: samples=$totalSamples RMS=${rms.toInt()} ZCR=${"%.3f".format(zcr)} " +
                        "SC=${"%.0f".format(spectralCentroid)}Hz Pitch=${"%.0f".format(pitch)}Hz"
            )

            AudioFeatures(
                energy = rms,
                energyVariance = energyVariance,
                zcr = zcr,
                spectralCentroid = spectralCentroid,
                spectralBandwidth = spectralBandwidth,
                spectralFlux = spectralFlux,
                pitch = pitch,
                speechRatio = speechRatio,
                mfcc = mfcc
            )
        } catch (e: Exception) {
            Log.e("WEAR_SENSOR", "Feature extraction failed", e)
            createDefaultFeatures()
        }
    }

    /**
     * Create default features when extraction fails
     */
    private fun createDefaultFeatures(): AudioFeatures {
        return AudioFeatures(
            energy = 0.0,
            energyVariance = 0.0,
            zcr = 0.0,
            spectralCentroid = 0.0,
            spectralBandwidth = 0.0,
            spectralFlux = 0.0,
            pitch = 0.0,
            speechRatio = 0.0,
            mfcc = List(numMfccCoeffs) { 0.0 }
        )
    }

    // ==================== MATHEMATICAL IMPLEMENTATIONS ====================

    /**
     * Zero-Crossing Rate (ZCR)
     * Mathematical Definition: ZCR = (1 / 2N) × Σ |sign(x[n]) - sign(x[n-1])|
     *
     * What it detects:
     * - High ZCR → high-frequency content, noise, unvoiced sounds
     * - Low ZCR → voiced speech, tonal sounds, hums
     */
    private fun calculateZCR(samples: List<Double>): Double {
        if (samples.size < 2) return 0.0

        var crossings = 0
        for (i in 1 until samples.size) {
            // Count sign changes (including zero crossings)
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)
            ) {
                crossings++
            }
        }

        // Normalize to [0, 1] range
        return crossings.toDouble() / (samples.size - 1)
    }

    /**
     * Energy Variance across frames (log-scaled for numerical stability)
     */
    private fun calculateEnergyVariance(samples: List<Double>): Double {
        val frameEnergies = mutableListOf<Double>()
        var i = 0

        while (i + frameSize <= samples.size) {
            val frame = samples.subList(i, i + frameSize)
            val frameEnergy = sqrt(frame.sumOf { it * it } / frame.size)
            frameEnergies.add(frameEnergy)
            i += hopSize
        }

        if (frameEnergies.isEmpty()) return 0.0

        val mean = frameEnergies.average()
        val variance = frameEnergies.map { (it - mean).pow(2) }.average()

        // Log scale for numerical stability
        return log10(variance + 1.0)
    }

    /**
     * Hamming Window
     * w[n] = 0.54 - 0.46 × cos(2πn / (N-1))
     */
    private fun applyHammingWindow(frame: List<Double>): DoubleArray {
        val n = frame.size
        if (n == 0) return DoubleArray(0)
        return DoubleArray(n) { i ->
            val w = if (n > 1) 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1)) else 1.0
            frame[i] * w
        }
    }

    /**
     * Safe FFT wrapper that handles non-power-of-2 sizes by padding
     */
    private fun computeFFTSafe(samples: DoubleArray): DoubleArray {
        if (samples.isEmpty()) return DoubleArray(1) { 0.0 }

        // Find next power of 2
        var n = 1
        while (n < samples.size) n = n shl 1

        // Pad with zeros if needed
        val paddedSamples = if (samples.size == n) {
            samples
        } else {
            DoubleArray(n).also { samples.copyInto(it) }
        }

        return try {
            computeFFT(paddedSamples)
        } catch (e: Exception) {
            Log.e("WEAR_SENSOR", "FFT failed, using fallback DFT", e)
            computeDFTFallback(samples)
        }
    }

    /**
     * Simple DFT fallback for when FFT fails (handles any size)
     */
    private fun computeDFTFallback(samples: DoubleArray): DoubleArray {
        val n = samples.size
        if (n == 0) return DoubleArray(1) { 0.0 }

        val magnitudes = DoubleArray(n / 2 + 1)
        for (k in magnitudes.indices) {
            var real = 0.0
            var imag = 0.0
            for (i in samples.indices) {
                val angle = 2.0 * PI * k * i / n
                real += samples[i] * cos(angle)
                imag -= samples[i] * sin(angle)
            }
            magnitudes[k] = sqrt(real * real + imag * imag)
        }
        return magnitudes
    }

    /**
     * Fast Fourier Transform (Cooley-Tukey Radix-2)
     * Returns magnitude spectrum |X[k]| for k = 0 to N/2
     *
     * Complexity: O(N log N) vs O(N²) for DFT
     * Note: Input size MUST be a power of 2
     */
    private fun computeFFT(samples: DoubleArray): DoubleArray {
        val n = samples.size

        // Validate power of 2 (return fallback if not)
        if (n <= 0 || (n and (n - 1)) != 0) {
            Log.w("WEAR_SENSOR", "FFT size $n is not power of 2, using DFT fallback")
            return computeDFTFallback(samples)
        }

        // Initialize complex arrays (real, imaginary)
        val real = samples.copyOf()
        val imag = DoubleArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit

            if (i < j) {
                // Swap real[i] and real[j]
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
            }
        }

        // Cooley-Tukey iterative FFT
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle)
            val wLenI = sin(angle)

            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0

                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wR - imag[i + k + len / 2] * wI
                    val vI = real[i + k + len / 2] * wI + imag[i + k + len / 2] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI

                    // Update twiddle factor
                    val newWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = newWR
                }
                i += len
            }
            len = len shl 1
        }

        // Return magnitude spectrum (only positive frequencies)
        val magnitudes = DoubleArray(n / 2 + 1)
        for (k in magnitudes.indices) {
            magnitudes[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
        }

        return magnitudes
    }

    /**
     * Spectral Centroid
     * Mathematical Definition: SC = Σ(f[k] × |X[k]|) / Σ|X[k]|
     *
     * What it detects:
     * - High SC (>2000 Hz) → bright, harsh sounds (screaming, shouting)
     * - Low SC (<1000 Hz) → calm, muffled sounds
     */
    private fun calculateSpectralCentroid(magnitudes: DoubleArray): Double {
        val freqResolution = sampleRate.toDouble() / (frameSize)

        var weightedSum = 0.0
        var magnitudeSum = 0.0

        for (k in magnitudes.indices) {
            val freq = k * freqResolution
            weightedSum += freq * magnitudes[k]
            magnitudeSum += magnitudes[k]
        }

        return if (magnitudeSum > 0) weightedSum / magnitudeSum else 0.0
    }

    /**
     * Spectral Bandwidth (Spectral Spread)
     * Mathematical Definition: SB = sqrt(Σ((f[k] - SC)² × |X[k]|) / Σ|X[k]|)
     *
     * What it detects:
     * - Narrow bandwidth → tonal, focused sound (whistle, hum)
     * - Wide bandwidth → broadband noise, screams
     */
    private fun calculateSpectralBandwidth(magnitudes: DoubleArray, centroid: Double): Double {
        val freqResolution = sampleRate.toDouble() / frameSize

        var weightedVariance = 0.0
        var magnitudeSum = 0.0

        for (k in magnitudes.indices) {
            val freq = k * freqResolution
            val deviation = freq - centroid
            weightedVariance += deviation * deviation * magnitudes[k]
            magnitudeSum += magnitudes[k]
        }

        return if (magnitudeSum > 0) sqrt(weightedVariance / magnitudeSum) else 0.0
    }

    /**
     * Spectral Flux
     * Mathematical Definition: SF = Σ(|X_t[k]| - |X_{t-1}[k]|)²
     *
     * What it detects:
     * - High flux → sudden onset (clap, hit, scream start)
     * - Low flux → steady sound
     */
    private fun calculateSpectralFlux(currentSpectrum: DoubleArray): Double {
        val prevSpectrum = previousSpectrum

        // Update stored spectrum for next call
        previousSpectrum = currentSpectrum.copyOf()

        if (prevSpectrum == null || prevSpectrum.size != currentSpectrum.size) {
            return 0.0
        }

        var flux = 0.0
        for (k in currentSpectrum.indices) {
            val diff = currentSpectrum[k] - prevSpectrum[k]
            // Only count positive differences (onset detection)
            if (diff > 0) {
                flux += diff * diff
            }
        }

        // Normalize by number of bins
        return sqrt(flux / currentSpectrum.size)
    }

    /**
     * Pitch Estimation using Autocorrelation
     * Mathematical Definition: R[lag] = Σ x[n] × x[n + lag]
     * F0 = sampleRate / argmax(R[lag])
     *
     * Search range: 80-400 Hz (covers most human vocalizations)
     * - Normal adult male: 85-180 Hz
     * - Normal adult female: 165-255 Hz
     * - Screaming/distress: Often > 300 Hz
     */
    private fun estimatePitch(samples: List<Double>): Double {
        val minLag = sampleRate / 400   // 400 Hz max
        val maxLag = sampleRate / 80    // 80 Hz min

        // Use center portion of samples for stability
        val analysisLength = minOf(samples.size, sampleRate)  // Max 1 second
        val startIdx = maxOf(0, (samples.size - analysisLength) / 2)
        val endIdx = minOf(samples.size, startIdx + analysisLength)

        if (endIdx - startIdx < maxLag * 2) return 0.0

        var bestLag = 0
        var bestCorr = 0.0
        var energy = 0.0

        // Calculate signal energy
        for (i in startIdx until endIdx) {
            energy += samples[i] * samples[i]
        }

        if (energy < 1e-6) return 0.0  // Silence

        // Autocorrelation search
        for (lag in minLag..maxLag) {
            var corr = 0.0
            var count = 0

            for (i in startIdx until minOf(endIdx - lag, endIdx)) {
                corr += samples[i] * samples[i + lag]
                count++
            }

            // Normalize by count
            if (count > 0) corr /= count

            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        // Verify periodicity (correlation should be significant)
        val normalizedCorr = bestCorr / (energy / (endIdx - startIdx))
        if (normalizedCorr < 0.3) return 0.0  // Not periodic enough

        return if (bestLag == 0) 0.0 else sampleRate.toDouble() / bestLag
    }

    /**
     * MFCC with Proper Mel Filterbank
     *
     * Steps:
     * 1. Apply Mel filterbank to power spectrum
     * 2. Take log of filter outputs
     * 3. Apply DCT to get MFCCs
     *
     * Mel scale: f_mel = 2595 × log₁₀(1 + f_hz / 700)
     */
    private fun computeMFCC(spectrum: DoubleArray): List<Double> {
        // Convert magnitude to power spectrum
        val powerSpectrum = DoubleArray(spectrum.size) { spectrum[it] * spectrum[it] }

        // Create and apply Mel filterbank
        val melEnergies = applyMelFilterbank(powerSpectrum)

        // Take log of filter energies
        val logMelEnergies = DoubleArray(numMelFilters) { i ->
            ln(melEnergies[i] + 1e-10)  // Add small constant to avoid log(0)
        }

        // Apply DCT-II to get MFCCs
        val mfcc = MutableList(numMfccCoeffs) { 0.0 }
        for (k in 0 until numMfccCoeffs) {
            var sum = 0.0
            for (n in 0 until numMelFilters) {
                sum += logMelEnergies[n] * cos(PI * k * (n + 0.5) / numMelFilters)
            }
            mfcc[k] = sum
        }

        return mfcc
    }

    /**
     * Mel Filterbank Application
     *
     * Creates triangular filters evenly spaced in Mel scale,
     * then applies them to the power spectrum.
     */
    private fun applyMelFilterbank(powerSpectrum: DoubleArray): DoubleArray {
        val nfft = (powerSpectrum.size - 1) * 2

        // Convert frequency bounds to Mel scale
        val melLow = hzToMel(melLowFreq)
        val melHigh = hzToMel(melHighFreq)

        // Create evenly spaced Mel points
        val melPoints = DoubleArray(numMelFilters + 2) { i ->
            melLow + i * (melHigh - melLow) / (numMelFilters + 1)
        }

        // Convert back to Hz and then to FFT bin indices
        val binIndices = IntArray(melPoints.size) { i ->
            val hz = melToHz(melPoints[i])
            ((hz * nfft) / sampleRate).toInt().coerceIn(0, powerSpectrum.size - 1)
        }

        // Apply triangular filters
        val melEnergies = DoubleArray(numMelFilters)

        for (m in 0 until numMelFilters) {
            val startBin = binIndices[m]
            val centerBin = binIndices[m + 1]
            val endBin = binIndices[m + 2]

            for (k in startBin until centerBin) {
                if (centerBin > startBin) {
                    val weight = (k - startBin).toDouble() / (centerBin - startBin)
                    melEnergies[m] += powerSpectrum[k] * weight
                }
            }

            for (k in centerBin until endBin) {
                if (endBin > centerBin) {
                    val weight = (endBin - k).toDouble() / (endBin - centerBin)
                    melEnergies[m] += powerSpectrum[k] * weight
                }
            }
        }

        return melEnergies
    }

    /**
     * Hz to Mel conversion
     * f_mel = 2595 × log₁₀(1 + f_hz / 700)
     */
    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    /**
     * Mel to Hz conversion
     * f_hz = 700 × (10^(f_mel / 2595) - 1)
     */
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    /**
     * Improved Voice Activity Detection (VAD)
     *
     * Uses combination of:
     * - Energy threshold (adaptive)
     * - ZCR range (speech-like ZCR)
     * - Spectral centroid (voiced speech > 300 Hz)
     *
     * Returns continuous ratio [0, 1] instead of discrete values
     */
    private fun calculateSpeechRatio(
        samples: List<Double>,
        overallRms: Double,
        overallZcr: Double,
        overallCentroid: Double
    ): Double {
        // Frame-by-frame analysis
        var speechFrames = 0
        var totalFrames = 0

        // Estimate noise floor from quietest 10% of frames
        val frameEnergies = mutableListOf<Double>()
        var i = 0
        while (i + frameSize <= samples.size) {
            val frame = samples.subList(i, i + frameSize)
            val frameEnergy = sqrt(frame.sumOf { it * it } / frame.size)
            frameEnergies.add(frameEnergy)
            i += hopSize
        }

        if (frameEnergies.isEmpty()) return 0.0

        // Adaptive threshold: noise floor + margin
        val sortedEnergies = frameEnergies.sorted()
        val noiseFloor = sortedEnergies.take(maxOf(1, sortedEnergies.size / 10)).average()
        val energyThreshold = noiseFloor * 3.0 + 100.0  // Adaptive + minimum

        // ZCR bounds for speech (typical speech ZCR: 0.05-0.25)
        val zcrMin = 0.02
        val zcrMax = 0.35

        // Spectral centroid minimum for voiced speech
        val centroidMin = 200.0

        i = 0
        while (i + frameSize <= samples.size) {
            val frame = samples.subList(i, i + frameSize)
            val frameEnergy = sqrt(frame.sumOf { it * it } / frame.size)
            val frameZcr = calculateZCR(frame)

            // Speech detection criteria
            val hasEnergy = frameEnergy > energyThreshold
            val hasValidZcr = frameZcr in zcrMin..zcrMax
            val hasVoicing = overallCentroid > centroidMin  // Use overall centroid

            if (hasEnergy && (hasValidZcr || hasVoicing)) {
                speechFrames++
            }
            totalFrames++

            i += hopSize
        }

        return if (totalFrames > 0) speechFrames.toDouble() / totalFrames else 0.0
    }

    /**
     * Reset internal state (call between recordings)
     */
    fun reset() {
        previousSpectrum = null
    }
}
