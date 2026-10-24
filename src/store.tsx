import React, { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import Constants from 'expo-constants';
import { applyMutations, emptyState, list, reconcile, type Conflict, type LocalState, type Mutation, type Preferences, type SyncResult } from './core/model';
import { sampleState } from './core/sample';
import { validateBackup, validateEntity, validateRelationships } from '../shared/validation.mjs';
interface Account { token: string; user: { id: string; email: string }; server: string }
interface Store {
  data: LocalState; ready: boolean; sample: boolean; account: Account | null; status: string; error: string; toast: string;
  commit: (mutations: Mutation[], message?: string) => void; undo: () => void; sync: () => Promise<void>; flush: () => Promise<void>;
  signIn: (server: string, email: string, password: string, register: boolean) => Promise<void>; signOut: () => Promise<void>;
  startFresh: () => Promise<void>; loadSample: () => Promise<void>; restore: (input: unknown) => Promise<void>; recoverRestorePoint: () => Promise<void>;
  resolveConflict: (conflict: Conflict, useLocal: boolean) => void; resolveAllConflicts: (useLocal: boolean) => void; notify: (message: string) => void; defaultServer: string;
}
const Context = createContext<Store | null>(null);
const AUTH = 'studyspace.auth.v1', LOCAL = 'studyspace.local.v1';
const keyFor = (account: Account | null) => account ? `studyspace.account.${encodeURIComponent(account.server)}.${account.user.id}` : LOCAL;
const secret = { get: () => Platform.OS === 'web' ? AsyncStorage.getItem(AUTH) : SecureStore.getItemAsync(AUTH), set: (value: string) => Platform.OS === 'web' ? AsyncStorage.setItem(AUTH, value) : SecureStore.setItemAsync(AUTH, value), remove: () => Platform.OS === 'web' ? AsyncStorage.removeItem(AUTH) : SecureStore.deleteItemAsync(AUTH) };
export function defaultServer() {
  if (process.env.EXPO_PUBLIC_API_URL) return process.env.EXPO_PUBLIC_API_URL;
  if (Platform.OS === 'web' && typeof location !== 'undefined') return location.port === '8081' ? `${location.protocol}//${location.hostname}:3001` : location.origin;
  const host = Constants.expoConfig?.hostUri?.split(':')[0] || '10.0.2.2';
  return `http://${host}:3001`;
}
export function StudyProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<LocalState>(emptyState()), [ready, setReady] = useState(false), [account, setAccount] = useState<Account | null>(null), [sample, setSample] = useState(false);
  const [status, setStatus] = useState('local'), [error, setError] = useState(''), [toast, setToast] = useState('');
  const dataRef = useRef(data), accountRef = useRef(account), keyRef = useRef(LOCAL), sampleRef = useRef(false), busy = useRef(false), epoch = useRef(0), undoRef = useRef<Mutation[]>([]), writes = useRef(Promise.resolve()), storageBlocked = useRef(false);
  const update = useCallback((fn: (current: LocalState) => LocalState) => { const next = fn(dataRef.current); dataRef.current = next; setData(next); }, []);
  const notify = useCallback((message: string) => setToast(message), []);
  useEffect(() => { if (!toast) return; const timer = setTimeout(() => setToast(''), 6000); return () => clearTimeout(timer); }, [toast]);
  const saveNow = useCallback(async (state: LocalState, key = keyRef.current, isSample = sampleRef.current) => { if (storageBlocked.current) throw new Error('The unreadable cache has been preserved. Restore a backup or start fresh first.'); const payload = JSON.stringify({ version: 1, state, sample: isSample }); writes.current = writes.current.catch(() => {}).then(() => AsyncStorage.setItem(key, payload)); await writes.current; }, []);
  const load = useCallback(async (nextAccount: Account | null) => {
    epoch.current++; storageBlocked.current = true; const key = keyFor(nextAccount), cached = await AsyncStorage.getItem(key);
    let next: LocalState, nextSample = false, cacheIssue = '';
    if (cached) { try { const parsed = JSON.parse(cached); if (parsed.version !== 1 || !parsed.state?.records || !parsed.state?.pending || !Array.isArray(parsed.state.conflicts) || !Number.isSafeInteger(parsed.state.cursor)) throw new Error('Invalid cache'); for (const record of Object.values(parsed.state.records) as any[]) if (!record || !validateEntity(record.kind, record.data)) throw new Error('Invalid record'); next = parsed.state; nextSample = !!parsed.sample; } catch { await AsyncStorage.setItem(key + '.unreadable', cached).catch(() => {}); next = emptyState(); cacheIssue = 'This device’s cache could not be opened and has been preserved. Restore a backup or explicitly start fresh in Settings.'; } }
    else { next = nextAccount ? emptyState() : sampleState(); nextSample = !nextAccount; }
    storageBlocked.current = !!cacheIssue; accountRef.current = nextAccount; keyRef.current = key; sampleRef.current = nextSample; undoRef.current = []; setAccount(nextAccount); setSample(nextSample); update(() => next); setStatus(cacheIssue ? 'recovery needed' : nextAccount ? 'syncing' : 'local'); setError(cacheIssue);
  }, [update]);
  useEffect(() => { (async () => { try { const stored = await secret.get(); await load(stored ? JSON.parse(stored) : null); } catch (e) { storageBlocked.current = true; setError((e as Error).message); } finally { setReady(true); } })(); }, [load]);
  useEffect(() => { if (ready && !storageBlocked.current) saveNow(data).catch(() => setError('Could not save on this device. Export a backup before closing.')); }, [data, ready, saveNow]);
  const commit = useCallback((mutations: Mutation[], message?: string) => {
    if (!mutations.length) return;
    if (storageBlocked.current) throw new Error('Your original cache is preserved. Restore a backup or explicitly start fresh before editing.');
    for (const mutation of mutations) if (!validateEntity(mutation.kind, mutation.data)) throw new Error('Some fields are invalid. Check your dates, duration, and required values.');
    const before = dataRef.current;
    const next = applyMutations(before, mutations, !!accountRef.current);
    validateRelationships(Object.values(next.records));
    undoRef.current = mutations.map(m => before.records[m.data.id] ? { kind: before.records[m.data.id].kind, data: before.records[m.data.id].data, deleted: before.records[m.data.id].deleted } : { ...m, deleted: true });
    update(() => next); if (message) notify(message); setError('');
  }, [notify, update]);
  const undo = useCallback(() => { const mutations = undoRef.current; if (mutations.length) { undoRef.current = []; update(state => applyMutations(state, mutations, !!accountRef.current)); notify('Change undone.'); } }, [notify, update]);
  const sync = useCallback(async () => {
    const auth = accountRef.current; if (!auth || storageBlocked.current || busy.current || dataRef.current.conflicts.length) return;
    busy.current = true; const requestEpoch = epoch.current; setStatus('syncing');
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const snapshot = dataRef.current, sent = Object.values(snapshot.pending);
      const response = await fetch(`${auth.server}/api/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.token}` }, body: JSON.stringify({ cursor: snapshot.cursor, changes: sent }), signal: controller.signal });
      const result = await response.json(); if (!response.ok) throw new Error(result.error || 'Sync could not finish.');
      if (requestEpoch !== epoch.current) return;
      update(current => reconcile(current, sent, result as SyncResult)); setStatus('synced'); setError('');
    } catch (e) { if (requestEpoch === epoch.current) { setStatus('offline'); setError((e as Error).name === 'AbortError' ? 'Sync timed out. Your changes are saved on this device.' : (e as Error).message); } }
    finally { clearTimeout(timeout); busy.current = false; }
  }, [update]);
  useEffect(() => { if (!ready || !account) return; sync(); const timer = setInterval(sync, 15000); const listener = AppState.addEventListener('change', state => { if (state === 'active') sync(); }); const online = () => sync(); if (Platform.OS === 'web') window.addEventListener('online', online); return () => { clearInterval(timer); listener.remove(); if (Platform.OS === 'web') window.removeEventListener('online', online); }; }, [account, ready, sync]);
  useEffect(() => { if (!ready || !account || !Object.keys(data.pending).length) return; const timer = setTimeout(sync, 1200); return () => clearTimeout(timer); }, [data.pending, ready, account, sync]);
  const signIn = useCallback(async (serverInput: string, email: string, password: string, register: boolean) => {
    const server = serverInput.trim().replace(/\/+$/, ''); if (!/^https?:\/\/[^\s]+$/.test(server)) throw new Error('Enter the full http:// or https:// server address.');
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000);
    let response: Response;
    try { response = await fetch(`${server}/api/auth/${register ? 'register' : 'login'}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }), signal: controller.signal }); } finally { clearTimeout(timeout); }
    const result = await response.json(); if (!response.ok) throw new Error(result.error || 'Could not sign in.');
    if (!storageBlocked.current) await saveNow(dataRef.current); const auth: Account = { ...result, server }; await load(auth); await secret.set(JSON.stringify(auth)); notify(register ? 'Account created. Your new workspace is ready.' : 'Welcome back. Syncing your workspace.');
  }, [load, notify, saveNow]);
  const signOut = useCallback(async () => { const auth = accountRef.current; await saveNow(dataRef.current); if (auth) fetch(`${auth.server}/api/auth/logout`, { method: 'POST', headers: { Authorization: `Bearer ${auth.token}` } }).catch(() => {}); await secret.remove(); await load(null); notify('Signed out. Your account’s local cache is preserved.'); }, [load, notify, saveNow]);
  const startFresh = useCallback(async () => { storageBlocked.current = false; await AsyncStorage.setItem(keyRef.current + '.restore-point', JSON.stringify(dataRef.current)); sampleRef.current = false; setSample(false); const existing = Object.values(dataRef.current.records).filter(r => !r.deleted).map(r => ({ kind: r.kind, data: r.data, deleted: true })); if (accountRef.current) commit(existing, 'Workspace cleared. A restore point was saved.'); else update(() => emptyState()); await saveNow(dataRef.current); setError(''); setStatus(accountRef.current ? 'syncing' : 'local'); }, [commit, saveNow, update]);
  const loadSample = useCallback(async () => { if (accountRef.current) throw new Error('Sign out before loading the sample workspace.'); await AsyncStorage.setItem(LOCAL + '.restore-point', JSON.stringify(dataRef.current)); storageBlocked.current = false; sampleRef.current = true; setSample(true); update(() => sampleState()); await saveNow(dataRef.current); setError(''); notify('Sample workspace loaded.'); }, [notify, saveNow, update]);
  const restore = useCallback(async (input: unknown) => { const incoming = validateBackup(input); await AsyncStorage.setItem(keyRef.current + '.restore-point', JSON.stringify(dataRef.current)); const ids = new Set(incoming.map(r => r.data.id)); const removed = Object.values(dataRef.current.records).filter(r => !r.deleted && !ids.has(r.id)).map(r => ({ kind: r.kind, data: r.data, deleted: true })); storageBlocked.current = false; commit([...removed, ...incoming], 'Backup restored. A restore point was saved.'); sampleRef.current = false; setSample(false); await saveNow(dataRef.current); setError(''); }, [commit, saveNow]);
  const resolveConflict = useCallback((conflict: Conflict, useLocal: boolean) => { update(current => { const without = { ...current, conflicts: current.conflicts.filter(c => c.local.id !== conflict.local.id) }; return useLocal ? applyMutations(without, [{ kind: conflict.local.kind, data: conflict.local.data, deleted: conflict.local.deleted }], true) : without; }); notify(useLocal ? 'Your version is queued for sync.' : 'Kept the server version.'); }, [notify, update]);
  const recoverRestorePoint = useCallback(async () => { const cached = await AsyncStorage.getItem(keyRef.current + '.restore-point'); if (!cached) throw new Error('No recovery point has been saved for this workspace yet.'); const previous = JSON.parse(cached) as LocalState; await restore({ format: 'studyspace', version: 1, records: Object.values(previous.records).filter(r => !r.deleted).map(r => ({ kind: r.kind, data: r.data })) }); }, [restore]);
  const resolveAllConflicts = useCallback((useLocal: boolean) => { update(current => { const next = { ...current, conflicts: [] }; const resolved = useLocal ? applyMutations(next, current.conflicts.map(c => ({ kind: c.local.kind, data: c.local.data, deleted: c.local.deleted })), true) : next; validateRelationships(Object.values(resolved.records)); return resolved; }); notify(useLocal ? 'Your changes are queued for sync.' : 'Kept the server versions.'); }, [notify, update]);
  const flush = useCallback(() => saveNow(dataRef.current), [saveNow]);
  return <Context.Provider value={{ data, ready, sample, account, status, error, toast, commit, undo, sync, flush, signIn, signOut, startFresh, loadSample, restore, recoverRestorePoint, resolveConflict, resolveAllConflicts, notify, defaultServer: defaultServer() }}>{children}</Context.Provider>;
}
export function useStudy() { const store = useContext(Context); if (!store) throw new Error('StudyProvider is missing.'); return store; }
export function usePreferences(): Preferences { const { data } = useStudy(); return list<Preferences>(data.records, 'preferences')[0] || { id: 'preferences', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', streakMinimum: 1, displayName: '' }; }
