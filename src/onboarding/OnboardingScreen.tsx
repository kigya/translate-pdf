import { useMotionReduced } from '../ui/useMotionReduced';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, AppState, AppStateStatus, BackHandler, PanResponder, Pressable, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { OnboardingState } from '../native/PdfTranslator';
import { Button, LayoutDirection, Notice } from '../ui/components';
import { AppIcon } from '../ui/Motion';
import { Palette } from '../ui/theme';
import { Translator } from '../i18n/messages';
import { ModelScene, OcrScene, ProgressScene, TranslationScene } from './OnboardingScenes';

export const ONBOARDING_VERSION = 1;
const PAGE_COUNT = 4;
export async function finishWhenIdle(saving: boolean, finish: () => Promise<void>): Promise<void> {
  if (saving) return;
  await finish();
}

export function swipePageDelta(dx: number, direction: LayoutDirection, threshold = 55): -1 | 0 | 1 {
  if (Math.abs(dx) <= threshold) return 0;
  return (direction === 'rtl' ? dx > 0 : dx < 0) ? 1 : -1;
}
const titleKeys = ['intro1Title', 'intro2Title', 'intro3Title', 'intro4Title'] as const;
const bodyKeys = ['intro1Body', 'intro2Body', 'intro3Body', 'intro4Body'] as const;

