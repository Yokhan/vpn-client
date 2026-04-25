# Android Studio Setup

Use the official Android Studio installer for Windows:

- https://developer.android.com/studio
- https://developer.android.com/studio/install

As of 2026-04-25, Google's Windows guidance requires a 64-bit Windows machine, recommends the `.exe` installer, and installs the recommended SDK packages through the Android Studio Setup Wizard.

## Install

1. Download the Windows `.exe` from the official Android Studio page.
2. Run the installer.
3. Keep these components enabled:
   - Android Studio
   - Android SDK
   - Android Virtual Device
4. Launch Android Studio.
5. Choose the standard setup unless you need a custom SDK directory.
6. Let it install the recommended SDK packages.

## Open This Repo

1. In Android Studio, choose `Open`.
2. Select `C:\Users\iohan\Documents\YokhanVPN`.
3. Wait for Gradle sync to finish.
4. If Android Studio asks for a JDK, use the bundled JDK 17.
5. If it asks for Android SDK location, use the SDK installed by the setup wizard.

## Phone Testing

1. Enable Developer Options on the phone.
2. Enable USB debugging.
3. Connect the phone by USB.
4. Accept the RSA debugging prompt on the phone.
5. In Android Studio, select the phone as the run target.

If Windows does not see the device, install the OEM USB driver or Google's USB driver from Android Studio SDK Manager.

## Emulator Testing

1. Open `Tools > Device Manager`.
2. Create a virtual device.
3. Pick a recent Pixel profile and a stable Android system image.
4. Start the emulator and run the app.

The emulator is useful for layout checks. Real VPN behavior still needs a physical Android device.

## Compose UI Checks

Android Compose route:

```powershell
cmd /c gradlew.bat -PyokhanComposeDashboard=true :app:assembleOssDebug --no-daemon
```

Desktop dashboard preview:

```powershell
cmd /c gradlew.bat :desktop-preview:run --no-daemon
```

Build a local Windows app for the dashboard preview:

```powershell
cmd /c gradlew.bat :desktop-preview:createDistributable --no-daemon
```

The built app is written under `preview/desktop/build/compose/binaries/main/app`.

The desktop preview uses the same shared Compose dashboard layout, rocket/flame PNG assets, and bundled fonts as the Android Compose route. It is faster for visual iteration on Windows, but final release checks still require a signed APK on the phone because Android density, status bars, touch behavior, and VPN lifecycle remain platform-specific.

The preview viewport is fixed to iPhone 15 Pro Max logical size (`430x932 dp`). Use `Save phone screenshot` to write PNG references to `preview/desktop/build/screenshots`.
