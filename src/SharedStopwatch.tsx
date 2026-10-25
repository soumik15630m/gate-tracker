import React, { useEffect, useRef, useState } from 'react';
import { AppState, Platform, View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Pause, Play, Timer } from 'lucide-react-native';
import { Button, C, Dialog, T, s } from './ui';
import { useStudy } from './store';
import { useWorkspace } from './workspace';
import { LogForm } from './forms';
import { elapsedMs, readTimer, timerClock, timerMinutes, type StudyTimer } from './core/stopwatch';
import type { Mutation, Session } from './core/model';

export interface SharedTimerState { version: number; timer: StudyTimer | null; lastSavedId: string | null; serverNow: number }
interface TimerAccount { server: string; token: string; user: { id: string } }
export function SharedStopwatch({ account, storageKey }: { account: TimerAccount; storageKey: string }) {
  const store = useStudy(), w = useWorkspace();
  const [state, setState] = useState<SharedTimerState | null>(null), [connected, setConnected] = useState(false), [busy, setBusy] = useState(false), [now, setNow] = useState(Date.now()), [offset, setOffset] = useState(0);
  const [logging, setLogging] = useState<SharedTimerState | null>(null), [discard, setDiscard] = useState(false);
  const current = useRef<SharedTimerState | null>(null), clockOffset = useRef(0), alive = useRef(true), pending = useRef(0), queue = useRef(Promise.resolve()), writes = useRef(Promise.resolve()), lastSaved = useRef<string | null>(null);
  function apply(next: SharedTimerState, skew: number) {
    if (!alive.current || current.current && next.version < current.current.version) return;
    const previous = current.current;
    current.current = next; clockOffset.current = skew; setOffset(skew); setConnected(true);
    if (!previous || previous.version !== next.version) setState(next);
    if (!previous || previous.version !== next.version) { writes.current = writes.current.catch(() => {}).then(() => AsyncStorage.setItem(storageKey + '.shared', JSON.stringify({ state: next, offset: skew }))); writes.current.catch(() => {}); }
    if (next.lastSavedId && lastSaved.current !== next.lastSavedId) { lastSaved.current = next.lastSavedId; void store.sync(); }
  }
  function request(input: Record<string, unknown>): Promise<SharedTimerState> {
    pending.current++;
    const run = queue.current.catch(() => {}).then(async () => {
      const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 10000), sent = Date.now();
      try {
        const response = await fetch(account.server + '/api/timer', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${account.token}` }, body: JSON.stringify(input), signal: controller.signal });
        const value = await response.json(), reply = response.ok ? value : value.state;
        if (reply && Number.isSafeInteger(reply.version)) apply(reply, reply.serverNow - (sent + Date.now()) / 2);
        if (!response.ok) throw Object.assign(new Error(value.error || 'Timer control failed.'), { reachable: true });
        return value as SharedTimerState;
      } catch (error) { if (alive.current && !(error as any).reachable) setConnected(false); throw error; }
      finally { clearTimeout(timeout); pending.current--; }
    });
    queue.current = run.then(() => {}, () => {}); return run;
  }
  useEffect(() => {
    alive.current = true;
    let stopped = false;
    const visible = () => Platform.OS === 'web' ? !document.hidden : AppState.currentState === 'active';
    let legacyRaw: string | null = null, initialized = false, migrated = false;
    async function refresh() {
      let next = await request({ action: 'read' });
      if (!migrated) {
        const previous = readTimer(legacyRaw);
        if (previous && !store.data.records[previous.id] && next.version === 0) next = await request({ action: 'import', version: 0, device: 'Web', timer: { ...previous, startedAt: previous.startedAt === null ? null : Math.round(previous.startedAt + clockOffset.current) } });
        if (previous && !stopped) { await AsyncStorage.setItem(storageKey + '.previous-local', legacyRaw!); await AsyncStorage.removeItem(storageKey); }
        migrated = true;
      }
    }
    async function initialize() {
      try {
        const [cached, legacy] = await Promise.all([AsyncStorage.getItem(storageKey + '.shared'), AsyncStorage.getItem(storageKey)]);
        if (stopped) return;
        legacyRaw = legacy;
        try { if (cached) { const value = JSON.parse(cached); if (Number.isSafeInteger(value.state?.version) && Number.isFinite(value.offset)) { current.current = value.state; setState(value.state); clockOffset.current = value.offset; setOffset(value.offset); } } } catch {}
        initialized = true; await refresh();
      } catch { initialized = true; /* Foreground polls retry without discarding the previous local timer. */ }
    }
    void initialize();
    const poll = () => { if (!stopped && initialized && visible() && pending.current === 0) void refresh().catch(() => {}); };
    const interval = setInterval(poll, 2000), subscription = AppState.addEventListener('change', value => { if (value === 'active') poll(); });
    if (Platform.OS === 'web') { window.addEventListener('online', poll); document.addEventListener('visibilitychange', poll); }
    return () => { stopped = true; alive.current = false; clearInterval(interval); subscription.remove(); if (Platform.OS === 'web') { window.removeEventListener('online', poll); document.removeEventListener('visibilitychange', poll); } };
  }, [storageKey, account.token]);
  const timer = state?.timer;
  useEffect(() => { setNow(Date.now()); if (!timer || timer.startedAt === null) return; const tick = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(tick); }, [timer]);
  useEffect(() => { if (logging?.timer && state && !state.timer && state.version > logging.version) { setLogging(null); store.notify(state.lastSavedId === logging.timer.id ? 'This timer was logged from another device.' : 'This timer was discarded from another device.'); } }, [state?.version]);
  async function action(name: string, extra: Record<string, unknown> = {}, expected = current.current) {
    if (!expected || busy) return null;
    setBusy(true);
    try { return await request({ action: name, version: expected.version, id: expected.timer?.id, device: 'Web', ...extra }); }
    catch (error) { store.notify((error as Error).name === 'AbortError' ? 'Timer connection timed out. Reconnect to check its current state.' : (error as Error).message); return null; }
    finally { if (alive.current) setBusy(false); }
  }
  async function save(mutations: Mutation[]) {
    if (!logging?.timer) throw new Error('Finish the current timer again.');
    if (current.current?.version !== logging.version && current.current?.lastSavedId !== logging.timer.id) throw new Error('The timer changed on another device. Close this form and finish the timer again to log its current time.');
    setBusy(true);
    try {
      await store.sync();
      const entry = mutations.find(m => m.kind === 'session')!.data as Session, review = mutations.find(m => m.kind === 'topic')?.data as any;
      await request({ action: 'log', version: logging.version, id: logging.timer.id, device: 'Web', entry, ...(review ? { review: { reviewOn: review.reviewOn } } : {}) });
      await store.sync(); store.notify('Study time recorded across your devices.');
    } finally { if (alive.current) setBusy(false); }
  }
  const disabled = busy || !connected, duration = timer ? elapsedMs(timer, now + offset) : 0;
  return <View style={{ paddingHorizontal: w.desktop ? 42 : 20, paddingVertical: 10, borderBottomWidth: 1, borderColor: C.line }}>
    <View style={[s.row, { flexWrap: 'wrap', justifyContent: 'space-between', gap: 10 }]}>
      <View style={[s.row, { flexWrap: 'wrap' }]}><Timer size={17} color={C.sage} /><T muted style={{ fontSize: 11 }}>{timer ? `${timer.startedAt === null ? timer.finished ? 'Ready to log' : 'Paused' : 'One timer running'} · started on ${timer.origin || 'another device'}` : state ? 'Shared across your devices' : 'Connecting stopwatch…'}</T>{timer && <T style={{ fontSize: 20, color: C.sage, fontVariant: ['tabular-nums'] }}>{timerClock(duration)}</T>}</View>
      <View style={[s.row, { flexWrap: 'wrap', gap: 8 }]}>{!timer ? <Button small kind="secondary" label={busy ? 'Starting…' : 'Start stopwatch'} icon={Play} disabled={disabled} onPress={() => { void action('start'); }} /> : <>
        <Button small kind="secondary" label={timer.startedAt === null ? 'Resume' : 'Pause'} icon={timer.startedAt === null ? Play : Pause} disabled={disabled} onPress={() => { void action(timer.startedAt === null ? 'resume' : 'pause'); }} />
        <Button small label={timer.finished ? 'Log time' : 'Finish & log'} disabled={disabled} onPress={() => { void action('finish').then(next => { if (next) setLogging(next); }); }} />
        <Button small kind="ghost" label="Discard" disabled={disabled} onPress={() => setDiscard(true)} />
      </>}</View>
    </View>
    {!connected && state && <T muted style={{ fontSize: 10, marginTop: 6 }}>Reconnecting · showing the last known timer. Controls return when connected.</T>}
    {logging?.timer && (w.exams.length ? <LogForm key={logging.version} draft={{ id: logging.timer.id, minutes: timerMinutes(logging.timer, Date.now() + clockOffset.current), note: `Stopwatch · ${timerClock(elapsedMs(logging.timer, Date.now() + clockOffset.current))}` }} saveEntry={save} onClose={() => setLogging(null)} /> : <Dialog open title="Your shared time is kept" onClose={() => setLogging(null)}><T muted>Create an exam workspace, then choose Log time again.</T><Button label="Create exam workspace" onPress={() => { setLogging(null); w.openExam(); }} /></Dialog>)}
    <Dialog open={discard} title="Discard this shared timer?" onClose={() => setDiscard(false)}><T muted style={{ marginBottom: 20 }}>This removes the unsaved timer from all your devices. Saved study entries stay as they are.</T><Button label="Keep timer" kind="secondary" onPress={() => setDiscard(false)} /><Button label="Discard shared timer" kind="ghost" disabled={disabled} onPress={() => { void action('discard').then(next => { if (next) setDiscard(false); }); }} /></Dialog>
  </View>;
}
