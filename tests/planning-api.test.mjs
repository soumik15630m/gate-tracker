import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createStudyServer} from '../server/index.mjs';

test('automatic scheduling, actual practice, legacy clients and separate accounts round trip atomically',async t=>{
  const server=process.env.TEST_CLOUD_URL?null:createStudyServer({database:':memory:'});
  if(server){server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}));}
  const base=process.env.TEST_CLOUD_URL||`http://127.0.0.1:${server.address().port}`;
  let token;
  async function post(path,input){const r=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(input)});return {status:r.status,body:await r.json()};}
  async function register(){const r=await post('/api/auth/register',{email:`studyspace-test-plan-${randomUUID()}@example.test`,password:'planning-long-test-password'});assert.equal(r.status,200);token=r.body.token;return token;}
  const firstToken=await register();
  const change=(kind,data,baseVersion=0)=>({id:data.id,kind,data,baseVersion,mutationId:randomUUID()});
  const sync=changes=>post('/api/sync',{cursor:0,changes});
  const day=new Date().toISOString().slice(0,10),end=new Date(Date.parse(day+'T12:00:00Z')+14*86400000).toISOString().slice(0,10);
  const exam={id:'exam',name:'Learning',date:end,weeklyTarget:10,archived:false,goalType:'course',schedule:{start:day,weekdays:[0,1,2,3,4,5,6],restDates:[],dailyLimit:3}};
  const root={id:'root',examId:exam.id,parentId:null,title:'Networks',order:0,completedOn:null,confidence:'unrated',lectureCount:8,schedulePace:2,paceMode:'daily'};
  let result=await sync([change('exam',exam),change('topic',root)]);assert.equal(result.status,200,JSON.stringify(result.body));
  let records=result.body.records,targets=records.filter(r=>r.kind==='task'&&!r.deleted);assert.equal(targets.length,4);
  const locked=targets[1];result=await sync([change('task',{...locked.data,locked:true},locked.version)]);assert.equal(result.status,200);
  const before=records.find(r=>r.id===root.id);result=await sync([change('topic',{...root,lectureCount:12},before.version)]);assert.equal(result.status,200,JSON.stringify(result.body));
  targets=result.body.records.filter(r=>r.kind==='task'&&!r.deleted);assert.equal(targets.reduce((n,r)=>n+r.data.lectureTarget,0),12);assert.equal(targets.find(r=>r.id===locked.id).data.locked,true);
  const started=await post('/api/timer',{action:'start',version:0,device:'Web'});assert.equal(started.status,200);
  const stopped=await post('/api/timer',{action:'finish',version:started.body.version,id:started.body.timer.id,device:'Android'});
  const entry={id:stopped.body.timer.id,examId:exam.id,topicId:root.id,topicPath:'Networks',date:day,minutes:45,activity:'Practice',note:'',lectureIds:[1,2],lectureSubjectId:root.id,practice:{correct:8,incorrect:2,skipped:3}};
  assert.equal((await post('/api/timer',{action:'log',id:entry.id,version:stopped.body.version,device:'Android',entry})).status,200);
  records=(await sync([])).body.records;assert.deepEqual(records.find(r=>r.id===entry.id).data.practice,entry.practice);
  const current=records.find(r=>r.id===root.id),older={...current.data};delete older.schedulePace;delete older.paceMode;
  result=await sync([change('topic',older,current.version)]);assert.equal(result.status,200);assert.equal(result.body.records.find(r=>r.id===root.id).data.schedulePace,2);
  const originalRoot=result.body.records.find(r=>r.id===root.id);
  const identityB=await register();assert.notEqual(identityB,firstToken);assert.equal((await sync([])).body.records.length,0);
  assert.equal((await sync([change('exam',exam),change('topic',{...root,lectureCount:4})])).status,200);
  token=firstToken;assert.equal((await sync([])).body.records.find(r=>r.id===root.id).data.lectureCount,12);
  const rejected=await sync([change('topic',{...originalRoot.data,lectureCount:1},originalRoot.version)]);assert.equal(rejected.status,400);
  assert.equal((await sync([])).body.records.find(r=>r.id===root.id).data.lectureCount,12);
  // Completing the rest today must persist removals of unneeded future automatic slots.
  const timerState=(await post('/api/timer',{action:'read'})).body;
  const again=(await post('/api/timer',{action:'start',version:timerState.version,device:'Web'})).body;
  const finished=(await post('/api/timer',{action:'finish',version:again.version,id:again.timer.id,device:'Web'})).body;
  const remaining={...entry,id:finished.timer.id,lectureIds:Array.from({length:10},(_,i)=>i+3)};
  const completed=await post('/api/timer',{action:'log',id:remaining.id,version:finished.version,device:'Web',entry:remaining});
  assert.equal(completed.status,200,JSON.stringify(completed.body));
  const finalRecords=(await sync([])).body.records;
  assert.ok(finalRecords.some(r=>r.kind==='task'&&r.deleted),'timer writes must persist automatic-plan tombstones');
  assert.equal(finalRecords.filter(r=>r.kind==='task'&&!r.deleted&&r.data.date>day&&!r.data.locked).length,0);
});
