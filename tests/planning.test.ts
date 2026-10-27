import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildSchedule, automaticPlanId } from '../shared/planner.mjs';
import { redistributeMissed } from '../shared/catchup.mjs';
import { validateEntity, validateRelationships, preserveTopicImportance } from '../shared/validation.mjs';
import { lectureForecast, practiceStats } from '../src/core/planning';
import type { Exam, Topic } from '../src/core/model';

const day='2026-10-27';
const exam:Exam={id:'exam',name:'Study goal',date:'2026-11-09',weeklyTarget:10,archived:false,schedule:{start:day,weekdays:[0,1,2,3,4,5,6],restDates:[]}};
const root:Topic={id:'networks',examId:exam.id,title:'Networks',parentId:null,order:0,completedOn:null,confidence:'unrated',lectureCount:8,schedulePace:2,paceMode:'daily'};
const record=(kind:any,data:any,deleted=false)=>({id:data.id,kind,data,deleted,version:0});
const seed=()=>[record('exam',exam),record('topic',root)];
function apply(records:any[],writes:any[]) { return [...new Map([...records,...writes.map(w=>record(w.kind,w.data,!!w.deleted))].map(r=>[r.id,r])).values()]; }
const plans=(records:any[])=>records.filter(r=>!r.deleted&&r.kind==='task').map(r=>r.data);

