import { test } from 'node:test';
import assert from 'node:assert/strict';
import { equalMinutes, validateSplit } from '../src/core/split';
import { checkpointTimers, registerTimerCheckpoint } from '../src/core/timerCheckpoint';
import { elapsedMs, readTimer } from '../src/core/stopwatch';

test('equal splits preserve every minute and custom allocations must match the block', () => {
  assert.deepEqual(equalMinutes(61,3),[21,20,20]);
  assert.deepEqual(equalMinutes(60,2),[30,30]);
  validateSplit(60,[10,35,15]);
  for(const values of [[60,60],[20,20],[-1,61],[0,60],[29.5,30.5]])assert.throws(()=>validateSplit(60,values));
  assert.throws(()=>equalMinutes(1,2));assert.throws(()=>equalMinutes(1441,2));
});

test('an update waits for timer writes, rejects failed persistence and unregisters old accounts', async () => {
  let complete!:()=>void,done=false;
  const pending=new Promise<void>(resolve=>{complete=resolve;});
  const remove=registerTimerCheckpoint('account-a',async()=>{await pending;done=true;});
  const saving=checkpointTimers();await Promise.resolve();assert.equal(done,false);
  complete();await saving;assert.equal(done,true);remove();
  const failure=registerTimerCheckpoint('account-b',async()=>{throw Error('disk unavailable');});
  await assert.rejects(checkpointTimers(),/disk unavailable/);failure();await checkpointTimers();
  const old=registerTimerCheckpoint('same-account',async()=>{throw Error('obsolete');});
  const fresh=registerTimerCheckpoint('same-account',async()=>{});old();await checkpointTimers();fresh();
});

test('restoring across a release retains the timer identity, running interval and paused duration', () => {
  const running=readTimer(JSON.stringify({id:'session_update',accumulatedMs:120000,startedAt:1000000}));
  assert.ok(running);assert.equal(running.id,'session_update');assert.equal(elapsedMs(running,1120000),240000);
  const paused=readTimer(JSON.stringify({...running,accumulatedMs:240000,startedAt:null}));
  assert.ok(paused);assert.equal(elapsedMs(paused,9000000),240000);
});
