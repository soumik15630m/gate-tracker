import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TopicSearchIndex, smartTextScore } from '../src/core/search';
import type { Topic } from '../src/core/model';
const node = (id: string, title: string, parentId: string | null = null, importance: number | null = null): Topic => ({ id, title, parentId, importance, examId: 'exam', order: 0, completedOn: null, confidence: 'unrated' });
test('deep-topic autocomplete supports prefixes, misspellings, reordered words, accents and breadcrumb context', () => {
  const index = new TopicSearchIndex([node('root', 'Computer Networks'), node('parent', 'Data communication', 'root'), node('leaf', 'Packet switching', 'parent'), node('circuit', 'Circuit switching', 'parent'), node('duplicate-root', 'Telecom'), node('duplicate', 'Packet switching', 'duplicate-root')]);
  assert.deepEqual(new Set(index.search('packet swithcing').map(r => r.topic.id)), new Set(['leaf', 'duplicate']));
  assert.deepEqual(new Set(index.search('swit pack').map(r => r.topic.id)), new Set(['leaf', 'duplicate']));
  assert.equal(index.search('computer packet')[0].topic.id, 'leaf');
  assert.deepEqual(new Set(index.search('pácket switching').map(r => r.topic.id)), new Set(['leaf', 'duplicate']));
  assert.equal(index.search('telecom packet')[0].topic.id, 'duplicate');
  assert.equal(index.path('leaf'), 'Computer Networks / Data communication / Packet switching');
  assert.ok(smartTextScore('Packet switching', 'packet swithcing') >= 0);
  assert.equal(smartTextScore('Circuit switching', 'packet switching'), -1);
});
test('granular filters keep five-star children discoverable under three-star parents', () => {
  const root = node('root', 'Networking', null, 3), branch = node('branch', 'Packets', 'root', 2), leaf = node('leaf', 'Packet switching', 'branch', 5), na = node('na', 'Not yet rated', 'root');
  const index = new TopicSearchIndex([root, branch, leaf, na]);
  assert.deepEqual(index.search('', { importance: '5', source: 'self' }).map(r => r.topic.id), ['leaf']);
  assert.deepEqual(index.search('', { importance: '3', level: 'subject' }).map(r => r.topic.id), ['root']);
  assert.deepEqual(new Set(index.search('', { importance: '3', level: 'leaf', source: 'subject' }).map(r => r.topic.id)), new Set(['leaf', 'na']));
  assert.deepEqual(index.search('', { importance: '2', source: 'parent', level: 'leaf' }).map(r => r.topic.id), ['leaf']);
  assert.deepEqual(index.search('', { level: 'parent' }).map(r => r.topic.id), ['root', 'branch']);
  assert.deepEqual(index.search('', { importance: 'na' }).map(r => r.topic.id), ['na']);
  assert.equal(leaf.importance, 5); assert.equal(root.importance, 3);
});
test('search indexing supports ten thousand levels without constructing every full path', () => {
  const topics = Array.from({ length: 10000 }, (_, i) => node(`deep-${i}`, i === 9999 ? 'Packet switching' : `Level ${i}`, i ? `deep-${i - 1}` : null));
  const start = performance.now(), index = new TopicSearchIndex(topics), result = index.search('packet swithcing');
  assert.equal(result[0].topic.id, 'deep-9999'); assert.equal(result[0].depth, 9999);
  console.log(`10,000-level search index + typo search: ${Math.round(performance.now() - start)}ms`);
});
