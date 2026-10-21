import React, { useEffect } from 'react';
import { Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';
import Animated, { cancelAnimation, interpolate, useAnimatedStyle, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { Palette } from '../ui/theme';

function useLoop(duration: number, active: boolean, reduced: boolean) {
  const progress = useSharedValue(0);
  useEffect(() => {
    if (!active || reduced) { cancelAnimation(progress); progress.value = 0; return; }
    // Oscillate between matching endpoints so a repeated scene never snaps
    // from its last frame back to its first frame.
    progress.value = withRepeat(withTiming(1, { duration }), -1, true);
    return () => { cancelAnimation(progress); progress.value = 0; };
  }, [active, duration, progress, reduced]);
  return progress;
}

function DocumentPage({ colors, chinese = false }: { colors: Palette; chinese?: boolean }) {
  return <View style={{ width: 190, height: 238, backgroundColor: colors.surface, borderRadius: 13, padding: 15, borderWidth: 1, borderColor: colors.border, elevation: 4, overflow: 'hidden' }}>
    <View style={{ flexDirection: 'row', gap: 5, marginBottom: 12 }}><View style={{ width: 5, height: 5, borderRadius: 4, backgroundColor: colors.accent }} /><View style={{ width: 5, height: 5, borderRadius: 4, backgroundColor: colors.border }} /><View style={{ width: 5, height: 5, borderRadius: 4, backgroundColor: colors.border }} /></View>
    <View style={{ height: 4, width: '64%', backgroundColor: colors.accent, opacity: 0.8, borderRadius: 4, marginBottom: 14 }} />
    {chinese ? <><View style={{ height: 49, borderRadius: 7, backgroundColor: colors.raised, marginBottom: 12, alignItems: 'center', justifyContent: 'center' }}><Text maxFontSizeMultiplier={1.2} style={{ color: colors.muted, fontSize: 20, fontWeight: '600' }}>技术手册</Text></View><View style={{ gap: 7 }}><View style={{ height: 5, width: '95%', borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: '85%', borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: '70%', borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: '90%', borderRadius: 4, backgroundColor: colors.border }} /></View></> : <><View style={{ height: 49, borderRadius: 7, backgroundColor: colors.raised, marginBottom: 12, alignItems: 'center', justifyContent: 'center' }}><Text maxFontSizeMultiplier={1.2} style={{ color: colors.muted, fontSize: 16, fontWeight: '600' }}>Technical manual</Text></View><View style={{ gap: 7 }}><View style={{ height: 5, width: '92%', borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: '78%', borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: '90%', borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: '63%', borderRadius: 4, backgroundColor: colors.border }} /></View></>}
  </View>;
}

export function TranslationScene({ colors, active, reduced }: { colors: Palette; active: boolean; reduced: boolean }) {
  const progress = useLoop(5200, active, reduced);
  const translate = useAnimatedStyle(() => ({ transform: [{ translateY: interpolate(progress.value, [0, 0.5, 1], [2, -4, 2]) }] }));
  const reveal = useAnimatedStyle(() => ({ width: `${interpolate(progress.value, [0, 0.46, 0.68, 1], [4, 5, 100, 100])}%` }));
  return <View testID="translation-scene" style={{ width: 250, height: 330, alignItems: 'center', justifyContent: 'center' }}>
    <Animated.View style={translate}><DocumentPage colors={colors} chinese /></Animated.View>
    <View style={{ position: 'absolute', top: 153, left: 19, right: 19, height: 33, borderRadius: 16, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center', elevation: 3 }}><View style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}><View style={{ width: 35, height: 4, borderRadius: 4, backgroundColor: '#FFFFFF' }} /><Svg width={18} height={18} viewBox="0 0 18 18"><Path d="M2 9h13m-5-5 5 5-5 5" stroke="#FFFFFF" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></Svg><View style={{ width: 35, height: 4, borderRadius: 4, backgroundColor: '#FFFFFF' }} /></View></View>
    <View style={{ position: 'absolute', bottom: 34, left: 30, width: 190, height: 238, overflow: 'hidden', borderRadius: 12, borderWidth: 2, borderColor: colors.accent }}><Animated.View style={[{ overflow: 'hidden' }, reveal]}><DocumentPage colors={colors} /></Animated.View></View>
  </View>;
}

export function OcrScene({ colors, active, reduced }: { colors: Palette; active: boolean; reduced: boolean }) {
  const progress = useLoop(3600, active, reduced);
  const scan = useAnimatedStyle(() => ({ transform: [{ translateY: interpolate(progress.value, [0, 1], [0, 119]) }], opacity: interpolate(progress.value, [0, 0.1, 0.9, 1], [0.4, 1, 1, 0.4]) }));
  return <View testID="ocr-scene" style={{ height: 300, width: 250, alignItems: 'center', justifyContent: 'center' }}>
    <View style={{ width: 202, height: 255, backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1, borderRadius: 14, padding: 14, justifyContent: 'space-between', elevation: 4 }}>
      <View style={{ height: 5, width: '72%', backgroundColor: colors.border, borderRadius: 4 }} />
      <View style={{ height: 112, borderRadius: 9, backgroundColor: colors.raised, alignItems: 'center', justifyContent: 'center' }}><Svg width={154} height={86} viewBox="0 0 154 86"><Path d="M12 68h24V20H12zm106 0h24V20h-24M36 30h19v28H36zm63 0h19v28H99zM56 36h42M56 51h42" fill="none" stroke={colors.muted} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"/><Circle cx="77" cy="43" r="8" fill={colors.accent} opacity=".65"/></Svg><Animated.View style={[{ position: 'absolute', left: 6, right: 6, top: 12, height: 2, backgroundColor: colors.accent, shadowColor: colors.accent, shadowOpacity: 0.8, shadowRadius: 8 }, scan]} /></View>
      <View style={{ gap: 8 }}><View style={{ height: 5, width: '92%', backgroundColor: colors.border, borderRadius: 4 }} /><View style={{ height: 5, width: '80%', backgroundColor: colors.border, borderRadius: 4 }} /><View style={{ height: 5, width: '58%', backgroundColor: colors.border, borderRadius: 4 }} /></View>
      <View style={{ height: 28, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, borderRadius: 8, backgroundColor: colors.softAccent }}><View style={{ width: 34, height: 4, borderRadius: 4, backgroundColor: colors.accent }} /><Svg width={30} height={20} viewBox="0 0 30 20"><Path d="M3 10h24m-8-8 8 8-8 8" stroke={colors.accent} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></Svg></View>
    </View>
  </View>;
}

export function ModelScene({ colors, active, reduced }: { colors: Palette; active: boolean; reduced: boolean }) {
  const progress = useLoop(4400, active, reduced);
  const orbit = useAnimatedStyle(() => ({ transform: [{ rotate: `${interpolate(progress.value, [0, 1], [0, 360])}deg` }] }));
  const pulse = useAnimatedStyle(() => ({ transform: [{ scale: interpolate(progress.value, [0, 0.5, 1], [0.94, 1.06, 0.94]) }] }));
  return <View testID="model-scene" style={{ width: 260, height: 300, alignItems: 'center', justifyContent: 'center' }}>
    <Animated.View testID="model-orbit" style={[{ position: 'absolute', width: 190, height: 190, borderWidth: 1, borderColor: colors.border, borderRadius: 100 }, orbit]}><View testID="model-orbit-bead" style={{ position: 'absolute', top: -6, left: '46.6%', width: 13, height: 13, borderRadius: 8, backgroundColor: colors.accent }} /></Animated.View>
    <Animated.View style={[{ width: 130, height: 150, borderRadius: 24, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, alignItems: 'center', justifyContent: 'center', gap: 14, elevation: 4 }, pulse]}>
      <View style={{ width: 52, height: 62, borderRadius: 12, borderWidth: 2, borderColor: colors.accent, justifyContent: 'center', alignItems: 'center' }}><Svg width={32} height={32} viewBox="0 0 32 32"><Path d="M5 5h14l8 8v14H5zM19 5v8h8M10 18h12M10 23h8" fill="none" stroke={colors.accent} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" /></Svg></View>
      <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: 5, height: 23 }}>{[10, 16, 22, 14, 20].map((height, index) => <View key={index} style={{ height, width: 7, borderRadius: 4, backgroundColor: index === 4 ? colors.accent : colors.border }} />)}</View>
    </Animated.View>
    <View style={{ position: 'absolute', bottom: 24, flexDirection: 'row', alignItems: 'center', gap: 9 }}><Svg width={12} height={12} viewBox="0 0 12 12"><Circle cx="6" cy="6" r="5" fill={colors.success} /></Svg><View style={{ height: 5, width: 62, borderRadius: 4, backgroundColor: colors.border }} /><View style={{ height: 5, width: 35, borderRadius: 4, backgroundColor: colors.border }} /></View>
  </View>;
}

export function ProgressScene({ colors, active, reduced }: { colors: Palette; active: boolean; reduced: boolean; ios?: boolean }) {
  const progress = useLoop(4800, active, reduced);
  const fill = useAnimatedStyle(() => ({ width: `${interpolate(progress.value, [0, 1], [8, 92])}%` }));
  const pageStyle = useAnimatedStyle(() => ({ transform: [{ translateY: interpolate(progress.value, [0, 1], [-5, 5]) }, { rotate: `${interpolate(progress.value, [0, 1], [-1, 1])}deg` }] }));
  return <View testID="progress-scene" style={{ width: 260, height: 300, alignItems: 'center', justifyContent: 'center' }}>
    <View testID="progress-source-page" style={{ position: 'absolute', top: 42, left: 28, width: 86, height: 111, borderRadius: 11, borderColor: colors.border, borderWidth: 1, backgroundColor: colors.raised, alignItems: 'center', justifyContent: 'center', transform: [{ rotate: '-9deg' }] }}><Svg width={48} height={48} viewBox="0 0 48 48"><Path d="M7 6h24l10 10v26H7zM31 6v10h10M14 24h20M14 31h16M14 38h12" fill="none" stroke={colors.muted} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" /></Svg></View>
    <Animated.View style={[{ width: 111, height: 143, borderRadius: 12, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, alignItems: 'center', justifyContent: 'center', elevation: 5 }, pageStyle]}><Svg width={65} height={65} viewBox="0 0 65 65"><Path d="M10 7h32l13 13v37H10zM42 7v13h13M18 32h28M18 41h23M18 50h18" fill="none" stroke={colors.accent} strokeWidth="2.6" strokeLinejoin="round" strokeLinecap="round"/><Path d="M22 15h13" stroke={colors.muted} strokeWidth="2.6" strokeLinecap="round" /></Svg></Animated.View>
    <View testID="progress-track" style={{ position: 'absolute', bottom: 44, left: 28, right: 28, height: 7, borderRadius: 7, backgroundColor: colors.raised, overflow: 'hidden' }}><Animated.View style={[{ height: '100%', backgroundColor: colors.accent, borderRadius: 7 }, fill]} /></View>
    <View style={{ position: 'absolute', bottom: 25, flexDirection: 'row', gap: 5, alignItems: 'center' }}>{[0, 1, 2, 3, 4].map(index => <View key={index} style={{ width: 6, height: 6, borderRadius: 4, backgroundColor: index < 3 ? colors.accent : colors.border }} />)}</View>
  </View>;
}
