import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { P2CameraModule } = NativeModules;

export interface LivenessResult {
  isLive: boolean;
  confidence: number;
  message: string;
}

export interface CapturedPhoto {
  uri: string;
  width: number;
  height: number;
  base64?: string;
}

interface P2CameraEvents {
  onRGBStatus: (status: { connected: boolean; message: string }) => void;
  onDepthStatus: (status: { connected: boolean; message: string }) => void;
  onRGBFrame: (frame: { timestamp: number }) => void;
  onDepthFrame: (frame: { timestamp: number }) => void;
  onLivenessResult: (result: LivenessResult) => void;
}

class P2CameraService {
  private eventEmitter: NativeEventEmitter | null = null;
  private listeners: Map<string, any> = new Map();

  constructor() {
    if (P2CameraModule && Platform.OS === 'android') {
      this.eventEmitter = new NativeEventEmitter(P2CameraModule);
    }
  }

  isAvailable(): boolean {
    return !!P2CameraModule && Platform.OS === 'android';
  }

  async startStreams(): Promise<string> {
    if (!this.isAvailable()) {
      throw new Error('P2 Camera is not available on this platform');
    }
    return P2CameraModule.startStreams();
  }

  async stopStreams(): Promise<string> {
    if (!this.isAvailable()) {
      return 'P2 Camera not available';
    }
    return P2CameraModule.stopStreams();
  }

  async capturePhoto(): Promise<CapturedPhoto> {
    if (!this.isAvailable()) {
      throw new Error('P2 Camera is not available on this platform');
    }
    return P2CameraModule.capturePhoto();
  }

  async checkLiveness(): Promise<LivenessResult> {
    if (!this.isAvailable()) {
      throw new Error('P2 Camera is not available on this platform');
    }
    return P2CameraModule.checkLiveness();
  }

  async resetLiveness(): Promise<string> {
    if (!this.isAvailable()) {
      return 'P2 Camera not available';
    }
    return P2CameraModule.resetLiveness();
  }

  addEventListener(events: Partial<P2CameraEvents>): void {
    if (!this.eventEmitter) return;

    if (events.onRGBStatus) {
      const listener = this.eventEmitter.addListener('onRGBStatus', events.onRGBStatus);
      this.listeners.set('onRGBStatus', listener);
    }

    if (events.onDepthStatus) {
      const listener = this.eventEmitter.addListener('onDepthStatus', events.onDepthStatus);
      this.listeners.set('onDepthStatus', listener);
    }

    if (events.onRGBFrame) {
      const listener = this.eventEmitter.addListener('onRGBFrame', events.onRGBFrame);
      this.listeners.set('onRGBFrame', listener);
    }

    if (events.onDepthFrame) {
      const listener = this.eventEmitter.addListener('onDepthFrame', events.onDepthFrame);
      this.listeners.set('onDepthFrame', listener);
    }

    if (events.onLivenessResult) {
      const listener = this.eventEmitter.addListener('onLivenessResult', events.onLivenessResult);
      this.listeners.set('onLivenessResult', listener);
    }
  }

  removeAllListeners(): void {
    this.listeners.forEach((listener) => {
      if (listener && typeof listener.remove === 'function') {
        listener.remove();
      }
    });
    this.listeners.clear();
  }
}

export const p2Camera = new P2CameraService();
