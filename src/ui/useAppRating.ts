import { useCallback, useEffect, useRef } from 'react';

/** Keep the final star crossed during a drag, even while a previous write is pending. */
export function useAppRating(save: (value: number) => Promise<unknown>, refresh: () => Promise<unknown>, onError: () => void) {
  const handlers = useRef({ save, refresh, onError });
  handlers.current = { save, refresh, onError };
  const pending = useRef<number | null>(null);
  const saving = useRef(false);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; pending.current = null; }; }, []);
  return useCallback((value: number) => {
    if (!live.current) return;
    pending.current = value;
    if (saving.current) return;
    saving.current = true;
    void (async () => {
      try {
        do {
          while (pending.current !== null && live.current) {
            const next = pending.current;
            pending.current = null;
            try { await handlers.current.save(next); }
            catch { if (live.current) handlers.current.onError(); }
          }
          if (live.current) {
            try { await handlers.current.refresh(); }
            catch { if (live.current) handlers.current.onError(); }
          }
        } while (pending.current !== null && live.current);
      } finally {
        saving.current = false;
      }
    })();
  }, []);
}
