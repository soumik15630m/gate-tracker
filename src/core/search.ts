import { matchesImportance, type Topic } from './model';

export const normalizeSearch = (text: string) => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
function near(a: string, b: string, budget: number) {
  if (Math.abs(a.length - b.length) > budget || a.length > 40 || b.length > 40) return false;
  let previousPrevious: number[] = [], previous = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const row = [i];
    for (let j = 1; j <= b.length; j++) {
      row[j] = Math.min(row[j - 1] + 1, previous[j] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) row[j] = Math.min(row[j], previousPrevious[j - 2] + 1);
    }
    previousPrevious = previous; previous = row;
  }
  return previous[b.length] <= budget;
}
export function tokenScore(text: string, token: string): number {
  if (text === token) return 100;
  const words = text.split(' ');
  if (words.some(word => word.startsWith(token))) return 85;
  if (text.includes(token)) return 65;
  if (token.length >= 4 && words.some(word => near(word, token, token.length >= 8 ? 2 : 1))) return 35;
  return -1;
}
export function smartTextScore(text: string, query: string): number {
  const normalized = normalizeSearch(text), tokens = normalizeSearch(query).slice(0, 120).split(' ').filter(Boolean).slice(0, 8);
  const scores = tokens.map(token => tokenScore(normalized, token));
  return scores.some(score => score < 0) ? -1 : scores.reduce((a, b) => a + b, 0);
}
export const topicLevels = [{ value: 'all', label: 'All topic levels' }, { value: 'subject', label: 'Subjects only' }, { value: 'parent', label: 'Parent topics · have children' }, { value: 'leaf', label: 'Final subtopics · no children' }];
export interface TopicFilter { importance?: string; level?: string; source?: string }
/** Paths are assembled for displayed suggestions only; deeply nested trees stay linear to index. */
export class TopicSearchIndex {
  readonly byId: Map<string, Topic>;
  readonly children = new Map<string | null, Topic[]>();
  readonly nodes: { topic: Topic; depth: number; root: Topic; text: string }[] = [];
  readonly roots = new Map<string, Topic>();
  constructor(topics: Topic[]) {
    this.byId = new Map(topics.map(t => [t.id, t]));
    for (const topic of topics) { const siblings = this.children.get(topic.parentId) || []; siblings.push(topic); this.children.set(topic.parentId, siblings); }
    const stack = [...(this.children.get(null) || [])].reverse().map(topic => ({ topic, depth: 0, root: topic })), seen = new Set<string>();
    while (stack.length) {
      const node = stack.pop()!; if (seen.has(node.topic.id)) continue; seen.add(node.topic.id);
      this.nodes.push({ ...node, text: normalizeSearch(node.topic.title) }); this.roots.set(node.topic.id, node.root);
      stack.push(...[...(this.children.get(node.topic.id) || [])].reverse().map(topic => ({ topic, depth: node.depth + 1, root: node.root })));
    }
  }
  matches(topic: Topic, filter: TopicFilter = {}) {
    const level = filter.level || 'all', hasChildren = !!this.children.get(topic.id)?.length;
    if (level === 'subject' && topic.parentId !== null || level === 'parent' && !hasChildren || level === 'leaf' && hasChildren) return false;
    const rated = filter.source === 'subject' ? this.roots.get(topic.id) || topic : filter.source === 'parent' ? this.byId.get(topic.parentId || '') || topic : topic;
    return matchesImportance(rated, filter.importance || 'all');
  }
  path(id: string) {
    const names: string[] = [], seen = new Set<string>(); let node = this.byId.get(id);
    while (node && !seen.has(node.id)) { seen.add(node.id); names.push(node.title); node = node.parentId ? this.byId.get(node.parentId) : undefined; }
    return names.reverse().join(' / ');
  }
  search(query: string, filter: TopicFilter = {}, limit = 40) {
    const normalized = normalizeSearch(query).slice(0, 120), tokens = normalized.split(' ').filter(Boolean).slice(0, 8), inherited = new Map<string, number[]>();
    const result: { topic: Topic; depth: number; score: number }[] = [];
    for (const node of this.nodes) {
      const own = tokens.map(token => tokenScore(node.text, token)), parent = inherited.get(node.topic.parentId || '') || [];
      const combined = own.map((score, i) => Math.max(score, (parent[i] ?? -1) >= 0 ? Math.max(0, parent[i] - 1) : -1));
      inherited.set(node.topic.id, combined);
      if (this.matches(node.topic, filter) && !combined.some(score => score < 0)) result.push({ topic: node.topic, depth: node.depth, score: combined.reduce((a, b) => a + b, 0) + own.filter(n => n >= 0).length * 15 + (node.text === normalized && tokens.length ? 100 : 0) });
    }
    return result.sort((a, b) => b.score - a.score || a.depth - b.depth).slice(0, limit);
  }
}
