import { useMotionReduced } from './useMotionReduced';
import React, { useCallback, useEffect, useRef } from 'react';
import { LayoutChangeEvent, View } from 'react-native';
import { AppIcon } from './Motion';

const BRAND_RED = '#C62828';

export function SplashScreen({ onFirstFrame }: { onFirstFrame: () => void }) {
  const fired = useRef(false);
  const animationFrames = useRef<number[]>([]);
  const reduced = useMotionReduced();
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
  return <View testID="custom-splash-screen" onLayout={onLayout} style={{ flex: 1, backgroundColor: BRAND_RED, alignItems: 'center', justifyContent: 'center' }}>
    <AppIcon size={92} color="#FFFFFF" background={BRAND_RED} active={!reduced} reduced={reduced} draw />
  </View>;
}
