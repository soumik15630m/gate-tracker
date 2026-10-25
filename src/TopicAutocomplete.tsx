import React, { useEffect, useMemo, useState } from 'react';
import { Pressable, View } from 'react-native';
import { C, Input, T, s } from './ui';
import { ImportanceBadge } from './importance';
import { TopicSearchIndex, type TopicFilter } from './core/search';
import type { Topic } from './core/model';

export function TopicAutocomplete({ topics, value, onChange, filter = {}, label = 'Find any subject or topic', autoFocus = false }: { topics: Topic[]; value: string; onChange: (id: string) => void; filter?: TopicFilter; label?: string; autoFocus?: boolean }) {
  const index = useMemo(() => new TopicSearchIndex(topics), [topics]);
  const [query, setQuery] = useState(''), [settled, setSettled] = useState(''), [open, setOpen] = useState(false), [active, setActive] = useState(0);
  useEffect(() => { const timer = setTimeout(() => setSettled(query), 90); return () => clearTimeout(timer); }, [query]);
  const results = useMemo(() => index.search(settled, filter, 25), [index, settled, filter.importance, filter.level, filter.source]);
  const choose = (id: string) => { onChange(id); setQuery(''); setSettled(''); setOpen(false); };
  return <View style={{ marginBottom: 18 }}><Input label={label} value={query} onChange={value => { setQuery(value); setActive(0); setOpen(true); }} autoFocus={autoFocus} onKey={key => { if (key === 'Escape') { setOpen(false); return true; } if (key === 'ArrowDown' || key === 'ArrowUp') { setOpen(true); setActive(current => Math.max(0, Math.min(results.length - 1, open ? current + (key === 'ArrowDown' ? 1 : -1) : 0))); return true; } return false; }} onSubmit={() => { const fresh = index.search(query, filter, 25), selected = fresh[Math.min(active, fresh.length - 1)]; if (selected) choose(selected.topic.id); }} placeholder="Type a name, e.g. packet switching" />
    {value && <T muted style={{ fontSize: 11, lineHeight: 19, marginBottom: 9 }}>Selected: {index.path(value) || 'Topic removed'}{index.byId.get(value) && !index.matches(index.byId.get(value)!, filter) ? ' · outside current filter' : ''}</T>}
    {open && <View style={{ borderWidth: 1, borderColor: C.line, borderRadius: 9, padding: 6 }}><Pressable accessibilityRole="button" onPress={() => choose('')} style={{ padding: 12 }}><T muted style={{ fontSize: 12 }}>Whole exam · no specific topic</T></Pressable>{query !== settled && <T muted style={{ fontSize: 11, padding: 12 }}>Finding topics…</T>}{results.map((result, position) => <Pressable key={result.topic.id} accessibilityRole="button" accessibilityState={{ selected: active === position }} onHoverIn={() => setActive(position)} accessibilityLabel={`Choose ${index.path(result.topic.id)}`} onPress={() => choose(result.topic.id)} style={({ hovered, focused }: any) => ({ borderTopWidth: 1, borderColor: C.line, padding: 12, gap: 5, backgroundColor: hovered || focused || active === position ? C.sageWash : 'transparent', borderRadius: 5 })}><View style={[s.row, { justifyContent: 'space-between' }]}><T style={{ flex: 1 }}>{result.topic.title}</T><ImportanceBadge value={result.topic.importance} /></View><T muted numberOfLines={2} style={{ fontSize: 10, lineHeight: 17 }}>{index.path(result.topic.id)}</T></Pressable>)}{!results.length && query === settled && <T muted style={{ padding: 12, lineHeight: 21 }}>No matching topics. Try fewer words or change the filters.</T>}<Pressable accessibilityRole="button" onPress={() => setOpen(false)} style={{ padding: 12 }}><T muted style={{ fontSize: 11 }}>↑ ↓ to browse · Enter to choose · Esc to close · up to 25 matches</T></Pressable></View>}
  </View>;
}
