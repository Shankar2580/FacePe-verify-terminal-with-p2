package com.vu2s222.facepeterminal.p2camera;

import android.content.Context;

import androidx.annotation.NonNull;

import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;

/**
 * React Native ViewManager for RGBCameraView.
 *
 * Registers the native TextureView under the JS name "RGBCameraView" so it can be
 * used in React Native like:
 *   import { requireNativeComponent } from 'react-native';
 *   const RGBCameraView = requireNativeComponent('RGBCameraView');
 *   ...
 *   <RGBCameraView style={{ flex: 1 }} />
 *
 * P2CameraModule holds a reference to the created view and calls
 * view.attachCamera(proxy) when the UVC camera is opened.
 */
public class RGBCameraViewManager extends SimpleViewManager<RGBCameraView> {

    public static final String REACT_CLASS = "RGBCameraView";

    // Singleton reference so P2CameraModule can find the active view
    private static RGBCameraView activeView = null;

    public static RGBCameraView getActiveView() {
        return activeView;
    }

    @NonNull
    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @NonNull
    @Override
    protected RGBCameraView createViewInstance(@NonNull ThemedReactContext reactContext) {
        RGBCameraView view = new RGBCameraView(reactContext);
        activeView = view;
        return view;
    }

    @Override
    public void onDropViewInstance(@NonNull RGBCameraView view) {
        super.onDropViewInstance(view);
        view.detachCamera();
        if (activeView == view) {
            activeView = null;
        }
    }
}
