# Catsmoker Plus Implementation Plan

Integrate the advanced performance monitoring and modern UI components of FrameX into Catsmoker to create an upgraded experience called **Catsmoker Plus**.

## User Review Required

> [!IMPORTANT]
> The integration introduces Jetpack Compose into the existing View-based Catsmoker project. This is a significant architectural addition but is necessary to leverage FrameX's modern UI.
> Existing Catsmoker features will remain intact and accessible.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/build.gradle.kts)
- Enable Jetpack Compose.
- Add dependencies for Compose Material 3, Hilt, and Navigation.
- Add FrameX-specific dependencies (e.g., Google Fonts).

### Core Engines & Utilities [NEW]

#### [NEW] [MetricsEngine.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/plus/metrics/MetricsEngine.kt)
- Port FrameX's metrics collection logic (FPS, CPU, RAM).

#### [NEW] [ThermalServiceParser.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/plus/metrics/ThermalServiceParser.kt)
- Port thermal diagnostic capabilities.

#### [NEW] [GamingModeEngine.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/plus/gaming/GamingModeEngine.kt)
- Port advanced gaming optimization logic.

### UI Integration

#### [NEW] [PlusDashboardActivity.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/plus/ui/PlusDashboardActivity.kt)
- New entry point for Catsmoker Plus features using Jetpack Compose.

#### [NEW] [PlusDashboardScreen.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/plus/ui/screens/PlusDashboardScreen.kt)
- Integrated dashboard based on FrameX's UI but themed for Catsmoker.

#### [NEW] [Theme.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/plus/ui/theme/Theme.kt)
- Compose theme for Catsmoker Plus.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Lenovo/StudioProjects/com.catsmoker.app/app/src/main/java/com/catsmoker/app/main/MainActivity.kt)
- Add a "Catsmoker Plus" button to launch the new dashboard.

### Assets [NEW]
- Copy required icons and assets from FrameX to Catsmoker's `res` directory.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure compilation success.
- Verify Shizuku integration remains functional.

### Manual Verification
- Launch Catsmoker and verify all original buttons (Spoofing, Root, About) work.
- Launch "Catsmoker Plus" and verify the live FPS graph and quick actions.
- Test new "Thermal Diagnostics" screen.
