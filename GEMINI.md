# JugglucoNG / Glucodata Project Context

## Project Overview
**JugglucoNG** (internally project "Glucodata") is an Android application for Continuous Glucose Monitoring (CGM). It appears to be a successor or variant of the Juggluco app, designed to interface with various glucose sensors (Libre 3, Sibionics, etc.) and display glucose levels, alarms, and history.

The project is structured as a single-module Android project (`:Common`) that uses extensive source sets and build variants to produce different applications, including:
- **Mobile App:** The main phone application.
- **WearOS App:** A companion app for smartwatches.
- **Sensor Variants:** Specific builds for different sensor types (e.g., Libre 3, Sibionics).
- **Service Variants:** Builds with and without Google Play Services dependencies.

## Key Technologies
*   **Language:** Kotlin/Java for Android UI and system integration; **C++** (heavy usage) for core logic, cryptography, and sensor communication.
*   **Build System:** Gradle (AGP 8.13.1) with CMake for native code compilation.
*   **Native Libraries:** Uses `nanovg` for rendering, `LibAscon` and `bcrypt` for cryptography, and direct NFC/Bluetooth LE interaction in C++.

## Project Structure
*   `Common/`: The main module containing all source code.
    *   `src/main/`: Core Java/Kotlin and C++ sources.
    *   `src/main/cpp/`: Extensive C++ codebase including `CMakeLists.txt`.
    *   `src/mobile/`, `src/wear/`: Source sets specific to phone and watch form factors.
    *   `src/libre3/`, `src/libreOld/`: Source sets for different sensor generations.
    *   `src/mobileSi.../`: Source sets for Sibionics sensor variants.
*   `makeapk.sh`: A shell script used to trigger specific Gradle build tasks (e.g., for WearOS releases).
*   `debug.sh`: Script for building debug versions.

## Building and Running

### Standard Build (Gradle)
The project uses the standard Gradle wrapper. You can build specific variants using the `assemble` tasks.

**List all tasks:**
```bash
./gradlew tasks
```

**Build a specific variant (example):**
```bash
# Build Mobile Libre 3 with Sibionics support (Google version)
./gradlew assembleMobileLibre3SiDexGoogleRelease

# Build WearOS version
./gradlew assembleWearLibre3SiDexGoogleRelease
```

### Helper Scripts
The project includes shell scripts to simplify complex build commands:
*   **Build Release APKs:**
    ```bash
    ./makeapk.sh
    ```
*   **Debug Build:**
    ```bash
    ./debug.sh
    ```

## Development Conventions
*   **Native Code First:** A significant portion of the app's functionality (including UI elements drawn via `nanovg` and sensor protocols) is implemented in C++. Changes often require modifying files in `Common/src/main/cpp/`.
*   **Source Sets:** Pay close attention to which source set a file belongs to. Code in `src/mobile` will not be available in the `wear` build, and vice versa.
*   **Signing:** The project is configured to use a default `everyone.keystore` (password: `IDontKnow`) for signing if specific properties are not set, making it easy to build and test locally.

## Critical Files
*   `Common/build.gradle`: Defines the complex flavor/source set logic.
*   `Common/src/main/cpp/CMakeLists.txt`: Configuration for the C++ native build.
*   `Common/src/main/AndroidManifest.xml`: The primary Android manifest (note that variants may have their own manifests that merge with this).
