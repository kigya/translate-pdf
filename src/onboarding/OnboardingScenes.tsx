import React, { useEffect, useRef } from 'react';
import { PanResponder, Pressable, Text, View, useWindowDimensions } from 'react-native';
import Animated, { cancelAnimation, Extrapolation, interpolate, useAnimatedStyle, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { Palette } from '../ui/theme';
import { Translator } from '../i18n/messages';

function useLoop(duration: number, enabled: boolean, reduced: boolean) {
  const progress = useSharedValue(0);
  useEffect(() => {
    if (!enabled || reduced) { cancelAnimation(progress); progress.value = 0; return; }
    progress.value = withRepeat(withTiming(1, { duration }), -1, false);
    return () => { cancelAnimation(progress); progress.value = 0; };
  }, [duration, enabled, progress, reduced]);
  return progress;
}

function TextLine({ colors, width = '76%', opacity = 1 }: { colors: Palette; width?: number | `${number}%`; opacity?: number }) {
  return <View style={{ height: 7, width, borderRadius: 8, backgroundColor: colors.border, opacity }} />;
}

function ExampleBadge({ colors, t }: { colors: Palette; t: Translator }) {
  return <View style={{ position: 'absolute', top: 4, right: 15, backgroundColor: colors.softAccent, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 20 }}><Text style={{ color: colors.accent, fontWeight: '800', fontSize: 10 }}>{t('example')}</Text></View>;
}

function ComparisonText({ colors, translated, width, testID }: { colors: Palette; translated: boolean; width: number; testID?: string }) {
  const heading = translated ? 'RU · Охлаждение' : 'EN · Cooling';
  const label = translated ? 'Бачок охлаждения' : 'Coolant tank';
  const instruction = translated ? 'Проверяйте на холодную.' : 'Check when cold.';
  const note = translated ? 'Не открывайте горячим.' : 'Never open hot.';
  const left = translated ? 9 : width * 0.58;
  const textWidth = translated ? Math.max(100, width * 0.42) : Math.max(84, width * 0.36);
  return <View testID={testID ?? (translated ? 'comparison-russian-canvas' : 'comparison-english-canvas')} style={{ width, height: 72 }}>
    <View style={{ position: 'absolute', top: 8, left, width: textWidth, gap: 3 }}>
      <Text numberOfLines={1} style={{ color: translated ? colors.accent : colors.muted, fontSize: 9, fontWeight: '800' }}>{heading}</Text>
      <Text numberOfLines={2} style={{ color: colors.text, fontSize: 10, lineHeight: 12, fontWeight: '700', flexShrink: 1 }}>{label}</Text>
      <Text numberOfLines={1} style={{ color: colors.muted, fontSize: 9, lineHeight: 11, flexShrink: 1 }}>{instruction} {note}</Text>
    </View>
  </View>;
}

export function TranslationScene({ colors, t, active, reduced }: { colors: Palette; t: Translator; active: boolean; reduced: boolean }) {
  const { width } = useWindowDimensions();
  const size = Math.min(360, Math.max(260, width - 58));
  const progress = useLoop(6000, active, reduced);
  const slide = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0, 0.18, 0.82, 1], [0.78, 1, 1, 0.78]), transform: [{ translateY: interpolate(progress.value, [0, 0.5, 1], [6, -4, 6]) }] }));
  const accent = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0, 0.32, 0.5, 0.72, 1], [0.08, 0.28, 0.18, 0.28, 0.08]), transform: [{ scale: interpolate(progress.value, [0, 0.5, 1], [0.95, 1.06, 0.95]) }] }));
  const reveal = useSharedValue(0.55);
  const pan = useRef(PanResponder.create({ onMoveShouldSetPanResponder: (_, g) => Math.abs(g.dx) > 3, onPanResponderMove: (_, g) => { reveal.value = Math.max(0.12, Math.min(0.88, 0.52 + g.dx / size)); }, onPanResponderRelease: (_, g) => { reveal.value = Math.max(0.12, Math.min(0.88, 0.52 + g.dx / size)); } })).current;
  const compareWidth = size - 24;
  const translatedWindow = useAnimatedStyle(() => ({ width: `${reveal.value * 100}%` }));
  const divider = useAnimatedStyle(() => ({ left: `${reveal.value * 100}%` }));
  const [showTranslation, setShowTranslation] = React.useState(true);
  return <View testID="translation-scene" style={{ width: size, height: 330, alignSelf: 'center', alignItems: 'center', justifyContent: 'center' }}>
    <ExampleBadge colors={colors} t={t} />
    <Animated.View style={[{ position: 'absolute', width: 250, height: 250, borderRadius: 125, backgroundColor: colors.softAccent }, accent]} />
    <Animated.View style={[{ position: 'absolute', top: 75, width: compareWidth, height: 146, borderRadius: 16, overflow: 'hidden', borderWidth: 2, borderColor: colors.surface, elevation: 7 }, slide]}>
      <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, right: 0, overflow: 'hidden', borderRadius: 14, backgroundColor: colors.raised }}>
        <Text style={{ position: 'absolute', top: 8, left: 0, right: 0, textAlign: 'center', color: colors.muted, fontSize: 9, fontWeight: '800' }}>Cooling system · Система охлаждения</Text>
        <View style={{ position: 'absolute', left: 0, right: 0, top: 26, height: 45, alignItems: 'center', justifyContent: 'center' }}>
          <View style={{ width: 116, height: 36, borderRadius: 7, borderWidth: 2, borderColor: colors.muted, backgroundColor: colors.surface, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-evenly', paddingHorizontal: 7 }}>
            <View style={{ width: 7, height: 27, borderRadius: 3, backgroundColor: colors.accent }} />
            {Array.from({ length: 7 }, (_, index) => <View key={index} style={{ width: 3, height: index % 2 ? 21 : 25, borderRadius: 2, backgroundColor: colors.muted }} />)}
            <View style={{ width: 7, height: 27, borderRadius: 3, backgroundColor: colors.accent }} />
          </View>
        </View>
      <View testID="comparison-english-underlay-window" style={{ position: 'absolute', left: 0, right: 0, top: 74, bottom: 0, overflow: 'hidden' }}>
        <ComparisonText colors={colors} translated={false} width={compareWidth} testID="comparison-english-canvas" />
      </View>
      </View>
      <Animated.View testID="comparison-russian-window" pointerEvents="none" style={[{ position: 'absolute', left: 0, top: 74, bottom: 0, overflow: 'hidden', borderBottomLeftRadius: 14, backgroundColor: colors.surface }, translatedWindow]}>
        <ComparisonText colors={colors} translated width={compareWidth} />
      </Animated.View>
      <View {...pan.panHandlers} accessible accessibilityRole="adjustable" accessibilityLabel={`${t('languagePair')}. ${t('example')}`} accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]} onAccessibilityAction={event => { reveal.value = Math.max(0, Math.min(1, reveal.value + (event.nativeEvent.actionName === 'increment' ? 0.2 : -0.2))); }} style={{ position: 'absolute', top: 74, left: 0, right: 0, bottom: 0 }} />
      <Animated.View pointerEvents="none" style={[{ position: 'absolute', top: 74, bottom: 0, width: 3, backgroundColor: colors.accent, zIndex: 3 }, divider]} />
    </Animated.View>
    <View style={{ position: 'absolute', top: 239, width: compareWidth, flexDirection: 'row', gap: 8 }}><Pressable accessibilityRole="button" onPress={() => { setShowTranslation(false); reveal.value = withTiming(0, { duration: reduced ? 0 : 180 }); }} style={{ flex: 1, minWidth: 0, alignItems: 'center', backgroundColor: !showTranslation ? colors.accent : colors.surface, paddingHorizontal: 8, paddingVertical: 7, borderRadius: 20 }}><Text numberOfLines={2} style={{ color: !showTranslation ? colors.onAccent : colors.text, fontSize: 9, textAlign: 'center', flexShrink: 1 }}>{t('tapOriginal')}</Text></Pressable><Pressable accessibilityRole="button" onPress={() => { setShowTranslation(true); reveal.value = withTiming(1, { duration: reduced ? 0 : 180 }); }} style={{ flex: 1, minWidth: 0, alignItems: 'center', backgroundColor: showTranslation ? colors.accent : colors.surface, paddingHorizontal: 8, paddingVertical: 7, borderRadius: 20 }}><Text numberOfLines={2} style={{ color: showTranslation ? colors.onAccent : colors.text, fontSize: 9, textAlign: 'center', flexShrink: 1 }}>{t('tapTranslation')}</Text></Pressable></View>
  </View>;
}