test('total edits automatically allocate only remaining lectures and generation is idempotent',()=>{
  const first=buildSchedule(seed(),day);assert.equal(first.writes.length,4);
  const saved=apply(seed(),first.writes);assert.equal(buildSchedule(saved,day).writes.length,0);
  const grown=apply(saved,[{kind:'topic',data:{...root,lectureCount:12}}]);
  const updated=apply(grown,buildSchedule(grown,day).writes);assert.equal(plans(updated).reduce((n,t)=>n+t.lectureTarget,0),12);
  const smaller=apply(updated,[{kind:'topic',data:{...root,lectureCount:5}}]);
  assert.equal(plans(apply(smaller,buildSchedule(smaller,day).writes)).reduce((n,t)=>n+t.lectureTarget,0),5);
});
test('daily capacity is shared fairly, rest dates excluded and deadline shortfalls reported',()=>{
  const records=[record('exam',{...exam,date:'2026-10-29',schedule:{...exam.schedule,dailyLimit:2,restDates:['2026-10-28']}}),record('topic',root),record('topic',{...root,id:'math',title:'Math',order:1})];
  const result=buildSchedule(records,day), targets=plans(apply(records,result.writes));
  assert.equal(targets.length,4);assert.ok(targets.every(t=>t.date!=='2026-10-28'&&t.lectureTarget===1));
  assert.equal(result.summaries.find(s=>s.subjectId===root.id)?.remaining,6);
});
test('weekly pace distributes exactly its budget over eligible days',()=>{
  const records=[record('exam',{...exam,date:'2026-11-01'}),record('topic',{...root,lectureCount:30,paceMode:'weekly',schedulePace:5})];
  const targets=plans(apply(records,buildSchedule(records,day).writes));
  assert.equal(targets.reduce((n,t)=>n+t.lectureTarget,0),5);
  assert.ok(targets.every(t=>t.lectureTarget===1));
});
test('past, manually locked and partially studied days survive recalculation without duplicate reservations',()=>{
  const initial=apply(seed(),buildSchedule(seed(),day).writes), tasks=plans(initial);
  const log={id:'log',examId:exam.id,topicId:root.id,topicPath:'Networks',date:day,minutes:30,activity:'Learning',note:'',lectureIds:[1],lectureSubjectId:root.id};
  const modified=apply(initial,[{kind:'task',data:{...tasks[1],locked:true}},{kind:'session',data:log},{kind:'topic',data:{...root,lectureCount:10,lectureIds:[1]}}]);
  const result=apply(modified,buildSchedule(modified,day).writes);
  assert.deepEqual(plans(result).find(t=>t.date===day),tasks[0]);
  assert.equal(plans(result).find(t=>t.id===tasks[1].id).locked,true);
  assert.equal(plans(result).reduce((n,t)=>n+t.lectureTarget,0),10);
  validateRelationships(result);
  const tomorrow=buildSchedule(result,'2026-10-28');assert.ok(!tomorrow.writes.some(w=>w.data.id===tasks[0].id));
});
test('unknown totals continue through end date and undated goals require an explicit horizon',()=>{
  const records=[record('exam',{...exam,date:'2026-10-29'}),record('topic',{...root,lectureCount:0})];
  const result=buildSchedule(records,day);assert.equal(result.writes.length,3);assert.equal(result.summaries[0].remaining,null);
  const undated=[record('exam',{...exam,date:'',goalType:'personal'}),record('topic',root)];
  assert.equal(buildSchedule(undated,day).summaries[0].missingDeadline,true);
  assert.ok(validateEntity('exam',undated[0].data));
  assert.equal(buildSchedule([record('exam',{...undated[0].data,schedule:{...exam.schedule,end:'2026-10-29'}}),record('topic',root)],day).writes.length,3);
});
test('validation rejects invalid preferences and question counts while preserving new fields from old clients',()=>{
  assert.equal(validateEntity('exam',{...exam,schedule:{...exam.schedule,weekdays:[]}}),false);
  assert.equal(validateEntity('topic',{...root,schedulePace:101}),false);
  const session={id:'log',examId:exam.id,topicId:root.id,topicPath:'',date:day,minutes:30,activity:'Practice',note:'',practice:{correct:8,incorrect:2,skipped:3}};
  assert.ok(validateEntity('session',session));assert.equal(validateEntity('session',{...session,practice:{correct:-1,incorrect:0,skipped:0}}),false);
  assert.deepEqual(practiceStats([session as any]),{correct:8,incorrect:2,skipped:3,attempted:10,accuracy:80});
  assert.deepEqual(preserveTopicImportance('topic',{...root,schedulePace:undefined,paceMode:undefined},root).schedulePace,2);
  assert.deepEqual(preserveTopicImportance('exam',{...exam,schedule:undefined},exam).schedule,exam.schedule);
  assert.deepEqual(preserveTopicImportance('session',{...session,practice:undefined},session).practice,session.practice);
});
test('forecast uses unique lecture history rather than arbitrary checklist sizes',()=>{
  const entry={id:'log',examId:exam.id,date:day,minutes:50,activity:'Learning',note:'',topicId:root.id,topicPath:'',lectureIds:[1,2,3,4],lectureSubjectId:root.id};
  const forecast=lectureForecast(root,[root],[entry as any],day);
  assert.equal(forecast.done,4);assert.equal(forecast.finish,'2026-11-24');
  assert.equal(lectureForecast({...root,lectureCount:0},[root],[entry as any],day).finish,null);
  assert.equal(automaticPlanId('networks',day),'auto_21xf82z53bjsy_2026-10-27');
});
test('deep syllabus scheduling remains iterative and does not inherit child importance',()=>{
  const topics=[record('topic',{...root,importance:3}),...Array.from({length:9999},(_,i)=>record('topic',{...root,id:'child'+i,parentId:i?'child'+(i-1):root.id,lectureCount:null,schedulePace:null,importance:5}))];
  const start=performance.now();const result=buildSchedule([record('exam',exam),...topics],day);
  assert.equal(result.writes.length,4);assert.ok(performance.now()-start<2000);
});
test('catch-up reserves selected days without changing the main deadline and keeps unallocated work overdue',()=>{
  const original=record('task',{id:'missed',examId:exam.id,title:'Missed lectures',type:'plan',date:'2026-10-26',done:false,topicId:root.id,lectureTarget:5});
  const records=[...seed(),original],initial=apply(records,buildSchedule(records,day).writes);
  const changes=redistributeMissed(initial,day,day,day,exam.id),saved=apply(initial,changes),rebuilt=apply(saved,buildSchedule(saved,day).writes);
  assert.ok(changes.every(c=>c.kind==='task'));
  assert.equal(plans(rebuilt).find(t=>t.date===day).locked,true);
  const missed=plans(rebuilt).find(t=>t.id==='missed');assert.equal(missed.lectureTarget,5);assert.equal(missed.carriedCount,2);assert.equal(missed.carriedOn,undefined);
  assert.equal(plans(rebuilt).filter(t=>t.date>=day).reduce((n,t)=>n+t.lectureTarget,0),8);
  validateRelationships(rebuilt);
});
test('automatic planning stops at the workspace limit instead of allocating an unbounded calendar',()=>{
  const records=[...seed(),...Array.from({length:19998},(_,i)=>record('task',{id:`note_${i}`,examId:exam.id,title:'Note',type:'inbox',date:'',done:false}))];
  assert.throws(()=>buildSchedule(records,day),/20,000/);
});
