/**
 * Spam Check Seed - plain one-screen UI: Settings, Status, Log.
 * All detection / reporting runs natively (see android/app/src/main/java/com/spamcheckapp/seed).
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  AppState,
  Button,
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

function Row({ label, ok, text }: { label: string; ok?: boolean; text?: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={[styles.rowValue, ok === false ? styles.bad : ok ? styles.good : null]}>
        {text ?? (ok ? 'YES' : 'NO')}
      </Text>
    </View>
  );
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function App(): React.JSX.Element {
  const [baseUrl, setBaseUrl] = useState('');
  const [token, setToken] = useState('');
  const [message, setMessage] = useState('');
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
      setMessage(String(e));
    }
  }, []);

  // On open: load config, start the monitor, send a heartbeat, refresh status.
  useEffect(() => {
    if (!isAvailable) {
      setMessage('Native module missing - run the Android build.');
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

  const save = async () => {
    await SpamCheck.saveConfig(baseUrl, token);
    setMessage('Saved.');
    refresh();
  };

  const testHeartbeat = async () => {
    setMessage('Sending heartbeat...');
    await SpamCheck.saveConfig(baseUrl, token);
    const r = await SpamCheck.sendHeartbeat();
    setMessage(
      r.ok
        ? `Heartbeat OK: ${r.body}`
        : `Heartbeat FAILED (${r.httpStatus || 'no response'}): ${r.error ?? ''} ${r.body}`,
    );
    refresh();
  };

  const grantPermissions = async () => {
    await requestAllPermissions(status?.sdkInt ?? 0);
    await SpamCheck.startMonitor().catch(() => false);
    refresh();
  };

  let lastHb = 'never';
  if (status?.lastHeartbeat) {
    try {
      const hb = JSON.parse(status.lastHeartbeat);
      lastHb = `${new Date(hb.at).toLocaleString()} - ${hb.ok ? 'ok' : `failed ${hb.error ?? ''}`}${
        hb.label ? ` (${hb.label})` : ''
      }`;
    } catch {
      lastHb = status.lastHeartbeat;
    }
  }

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.safe}>
        <ScrollView contentContainerStyle={styles.container}>
          <Text style={styles.title}>Spam Check Seed</Text>
          {message ? <Text style={styles.message}>{message}</Text> : null}

          {/* ---------------- Settings ---------------- */}
          <Text style={styles.h2}>Settings</Text>
          <Text>Server base URL</Text>
          <TextInput
            style={styles.input}
            value={baseUrl}
            onChangeText={setBaseUrl}
            placeholder="https://qualia-voice.example.com"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
          />
          <Text>Seed token</Text>
          <TextInput
            style={styles.input}
            value={token}
            onChangeText={setToken}
            placeholder="X-Seed-Token from admin"
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry
          />
          <View style={styles.buttons}>
            <Button title="Save" onPress={save} />
            <Button title="Send test heartbeat" onPress={testHeartbeat} />
          </View>

          {/* ---------------- Status ---------------- */}
          <Text style={styles.h2}>Status</Text>
          {status ? (
            <>
              <Row label="SIM operator" text={status.simOperator || '(none)'} />
              <Row label="Android" text={`${status.androidVersion} (API ${status.sdkInt})`} />
              <Row label="Server configured" ok={status.configured} />
              <Row label="Phone state permission" ok={status.readPhoneState} />
              <Row label="Call log permission" ok={status.readCallLog} />
              <Row label="Answer/end calls permission" ok={status.answerPhoneCalls} />
              {!status.canEndCall ? (
                <Row label="Auto-reject" ok={false} text="needs Android 9+" />
              ) : null}
              <Row label="Notifications permission" ok={status.postNotifications} />
              <Row label="Accessibility service enabled" ok={status.accessibilityEnabled} />
              <Row label="Accessibility service connected" ok={status.accessibilityConnected} />
              <Row label="Foreground service running" ok={status.foregroundServiceRunning} />
              <Row label="Call listener registered" ok={status.callListenerRegistered} />
              <Row label="Battery optimisation off" ok={status.batteryOptimizationIgnored} />
              <Row label="Last heartbeat" text={lastHb} />
              <Row label="Reject after" text={`${status.rejectAfterSeconds} s`} />
              <Row label="Keywords" text={status.keywords.join(', ')} />
            </>
          ) : (
            <Text>Loading...</Text>
          )}
          <View style={styles.buttonsCol}>
            <Button title="Grant permissions" onPress={grantPermissions} />
            <Button
              title="Open Accessibility settings"
              onPress={() => SpamCheck.openAccessibilitySettings()}
            />
            <Button
              title="Battery optimisation settings"
              onPress={() => SpamCheck.openBatteryOptimizationSettings()}
            />
            <Button title="App settings" onPress={() => SpamCheck.openAppSettings()} />
            <Button
              title="Start foreground service"
              onPress={() => SpamCheck.startMonitor().then(refresh)}
            />
            <Button title="Refresh" onPress={refresh} />
          </View>

          {/* ---------------- Log ---------------- */}
          <Text style={styles.h2}>Log (last {events.length})</Text>
          {events.length === 0 ? <Text>No calls yet.</Text> : null}
          {events.map(e => (
            <View key={e.id} style={styles.event}>
              <Text style={styles.bold}>
                {formatTime(e.payload.received_at)} - {e.payload.caller_number || '(unknown)'}
              </Text>
              <Text style={e.payload.label_seen ? styles.bad : styles.good}>
                Label seen: {e.payload.label_seen ? 'YES' : 'no'}
                {e.payload.matched_keywords.length
                  ? ` [${e.payload.matched_keywords.join(', ')}]`
                  : ''}
              </Text>
              <Text>
                Rang {e.payload.ring_seconds}s, rejected: {e.payload.rejected ? 'yes' : 'no'}
                {e.local?.reject_error ? ` (${e.local.reject_error})` : ''}
              </Text>
              <Text style={e.sent ? styles.good : styles.bad}>
                Sent: {e.sent ? 'ok' : 'NO'} (attempts {e.attempts}
                {e.last_error ? `, ${e.last_error}` : ''})
              </Text>
              {e.payload.screen_texts.length ? (
                <Text style={styles.small} numberOfLines={4}>
                  Screen: {e.payload.screen_texts.join(' | ')}
                </Text>
              ) : null}
            </View>
          ))}
          {events.length ? (
            <Button title="Clear log" onPress={() => SpamCheck.clearEvents().then(refresh)} />
          ) : null}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#fff' },
  container: { padding: 16, paddingBottom: 48 },
  title: { fontSize: 22, fontWeight: 'bold', marginBottom: 8, color: '#000' },
  h2: { fontSize: 18, fontWeight: 'bold', marginTop: 20, marginBottom: 8, color: '#000' },
  message: { backgroundColor: '#eef', padding: 8, marginBottom: 8, color: '#000' },
  input: {
    borderWidth: 1,
    borderColor: '#999',
    borderRadius: 4,
    padding: 8,
    marginVertical: 4,
    color: '#000',
  },
  buttons: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 8 },
  buttonsCol: { gap: 8, marginTop: 8 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 4,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#ccc',
  },
  rowLabel: { flex: 1, color: '#000' },
  rowValue: { flex: 1, textAlign: 'right', color: '#000' },
  good: { color: '#1a7f37' },
  bad: { color: '#c62828' },
  event: { borderWidth: 1, borderColor: '#ddd', padding: 8, marginBottom: 8, borderRadius: 4 },
  bold: { fontWeight: 'bold', color: '#000' },
  small: { fontSize: 12, color: '#555' },
});

export default App;
