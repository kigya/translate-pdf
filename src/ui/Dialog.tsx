import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { PopDialog } from '../components/kinetik/pop-dialog';
import { durations } from '../lib/kinetik/motion/motion';
import { useUiLocale } from '../i18n/UiLocaleContext';
import { createTranslator, normalizeLocale } from '../i18n/messages';
import { LayoutDirection } from './components';
import { Icon, IconName } from './Controls';
import { Palette } from './theme';
import { useMotionReduced } from './useMotionReduced';

export type DialogAction = { id: string; label: string; destructive?: boolean; quiet?: boolean };
export type DialogOptions = { title: string; message: string; actions: DialogAction[]; icon?: IconName };
type Request = DialogOptions & { resolve: (result: string | null) => void };
export const DialogContext = createContext<(options: DialogOptions) => Promise<string | null>>(() => Promise.resolve(null));

/** Durable request queue around Kinetik's presentation. Native permissions remain OS-owned. */
export function useDialogController(colors: Palette, direction: LayoutDirection = 'ltr', localeOverride?: string) {
  const contextLocale = useUiLocale();
  const t = createTranslator(normalizeLocale(localeOverride ?? contextLocale));
  const [current, setCurrent] = useState<Request | null>(null);
  const [exiting, setExiting] = useState(false);
  const queue = useRef<Request[]>([]);
  const pending = useRef<Request | null>(null);
  const closing = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reduced = useMotionReduced();
  const show = useCallback((options: DialogOptions) => new Promise<string | null>(resolve => {
    const request = { ...options, resolve };
    if (pending.current) queue.current.push(request);
    else { pending.current = request; setCurrent(request); }
  }), []);
  useEffect(() => () => {
    if (timer.current !== null) clearTimeout(timer.current);
    pending.current?.resolve(null);
    for (const request of queue.current) request.resolve(null);
  }, []);
  const dismiss = (result: string | null) => {
    if (!pending.current || closing.current) return;
    closing.current = true;
    setExiting(true);
    const complete = () => {
      const request = pending.current!;
      pending.current = queue.current.shift() ?? null;
      closing.current = false;
      timer.current = null;
      setCurrent(pending.current);
      setExiting(false);
      request.resolve(result);
    };
    if (reduced) complete();
    else timer.current = setTimeout(complete, durations.quick + 32);
  };
  return { showDialog: show, dialogOpen: current !== null, dialog: <PopDialog
    testID="app-dialog" visible={current !== null && !exiting} onClose={() => dismiss(null)}
    title={current?.title ?? ''} direction={direction} closeLabel={t('back')}
    style={{ maxHeight: '85%', backgroundColor: colors.surface }}
    actions={(current?.actions ?? []).map(action => ({ label: action.label, tone: action.destructive ? 'danger' : action.quiet ? 'default' : 'primary', onPress: () => dismiss(action.id) }))}>
      {current ? <><View style={{ width: 48, height: 48, borderRadius: 16, backgroundColor: colors.softAccent, alignItems: 'center', justifyContent: 'center' }}><Icon name={current.icon ?? 'info'} color={colors.accent} size={26} /></View><ScrollView style={{ maxHeight: 340 }}><Text style={{ color: colors.muted, fontSize: 16, lineHeight: 23, writingDirection: direction, textAlign: direction === 'rtl' ? 'right' : 'left' }}>{current.message}</Text></ScrollView></> : null}
  </PopDialog> };
}
export const useDialog = () => useContext(DialogContext);
export function DialogProvider({ children, colors, direction }: { children: React.ReactNode; colors: Palette; direction?: LayoutDirection }) {
  const { showDialog, dialog, dialogOpen } = useDialogController(colors, direction);
  return <DialogContext.Provider value={showDialog}><View accessibilityElementsHidden={dialogOpen} importantForAccessibility={dialogOpen ? 'no-hide-descendants' : 'auto'} style={{ flex: 1 }}>{children}</View>{dialog}</DialogContext.Provider>;
}
