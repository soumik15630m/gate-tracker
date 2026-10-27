import React, { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Platform, View } from 'react-native';
import { Download, RefreshCw } from 'lucide-react-native';
import { useStudy } from './store';
import { hasNewWebRelease } from './core/releases';
import { Button, Panel, T, s } from './ui';
interface UpdateState { enabled:boolean; checking:boolean; pending:boolean; error:string; checkedAt:string; check:()=>Promise<void>; apply:()=>Promise<void> }
const Context=createContext<UpdateState|null>(null);
export function UpdateProvider({children}:{children:ReactNode}) {
  const study=useStudy(),enabled=Platform.OS==='web';
  const [checking,setChecking]=useState(false),[pending,setPending]=useState(false),[error,setError]=useState(''),[checkedAt,setCheckedAt]=useState('');
  const busy=useRef(false);
  const check=useCallback(async()=>{
    if(!enabled||busy.current)return;
    busy.current=true;setChecking(true);const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),10000);
    try {
      const response=await fetch('/version.json',{cache:'no-store',signal:controller.signal});
      if(!response.ok)throw Error('Could not check for a new release. Your current app stays available.');
      const current=Array.from(document.scripts).filter(s=>s.src).map(s=>new URL(s.src,location.href).pathname).filter(p=>p.startsWith('/_expo/static/js/web/'));
      setPending(hasNewWebRelease(await response.json(),current));setCheckedAt(new Date().toISOString());setError('');
    }catch{setError('Could not check for a new release. Your current app stays available offline.');}
    finally{clearTimeout(timeout);busy.current=false;setChecking(false);}
  },[enabled]);
  useEffect(()=>{
    if(!enabled)return;
    check();const focused=()=>{if(document.visibilityState==='visible')check();};
    const interval=setInterval(focused,60000);document.addEventListener('visibilitychange',focused);window.addEventListener('online',focused);
    return()=>{clearInterval(interval);document.removeEventListener('visibilitychange',focused);window.removeEventListener('online',focused);};
  },[enabled,check]);
  const apply=useCallback(async()=>{
    if(!enabled)return;
    if(!navigator.onLine){study.notify('Reconnect to download the update. Your study data stays on this device.');return;}
    try{await study.flush();}catch{study.notify('Could not save your latest changes. Export a backup before updating.');return;}
    if('serviceWorker' in navigator) await navigator.serviceWorker.getRegistration().then(r=>r?.update()).catch(()=>{});
    location.reload();
  },[enabled,study.flush,study.notify]);
  return <Context.Provider value={{enabled,checking,pending,error,checkedAt,check,apply}}>{children}</Context.Provider>;
}
export function useAppUpdates(){const value=useContext(Context);if(!value)throw Error('UpdateProvider is missing.');return value;}
export function UpdateSettings(){
  const updates=useAppUpdates();
  return <Panel title="App updates" subtitle="New releases appear inside the app with an Update action."><T muted style={{lineHeight:22,marginBottom:18}}>{updates.pending?'A newer web release is ready. Save and reload to update.':updates.checking?'Checking for a new release…':updates.checkedAt?'You are using the latest web release. Checks run every minute while this page is visible.':'Checks run when you open or return to the app, and while it stays visible.'}</T><T muted style={{lineHeight:22,marginBottom:18}}>Native Android checks for signed APK releases on launch and every five minutes while open. Its Update action downloads and verifies the APK, then opens Android’s installer.</T>{!!updates.error&&<T muted style={{marginBottom:14}}>{updates.error}</T>}<View style={[s.row,{flexWrap:'wrap'}]}><Button label={updates.checking?'Checking…':'Check for updates'} icon={RefreshCw} kind="secondary" onPress={updates.check} disabled={updates.checking}/>{updates.pending&&<Button label="Save & update" icon={Download} onPress={updates.apply}/>}</View></Panel>;
}
