import { SharedStopwatch } from './SharedStopwatch';
import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Pause, Play, Timer, Trash2 } from 'lucide-react-native';
import { Button, C, Dialog, T, s } from './ui';
import { LogForm } from './forms';
import { useStudy } from './store';
import { useWorkspace } from './workspace';
import { uid } from './core/model';
import { elapsedMs, pauseTimer, readTimer, resumeTimer, timerClock, timerMinutes, type StudyTimer } from './core/stopwatch';

// Ticks stay inside this component, so large syllabus trees do not rerender every second.
export function Stopwatch({ storageKey }: { storageKey: string }) {
  const { account } = useStudy();
  return account ? <SharedStopwatch account={account} storageKey={storageKey} /> : <LocalStopwatch storageKey={storageKey} />;
}
function LocalStopwatch({ storageKey }: { storageKey: string }) {
  const store = useStudy(), workspace = useWorkspace();
  const [timer, setTimer] = useState<StudyTimer | null>(null), [ready, setReady] = useState(false), [now, setNow] = useState(Date.now()), [logging, setLogging] = useState(false), [discard, setDiscard] = useState(false);
  const writes = useRef(Promise.resolve());
  useEffect(() => { let active = true; AsyncStorage.getItem(storageKey).then(value => { if (active) { const restored = readTimer(value); const alreadySaved = restored && store.data.records[restored.id] && !store.data.records[restored.id].deleted; setTimer(alreadySaved ? null : restored); if (alreadySaved) AsyncStorage.removeItem(storageKey).catch(() => {}); setReady(true); } }).catch(() => store.notify('Could not restore the stopwatch. Reload to try again.')); return () => { active = false; }; }, [storageKey]);
  useEffect(() => { if (!timer || timer.startedAt === null) return; setNow(Date.now()); const tick = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(tick); }, [timer]);
  function update(value: StudyTimer | null) {
    setTimer(value); setNow(Date.now());
    writes.current = writes.current.catch(() => {}).then(() => value ? AsyncStorage.setItem(storageKey, JSON.stringify(value)) : AsyncStorage.removeItem(storageKey));
    writes.current.catch(() => store.notify('Stopwatch storage is unavailable. Keep this page open until you save your time.'));
  }
  if (!ready) return null;
  const duration = timer ? elapsedMs(timer, now) : 0;
  return <View style={{ paddingHorizontal: workspace.desktop ? 42 : 20, paddingVertical: 10, borderBottomWidth: 1, borderColor: C.line }}>
    <View style={[s.row, { flexWrap: 'wrap', justifyContent: 'space-between', gap: 10 }]}>
      <View style={s.row}><Timer color={C.sage} size={17} /><T muted style={{ fontSize: 11 }}>{timer ? timer.startedAt === null ? 'Paused' : 'Studying' : 'Ready to focus'}</T>{timer && <T accessibilityLabel={`Stopwatch ${timerClock(duration)}`} style={{ color: C.sage, fontSize: 20, fontVariant: ['tabular-nums'] }}>{timerClock(duration)}</T>}</View>
      <View style={[s.row, { flexWrap: 'wrap', gap: 8 }]}>
        {!timer ? <Button small kind="secondary" label="Start stopwatch" icon={Play} onPress={() => update({ id: uid('session'), accumulatedMs: 0, startedAt: Date.now() })} /> : <>
          <Button small kind="secondary" label={timer.startedAt === null ? 'Resume' : 'Pause'} icon={timer.startedAt === null ? Play : Pause} onPress={() => update(timer.startedAt === null ? resumeTimer(timer) : pauseTimer(timer))} />
          <Button small label="Finish & log" onPress={() => { update(pauseTimer(timer)); setLogging(true); }} />
          <Button small kind="ghost" label="Discard" icon={Trash2} onPress={() => setDiscard(true)} />
        </>}
      </View>
    </View>
    {logging && timer && (workspace.exams.length ? <LogForm draft={{ id: timer.id, minutes: timerMinutes(timer), note: `Stopwatch · ${timerClock(elapsedMs(timer))}` }} onSaved={async () => { await store.flush(); update(null); }} onClose={() => setLogging(false)} /> : <Dialog open title="Your time is kept" onClose={() => setLogging(false)}><T muted style={{ marginBottom: 20 }}>Create an exam workspace, then choose Finish & log again. Your stopwatch stays paused.</T><Button label="Create exam workspace" onPress={() => { setLogging(false); workspace.openExam(); }} /></Dialog>)}
    <Dialog open={discard} title="Discard this stopwatch?" onClose={() => setDiscard(false)}><T muted style={{ marginBottom: 20 }}>This removes {timerClock(duration)} of unsaved time. Your saved study entries stay as they are.</T><Button label="Keep studying" kind="secondary" onPress={() => setDiscard(false)} /><Button label="Discard unsaved time" kind="ghost" onPress={() => { update(null); setDiscard(false); }} /></Dialog>
  </View>;
}
