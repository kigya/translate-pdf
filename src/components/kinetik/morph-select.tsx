import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import Animated, {
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withDelay,
  withSpring,
  withTiming,
  type SharedValue,
} from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';

import { useHaptic } from '../../lib/kinetik/hooks/use-haptic';
import { useReduceMotion } from '../../lib/kinetik/hooks/use-reduce-motion';
import { durations, easings, spring } from '../../lib/kinetik/motion/motion';
import { useKinetikTheme } from '../../lib/kinetik/tokens/theme';

export type MorphSelectOption = {
  value: string;
  label: string;
  /** Optional shorter visual label for the closed trigger; full label stays accessible. */
  compactLabel?: string;
  /** A second line in the list, e.g. a time zone offset. */
  description?: string;
  disabled?: boolean;
};

export type MorphSelectProps = {
  options: readonly MorphSelectOption[];
  disabled?: boolean;
  value: string | null;
  onValueChange: (value: string) => void;
  /** Shown above the trigger and used as its accessible name. */
  label: string;
  /** Accessible label for the backdrop close action. Defaults to "Close list". */
  closeLabel?: string;
  /** Shown in the trigger while nothing is chosen. Defaults to "Choose". */
  placeholder?: string;
  /** Rows shown before the list scrolls. Defaults to 6. */
  maxVisible?: number;
  /** Minimum width of the expanded menu, clamped to the viewport. */
  minMenuWidth?: number;
  /** A tick on open and on choosing. Defaults to true. */
  haptics?: boolean;
  testID?: string;
  style?: StyleProp<ViewStyle>;
};

const TRIGGER = 52;
const ROW = 48;
const PAD = 6;
const MARGIN = 12;

type Rect = { x: number; y: number; width: number; height: number };

/**
 * A select whose trigger grows into its list. The field stretches open in
 * place, the options slide in under it one after another, and a highlight
 * glides to the one you pick before the list folds back into the field.
 */