export function OnboardingScreen({ colors, t, initialState, persist, onComplete, ios: _ios, direction = 'ltr', replay = false, onExit }: {
  colors: Palette; t: Translator; initialState: OnboardingState; persist: (state: OnboardingState) => Promise<void>; onComplete: () => void; ios: boolean; direction?: LayoutDirection; replay?: boolean; onExit?: () => void;
}) {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const reduced = useMotionReduced();
  const [page, setPage] = useState(replay ? 0 : Math.max(0, Math.min(PAGE_COUNT - 1, initialState.page)));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [appState, setAppState] = useState<AppStateStatus>((AppState.currentState ?? 'active') as AppStateStatus);
  const pageRef = useRef(page); pageRef.current = page;
  const isWide = width >= 600 && width > height;
  const availableWidth = width - insets.left - insets.right;
  const sceneWidth = isWide ? Math.min(440, Math.max(260, (availableWidth - 94) * 0.62)) : Math.min(440, Math.max(260, availableWidth - 58));
  const pageChange = (next: number) => {
    const bounded = Math.max(0, Math.min(PAGE_COUNT - 1, next));
    setPage(bounded);
    if (!replay) void persist({ version: ONBOARDING_VERSION, page: bounded, done: false }).catch(() => setError(t('onboardFailed')));
  };
  const pageChangeRef = useRef(pageChange); pageChangeRef.current = pageChange;
  const responder = useMemo(() => PanResponder.create({
    onMoveShouldSetPanResponder: (_, gesture) => Math.abs(gesture.dx) > 35 && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.45,
    onPanResponderRelease: (_, gesture) => {
      const delta = swipePageDelta(gesture.dx, direction);
      if (delta) pageChangeRef.current(pageRef.current + delta);
    },
  }), [direction]);
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (replay) { onExit?.(); return true; }
      return false;
    });
    return () => subscription.remove();
  }, [onExit, replay]);
  useEffect(() => { const subscription = AppState.addEventListener('change', setAppState); return () => subscription.remove(); }, []);
  useEffect(() => { AccessibilityInfo.announceForAccessibility(`${page + 1} of ${PAGE_COUNT}. ${t(titleKeys[page])}`); }, [page, t]);
  const exitReplay = () => onExit?.();
  const finish = async () => finishWhenIdle(saving, async () => {
    if (replay) { exitReplay(); return; }
    setSaving(true); setError('');
    try { await persist({ version: ONBOARDING_VERSION, page, done: true }); onComplete(); }
    catch { setError(t('onboardFailed')); setSaving(false); }
  });
  const sceneActive = !saving && appState === 'active';
  const scene = page === 0 ? <TranslationScene colors={colors} active={sceneActive} reduced={reduced} />
    : page === 1 ? <OcrScene colors={colors} active={sceneActive} reduced={reduced} />
      : page === 2 ? <ModelScene colors={colors} active={sceneActive} reduced={reduced} />
        : <ProgressScene colors={colors} active={sceneActive} reduced={reduced} />;
  const scale = isWide ? Math.max(0.45, Math.min(1, (height - insets.top - insets.bottom - 176) / 330)) : Math.max(0.45, Math.min(1, (height - insets.top - insets.bottom - 245) / 420));
  const copyDirection = { writingDirection: direction, textAlign: direction === 'rtl' ? 'right' as const : 'left' as const };
  const smallTitle = width < 360 ? 27 : 31;
  return <View testID="onboarding-root" style={{ flex: 1, backgroundColor: colors.background, direction, paddingTop: insets.top + 5, paddingBottom: Math.max(10, insets.bottom), paddingLeft: insets.left, paddingRight: insets.right }} {...responder.panHandlers}>
    <View style={{ width: '100%', maxWidth: 1000, minHeight: 56, paddingHorizontal: 22, flexDirection: 'row', alignItems: 'center', alignSelf: 'center', direction }}>
      <View style={{ width: 36, height: 38, justifyContent: 'center' }}><AppIcon size={34} color="#FFFFFF" active={false} background={colors.accent} /></View>
      <Text style={{ color: colors.text, marginStart: 9, fontSize: 17, fontWeight: '800', letterSpacing: -0.35 }}>{t('appName')}</Text>
      <View style={{ flex: 1 }} />
      <Pressable accessibilityRole="button" accessibilityLabel={t('skip')} onPress={() => replay ? exitReplay() : void finish()} disabled={saving} hitSlop={12} style={{ minWidth: 64, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.accent, fontSize: 14, fontWeight: '700' }}>{t('skip')}</Text></Pressable>
    </View>
    <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', paddingHorizontal: 20, paddingVertical: 4 }} showsVerticalScrollIndicator={false}>
      <Animated.View testID="onboarding-animated-content" key={page} entering={reduced ? undefined : FadeIn.duration(230)} style={{ flexDirection: isWide ? 'row' : 'column', alignItems: 'center', justifyContent: 'center', gap: isWide ? 30 : 0, width: '100%', maxWidth: 980, alignSelf: 'center', direction }}>
        {isWide ? <View style={{ flex: 1, maxWidth: 400, direction }}><Text accessibilityRole="header" style={[{ color: colors.text, fontSize: 36, fontWeight: '800', lineHeight: 43, letterSpacing: -1 }, copyDirection]}>{t(titleKeys[page])}</Text><Text style={[{ color: colors.muted, fontSize: 16, lineHeight: 24, marginTop: 13 }, copyDirection]}>{t(bodyKeys[page])}</Text></View> : null}
        <View testID="onboarding-example-scene" accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" pointerEvents="none" style={{ width: sceneWidth, height: 330 * scale, marginVertical: isWide ? 0 : -5, direction: 'ltr', alignItems: 'center' }}><View testID="onboarding-scene-scale-wrapper" style={{ position: 'absolute', top: -(330 - 330 * scale) / 2, width: sceneWidth, height: 330, alignItems: 'center', justifyContent: 'center', transform: [{ scale }], direction: 'ltr' }}>{scene}</View></View>
        {!isWide ? <View style={{ alignItems: 'center', maxWidth: 500, paddingHorizontal: 8, direction }}><Text accessibilityRole="header" style={[{ textAlign: 'center', color: colors.text, fontSize: smallTitle, lineHeight: 37, fontWeight: '800', letterSpacing: -0.8, writingDirection: direction }]}>{t(titleKeys[page])}</Text><Text style={{ textAlign: 'center', color: colors.muted, fontSize: 15, lineHeight: 22, marginTop: 8, writingDirection: direction }}>{t(bodyKeys[page])}</Text></View> : null}
      </Animated.View>
    </ScrollView>
    <View style={{ width: '100%', maxWidth: 560, alignSelf: 'center', paddingHorizontal: 22, gap: 7, direction }}>
      <Notice colors={colors} text={error} />
      <View testID="onboarding-page-indicator" accessibilityRole="image" accessibilityLabel={`${page + 1} of ${PAGE_COUNT}`} style={{ flexDirection: 'row', justifyContent: 'center', alignItems: 'center', gap: 9, marginBottom: 2 }}>
        {Array.from({ length: PAGE_COUNT }, (_, index) => <View key={index} style={{ width: page === index ? 22 : 6, height: 6, borderRadius: 6, backgroundColor: page === index ? colors.accent : colors.border }} />)}
      </View>
      <Button label={page === PAGE_COUNT - 1 ? (replay ? t('done') : t('next')) : t('next')} onPress={() => page === PAGE_COUNT - 1 ? void finish() : pageChange(page + 1)} colors={colors} disabled={saving} loading={saving} />
    </View>
  </View>;
}
