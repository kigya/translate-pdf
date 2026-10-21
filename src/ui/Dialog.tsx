import { useMotionReduced } from './useMotionReduced';
import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { Button, LayoutDirection } from './components';
import { Icon, IconName } from './Controls';
import { Palette } from './theme';

export type DialogAction = { id: string; label: string; destructive?: boolean; quiet?: boolean };
export type DialogOptions = { title: string; message: string; actions: DialogAction[]; icon?: IconName };
type Request = DialogOptions & { resolve: (result: string | null) => void };
export const DialogContext = createContext<(options: DialogOptions) => Promise<string | null>>(() => Promise.resolve(null));

/** App-owned presentation; OS permission and document pickers remain OS-owned. */
export function useDialogController(colors: Palette, direction: LayoutDirection = 'ltr') {
  const textDirection = { writingDirection: direction, textAlign: direction === 'rtl' ? 'right' as const : 'left' as const };
  const [current, setCurrent] = useState<Request | null>(null);
  const queue = useRef<Request[]>([]);
  const pending = useRef<Request | null>(null);
  const closing = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reduced = useMotionReduced();
  const progress = useSharedValue(0);
  const show = useCallback((options: DialogOptions) => new Promise<string | null>(resolve => {
    const request = { ...options, resolve };
    if (pending.current) queue.current.push(request);
    else { pending.current = request; setCurrent(request); }
  }), []);
  useEffect(() => {
    progress.value = current ? (reduced ? 1 : withTiming(1, { duration: 230 })) : 0;
  }, [current, progress, reduced]);
  useEffect(() => () => {
    if (timer.current !== null) clearTimeout(timer.current);
    pending.current?.resolve(null);
    for (const request of queue.current) request.resolve(null);
  }, []);
  const dismiss = (result: string | null) => {
    if (!pending.current || closing.current) return;
    closing.current = true;
    progress.value = reduced ? 0 : withTiming(0, { duration: 160 });
    const complete = () => {
      const request = pending.current!;
      pending.current = queue.current.shift() ?? null;
      closing.current = false;
      timer.current = null;
      setCurrent(pending.current);
      request.resolve(result);
    };
    if (reduced) complete();
    else timer.current = setTimeout(complete, 160);
  };
  const backdrop = useAnimatedStyle(() => ({ opacity: progress.value }));
  const panel = useAnimatedStyle(() => ({ opacity: progress.value, transform: [{ translateY: 16 * (1 - progress.value) }, { scale: 0.94 + 0.06 * progress.value }] }));
  return { showDialog: show, dialogOpen: current !== null, dialog: <Modal visible={current !== null} transparent animationType="none" onRequestClose={() => dismiss(null)}>
      <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24, direction }}>
        <Animated.View pointerEvents="none" style={[{ position: 'absolute', inset: 0, backgroundColor: '#00000088' }, backdrop]}/>
        <Pressable testID="dialog-backdrop" accessible={false} onPress={() => dismiss(null)} style={{ position: 'absolute', inset: 0 }}/>
        {current ? <Animated.View testID="app-dialog" accessibilityViewIsModal style={[{ width: '100%', maxWidth: 420, maxHeight: '85%', backgroundColor: colors.surface, borderRadius: 30, padding: 24, gap: 18 }, panel]}>
          <View style={{ width: 52, height: 52, borderRadius: 18, backgroundColor: colors.softAccent, justifyContent: 'center', alignItems: 'center' }}><Icon name={current.icon ?? 'info'} color={colors.accent} size={27}/></View>
          <Text accessibilityRole="header" style={{ color: colors.text, fontSize: 23, fontWeight: '800', ...textDirection }}>{current.title}</Text>
          <ScrollView><Text style={{ color: colors.muted, fontSize: 16, lineHeight: 23, ...textDirection }}>{current.message}</Text></ScrollView>
          <View style={{ gap: 10 }}>{current.actions.map(action => <Button key={action.id} colors={colors} label={action.label} variant={action.destructive ? 'danger' : action.quiet ? 'quiet' : 'primary'} onPress={() => dismiss(action.id)}/>)}</View>
        </Animated.View> : null}
      </View>
    </Modal> };
}

export const useDialog = () => useContext(DialogContext);

export function DialogProvider({ children, colors, direction }: { children: React.ReactNode; colors: Palette; direction?: LayoutDirection }) {
  const { showDialog, dialog, dialogOpen } = useDialogController(colors, direction);
  return <DialogContext.Provider value={showDialog}><View accessibilityElementsHidden={dialogOpen} importantForAccessibility={dialogOpen ? 'no-hide-descendants' : 'auto'} style={{ flex: 1 }}>{children}</View>{dialog}</DialogContext.Provider>;
}
