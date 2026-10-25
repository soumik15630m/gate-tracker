import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, BackHandler, Platform, Pressable, ScrollView, View, useWindowDimensions } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { useFonts } from 'expo-font';
import { DMSans_400Regular } from '@expo-google-fonts/dm-sans/400Regular';
import { DMSans_500Medium } from '@expo-google-fonts/dm-sans/500Medium';
import { InstrumentSerif_400Regular } from '@expo-google-fonts/instrument-serif/400Regular';
import { ArrowUpRight, BookOpen, CalendarDays, ChartNoAxesCombined, Check, CircleHelp, CloudCheck, CloudOff, Ellipsis, House, Layers, Orbit, Plus, RotateCcw, Settings2, Timer, Trophy, WifiOff, X } from 'lucide-react-native';
import { Badge, Button, C, Dialog, F, IconButton, Select, T, s } from './ui';
import { StudyProvider, usePreferences, useStudy } from './store';
import { WorkspaceContext, type Screen, type Workspace } from './workspace';
import { completionMutations, dateKey, dayNumber, list, topicProgress, type Activity, type Exam, type Mock, type Session, type Topic } from './core/model';
import { CompletionForm, QuickRevisionForm } from './progress';
import { ExamForm, LogForm, MockForm } from './forms';
import { Stopwatch } from './Stopwatch';
import { timerStorageKey } from './core/stopwatch';
import Today from './screens/Today';
import Syllabus from './screens/Syllabus';
import StudyLog from './screens/StudyLog';
import Revision from './screens/Revision';
import Mocks from './screens/Mocks';
import Insights from './screens/Insights';
import Plan from './screens/Plan';
import Settings from './screens/Settings';
import { UpdateProvider, useAppUpdates } from './updates';
const pages: { name: Screen; icon: any }[] = [{ name: 'Today', icon: House }, { name: 'Syllabus', icon: BookOpen }, { name: 'Study log', icon: Timer }, { name: 'Revision', icon: RotateCcw }, { name: 'Mock tests', icon: Trophy }, { name: 'Insights', icon: ChartNoAxesCombined }, { name: 'Plan', icon: CalendarDays }];
function initialView(): Screen { if (Platform.OS !== 'web') return 'Today'; const value = new URLSearchParams(location.search).get('view'); return [...pages.map(p => p.name), 'Settings'].includes(value || '') ? value as Screen : 'Today'; }
function Shell() {
  const store = useStudy(), prefs = usePreferences(), { width } = useWindowDimensions(), desktop = Platform.OS === 'web' && width >= 960;
  const updates = useAppUpdates();
  const [completion, setCompletion] = useState<{ id: string; recordOnly?: boolean } | null>(null), [quickRevision, setQuickRevision] = useState(false);
  const [screen, setScreen] = useState<Screen>(initialView), [selectedExam, setSelectedExam] = useState(''), [more, setMore] = useState(false), [examForm, setExamForm] = useState<Exam | 'new' | null>(null), [logForm, setLogForm] = useState<{ topicId?: string; session?: Session; activity?: Activity } | null>(null), [mockForm, setMockForm] = useState<Mock | 'new' | null>(null), [now, setNow] = useState(new Date());
  const scroll = useRef<ScrollView>(null), exams = list<Exam>(store.data.records, 'exam'), examId = selectedExam === 'all' || exams.some(e => e.id === selectedExam) ? selectedExam : exams.find(e => !e.archived)?.id || exams[0]?.id || 'all', exam = exams.find(e => e.id === examId);
  const today = dateKey(now, prefs.timezone), topics = list<Topic>(store.data.records, 'topic').filter(t => examId === 'all' || t.examId === examId), sessions = list<Session>(store.data.records, 'session').filter(s => examId === 'all' || s.examId === examId), mocks = list<Mock>(store.data.records, 'mock').filter(m => examId === 'all' || m.examId === examId);
  const navigate = (page: Screen) => { setScreen(page); setMore(false); scroll.current?.scrollTo({ y: 0, animated: false }); if (Platform.OS === 'web') { const url = new URL(location.href); url.searchParams.set('view', page); history.pushState({ view: page }, '', url); } };
  const openLog = (topicId?: string, session?: Session, activity?: Activity) => exams.length ? setLogForm({ topicId, session, activity }) : setExamForm('new');
  useEffect(() => { const timer = setInterval(() => setNow(new Date()), 30000); return () => clearInterval(timer); }, []);
  useEffect(() => { const listener = BackHandler.addEventListener('hardwareBackPress', () => { if (screen !== 'Today') { setScreen('Today'); return true; } return false; }); return () => listener.remove(); }, [screen]);
  useEffect(() => { if (Platform.OS !== 'web') return; const pop = () => setScreen(initialView()); const key = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); openLog(); } if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) { e.preventDefault(); store.undo(); } }; window.addEventListener('popstate', pop); window.addEventListener('keydown', key); document.title = `${screen} — Studyspace`; return () => { window.removeEventListener('popstate', pop); window.removeEventListener('keydown', key); }; }, [screen, exams.length]);
  const workspace: Workspace = { examId, exam, exams, topics, sessions, mocks, today, desktop, navigate, setExamId: setSelectedExam, openLog, openExam: initial => setExamForm(initial || 'new'), openMock: initial => exams.length ? setMockForm(initial || 'new') : setExamForm('new'), completeTopic: id => { if (topicProgress(topics, id).percent === 100) store.commit(completionMutations(topics, id, today), 'Branch reopened. Recorded content released.'); else setCompletion({ id }); }, editContent: id => setCompletion({ id, recordOnly: true }), quickRevision: () => setQuickRevision(true) };
  const options = [{ value: 'all', label: 'All exams' }, ...exams.map(e => ({ value: e.id, label: e.name + (e.archived ? ' · archived' : '') }))];
  const syncLabel = !store.account ? 'Saved on this device' : store.status === 'syncing' ? 'Syncing changes' : store.status === 'synced' ? 'All changes synced' : 'Saved offline';
  const ActiveScreen = { Today, Syllabus, 'Study log': StudyLog, Revision, 'Mock tests': Mocks, Insights, Plan, Settings }[screen];
  if (!store.ready) return <View style={{ flex: 1, backgroundColor: C.bg, justifyContent: 'center', alignItems: 'center', gap: 20 }}><Orbit size={30} color={C.sage} /><T style={{ fontFamily: F.display, fontSize: 32 }}>A little space for progress.</T><ActivityIndicator color={C.sage} /></View>;
  return <WorkspaceContext.Provider value={workspace}><SafeAreaView style={{ flex: 1, backgroundColor: C.bg }} edges={['top', 'bottom']}><StatusBar style="light" backgroundColor="#000000" /><View style={{ flex: 1, flexDirection: 'row' }}>
    {desktop && <View style={{ width: 232, paddingHorizontal: 20, paddingTop: 34, paddingBottom: 22, borderRightWidth: 1, borderColor: C.line }}>
      <View style={[s.row, { gap: 10, marginBottom: 38, paddingHorizontal: 7 }]}><Orbit size={26} color={C.sage} strokeWidth={1.2} /><T style={{ fontFamily: F.medium, fontSize: 20, letterSpacing: -.7 }}>studyspace.</T></View>
      <T style={{ fontSize: 9, letterSpacing: 1.5, color: C.faint, marginBottom: 12, paddingHorizontal: 7 }}>YOUR WORKSPACE</T><Select value={examId} options={options} onChange={setSelectedExam} />
      <View style={{ gap: 6, marginTop: 10 }}>{pages.map(({ name, icon: Icon }) => <Pressable key={name} accessibilityRole="button" accessibilityLabel={`Navigate to ${name}`} accessibilityState={{ selected: screen === name }} onPress={() => navigate(name)} style={({ hovered }: any) => ({ flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13, paddingHorizontal: 13, borderRadius: 8, backgroundColor: screen === name ? C.sageWash : hovered ? C.surface : 'transparent' })}><Icon size={17} color={screen === name ? C.sage : C.muted} strokeWidth={1.5} /><T style={{ fontSize: 12, color: screen === name ? C.sage : C.muted, fontFamily: screen === name ? F.medium : F.body }}>{name}</T>{name === 'Revision' && topics.some(t => t.reviewOn && t.reviewOn <= today) && <View style={{ marginLeft: 'auto', width: 5, height: 5, borderRadius: 3, backgroundColor: C.gold }} />}</Pressable>)}</View>
      <View style={{ flex: 1, minHeight: 30 }} />
      {exam && <View style={{ padding: 16, backgroundColor: C.surface, borderRadius: 10, borderWidth: 1, borderColor: C.line, marginBottom: 20 }}><T muted style={{ fontSize: 10 }}>A LITTLE CLOSER EVERY DAY</T><T style={{ fontFamily: F.display, fontSize: 34, color: C.sage, marginVertical: 9 }}>{Math.max(0, dayNumber(exam.date) - dayNumber(today))}<T muted style={{ fontSize: 12 }}> days to go</T></T><T muted style={{ fontSize: 10 }}>{exam.name}</T></View>}
      <Pressable accessibilityRole="button" accessibilityLabel="Navigate to Settings" onPress={() => navigate('Settings')} style={[s.row, { padding: 13 }]}><Settings2 size={17} color={C.muted} /><T muted style={{ fontSize: 12 }}>Settings & backup</T>{store.data.conflicts.length > 0 && <Badge color={C.gold}>{store.data.conflicts.length}</Badge>}</Pressable>
      <T style={{ fontSize: 9, color: C.faint, paddingLeft: 13, marginTop: 15, letterSpacing: 1 }}>YOUR OWN PACE, ALWAYS.</T>
    </View>}
    <View style={{ flex: 1, minWidth: 0 }}>
      <View style={{ minHeight: desktop ? 76 : 65, paddingHorizontal: desktop ? 42 : 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12, borderBottomWidth: 1, borderColor: C.line }}>
        <View style={[s.row, { flex: 1 }]}>{!desktop && <Orbit size={22} color={C.sage} strokeWidth={1.3} />}<T style={{ fontSize: desktop ? 12 : 17, fontFamily: desktop ? F.body : F.medium, color: desktop ? C.muted : C.text }}>{desktop ? `${exam?.name || 'All exams'}  /  ${screen}` : 'studyspace.'}</T></View>
        {store.sample && <View><Badge>Sample data</Badge></View>}{desktop && <Pressable accessibilityRole="button" accessibilityLabel={syncLabel} onPress={store.account ? store.sync : () => navigate('Settings')} style={s.row}>{store.status === 'offline' ? <CloudOff size={14} color={C.gold} /> : <CloudCheck size={14} color={C.muted} />}<T muted style={{ fontSize: 10 }}>{syncLabel}</T></Pressable>}{!desktop && <IconButton icon={Settings2} label="Settings and sync" onPress={() => navigate('Settings')} />}
      </View>
      {!desktop && exams.length > 0 && <View style={{ paddingHorizontal: 20, paddingTop: 15 }}><Select value={examId} options={options} onChange={setSelectedExam} /></View>}
      {(store.error || store.data.conflicts.length > 0) && <Pressable accessibilityRole="button" onPress={() => navigate('Settings')} style={{ paddingHorizontal: desktop ? 42 : 20, paddingVertical: 12, backgroundColor: C.goldWash }}><T style={{ fontSize: 12, color: C.gold }}>{store.data.conflicts.length ? `${store.data.conflicts.length} sync changes need your choice. Open Settings.` : store.error}</T></Pressable>}
      {updates.pending && !examForm && !logForm && !mockForm && <View style={[s.row, { justifyContent: 'space-between', flexWrap: 'wrap', paddingHorizontal: 20, paddingVertical: 10, backgroundColor: C.sageWash }]}><T style={{ fontSize: 12 }}>An improvement is ready for your next launch.</T><Button small kind="ghost" label="Save & restart" onPress={updates.apply} /></View>}
      <Stopwatch key={timerStorageKey(store.account?.server, store.account?.user.id)} storageKey={timerStorageKey(store.account?.server, store.account?.user.id)} />
      <ScrollView ref={scroll} keyboardShouldPersistTaps="handled" contentContainerStyle={{ paddingHorizontal: desktop ? 42 : 20, paddingTop: desktop ? 36 : 20, paddingBottom: 44, width: '100%', maxWidth: 1300, alignSelf: 'center' }}><ActiveScreen /><View style={{ marginTop: 40, borderTopWidth: 1, borderColor: C.line, paddingTop: 18 }}><T muted style={{ fontSize: 10, letterSpacing: .5 }}>{store.sample ? 'ILLUSTRATIVE WORKSPACE · START FRESH IN SETTINGS' : 'A LITTLE FURTHER, EVERY DAY.'}</T></View></ScrollView>
      {!desktop && <View style={{ flexDirection: 'row', borderTopWidth: 1, borderColor: C.line, paddingTop: 8, paddingBottom: 4, backgroundColor: C.bg }}>{[...pages.slice(0, 4), { name: 'More' as const, icon: Ellipsis }].map(({ name, icon: Icon }) => { const selected = name === 'More' ? !pages.slice(0, 4).some(p => p.name === screen) : screen === name; return <Pressable key={name} accessibilityRole="button" accessibilityLabel={`Navigate to ${name}`} accessibilityState={{ selected }} onPress={() => name === 'More' ? setMore(true) : navigate(name)} style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: 6, minHeight: 53 }}><Icon size={20} strokeWidth={1.5} color={selected ? C.sage : C.faint} /><T style={{ fontSize: 9, color: selected ? C.sage : C.faint }}>{name === 'Study log' ? 'Log' : name}</T></Pressable>; })}</View>}
      {!!store.toast && <View style={{ position: 'absolute', bottom: desktop ? 20 : 76, left: 16, right: 16, alignItems: 'center' }}><View accessibilityLiveRegion="polite" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 12, maxWidth: 580, padding: 15, backgroundColor: '#1c261e', borderWidth: 1, borderColor: '#3d4d40', borderRadius: 10 }}><Check size={15} color={C.sage} /><T style={{ flex: 1, fontSize: 12 }}>{store.toast}</T><Button label="Undo" kind="ghost" small onPress={store.undo} /></View></View>}
    </View>
  </View>
  <Dialog open={more} onClose={() => setMore(false)} title="Your workspace"><View style={{ gap: 8 }}>{[...pages.slice(4), { name: 'Settings' as Screen, icon: Settings2 }].map(({ name, icon }) => <Button key={name} kind="secondary" label={name} icon={icon} onPress={() => navigate(name)} />)}<Button label="Add an exam" kind="ghost" icon={Plus} onPress={() => { setMore(false); setExamForm('new'); }} /><Button label="Undo last change" kind="ghost" icon={RotateCcw} onPress={() => { store.undo(); setMore(false); }} /></View></Dialog>
  {examForm && <ExamForm initial={examForm === 'new' ? undefined : examForm} onClose={() => setExamForm(null)} />}
  {logForm && <LogForm initial={logForm.session} topicId={logForm.topicId} initialActivity={logForm.activity} onClose={() => setLogForm(null)} />}
  {mockForm && <MockForm initial={mockForm === 'new' ? undefined : mockForm} onClose={() => setMockForm(null)} />}
  {completion && <CompletionForm key={completion.id} topicId={completion.id} recordOnly={completion.recordOnly} onClose={() => setCompletion(null)} />}
  {quickRevision && <QuickRevisionForm onClose={() => setQuickRevision(false)} />}
  </SafeAreaView></WorkspaceContext.Provider>;
}
export default function App() {
  const [fontsLoaded, fontError] = useFonts({ DMSans_400Regular, DMSans_500Medium, InstrumentSerif_400Regular });
  if (!fontsLoaded && !fontError) return <View style={{ flex: 1, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center' }}><ActivityIndicator color={C.sage} /></View>;
  return <SafeAreaProvider><StudyProvider><UpdateProvider><Shell /></UpdateProvider></StudyProvider></SafeAreaProvider>;
}
