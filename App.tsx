/**
 * Spam Check Seed – one screen: setup checklist, connection, phone status and the seed calls log.
 * All detection / reporting runs natively (see android/app/src/main/java/com/spamcheckapp/seed).
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import {
  isAvailable,
  requestAllPermissions,
  SeedEvent,
  SeedStatus,
  SpamCheck,
} from './src/SpamCheck';

const C = {
  bg: '#f4f5f7',
  card: '#ffffff',
  border: '#e5e7eb',
  text: '#111827',
  muted: '#6b7280',
  brand: '#006239',
  good: '#15803d',
  goodBg: '#dcfce7',
  bad: '#b91c1c',
  badBg: '#fee2e2',
  warn: '#b45309',
  warnBg: '#fef3c7',
};

type Heartbeat = { at?: string; ok?: boolean; error?: string; label?: string };

function parseHeartbeat(raw?: string): Heartbeat | null {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as Heartbeat;
  } catch {
    return null;
  }
}

function timeText(value?: string | number): string {
  if (value === undefined || value === '') {
    return '—';
  }
  const d = new Date(value);
  return isNaN(d.getTime())
    ? String(value)
    : d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function Btn({
  title,
  onPress,
  kind = 'primary',
  disabled,
}: {
  title: string;
  onPress: () => void;
  kind?: 'primary' | 'ghost';
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.btn,
        kind === 'primary' ? styles.btnPrimary : styles.btnGhost,
        (pressed || disabled) && { opacity: 0.6 },
      ]}>
      <Text style={kind === 'primary' ? styles.btnPrimaryText : styles.btnGhostText}>{title}</Text>
    </Pressable>
  );
}

function Pill({ text, tone }: { text: string; tone: 'good' | 'bad' | 'warn' | 'muted' }) {
  const map = {
    good: [C.goodBg, C.good],
    bad: [C.badBg, C.bad],
    warn: [C.warnBg, C.warn],
    muted: ['#f3f4f6', C.muted],
  } as const;
  const [bg, fg] = map[tone];
  return (
    <View style={[styles.pill, { backgroundColor: bg }]}>
      <Text style={[styles.pillText, { color: fg }]}>{text}</Text>
    </View>
  );
}

function Step({
  n,
  title,
  done,
  detail,
  action,
}: {
  n: number;
  title: string;
  done: boolean;
  detail?: string;
  action?: React.ReactNode;
}) {
  return (
    <View style={styles.step}>
      <View style={[styles.stepDot, { backgroundColor: done ? C.good : '#d1d5db' }]}>
        <Text style={styles.stepDotText}>{done ? '✓' : n}</Text>
      </View>
      <View style={styles.stepBody}>
        <Text style={styles.stepTitle}>{title}</Text>
        {detail ? <Text style={styles.muted}>{detail}</Text> : null}
      </View>
      {!done && action ? <View>{action}</View> : null}
    </View>
  );
}

function KV({ k, v, ok }: { k: string; v: string; ok?: boolean }) {
  return (
    <View style={styles.kv}>
      <Text style={styles.kvKey}>{k}</Text>
      <Text style={[styles.kvVal, ok === true && { color: C.good }, ok === false && { color: C.bad }]}>
        {v}
      </Text>
    </View>
  );
}

function CallCard({ e }: { e: SeedEvent }) {
  const [open, setOpen] = useState(false);
  const p = e.payload;
  return (
    <Pressable onPress={() => setOpen(o => !o)} style={styles.callCard}>
      <View style={styles.callTop}>
        <Pill text={p.label_seen ? 'SPAM LABEL' : 'Clean'} tone={p.label_seen ? 'bad' : 'good'} />
        <Text style={styles.muted}>{timeText(p.received_at)}</Text>
      </View>
      <Text style={styles.callNumber}>{p.caller_number || 'Unknown number'}</Text>
      {p.matched_keywords.length ? (
        <Text style={{ color: C.bad }}>Found: {p.matched_keywords.join(', ')}</Text>
      ) : null}
      <Text style={styles.muted}>
        Rang {p.ring_seconds}s · {p.rejected ? 'cut by app' : 'not cut'}
        {e.local?.reject_error ? ` (${e.local.reject_error})` : ''} ·{' '}
        <Text style={{ color: e.sent ? C.good : C.bad }}>
          {e.sent ? 'sent' : `not sent${e.last_error ? `: ${e.last_error}` : ''}`}
        </Text>
      </Text>
      {open && p.screen_texts.length ? (
        <View style={styles.screenBox}>
          <Text style={styles.screenTitle}>What the screen showed</Text>
          {p.screen_texts.map((t, i) => (
            <Text key={i} style={styles.screenText}>
              • {t}
            </Text>
          ))}
        </View>
      ) : p.screen_texts.length ? (
        <Text style={styles.link}>Tap to see the screen text</Text>
      ) : null}
    </Pressable>
  );
}

function App(): React.JSX.Element {
  const [baseUrl, setBaseUrl] = useState('');
  const [token, setToken] = useState('');
  const [editServer, setEditServer] = useState(false);
  const [message, setMessage] = useState<{ text: string; tone: 'good' | 'bad' | 'muted' } | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<SeedStatus | null>(null);
  const [events, setEvents] = useState<SeedEvent[]>([]);

  const refresh = useCallback(async () => {
    if (!isAvailable) {
      return;
    }
    try {
      setStatus(await SpamCheck.getStatus());
      setEvents(await SpamCheck.getEvents());
    } catch (e) {
      setMessage({ text: String(e), tone: 'bad' });
    }
  }, []);

  // On open: load config, start the monitor, send a heartbeat, refresh status.
  useEffect(() => {
    if (!isAvailable) {
      setMessage({ text: 'Native module missing – install the Android build.', tone: 'bad' });
      return;
    }
    (async () => {
      const cfg = await SpamCheck.getConfig();
      setBaseUrl(cfg.baseUrl);
      setToken(cfg.token);
      await SpamCheck.startMonitor().catch(() => false);
      if (cfg.baseUrl && cfg.token) {
        SpamCheck.sendHeartbeat().then(refresh).catch(() => undefined);
      }
      await refresh();
    })();
    const sub = AppState.addEventListener('change', s => {
      if (s === 'active') {
        refresh();
      }
    });
    const timer = setInterval(refresh, 5000);
    return () => {
      sub.remove();
      clearInterval(timer);
    };
  }, [refresh]);

  const saveAndTest = async () => {
    setBusy(true);
    setMessage({ text: 'Connecting…', tone: 'muted' });
    try {
      await SpamCheck.saveConfig(baseUrl.trim(), token.trim());
      const r = await SpamCheck.sendHeartbeat();
      if (r.ok) {
        let label = '';
        try {
          label = JSON.parse(r.body)?.label ?? '';
        } catch {}
        setMessage({ text: `Connected${label ? ` as "${label}"` : ''}.`, tone: 'good' });
        setEditServer(false);
      } else if (r.httpStatus === 401) {
        setMessage({ text: 'Token not accepted. Copy it again from the admin panel.', tone: 'bad' });
      } else if (r.httpStatus === 404) {
        setMessage({ text: 'Server does not have Spam Check yet (404). Deploy the backend first.', tone: 'bad' });
      } else {
        setMessage({
          text: `Could not connect (${r.httpStatus || 'no response'}). ${r.error ?? ''}`.trim(),
          tone: 'bad',
        });
      }
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const grantPermissions = async () => {
    await requestAllPermissions(status?.sdkInt ?? 0);
    await SpamCheck.startMonitor().catch(() => false);
    refresh();
  };

  const hb = parseHeartbeat(status?.lastHeartbeat);
  const permissionsOk = !!status && status.readPhoneState && status.readCallLog && status.answerPhoneCalls
    && (status.sdkInt < 33 || status.postNotifications);
  const accessibilityOk = !!status
    && (status.accessibilityOnDemand || (status.accessibilityEnabled && status.accessibilityConnected));
  const batteryOk = !!status && status.batteryOptimizationIgnored;
  const connectedOk = !!status && status.configured && !!hb?.ok;
  const ready = permissionsOk && accessibilityOk && connectedOk;
  const spamCount = events.filter(e => e.payload.label_seen).length;

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.safe} edges={['top']}>
        <View style={styles.header}>
          <View>
            <Text style={styles.headerTitle}>Spam Check Seed</Text>
            <Text style={styles.headerSub}>
              {status?.simOperator ? `SIM: ${status.simOperator}` : 'Seed phone'}
              {hb?.label ? ` · ${hb.label}` : ''}
            </Text>
          </View>
          {status ? <Pill text={ready ? 'Ready' : 'Setup needed'} tone={ready ? 'good' : 'warn'} /> : null}
        </View>

        <ScrollView contentContainerStyle={styles.container}>
          {message ? (
            <View
              style={[
                styles.banner,
                message.tone === 'good' && { backgroundColor: C.goodBg },
                message.tone === 'bad' && { backgroundColor: C.badBg },
              ]}>
              <Text
                style={{ color: message.tone === 'good' ? C.good : message.tone === 'bad' ? C.bad : C.text }}>
                {message.text}
              </Text>
            </View>
          ) : null}

          {/* ---------------- Setup ---------------- */}
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Setup</Text>
            {!status ? (
              <ActivityIndicator color={C.brand} />
            ) : (
              <>
                <Step
                  n={1}
                  title="Phone permissions"
                  done={permissionsOk}
                  detail="Phone state, call log, end calls, notifications"
                  action={<Btn title="Grant" onPress={grantPermissions} />}
                />
                <Step
                  n={2}
                  title="Read the call screen"
                  done={accessibilityOk}
                  detail={
                    status.accessibilityOnDemand
                      ? 'Automatic: turns on only while our test call rings, off the rest of the time'
                      : status.accessibilityEnabled && !status.accessibilityConnected
                        ? 'Turned on but not connected yet – turn it off and on again'
                        : 'Accessibility → Spam Check → On (or the one-time adb command in the README to make it automatic)'
                  }
                  action={
                    status.accessibilityOnDemand
                      ? undefined
                      : <Btn title="Open" onPress={() => SpamCheck.openAccessibilitySettings()} />
                  }
                />
                <Step
                  n={3}
                  title="Keep running in background"
                  done={batteryOk}
                  detail="Battery optimisation off (and Autostart on Xiaomi / Vivo / Oppo)"
                  action={<Btn title="Open" onPress={() => SpamCheck.openBatteryOptimizationSettings()} />}
                />
                <Step
                  n={4}
                  title="Connected to server"
                  done={connectedOk}
                  detail={
                    hb?.at
                      ? `Last check ${timeText(hb.at)} – ${hb.ok ? 'ok' : `failed ${hb.error ?? ''}`}`
                      : 'Enter the seed token below'
                  }
                />
              </>
            )}
          </View>

          {/* ---------------- Connection ---------------- */}
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Seed token</Text>
            <Text style={styles.muted}>
              From the admin panel → Spam Check → seed phone. Enter it once; it never expires.
            </Text>
            <TextInput
              style={styles.input}
              value={token}
              onChangeText={setToken}
              placeholder="Paste the seed token"
              placeholderTextColor="#9ca3af"
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
            />
            {editServer ? (
              <>
                <Text style={[styles.muted, { marginTop: 8 }]}>Server</Text>
                <TextInput
                  style={styles.input}
                  value={baseUrl}
                  onChangeText={setBaseUrl}
                  placeholder="https://dialerserver.qualiabits.com"
                  placeholderTextColor="#9ca3af"
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="url"
                />
              </>
            ) : (
              <Pressable onPress={() => setEditServer(true)}>
                <Text style={styles.link}>Server: {baseUrl || '—'} (change)</Text>
              </Pressable>
            )}
            <View style={{ marginTop: 12 }}>
              <Btn title={busy ? 'Connecting…' : 'Save & test connection'} onPress={saveAndTest} disabled={busy} />
            </View>
          </View>

          {/* ---------------- Seed calls ---------------- */}
          <View style={styles.card}>
            <View style={styles.rowBetween}>
              <Text style={styles.cardTitle}>Seed calls</Text>
              {events.length ? (
                <Text style={styles.muted}>
                  {events.length} calls · <Text style={{ color: C.bad }}>{spamCount} with spam label</Text>
                </Text>
              ) : null}
            </View>
            <Text style={styles.muted}>
              Only our own test calls appear here. Any other call rings normally and is not reported.
            </Text>
            {events.length === 0 ? (
              <Text style={[styles.muted, { marginTop: 12 }]}>No seed calls yet.</Text>
            ) : (
              events.map(e => <CallCard key={e.id} e={e} />)
            )}
            {events.length ? (
              <View style={{ marginTop: 8 }}>
                <Btn title="Clear log" kind="ghost" onPress={() => SpamCheck.clearEvents().then(refresh)} />
              </View>
            ) : null}
          </View>

          {/* ---------------- Phone ---------------- */}
          {status ? (
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Phone</Text>
              <KV k="SIM operator" v={status.simOperator || '(none)'} />
              <KV k="Android" v={`${status.androidVersion} (API ${status.sdkInt})`} />
              <KV k="Call listener" v={status.callListenerRegistered ? 'running' : 'stopped'} ok={status.callListenerRegistered} />
              <KV
                k="Background service"
                v={status.foregroundServiceRunning ? 'running' : 'stopped'}
                ok={status.foregroundServiceRunning}
              />
              <KV k="Cuts our call after" v={`${status.rejectAfterSeconds} s`} />
              <KV k="Spam words" v={status.keywords.join(', ')} />
              <View style={styles.rowGap}>
                <Btn title="Restart service" kind="ghost" onPress={() => SpamCheck.startMonitor().then(refresh)} />
                <Btn title="App settings" kind="ghost" onPress={() => SpamCheck.openAppSettings()} />
              </View>
            </View>
          ) : null}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: C.brand },
  header: {
    backgroundColor: C.brand,
    paddingHorizontal: 16,
    paddingVertical: 14,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  headerTitle: { color: '#fff', fontSize: 20, fontWeight: '700' },
  headerSub: { color: '#cfe3d9', fontSize: 12, marginTop: 2 },
  container: { padding: 12, paddingBottom: 40, backgroundColor: C.bg, flexGrow: 1 },
  banner: { backgroundColor: '#e5e7eb', borderRadius: 10, padding: 12, marginBottom: 12 },
  card: {
    backgroundColor: C.card,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: C.border,
    padding: 14,
    marginBottom: 12,
  },
  cardTitle: { fontSize: 16, fontWeight: '700', color: C.text, marginBottom: 6 },
  muted: { color: C.muted, fontSize: 13 },
  link: { color: C.brand, fontSize: 13, marginTop: 8, fontWeight: '600' },
  input: {
    borderWidth: 1,
    borderColor: C.border,
    backgroundColor: '#f9fafb',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginTop: 8,
    color: C.text,
    fontSize: 15,
  },
  btn: { borderRadius: 10, paddingVertical: 10, paddingHorizontal: 14, alignItems: 'center' },
  btnPrimary: { backgroundColor: C.brand },
  btnPrimaryText: { color: '#fff', fontWeight: '700' },
  btnGhost: { backgroundColor: '#f3f4f6', borderWidth: 1, borderColor: C.border },
  btnGhostText: { color: C.text, fontWeight: '600' },
  pill: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  pillText: { fontSize: 12, fontWeight: '700' },
  step: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: C.border,
    gap: 10,
  },
  stepDot: { width: 26, height: 26, borderRadius: 13, alignItems: 'center', justifyContent: 'center' },
  stepDotText: { color: '#fff', fontWeight: '700' },
  stepBody: { flex: 1 },
  stepTitle: { color: C.text, fontWeight: '600', fontSize: 15 },
  kv: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: C.border,
    gap: 12,
  },
  kvKey: { color: C.muted },
  kvVal: { color: C.text, flexShrink: 1, textAlign: 'right' },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  rowGap: { flexDirection: 'row', gap: 8, marginTop: 12 },
  callCard: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 12,
    padding: 12,
    marginTop: 10,
    gap: 4,
  },
  callTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  callNumber: { color: C.text, fontSize: 16, fontWeight: '700', marginTop: 4 },
  screenBox: { backgroundColor: '#f9fafb', borderRadius: 8, padding: 8, marginTop: 6 },
  screenTitle: { color: C.muted, fontSize: 12, fontWeight: '700', marginBottom: 4 },
  screenText: { color: C.text, fontSize: 13 },
});

export default App;
