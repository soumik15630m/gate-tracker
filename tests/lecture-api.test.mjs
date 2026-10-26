import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createStudyServer } from '../server/index.mjs';

test('lecture logging and calendar targets round trip with server-enforced subject totals', async t => {
  const server = process.env.TEST_CLOUD_URL ? null : createStudyServer({ database: ':memory:' });
  if (server) { server.listen(0,'127.0.0.1'); await once(server,'listening'); t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); })); }
  const base = process.env.TEST_CLOUD_URL || `http://127.0.0.1:${server.address().port}`;
  let token;
  async function post(path, input) { const r = await fetch(base+path,{ method:'POST',headers:{'Content-Type':'application/json',...(token ? {Authorization:`Bearer ${token}`} : {})},body:JSON.stringify(input)}); return {status:r.status,body:await r.json()}; }
  const auth = await post('/api/auth/register',{email:`studyspace-test-lectures-${randomUUID()}@example.test`,password:'lecture-test-long-password'}); assert.equal(auth.status,200); token=auth.body.token;
  const change = (kind,data,version=0) => ({id:data.id,kind,data,baseVersion:version,mutationId:randomUUID()});
  const sync = changes => post('/api/sync',{cursor:0,changes});
  const exam = {id:'exam',name:'Exam',date:'2027-02-07',weeklyTarget:10,archived:false};
  const root = {id:'root',examId:'exam',parentId:null,title:'Networks',order:0,completedOn:null,confidence:'unrated',lectureCount:3};
  const leaf = {...root,id:'leaf',parentId:'root',title:'Packet switching',lectureCount:null};
  const plan = {id:'target',examId:'exam',topicId:root.id,title:'Daily lectures',type:'plan',date:'2026-10-26',done:false,lectureTarget:2};
  const seeded=await sync([change('exam',exam),change('topic',root),change('topic',leaf),change('topic',{...root,id:'other-root'}),change('task',plan)]); assert.equal(seeded.status,200);
  const oversized=await sync([change('task',{...plan,id:'bad-target',date:'2026-10-27',lectureTarget:4})]); assert.equal(oversized.status,400);
  const started=await post('/api/timer',{action:'start',version:0,device:'Web'}); assert.equal(started.status,200);
  const finished=await post('/api/timer',{action:'finish',version:started.body.version,id:started.body.timer.id,device:'Android'}); assert.equal(finished.status,200);
  const entry={id:finished.body.timer.id,examId:'exam',topicId:leaf.id,topicPath:'Networks / Packet switching',date:plan.date,minutes:45,activity:'Learning',note:'',lectureSubjectId:root.id,lectureIds:[4]};
  const log=input=>post('/api/timer',{action:'log',version:finished.body.version,id:entry.id,device:'Android',entry:input});
  assert.equal((await log(entry)).status,400);
  assert.equal((await sync([change('session',{...entry,id:'wrong-subject',lectureIds:[1],lectureSubjectId:'other-root'})])).status,400);
  const kept=await post('/api/timer',{action:'read'}); assert.equal(kept.body.timer.id,entry.id);
  const saved=await log({...entry,lectureIds:[1,2]}); assert.equal(saved.status,200); assert.equal(saved.body.timer,null);
  const records=(await sync([])).body.records;
  assert.deepEqual(records.find(r=>r.id===entry.id).data.lectureIds,[1,2]); assert.deepEqual(records.find(r=>r.id===leaf.id).data.lectureIds,[1,2]);
  assert.equal(records.find(r=>r.id===plan.id).data.lectureTarget,2); assert.equal(records.find(r=>r.id===leaf.id).data.completedOn,null);
  const duplicate=await sync([change('session',{...entry,id:'another-entry',lectureIds:[2]})]); assert.equal(duplicate.status,400);
  assert.equal((await sync([])).body.records.filter(r=>r.kind==='session').length,1);
  const legacy=records.find(r=>r.id===entry.id); const older={...legacy.data}; delete older.lectureIds; delete older.lectureSubjectId;
  const compatible=await sync([change('session',older,legacy.version)]); assert.equal(compatible.status,200); assert.deepEqual(compatible.body.records.find(r=>r.id===entry.id).data.lectureIds,[1,2]);
});
