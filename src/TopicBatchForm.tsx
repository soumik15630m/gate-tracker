import React, { useState } from 'react';
import { View } from 'react-native';
import { Plus, Trash2 } from 'lucide-react-native';
import { Button, C, Dialog, IconButton, Input, T, s } from './ui';
import { PaceFields } from './AutoPlanner';
import { ImportancePicker } from './importance';
import { useStudy } from './store';
import { useWorkspace } from './workspace';
import { uid, type Mutation, type Topic } from './core/model';

interface Draft { id: string; title: string; importance: number | null; lectures: string; tests: string; pace: string; mode: 'daily' | 'weekly' }
const blank = (): Draft => ({ id: uid('topic'), title: '', importance: null, lectures: '', tests: '', pace: '1', mode: 'daily' });
export function TopicBatchForm({ parentId, onClose, onCreated }: { parentId?: string | null; onClose: () => void; onCreated: (id: string) => void }) {
  const store = useStudy(), w = useWorkspace();
  const [rows, setRows] = useState<Draft[]>(() => [blank()]), [active, setActive] = useState(''), [error, setError] = useState('');
  const parent = w.topics.find(t => t.id === parentId), noun = parentId ? 'child topic' : 'subject';
  function update(id: string, patch: Partial<Draft>) { setRows(current => current.map(row => row.id === id ? { ...row, ...patch } : row)); }
  function save() {
    try {
      if (!w.exam || parentId && !parent) throw new Error('The selected parent is no longer available. Reopen the form.');
      const offset = Math.max(-1, ...w.topics.filter(t => t.parentId === (parentId || null)).map(t => t.order)) + 1;
      const changes: Mutation[] = rows.map((row, index) => {
        if (!row.title.trim()) throw new Error(`Give ${noun} ${index + 1} a name, or remove that row.`);
        const topic: Topic = { id: row.id, examId: w.exam!.id, parentId: parentId || null, title: row.title.trim(), order: offset + index, completedOn: null, confidence: 'unrated', importance: row.importance, ...(!parentId ? { schedulePace: Number(row.pace), paceMode: row.mode, lectureCount: Number(row.lectures || 0), testCount: Number(row.tests || 0) } : {}) };
        return { kind: 'topic', data: topic };
      });
      if (parent) changes.push({ kind: 'topic', data: { ...parent, completedOn: null } });
      store.commit(changes, `${rows.length} ${rows.length === 1 ? noun : noun + 's'} added.`);
      onCreated(rows[0].id); onClose();
    } catch (e) { setError((e as Error).message); }
  }
  return <Dialog open title={parent ? `Add children to ${parent.title}` : 'Add subjects'} onClose={onClose}>
    <T muted style={{ lineHeight: 21, marginBottom: 18 }}>New subjects start at 1 lecture per study day; adjust this in subject details or Plan. Keep adding rows here. Save all creates them together{parent ? ` under ${parent.title}` : ' as separate subjects'}. Enter saves the list.</T>
    {rows.map((row, index) => <View key={row.id} style={{ borderWidth: 1, borderColor: C.line, borderRadius: 10, padding: 14, marginBottom: 14 }}>
      <View style={[s.row, { justifyContent: 'space-between', marginBottom: 8 }]}><T muted style={{ fontSize: 11 }}>{noun.toUpperCase()} {index + 1}</T><IconButton icon={Trash2} disabled={rows.length === 1} label={`Remove ${noun} ${index + 1}`} onPress={() => { const remaining = rows.filter(item => item.id !== row.id); setRows(remaining); setActive(remaining[Math.max(0, index - 1)].id); }} /></View>
      <Input label={index === 0 ? 'Name' : `Name ${index + 1}`} value={row.title} onChange={title => update(row.id, { title })} autoFocus={active ? active === row.id : index === 0} onSubmit={save} placeholder={`Name this ${noun}`} />
      <ImportancePicker value={row.importance} onChange={importance => update(row.id, { importance })} />
      {!parentId && <><PaceFields pace={row.pace} mode={row.mode} onPace={pace=>update(row.id,{pace})} onMode={mode=>update(row.id,{mode:mode as 'daily'|'weekly'})}/><View style={s.columns}><View style={{ flex: 1 }}><Input label="Lecture count · optional" numeric value={row.lectures} onChange={lectures => update(row.id, { lectures })} /></View><View style={{ flex: 1 }}><Input label="Test count · optional" numeric value={row.tests} onChange={tests => update(row.id, { tests })} /></View></View></>}
    </View>)}
    <Button label={parentId ? '+ Add child' : '+ Add subject'} icon={Plus} kind="secondary" disabled={rows.length >= 100} onPress={() => { const row = blank(); setRows(current => [...current, row]); setActive(row.id); }} />
    {!!error && <T style={{ color: C.red, lineHeight: 21, marginTop: 16 }}>{error}</T>}
    <Button label={`Save all · ${rows.length}`} onPress={save} style={{ marginTop: 18 }} />
  </Dialog>;
}
