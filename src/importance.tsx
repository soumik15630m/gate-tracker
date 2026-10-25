import React from 'react';
import { Pressable, View } from 'react-native';
import { Star } from 'lucide-react-native';
import { C, Select, T, s } from './ui';
import { importanceFilters, importanceLabel } from './core/model';
import { topicLevels, type TopicFilter } from './core/search';

export function ImportancePicker({ value, onChange }: { value?: number | null; onChange: (value: number | null) => void }) {
  return <View style={{ gap: 9, marginBottom: 20 }}><View style={[s.row, { justifyContent: 'space-between' }]}><T muted style={{ fontSize: 12 }}>Importance</T><T style={{ color: value ? C.sage : C.muted, fontSize: 11 }}>{value ? `${value} / 5` : 'N/A'}</T></View>
    <View style={[s.row, { flexWrap: 'wrap', gap: 5 }]}><Pressable accessibilityRole="button" accessibilityLabel="Importance: N/A" accessibilityState={{ selected: value == null }} onPress={() => onChange(null)} style={{ minHeight: 44, paddingHorizontal: 12, justifyContent: 'center', borderRadius: 7, borderWidth: 1, borderColor: value == null ? C.sage : C.line, backgroundColor: value == null ? C.sageWash : C.bg }}><T muted style={{ fontSize: 12 }}>N/A</T></Pressable>{[1, 2, 3, 4, 5].map(n => <Pressable key={n} accessibilityRole="button" accessibilityLabel={`Importance: ${n} ${n === 1 ? 'star' : 'stars'}`} accessibilityState={{ selected: value === n }} onPress={() => onChange(n)} style={{ minHeight: 44, width: 38, justifyContent: 'center', alignItems: 'center' }}><Star size={23} color={value && n <= value ? C.sage : C.faint} fill={value && n <= value ? C.sage : 'transparent'} strokeWidth={1.3} /></Pressable>)}</View>
    <T muted style={{ fontSize: 10, lineHeight: 17 }}>Your priority for this item only. Independent of confidence and completion.</T>
  </View>;
}
export function ImportanceFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) { return <Select label="Filter by importance" value={value} onChange={onChange} options={importanceFilters} />; }
export function TopicFilterControls({ value, onChange }: { value: TopicFilter; onChange: (next: TopicFilter) => void }) {
  return <><ImportanceFilter value={value.importance || 'all'} onChange={importance => onChange({ ...value, importance })} /><View style={s.columns}><View style={{ flex: 1, minWidth: 170 }}><Select label="Topic level" value={value.level || 'all'} onChange={level => onChange({ ...value, level })} options={topicLevels} /></View><View style={{ flex: 1, minWidth: 170 }}><Select label="Match stars from" value={value.source || 'self'} onChange={source => onChange({ ...value, source })} options={[{ value: 'self', label: 'This item’s own stars' }, { value: 'parent', label: 'Its immediate parent’s stars' }, { value: 'subject', label: 'Its subject’s stars' }]} /></View></View></>;
}
export function ImportanceBadge({ value }: { value?: number | null }) { return <T style={{ fontSize: 10, color: value ? C.sage : C.faint }}>{importanceLabel(value)}</T>; }
