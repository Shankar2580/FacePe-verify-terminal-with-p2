# P2 Camera Integration Plan for Verify Terminal App

## Overview
Replace the native `expo-camera` with **Orbbec P2 RGB camera** for better depth sensing and liveness detection.

---

## ✅ Completed Steps

### 1. Created P2 Camera Service
- ✅ Created `src/p2Camera.ts` - TypeScript service for P2 camera control

---

## 📋 Remaining Steps

### 2. Copy Native Android Code
Copy these files from `FacePeKioskFrontend/android/app/src/main/java/ai/facepe/merchantapp/p2camera/`:

**Source:** `c:\Users\HP\Documents\mobileApp\FacePeMerchnet\FacePeKioskFrontend\android\app\src\main\java\ai\facepe\merchantapp\p2camera\`

**Destination:** `c:\Users\HP\Documents\mobileApp\facepe-verify-terminal-app\android\app\src\main\java\com\vu2s222\facepeterminal\p2camera\`

**Files to copy:**
1. `LivenessProcessor.java` (30KB) - Depth-based liveness detection
2. `OpenNIEngine.java` (14KB) - OpenNI2 depth camera engine
3. `P2CameraModule.java` (20KB) - React Native bridge module
4. `P2CameraPackage.java` (1KB) - Package registration
5. `RGBCameraView.java` (4.5KB) - UVC RGB camera view
6. `RGBCameraViewManager.java` (1.6KB) - View manager for React Native
7. `UvcStreamManager.java` (18KB) - UVC camera stream manager

**Important:** Update package names from `ai.facepe.merchantapp` to `com.vu2s222.facepeterminal`

---

### 3. Update Android Build Configuration

#### `android/app/build.gradle`
Add dependencies:
```gradle
dependencies {
    // ... existing dependencies ...
    
    // P2 Camera dependencies
    implementation files('libs/libUVCCamera.aar')
    implementation files('libs/OpenNI2.jar')
}
```

#### Create `android/app/libs/` folder
Copy these library files from Kiosk app:
- `libUVCCamera.aar` - UVC camera library
- `OpenNI2.jar` - OpenNI2 Java bindings

---

### 4. Register P2 Camera Module

#### `android/app/src/main/java/com/vu2s222/facepeterminal/MainApplication.kt`
Add P2CameraPackage to the packages list:

```kotlin
import com.vu2s222.facepeterminal.p2camera.P2CameraPackage

override fun getPackages(): List<ReactPackage> {
    return PackageList(this).packages.apply {
        add(P2CameraPackage())  // Add this line
    }
}
```

---

### 5. Create RGBCameraView Component (TypeScript)

Create `src/components/RGBCameraView.tsx`:
```typescript
import { requireNativeComponent, ViewProps } from 'react-native';

interface RGBCameraViewProps extends ViewProps {
  style?: any;
}

export const RGBCameraView = requireNativeComponent<RGBCameraViewProps>('RGBCameraView');
```

---

### 6. Update App.tsx

Replace expo-camera with P2 camera:

**Remove:**
```typescript
import { CameraView, useCameraPermissions } from 'expo-camera';
const cameraRef = useRef<CameraView>(null);
const photo = await cameraRef.current?.takePictureAsync({ quality: 0.85 });
```

**Add:**
```typescript
import { p2Camera } from './src/p2Camera';
import { RGBCameraView } from './src/components/RGBCameraView';
import { PermissionsAndroid, AppState } from 'react-native';

// In component:
useEffect(() => {
  if (!p2Camera.isAvailable()) return;
  
  const startP2 = async () => {
    const hasPermission = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.CAMERA);
    if (!hasPermission) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA);
    }
    await p2Camera.startStreams();
  };
  
  setTimeout(() => startP2(), 500);
  
  return () => {
    p2Camera.stopStreams();
  };
}, [mode === 'capture']);

// Replace takePictureAsync with:
const photo = await p2Camera.capturePhoto();
```

**Replace camera view:**
```typescript
{/* OLD */}
<CameraView ref={cameraRef} style={styles.camera} facing="front" />

{/* NEW */}
<RGBCameraView style={styles.camera} />
```

---

### 7. Add Native Libraries to Android Project

Copy from Kiosk app `android/app/src/main/jniLibs/`:
- `arm64-v8a/` folder with `.so` files
- `armeabi-v7a/` folder with `.so` files

These contain:
- `libOpenNI2.so`
- `liborbbecusb2.so`
- `libUVCCamera.so`
- Other native libraries

---

### 8. Update AndroidManifest.xml

Add USB permissions:
```xml
<uses-feature android:name="android.hardware.usb.host" />
<uses-permission android:name="android.permission.CAMERA" />
```

---

## 🔧 Testing Steps

1. Build Android APK: `eas build --platform android --profile preview`
2. Install on Sunmi device with P2 camera
3. Test camera stream appears
4. Test face capture works
5. Test liveness detection (optional)

---

## 📝 Notes

- P2 camera requires **Orbbec P2 hardware** (VID=0x2bc5, PID=0x051e for RGB)
- RGB stream uses UVC (USB Video Class)
- Depth stream uses OpenNI2
- Liveness detection uses depth data
- Camera permission must be granted at runtime

---

## ⚠️ Potential Issues

1. **SELinux on Sunmi** - May need Permissive mode for depth camera
2. **USB permissions** - Need to handle USB device permissions
3. **White screen** - Fixed by 500ms delay before starting streams
4. **Package name mismatch** - Must update all package imports

---

## 🎯 Expected Result

After integration:
- ✅ P2 RGB camera displays in capture screen
- ✅ Face capture uses P2 camera instead of device camera
- ✅ Better image quality for verification
- ✅ Optional: Liveness detection using depth data
