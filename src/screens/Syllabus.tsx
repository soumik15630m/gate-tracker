import React, { useEffect, useMemo, useState } from 'react';
import { Pressable, View } from 'react-native';
import { ArrowDown, ArrowLeft, ArrowUp, ChevronDown, ChevronRight, Edit3, FolderInput, MoreHorizontal, Plus, Search, Trash2, Upload, X } from 'lucide-react-native';
import { Badge, Button, C, Checkbox, Confirm, DateField, Dialog, Empty, F, Heading, IconButton, Input, Meter, Panel, Select, T, s } from '../ui';
import { useStudy } from '../store';
import { ContentMeters, SubjectCounts } from '../progress';
import { ImportanceBadge, ImportancePicker } from '../importance';
import { TopicSearchIndex } from '../core/search';
import { TopicBatchForm } from '../TopicBatchForm';
import { useWorkspace } from '../workspace';
import { branchLeaves, branchMinutes, canMove, children, confidenceNames, descendants, duration, moveTopicMutations, outlineTopics, parseOutline, prettyDate, topicPath, topicProgress, uid, type Confidence, type Mutation, type Topic } from '../core/model';
export default function Syllabus() {
  const store = useStudy(), w = useWorkspace(), { topics, sessions, today, desktop, exam } = w;
  const [importance, setImportance] = useState<number | null>(null);
  const [selectedId, setSelected] = useState<string | null>(null), [collapsed, setCollapsed] = useState<Set<string>>(new Set()), [search, setSearch] = useState(''), [filter, setFilter] = useState('all');
  const [editor, setEditor] = useState<{ mode: 'add' | 'rename' | 'move'; topic?: Topic; parentId?: string | null } | null>(null), [title, setTitle] = useState(''), [parent, setParent] = useState(''), [error, setError] = useState(''), [remove, setRemove] = useState<Topic | null>(null), [importOpen, setImportOpen] = useState(false), [outline, setOutline] = useState(''), [preview, setPreview] = useState<ReturnType<typeof parseOutline> | null>(null);
  const selected = topics.find(t => t.id === selectedId) || (desktop ? topics[0] : undefined), progress = selected && topicProgress(topics, selected.id);
  const searchIndex = useMemo(() => new TopicSearchIndex(topics), [topics]);
  const [settledSearch, setSettledSearch] = useState('');
  useEffect(() => { const timer = setTimeout(() => setSettledSearch(search), 90); return () => clearTimeout(timer); }, [search]);
  const flat = useMemo(() => {
    const included = settledSearch ? new Set(searchIndex.search(settledSearch, {}, topics.length).map(row => row.topic.id)) : null;
    if (included) for (const { topic } of [...searchIndex.nodes].reverse()) if (included.has(topic.id) && topic.parentId) included.add(topic.parentId);
    const rows: { topic: Topic; depth: number }[] = [], hidden = new Set<string>();
    for (const node of searchIndex.nodes) {
      if (!settledSearch && node.topic.parentId && (hidden.has(node.topic.parentId) || collapsed.has(node.topic.parentId))) { hidden.add(node.topic.id); continue; }
      if (included && !included.has(node.topic.id)) continue;
      const progress = topicProgress(topics, node.topic.id);
      if (filter === 'done' && progress.percent < 100 || filter === 'open' && progress.percent === 100) continue;
      rows.push({ topic: node.topic, depth: node.depth });
    }
    return rows;
  }, [topics, searchIndex, collapsed, settledSearch, filter]);

  if (!exam) return <Empty title="Choose an exam workspace" description="Each exam has its own syllabus. Select one to organise its subjects and topics." action={w.exams[0] ? <Button label={`Open ${w.exams[0].name}`} onPress={() => w.setExamId(w.exams[0].id)} /> : <Button label="Add an exam" onPress={() => w.openExam()} />} />;
  function openEditor(mode: 'add' | 'rename' | 'move', topic?: Topic, parentId?: string | null) { setEditor({ mode, topic, parentId }); setTitle(mode === 'rename' ? topic!.title : ''); setImportance(topic?.importance ?? null); setParent(topic?.parentId || ''); setError(''); }
  function toggle(topic: Topic) { w.completeTopic(topic.id); }
  function saveEditor() { try {
    if (!editor || !editor.topic) return;
    const current = topics.find(t => t.id === editor.topic!.id);
    if (!current) throw new Error('This topic is no longer available. Reopen the form.');
    const mutations: Mutation[] = editor.mode === 'move' ? moveTopicMutations(topics, editor.topic.id, parent || null) : [{ kind: 'topic', data: { ...current, title: title.trim(), importance } }];
    if (editor.mode === 'move' && parent) { const node = topics.find(t => t.id === parent)!; mutations.push({ kind: 'topic', data: { ...node, completedOn: null } }); }
    if (editor.mode !== 'move' && !title.trim()) throw new Error('Give this topic a name.');
    store.commit(mutations, 'Syllabus updated.'); setEditor(null);
  } catch (e) { setError((e as Error).message); } }

  const changeTopic = (patch: Partial<Topic>, message: string) => selected && store.commit([{ kind: 'topic', data: { ...selected, ...patch } }], message);
  const reorder = (topic: Topic, direction: number) => { const siblings = children(topics, topic.parentId), index = siblings.findIndex(t => t.id === topic.id), other = siblings[index + direction]; if (other) store.commit(siblings.map((t, i) => ({ kind: 'topic', data: { ...t, order: t.id === topic.id ? index + direction : t.id === other.id ? index : i } })), 'Topic order updated.'); };
  const treeVisible = desktop || !selected;
  return <>
    <Heading eyebrow={exam.name} title="One topic at a time." subtitle="Give your preparation structure. Let the small wins add up." action={<View style={s.row}><Button label="Import outline" kind="secondary" icon={Upload} onPress={() => { setImportOpen(true); setError(''); }} /><Button label="Add subject" icon={Plus} onPress={() => openEditor('add')} /></View>} />
    <View style={[s.columns, { alignItems: 'flex-start' }]}>
      {treeVisible && <Panel style={{ flex: 1.4, minWidth: desktop ? 350 : '100%' }}><View style={s.row}><View style={{ flex: 1 }}><Input value={search} onChange={setSearch} placeholder="Find a subject or topic" /></View></View><Select label="Show" value={filter} options={[{ value: 'all', label: 'All topics' }, { value: 'open', label: 'Unfinished' }, { value: 'done', label: 'Completed' }]} onChange={setFilter} />
        {flat.map(({ topic, depth }) => { const kids = children(topics, topic.id), p = topicProgress(topics, topic.id); return <View key={topic.id} style={{ flexDirection: 'row', alignItems: 'center', paddingLeft: Math.min(depth, 5) * 13, borderTopWidth: 1, borderColor: C.line, backgroundColor: selected?.id === topic.id ? C.sageWash : 'transparent', borderRadius: 6, minHeight: 62 }}><Pressable accessibilityRole="button" accessibilityLabel={`${collapsed.has(topic.id) ? 'Expand' : 'Collapse'} ${topic.title}`} disabled={!kids.length} onPress={() => setCollapsed(current => { const next = new Set(current); next.has(topic.id) ? next.delete(topic.id) : next.add(topic.id); return next; })} style={{ width: 24, minHeight: 44, justifyContent: 'center' }}>{kids.length ? collapsed.has(topic.id) ? <ChevronRight size={14} color={C.muted} /> : <ChevronDown size={14} color={C.muted} /> : null}</Pressable><Checkbox checked={p.percent === 100} partial={p.done > 0 && p.percent < 100} label={`Complete ${topic.title}`} onPress={() => toggle(topic)} /><Pressable accessibilityRole="button" accessibilityLabel={`Open ${topic.title}`} onPress={() => setSelected(topic.id)} style={{ flex: 1, paddingVertical: 12, paddingRight: 12 }}><T style={{ fontSize: depth === 0 ? 14 : 13, fontFamily: depth === 0 ? F.medium : F.body, ...(p.percent === 100 ? { color: C.muted, textDecorationLine: 'line-through' as const } : {}) }}>{topic.title}</T><ImportanceBadge value={topic.importance} /><T muted style={{ fontSize: 10, marginTop: 4 }}>{kids.length ? `${p.done}/${p.total} complete` : topic.completedOn ? `Closed ${prettyDate(topic.completedOn)}` : confidenceNames[topic.confidence]}{depth > 5 ? ` · depth ${depth + 1}` : ''}</T></Pressable></View>; })}
        {!topics.length && <Empty title="A blank page, full of possibility." description="Add your first subject, or paste an indented outline to build the whole tree." action={<Button label="Add first subject" kind="secondary" onPress={() => openEditor('add')} />} />}
        {!!topics.length && !flat.length && <Empty title="No matching topics" description="Try a different search or completion filter." />}
      </Panel>}
      {selected && <Panel style={{ flex: 1, minWidth: desktop ? 270 : '100%' }}>
        {!desktop && <Button small label="All topics" icon={ArrowLeft} kind="ghost" onPress={() => setSelected(null)} style={{ alignSelf: 'flex-start', marginBottom: 16 }} />}
        <T style={s.eyebrow}>TOPIC DETAILS</T><T style={{ fontFamily: F.display, fontSize: 32 }}>{selected.title}</T><T muted style={{ fontSize: 11, lineHeight: 19, marginTop: 12 }}>{topicPath(topics, selected.parentId) || 'Subject'}</T>
        <View style={s.divider} /><View style={[s.row, { justifyContent: 'space-between', marginBottom: 13 }]}><T muted>Completion</T><T style={{ fontSize: 12 }}>{progress!.date ? prettyDate(progress!.date, true) : progress!.done ? `${progress!.done}/${progress!.total}` : 'Not completed'}</T></View><Meter value={progress!.percent} /><View style={[s.row, { justifyContent: 'space-between', marginTop: 18, marginBottom: 22 }]}><T muted>Study time</T><T>{duration(branchMinutes(sessions, topics, selected.id))}</T></View>
        <ImportancePicker value={selected.importance} onChange={importance => changeTopic({ importance }, 'Importance updated.')} /><ContentMeters topic={selected} topics={topics} />{!selected.parentId && <SubjectCounts key={`${selected.id}:${selected.lectureCount}:${selected.testCount}`} topic={selected} />}<Button label="Record lectures / tests" kind="secondary" onPress={() => w.editContent(selected.id)} style={{ marginVertical: 18 }} /><T muted style={{ fontSize: 12, marginBottom: 12 }}>Confidence · independent of completion</T><View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 7, marginBottom: 24 }}>{Object.entries(confidenceNames).map(([value, label]) => <Pressable key={value} accessibilityRole="button" accessibilityState={{ selected: selected.confidence === value }} onPress={() => changeTopic({ confidence: value as Confidence }, 'Confidence updated.')} style={{ width: '48%', minHeight: 44, padding: 10, borderWidth: 1, borderColor: selected.confidence === value ? C.sage : C.line, borderRadius: 7, backgroundColor: selected.confidence === value ? C.sageWash : C.bg }}><T style={{ fontSize: 12, color: selected.confidence === value ? C.sage : C.muted }}>{label}</T></Pressable>)}</View>
        <DateField label="Next revision" value={selected.reviewOn || ''} optional onChange={reviewOn => changeTopic({ reviewOn }, 'Revision date updated.')} />
        {selected.lastReviewedOn && <T muted style={{ fontSize: 11, marginBottom: 18 }}>Last revised {prettyDate(selected.lastReviewedOn)}</T>}
        <Button label="Log time here" onPress={() => w.openLog(selected.id)} /><Button label={selected.focusOn === today ? 'Remove from today’s focus' : 'Add to today’s focus'} kind="secondary" onPress={() => changeTopic({ focusOn: selected.focusOn === today ? '' : today }, 'Today’s focus updated.')} style={{ marginTop: 10 }} />
        <View style={s.divider} /><View style={{ gap: 6 }}><Button small label="Add child topic" kind="ghost" icon={Plus} onPress={() => openEditor('add', undefined, selected.id)} /><View style={[s.row, { flexWrap: 'wrap' }]}><IconButton icon={Edit3} label="Edit topic" onPress={() => openEditor('rename', selected)} /><IconButton icon={FolderInput} label="Move topic" onPress={() => openEditor('move', selected)} /><IconButton icon={ArrowUp} label="Move topic up" onPress={() => reorder(selected, -1)} /><IconButton icon={ArrowDown} label="Move topic down" onPress={() => reorder(selected, 1)} /><IconButton icon={Trash2} color={C.red} label="Delete branch" onPress={() => setRemove(selected)} /></View></View>
      </Panel>}
    </View>
    {editor?.mode === 'add' && <TopicBatchForm parentId={editor.parentId} onClose={() => setEditor(null)} onCreated={setSelected} />}
    {editor && editor.mode !== 'add' && <Dialog open title={editor.mode === 'move' ? 'Move this branch' : 'Edit topic'} onClose={() => setEditor(null)}>{editor.mode === 'move' ? <><T muted style={{ marginBottom: 16, lineHeight: 21 }}>Moving to another subject clears this branch's numbered lecture/test selections. Completion dates and study time stay attached.</T><Select label="New parent" value={parent} onChange={setParent} options={[{ value: '', label: 'Top level subject' }, ...topics.filter(t => canMove(topics, editor.topic!.id, t.id)).map(t => ({ value: t.id, label: topicPath(topics, t.id) }))]} /></> : <><Input autoFocus onSubmit={saveEditor} label="Name" value={title} onChange={setTitle} placeholder="Give this topic a name" /><ImportancePicker value={importance} onChange={setImportance} /></>}{error && <T style={{ color: C.red, marginBottom: 16 }}>{error}</T>}<Button label="Save topic" onPress={saveEditor} /></Dialog>}

    <Dialog open={importOpen} wide title="Bring your syllabus" onClose={() => setImportOpen(false)}><T muted style={{ marginBottom: 20, lineHeight: 22 }}>Paste an outline. Indent child topics with spaces or tabs. Each extra level becomes another branch.</T><Input label="Indented syllabus outline" value={outline} onChange={value => { setOutline(value); setPreview(null); }} multiline placeholder={'Mathematics\n  Calculus\n    Integration\n      Definite integrals\n        Properties'} />{error && <T style={{ color: C.red, marginBottom: 15 }}>{error}</T>}{preview && <View style={{ marginBottom: 20, padding: 16, backgroundColor: C.bg, borderRadius: 8 }}><T muted style={{ marginBottom: 12 }}>{preview.length} topics ready to add</T>{preview.slice(0, 100).map((line, i) => <T key={i} style={{ paddingLeft: Math.min(line.depth, 10) * 12, marginVertical: 4, fontSize: 12 }}>{line.title}</T>)}{preview.length > 100 && <T muted>…and {preview.length - 100} more</T>}</View>}<View style={s.row}><Button label="Preview structure" kind="secondary" onPress={() => { try { const parsed = parseOutline(outline); if (!parsed.length) throw new Error('Paste at least one topic.'); setPreview(parsed); setError(''); } catch (e) { setError((e as Error).message); } }} />{preview && <Button label="Import topics" onPress={() => { try { store.commit(outlineTopics(preview, exam.id, null, Date.now()).map(data => ({ kind: 'topic', data })), 'Syllabus imported.'); setImportOpen(false); setOutline(''); setPreview(null); } catch (e) { setError((e as Error).message); } }} />}</View></Dialog>
    <Confirm open={!!remove} title="Delete this branch?" message="This removes the topic and all its children. Study entries keep their recorded topic names. You can undo this action." label="Delete branch" danger onClose={() => setRemove(null)} onConfirm={() => { if (remove) store.commit([remove, ...descendants(topics, remove.id)].map(data => ({ kind: 'topic', data, deleted: true })), 'Branch deleted. Use Undo to recover it.'); setSelected(null); }} />

  </>;
}
