import React, { useEffect, useState } from 'react';
import { AppState, BackHandler, View } from 'react-native';
import { Onboarding } from '../components/kinetik/screens/onboarding';
import { PressJelly } from '../components/kinetik/press-jelly';
import { MagnetButton } from '../components/kinetik/magnet-button';
import { OnboardingState } from '../native/PdfTranslator';
import { LayoutDirection, Notice } from '../ui/components';
import { Palette } from '../ui/theme';
import { Translator } from '../i18n/messages';
import { ModelScene, onboardingScenePalette, ProgressScene, TranslationScene } from './OnboardingScenes';
import { useMotionReduced } from '../ui/useMotionReduced';
export const ONBOARDING_VERSION = 1;
export async function finishWhenIdle(saving: boolean, finish: () => Promise<void>): Promise<void> { if (!saving) await finish(); }
export function swipePageDelta(dx: number, direction: LayoutDirection, threshold = 55): -1 | 0 | 1 {
  if (Math.abs(dx) <= threshold) return 0;
  return (direction === 'rtl' ? dx > 0 : dx < 0) ? 1 : -1;
}
export function OnboardingScreen({ colors, t, persist, onComplete, direction = 'ltr', replay = false, onExit }: {
  colors: Palette; t: Translator; initialState: OnboardingState; persist: (state: OnboardingState) => Promise<void>;
  onComplete: () => void; ios: boolean; direction?: LayoutDirection; replay?: boolean; onExit?: () => void;
}) {
  const reduced = useMotionReduced();
  const [active, setActive] = useState(AppState.currentState === 'active');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const sceneColors = onboardingScenePalette(colors);
  useEffect(() => { const sub = AppState.addEventListener('change', state => setActive(state === 'active')); return () => sub.remove(); }, []);
  useEffect(() => { const sub = BackHandler.addEventListener('hardwareBackPress', () => { if (!replay) return false; onExit?.(); return true; }); return () => sub.remove(); }, [replay, onExit]);
  const finish = () => void finishWhenIdle(saving, async () => {
    if (replay) { onExit?.(); return; }
    setSaving(true); setError('');
    try { await persist({ version: ONBOARDING_VERSION, page: 2, done: true }); onComplete(); }
    catch { setError(t('onboardFailed')); setSaving(false); }
  });
  return <View testID="onboarding-root" style={{ flex: 1, direction }}>
    <Onboarding active={active && !saving} disabled={saving} nextLabel={t('next')} finishLabel={t('next')} skipLabel={t('skip')} progressLabel={t('progress')} onSkip={finish} onFinish={finish}
      renderAction={(last, next) => last ? <MagnetButton flatAppearance onPress={next} disabled={saving} size="lg" block field={12}>{t('next')}</MagnetButton> : <PressJelly flatAppearance block size="lg" onPress={next} disabled={saving}>{t('next')}</PressJelly>}
      slides={[
        { key: 'documents', title: t('intro1Title'), visual: playing => <View accessible={false} pointerEvents="none"><TranslationScene colors={sceneColors} active={playing} reduced={reduced} /></View> },
        { key: 'engines', title: t('intro2Title'), visual: playing => <View accessible={false} pointerEvents="none"><ModelScene colors={sceneColors} active={playing} reduced={reduced} /></View> },
        { key: 'results', title: t('intro3Title'), visual: playing => <View accessible={false} pointerEvents="none"><ProgressScene colors={sceneColors} active={playing} reduced={reduced} /></View> },
      ]} />
    <Notice colors={colors} text={error} />
  </View>;
}
