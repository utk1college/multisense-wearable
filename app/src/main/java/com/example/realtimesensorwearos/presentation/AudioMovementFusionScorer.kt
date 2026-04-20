package com.example.realtimesensorwearos.presentation

import android.util.Log
import kotlin.math.sqrt

/**
 * Motion statistics from sensor data window.
 */
data class MotionStats(
    val accelerationMagnitude: Float,
    val accelerationVariance: Float,
    val gyroMagnitude: Float,
    val heartRate: Float?,
    val heartRateBaseline: Float = 70f  // Default resting heart rate
) {
    val heartRateElevation: Float
        get() = if (heartRate != null) (heartRate - heartRateBaseline).coerceAtLeast(0f) else 0f

    companion object {
        val EMPTY = MotionStats(
            accelerationMagnitude = 9.8f,  // Gravity only
            accelerationVariance = 0f,
            gyroMagnitude = 0f,
            heartRate = null
        )
    }
}

/**
 * Combined agitation score with component breakdown.
 */
data class CombinedAgitationScore(
    val combined: Float,              // Overall agitation score [0-1]
    val speechComponent: Float,       // Speech Detection Score (SDS)
    val acousticComponent: Float,     // Audio-Acoustic Score (AAS)
    val motionComponent: Float,       // Motion Score (MS)
    val confidenceLevel: String,      // "HIGH", "MEDIUM", "LOW"
    val dominantContributor: String   // Which component contributed most
) {
    companion object {
        val EMPTY = CombinedAgitationScore(
            combined = 0f,
            speechComponent = 0f,
            acousticComponent = 0f,
            motionComponent = 0f,
            confidenceLevel = "LOW",
            dominantContributor = "none"
        )
    }

    /**
     * Check if this score indicates high agitation (alert threshold).
     */
    fun isHighAgitation(): Boolean = combined >= 0.50f

    /**
     * Check if this score indicates medium agitation (log for review).
     */
    fun isMediumAgitation(): Boolean = combined >= 0.60f && combined < 0.80f
}

/**
 * Multi-Modal Fusion Scorer for CMAI Agitation Detection.
 *
 * Combines three independent scores:
 * 1. Speech Detection Score (SDS) - from keyword analysis
 * 2. Audio-Acoustic Score (AAS) - from audio features
 * 3. Motion Score (MS) - from sensor data
 *
 * Uses adaptive weighting based on speech confidence to prioritize
 * the most informative modality for each assessment window.
 */
class AudioMovementFusionScorer {

    companion object {
        private const val TAG = "FUSION_SCORER"

        // Acoustic score normalization ranges
        private const val ENERGY_MIN = 500f
        private const val ENERGY_MAX = 3500f
        private const val PITCH_MIN = 100f
        private const val PITCH_MAX = 250f
        private const val ZCR_MIN = 0.02f
        private const val ZCR_MAX = 0.35f
        private const val SPECTRAL_CENTROID_MIN = 1000f
        private const val SPECTRAL_CENTROID_MAX = 3500f
        private const val ENERGY_VARIANCE_MIN = 0f
        private const val ENERGY_VARIANCE_MAX = 1000f

        // Motion score normalization ranges
        private const val ACCEL_MAG_MIN = 9f      // Gravity baseline
        private const val ACCEL_MAG_MAX = 20f     // Active movement
        private const val ACCEL_VAR_MIN = 0f
        private const val ACCEL_VAR_MAX = 10f
        private const val GYRO_MAG_MIN = 0f
        private const val GYRO_MAG_MAX = 2f       // Radians/sec
        private const val HR_ELEVATION_MIN = 0f
        private const val HR_ELEVATION_MAX = 40f  // BPM above baseline
    }

    /**
     * Calculate combined agitation score from all modalities.
     *
     * @param speechResult Result from BagOfWordsAnalyzer
     * @param audioFeatures Extracted audio features
     * @param motionStats Motion statistics from sensor window
     * @return CombinedAgitationScore with component breakdown
     */
    fun calculateCombinedScore(
        speechResult: KeywordAnalysisResult,
        audioFeatures: AudioFeatures,
        motionStats: MotionStats
    ): CombinedAgitationScore {
        // Calculate individual component scores
        val speechScore = calculateSpeechScore(speechResult)
        val acousticScore = calculateAcousticScore(audioFeatures)
        val motionScore = calculateMotionScore(motionStats)

        // Apply adaptive weighting based on speech confidence
        val combinedScore = calculateAdaptiveWeightedScore(
            speechScore, acousticScore, motionScore
        )

        // Determine confidence level
        val confidenceLevel = when {
            combinedScore >= 0.80f -> "HIGH"
            combinedScore >= 0.60f -> "MEDIUM"
            else -> "LOW"
        }

        // Determine dominant contributor
        val dominantContributor = when {
            speechScore >= acousticScore && speechScore >= motionScore -> "speech"
            acousticScore >= speechScore && acousticScore >= motionScore -> "acoustic"
            else -> "motion"
        }

        val result = CombinedAgitationScore(
            combined = combinedScore,
            speechComponent = speechScore,
            acousticComponent = acousticScore,
            motionComponent = motionScore,
            confidenceLevel = confidenceLevel,
            dominantContributor = dominantContributor
        )

        Log.d(TAG, "Fusion: SDS=${"%.2f".format(speechScore)} " +
                "AAS=${"%.2f".format(acousticScore)} " +
                "MS=${"%.2f".format(motionScore)} " +
                "CAS=${"%.2f".format(combinedScore)} ($confidenceLevel)")

        return result
    }

