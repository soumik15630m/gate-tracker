import React, { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState, Platform, View } from 'react-native';
import * as Updates from 'expo-updates';
import { Download, RefreshCw } from 'lucide-react-native';
import { useStudy } from './store';
import { Badge, Button, C, Panel, T, s } from './ui';
interface UpdateState { enabled: boolean; checking: boolean; pending: boolean; error: string; checkedAt: string; check: () => Promise<void>; apply: () => Promise<void> }
const Context = createContext<UpdateState | null>(null);
export function UpdateProvider({ children }: { children: ReactNode }) {
  const study = useStudy(), enabled = Platform.OS === 'android' && Updates.isEnabled;
  const updates = Updates.useUpdates(), [checking, setChecking] = useState(false), [error, setError] = useState(''), [checkedAt, setCheckedAt] = useState('');
  const busy = useRef(false), lastAttempt = useRef(0);
  const check = useCallback(async () => {
    if (!enabled || busy.current) return;
    busy.current = true; lastAttempt.current = Date.now(); setChecking(true); setError('');
    try { const result = await Updates.checkForUpdateAsync(); if (result.isAvailable || result.isRollBackToEmbedded) await Updates.fetchUpdateAsync(); setCheckedAt(new Date().toISOString()); }
    catch { setError('Could not reach the update service. Your installed version and study data are available offline.'); }
    finally { busy.current = false; setChecking(false); }
  }, [enabled]);
  useEffect(() => { if (!enabled) return; check(); const listener = AppState.addEventListener('change', state => { if (state === 'active' && Date.now() - lastAttempt.current > 5 * 60000) check(); }); return () => listener.remove(); }, [check, enabled]);
  const apply = useCallback(async () => { try { await study.flush(); await Updates.reloadAsync(); } catch { study.notify('The update could not restart. Your data is saved; close and reopen the app to try again.'); } }, [study.flush, study.notify]);
  return <Context.Provider value={{ enabled, checking: checking || updates.isDownloading, pending: enabled && updates.isUpdatePending, error, checkedAt, check, apply }}>{children}</Context.Provider>;
}
export function useAppUpdates() { const state = useContext(Context); if (!state) throw new Error('UpdateProvider is missing.'); return state; }
export function UpdateSettings() {
  const updates = useAppUpdates();
  return <Panel title="App updates" subtitle={Platform.OS === 'web' ? 'The web app loads the latest deployed version when you reopen it.' : 'Compatible improvements arrive automatically from GitHub through Expo Updates.'}>
    {Platform.OS === 'web' ? <T muted style={{ lineHeight: 22 }}>Android downloads compatible updates in the background and applies them on the next launch. Your study data is stored separately from the app version.</T> : !updates.enabled ? <T muted style={{ lineHeight: 22 }}>This APK is not yet linked to an Expo update project. Configure the project and install one new APK to enable automatic OTA delivery.</T> : <><View style={[s.row, { flexWrap: 'wrap', marginBottom: 18 }]}><Badge color={C.teal}>{Updates.channel || 'production'}</Badge><T muted style={{ fontSize: 11 }}>Runtime {String(Updates.runtimeVersion || '').slice(0, 12)}</T><T muted style={{ fontSize: 11 }}>{Updates.isEmbeddedLaunch ? 'Bundled version' : `OTA ${Updates.updateId?.slice(0, 8)}`}</T></View><T muted style={{ lineHeight: 22, marginBottom: 18 }}>{updates.pending ? 'An update is downloaded. Restart when you’re ready, or it will apply on your next launch.' : updates.checking ? 'Checking for improvements…' : updates.checkedAt ? 'Update check completed. You’re using a compatible release.' : 'Updates are checked on launch and when you return to the app.'}</T>{!!updates.error && <T muted style={{ lineHeight: 22, marginBottom: 16 }}>{updates.error}</T>}<View style={[s.row, { flexWrap: 'wrap' }]}><Button label={updates.checking ? 'Checking…' : 'Check for updates'} icon={RefreshCw} kind="secondary" onPress={updates.check} disabled={updates.checking} />{updates.pending && <Button label="Save & restart" icon={Download} onPress={updates.apply} />}</View></>}
  </Panel>;
}