export function MorphSelect({
  options,
  disabled = false,
  value,
  onValueChange,
  label,
  closeLabel = 'Close list',
  placeholder = 'Choose',
  maxVisible = 6,
  minMenuWidth,
  haptics = true,
  testID,
  style,
}: MorphSelectProps) {
  const { colors, font, radius, space, shadow } = useKinetikTheme();
  const reduced = useReduceMotion();
  const haptic = useHaptic(haptics);
  const window = useWindowDimensions();
  const trigger = useRef<React.ElementRef<typeof View>>(null);
  const [visible, setVisible] = useState(false);
  const [anchor, setAnchor] = useState<Rect | null>(null);
  const baseRowHeight = Math.max(ROW, Math.ceil(font.size.md * 1.35 + space[3] * 2));
  const optionCount = options.length;
  const [rowHeights, setRowHeights] = useState<number[]>(() => Array(options.length).fill(baseRowHeight));
  const optionsLayoutKey = JSON.stringify(options.map((option) => [option.value, option.label, option.description]));
  const t = useSharedValue(0);
  const index = options.findIndex((o) => o.value === value);
  const rowOffsets = useMemo(() => rowHeights.map((_, i) => rowHeights.slice(0, i).reduce((sum, height) => sum + height, 0)), [rowHeights]);
  const highlight = useSharedValue(rowOffsets[Math.max(0, index)] ?? 0);
  const selected = index >= 0 ? options[index] : undefined;

  const listH = rowHeights.reduce((sum, height) => sum + height, 0) + PAD * 2;
  const rect = anchor ?? { x: MARGIN, y: 120, width: window.width - MARGIN * 2, height: TRIGGER };
  const roomDown = Math.max(0, window.height - (rect.y + rect.height) - MARGIN);
  const roomUp = Math.max(0, rect.y - MARGIN);
  const visibleRowsHeight = rowHeights.slice(0, maxVisible).reduce((sum, height) => sum + height, 0) + PAD * 2;
  const preferredListHeight = Math.min(listH, visibleRowsHeight);
  const down = roomDown >= Math.min(preferredListHeight, baseRowHeight * 2) || roomDown >= roomUp;
  const available = down ? roomDown : roomUp;
  const menuListHeight = Math.max(0, Math.min(preferredListHeight, available - rect.height - StyleSheet.hairlineWidth));
  const fullH = rect.height + StyleSheet.hairlineWidth + menuListHeight;

  const open = () => {
    highlight.set(rowOffsets[Math.max(0, index)] ?? 0);
    setAnchor(null);
    setVisible(true);
    haptic('tick');
    trigger.current?.measureInWindow((x, y, width, height) => {
      const maxWidth = Math.max(0, window.width - MARGIN * 2);
      const menuWidth = Math.min(maxWidth, Math.max(width, minMenuWidth ?? width));
      const menuX = Math.max(MARGIN, Math.min(x + width / 2 - menuWidth / 2, window.width - MARGIN - menuWidth));
      setAnchor({ x: menuX, y, width: menuWidth, height });
    });
  };

  useEffect(() => {
    if (!visible || !anchor) return;
    t.set(0);
    t.set(reduced ? 1 : withSpring(1, spring('smooth')));
  }, [visible, anchor, reduced, t]);

  useEffect(() => {
    setRowHeights((previous) => {
      const next = Array.from({ length: optionCount }, () => baseRowHeight);
      return previous.length === next.length && previous.every((height, i) => height === next[i]) ? previous : next;
    });
  }, [optionsLayoutKey, optionCount, baseRowHeight]);

  useEffect(() => {
    highlight.set(rowOffsets[Math.max(0, index)] ?? 0);
  }, [highlight, index, rowOffsets]);

  const close = (chosen?: string) => {
    const done = () => {
      setVisible(false);
      if (chosen !== undefined) onValueChange(chosen);
    };
    if (reduced) {
      t.set(0);
      done();
      return;
    }
    // A chosen row holds its highlight for a beat before the list folds away.
    const wait = chosen === undefined ? 0 : 140;
    t.set(
      withDelay(
        wait,
        withTiming(0, { duration: durations.base, easing: easings.exit }, (finished) => {
          if (finished) scheduleOnRN(done);
        }),
      ),
    );
  };

  const choose = (i: number) => {
    const option = options[i];
    if (!option || option.disabled) return;
    haptic('tick');
    const top = rowOffsets[i] ?? 0;
    highlight.set(reduced ? top : withSpring(top, spring('snappy')));
    close(option.value);
  };

  const panel = useAnimatedStyle(() => {
    const h = interpolate(t.get(), [0, 1], [rect.height, fullH]);
    return {
      height: h,
      top: down ? rect.y : rect.y + rect.height - h,
      opacity: anchor ? 1 : 0,
    };
  });
  const scrim = useAnimatedStyle(() => ({ opacity: t.get() * 0.6 }));
  const chevron = useAnimatedStyle(() => ({
    transform: [{ rotate: `${t.get() * 180}deg` }],
  }));
  const pill = useAnimatedStyle(() => ({ transform: [{ translateY: highlight.get() }], height: rowHeights[index] ?? baseRowHeight }));

  const shown = selected?.compactLabel ?? selected?.label ?? placeholder;
  const accessibleShown = selected?.label ?? placeholder;
  const field = (
    <View style={[styles.fieldRow, { paddingHorizontal: space[4] }]}>
      <Text
        numberOfLines={1}
        style={[
          styles.grow,
          { color: selected ? colors.text : colors.textSubtle, fontSize: font.size.md },
        ]}
      >
        {shown}
      </Text>
      <Animated.View style={visible ? chevron : undefined}>
        <Chevron color={colors.textMuted} />
      </Animated.View>
    </View>
  );

  return (
    <View testID={testID} style={[{ gap: space[2] }, style]}>
      <Text style={{ color: colors.text, fontSize: font.size.sm, fontWeight: font.weight.medium }}>
        {label}
      </Text>
      <Pressable
        disabled={disabled}
        ref={trigger}
        onPress={open}
        accessibilityRole="button"
        accessibilityLabel={`${label}, ${accessibleShown}`}
        accessibilityState={{ expanded: visible }}
        style={({ pressed }) => [
          styles.trigger,
          {
            backgroundColor: pressed ? colors.surfaceSunken : colors.surface,
            borderColor: colors.borderStrong,
            borderRadius: radius.md,
            opacity: visible && anchor ? 0 : 1,
          },
        ]}
      >
        {field}
      </Pressable>

      <Modal
        visible={visible}
        transparent
        animationType="none"
        onRequestClose={() => close()}
        statusBarTranslucent
        navigationBarTranslucent
      >
        <View style={StyleSheet.absoluteFill} onAccessibilityEscape={() => close()}>
          <Animated.View
            style={[StyleSheet.absoluteFill, { backgroundColor: colors.scrim }, scrim]}
          >
            <Pressable
              style={StyleSheet.absoluteFill}
              onPress={() => close()}
              accessibilityRole="button"
              accessibilityLabel={closeLabel}
            />
          </Animated.View>
          <Animated.View
            accessibilityRole="list"
            style={[
              styles.panel,
              {
                left: rect.x,
                width: rect.width,
                backgroundColor: colors.surfaceRaised,
                borderColor: colors.borderStrong,
                borderRadius: radius.md,
                boxShadow: shadow.lg,
                flexDirection: down ? 'column' : 'column-reverse',
              },
              panel,
            ]}
          >
            <Pressable
              onPress={() => close()}
              accessibilityRole="button"
              accessibilityLabel={`${label}, ${accessibleShown}`}
              accessibilityState={{ expanded: true }}
              style={styles.header}
            >
              {field}
            </Pressable>
            <View style={[styles.divider, { backgroundColor: colors.border }]} />
            <ScrollView
              style={[styles.grow, { maxHeight: menuListHeight }]}
              contentContainerStyle={{ padding: PAD }}
              scrollEnabled={listH > menuListHeight}
              showsVerticalScrollIndicator={false}
            >
              <Animated.View
                testID="morph-select-highlight"
                pointerEvents="none"
                style={[
                  styles.pill,
                  { top: PAD, left: PAD, right: PAD, backgroundColor: colors.azureSoft },
                  { borderRadius: radius.sm, opacity: index >= 0 ? 1 : 0 },
                  pill,
                ]}
              />
              {options.map((option, i) => (
                <Row
                  key={option.value}
                  option={option}
                  index={i}
                  selected={i === index}
                  t={t}
                  fontSize={font.size.md}
                  minHeight={baseRowHeight}
                  onHeight={(height) => setRowHeights((previous) => {
                    if (Math.abs((previous[i] ?? 0) - height) < 1) return previous;
                    const next = [...previous];
                    next[i] = height;
                    return next;
                  })}
                  onPress={() => choose(i)}
                />
              ))}
            </ScrollView>
          </Animated.View>
        </View>
      </Modal>
    </View>
  );
}

