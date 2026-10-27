import { randomBytes } from 'node:crypto';
import { validateEntity, validateRelationships } from '../shared/validation.mjs';
import { buildSchedule } from '../shared/planner.mjs';

const fail = (status, message) => Object.assign(new Error(message), { status });
export const emptyTimerState = () => ({ version: 0, timer: null, lastSavedId: null });
export const unpackTimer = row => row ? { version: row.version, timer: JSON.parse(row.state), lastSavedId: row.last_saved_id } : emptyTimerState();
export const timerReply = state => ({ ...state, serverNow: Date.now(), splitLogging: true });

// Called while holding the same per-user write lock as ordinary study sync.
export function transitionTimer(input, current, now = Date.now()) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw fail(400, 'Invalid timer request.');
  if (input.action === 'read') return current;
  if (!['start', 'import', 'pause', 'resume', 'finish', 'discard', 'log'].includes(input.action) || !Number.isSafeInteger(input.version) || input.version < 0 || !['Web', 'Android'].includes(input.device)) throw fail(400, 'Invalid timer action.');
  if (input.action === 'log' && input.id && input.id === current.lastSavedId) return current;
  if (input.version !== current.version) throw fail(409, 'The timer changed on another device. Your controls have been refreshed.');
  let timer = current.timer;
  if (input.action === 'start' || input.action === 'import') {
    if (timer) throw fail(409, 'A timer is already active on your account.');
    timer = { id: 'session_' + randomBytes(16).toString('hex'), accumulatedMs: 0, startedAt: now, origin: input.device, updatedBy: input.device, finished: false };
    if (input.action === 'import') {
      const old = input.timer;
      if (current.version !== 0 || !old || typeof old.id !== 'string' || !/^session_[a-zA-Z0-9:_-]{1,160}$/.test(old.id) || !Number.isSafeInteger(old.accumulatedMs) || old.accumulatedMs < 0 || old.accumulatedMs > 31536000000 || !(old.startedAt === null || Number.isSafeInteger(old.startedAt) && old.startedAt >= 0 && old.startedAt <= now + 86400000)) throw fail(400, 'The previous local stopwatch could not be imported.');
      timer = { ...timer, id: old.id, accumulatedMs: old.accumulatedMs, startedAt: old.startedAt };
    }
  } else {
    if (!timer || input.id !== timer.id) throw fail(409, 'This timer was already logged, discarded, or replaced on another device.');
    const accumulatedMs = timer.accumulatedMs + (timer.startedAt === null ? 0 : Math.max(0, now - timer.startedAt));
    if (input.action === 'discard') timer = null;
    else if (input.action === 'log') {
      if (!timer.finished || timer.startedAt !== null) throw fail(409, 'Finish the timer again before logging its time.');
      const entries=timerEntries(input);
      if(entries[0].id!==timer.id) throw fail(400,'Choose a valid stopwatch entry.');
      timer = null;
    } else if (input.action === 'resume') timer = { ...timer, startedAt: timer.startedAt ?? now, finished: false, updatedBy: input.device };
    else timer = { ...timer, accumulatedMs, startedAt: null, finished: input.action === 'finish', updatedBy: input.device };
  }
  return { version: current.version + 1, timer, lastSavedId: input.action === 'log' ? input.id : current.lastSavedId };
}

export function timerEntries(input) {
  const entries=input.entries===undefined ? [input.entry] : input.entries;
  if(!Array.isArray(entries)||!entries.length||entries.length>20||entries.some(e=>!validateEntity('session',e)||e.mockId!=null)||new Set(entries.map(e=>e.id)).size!==entries.length) throw fail(400,'Choose up to 20 valid study entries with unique IDs.');
  const total=entries.reduce((n,e)=>n+e.minutes,0);
  if(total>1440 || entries.some(e=>e.examId!==entries[0].examId || e.date!==entries[0].date) || (input.entries!==undefined && (!Number.isInteger(input.totalMinutes)||input.totalMinutes!==total))) throw fail(400,'Split minutes must add up exactly to one study block, on the same date and in the same workspace.');
  return entries;
}
export function timerLogWrites(input, records) {
  const entries=timerEntries(input),byId=new Map(records.map(r=>[r.id,r])),writes=new Map();
  const put=record=>{writes.set(record.id,record);byId.set(record.id,record);};
  const rootFor=record=>{let node=record;const seen=new Set();while(node?.data.parentId&&!seen.has(node.id)){seen.add(node.id);node=byId.get(node.data.parentId);}return node;};
  for(const original of entries) {
    const entry=entries.length>1 ? {...original,groupId:entries[0].id} : original;
    if(byId.has(entry.id)) throw fail(409,'This stopwatch or split entry already has a study log. Refresh your study log.');
    put({id:entry.id,kind:'session',data:entry,deleted:false});
    if(entry.lectureIds?.length) {
      const topic=byId.get(entry.topicId),subject=byId.get(entry.lectureSubjectId);
      if(!topic||topic.deleted||topic.kind!=='topic'||!subject||subject.deleted||subject.kind!=='topic'||subject.data.parentId!==null||subject.data.examId!==entry.examId) throw fail(400,'Select a valid subject or topic for completed lectures.');
      if(rootFor(topic)?.id!==subject.id) throw fail(400,'The selected lectures belong to a different subject.');
      const selected=new Set(entry.lectureIds);
      if([...byId.values()].some(r=>!r.deleted&&r.kind==='topic'&&(r.data.lectureIds||[]).some(n=>selected.has(n))&&rootFor(r)?.id===subject.id)) throw fail(409,'Some lectures were already completed. Choose remaining lectures.');
      put({...topic,data:{...topic.data,lectureIds:[...new Set([...(topic.data.lectureIds||[]),...entry.lectureIds])].sort((a,b)=>a-b)}});
    }
  }
  const reviews=input.reviews===undefined ? input.review==null ? [] : [{topicId:entries[0].topicId,reviewOn:input.review.reviewOn}] : input.reviews;
  if(!Array.isArray(reviews)||reviews.length>20) throw fail(400,'Choose valid next revision dates.');
  for(const review of reviews) {
    const entry=entries.find(e=>e.activity==='Revision'&&e.topicId===review?.topicId),topic=byId.get(review?.topicId);
    if(!entry||!topic||topic.deleted||topic.kind!=='topic') throw fail(400,'Choose a valid revision topic.');
    const data={...topic.data,lastReviewedOn:entry.date,reviewOn:review.reviewOn};
    if(!validateEntity('topic',data)) throw fail(400,'Choose a valid next review date.');
    put({...topic,data});
  }
  const timezone=[...byId.values()].find(r=>!r.deleted&&r.kind==='preferences')?.data.timezone||'UTC';
  const today=new Intl.DateTimeFormat('en-CA',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  for(const change of buildSchedule([...byId.values()],today).writes) put({...change,id:change.data.id,deleted:!!change.deleted});
  if([...byId.values()].filter(r=>!r.deleted).length>20000) throw fail(400,'A workspace supports up to 20,000 records.');
  try{validateRelationships([...byId.values()]);}catch(error){throw fail(400,error.message);}
  return [...writes.values()];
}
