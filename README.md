# RealTimeSensorWearOs (Watch App)

A highly customized Kotlin-based Android application tailored for Wear OS 4+ smartwatches. This app operates as the primary data ingestion engine for a cognitive and behavioral monitoring platform, utilizing hardware sensors, microphones, and the Samsung Health Sensor SDK to accurately aggregate and upload real-time multi-modal physiological data to Firebase Firestore.

## Core Features

- **Continuous Hardware Telemetry**: Monitors Accelerometer, Gyroscope, Heart Rate, and Ambient Light sensors via standard Android SensorManager APIs.
- **Micro-batching & Firebase Synchronization**: Bundles raw sensor payloads per epoch into distinct Firestore collections (`sensor_samples`, `audio_samples`, `camera_samples`) to maximize battery usage and optimize cloud-side rendering.
- **SpO2 Integration via Samsung Health**: Implements a dedicated `OptionalSamsungSpo2Manager` that bypasses legacy reflection patterns in favor of direct SDK callbacks. Extracts comprehensive blood oxygen saturation and handles listener lifecycle (`STATUS_COMPLETE`) flawlessly.
- **CMAI Metric Pipeline**: Acts as the data proxy for downstream Dashboard CMAI inference engines (predicting Pain, Agitation, Tremors) based on raw arrays and mic energies collected here.

## Project Structure & Architecture

- **/app/src/main/java/com/example/realtimesensorwearos/presentation/**: Contains the Wear OS compose UI, background worker services, and explicit handlers for sensory hardware.
- **`OptionalSamsungSpo2Manager.kt`**: Safely encapsulates Samsung Health API logic to ping for `SPO2_ON_DEMAND` capabilities and avoids crashes on unsupported watch models.

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
