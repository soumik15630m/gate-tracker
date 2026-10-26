import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, Animated, Easing, View } from 'react-native';
import Svg, { Circle, Line } from 'react-native-svg';
import { C, F, T } from './ui';
import { timerClock } from './core/stopwatch';

export function StopwatchDial({ elapsed, running, label }: { elapsed: number; running: boolean; label: string }) {
  const pulse = useRef(new Animated.Value(0)).current, [reduced, setReduced] = useState(true);
  useEffect(() => { let active = true; AccessibilityInfo.isReduceMotionEnabled().then(value => { if (active) setReduced(value); }); const listener = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduced); return () => { active = false; listener.remove(); }; }, []);
  useEffect(() => { if (!running || reduced) { pulse.setValue(0); return; } const animation = Animated.loop(Animated.sequence([Animated.timing(pulse, { toValue: 1, duration: 2200, easing: Easing.inOut(Easing.ease), useNativeDriver: true }), Animated.timing(pulse, { toValue: 0, duration: 2200, easing: Easing.inOut(Easing.ease), useNativeDriver: true })])); animation.start(); return () => animation.stop(); }, [running, reduced]);
  const radius = 115, circumference = 2 * Math.PI * radius, progress = elapsed / 3600000 % 1;
  const motivation = elapsed < 60000 ? 'One quiet beginning.' : elapsed < 15 * 60000 ? 'You are making room for progress.' : elapsed < 30 * 60000 ? 'Your consistency is taking shape.' : 'Look at the time you have given yourself.';
  return <View style={{ alignItems: 'center', gap: 20, paddingVertical: 22 }}><View style={{ width: 278, height: 278, alignItems: 'center', justifyContent: 'center' }}>
    <Animated.View style={{ position: 'absolute', width: 250, height: 250, borderRadius: 125, borderWidth: 1, borderColor: C.sage, opacity: pulse.interpolate({ inputRange: [0,1], outputRange: [.08,.25] }), transform: [{ scale: pulse.interpolate({ inputRange: [0,1], outputRange: [1,1.08] }) }] }} />
    <Svg width={278} height={278} style={{ position: 'absolute' }}><Circle cx={139} cy={139} r={radius} fill="none" stroke={C.line} strokeWidth={2} /><Circle cx={139} cy={139} r={radius} fill="none" stroke={C.teal} strokeWidth={3} strokeLinecap="round" strokeDasharray={`${circumference} ${circumference}`} strokeDashoffset={circumference * (1-progress)} rotation={-90} origin="139,139" />{Array.from({ length: 60 },(_,i) => { const angle = i * Math.PI/30 - Math.PI/2, inner = i%5 === 0 ? 123 : 127; return <Line key={i} x1={139+Math.cos(angle)*inner} y1={139+Math.sin(angle)*inner} x2={139+Math.cos(angle)*132} y2={139+Math.sin(angle)*132} stroke={i%5===0 ? C.sage : C.line} strokeWidth={i%5===0 ? 1.5 : 1} />; })}</Svg>
    <T muted style={{ fontSize: 10, letterSpacing: 2, marginBottom: 12 }}>{label.toUpperCase()}</T><T accessibilityLabel={`Elapsed study time ${timerClock(elapsed)}`} style={{ fontFamily: F.display, fontSize: 47, color: C.text, fontVariant: ['tabular-nums'] }}>{timerClock(elapsed)}</T><T muted style={{ fontSize: 10, marginTop: 12 }}>YOUR TIME, WELL SPENT</T>
  </View><T style={{ fontFamily: F.display, fontSize: 25, textAlign: 'center', color: C.sage }}>{running ? motivation : elapsed ? 'A pause belongs to the process.' : 'A little further starts here.'}</T><T muted style={{ textAlign: 'center', fontSize: 11, lineHeight: 19 }}>Start with your attention. Choose the subject and lectures when you finish.</T></View>;
}