type RowProps = {
  option: MorphSelectOption;
  index: number;
  selected: boolean;
  t: SharedValue<number>;
  fontSize: number;
  minHeight: number;
  onHeight: (height: number) => void;
  onPress: () => void;
};

function Row({ option, index, selected, t, fontSize, minHeight, onHeight, onPress }: RowProps) {
  const { colors, font, space } = useKinetikTheme();
  // Rows arrive one after another as the panel opens.
  const motion = useAnimatedStyle(() => {
    const start = Math.min(0.5, 0.15 + index * 0.06);
    const p = interpolate(t.get(), [start, start + 0.45], [0, 1], 'clamp');
    return { opacity: p, transform: [{ translateY: (1 - p) * -8 }] };
  });
  return (
    <Animated.View style={motion}>
      <Pressable
        onPress={onPress}
        onLayout={(event) => onHeight(event.nativeEvent.layout.height)}
        disabled={option.disabled}
        accessibilityRole="menuitem"
        accessibilityLabel={[option.label, option.description].filter(Boolean).join(', ')}
        accessibilityState={{ selected, disabled: option.disabled }}
        style={[styles.row, { minHeight, paddingHorizontal: space[3], paddingVertical: space[2], opacity: option.disabled ? 0.4 : 1 }]}
      >
        <View style={styles.grow}>
          <Text
            style={{
              color: selected ? colors.azure : colors.text,
              fontSize,
              lineHeight: Math.ceil(fontSize * 1.3),
              fontWeight: selected ? font.weight.semibold : font.weight.regular,
            }}
          >
            {option.label}
          </Text>
          {option.description ? (
            <Text style={{ color: colors.textSubtle, fontSize: font.size.xs, lineHeight: Math.ceil(font.size.xs * 1.3) }}>
              {option.description}
            </Text>
          ) : null}
        </View>
        {selected ? <Tick color={colors.azure} /> : null}
      </Pressable>
    </Animated.View>
  );
}

function Chevron({ color }: { color: string }) {
  return (
    <View style={styles.icon}>
      <View style={[styles.stroke, { backgroundColor: color }, styles.chevronL]} />
      <View style={[styles.stroke, { backgroundColor: color }, styles.chevronR]} />
    </View>
  );
}

function Tick({ color }: { color: string }) {
  return (
    <View style={styles.icon}>
      <View style={[styles.stroke, { backgroundColor: color }, styles.tickShort]} />
      <View style={[styles.stroke, { backgroundColor: color }, styles.tickLong]} />
    </View>
  );
}

const styles = StyleSheet.create({
  trigger: { minHeight: TRIGGER, borderWidth: 1, justifyContent: 'center' },
  header: { height: TRIGGER - 2, justifyContent: 'center' },
  fieldRow: { flexDirection: 'row', alignItems: 'center', gap: 8, height: TRIGGER - 2 },
  grow: { flex: 1 },
  panel: { position: 'absolute', borderWidth: 1, overflow: 'hidden' },
  divider: { height: StyleSheet.hairlineWidth },
  pill: { position: 'absolute' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  icon: { width: 16, height: 16, alignItems: 'center', justifyContent: 'center' },
  stroke: { position: 'absolute', height: 1.8, borderRadius: 1 },
  chevronL: { width: 7, left: 2.6, top: 8, transform: [{ rotate: '40deg' }] },
  chevronR: { width: 7, right: 2.6, top: 8, transform: [{ rotate: '-40deg' }] },
  tickShort: { width: 5.5, left: 2.2, top: 9, transform: [{ rotate: '45deg' }] },
  tickLong: { width: 10, left: 4.6, top: 7.4, transform: [{ rotate: '-50deg' }] },
});
