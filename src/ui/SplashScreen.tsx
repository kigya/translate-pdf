import { useMotionReduced } from './useMotionReduced';
import React, { useCallback, useEffect, useRef } from 'react';
import { LayoutChangeEvent, View } from 'react-native';
import { AppIcon } from './Motion';
import { GrainDrift } from '../components/kinetik/grain-drift';
import { useKinetikTheme } from '../lib/kinetik/tokens/theme';
import { AppState } from 'react-native';
import { useState } from 'react';

const BRAND_RED = '#C62828';

export function SplashScreen({ onFirstFrame }: { onFirstFrame: () => void }) {
  const [active, setActive] = useState(AppState.currentState === 'active');
  useEffect(() => { const sub = AppState.addEventListener('change', state => setActive(state === 'active')); return () => sub.remove(); }, []);
  const fired = useRef(false);
  const animationFrames = useRef<number[]>([]);
  const reduced = useMotionReduced();
  const { colors } = useKinetikTheme();
  const brand = colors.ember || BRAND_RED;
  const onLayout = useCallback((_event: LayoutChangeEvent) => {
    if (fired.current) return;
    fired.current = true;
    // Let the layout commit and at least one native frame be scheduled before
    // the caller starts its visible-duration clock.
    animationFrames.current.push(requestAnimationFrame(() => {
      animationFrames.current.push(requestAnimationFrame(onFirstFrame));
    }));
  }, [onFirstFrame]);
  useEffect(() => () => animationFrames.current.forEach(frame => cancelAnimationFrame(frame)), []);
  return <View testID="custom-splash-screen" onLayout={onLayout} style={{ flex: 1, backgroundColor: brand, alignItems: 'center', justifyContent: 'center' }}>
    <GrainDrift grain={0} speed={3} active={active} reduceEffects={reduced} colors={[brand, '#C93434', '#B91F1F', brand]} style={{ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0 }} />
    <AppIcon size={92} color={colors.onEmber} background={brand} active={!reduced} reduced={reduced} draw />
  </View>;
}
