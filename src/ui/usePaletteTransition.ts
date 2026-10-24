import { useCallback, useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { captureRef, releaseCapture } from 'react-native-view-shot';
import { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import type { ThemeMode } from './theme';

/** Crossfade a private, temporary capture so every surface changes together without remounting. */
export function usePaletteTransition(target: ThemeMode, reduced: boolean) {
  const view = useRef<React.ElementRef<typeof View>>(null);
  const [mode, setMode] = useState(target);
  const [image, setImage] = useState('');
  const captured = useRef('');
  const fadeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const opacity = useSharedValue(0);
  const clearFadeTimer = useCallback(() => {
    clearTimeout(fadeTimer.current);
    fadeTimer.current = undefined;
  }, []);
  const discard = useCallback((uri: string) => {
    if (captured.current !== uri) return;
    captured.current = '';
    releaseCapture(uri);
    setImage('');
  }, []);
  useEffect(() => {
    let live = true;
    if (mode === target) return;
    if (reduced || !view.current) {
      opacity.set(0);
      setMode(target);
      return;
    }
    captureRef(view, { format: 'png', result: 'tmpfile' }).then(uri => {
      if (!live) { releaseCapture(uri); return; }
      clearFadeTimer();
      if (captured.current) releaseCapture(captured.current);
      captured.current = uri;
      opacity.set(1);
      setImage(uri);
      setMode(target);
    }).catch(() => { if (live) setMode(target); });
    return () => { live = false; };
  }, [target, mode, reduced, opacity, clearFadeTimer]);
  useEffect(() => {
    if (!reduced) return;
    clearFadeTimer();
    opacity.set(0);
    if (captured.current) discard(captured.current);
  }, [reduced, clearFadeTimer, discard, opacity]);
  useEffect(() => () => {
    clearFadeTimer();
    if (captured.current) releaseCapture(captured.current);
  }, [clearFadeTimer]);
  const overlay = useAnimatedStyle(() => ({ opacity: opacity.get() }));
  const reveal = () => {
    if (!image || captured.current !== image) return;
    clearFadeTimer();
    const duration = reduced ? 0 : 240;
    opacity.set(withTiming(0, { duration }));
    // Remove the native Image after its fade, and release the private PNG.
    // Android Image does not implement View's pointerEvents contract, so its
    // host also lives inside a non-interactive View in App.tsx.
    fadeTimer.current = setTimeout(() => discard(image), duration + 40);
  };
  return { view, mode, image, overlay, reveal };
}
