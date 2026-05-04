import { useEffect, useRef, useState } from 'react';
import {
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { StatusBar } from 'expo-status-bar';
import { LinearGradient } from 'expo-linear-gradient';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import {
  enrollFace,
  getNextTask,
  confirmAmbiguousVerifyOtp,
  issueNonce,
  recordConsent,
  registerTerminal,
  sendAmbiguousVerifyOtp,
  sendOtp,
  setAccessToken,
  terminalLogin,
  updateTaskStatus,
  verifyFaceForTask,
  verifyOtp,
  WS_BASE_URL,
} from './src/api';
import type { TerminalAuthResponse, TerminalTask } from './src/types';

type Mode = 'login' | 'idle' | 'consent' | 'otp' | 'capture' | 'ambiguous' | 'done';

function BrandBackdrop() {
  return (
    <View pointerEvents="none" style={StyleSheet.absoluteFill}>
      <LinearGradient
        colors={['#eef2ff', '#fdf2f8', '#f8fafc']}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={StyleSheet.absoluteFill}
      />
      {/* Soft indigo orb */}
      <View style={styles.orbIndigo} />
      {/* Soft pink orb */}
      <View style={styles.orbPink} />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <AppInner />
    </SafeAreaProvider>
  );
}

function AppInner() {
  const cameraRef = useRef<CameraView>(null);
  const loginScrollRef = useRef<ScrollView>(null);
  const [permission, requestPermission] = useCameraPermissions();
  const [terminalCode, setTerminalCode] = useState('');
  const [terminalName, setTerminalName] = useState('');
  const [branchCode, setBranchCode] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [authMode, setAuthMode] = useState<'signin' | 'register'>('signin');
  const [auth, setAuth] = useState<TerminalAuthResponse | null>(null);
  const [mode, setMode] = useState<Mode>('login');
  const [task, setTask] = useState<TerminalTask | null>(null);
  const [otpReference, setOtpReference] = useState('');
  const [otpCode, setOtpCode] = useState('');
  const [selectedCandidateId, setSelectedCandidateId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  const deviceId = auth?.terminal_id || 'facepe-terminal';

  useEffect(() => {
    if (!auth) return;
    const ws = new WebSocket(`${WS_BASE_URL}/ws/terminals/${auth.terminal_id}?token=${encodeURIComponent(auth.access_token)}`);
    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.event === 'terminal.assigned') {
          setAuth((current) => current ? { ...current, assigned_cashier_id: msg.payload.cashier_id, status: 'assigned' } : current);
        }
        if (msg.event === 'terminal.released') {
          setAuth((current) => current ? { ...current, assigned_cashier_id: null, status: 'available' } : current);
        }
        if (msg.event === 'terminal_task.created') {
          receiveTask(msg.payload);
        }
      } catch {
        // Polling covers missed or malformed realtime messages.
      }
    };
    const interval = setInterval(async () => {
      try {
        const next = await getNextTask();
        if (next && (!task || next.id !== task.id)) receiveTask(next);
      } catch {
        // Keep terminal available; network state is shown when actions fail.
      }
    }, 2500);
    return () => {
      ws.close();
      clearInterval(interval);
    };
  }, [auth?.terminal_id, auth?.access_token, task?.id]);

  const receiveTask = async (nextTask: TerminalTask) => {
    setTask(nextTask);
    setError('');
    setMessage('');
    setBusy(true);
    try {
      await updateTaskStatus(nextTask.id, 'accepted');
      if (nextTask.task_type === 'face_registration') {
        setMode(nextTask.session?.consent_required ? 'consent' : nextTask.session?.otp_required ? 'otp' : 'capture');
        if (!nextTask.session?.consent_required && nextTask.session?.otp_required) {
          const otp = await sendOtp(nextTask.face_registration_session_id as string);
          setOtpReference(otp.otp_reference);
        }
      } else {
        setMode('capture');
      }
    } catch (err: any) {
      setError(err.message || 'Could not accept task');
    } finally {
      setBusy(false);
    }
  };

  const authenticate = async (register: boolean) => {
    setBusy(true);
    setError('');
    try {
      const response = register
        ? await registerTerminal({
            terminal_code: terminalCode.trim(),
            password,
            name: terminalName.trim() || terminalCode.trim(),
            branch_code: branchCode.trim() || undefined,
          })
        : await terminalLogin(terminalCode.trim(), password);
      setAuth(response);
      setAccessToken(response.access_token);
      setMode('idle');
    } catch (err: any) {
      setError(err.message || 'Authentication failed');
    } finally {
      setBusy(false);
    }
  };

  const acceptConsent = async (accepted: boolean) => {
    if (!task?.face_registration_session_id) return;
    setBusy(true);
    setError('');
    try {
      await updateTaskStatus(task.id, 'in_progress');
      await recordConsent(task.face_registration_session_id, accepted, deviceId);
      if (!accepted) {
        await updateTaskStatus(task.id, 'failed', undefined, 'Customer declined consent');
        setMessage('Consent declined. Please return to the cashier.');
        setMode('done');
        return;
      }
      if (task.session?.otp_required) {
        const otp = await sendOtp(task.face_registration_session_id);
        setOtpReference(otp.otp_reference);
        setMode('otp');
      } else {
        setMode('capture');
      }
    } catch (err: any) {
      setError(err.message || 'Consent failed');
    } finally {
      setBusy(false);
    }
  };

  const submitOtp = async () => {
    if (!task?.face_registration_session_id || !otpReference) return;
    setBusy(true);
    setError('');
    try {
      await verifyOtp(task.face_registration_session_id, otpReference, otpCode.trim());
      setMode('capture');
    } catch (err: any) {
      setError(err.message || 'OTP verification failed');
    } finally {
      setBusy(false);
    }
  };

  const captureAndSubmit = async () => {
    if (!task) return;
    if (!permission?.granted) {
      await requestPermission();
      return;
    }
    setBusy(true);
    setError('');
    try {
      await updateTaskStatus(task.id, 'in_progress');
      const photo = await cameraRef.current?.takePictureAsync({ quality: 0.85 });
      if (!photo?.uri) throw new Error('Could not capture image');

      if (task.task_type === 'face_registration') {
        const sessionId = task.face_registration_session_id as string;
        const nonce = await issueNonce(sessionId, deviceId);
        await enrollFace(sessionId, photo.uri, nonce.nonce, deviceId, Boolean(task.account?.face_enrolled));
        setMessage('Face registration complete. Please return to the cashier.');
      } else {
        const completedTask = await verifyFaceForTask(task.id, photo.uri);
        setTask(completedTask);
        if (completedTask.result?.ambiguous) {
          setMode('ambiguous');
          return;
        }
        if (completedTask.result?.matched) {
          setMessage(`Identity verified for ${completedTask.result.name || 'customer'}. Please return to the cashier.`);
        } else {
          setMessage(completedTask.last_error || 'No matching account found. Please return to the cashier.');
        }
      }
      setMode('done');
    } catch (err: any) {
      setError(err.message || 'Face capture failed');
      try {
        await updateTaskStatus(task.id, 'failed', undefined, err.message || 'Face capture failed');
      } catch {
        // Best effort status update.
      }
    } finally {
      setBusy(false);
    }
  };

  const sendCandidateOtp = async () => {
    if (!task || !selectedCandidateId) return;
    setBusy(true);
    setError('');
    try {
      const updated = await sendAmbiguousVerifyOtp(task.id, selectedCandidateId);
      setTask(updated);
      setOtpReference(String(updated.result?.otp_reference || ''));
    } catch (err: any) {
      setError(err.message || 'Could not send OTP');
    } finally {
      setBusy(false);
    }
  };

  const confirmCandidateOtp = async () => {
    if (!task || !otpReference) return;
    setBusy(true);
    setError('');
    try {
      const updated = await confirmAmbiguousVerifyOtp(task.id, otpReference, otpCode.trim());
      setTask(updated);
      if (updated.result?.matched) {
        setMessage(`Identity verified for ${updated.result.name || 'customer'}. Please return to the cashier.`);
        setMode('done');
      }
    } catch (err: any) {
      setError(err.message || 'OTP verification failed');
    } finally {
      setBusy(false);
    }
  };

  const finish = () => {
    setTask(null);
    setOtpCode('');
    setOtpReference('');
    setSelectedCandidateId('');
    setMessage('');
    setError('');
    setMode('idle');
  };

  const loginDisabled = busy || !terminalCode || !password || (authMode === 'register' && !terminalName);

  return (
    <View style={styles.screen}>
      <BrandBackdrop />
      <StatusBar style="dark" />
      <SafeAreaView style={styles.safeRoot} edges={['top', 'bottom', 'left', 'right']}>
        {mode === 'login' && (
          <KeyboardAvoidingView
            style={styles.flex1}
            behavior={Platform.OS === 'ios' ? 'padding' : undefined}
            keyboardVerticalOffset={0}
          >
            <ScrollView
              ref={loginScrollRef}
              contentContainerStyle={styles.loginScroll}
              keyboardShouldPersistTaps="handled"
              showsVerticalScrollIndicator={false}
            >
              <View style={styles.brandRow}>
                <View style={styles.brandLogoHalo}>
                  <Image source={require('./assets/fplogo.png')} style={styles.brandLogoImg} />
                </View>
                <Text style={styles.brandRowName}>FacePe Verify</Text>
              </View>

              <Text style={styles.displayTitle}>
                {authMode === 'signin' ? 'Welcome back' : 'Set up terminal'}
              </Text>

              <View style={styles.pillSegmented}>
                <Pressable
                  onPress={() => setAuthMode('signin')}
                  style={styles.pillSegmentWrap}
                >
                  {authMode === 'signin' ? (
                    <LinearGradient
                      colors={['#6366f1', '#4f46e5']}
                      start={{ x: 0, y: 0 }}
                      end={{ x: 1, y: 1 }}
                      style={styles.pillSegmentActive}
                    >
                      <Text style={styles.pillSegmentTextActive}>Sign In</Text>
                    </LinearGradient>
                  ) : (
                    <View style={styles.pillSegmentInactive}>
                      <Text style={styles.pillSegmentText}>Sign In</Text>
                    </View>
                  )}
                </Pressable>
                <Pressable
                  onPress={() => setAuthMode('register')}
                  style={styles.pillSegmentWrap}
                >
                  {authMode === 'register' ? (
                    <LinearGradient
                      colors={['#6366f1', '#4f46e5']}
                      start={{ x: 0, y: 0 }}
                      end={{ x: 1, y: 1 }}
                      style={styles.pillSegmentActive}
                    >
                      <Text style={styles.pillSegmentTextActive}>Register</Text>
                    </LinearGradient>
                  ) : (
                    <View style={styles.pillSegmentInactive}>
                      <Text style={styles.pillSegmentText}>Register</Text>
                    </View>
                  )}
                </Pressable>
              </View>

              <View style={styles.glassCard}>
                <View style={styles.inputWrap}>
                  <Text style={styles.inputLabel}>Terminal Code</Text>
                  <TextInput
                    style={styles.premiumInput}
                    placeholder="TERM-001"
                    placeholderTextColor="#a1a1aa"
                    value={terminalCode}
                    onChangeText={setTerminalCode}
                    autoCapitalize="characters"
                    autoCorrect={false}
                    returnKeyType="next"
                  />
                </View>

                {authMode === 'register' && (
                  <>
                    <View style={styles.inputSpacer} />
                    <View style={styles.inputWrap}>
                      <Text style={styles.inputLabel}>Terminal Name</Text>
                      <TextInput
                        style={styles.premiumInput}
                        placeholder="Counter 1"
                        placeholderTextColor="#a1a1aa"
                        value={terminalName}
                        onChangeText={setTerminalName}
                        returnKeyType="next"
                      />
                    </View>
                    <View style={styles.inputSpacer} />
                    <View style={styles.inputWrap}>
                      <Text style={styles.inputLabel}>Branch Code</Text>
                      <TextInput
                        style={styles.premiumInput}
                        placeholder="BR-MUM-01"
                        placeholderTextColor="#a1a1aa"
                        value={branchCode}
                        onChangeText={setBranchCode}
                        autoCapitalize="characters"
                        autoCorrect={false}
                        returnKeyType="next"
                      />
                    </View>
                  </>
                )}

                <View style={styles.inputSpacer} />
                <View style={styles.inputWrap}>
                  <Text style={styles.inputLabel}>Password</Text>
                  <View style={styles.passwordRow}>
                    <TextInput
                      style={[styles.premiumInput, styles.passwordField]}
                      placeholder="Enter password"
                      placeholderTextColor="#a1a1aa"
                      value={password}
                      onChangeText={setPassword}
                      secureTextEntry={!showPassword}
                      returnKeyType="done"
                      onSubmitEditing={() => !loginDisabled && authenticate(authMode === 'register')}
                      onFocus={() => {
                        setTimeout(() => {
                          loginScrollRef.current?.scrollToEnd({ animated: true });
                        }, 250);
                      }}
                    />
                    <Pressable onPress={() => setShowPassword((v) => !v)} hitSlop={12} style={styles.revealBtn}>
                      <Text style={styles.revealText}>{showPassword ? 'Hide' : 'Show'}</Text>
                    </Pressable>
                  </View>
                </View>
              </View>

              <Pressable
                onPress={() => authenticate(authMode === 'register')}
                disabled={loginDisabled}
                style={[styles.ctaButton, loginDisabled && styles.ctaDisabled]}
              >
                <LinearGradient
                  colors={loginDisabled ? ['#94a3b8', '#94a3b8'] : ['#6366f1', '#4f46e5']}
                  start={{ x: 0, y: 0 }}
                  end={{ x: 1, y: 1 }}
                  style={styles.ctaGradient}
                >
                  <Text style={styles.ctaText}>
                    {busy ? 'Please wait…' : authMode === 'signin' ? 'Sign In' : 'Create Terminal'}
                  </Text>
                </LinearGradient>
              </Pressable>

              <Text style={styles.securityNote}>🔒 Secured by FacePe biometrics</Text>
            </ScrollView>
          </KeyboardAvoidingView>
        )}

        {mode === 'idle' && (
          <View style={styles.idleRoot}>
            <View style={styles.idleTopBar}>
              <View style={styles.idleBrandRow}>
                <Image source={require('./assets/fplogo.png')} style={styles.idleBrandLogo} />
                <View>
                  <Text style={styles.idleBrandName}>FacePe Verify</Text>
                  <Text style={styles.idleBrandMeta}>
                    {auth?.terminal_code} · {auth?.name}
                  </Text>
                </View>
              </View>
              <View style={[styles.chip, auth?.assigned_cashier_id ? styles.chipAssigned : styles.chipWaiting]}>
                <View style={[styles.chipDot, auth?.assigned_cashier_id ? styles.chipDotActive : styles.chipDotIdle]} />
                <Text style={[styles.chipText, auth?.assigned_cashier_id ? styles.chipTextActive : styles.chipTextIdle]}>
                  {auth?.assigned_cashier_id ? 'Assigned' : 'Waiting'}
                </Text>
              </View>
            </View>

            <View style={styles.idleCenter}>
              <View style={styles.pulseWrap}>
                <View style={styles.pulseOuter} />
                <View style={styles.pulseMid} />
                <View style={styles.pulseCoreShadow}>
                  <View style={styles.pulseCore}>
                    <Image source={require('./assets/fplogo.png')} style={styles.pulseLogo} />
                  </View>
                </View>
              </View>
              <Text style={styles.idleStatusLabel}>Terminal Ready</Text>
              <Text style={styles.idleStatusText}>Ready for customer</Text>
              <Text style={styles.idleHint}>
                {auth?.assigned_cashier_id
                  ? 'Waiting for next verification task from cashier.'
                  : 'This terminal is not yet assigned to a cashier.'}
              </Text>
            </View>

            <View style={styles.idleFooter}>
              <Text style={styles.idleFooterText}>Powered by FacePe · Biometric Identity Verification</Text>
            </View>
          </View>
        )}

      {mode === 'consent' && task && (
        <View style={styles.panel}>
          <Text style={styles.kicker}>Biometric consent</Text>
          <Text style={styles.title}>{task.account?.full_name || 'Customer'}</Text>
          <Text style={styles.body}>
            I consent to FacePe collecting and storing my facial biometric data for identity verification.
          </Text>
          <Action label="I agree and continue" onPress={() => acceptConsent(true)} disabled={busy} />
          <Action label="Decline" secondary onPress={() => acceptConsent(false)} disabled={busy} />
        </View>
      )}

      {mode === 'otp' && (
        <KeyboardAvoidingView
          style={styles.flex1}
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        >
          <ScrollView contentContainerStyle={styles.panel} keyboardShouldPersistTaps="handled">
            <Text style={styles.kicker}>OTP verification</Text>
            <Text style={styles.title}>Enter the code sent to your phone</Text>
            <TextInput style={styles.otp} placeholder="000000" value={otpCode} onChangeText={setOtpCode} keyboardType="number-pad" maxLength={6} />
            <Action label="Verify code" onPress={submitOtp} disabled={busy || otpCode.length < 4} />
          </ScrollView>
        </KeyboardAvoidingView>
      )}

      {mode === 'capture' && (
        <View style={styles.cameraScreen}>
          {permission?.granted ? (
            <CameraView ref={cameraRef} style={styles.camera} facing="front" />
          ) : (
            <View style={styles.cameraFallback}>
              <Text style={styles.body}>Camera permission is required.</Text>
            </View>
          )}
          <View style={styles.captureBar}>
            <Text style={styles.captureTitle}>{task?.task_type === 'face_registration' ? 'Face registration' : 'Face verification'}</Text>
            <Action label={permission?.granted ? 'Capture face' : 'Allow camera'} onPress={captureAndSubmit} disabled={busy} />
          </View>
        </View>
      )}

      {mode === 'ambiguous' && task?.result && (
        <View style={styles.panel}>
          <Text style={styles.kicker}>Additional verification</Text>
          <Text style={styles.title}>Select your account</Text>
          <Text style={styles.body}>Multiple enrolled accounts look similar. Confirm your account and verify OTP.</Text>
          {(task.result.candidates || []).map((candidate: any) => (
            <Pressable
              key={candidate.account_id}
              onPress={() => {
                setSelectedCandidateId(candidate.account_id);
                setOtpReference('');
                setOtpCode('');
              }}
              style={[styles.candidate, selectedCandidateId === candidate.account_id && styles.selectedCandidate]}
            >
              <Text style={styles.candidateName}>{candidate.name}</Text>
              <Text style={styles.candidateMeta}>Account {candidate.account_number}</Text>
            </Pressable>
          ))}
          {!otpReference ? (
            <Action label="Send OTP" onPress={sendCandidateOtp} disabled={busy || !selectedCandidateId} />
          ) : (
            <>
              <Text style={styles.body}>OTP sent to {String(task.result.masked_phone || 'your phone')}</Text>
              <TextInput style={styles.otp} placeholder="000000" value={otpCode} onChangeText={setOtpCode} keyboardType="number-pad" maxLength={6} />
              <Action label="Verify OTP" onPress={confirmCandidateOtp} disabled={busy || otpCode.length < 4} />
            </>
          )}
        </View>
      )}

      {mode === 'done' && (
        <View style={styles.panel}>
          <Text style={styles.kicker}>Complete</Text>
          <Text style={styles.title}>{message || 'Task complete'}</Text>
          <Action label="Ready for next customer" onPress={finish} disabled={busy} />
        </View>
      )}

        {!!error && <Text style={styles.error}>{error}</Text>}
      </SafeAreaView>
    </View>
  );
}

