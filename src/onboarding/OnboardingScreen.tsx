import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, AppState, AppStateStatus, BackHandler, PanResponder, Pressable, ScrollView, Text, View, useWindowDimensions } from 'react-native';
import Animated, { FadeIn, useReducedMotion } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { OnboardingState } from '../native/PdfTranslator';
import { Button, LayoutDirection, Notice } from '../ui/components';
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

export function OnboardingScreen({ colors, t, initialState, persist, onComplete, ios, direction = 'ltr' }: {
  colors: Palette; t: Translator; initialState: OnboardingState; persist: (state: OnboardingState) => Promise<void>; onComplete: () => void; ios: boolean; direction?: LayoutDirection;
}) {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const reduced = useReducedMotion() ?? false;
  const [page, setPage] = useState(Math.max(0, Math.min(PAGE_COUNT - 1, initialState.page)));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [appState, setAppState] = useState<AppStateStatus>((AppState.currentState ?? 'active') as AppStateStatus);
  const pageRef = useRef(page); pageRef.current = page;
  const isWide = width >= 600 && width > height;
  const availableWidth = width - insets.left - insets.right;
  const sceneWidth = isWide
    ? Math.min(440, Math.max(260, (availableWidth - 94) * 0.62))
    : Math.min(440, Math.max(260, availableWidth - 58));
  const pageChange = (next: number) => {
    const bounded = Math.max(0, Math.min(PAGE_COUNT - 1, next));
    setPage(bounded);
    void persist({ version: ONBOARDING_VERSION, page: bounded, done: false }).catch(() => setError(t('onboardFailed')));
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
      if (pageRef.current <= 0) return false;
      pageChangeRef.current(pageRef.current - 1); return true;
    });
    return () => subscription.remove();
  }, []);
  useEffect(() => { const subscription = AppState.addEventListener('change', setAppState); return () => subscription.remove(); }, []);
  useEffect(() => { AccessibilityInfo.announceForAccessibility(`${page + 1} of ${PAGE_COUNT}. ${t(titleKeys[page])}`); }, [page, t]);
  const finish = async () => finishWhenIdle(saving, async () => {
    setSaving(true); setError('');
    try { await persist({ version: ONBOARDING_VERSION, page, done: true }); onComplete(); }
    catch { setError(t('onboardFailed')); setSaving(false); }
  });
  const sceneActive = !saving && appState === 'active';
  const scene = page === 0 ? <TranslationScene colors={colors} t={t} active={sceneActive} reduced={reduced} />
    : page === 1 ? <OcrScene colors={colors} t={t} active={sceneActive} reduced={reduced} />
      : page === 2 ? <ModelScene colors={colors} t={t} active={sceneActive} reduced={reduced} />
        : <ProgressScene colors={colors} t={t} active={sceneActive} reduced={reduced} ios={ios} />;
  const scale = isWide
    ? Math.max(0.45, Math.min(1, (height - insets.top - insets.bottom - 176) / 330))
    : Math.max(0.45, Math.min(1, (height - insets.top - insets.bottom - 245) / 420));
  const copyDirection = { writingDirection: direction, textAlign: direction === 'rtl' ? 'right' as const : 'left' as const };
  const platformLimit = page === PAGE_COUNT - 1 ? <Text testID="progress-platform-limit" style={{ color: colors.muted, fontSize: 12, lineHeight: 18, marginTop: 8, writingDirection: direction, textAlign: direction === 'rtl' ? 'right' : 'left' }}>{ios ? t('iosBackground') : t('androidBackground')}</Text> : null;
  return <View testID="onboarding-root" style={{ flex: 1, backgroundColor: colors.background, direction, paddingTop: insets.top + 5, paddingBottom: Math.max(10, insets.bottom), paddingLeft: insets.left, paddingRight: insets.right }} {...responder.panHandlers}>
    <View style={{ width: '100%', maxWidth: 1000, minHeight: 56, paddingHorizontal: 22, flexDirection: 'row', alignItems: 'center', alignSelf: 'center', direction }}>
      <View style={{ width: 36, height: 36, borderRadius: 12, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.onAccent, fontWeight: '900', fontSize: 18 }}>P</Text></View>
      <Text style={{ color: colors.text, marginStart: 10, fontSize: 20, fontWeight: '800', letterSpacing: -0.4 }}>{t('appName')}</Text>
      <Text accessibilityLabel={`${page + 1} of ${PAGE_COUNT}`} style={{ color: colors.muted, marginStart: 'auto', fontSize: 12, fontWeight: '700', letterSpacing: 1 }}>{`0${page + 1} / 04`}</Text>
    </View>
    <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', paddingHorizontal: 20, paddingVertical: 5 }} showsVerticalScrollIndicator={false}>
      <Animated.View testID="onboarding-animated-content" key={page} entering={reduced ? undefined : FadeIn.duration(230)} style={{ flexDirection: isWide ? 'row' : 'column', alignItems: 'center', justifyContent: 'center', gap: isWide ? 30 : 0, width: '100%', maxWidth: 980, alignSelf: 'center', direction }}>
        {isWide ? <View style={{ flex: 1, maxWidth: 400, direction }}><Text accessibilityRole="header" style={[{ color: colors.text, fontSize: 36, fontWeight: '800', lineHeight: 43, letterSpacing: -1 }, copyDirection]}>{t(titleKeys[page])}</Text><Text style={[{ color: colors.muted, fontSize: 16, lineHeight: 24, marginTop: 13 }, copyDirection]}>{t(bodyKeys[page])}</Text>{platformLimit}</View> : null}
        <View testID="onboarding-example-scene" style={{ width: sceneWidth, height: 330 * scale, marginVertical: isWide ? 0 : -5, direction: 'ltr', alignItems: 'center' }}><View testID="onboarding-scene-scale-wrapper" style={{ position: 'absolute', top: -(330 - 330 * scale) / 2, width: sceneWidth, height: 330, transform: [{ scale }], direction: 'ltr' }}>{scene}</View></View>
        {!isWide ? <View style={{ alignItems: 'center', maxWidth: 500, paddingHorizontal: 8, direction }}><Text accessibilityRole="header" style={[{ textAlign: 'center', color: colors.text, fontSize: width < 360 ? 27 : 31, lineHeight: 37, fontWeight: '800', letterSpacing: -0.8, writingDirection: direction }]}>{t(titleKeys[page])}</Text><Text style={{ textAlign: 'center', color: colors.muted, fontSize: 15, lineHeight: 22, marginTop: 9, writingDirection: direction }}>{t(bodyKeys[page])}</Text>{platformLimit}</View> : null}
      </Animated.View>
    </ScrollView>
    <View style={{ width: '100%', maxWidth: 520, alignSelf: 'center', paddingHorizontal: 22, gap: 8, direction }}>
      <Notice colors={colors} text={error} />
      <View style={{ flexDirection: 'row', justifyContent: 'center', alignItems: 'center', marginTop: 2, marginBottom: 3 }}>
        {Array.from({ length: PAGE_COUNT }, (_, index) => <Pressable key={index} accessibilityRole="button" accessibilityLabel={`${t('page')} ${index + 1} of ${PAGE_COUNT}`} accessibilityState={{ selected: page === index }} onPress={() => pageChange(index)} style={{ minWidth: 40, height: 38, alignItems: 'center', justifyContent: 'center' }}><View style={{ width: page === index ? 25 : 7, height: 7, borderRadius: 8, backgroundColor: page === index ? colors.accent : colors.border }} /></Pressable>)}
      </View>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {page > 0 ? <Button label={t('previous')} onPress={() => pageChange(page - 1)} colors={colors} variant="secondary" disabled={saving} /> : null}
        {page < PAGE_COUNT - 1 ? <Button label={t('skip')} onPress={() => { void finish(); }} colors={colors} variant="quiet" disabled={saving} /> : null}
        <View style={{ flex: 1 }}><Button label={page === PAGE_COUNT - 1 ? t('done') : t('next')} onPress={() => page === PAGE_COUNT - 1 ? void finish() : pageChange(page + 1)} colors={colors} disabled={saving} loading={saving} /></View>
      </View>
    </View>
  </View>;
}
