import React, { useEffect } from 'react';
import { View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import Animated, { cancelAnimation, Easing, interpolate, useAnimatedProps, useAnimatedStyle, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';

const AnimatedPath = Animated.createAnimatedComponent(Path);

/** An unfolding folio and sail share the silhouette of an F. Launcher artwork is separate. */
export function FolivelaMark({ color, active, reduced }: { color: string; active: boolean; reduced: boolean }) {
  const reveal = useSharedValue(reduced ? 1 : 0);
  const breathe = useSharedValue(0);
  useEffect(() => {
    if (reduced) {
      cancelAnimation(reveal); cancelAnimation(breathe);
      reveal.set(1); breathe.set(0);
      return;
    }
    if (!active) {
      cancelAnimation(reveal); cancelAnimation(breathe);
      return;
    }
    reveal.set(withTiming(1, { duration: 1500, easing: Easing.inOut(Easing.linear) }));
    breathe.set(withRepeat(withTiming(Math.PI * 2, { duration: 3600, easing: Easing.linear }), -1, false));
    return () => { cancelAnimation(reveal); cancelAnimation(breathe); };
  }, [active, reduced, reveal, breathe]);
  const contour = useAnimatedProps(() => ({
    strokeDashoffset: interpolate(reveal.get(), [0, 0.45, 1], [320, 0, 0]),
    fillOpacity: interpolate(reveal.get(), [0, 0.45, 0.9, 1], [0, 0, 1, 1]),
  }));
  const left = useAnimatedStyle(() => ({
    transform: [{ translateX: interpolate(reveal.get(), [0, 0.45, 1], [-16, -16, 0]) }, { rotate: `${interpolate(reveal.get(), [0, 0.45, 1], [-12, -12, 0])}deg` }],
  }));
  const sail = useAnimatedStyle(() => ({
    transform: [{ translateY: interpolate(reveal.get(), [0, 0.45, 1], [18, 18, 0]) }, { rotate: `${interpolate(reveal.get(), [0, 0.45, 1], [18, 18, 0])}deg` }],
  }));
  const accent = useAnimatedProps(() => ({ opacity: reveal.get() * 0.46 }));
  const shell = useAnimatedStyle(() => ({ transform: [{ translateY: Math.sin(breathe.get()) * 2 }, { scale: 1 + Math.sin(breathe.get()) * 0.012 }] }));
  return <Animated.View testID="folivela-splash-mark" accessible accessibilityRole="image" accessibilityLabel="Folivela" style={[{ width: 144, height: 152 }, shell]}>
    <Animated.View style={[{ position: 'absolute', inset: 0 }, left]}>
      <Svg width={144} height={152} viewBox="0 0 144 152">
        <AnimatedPath d="M26 132V32L70 17V115L26 132Z" fill={color} stroke={color} strokeWidth={2} strokeLinejoin="round" strokeDasharray="320 320" animatedProps={contour} />
      </Svg>
    </Animated.View>
    <Animated.View style={[{ position: 'absolute', inset: 0 }, sail]}>
      <Svg width={144} height={152} viewBox="0 0 144 152">
        <AnimatedPath d="M79 17L123 34L79 61V17ZM79 72L111 63L111 84L79 96V72Z" fill={color} stroke={color} strokeWidth={2} strokeLinejoin="round" strokeDasharray="320 320" animatedProps={contour} />
        <AnimatedPath d="M79 106L99 99L79 124Z" fill={color} animatedProps={accent} />
      </Svg>
    </Animated.View>
    <View pointerEvents="none" />
  </Animated.View>;
}