function Action({
  label,
  onPress,
  disabled,
  secondary,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
}) {
  return (
    <Pressable onPress={onPress} disabled={disabled} style={[styles.button, secondary && styles.secondaryButton, disabled && styles.disabled]}>
      <Text style={[styles.buttonText, secondary && styles.secondaryButtonText]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#f8fafc',
  },
  safeRoot: {
    flex: 1,
  },
  flex1: {
    flex: 1,
  },
  orbIndigo: {
    position: 'absolute',
    top: -120,
    right: -100,
    width: 320,
    height: 320,
    borderRadius: 160,
    backgroundColor: 'rgba(99, 102, 241, 0.18)',
  },
  orbPink: {
    position: 'absolute',
    bottom: -140,
    left: -120,
    width: 340,
    height: 340,
    borderRadius: 170,
    backgroundColor: 'rgba(236, 72, 153, 0.14)',
  },
  loginScroll: {
    flexGrow: 1,
    paddingHorizontal: 24,
    paddingTop: 32,
    paddingBottom: 24,
  },
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    marginBottom: 36,
  },
  brandLogoHalo: {
    width: 44,
    height: 44,
    borderRadius: 14,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#4f46e5',
    shadowOpacity: 0.2,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 4 },
    elevation: 4,
  },
  brandLogoImg: {
    width: 28,
    height: 28,
    resizeMode: 'contain',
  },
  brandRowName: {
    color: '#0f172a',
    fontSize: 18,
    fontWeight: '700',
    letterSpacing: -0.3,
  },
  displayTitle: {
    color: '#0f172a',
    fontSize: 34,
    fontWeight: '800',
    letterSpacing: -1.1,
    textAlign: 'center',
    marginBottom: 28,
  },
  pillSegmented: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255, 255, 255, 0.7)',
    borderRadius: 14,
    padding: 5,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: 'rgba(99, 102, 241, 0.18)',
    gap: 4,
  },
  pillSegmentWrap: {
    flex: 1,
    borderRadius: 10,
    overflow: 'hidden',
  },
  pillSegmentActive: {
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#4f46e5',
    shadowOpacity: 0.35,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 4 },
    elevation: 4,
  },
  pillSegmentInactive: {
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pillSegmentText: {
    color: '#6366f1',
    fontSize: 14,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  pillSegmentTextActive: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  glassCard: {
    backgroundColor: '#ffffff',
    borderRadius: 20,
    padding: 20,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#e2e8f0',
    shadowColor: '#0f172a',
    shadowOpacity: 0.04,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 8 },
    elevation: 2,
  },
  inputWrap: {
    gap: 6,
  },
  inputSpacer: {
    height: 20,
  },
  inputLabel: {
    color: '#475569',
    fontSize: 13,
    fontWeight: '600',
    marginLeft: 2,
  },
  premiumInput: {
    backgroundColor: '#f8fafc',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    fontSize: 16,
    color: '#0f172a',
    fontWeight: '500',
  },
  passwordRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  passwordField: {
    flex: 1,
  },
  revealBtn: {
    position: 'absolute',
    right: 12,
    padding: 8,
  },
  revealText: {
    color: '#4f46e5',
    fontSize: 13,
    fontWeight: '600',
  },
  ctaButton: {
    borderRadius: 16,
    overflow: 'hidden',
    marginBottom: 18,
    shadowColor: '#4f46e5',
    shadowOpacity: 0.3,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 8 },
    elevation: 8,
  },
  ctaGradient: {
    minHeight: 56,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  ctaDisabled: {
    opacity: 0.5,
    shadowOpacity: 0,
  },
  ctaText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
    letterSpacing: -0.2,
  },
  securityNote: {
    textAlign: 'center',
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '500',
  },
  idleRoot: {
    flex: 1,
    paddingHorizontal: 24,
    paddingTop: 16,
    paddingBottom: 24,
  },
  idleTopBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  idleBrandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  idleBrandLogo: {
    width: 36,
    height: 36,
    resizeMode: 'contain',
  },
  idleBrandName: {
    color: '#0f172a',
    fontSize: 15,
    fontWeight: '700',
    letterSpacing: -0.2,
  },
  idleBrandMeta: {
    color: '#64748b',
    fontSize: 12,
    fontWeight: '500',
    marginTop: 1,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 999,
    borderWidth: 1,
  },
  chipAssigned: {
    backgroundColor: 'rgba(16, 185, 129, 0.12)',
    borderColor: 'rgba(16, 185, 129, 0.3)',
  },
  chipWaiting: {
    backgroundColor: 'rgba(245, 158, 11, 0.12)',
    borderColor: 'rgba(245, 158, 11, 0.3)',
  },
  chipDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  chipDotActive: {
    backgroundColor: '#10b981',
  },
  chipDotIdle: {
    backgroundColor: '#f59e0b',
  },
  chipText: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  chipTextActive: {
    color: '#047857',
  },
  chipTextIdle: {
    color: '#b45309',
  },
  idleCenter: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pulseWrap: {
    width: 200,
    height: 200,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 32,
  },
  pulseOuter: {
    position: 'absolute',
    width: 200,
    height: 200,
    borderRadius: 100,
    backgroundColor: 'rgba(99, 102, 241, 0.08)',
  },
  pulseMid: {
    position: 'absolute',
    width: 150,
    height: 150,
    borderRadius: 75,
    backgroundColor: 'rgba(99, 102, 241, 0.14)',
  },
  pulseCoreShadow: {
    shadowColor: '#4f46e5',
    shadowOpacity: 0.45,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 12 },
    elevation: 12,
    borderRadius: 60,
  },
  pulseCore: {
    width: 108,
    height: 108,
    borderRadius: 54,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#ffffff',
    borderWidth: 4,
    borderColor: 'rgba(99, 102, 241, 0.25)',
  },
  pulseLogo: {
    width: 64,
    height: 64,
    resizeMode: 'contain',
  },
  idleStatusLabel: {
    color: '#6366f1',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
    marginBottom: 6,
  },
  idleStatusText: {
    color: '#0f172a',
    fontSize: 32,
    fontWeight: '800',
    letterSpacing: -0.8,
    marginBottom: 10,
  },
  idleHint: {
    color: '#64748b',
    fontSize: 14,
    textAlign: 'center',
    paddingHorizontal: 24,
    lineHeight: 20,
  },
  idleFooter: {
    alignItems: 'center',
    paddingVertical: 8,
  },
  idleFooterText: {
    color: '#94a3b8',
    fontSize: 11,
    fontWeight: '500',
    letterSpacing: 0.3,
  },
  panel: {
    flex: 1,
    justifyContent: 'center',
    padding: 24,
    gap: 14,
  },
  kicker: {
    color: '#4f46e5',
    fontSize: 13,
    fontWeight: '700',
    textTransform: 'uppercase',
  },
  title: {
    color: '#0f172a',
    fontSize: 30,
    fontWeight: '800',
    lineHeight: 36,
  },
  body: {
    color: '#475569',
    fontSize: 17,
    lineHeight: 25,
  },
  status: {
    marginTop: 20,
    borderRadius: 14,
    backgroundColor: '#ecfdf5',
    color: '#047857',
    padding: 16,
    fontSize: 18,
    fontWeight: '700',
    textAlign: 'center',
  },
  input: {
    borderWidth: 1,
    borderColor: '#e2e8f0',
    borderRadius: 12,
    backgroundColor: '#f8fafc',
    paddingHorizontal: 14,
    paddingVertical: 14,
    fontSize: 16,
    color: '#0f172a',
  },
  otp: {
    borderWidth: 1,
    borderColor: '#cbd5e1',
    borderRadius: 16,
    backgroundColor: '#ffffff',
    paddingHorizontal: 18,
    paddingVertical: 16,
    fontSize: 30,
    fontWeight: '800',
    letterSpacing: 4,
    textAlign: 'center',
  },
  button: {
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    backgroundColor: '#4f46e5',
    paddingHorizontal: 18,
  },
  secondaryButton: {
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#cbd5e1',
  },
  disabled: {
    opacity: 0.55,
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '800',
  },
  secondaryButtonText: {
    color: '#334155',
  },
  error: {
    position: 'absolute',
    left: 16,
    right: 16,
    bottom: 24,
    borderRadius: 12,
    backgroundColor: '#fee2e2',
    color: '#991b1b',
    padding: 14,
    fontSize: 14,
    fontWeight: '700',
  },
  cameraScreen: {
    flex: 1,
    backgroundColor: '#000000',
  },
  camera: {
    flex: 1,
  },
  cameraFallback: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#e2e8f0',
    padding: 24,
  },
  captureBar: {
    gap: 12,
    padding: 18,
    backgroundColor: '#ffffff',
  },
  captureTitle: {
    color: '#0f172a',
    fontSize: 18,
    fontWeight: '800',
    textAlign: 'center',
  },
  candidate: {
    borderWidth: 1,
    borderColor: '#cbd5e1',
    borderRadius: 14,
    backgroundColor: '#ffffff',
    padding: 14,
  },
  selectedCandidate: {
    borderColor: '#4f46e5',
    backgroundColor: '#eef2ff',
  },
  candidateName: {
    color: '#0f172a',
    fontSize: 17,
    fontWeight: '800',
  },
  candidateMeta: {
    marginTop: 4,
    color: '#64748b',
    fontSize: 14,
    fontWeight: '600',
  },
});
