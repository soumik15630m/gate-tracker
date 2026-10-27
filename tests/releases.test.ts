import {test} from 'node:test';
import assert from 'node:assert/strict';
import {hasNewWebRelease} from '../src/core/releases';
test('web release checks compare hashed running bundles and do not prompt for the same release',()=>{
  const current='/_expo/static/js/web/index-1234.js',next='/_expo/static/js/web/index-5678.js';
  assert.equal(hasNewWebRelease({version:'0123456789ab',bundles:[current]},[current]),false);
  assert.equal(hasNewWebRelease({version:'0123456789ab',bundles:[next]},[current]),true);
  assert.equal(hasNewWebRelease({version:'0123456789ab',bundles:[next]},[]),false);
});
test('unreadable or external release metadata never causes an update navigation',()=>{
  for(const value of [null,{}, {version:'x',bundles:[]},{version:'0123456789ab',bundles:['https://example.com/app.js']},{version:'0123456789ab',bundles:['/api/log.js']}]) assert.throws(()=>hasNewWebRelease(value,['/_expo/static/js/web/index-old.js']),/metadata/);
});
