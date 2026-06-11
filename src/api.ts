import Constants from 'expo-constants';
import type { TerminalAuthResponse, TerminalTask } from './types';

const rawBaseUrl = Constants.expoConfig?.extra?.EXPO_PUBLIC_API_URL || 'https://api.dev.facepe.ai/fv';
export const API_BASE_URL = rawBaseUrl.replace(/\/+$/, '').endsWith('/fv')
  ? rawBaseUrl.replace(/\/+$/, '')
  : `${rawBaseUrl.replace(/\/+$/, '')}/fv`;
export const API_V1_BASE_URL = `${API_BASE_URL}/api/v1`;
export const WS_BASE_URL = API_BASE_URL.replace(/^http/, 'ws');

let accessToken = '';
let refreshToken = '';
let onTokensRefreshed: ((auth: TerminalAuthResponse) => void) | null = null;

export const setAccessToken = (token: string) => {
  accessToken = token;
};

export const setRefreshToken = (token: string) => {
  refreshToken = token;
};

export const setTokens = (access: string, refresh: string) => {
  accessToken = access;
  refreshToken = refresh;
};

export const onAuthRefreshed = (cb: (auth: TerminalAuthResponse) => void) => {
  onTokensRefreshed = cb;
};

const refreshAccessToken = async (): Promise<boolean> => {
  if (!refreshToken) return false;
  try {
    const response = await fetch(`${API_V1_BASE_URL}/terminals/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });
    if (!response.ok) return false;
    const auth = (await response.json()) as TerminalAuthResponse;
    accessToken = auth.access_token;
    refreshToken = auth.refresh_token;
    onTokensRefreshed?.(auth);
    return true;
  } catch {
    return false;
  }
};

const request = async <T>(path: string, options: RequestInit = {}, _retried = false): Promise<T> => {
  const headers = new Headers(options.headers);
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (!(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(`${API_V1_BASE_URL}${path}`, { ...options, headers });
  if (response.status === 401 && !_retried && refreshToken && path !== '/terminals/refresh') {
    if (await refreshAccessToken()) {
      return request<T>(path, options, true);
    }
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const message = data?.detail || data?.message || `Request failed (${response.status})`;
    throw new Error(typeof message === 'string' ? message : JSON.stringify(message));
  }
  return data as T;
};

export const terminalLogin = (terminal_code: string, password: string) =>
  request<TerminalAuthResponse>('/terminals/login', {
    method: 'POST',
    body: JSON.stringify({ terminal_code, password }),
  });

export const registerTerminal = (payload: {
  terminal_code: string;
  password: string;
  name: string;
  branch_code?: string;
}) =>
  request<TerminalAuthResponse>('/terminals/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  });

export const getNextTask = () => request<TerminalTask | null>('/terminals/tasks/next');

export const updateTaskStatus = (
  taskId: string,
  status: 'accepted' | 'in_progress' | 'completed' | 'failed' | 'cancelled',
  result?: Record<string, any>,
  error?: string,
) =>
  request<TerminalTask>(`/terminal-tasks/${taskId}/status`, {
    method: 'POST',
    body: JSON.stringify({ status, result, error }),
  });

export const recordConsent = (sessionId: string, accepted: boolean, deviceId: string) =>
  request(`/face-registration-sessions/${sessionId}/consent`, {
    method: 'POST',
    body: JSON.stringify({ accepted, device_id: deviceId, consent_version: 'v1' }),
  });

export const sendOtp = (sessionId: string) =>
  request<{ otp_reference: string }>(`/face-registration-sessions/${sessionId}/otp/send`, {
    method: 'POST',
    body: JSON.stringify({ channel: 'sms' }),
  });

export const verifyOtp = (sessionId: string, otp_reference: string, otp_code: string) =>
  request(`/face-registration-sessions/${sessionId}/otp/verify`, {
    method: 'POST',
    body: JSON.stringify({ otp_reference, otp_code }),
  });

export const issueNonce = (sessionId: string, deviceId: string) =>
  request<{ nonce: string }>(`/face-registration-sessions/${sessionId}/nonce`, {
    method: 'POST',
    body: JSON.stringify({ device_id: deviceId, purpose: 'face_registration' }),
  });

export const enrollFace = (sessionId: string, imageUri: string, nonce: string, deviceId: string, force: boolean) => {
  const form = new FormData();
  form.append('nonce', nonce);
  form.append('device_id', deviceId);
  form.append('force_reenrollment', String(force));
  form.append('file', {
    uri: imageUri,
    name: 'face.jpg',
    type: 'image/jpeg',
  } as any);
  return request(`/face-registration-sessions/${sessionId}/enroll-face`, {
    method: 'POST',
    body: form,
  });
};

export const verifyFaceForTask = (taskId: string, imageUri: string) => {
  const form = new FormData();
  form.append('file', {
    uri: imageUri,
    name: 'face.jpg',
    type: 'image/jpeg',
  } as any);
  return request<TerminalTask>(`/terminal-tasks/${taskId}/face/verify`, {
    method: 'POST',
    body: form,
  });
};

export const sendAmbiguousVerifyOtp = (taskId: string, account_id: string) =>
  request<TerminalTask>(`/terminal-tasks/${taskId}/face/verify/otp/send`, {
    method: 'POST',
    body: JSON.stringify({ account_id, channel: 'sms' }),
  });

export const confirmAmbiguousVerifyOtp = (taskId: string, otp_reference: string, otp_code: string) =>
  request<TerminalTask>(`/terminal-tasks/${taskId}/face/verify/otp/confirm`, {
    method: 'POST',
    body: JSON.stringify({ otp_reference, otp_code }),
  });
