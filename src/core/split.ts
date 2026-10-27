export function equalMinutes(total:number,count:number) {
  if(!Number.isInteger(total)||total<1||total>1440||!Number.isInteger(count)||count<1||count>20||count>total)throw Error('Give each entry at least one minute, with up to 20 entries and 24 hours in total.');
  return Array.from({length:count},(_,i)=>Math.floor(total/count)+(i<total%count?1:0));
}
export function validateSplit(total:number,minutes:number[]) {
  if(!Number.isInteger(total)||total<1||total>1440||!minutes.length||minutes.length>20||minutes.some(n=>!Number.isInteger(n)||n<1)||minutes.reduce((n,v)=>n+v,0)!==total)throw Error('Split minutes must add up exactly to this study block, with at least one minute per entry.');
}
