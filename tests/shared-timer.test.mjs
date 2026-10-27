import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { createStudyServer } from '../server/index.mjs';

test('shared stopwatch: bidirectional control, isolation, races, atomic logging and retries', async t => {
  const server = process.env.TEST_CLOUD_URL ? null : createStudyServer({ database: ':memory:' });
  if (server) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); })); }
  const base = process.env.TEST_CLOUD_URL || `http://127.0.0.1:${server.address().port}`;
  async function post(path, input, token) {
    const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(input) });
    return { status: response.status, body: await response.json(), cache: response.headers.get('cache-control') };
  }
  const password = 'timer-test-password-long', email = `studyspace-test-timer-${randomUUID()}@example.test`;
  const web = await post('/api/auth/register', { email, password });
  const phone = await post('/api/auth/login', { email, password });
  const other = await post('/api/auth/register', { email: `studyspace-test-other-timer-${randomUUID()}@example.test`, password });
  assert.equal(web.status, 200); assert.equal(phone.status, 200); assert.equal(other.status, 200);
  const timer = (auth, input) => post('/api/timer', input, auth.body.token);
  const read = auth => timer(auth, { action: 'read' });
  const sync = (auth, changes = []) => post('/api/sync', { cursor: 0, changes }, auth.body.token);
  assert.equal((await post('/api/timer', { action: 'read' })).status, 401);
  let state = (await read(web)).body;
  assert.equal(state.version, 0); assert.equal(state.timer, null);
  await t.test('two devices starting together create exactly one account timer', async () => {
    const results = await Promise.all([['Web', web], ['Android', phone]].map(([device, auth]) => timer(auth, { action: 'start', version: 0, device })));
    assert.deepEqual(results.map(r => r.status).sort(), [200, 409]);
    state = (await read(phone)).body;
    assert.ok(['Web', 'Android'].includes(state.timer.origin)); assert.ok(state.timer.startedAt <= state.serverNow);
    assert.equal(results.find(r => r.status === 409).body.state.timer.id, state.timer.id);
    const foreign = await timer(other, { action: 'pause', version: state.version, id: state.timer.id, device: 'Android', userId: web.body.user.id });
    assert.equal(foreign.status, 409); assert.equal(foreign.body.state.timer, null);
    assert.equal((await read(other)).body.version, 0);
    assert.equal((await read(web)).body.timer.id, state.timer.id);
  });
  await t.test('phone pauses web timer and web resumes phone state using server timestamps', async () => {
    const paused = await timer(phone, { action: 'pause', version: state.version, id: state.timer.id, device: 'Android' });
    assert.equal(paused.status, 200); state = paused.body;
    assert.equal(state.timer.startedAt, null); assert.equal(state.timer.updatedBy, 'Android');
    const fromWeb = await read(web); assert.deepEqual(fromWeb.body.timer, state.timer); assert.match(fromWeb.cache, /no-store/);
    const resumed = await timer(web, { action: 'resume', version: state.version, id: state.timer.id, device: 'Web' });
    assert.equal(resumed.status, 200); assert.equal(resumed.body.timer.accumulatedMs, state.timer.accumulatedMs);
    state = resumed.body; assert.equal(state.timer.updatedBy, 'Web'); assert.ok(state.timer.startedAt <= state.serverNow);
    const stale = await timer(phone, { action: 'discard', version: state.version - 1, id: state.timer.id, device: 'Android' });
    assert.equal(stale.status, 409); assert.equal(stale.body.state.version, state.version);
  });
  await t.test('finishing does not log time and a failed log preserves timer and all records', async () => {
    const finished = await timer(phone, { action: 'finish', version: state.version, id: state.timer.id, device: 'Android' });
    assert.equal(finished.status, 200); state = finished.body;
    assert.equal(state.timer.finished, true); assert.equal(state.timer.startedAt, null);
    assert.equal((await sync(web)).body.records.length, 0);
    const bad = await timer(web, { action: 'log', version: state.version, id: state.timer.id, device: 'Web', entry: { id: state.timer.id, examId: 'missing', topicId: null, topicPath: '', date: '2026-10-25', minutes: 25, activity: 'Learning', note: '' } });
    assert.notEqual(bad.status, 200); assert.equal((await read(phone)).body.version, state.version);
    assert.equal((await sync(web)).body.records.length, 0);
  });
  await t.test('either device logs once atomically and a retried save cannot duplicate or overwrite it', async () => {
    const exam = { id: 'timer-exam', name: 'Timer exam', date: '2027-02-07', weeklyTarget: 10, archived: false };
    const seeded = await sync(web, [{ id: exam.id, kind: 'exam', data: exam, mutationId: 'timer-exam-seed', baseVersion: 0 }]);
    assert.equal(seeded.status, 200);
    const entry = { id: state.timer.id, examId: exam.id, topicId: null, topicPath: 'Whole exam', date: '2026-10-25', minutes: 25, activity: 'Learning', note: 'Shared timer' };
    const input = { action: 'log', version: state.version, id: entry.id, device: 'Android', entry };
    const saved = await timer(phone, input); assert.equal(saved.status, 200); assert.equal(saved.body.timer, null);
    const retry = await timer(web, { ...input, device: 'Web', entry: { ...entry, minutes: 80 } });
    assert.equal(retry.status, 200); assert.equal(retry.body.version, saved.body.version);
    const records = (await sync(phone)).body.records;
    assert.equal(records.filter(r => r.kind === 'session').length, 1); assert.equal(records.find(r => r.kind === 'session').data.minutes, 25);
    assert.equal((await read(web)).body.timer, null);
    assert.equal((await read(other)).body.lastSavedId, null);
  });
  await t.test('a resumed timer invalidates an older finish form; one save wins against simultaneous discard', async () => {
    state = (await timer(phone, { action: 'start', version: (await read(phone)).body.version, device: 'Android' })).body;
    assert.equal(state.timer.origin, 'Android');
    state = (await timer(web, { action: 'finish', version: state.version, id: state.timer.id, device: 'Web' })).body;
    const finished = state;
    state = (await timer(phone, { action: 'resume', version: state.version, id: state.timer.id, device: 'Android' })).body;
    const entry = { id: state.timer.id, examId: 'timer-exam', topicId: null, topicPath: '', date: '2026-10-25', minutes: 5, activity: 'Learning', note: '' };
    assert.equal((await timer(web, { action: 'log', version: finished.version, id: entry.id, device: 'Web', entry })).status, 409);
    state = (await timer(phone, { action: 'finish', version: state.version, id: entry.id, device: 'Android' })).body;
    const race = await Promise.all([timer(web, { action: 'log', version: state.version, id: entry.id, device: 'Web', entry }), timer(phone, { action: 'discard', version: state.version, id: entry.id, device: 'Android' })]);
    assert.deepEqual(race.map(r => r.status).sort(), [200, 409]);
    assert.equal((await read(web)).body.timer, null);
  });
  await t.test('split logging saves all allocations and lectures atomically with safe retries', async () => {
    const topics=[
      {id:'split-root-a',examId:'timer-exam',parentId:null,title:'Networks',lectureCount:3},
      {id:'split-parent',examId:'timer-exam',parentId:'split-root-a',title:'Switching'},
      {id:'split-deep',examId:'timer-exam',parentId:'split-parent',title:'Packet switching'},
      {id:'split-second',examId:'timer-exam',parentId:'split-root-a',title:'Routing'},
      {id:'split-root-b',examId:'timer-exam',parentId:null,title:'Mathematics',lectureCount:2}
    ].map((topic,i)=>({...topic,order:i,confidence:'unrated',note:'',completedOn:null,reviewOn:null,focusOn:null}));
    assert.equal((await sync(web,topics.map(data=>({id:data.id,kind:'topic',data,baseVersion:0,mutationId:'seed-'+data.id})))).status,200);
    state=(await timer(web,{action:'start',version:(await read(web)).body.version,device:'Web'})).body;
    state=(await timer(phone,{action:'finish',version:state.version,id:state.timer.id,device:'Android'})).body;
    assert.equal(state.splitLogging,true);
    const baseEntry={examId:'timer-exam',date:'2026-10-26',activity:'Revision',note:'Split study block',minutes:20};
    const entries=[
      {...baseEntry,id:state.timer.id,topicId:'split-deep',topicPath:'Networks / Switching / Packet switching',lectureSubjectId:'split-root-a',lectureIds:[1],practice:{correct:4,incorrect:1,skipped:2}},
      {...baseEntry,id:'session_split_b',topicId:'split-root-b',topicPath:'Mathematics',lectureSubjectId:'split-root-b',lectureIds:[1]},
      {...baseEntry,id:'session_split_a2',topicId:'split-second',topicPath:'Networks / Routing',lectureSubjectId:'split-root-a',lectureIds:[1]}
    ];
    const input={action:'log',version:state.version,id:state.timer.id,device:'Web',entries,totalMinutes:60,reviews:entries.map(e=>({topicId:e.topicId,reviewOn:'2026-11-05'}))};
    assert.equal((await timer(web,{...input,totalMinutes:59})).status,400);
    assert.equal((await timer(web,input)).status,409);
    assert.equal((await read(phone)).body.version,state.version);
    assert.equal((await sync(web)).body.records.filter(r=>entries.some(e=>e.id===r.id)).length,0);
    entries[2].lectureIds=[2];
    const saved=await timer(phone,{...input,device:'Android'});assert.equal(saved.status,200);assert.equal(saved.body.timer,null);
    const retry=await timer(web,input);assert.equal(retry.status,200);assert.equal(retry.body.version,saved.body.version);
    const records=(await sync(web)).body.records,logs=records.filter(r=>entries.some(e=>e.id===r.id));
    assert.equal(logs.length,3);assert.equal(logs.reduce((sum,r)=>sum+r.data.minutes,0),60);
    assert.ok(logs.every(r=>r.data.groupId===entries[0].id));
    assert.deepEqual(records.find(r=>r.id==='split-deep').data.lectureIds,[1]);
    assert.deepEqual(records.find(r=>r.id==='split-second').data.lectureIds,[2]);
    assert.deepEqual(records.find(r=>r.id==='split-root-b').data.lectureIds,[1]);
    assert.equal(records.find(r=>r.id==='split-deep').data.reviewOn,'2026-11-05');
    assert.equal(logs.find(r=>r.id===entries[0].id).data.practice.correct,4);
    assert.equal((await sync(other)).body.records.filter(r=>entries.some(e=>e.id===r.id)).length,0);
  });
  await t.test('the previous local timer migrates once without resetting its accumulated time', async () => {
    const legacy = { id: 'session_legacy', accumulatedMs: 90000, startedAt: null };
    const imported = await timer(other, { action: 'import', version: 0, device: 'Android', timer: legacy });
    assert.equal(imported.status, 200); assert.equal(imported.body.timer.accumulatedMs, 90000);
    assert.equal(imported.body.timer.origin, 'Android'); assert.equal(imported.body.timer.startedAt, null);
    const cleared = await timer(other, { action: 'discard', version: imported.body.version, id: legacy.id, device: 'Web' });
    assert.equal(cleared.status, 200);
    assert.equal((await timer(other, { action: 'import', version: cleared.body.version, device: 'Android', timer: legacy })).status, 400);
    assert.equal((await read(other)).body.timer, null);
  });

});
