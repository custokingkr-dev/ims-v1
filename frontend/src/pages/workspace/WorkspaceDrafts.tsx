import { createContext, useCallback, useContext, useEffect, useRef, useSyncExternalStore, type Dispatch, type ReactNode, type SetStateAction } from 'react';

type DraftStore = { values: Map<string, unknown>; dirty: Set<string>; listeners: Map<string, Set<() => void>> };
const createStore = (): DraftStore => ({ values: new Map(), dirty: new Set(), listeners: new Map() });
const DraftContext = createContext<DraftStore | null>(null);

/** Lives only inside one authenticated workspace. No student data is written to browser storage. */
export function WorkspaceDraftProvider({ children }: { children: ReactNode }) {
  const store = useRef<DraftStore>(createStore());
  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (!store.current.dirty.size) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', guard);
    return () => {
      window.removeEventListener('beforeunload', guard);
      // The provider owns the store: leaving the authenticated workspace releases
      // it. Do not erase it during React StrictMode's effect setup/cleanup probe.
    };
  }, []);
  return <DraftContext.Provider value={store.current}>{children}</DraftContext.Provider>;
}

export function useWorkspaceDraft<T>(key: string, initial: T | (() => T)): [T, Dispatch<SetStateAction<T>>] {
  const shared = useContext(DraftContext);
  const local = useRef<DraftStore>(createStore());
  const store = shared ?? local.current;
  if (!store.values.has(key)) store.values.set(key, typeof initial === 'function' ? (initial as () => T)() : initial);
  const subscribe = useCallback((listener: () => void) => {
    if (!store.listeners.has(key)) store.listeners.set(key, new Set());
    store.listeners.get(key)!.add(listener);
    return () => { store.listeners.get(key)?.delete(listener); };
  }, [key, store]);
  const snapshot = useCallback(() => store.values.get(key) as T, [key, store]);
  const value = useSyncExternalStore(subscribe, snapshot, snapshot);
  const update = useCallback<Dispatch<SetStateAction<T>>>((next) => {
    const resolved = typeof next === 'function' ? (next as (previous: T) => T)(store.values.get(key) as T) : next;
    store.values.set(key, resolved);
    store.listeners.get(key)?.forEach(listener => listener());
  }, [key, store]);
  return [value, update];
}

export function useDraftDirty(key: string, dirty: boolean) {
  const store = useContext(DraftContext);
  useEffect(() => {
    if (dirty) store?.dirty.add(key);
    else store?.dirty.delete(key);
    // Keep dirty status while the panel is unmounted: its draft remains recoverable.
  }, [store, key, dirty]);
  useEffect(() => {
    if (store || !dirty) return;
    const guard = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [store, dirty]);
  return () => store?.dirty.delete(key);
}

export function useConfirmWorkspaceExit() {
  const store = useContext(DraftContext);
  return () => !store?.dirty.size || window.confirm('Leave the workspace and discard unsaved drafts? Your saved records will remain.');
}
