import { useRef, useState, type ReactNode } from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type LayoutChangeEvent,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import Animated, {
  useAnimatedRef,
  useAnimatedScrollHandler,
  useAnimatedStyle,
  useSharedValue,
  type SharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { PressJelly } from '../press-jelly';
import { WordCascade } from '../word-cascade';
import { useReduceMotion } from '../../../lib/kinetik/hooks/use-reduce-motion';
import { useKinetikTheme } from '../../../lib/kinetik/tokens/theme';

export type OnboardingSlide = {
  key: string;
  /** Small chip above the title, e.g. "Easy sign-up". */
  tag?: string;
  title: string;
  /**
   * Fills the rounded panel at the top. Receives whether its slide is the
   * current one, so it can play only while visible. Defaults to built-in
   * illustrations.
   */
  visual?: (active: boolean) => ReactNode;
};

export type OnboardingProps = {
  /** Called by the button on the last slide. */
  onFinish: () => void;
  /** Called by the link under the button. Hidden when omitted. */
  onSkip?: () => void;
  slides: readonly OnboardingSlide[];
  active?: boolean;
  disabled?: boolean;
  renderAction?: (last: boolean, next: () => void) => ReactNode;
  /** Button label before the last slide. Defaults to "Continue". */
  nextLabel?: string;
  /** Button label on the last slide. Defaults to "Get started". */
  finishLabel?: string;
  /** Link label. Defaults to "Maybe later". */
  skipLabel?: string;
  progressLabel?: string;
};

/**
 * A paged welcome: a rounded illustration panel, a thin progress line that
 * follows the finger, a tag and a title that rises in word by word, and one
 * clear button with a quiet "Maybe later" under it.
 */
export function Onboarding({
  onFinish,
  onSkip,
  slides,
  active = true,
  disabled = false,
  renderAction,
  nextLabel = 'Continue',
  finishLabel = 'Get started',
  skipLabel = 'Maybe later',
  progressLabel = 'Onboarding progress',
}: OnboardingProps) {
  const { colors, space, font } = useKinetikTheme();
  const reduced = useReduceMotion();
  const insets = useSafeAreaInsets();
  const { height: screenHeight } = useWindowDimensions();
  const scroller = useAnimatedRef<Animated.ScrollView>();
  const offset = useSharedValue(0);
  const widthRef = useRef(0);
  const [width, setWidth] = useState(0);
  const [height, setHeight] = useState(0);
  const [panelBottom, setPanelBottom] = useState(0);
  const [index, setIndex] = useState(0);
  const count = slides.length;
  const last = count === 0 || index >= count - 1;
  const compact = screenHeight < 740;
  const fallbackScale = screenHeight < 620 ? 0.58 : compact ? 0.74 : 1;
  const visualScale = height > 0 ? Math.max(0.5, Math.min(1, (panelBottom - 24) / 330)) : fallbackScale;

  const onScroll = useAnimatedScrollHandler((e) => {
    offset.set(e.contentOffset.x);
  });

  const onLayout = (e: LayoutChangeEvent) => {
    widthRef.current = e.nativeEvent.layout.width;
    setWidth(e.nativeEvent.layout.width);
  };

  const settle = (e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const w = widthRef.current;
    if (w > 0 && count > 0) {
      setIndex(Math.max(0, Math.min(count - 1, Math.round(e.nativeEvent.contentOffset.x / w))));
    }
  };

  const settleWithoutMomentum = (e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const w = widthRef.current;
    const { contentOffset, velocity } = e.nativeEvent;
    if (w <= 0 || count <= 0 || velocity?.x !== 0) return;
    const page = Math.round(contentOffset.x / w);
    if (page < 0 || page >= count || Math.abs(contentOffset.x - page * w) > 1) return;
    setIndex(page);
  };

  const next = () => {
    if (disabled) return;
    if (last) return onFinish();
    const to = index + 1;
    scroller.current?.scrollTo({ x: to * widthRef.current, animated: !reduced });
    // Keep the incoming title/illustration inactive until the pager settles.
    // Reduced motion scrolls immediately, so it can settle in the same frame.
    if (reduced) setIndex(to);
  };

  const pad = space[6];

  return (
    <View
      style={[
        styles.fill,
        {
          backgroundColor: colors.background,
          paddingTop: insets.top + space[3],
          paddingBottom: insets.bottom + space[4],
        },
      ]}
      onLayout={onLayout}
      testID="onboarding"
    >
      <Animated.ScrollView
        ref={scroller}
        horizontal
        pagingEnabled
        scrollEnabled={!disabled}
        showsHorizontalScrollIndicator={false}
        onScroll={onScroll}
        scrollEventThrottle={16}
        onScrollEndDrag={settleWithoutMomentum}
        onMomentumScrollEnd={settle}
        onLayout={(e) => setHeight(e.nativeEvent.layout.height)}
        style={styles.fill}
      >
        {width > 0 &&
          slides.map((slide, i) => {
            const sceneActive = active && i === index;
            return (
              <View
                key={slide.key}
                style={{
                  width,
                  height: height || undefined,
                  paddingHorizontal: pad,
                  paddingBottom: compact ? space[2] : space[4],
                }}
                accessibilityElementsHidden={!sceneActive}
                importantForAccessibility={sceneActive ? 'auto' : 'no-hide-descendants'}
              >
                <Panel testID="onboarding-illustration-panel" compact={compact} onLayout={(event) => setPanelBottom(event.nativeEvent.layout.y + event.nativeEvent.layout.height)}>
                  <View
                    testID="onboarding-visual-stage"
                    style={{
                      flex: 1,
                      alignItems: 'center',
                      justifyContent: 'center',
                      transform: [{ scale: visualScale }],
                    }}
                  >
                    {slide.visual?.(sceneActive)}
                  </View>
                </Panel>
                <View style={{ gap: space[3], alignItems: 'flex-start', marginTop: space[8], flexShrink: 0 }}>
                  {slide.tag ? (
                    <View
                      style={[
                        styles.tag,
                        { backgroundColor: colors.surfaceRaised, borderColor: colors.border },
                      ]}
                    >
                      <Text
                        style={{
                          color: colors.textMuted,
                          fontSize: font.size.xs,
                          fontWeight: font.weight.semibold,
                        }}
                      >
                        {slide.tag}
                      </Text>
                    </View>
                  ) : null}
                  <WordCascade
                    text={slide.title}
                    active={sceneActive}
                    replayKey={sceneActive ? `${slide.key}-on` : slide.key}
                    stagger={55}
                    rise={14}
                    style={{
                      color: colors.text,
                      fontSize: font.size['2xl'],
                      lineHeight: 34,
                      fontWeight: font.weight.bold,
                      letterSpacing: font.tracking.tight,
                    }}
                  />
                </View>
              </View>
            );
          })}
      </Animated.ScrollView>

      {count > 0 ? (
        <ProgressTrack
          count={count}
          index={index}
          offset={offset}
          width={width}
          inset={pad}
          label={progressLabel}
          top={insets.top + space[3] + panelBottom + space[3]}
        />
      ) : null}

      <View style={{ paddingHorizontal: pad, gap: space[1] }}>
        {renderAction ? renderAction(last, next) : <PressJelly flatAppearance block size="lg" onPress={next} disabled={disabled}>{last ? finishLabel : nextLabel}</PressJelly>}
        {onSkip ? <Pressable accessibilityRole="button" accessibilityLabel={skipLabel} onPress={onSkip} disabled={disabled} style={{ minHeight: 44, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.textMuted, fontSize: font.size.sm, fontWeight: font.weight.medium }}>{skipLabel}</Text></Pressable> : null}
      </View>
    </View>
  );
}

/** The rounded panel every illustration sits in. */
function Panel({ children, compact, onLayout, testID }: { children: ReactNode; compact: boolean; onLayout: (event: LayoutChangeEvent) => void; testID?: string }) {
  const { colors, radius } = useKinetikTheme();
  return (
    <View
      style={[
        styles.panel,
        compact && styles.compactPanel,
        {
          backgroundColor: colors.surface,
          borderColor: colors.border,
          borderRadius: radius.xl,
        },
      ]}
      onLayout={onLayout}
      testID={testID}
    >
      {children}
    </View>
  );
}

/**
 * One fixed progress track below the paged content. Its active segment follows
 * the scroll offset while the track itself remains in the screen frame.
 */
function ProgressTrack({
  index,
  count,
  offset,
  width,
  inset,
  label,
  top,
}: {
  index: number;
  count: number;
  offset: SharedValue<number>;
  width: number;
  inset: number;
  label: string;
  top: number;
}) {
  const { colors } = useKinetikTheme();
  const inner = Math.max(0, width - inset * 2);
  const segment = inner / count;
  const style = useAnimatedStyle(() => {
    const progress = width > 0 ? Math.max(0, Math.min(count - 1, offset.get() / width)) : index;
    return { transform: [{ translateX: progress * segment }] };
  });
  return (
    <View
      testID="onboarding-progress"
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel={label}
      accessibilityValue={{ min: 1, max: count, now: Math.min(count, index + 1) }}
      pointerEvents="none"
      style={[styles.fixedTrack, { top, left: inset, width: inner, backgroundColor: colors.track }]}
    >
      <Animated.View
        testID="onboarding-progress-indicator"
        style={[styles.segment, { width: segment, backgroundColor: colors.text }, style]}
      />
    </View>
  );
}

/** Three cards that fan out and drift, standing for "everything in one place". */
const styles = StyleSheet.create({
  fill: { flex: 1 }, panel: { flex: 1, minHeight: 132, maxHeight: 360, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  compactPanel: { minHeight: 112, maxHeight: 290 },
  fixedTrack: { position: 'absolute', alignSelf: 'center', height: 3, borderRadius: 3, overflow: 'hidden' },
  tag: { paddingHorizontal: 10, paddingVertical: 5, borderWidth: 1, borderRadius: 30 },
  track: { height: 3, borderRadius: 3, overflow: 'hidden' }, segment: { height: 3, borderRadius: 3 },
});