    /**
     * Calculate Speech Detection Score (SDS) from keyword analysis.
     */
    private fun calculateSpeechScore(speechResult: KeywordAnalysisResult): Float {
        if (speechResult.detectedCategories.isEmpty()) {
            return 0f
        }

        // Use the pre-calculated speech detection score from analyzer
        // This already incorporates category weights
        return speechResult.speechDetectionScore
    }

    /**
     * Calculate Audio-Acoustic Score (AAS) from audio features.
     *
     * Weighted combination:
     * - 0.30 × normalized(energy)
     * - 0.25 × normalized(pitch)
     * - 0.20 × normalized(ZCR)
     * - 0.15 × normalized(spectral_centroid)
     * - 0.10 × normalized(energy_variance)
     */
    private fun calculateAcousticScore(features: AudioFeatures): Float {
        val normalizedEnergy = normalize(
            features.energy.toFloat(), ENERGY_MIN, ENERGY_MAX
        )
        val normalizedPitch = normalize(
            features.pitch.toFloat(), PITCH_MIN, PITCH_MAX
        )
        val normalizedZcr = normalize(
            features.zcr.toFloat(), ZCR_MIN, ZCR_MAX
        )
        val normalizedCentroid = normalize(
            features.spectralCentroid.toFloat(), SPECTRAL_CENTROID_MIN, SPECTRAL_CENTROID_MAX
        )
        val normalizedVariance = normalize(
            features.energyVariance.toFloat(), ENERGY_VARIANCE_MIN, ENERGY_VARIANCE_MAX
        )

        val acousticScore = (
            0.30f * normalizedEnergy +
            0.25f * normalizedPitch +
            0.20f * normalizedZcr +
            0.15f * normalizedCentroid +
            0.10f * normalizedVariance
        )

        return acousticScore.coerceIn(0f, 1f)
    }

    /**
     * Calculate Motion Score (MS) from sensor data.
     *
     * Weighted combination:
     * - 0.40 × normalized(acceleration_magnitude)
     * - 0.35 × normalized(acceleration_variance)
     * - 0.15 × normalized(gyro_magnitude)
     * - 0.10 × normalized(heart_rate_elevation)
     */
    private fun calculateMotionScore(stats: MotionStats): Float {
        val normalizedAccel = normalize(
            stats.accelerationMagnitude, ACCEL_MAG_MIN, ACCEL_MAG_MAX
        )
        val normalizedAccelVar = normalize(
            stats.accelerationVariance, ACCEL_VAR_MIN, ACCEL_VAR_MAX
        )
        val normalizedGyro = normalize(
            stats.gyroMagnitude, GYRO_MAG_MIN, GYRO_MAG_MAX
        )
        val normalizedHR = normalize(
            stats.heartRateElevation, HR_ELEVATION_MIN, HR_ELEVATION_MAX
        )

        val motionScore = (
            0.40f * normalizedAccel +
            0.35f * normalizedAccelVar +
            0.15f * normalizedGyro +
            0.10f * normalizedHR
        )

        return motionScore.coerceIn(0f, 1f)
    }

    /**
     * Calculate Combined Agitation Score (CAS) with adaptive weighting.
     *
     * Weighting strategy based on speech confidence:
     * - High speech (SDS > 0.6): Speech-dominant (50% speech, 30% acoustic, 20% motion)
     * - Medium speech (SDS > 0.3): Balanced (35% speech, 40% acoustic, 25% motion)
     * - Low/no speech: Motion/Acoustic-dominant (10% speech, 50% acoustic, 40% motion)
     */
    private fun calculateAdaptiveWeightedScore(
        speechScore: Float,
        acousticScore: Float,
        motionScore: Float
    ): Float {
        return when {
            // High speech confidence - prioritize speech
            speechScore > 0.6f -> {
                0.50f * speechScore + 0.30f * acousticScore + 0.20f * motionScore
            }
            // Medium speech confidence - balanced approach
            speechScore > 0.3f -> {
                0.35f * speechScore + 0.40f * acousticScore + 0.25f * motionScore
            }
            // Low/no speech - rely on acoustic and motion
            else -> {
                0.10f * speechScore + 0.50f * acousticScore + 0.40f * motionScore
            }
        }
    }

    /**
     * Normalize a value to [0, 1] range.
     */
    private fun normalize(value: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        return ((value - min) / (max - min)).coerceIn(0f, 1f)
    }

    /**
     * Create motion stats from sensor readings.
     *
     * @param accelReadings List of acceleration magnitude readings
     * @param gyroReadings List of (x, y, z) gyroscope readings
     * @param heartRate Current heart rate (null if unavailable)
     */
    fun createMotionStats(
        accelReadings: List<Float>,
        gyroReadings: List<Triple<Float, Float, Float>>,
        heartRate: Float?
    ): MotionStats {
        // Calculate acceleration statistics
        val accelMag = if (accelReadings.isNotEmpty()) {
            accelReadings.average().toFloat()
        } else {
            9.8f  // Default gravity
        }

        val accelVariance = if (accelReadings.size > 1) {
            val mean = accelReadings.average()
            accelReadings.map { (it - mean) * (it - mean) }.average().toFloat()
        } else {
            0f
        }

        // Calculate gyroscope magnitude (RMS of angular velocities)
        val gyroMag = if (gyroReadings.isNotEmpty()) {
            val magnitudes = gyroReadings.map { (x, y, z) ->
                sqrt(x * x + y * y + z * z)
            }
            magnitudes.average().toFloat()
        } else {
            0f
        }

        return MotionStats(
            accelerationMagnitude = accelMag,
            accelerationVariance = accelVariance,
            gyroMagnitude = gyroMag,
            heartRate = heartRate
        )
    }
}
