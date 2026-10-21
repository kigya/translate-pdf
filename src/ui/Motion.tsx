import { useMotionReduced } from './useMotionReduced';
import React, { useEffect, useRef, useState } from 'react';
import { AppState, AppStateStatus, Pressable, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';
import Animated, { cancelAnimation, Easing, interpolate, useAnimatedProps, useAnimatedStyle, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { Palette, palettes } from './theme';

const AnimatedView = Animated.View;
const AnimatedPath = Animated.createAnimatedComponent(Path);

function useIsForeground() {
  const [state, setState] = useState<AppStateStatus>((AppState.currentState ?? 'active') as AppStateStatus);
  useEffect(() => {
    const subscription = AppState.addEventListener('change', setState);
    return () => subscription.remove();
  }, []);
  return state !== 'background' && state !== 'inactive';
}

export function AppIcon({ size = 56, color = '#FFFFFF', background = 'transparent', active = true, reduced, draw = false }: {
  size?: number; color?: string; background?: string; active?: boolean; reduced?: boolean; draw?: boolean;
}) {
  const systemReduced = useMotionReduced();
  const shouldReduce = reduced ?? systemReduced;
  const foreground = useIsForeground();
  const motion = useSharedValue(0);
  const outline = useSharedValue(draw && !shouldReduce ? 0 : 1);
  useEffect(() => {
    outline.value = draw && !shouldReduce ? 0 : 1;
    if (draw && active && foreground && !shouldReduce) outline.value = withTiming(1, { duration: 850 });
    return () => cancelAnimation(outline);
  }, [active, draw, foreground, outline, shouldReduce]);
  const documentProps = useAnimatedProps(() => ({
    strokeDashoffset: 360 * (1 - outline.value),
    fillOpacity: interpolate(outline.value, [0, 0.6, 1], [0, 0, 1]),
  }));
  useEffect(() => {
    if (!active || !foreground || shouldReduce) { cancelAnimation(motion); motion.value = 0; return; }
    motion.value = withRepeat(withTiming(1, { duration: 1500 }), -1, false);
    return () => { cancelAnimation(motion); motion.value = 0; };
  }, [active, foreground, motion, shouldReduce]);
  const firstArrow = useAnimatedStyle(() => ({ transform: [{ translateX: interpolate(motion.value, [0, 0.5, 1], [0, 4, 0]) }] }));
  const secondArrow = useAnimatedStyle(() => ({ transform: [{ translateX: interpolate(motion.value, [0, 0.5, 1], [0, -4, 0]) }] }));
  return <View testID="app-motion-icon" accessible accessibilityRole="image" accessibilityLabel="Translate PDF" style={{ width: size, height: size, borderRadius: size * 0.23, backgroundColor: background, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
    <Svg width={size * 0.78} height={size * 0.82} viewBox="0 0 100 106" fill="none">
      <AnimatedPath d="M24 7h37l18 18v69a7 7 0 0 1-7 7H24a7 7 0 0 1-7-7V14a7 7 0 0 1 7-7Z" fill={color} stroke={color} strokeWidth={2.5} strokeDasharray="360 360" animatedProps={documentProps} />
      <Path d="M61 7v14a4 4 0 0 0 4 4h14" fill={color} opacity={0.7} />
    </Svg>
    <AnimatedView pointerEvents="none" style={[{ position: 'absolute', top: size * 0.37, left: size * 0.24 }, firstArrow]}>
      <Svg width={size * 0.52} height={size * 0.18} viewBox="0 0 56 20"><Path d="M3 10h42m-9-8 9 8-9 8" stroke={background === 'transparent' ? '#C62828' : background} strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" /></Svg>
    </AnimatedView>
    <AnimatedView pointerEvents="none" style={[{ position: 'absolute', top: size * 0.55, left: size * 0.24 }, secondArrow]}>
      <Svg width={size * 0.52} height={size * 0.18} viewBox="0 0 56 20"><Path d="M53 10H11m9 8-9-8 9-8" stroke={background === 'transparent' ? '#C62828' : background} strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" /></Svg>
    </AnimatedView>
  </View>;
}

function TabGlyph({ kind, color, active }: { kind: 'library' | 'settings'; color: string; active: boolean }) {
  const animated = useSharedValue(active ? 1 : 0);
  const reduced = useMotionReduced();
  useEffect(() => { animated.value = reduced ? (active ? 1 : 0) : withTiming(active ? 1 : 0, { duration: 220 }); }, [active, animated, reduced]);
  const style = useAnimatedStyle(() => ({ transform: [{ translateY: interpolate(animated.value, [0, 1], [0, -2]) }, { scale: interpolate(animated.value, [0, 1], [1, 1.1]) }] }));
  return <AnimatedView style={style} pointerEvents="none"><Svg width={24} height={24} viewBox="0 0 24 24" fill="none">
    {kind === 'library' ? <><Path d="M6 3h8l4 4v14H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z" stroke={color} strokeWidth={1.8} strokeLinejoin="round"/><Path d="M14 3v4h4M8 12h8M8 16h6" stroke={color} strokeWidth={1.8} strokeLinecap="round"/></> : <><Circle cx="12" cy="12" r="3" stroke={color} strokeWidth={1.8}/><Path d="m19.4 15 .1.1a1.8 1.8 0 0 1-2.5 2.5l-.1-.1a1.8 1.8 0 0 0-3 .8v.2a1.8 1.8 0 0 1-3.6 0v-.2a1.8 1.8 0 0 0-3-.8l-.1.1a1.8 1.8 0 0 1-2.5-2.5l.1-.1a1.8 1.8 0 0 0-.8-3h-.2a1.8 1.8 0 0 1 0-3.6h.2a1.8 1.8 0 0 0 .8-3l-.1-.1a1.8 1.8 0 0 1 2.5-2.5l.1.1a1.8 1.8 0 0 0 3-.8v-.2a1.8 1.8 0 0 1 3.6 0v.2a1.8 1.8 0 0 0 3 .8l.1-.1a1.8 1.8 0 0 1 2.5 2.5l-.1.1a1.8 1.8 0 0 0 .8 3h.2a1.8 1.8 0 0 1 0 3.6H20a1.8 1.8 0 0 0-.6 3Z" stroke={color} strokeWidth={1.5} strokeLinejoin="round"/></>}
  </Svg></AnimatedView>;
}

export function AnimatedTabBar({ active, onChange, colors = palettes.light, labels = { library: 'Library', settings: 'Settings' }, bottomInset = 0, direction = 'ltr' }: {
  active: 'library' | 'settings'; onChange: (tab: 'library' | 'settings') => void; colors?: Palette; labels?: { library: string; settings: string }; bottomInset?: number; direction?: 'ltr' | 'rtl';
}) {
  const reduced = useMotionReduced();
  const selectedPosition = active === 'library' ? (direction === 'rtl' ? 1 : 0) : (direction === 'rtl' ? 0 : 1);
  const indicator = useSharedValue(selectedPosition);
  useEffect(() => { indicator.value = reduced ? selectedPosition : withTiming(selectedPosition, { duration: 260 }); }, [active, direction, indicator, reduced, selectedPosition]);
  const indicatorStyle = useAnimatedStyle(() => ({ left: `${interpolate(indicator.value, [0, 1], [12.5, 62.5])}%` }));
  return <View testID="animated-tab-bar" style={{ backgroundColor: colors.surface, borderTopWidth: 1, borderColor: colors.border, paddingBottom: Math.max(8, bottomInset), paddingTop: 8, direction }}>
    {/* Positions above are physical left coordinates; keep Yoga from mirroring them a second time. */}
    <View testID="tab-indicator-track" style={{ direction: 'ltr', height: 3, position: 'absolute', top: 0, left: 0, right: 0 }}><AnimatedView testID="tab-indicator" style={[{ position: 'absolute', top: 0, width: '25%', height: 3, borderRadius: 3, backgroundColor: colors.accent }, indicatorStyle]} /></View>
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>{(['library', 'settings'] as const).map(tab => {
      const selected = active === tab;
      return <Pressable key={tab} accessibilityRole="tab" accessibilityLabel={labels[tab]} accessibilityState={{ selected }} onPress={() => onChange(tab)} style={{ flex: 1, minHeight: 52, alignItems: 'center', justifyContent: 'center', gap: 3 }}>
        <TabGlyph kind={tab} color={selected ? colors.accent : colors.muted} active={selected} />
        <Text style={{ color: selected ? colors.accent : colors.muted, fontSize: 11, fontWeight: selected ? '800' : '600' }}>{labels[tab]}</Text>
      </Pressable>;
    })}</View>
  </View>;
}

export function AnimatedNumber({ value, suffix = '', color = '#C62828', active = true, reduced, decimals, fontSize = 32 }: { value: number; suffix?: string; color?: string; active?: boolean; reduced?: boolean; decimals?: number; fontSize?: number }) {
  const systemReduced = useMotionReduced();
  const shouldReduce = reduced ?? systemReduced;
  const foreground = useIsForeground();
  const isActive = active && foreground;
  const decimalPlaces = Math.max(0, Math.min(2, decimals ?? value.toString().split('.')[1]?.length ?? 0));
  const [display, setDisplay] = useState(0);
  const frame = useRef<number | null>(null);
  useEffect(() => {
    if (!isActive || shouldReduce) { setDisplay(value); return; }
    const startValue = display;
    const startedAt = Date.now();
    const tick = () => {
      const progress = Math.min(1, (Date.now() - startedAt) / 680);
      const eased = 1 - (1 - progress) ** 3;
      setDisplay(Number((startValue + (value - startValue) * eased).toFixed(decimalPlaces)));
      if (progress < 1) frame.current = requestAnimationFrame(tick);
      else frame.current = null;
    };
    frame.current = requestAnimationFrame(tick);
    return () => { if (frame.current !== null) cancelAnimationFrame(frame.current); };
  // Intentionally only restart for a new target or visibility/accessibility state.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [decimalPlaces, isActive, shouldReduce, value]);
  return <Text accessibilityLabel={`${value.toFixed(decimalPlaces)}${suffix}`} style={{ color, fontSize, fontVariant: ['tabular-nums'], fontWeight: '800' }}>{decimalPlaces ? display.toFixed(decimalPlaces) : display}{suffix}</Text>;
}

export function DownloadSculpture({ progress = 0, active = true, reduced, color = '#C62828' }: { progress?: number; active?: boolean; reduced?: boolean; color?: string }) {
  const systemReduced = useMotionReduced();
  const shouldReduce = reduced ?? systemReduced;
  const foreground = useIsForeground();
  const phase = useSharedValue(0);
  useEffect(() => {
    if (!active || !foreground || shouldReduce) { cancelAnimation(phase); phase.value = 0; return; }
    // The value wraps at 2π, but every visible transform is periodic. Its
    // first and last frames therefore match instead of snapping at the seam.
    phase.value = withRepeat(withTiming(Math.PI * 2, { duration: 5200, easing: Easing.linear }), -1, false);
    return () => { cancelAnimation(phase); phase.value = 0; };
  }, [active, foreground, phase, shouldReduce]);
  const sculpture = useAnimatedStyle(() => {
    const swell = Math.sin(phase.value);
    const settle = Math.cos(phase.value);
    return { transform: [
      { rotate: `${swell * 2.8}deg` },
      { scaleX: 1 + swell * 0.035 },
      { scaleY: 1 + settle * 0.04 },
      { translateX: Math.sin(phase.value + 0.8) * 2 },
    ] };
  });
  const clamped = Math.round(Math.max(0, Math.min(1, progress)) * 100);
  return <View testID="download-sculpture" accessible accessibilityRole="progressbar" accessibilityValue={{ min: 0, max: 100, now: clamped }} style={{ width: 184, height: 164, alignItems: 'center', justifyContent: 'center' }}>
    <Svg width={184} height={148} viewBox="0 0 184 148" fill="none"><Circle cx="92" cy="75" r="66" fill={color} opacity={0.09}/></Svg>
    <AnimatedView style={[{ position: 'absolute' }, sculpture]} pointerEvents="none"><Svg width={184} height={148} viewBox="0 0 184 148" fill="none"><Path d="M49 94c-11-10-15-23-8-34 8-13 27-8 35-18 10-13 26-18 39-9 10 7 9 21 21 29 12 9 12 26 4 36-7 10-19 9-29 18-14 13-29 11-39 1-8-8-15-15-23-23Z" fill={color} opacity={0.88}/><Path d="M73 78h38m-12-12 12 12-12 12" stroke="#FFFFFF" strokeWidth="6" strokeLinecap="round" strokeLinejoin="round"/><Path d="M56 109h72" stroke={color} strokeOpacity={0.2} strokeWidth="8" strokeLinecap="round"/></Svg></AnimatedView>
    <View style={{ position: 'absolute', bottom: 2, left: 15, right: 15, height: 6, borderRadius: 6, backgroundColor: `${color}22`, overflow: 'hidden' }}><View style={{ height: '100%', width: `${clamped}%`, backgroundColor: color, borderRadius: 6 }} /></View>
  </View>;
}

export function Balloons({ active = false, reduced, color = '#C62828', onComplete }: { active?: boolean; reduced?: boolean; color?: string; onComplete?: () => void }) {
  const systemReduced = useMotionReduced();
  const shouldReduce = reduced ?? systemReduced;
  const foreground = useIsForeground();
  const started = useRef(false);
  const completed = useRef(false);
  const completion = useRef(onComplete);
  const lift = useSharedValue(0);
  useEffect(() => { completion.current = onComplete; }, [onComplete]);
  useEffect(() => {
    if (!active) {
      started.current = false;
      completed.current = false;
      cancelAnimation(lift);
      lift.value = 0;
      return;
    }
    if (!foreground) {
      started.current = false;
      completed.current = false;
      cancelAnimation(lift);
      lift.value = 0;
      return;
    }
    if (started.current) {
      if (shouldReduce && !completed.current) {
        completed.current = true;
        completion.current?.();
      }
      return;
    }
    started.current = true;
    completed.current = false;
    if (shouldReduce) { completed.current = true; completion.current?.(); return; }
    lift.value = withTiming(1, { duration: 1050 });
    const timeout = setTimeout(() => {
      if (completed.current) return;
      completed.current = true;
      completion.current?.();
    }, 1050);
    return () => { clearTimeout(timeout); cancelAnimation(lift); };
  }, [active, foreground, lift, shouldReduce]);
  const balloon = useAnimatedStyle(() => ({ opacity: interpolate(lift.value, [0, 0.16, 1], [0, 1, 0]), transform: [{ translateY: interpolate(lift.value, [0, 1], [34, -64]) }] }));
  return <View pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ width: 180, height: 110, position: 'relative' }}>
    {[0, 1, 2, 3].map((index) => <AnimatedView key={index} style={[{ position: 'absolute', left: 25 + index * 35, top: 34 + (index % 2) * 12 }, balloon]}><Svg width={28} height={46} viewBox="0 0 28 46"><Path d="M14 1C6 1 2 8 2 15c0 9 12 22 12 22s12-13 12-22C26 8 22 1 14 1Z" fill={index % 2 ? '#F4A261' : color}/><Path d="M14 37v8" stroke="#85716B" strokeWidth="1.5"/></Svg></AnimatedView>)}
  </View>;
}
