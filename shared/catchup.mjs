import { automaticPlanId, lectureCredit, scheduleDefaults } from './planner.mjs';
const add=(d,n)=>new Date(Date.parse(d+'T12:00:00Z')+n*86400000).toISOString().slice(0,10);
const week=d=>add(d,-((new Date(d+'T12:00:00Z').getUTCDay()+6)%7));
export function redistributeMissed(records,today,start,end,examId) {
  if(start<today||end<start||(Date.parse(end)-Date.parse(start))/86400000>730) throw Error('Choose a future catch-up window of up to two years.');
  const live=records.filter(r=>!r.deleted),exam=live.find(r=>r.kind==='exam'&&r.data.id===examId)?.data;
  if(!exam) throw Error('Choose a study goal.');
  const settings=exam.schedule||scheduleDefaults(today), sessions=live.filter(r=>r.kind==='session').map(r=>r.data),topics=live.filter(r=>r.kind==='topic').map(r=>r.data), tasks=live.filter(r=>r.kind==='task'&&r.data.examId===examId).map(r=>r.data), changes=[];
  const missed=tasks.filter(t=>t.lectureTarget&&t.date<today&&!t.locked&&!t.carriedOn).sort((a,b)=>a.date.localeCompare(b.date));
  for(const root of topics.filter(t=>t.examId===examId&&t.parentId===null)) {
    const sources=missed.filter(t=>t.topicId===root.id), backlog=sources.reduce((n,t)=>n+Math.max(0,t.lectureTarget-lectureCredit(sessions,root.id,t.date)-(t.carriedCount||0)),0);
    if(!backlog)continue;
    let outstanding=backlog;
    const credit=new Set(sessions.filter(s=>s.lectureSubjectId===root.id).flatMap(s=>s.lectureIds||[]));
    const byId=new Map(topics.map(t=>[t.id,t]));for(const t of topics) {let node=t;const seen=new Set();while(node?.parentId&&!seen.has(node.id)){seen.add(node.id);node=byId.get(node.parentId);}if(node?.id===root.id)for(const n of t.lectureIds||[])credit.add(n);}
    const reserved=tasks.filter(t=>t.topicId===root.id&&t.date>=today&&(!t.automatic||t.locked||lectureCredit(sessions,root.id,t.date)>0)).reduce((n,t)=>n+Math.max(0,t.lectureTarget-lectureCredit(sessions,root.id,t.date)),0);
    let remaining=root.lectureCount>0?Math.max(0,root.lectureCount-credit.size-reserved):Infinity;
    for(let date=start;date<=end&&outstanding>0;date=add(date,1)) {
      if(exam.date&&date>exam.date)break;
      if(!settings.weekdays.includes(new Date(date+'T12:00:00Z').getUTCDay())||settings.restDates.includes(date))continue;
      const existing=tasks.find(t=>t.topicId===root.id&&t.date===date&&t.lectureTarget);
      if(existing&&(!existing.automatic||existing.locked)||lectureCredit(sessions,root.id,date)>0)continue;
      const room=settings.dailyLimit?Math.max(0,settings.dailyLimit-tasks.filter(t=>t.date===date&&t.topicId!==root.id&&t.lectureTarget).reduce((n,t)=>n+t.lectureTarget,0)):10000;
      const pace=root.schedulePace||1;
      const quota=root.paceMode==='weekly'?Math.max(0,pace-tasks.filter(t=>t.topicId===root.id&&t.date!==date&&week(t.date)===week(date)&&t.lectureTarget).reduce((n,t)=>n+t.lectureTarget,0)):pace;
      const count=Math.min(outstanding,remaining,room,quota);if(!count)continue;
      const target=Math.min(Math.max(existing?.lectureTarget||0,count),remaining,room,quota);
      const data={...(existing||{}),id:existing?.id||automaticPlanId(root.id,date),examId,type:'plan',title:root.title+' lectures',topicId:root.id,date,done:false,lectureTarget:target,automatic:true,locked:true,originalDate:existing?.originalDate||date,originalTarget:existing?.originalTarget||target,notes:`Catch-up allocation from missed days. ${count} lectures reserved; original targets preserved.`};
      changes.push({kind:'task',data});if(existing)tasks[tasks.indexOf(existing)]=data;else tasks.push(data);
      outstanding-=count;remaining-=target;
    }
    let allocated=backlog-outstanding;
    for(const source of sources) {
      const needed=Math.max(0,source.lectureTarget-lectureCredit(sessions,root.id,source.date)-(source.carriedCount||0)),n=Math.min(needed,allocated);if(!n)continue;allocated-=n;
      changes.push({kind:'task',data:{...source,carriedCount:(source.carriedCount||0)+n,...(n===needed?{carriedOn:today}:{}),originalDate:source.originalDate||source.date,originalTarget:source.originalTarget||source.lectureTarget}});
    }
  }
  if(!changes.length)throw Error('No free capacity in this window. Increase pace or daily capacity, choose more study days, or unlock a future target.');
  return changes;
}