export function OcrScene({ colors, t, active, reduced }: { colors: Palette; t: Translator; active: boolean; reduced: boolean }) {
  const progress = useLoop(5400, active, reduced);
  const [showTranslation, setShowTranslation] = React.useState(true);
  const [selectedLabel, setSelectedLabel] = React.useState<'inlet' | 'outlet'>('inlet');
  const scan = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0, 0.12, 0.8, 1], [0.25, 1, 1, 0.25]), transform: [{ translateY: interpolate(progress.value, [0, 1], [-27, 27]) }] }));
  const selectedDescription = selectedLabel === 'inlet' ? 'Inlet connection · Входной патрубок' : 'Outlet connection · Выходной патрубок';
  const labelValue = (label: 'inlet' | 'outlet') => label === 'inlet'
    ? (showTranslation ? 'ВХОД' : 'INLET')
    : (showTranslation ? 'ВЫХОД' : 'OUTLET');
  const selectLabel = (label: 'inlet' | 'outlet') => <Pressable
    key={label}
    accessibilityRole="button"
    accessibilityLabel={`${label === 'inlet' ? 'Inlet' : 'Outlet'} label`}
    accessibilityState={{ selected: selectedLabel === label }}
    onPress={() => setSelectedLabel(label)}
    style={{ flex: 1, minWidth: 0, paddingHorizontal: 8, paddingVertical: 6, borderRadius: 9, alignItems: 'center', backgroundColor: selectedLabel === label ? colors.softAccent : colors.surface, borderWidth: 1, borderColor: selectedLabel === label ? colors.accent : colors.border }}>
    <Text style={{ color: selectedLabel === label ? colors.accent : colors.muted, fontSize: 10, fontWeight: '800' }}>{labelValue(label)}</Text>
  </Pressable>;
  return <View style={{ height: 330, width: '100%', alignItems: 'center', justifyContent: 'center' }}>
    <ExampleBadge colors={colors} t={t} />
    <View style={{ width: 250, height: 258, backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1, borderRadius: 20, padding: 14, gap: 10, elevation: 4 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}><Text style={{ color: colors.text, fontSize: 11, fontWeight: '800' }}>{t('scanLabel')}</Text><Text style={{ color: colors.accent, fontSize: 9, fontWeight: '800' }}>EN · RU</Text></View>
      <View style={{ height: 122, borderRadius: 13, backgroundColor: colors.raised, overflow: 'hidden', padding: 8, justifyContent: 'space-between' }}>
        <View style={{ flexDirection: 'row', gap: 7 }}>{selectLabel('inlet')}{selectLabel('outlet')}</View>
        <View style={{ alignSelf: 'center', width: 170, height: 43, borderRadius: 8, borderWidth: 2, borderColor: colors.muted, backgroundColor: colors.surface, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-evenly', paddingHorizontal: 8 }}>
          <View style={{ width: 9, height: 33, borderRadius: 3, backgroundColor: colors.accent, opacity: 0.78 }} />
          {Array.from({ length: 8 }, (_, index) => <View key={index} style={{ width: 3, height: index % 2 === 0 ? 30 : 25, borderRadius: 2, backgroundColor: colors.muted, opacity: 0.8 }} />)}
          <View style={{ width: 9, height: 33, borderRadius: 3, backgroundColor: colors.accent, opacity: 0.78 }} />
        </View>
        <Animated.View style={[{ position: 'absolute', top: 68, left: 0, right: 0, height: 2, backgroundColor: colors.accent, elevation: 5 }, scan]} />
      </View>
      <View style={{ gap: 4 }}><Text style={{ color: colors.muted, fontSize: 9, fontWeight: '700' }}>{showTranslation ? t('translated') : t('original')}</Text><Text numberOfLines={2} style={{ color: colors.text, fontSize: 11, fontWeight: '700', flexShrink: 1 }}>{selectedDescription}</Text></View>
    </View>
    <View style={{ position: 'absolute', bottom: 7, flexDirection: 'row', gap: 8 }}><Pressable accessibilityRole="button" onPress={() => setShowTranslation(false)} style={{ backgroundColor: !showTranslation ? colors.accent : colors.surface, borderWidth: 1, borderColor: colors.border, paddingHorizontal: 11, paddingVertical: 8, borderRadius: 20 }}><Text style={{ color: !showTranslation ? colors.onAccent : colors.text, fontSize: 10 }}>{t('tapOriginal')}</Text></Pressable><Pressable accessibilityRole="button" onPress={() => setShowTranslation(true)} style={{ backgroundColor: showTranslation ? colors.accent : colors.surface, borderWidth: 1, borderColor: colors.border, paddingHorizontal: 11, paddingVertical: 8, borderRadius: 20 }}><Text style={{ color: showTranslation ? colors.onAccent : colors.text, fontSize: 10 }}>{t('tapTranslation')}</Text></Pressable></View>
  </View>;
}

export function ModelScene({ colors, t, active, reduced }: { colors: Palette; t: Translator; active: boolean; reduced: boolean }) {
  const progress = useLoop(6200, active, reduced);
  const glow = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0, 0.5, 1], [0.16, 0.42, 0.16]), transform: [{ scale: interpolate(progress.value, [0, 0.5, 1], [0.92, 1.08, 0.92]) }] }));
  const download = useAnimatedStyle(() => ({ width: `${interpolate(progress.value, [0, 1], [42, 86])}%` }));
  return <View style={{ height: 330, alignItems: 'center', justifyContent: 'center' }}>
    <ExampleBadge colors={colors} t={t} />
    <Animated.View style={[{ position: 'absolute', width: 235, height: 235, borderRadius: 120, backgroundColor: colors.softAccent }, glow]} />
    <View style={{ width: 235, padding: 18, borderRadius: 24, backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1, gap: 15, elevation: 4 }}>
      <View style={{ width: 52, height: 52, borderRadius: 17, backgroundColor: colors.softAccent, justifyContent: 'center', alignItems: 'center' }}><Text style={{ fontSize: 22 }}>◈</Text></View>
      <Text style={{ fontSize: 19, color: colors.text, fontWeight: '800' }}>Hy-MT2 · Q4_K_M</Text><Text style={{ color: colors.muted, fontSize: 12 }}>1.13 GB · {t('modelStrong')}</Text>
      <View style={{ height: 8, borderRadius: 8, backgroundColor: colors.raised, overflow: 'hidden' }}><Animated.View style={[{ height: '100%', backgroundColor: colors.accent }, download]} /></View>
      <Text style={{ color: colors.muted, fontSize: 11 }}>{t('downloadLabel')} · {t('checksumPinned')}</Text>
    </View>
    <View style={{ position: 'absolute', bottom: 5, flexDirection: 'row', gap: 8, maxWidth: 310 }}><View style={{ padding: 11, borderRadius: 16, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, flex: 1 }}><Text style={{ fontSize: 10, color: colors.success, fontWeight: '700', flexShrink: 1 }}>✓ {t('exampleRecommendation')}</Text></View><View style={{ padding: 11, borderRadius: 16, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, flex: 1 }}><Text style={{ fontSize: 10, color: colors.muted, flexShrink: 1 }}>{t('modelSmaller')}</Text></View></View>
  </View>;
}

