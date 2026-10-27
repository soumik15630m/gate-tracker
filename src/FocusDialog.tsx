import React, { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Modal, Platform, ScrollView, View, useWindowDimensions } from 'react-native';
import { Maximize2, Minimize2, X } from 'lucide-react-native';
import { C, F, IconButton, T, s } from './ui';

const ExpandedContext=createContext(false);
export const useFocusExpanded=()=>useContext(ExpandedContext);

export function FocusDialog({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  const [expanded, setExpanded] = useState(false), ownedFullscreen = useRef(false);
  const { width, height } = useWindowDimensions();
  const browser = Platform.OS === 'web' && typeof document !== 'undefined';
  async function leaveFullscreen() {
    const owned=ownedFullscreen.current;ownedFullscreen.current=false;
    if (browser && owned && document.fullscreenElement) {
      await document.exitFullscreen().catch(() => {});
    }
  }
  function close() { void leaveFullscreen(); setExpanded(false); onClose(); }
  useEffect(() => {
    if (!browser || !open) return;
    const changed = () => { if (ownedFullscreen.current && !document.fullscreenElement) { ownedFullscreen.current = false; setExpanded(false); } };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape' && !document.fullscreenElement) { event.preventDefault(); if (expanded) setExpanded(false); else onClose(); } };
    document.addEventListener('fullscreenchange', changed);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('fullscreenchange', changed); document.removeEventListener('keydown', escape); };
  }, [browser, open, expanded, onClose]);
  useEffect(() => { if (!open) { void leaveFullscreen(); setExpanded(false); } }, [open]);
  useEffect(() => () => { void leaveFullscreen(); }, []);
  async function toggle() {
    if (expanded) { setExpanded(false); await leaveFullscreen(); return; }
    setExpanded(true);
    // The root includes React Native Web's modal portal and keeps every control visible.
    if (browser && !document.fullscreenElement && document.documentElement.requestFullscreen) {
      ownedFullscreen.current = true;
      try { await document.documentElement.requestFullscreen(); if(!ownedFullscreen.current && document.fullscreenElement) await document.exitFullscreen().catch(()=>{}); } catch { ownedFullscreen.current = false; }
    }
  }
  return <Modal visible={open} transparent={!expanded} animationType="fade" onRequestClose={close}>
    <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: expanded ? C.bg : 'rgba(0,0,0,.88)', padding: expanded ? 0 : 18 }}>
      <View testID="study-dialog" accessibilityViewIsModal style={{ width: '100%', maxWidth: expanded ? undefined : 580, height: expanded ? '100%' : undefined, maxHeight: expanded ? '100%' : '94%', backgroundColor: C.bg, borderWidth: expanded ? 0 : 1, borderColor: C.line, borderRadius: expanded ? 0 : 16, padding: width < 600 ? 18 : 28 }}>
        <View style={[s.row, { justifyContent: 'space-between' }]}><T style={{ fontFamily: F.display, fontSize: width < 600 ? 26 : 32, flexShrink: 1 }}>Make room for focus.</T><View style={s.row}><IconButton icon={expanded ? Minimize2 : Maximize2} label={expanded ? 'Exit fullscreen' : 'Enter fullscreen'} onPress={() => { void toggle(); }} /><IconButton icon={X} label="Close focus dial" onPress={close} /></View></View>
        <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', alignItems: 'center', paddingVertical: expanded && height > 700 ? 30 : 12 }}><View style={{ width: '100%', maxWidth: expanded ? 860 : 520 }}><ExpandedContext.Provider value={expanded}>{children}</ExpandedContext.Provider></View></ScrollView>
        {expanded && <T muted style={{ textAlign: 'center', fontSize: 11, paddingTop: 12 }}>Leaving fullscreen keeps your timer’s current state. Press Esc to return.</T>}
      </View>
    </View>
  </Modal>;
}
