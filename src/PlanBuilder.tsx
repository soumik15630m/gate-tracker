import React, { useState } from 'react';
import { View } from 'react-native';
import { Button, C, DateField, Dialog, Input, Select, T, s } from './ui';
import { useStudy } from './store';
import { useWorkspace } from './workspace';
import { activities, planDates, topicPath, uid, type Activity, type Task } from './core/model';

export function PlanBuilder({ initial, onClose }: { initial?: Task; onClose: () => void }) {
  const store = useStudy(), w = useWorkspace();
  const [title, setTitle] = useState(initial?.title || ''), [topic, setTopic] = useState(initial?.topicId || ''), [date, setDate] = useState(initial?.date || w.today), [minutes, setMinutes] = useState(String(initial?.minutes || 60)), [activity, setActivity] = useState<Activity>(initial?.activity || 'Learning'), [notes, setNotes] = useState(initial?.notes || ''), [repeat, setRepeat] = useState(false), [end, setEnd] = useState(w.exam?.date || w.today), [days, setDays] = useState([1, 2, 3, 4, 5]), [error, setError] = useState('');
  const dates = (() => { try { return planDates(date, repeat ? end : date, repeat ? days : undefined); } catch { return []; } })();
  return <Dialog open wide title={initial ? 'Edit your study block' : 'Create a detailed plan'} onClose={onClose}>
    <T muted style={{ marginBottom: 22, lineHeight: 22 }}>Give each block a clear outcome, a place in your syllabus and time to work. Planned time stays separate from your actual study logs.</T>
    <Input label="What will you work on?" value={title} onChange={setTitle} placeholder="Integration lectures and practice questions" />
    <Select label="Subject, chapter or subtopic · optional" value={topic} onChange={setTopic} options={[{ value: '', label: 'Whole exam' }, ...w.topics.map(t => ({ value: t.id, label: topicPath(w.topics, t.id) }))]} />
    <View style={s.columns}><View style={s.column}><DateField label={repeat ? 'Start date' : 'Study date'} value={date} onChange={setDate} /><Input label="Planned duration · minutes" numeric value={minutes} onChange={setMinutes} /></View><View style={s.column}><Select label="Activity" value={activity} onChange={value => setActivity(value as Activity)} options={activities.map(value => ({ value, label: value }))} />{!initial && <Select label="Schedule" value={repeat ? 'repeat' : 'once'} onChange={value => setRepeat(value === 'repeat')} options={[{ value: 'once', label: 'One study block' }, { value: 'repeat', label: 'Repeat on selected days' }]} />}</View></View>
    {repeat && <><DateField label="Repeat until · up to one year" value={end} onChange={setEnd} /><View style={[s.row, { flexWrap: 'wrap', marginBottom: 18 }]}>{['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((label, day) => <Button key={label} small label={label} kind={days.includes(day) ? 'primary' : 'secondary'} onPress={() => setDays(value => value.includes(day) ? value.filter(d => d !== day) : [...value, day])} />)}</View><T muted style={{ marginBottom: 20 }}>{dates.length} study blocks · {Number(minutes) * dates.length || 0} planned minutes. Each block can be edited individually.</T></>}
    <Input label="Detailed steps, resources & outcome · optional" multiline value={notes} onChange={setNotes} placeholder={'1. Watch the selected lectures\n2. Solve 15 questions\n3. Note doubts for revision'} />
    {!!error && <T style={{ color: C.red, marginBottom: 15 }}>{error}</T>}
    <Button label={initial ? 'Save study block' : repeat ? `Create ${dates.length} study blocks` : 'Create study block'} onPress={() => {
      try { if (!w.exam || !title.trim()) throw new Error('Choose an exam and give this block a clear goal.'); const duration = Number(minutes); if (!Number.isInteger(duration) || duration < 1 || duration > 1440) throw new Error('Use a planned duration between 1 and 1,440 whole minutes.');
        const selectedDates = planDates(date, repeat ? end : date, repeat ? days : undefined);
        store.commit(selectedDates.map(date => ({ kind: 'task', data: { id: initial?.id || uid('task'), examId: w.exam!.id, title: title.trim(), type: 'plan', date, done: initial?.done || false, topicId: topic || null, minutes: duration, activity, notes } })), 'Your study plan is ready.'); onClose();
      } catch (e) { setError((e as Error).message); }
    }} />
  </Dialog>;
}
