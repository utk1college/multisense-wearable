# RealTimeSensorWearOs (Watch App)

A highly customized Kotlin-based Android application tailored for Wear OS 4+ smartwatches. This app operates as the primary data ingestion engine for a cognitive and behavioral monitoring platform, utilizing hardware sensors, microphones, and the Samsung Health Sensor SDK to accurately aggregate and upload real-time multi-modal physiological data to Firebase Firestore.

## Core Features

- **Continuous Hardware Telemetry**: Monitors Accelerometer, Gyroscope, Heart Rate, and Ambient Light sensors via standard Android SensorManager APIs.
- **Acoustic Monitoring via Microphone**: Captures real-time audio at 16kHz sampling rate with advanced feature extraction (RMS energy, Zero-Crossing Rate, Spectral Centroid, Pitch, MFCCs). Supplies raw PCM streams and computed acoustic features to behavioral monitoring pipelines.
- **Micro-batching & Firebase Synchronization**: Bundles raw sensor payloads per epoch into distinct Firestore collections (`sensor_samples`, `audio_samples`, `camera_samples`) to maximize battery usage and optimize cloud-side rendering.
- **SpO2 Integration via Samsung Health**: Implements a dedicated `OptionalSamsungSpo2Manager` that bypasses legacy reflection patterns in favor of direct SDK callbacks. Extracts comprehensive blood oxygen saturation and handles listener lifecycle (`STATUS_COMPLETE`) flawlessly.
- **CMAI Metric Pipeline**: Acts as the data proxy for downstream Dashboard CMAI inference engines (predicting Pain, Agitation, Tremors) based on raw arrays and acoustic features collected here.

## Project Structure & Architecture

- **/app/src/main/java/com/example/realtimesensorwearos/presentation/**: Contains the Wear OS compose UI, background worker services, and explicit handlers for sensory hardware.
- **`OptionalSamsungSpo2Manager.kt`**: Safely encapsulates Samsung Health API logic to ping for `SPO2_ON_DEMAND` capabilities and avoids crashes on unsupported watch models.
- **`AudioFeatureExtractor.kt`**: Handles real-time audio acquisition and mathematically-defined acoustic feature computation for behavioral monitoring.

## Microphone & Audio Feature Extraction

The app includes a sophisticated real-time audio processing pipeline (`AudioFeatureExtractor`) that captures and analyzes microphone input for behavioral assessment:

### Recording Specification
- **Sample Rate**: 16 kHz (16,000 samples per second)
- **Bit Depth**: 16-bit PCM (mono channel)
- **Default Recording Window**: 5 seconds per epoch
- **Buffer Size**: Adaptive based on device minimum buffer requirements

### Extracted Acoustic Features
All audio features are mathematically-defined for deterministic, reproducible analysis:

**Time-Domain Features**:
- **RMS Energy**: Perceived loudness (√(1/N × Σx[n]²))
- **Zero-Crossing Rate (ZCR)**: Frequency content indicator [0, 1]; high ZCR indicates noise/unvoiced speech
- **Frame Energy Variance**: Log-scaled variance across frame energies for temporal variability

**Frequency-Domain Features**:
- **Spectral Centroid**: Center of spectral mass (Hz); shifts higher for aggressive/strained vocalizations
- **Spectral Bandwidth**: Spread of energy around centroid (Hz)
- **Spectral Flux**: Frame-to-frame spectral change magnitude

**Voice & Pitch**:
- **Fundamental Frequency (Pitch)**: Estimated via autocorrelation over [80–400 Hz] range
- **Speech Ratio**: Voice Activity Detection ratio [0, 1] combining energy, ZCR, and spectral cues

**Cepstral Features**:
- **MFCCs (13 coefficients)**: Mel-Frequency Cepstral Coefficients derived from FFT → Mel Filterbank (300–8000 Hz) → Log scale → Discrete Cosine Transform

### Raw Audio Capture
In addition to extracted features, the system captures and uploads **raw PCM audio bytes** to Firebase for:
- Speech-to-text processing and transcription
- Secondary acoustic analysis and validation
- Longitudinal behavioral trend analysis

### Firebase Integration
Processed audio features and raw PCM samples are aggregated per epoch and stored in Firestore's `audio_samples` collection for synchronization with upstream CMAI inference engines.

### Microphone Permissions
The app requires the `RECORD_AUDIO` Android permission. Ensure the watch OS and user grant this permission:
- Android 6.0+: Runtime permission request at first audio recording attempt
- Wear OS 4+: Check device Settings > Apps > [App Name] > Permissions > Microphone

## Samsung SDK Setup & Build Protocol

Since this project relies on device-level biometrics, it requires specific proprietary files to build correctly.

1. **Samsung Health Tracker SDK**:
   Place the Samsung SDK file (`samsung-health-sensor-api-1.4.1.aar`) inside the `app/libs/` directory. *(Note: This file is ignored via `.gitignore` to comply with proprietary distribution guidelines).*
2. **Google Services Initialization**:
   Add your Firebase `google-services.json` into the `app/` directory for cloud authentication. *(Note: Also strictly blocked by `.gitignore`).*
3. **Developer Mode Bypass**:
   Because custom SpO2 tracking fails with an `SDK_POLICY_ERROR` on unpartnered/debug APKs, ensure the physical Galaxy watch running this app has Developer Mode on:
   - Navigate to Watch Settings > Apps > Health Platform.
   - Tap the sub-version / title exactly 10 times to unlock `[Dev Mode]`.

## Security 
The `.gitignore` has been carefully configured to ensure that Android Studio configuration files (`.idea/`), generated builds (`build/`), and secret keys (`google-services.json`) are blocked from upstream repository mirrors.

## License & Usage

This application is built as an experimental research prototype for capturing behavioral anomalies. Please ensure all testing occurs with compliance to user data tracking and privacy laws concerning medical biometric arrays.
