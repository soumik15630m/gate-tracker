// Pure scheduling shared by web and server. Android mirrors this algorithm.
const add = (d, n) => new Date(Date.parse(d + 'T12:00:00Z') + n * 86400000).toISOString().slice(0, 10);
const weekday = d => new Date(d + 'T12:00:00Z').getUTCDay();
const week = d => add(d, -((weekday(d) + 6) % 7));
export const scheduleDefaults = start => ({ start, weekdays: [1, 2, 3, 4, 5, 6], restDates: [], dailyLimit: null });
const stable = value => { let h = 14695981039346656037n; for (let i = 0; i < value.length; i++) h = BigInt.asUintN(64,(h ^ BigInt(value.charCodeAt(i))) * 1099511628211n); return h.toString(36); };
export const automaticPlanId = (subject, date) => `auto_${stable(subject)}_${date}`;
export const lectureCredit = (sessions, id, date) => new Set(sessions.filter(s => s.lectureSubjectId === id && (!date || s.date === date)).flatMap(s => s.lectureIds || [])).size;

export function buildSchedule(records, today) {
  const live = records.filter(r => !r.deleted), topics = live.filter(r => r.kind === 'topic').map(r => r.data), sessions = live.filter(r => r.kind === 'session').map(r => r.data), tasks = live.filter(r => r.kind === 'task').map(r => r.data);
  const byTopic = new Map(topics.map(t => [t.id,t])), rootFor = new Map(), content = new Map();
  for (const topic of topics) {
    let node=topic; const path=[], seen=new Set();
    while (node && !rootFor.has(node.id) && !seen.has(node.id)) { path.push(node.id); seen.add(node.id); if (!node.parentId) break; node=byTopic.get(node.parentId); }
    const root=rootFor.get(node?.id) || (node?.parentId == null ? node?.id : null);
    for (const id of path) rootFor.set(id,root);
    if(root) { const ids=content.get(root)||new Set(); for(const n of topic.lectureIds || []) ids.add(n); content.set(root,ids); }
  }
  for(const session of sessions) if(session.lectureSubjectId) { const ids=content.get(session.lectureSubjectId)||new Set(); for(const n of session.lectureIds || []) ids.add(n); content.set(session.lectureSubjectId,ids); }
  const writes = [], summaries = []; let liveTotal=live.length;
  for (const { data: exam } of live.filter(r => r.kind === 'exam')) {
    if (exam.archived) continue;
    const settings = exam.schedule || scheduleDefaults(today), end = settings.end && exam.date ? (settings.end < exam.date ? settings.end : exam.date) : settings.end || exam.date;
    const roots = topics.filter(t => t.examId === exam.id && t.parentId === null && t.schedulePace > 0).sort((a,b) => a.order - b.order || a.id.localeCompare(b.id));
    if (!roots.length) continue;
    const from = settings.start > today ? settings.start : today;
    const removed = new Set(tasks.filter(t => t.examId === exam.id && t.automatic && !t.locked && !t.done && t.date >= today && lectureCredit(sessions, t.topicId, t.date) === 0).map(t => t.id));
    liveTotal-=removed.size;
    const fixed = tasks.filter(t => t.lectureTarget != null && !removed.has(t.id));
    // An unset total is open-ended. Numbered credits remain bounded to known totals.
    const overbooked = new Map();
    const remaining = new Map(roots.map(root => {
      const ids = content.get(root.id) || new Set();
      const reserved = fixed.filter(t => t.topicId === root.id && t.date >= today).reduce((n,t) => n + Math.max(0, t.lectureTarget - lectureCredit(sessions, root.id, t.date)), 0);
      overbooked.set(root.id,root.lectureCount > 0 ? Math.max(0,ids.size + reserved - root.lectureCount) : 0);
      return [root.id, root.lectureCount > 0 ? Math.max(0, root.lectureCount - ids.size - reserved) : Infinity];
    }));
    const generated = [], weekly = new Map();
    if (end && end >= from && (Date.parse(end) - Date.parse(from)) / 86400000 <= 730) {
      const eligible = [];
      for (let date = from; date <= end; date = add(date,1)) if (settings.weekdays.includes(weekday(date)) && !settings.restDates.includes(date)) eligible.push(date);
      const weeks = new Map(); for (const date of eligible) { const k = week(date); weeks.set(k, [...(weeks.get(k) || []), date]); }
      for (const date of eligible) {
        let room = settings.dailyLimit > 0 ? Math.max(0, settings.dailyLimit - fixed.filter(t => t.examId === exam.id && t.date === date).reduce((n,t) => n + t.lectureTarget,0)) : 10000;
        const wanted = new Map();
        for (const root of roots) {
          if (fixed.some(t => t.topicId === root.id && t.date === date)) continue;
          let quota = root.schedulePace;
          if (root.paceMode === 'weekly') {
            const k = root.id + week(date), dates = weeks.get(week(date));
            const reserved = fixed.filter(t => t.topicId === root.id && week(t.date) === week(date)).reduce((n,t) => n + t.lectureTarget,0);
            const budget = Math.max(0, root.schedulePace - reserved - (weekly.get(k) || 0));
            const availableDays = dates.filter(d => d >= date && !fixed.some(t => t.topicId === root.id && t.date === d)).length;
            quota = Math.ceil(budget / Math.max(1,availableDays));
          }
          wanted.set(root.id, Math.min(quota, remaining.get(root.id)));
        }
        const allocated = new Map();
        // Round-robin avoids letting the first subject consume the daily cap.
        while (room > 0 && [...wanted.values()].some(n => n > 0)) for (const root of roots) if (room > 0 && wanted.get(root.id) > 0) { allocated.set(root.id,(allocated.get(root.id)||0)+1); wanted.set(root.id,wanted.get(root.id)-1); room--; }
        for (const root of roots) {
          const n = allocated.get(root.id); if (!n) continue;
          remaining.set(root.id,remaining.get(root.id)-n);
          const k = root.id + week(date); weekly.set(k,(weekly.get(k)||0)+n);
          const existing = tasks.find(t => t.topicId === root.id && t.date === date && removed.has(t.id));
          if(++liveTotal>20000) throw Object.assign(new Error('A workspace supports up to 20,000 records. Shorten the planning window or disable automatic scheduling for some subjects.'),{status:400});
          generated.push({ ...(existing || {}), id: existing?.id || automaticPlanId(root.id,date), examId: exam.id, title: root.title + ' lectures', type: 'plan', date, done: false, topicId: root.id, lectureTarget: n, automatic: true, locked: false, originalDate: existing?.originalDate || date, originalTarget: existing?.originalTarget || n });
        }
      }
    }
    for (const root of roots) summaries.push({ subjectId: root.id, overbooked: overbooked.get(root.id) || 0, remaining: Number.isFinite(remaining.get(root.id)) ? remaining.get(root.id) : null, missingDeadline: !end, rangeTooLong: !!end && (Date.parse(end) - Date.parse(from)) / 86400000 > 730, scheduled: generated.filter(t => t.topicId === root.id).reduce((n,t) => n+t.lectureTarget,0) });
    const byId = new Map(generated.map(t => [t.id,t]));
    for (const old of tasks.filter(t => removed.has(t.id))) if (!byId.has(old.id)) writes.push({ kind:'task', data:old, deleted:true });
    for (const data of generated) { const old = tasks.find(t => t.id === data.id); if (JSON.stringify(old) !== JSON.stringify(data)) writes.push({ kind:'task', data }); }
  }
  // Disabling a subject's pace removes only its editable future automatic targets.
  for (const old of tasks.filter(t => t.automatic && !t.locked && !t.done && t.date >= today)) if (!topics.some(t => t.id === old.topicId && t.schedulePace > 0) && lectureCredit(sessions,old.topicId,old.date) === 0) writes.push({ kind:'task', data:old, deleted:true });
  return { writes, summaries };
}