export function ProgressScene({ colors, t, active, reduced }: { colors: Palette; t: Translator; active: boolean; reduced: boolean; ios: boolean }) {
  const [paused, setPaused] = React.useState(false);
  const progress = useSharedValue(0);
  useEffect(() => {
    if (!active || reduced) { cancelAnimation(progress); progress.value = 0; return; }
    if (paused) { cancelAnimation(progress); return; }
    progress.value = withRepeat(withTiming(1, { duration: 7000 }), -1, false);
    return () => cancelAnimation(progress);
  }, [active, paused, progress, reduced]);
  const fill = useAnimatedStyle(() => ({ width: `${interpolate(progress.value, [0, 0.85, 0.93, 1], [18, 83, 83, 18], Extrapolation.CLAMP)}%` }));
  const pulse = useAnimatedStyle(() => ({ opacity: interpolate(progress.value, [0, 0.5, 1], [0.5, 1, 0.5]), transform: [{ scale: interpolate(progress.value, [0, 0.5, 1], [0.96, 1.04, 0.96]) }] }));
  const label = paused ? t('demoPaused') : t('demoWorking');
  return <View testID="progress-scene" style={{ height: 330, alignItems: 'center', justifyContent: 'center' }}>
    <View testID="progress-demo-card" style={{ width: 246, height: 250, borderRadius: 30, backgroundColor: colors.surface, borderColor: colors.border, borderWidth: 1, padding: 12, gap: 8, elevation: 4 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}><Text style={{ color: colors.text, fontWeight: '800' }}>{t('appName')}</Text><Text style={{ color: colors.accent, fontWeight: '700' }}>•••</Text></View>
      <View style={{ height: 1, backgroundColor: colors.border }} />
      <View style={{ height: 56, borderRadius: 12, backgroundColor: colors.raised, padding: 10, justifyContent: 'center', gap: 7 }}><TextLine colors={colors} width="90%"/><TextLine colors={colors}/><TextLine colors={colors} width="62%"/></View>
      <View style={{ gap: 6 }}><Text style={{ color: colors.text, fontSize: 13, fontWeight: '700' }}>{paused ? t('demoPaused') : t('progress')}</Text><Animated.View style={[{ height: 7, borderRadius: 8, backgroundColor: colors.raised, overflow: 'hidden' }, pulse]}><Animated.View style={[{ height: '100%', backgroundColor: colors.accent }, fill]} /></Animated.View><Text numberOfLines={1} style={{ color: colors.muted, fontSize: 10 }}>{label}</Text></View>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <Pressable accessibilityRole="button" onPress={() => setPaused(!paused)} style={{ paddingHorizontal: 12, paddingVertical: 7, backgroundColor: colors.raised, borderRadius: 12 }}><Text style={{ color: colors.text, fontSize: 11, fontWeight: '700' }}>{paused ? t('resume') : t('pause')}</Text></Pressable>
        <View testID="progress-saved-result" style={{ flex: 1, minWidth: 0, paddingHorizontal: 9, paddingVertical: 7, borderRadius: 13, backgroundColor: colors.raised, borderWidth: 1, borderColor: colors.border }}><Text numberOfLines={2} style={{ color: colors.success, fontWeight: '800', fontSize: 9, textAlign: 'center', flexShrink: 1 }}>✓ {t('demoReady')}</Text></View>
      </View>
    </View>
    <ExampleBadge colors={colors} t={t} />
  </View>;
}
