import React, { useEffect } from 'react';
import Animated, { useSharedValue, useAnimatedStyle, useReducedMotion, withTiming } from 'react-native-reanimated';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  Text,
  View,
} from 'react-native';
import Svg, { Path } from 'react-native-svg';
import type { Palette } from './theme';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
export type IconName =
  | 'arrow'
  | 'swap'
  | 'chevron'
  | 'document'
  | 'download'
  | 'check'
  | 'info'
  | 'trash'
  | 'globe'
  | 'moon'
  | 'sun'
  | 'monitor'
  | 'notification'
  | 'book'
  | 'storage'
  | 'shield'
  | 'license'
  | 'replay'
  | 'close'
  | 'plus';
const paths: Record<IconName, string> = {
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  swap: 'M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4',
  chevron: 'm9 5 7 7-7 7',
  document:
    'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zm0 0v6h6M8 13h8M8 17h5',
  download: 'M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4',
  check: 'm5 12 4 4L19 6',
  info: 'M12 11v6M12 7v.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
  globe:
    'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M2 12h20M12 2c-6 5-6 15 0 20 6-5 6-15 0-20',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5',
  monitor: 'M3 4h18v13H3zM8 21h8M12 17v4',
  notification: 'M18 8a6 6 0 0 0-12 0v7l-2 3h16l-2-3zm-8 13h4',
  moon: 'M21 13A9 9 0 0 1 11 3a9 9 0 1 0 10 10',
  book: 'M12 5C8 2 3 3 3 3v16s5-1 9 2c4-3 9-2 9-2V3s-5-1-9 2zm0 0v16',
  storage: 'M4 4h16v6H4zM4 14h16v6H4zM16 7h1M16 17h1',
  shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6zm-5 10 3 3 7-7',
  license: 'M7 3h10v12H7zM9 15l-1 7 4-3 4 3-1-7M10 7h4M10 10h4',
  replay: 'M3 10a9 9 0 1 1 1 7M3 4v6h6',
  close: 'm6 6 12 12M18 6 6 18',
  plus: 'M12 5v14M5 12h14',
};
export function Icon({
  name,
  size = 24,
  color,
  rotation = 0,
}: {
  name: IconName;
  size?: number;
  color: string;
  rotation?: number;
}) {
  return (
    <Svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      style={{ transform: [{ rotate: `${rotation}deg` }] }}
      accessible={false}
    >
      <Path
        d={paths[name]}
        fill="none"
        stroke={color}
        strokeWidth={1.8}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}
export function IconButton({
  name,
  label,
  onPress,
  colors,
  disabled = false,
}: {
  name: IconName;
  label: string;
  onPress: () => void;
  colors: Palette;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => ({
        minWidth: 48,
        minHeight: 48,
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: 24,
        opacity: disabled ? 0.4 : pressed ? 0.6 : 1,
      })}
    >
      <Icon name={name} color={colors.accent} />
    </Pressable>
  );
}
export function SettingsRow({
  icon,
  label,
  value,
  onPress,
  colors,
  danger = false,
}: {
  icon: IconName;
  label: string;
  value?: string;
  onPress: () => void;
  colors: Palette;
  danger?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => ({
        minHeight: 64,
        flexDirection: 'row',
        alignItems: 'center',
        gap: 16,
        borderBottomColor: colors.border,
        borderBottomWidth: 0.5,
        opacity: pressed ? 0.65 : 1,
      })}
    >
      <Icon name={icon} color={danger ? colors.accent : colors.muted} />
      <View style={{ flex: 1, gap: 3 }}>
        <Text
          style={{
            color: danger ? colors.accent : colors.text,
            fontSize: 16,
            fontWeight: '600',
          }}
        >
          {label}
        </Text>
        {value ? (
          <Text numberOfLines={1} style={{ color: colors.muted, fontSize: 13 }}>
            {value}
          </Text>
        ) : null}
      </View>
      <Icon name="chevron" size={18} color={colors.muted} />
    </Pressable>
  );
}
export function Sheet({
  visible,
  title,
  onClose,
  colors,
  children,
  closeLabel = 'Close',
}: {
  visible: boolean;
  title: string;
  onClose: () => void;
  colors: Palette;
  children: React.ReactNode;
  closeLabel?: string;
}) {
  const insets = useSafeAreaInsets();
  const reduced = useReducedMotion() ?? false;
  const reveal = useSharedValue(0);
  useEffect(() => { reveal.value = visible ? (reduced ? 1 : withTiming(1, { duration: 260 })) : 0; }, [reduced, reveal, visible]);
  const sheetStyle = useAnimatedStyle(() => ({ opacity: reveal.value, transform: [{ translateY: (1 - reveal.value) * 34 }] }));
  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      onRequestClose={onClose}
    >
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={{
          flex: 1,
          justifyContent: 'flex-end',
          backgroundColor: '#00000066',
        }}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={closeLabel}
          onPress={onClose}
          style={{ flex: 1 }}
        />
        <Animated.View
          accessibilityViewIsModal
          style={[sheetStyle, {
            maxHeight: '80%',
            backgroundColor: colors.surface,
            borderTopLeftRadius: 28,
            borderTopRightRadius: 28,
            paddingHorizontal: 24,
            paddingBottom: Math.max(20, insets.bottom),
          }]}
        >
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              paddingTop: 10,
              paddingBottom: 8,
            }}
          >
            <Text
              accessibilityRole="header"
              style={{
                color: colors.text,
                fontWeight: '800',
                fontSize: 21,
                flex: 1,
              }}
            >
              {title}
            </Text>
            <IconButton
              name="close"
              label={closeLabel}
              onPress={onClose}
              colors={colors}
            />
          </View>
          <ScrollView
            keyboardShouldPersistTaps="handled"
            contentContainerStyle={{ paddingBottom: 12 }}
          >
            {children}
          </ScrollView>
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
