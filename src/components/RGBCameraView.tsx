import { requireNativeComponent, ViewProps } from 'react-native';

interface RGBCameraViewProps extends ViewProps {
  style?: any;
}

export const RGBCameraView = requireNativeComponent<RGBCameraViewProps>('RGBCameraView');
