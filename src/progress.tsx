import React, { useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import { Button, C, DateField, Dialog, Input, Meter, Select, T, s } from './ui';
import { useStudy } from './store';
import { useWorkspace } from './workspace';
import { PaceFields } from './AutoPlanner';
import { TopicFilterControls } from './importance';
import { TopicAutocomplete } from './TopicAutocomplete';
import type { TopicFilter } from './core/search';
import { importanceLabel, matchesImportance, branchLeaves, completionMutations, contentProgress, descendants, list, subjectFor, topicPath, topicProgress, type Topic } from './core/model';

export function ContentMeters({ topic, topics }: { topic: Topic; topics: Topic[] }) {
  const progress = contentProgress(topics, topic.id), subject = subjectFor(topics, topic.id);
  if (!subject || !subject.lectureCount && !subject.testCount) return null;
  return <View style={{ gap: 12, marginVertical: 16 }}>{(['lectures', 'tests'] as const).map(kind => {
    const total = kind === 'lectures' ? subject.lectureCount : subject.testCount, done = progress[kind].size;
    return total ? <View key={kind} style={{ gap: 8 }}><T muted style={{ fontSize: 12 }}>{topic.parentId ? `${done} ${kind} recorded in this branch` : `${done} / ${total} ${kind} completed`}</T>{!topic.parentId && <Meter value={done / total * 100} color={kind === 'lectures' ? C.teal : C.sage} />}</View> : null;
  })}{topic.parentId && <T muted style={{ fontSize: 10 }}>Counts come from your selections · {subject.title}</T>}</View>;
}
export function SubjectCounts({ topic }: { topic: Topic }) {
  const store = useStudy(), w = useWorkspace();
  const [pace,setPace] = useState(String(topic.schedulePace ?? 1)), [mode,setMode] = useState(topic.paceMode || 'daily');
  const [lectures, setLectures] = useState(String(topic.lectureCount || '')), [tests, setTests] = useState(String(topic.testCount || ''));
  return <View><T muted style={{ marginBottom: 14 }}>Subject totals</T><View style={s.columns}><View style={{ flex: 1 }}><Input label="Lecture count" numeric value={lectures} onChange={setLectures} /></View><View style={{ flex: 1 }}><Input label="Test count" numeric value={tests} onChange={setTests} /></View></View><PaceFields pace={pace} mode={mode} onPace={setPace} onMode={v=>setMode(v as 'daily'|'weekly')} /><Button small kind="secondary" label="Save subject totals" onPress={() => {
    const lectureCount = Number(lectures || 0), testCount = Number(tests || 0), used = contentProgress(w.topics, topic.id);
    if (![lectureCount, testCount].every(n => Number.isInteger(n) && n >= 0 && n <= 10000)) return store.notify('Use whole counts between 0 and 10,000.');
    if ([...used.lectures].some(n => n > lectureCount) || [...used.tests].some(n => n > testCount)) return store.notify('Totals must include your already recorded lecture and test numbers.');
    store.commit([{ kind: 'topic', data: { ...topic, lectureCount, testCount, schedulePace:Number(pace), paceMode:mode } }], 'Subject totals saved. Future schedule updated automatically.');
  }} /></View>;
}
export function CompletionForm({ topicId, recordOnly = false, onClose }: { topicId: string; recordOnly?: boolean; onClose: () => void }) {
  const store = useStudy(), w = useWorkspace(), topics = list<Topic>(store.data.records, 'topic'), topic = topics.find(t => t.id === topicId), subject = subjectFor(topics, topicId);
  const [kind, setKind] = useState<'lecture' | 'test'>('lecture'), [lectureIds, setLectures] = useState(new Set(topic?.lectureIds || [])), [testIds, setTests] = useState(new Set(topic?.testIds || []));
  if (!topic || !subject) return null;
  const subjectIds = new Set([subject.id, ...descendants(topics, subject.id).map(t => t.id)]);
  const other = topics.filter(t => subjectIds.has(t.id) && t.id !== topicId), used = new Set(other.flatMap(t => kind === 'lecture' ? t.lectureIds || [] : t.testIds || []));
  const count = (kind === 'lecture' ? subject.lectureCount : subject.testCount) || 0, selected = kind === 'lecture' ? lectureIds : testIds, setter = kind === 'lecture' ? setLectures : setTests;
  const unfinished = branchLeaves(topics, topic.id).filter(t => !t.completedOn).length;
  return <Dialog open title={recordOnly ? 'Record your progress' : 'A little further.'} onClose={onClose}>
    <T style={{ fontSize: 17, marginBottom: 8 }}>{topic.title}</T><T muted style={{ lineHeight: 21, marginBottom: 18 }}>{recordOnly ? 'Update the lectures and tests you covered here.' : `${unfinished} checklist ${unfinished === 1 ? 'item will' : 'items will'} close today. Select the lectures or tests you covered.`} Totals belong to {subject.title}.</T>
    <View style={[s.row, { marginBottom: 16 }]}><Button small kind={kind === 'lecture' ? 'primary' : 'secondary'} label={`Lectures · ${lectureIds.size}`} onPress={() => setKind('lecture')} /><Button small kind={kind === 'test' ? 'primary' : 'secondary'} label={`Tests · ${testIds.size}`} onPress={() => setKind('test')} /></View>
    {count ? <><T muted style={{ fontSize: 12, marginBottom: 10 }}>{selected.size} selected here · {used.size} recorded elsewhere</T><FlatList style={{ height: 240, flexGrow: 0, marginBottom: 16, borderWidth: 1, borderColor: C.line, borderRadius: 8 }} nestedScrollEnabled data={Array.from({ length: count }, (_, i) => i + 1)} keyExtractor={n => String(n)} initialNumToRender={8} getItemLayout={(_, index) => ({ index, length: 44, offset: index * 44 })} renderItem={({ item: n }) => <Pressable disabled={used.has(n) && !selected.has(n)} accessibilityRole="checkbox" accessibilityLabel={`${kind === 'lecture' ? 'Lecture' : 'Test'} ${n}`} accessibilityState={{ checked: selected.has(n) || used.has(n), disabled: used.has(n) && !selected.has(n) }} onPress={() => setter(current => { const next = new Set(current); next.has(n) ? next.delete(n) : next.add(n); return next; })} style={{ height: 44, paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: selected.has(n) ? C.sageWash : 'transparent', opacity: used.has(n) && !selected.has(n) ? .5 : 1 }}><T>{kind === 'lecture' ? 'Lecture' : 'Test'} {n}</T><T muted style={{ fontSize: 11 }}>{used.has(n) && !selected.has(n) ? 'Recorded elsewhere' : selected.has(n) ? '✓ Selected' : 'Select'}</T></Pressable>} /></> : <T muted style={{ marginBottom: 22, lineHeight: 22 }}>Set the {kind} count in your subject’s details to select numbered items. You can still complete this topic now and record content later.</T>}
    <Button label={recordOnly ? 'Save progress' : 'Complete & save progress'} onPress={() => {
      try { if (!recordOnly && topicProgress(topics, topic.id).percent === 100) throw new Error('This branch was already completed on another device. Close this popup and use Record lectures / tests to edit its progress.');
        const patch = { ...topic, lectureIds: [...lectureIds].sort((a, b) => a - b), testIds: [...testIds].sort((a, b) => a - b) };
        store.commit(recordOnly ? [{ kind: 'topic', data: patch }] : completionMutations(topics, topic.id, w.today, patch.lectureIds, patch.testIds), 'Progress saved.'); onClose();
      } catch (error) { store.notify((error as Error).message); }
    }} />
  </Dialog>;
}
export function QuickRevisionForm({ onClose }: { onClose: () => void }) {
  const store = useStudy(), w = useWorkspace(), [topicId, setTopic] = useState(''), [date, setDate] = useState(w.today);
  const [topicFilter, setTopicFilter] = useState<TopicFilter>({});
  const topics = w.topics, topic = topics.find(t => t.id === topicId);
  return <Dialog open title="Add to your revision queue" onClose={onClose}><TopicFilterControls value={topicFilter} onChange={setTopicFilter} /><TopicAutocomplete topics={topics} value={topicId} onChange={setTopic} filter={topicFilter} label="Subject, chapter or subtopic" /><DateField label="Revision date" value={date} onChange={setDate} /><Button label="Schedule revision" disabled={!topic} onPress={() => { if (topic) { try { store.commit([{ kind: 'topic', data: { ...topic, reviewOn: date } }], 'Revision scheduled.'); onClose(); } catch (e) { store.notify((e as Error).message); } } }} />{!topics.length && <T muted style={{ marginTop: 16 }}>Add subjects and topics in your syllabus first.</T>}</Dialog>;
}
