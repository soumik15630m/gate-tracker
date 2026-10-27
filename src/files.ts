import { Platform } from 'react-native';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import * as DocumentPicker from 'expo-document-picker';
export async function saveFile(name: string, contents: string, mime: string) {
  if (Platform.OS === 'web') { const url = URL.createObjectURL(new Blob([contents], { type: mime })); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); return; }
  const uri = FileSystem.cacheDirectory + name; await FileSystem.writeAsStringAsync(uri, contents); await Sharing.shareAsync(uri, { mimeType: mime, dialogTitle: 'Save your Study Tracker export' });
}
export async function openJsonFile(): Promise<unknown | null> {
  const result = await DocumentPicker.getDocumentAsync({ type: ['application/json', 'text/plain'], copyToCacheDirectory: true });
  if (result.canceled) return null;
  const asset = result.assets[0]; if ((asset.size || 0) > 8e6) throw new Error('Choose a backup smaller than 8 MB.');
  const text = Platform.OS === 'web' ? await (await fetch(asset.uri)).text() : await FileSystem.readAsStringAsync(asset.uri);
  try { return JSON.parse(text); } catch { throw new Error('This file does not contain valid JSON.'); }
}
