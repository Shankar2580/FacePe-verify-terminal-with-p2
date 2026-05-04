export interface TerminalAuthResponse {
  access_token: string;
  refresh_token: string;
  terminal_id: string;
  terminal_code: string;
  name: string;
  branch_code: string | null;
  status: string;
  assigned_cashier_id: string | null;
  expires_in: number;
}

export interface Account {
  id: string;
  account_number: string;
  full_name: string;
  mobile_number: string | null;
  face_enrolled: boolean;
}

export interface FaceRegistrationSession {
  id: string;
  account_id: string;
  cashier_id: string;
  terminal_id: string;
  status: string;
  consent_required: boolean;
  otp_required: boolean;
  consent_recorded: boolean;
  otp_verified: boolean;
  face_enrolled: boolean;
}

export interface TerminalTask {
  id: string;
  terminal_id: string;
  cashier_id: string;
  task_type: 'face_registration' | 'face_verification' | string;
  account_id: string | null;
  face_registration_session_id: string | null;
  status: string;
  instructions: string | null;
  result: Record<string, any> | null;
  last_error: string | null;
  account?: Account | null;
  session?: FaceRegistrationSession | null;
}
