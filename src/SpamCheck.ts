import { NativeModules, PermissionsAndroid, Platform, Permission } from 'react-native';

export type SeedConfig = { baseUrl: string; token: string };

export type SeedStatus = {
  simOperator: string;
  sdkInt: number;
  androidVersion: string;
  readPhoneState: boolean;
  readCallLog: boolean;
  answerPhoneCalls: boolean;
  canEndCall: boolean;
  postNotifications: boolean;
  accessibilityEnabled: boolean;
  accessibilityConnected: boolean;
  foregroundServiceRunning: boolean;
  callListenerRegistered: boolean;
  batteryOptimizationIgnored: boolean;
  configured: boolean;
  lastHeartbeat: string; // JSON string or ''
  rejectAfterSeconds: number;
  keywords: string[];
};

export type HeartbeatResult = {
  ok: boolean;
  httpStatus: number;
  body: string;
  error: string | null;
};

export type ReportPayload = {
  caller_number: string;
  received_at: string;
  ring_seconds: number;
  label_seen: boolean;
  matched_keywords: string[];
  screen_texts: string[];
  rejected: boolean;
  app_operator: string;
};

export type SeedEvent = {
  id: string;
  created_ms?: number;
  payload: ReportPayload;
  sent: boolean;
  attempts: number;
  last_error: string | null;
  http_status?: number;
  matched_result_id?: string | number;
  local?: { reject_error?: string | null };
};

type NativeSpamCheck = {
  getConfig(): Promise<SeedConfig>;
  saveConfig(baseUrl: string, token: string): Promise<boolean>;
  sendHeartbeat(): Promise<HeartbeatResult>;
  getStatus(): Promise<SeedStatus>;
  getEvents(): Promise<string>;
  clearEvents(): Promise<boolean>;
  startMonitor(): Promise<boolean>;
  stopMonitor(): Promise<boolean>;
  openAccessibilitySettings(): void;
  openBatteryOptimizationSettings(): void;
  openAppSettings(): void;
};

const Native: NativeSpamCheck | undefined = NativeModules.SpamCheck;

export const isAvailable = Platform.OS === 'android' && !!Native;

function native(): NativeSpamCheck {
  if (!Native) {
    throw new Error('SpamCheck native module not found (Android build only).');
  }
  return Native;
}

export const SpamCheck = {
  getConfig: () => native().getConfig(),
  saveConfig: (baseUrl: string, token: string) => native().saveConfig(baseUrl, token),
  sendHeartbeat: () => native().sendHeartbeat(),
  getStatus: () => native().getStatus(),
  getEvents: async (): Promise<SeedEvent[]> => {
    try {
      return JSON.parse(await native().getEvents()) as SeedEvent[];
    } catch {
      return [];
    }
  },
  clearEvents: () => native().clearEvents(),
  startMonitor: () => native().startMonitor(),
  stopMonitor: () => native().stopMonitor(),
  openAccessibilitySettings: () => native().openAccessibilitySettings(),
  openBatteryOptimizationSettings: () => native().openBatteryOptimizationSettings(),
  openAppSettings: () => native().openAppSettings(),
};

/** Asks for every runtime permission the background detection needs. */
export async function requestAllPermissions(sdkInt: number): Promise<void> {
  const P = PermissionsAndroid.PERMISSIONS;
  const wanted: Permission[] = [P.READ_PHONE_STATE, P.READ_CALL_LOG];
  if (sdkInt >= 26) {
    wanted.push(P.ANSWER_PHONE_CALLS);
  }
  if (sdkInt >= 33) {
    wanted.push(P.POST_NOTIFICATIONS);
  }
  await PermissionsAndroid.requestMultiple(wanted);
}
