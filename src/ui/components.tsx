import React, { ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, TextInputProps, View, ViewStyle } from 'react-native';
import { Palette } from './theme';

export type LayoutDirection = 'ltr' | 'rtl';

export function Screen({ children, colors, maxWidth = 940, direction = 'ltr' }: { children: ReactNode; colors: Palette; maxWidth?: number; direction?: LayoutDirection }) {
  return <View style={{ flex: 1, backgroundColor: colors.background, direction }}><View style={{ width: '100%', maxWidth, alignSelf: 'center', flex: 1, direction }}>{children}</View></View>;
}

export function Header({ title, subtitle, colors, onBack, right, backLabel = 'Back', direction = 'ltr' }: { title: string; subtitle?: string; colors: Palette; onBack?: () => void; right?: ReactNode; backLabel?: string; direction?: LayoutDirection }) {
  const textDirection = { writingDirection: direction, textAlign: direction === 'rtl' ? 'right' as const : 'left' as const };
  return <View style={[styles.header, { borderBottomColor: colors.border, direction }]}>
    {onBack ? <Pressable accessibilityRole="button" accessibilityLabel={backLabel} onPress={onBack} hitSlop={10} style={styles.back}><Text style={{ color: colors.accent, fontSize: 22 }}>{direction === 'rtl' ? '›' : '‹'}</Text></Pressable> : null}
    <View style={{ flex: 1 }}><Text accessibilityRole="header" style={[styles.headerTitle, { color: colors.text }, textDirection]}>{title}</Text>{subtitle ? <Text style={[{ color: colors.muted, marginTop: 3, fontSize: 13 }, textDirection]}>{subtitle}</Text> : null}</View>{right}
  </View>;
}

export function Card({ children, colors, style }: { children: ReactNode; colors: Palette; style?: ViewStyle }) {
  return <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }, style]}>{children}</View>;
}

export function Label({ children, colors, tone = 'muted' }: { children: ReactNode; colors: Palette; tone?: 'muted' | 'accent' | 'success' }) {
  const bg = tone === 'accent' ? colors.softAccent : colors.raised;
  const fg = tone === 'accent' ? colors.accent : tone === 'success' ? colors.success : colors.muted;
  return <View style={{ alignSelf: 'flex-start', backgroundColor: bg, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 30 }}><Text style={{ color: fg, fontWeight: '700', fontSize: 11, letterSpacing: 0.2 }}>{children}</Text></View>;
}

export function buttonOpacity(disabled: boolean | undefined, pressed: boolean): number {
  if (disabled) return 0.45;
  return pressed ? 0.78 : 1;
}

export function Button({ label, onPress, colors, variant = 'primary', disabled, loading, accessibilityLabel }: { label: string; onPress: () => void; colors: Palette; variant?: 'primary' | 'secondary' | 'quiet' | 'danger'; disabled?: boolean; loading?: boolean; accessibilityLabel?: string }) {
  const primary = variant === 'primary';
  const quiet = variant === 'quiet';
  const bg = primary ? colors.accent : quiet ? 'transparent' : colors.raised;
  const fg = primary ? colors.onAccent : variant === 'danger' ? colors.accent : colors.text;
  return <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel ?? label} accessibilityState={{ disabled: Boolean(disabled || loading), busy: Boolean(loading) }} disabled={disabled || loading} onPress={onPress} style={({ pressed }) => [styles.button, { backgroundColor: bg, borderColor: colors.border, borderWidth: primary || quiet ? 0 : 1, opacity: buttonOpacity(disabled, pressed) }, variant === 'danger' && { backgroundColor: colors.softAccent }]}>
    {loading ? <ActivityIndicator size="small" color={fg} /> : <Text style={{ color: fg, fontWeight: '700', fontSize: 15, textAlign: 'center' }}>{label}</Text>}
  </Pressable>;
}

export function Field({ label, colors, value, onChangeText, placeholder, ...props }: TextInputProps & { label: string; colors: Palette }) {
  return <View style={{ gap: 7 }}><Text style={{ color: colors.muted, fontSize: 12, fontWeight: '700' }}>{label}</Text><TextInput value={value} onChangeText={onChangeText} placeholder={placeholder} placeholderTextColor={colors.muted} selectionColor={colors.accent} style={[styles.field, { backgroundColor: colors.background, color: colors.text, borderColor: colors.border }]} {...props} /></View>;
}

export function Notice({ text, colors, retry, retryLabel = 'Try again', tone = 'error' }: { text?: string; colors: Palette; retry?: () => void; retryLabel?: string; tone?: 'error' | 'info' }) {
  if (!text) return null;
  return <View testID="notice" accessibilityRole={tone === 'error' ? 'alert' : 'text'} style={{ padding: 13, borderRadius: 14, backgroundColor: tone === 'error' ? colors.softAccent : colors.raised, borderWidth: 1, borderColor: colors.border, gap: retry ? 8 : 0 }}><Text style={{ color: tone === 'error' ? colors.accent : colors.text, fontSize: 13, lineHeight: 19 }}>{text}</Text>{retry ? <Button label={retryLabel} onPress={retry} colors={colors} variant="quiet" /> : null}</View>;
}

export function ProgressBar({ value, colors, label }: { value: number; colors: Palette; label?: string }) {
  const ratio = Math.max(0, Math.min(1, value));
  return <View accessible accessibilityRole="progressbar" accessibilityLabel={label} accessibilityValue={{ min: 0, max: 100, now: Math.round(ratio * 100) }} style={{ height: 8, borderRadius: 8, overflow: 'hidden', backgroundColor: colors.raised }}><View style={{ height: '100%', width: `${Math.round(ratio * 100)}%`, backgroundColor: colors.accent, borderRadius: 8 }} /></View>;
}

export function Row({ children, style }: { children: ReactNode; style?: ViewStyle }) { return <View style={[{ flexDirection: 'row', alignItems: 'center', gap: 10 }, style]}>{children}</View>; }
export function SectionTitle({ children, colors }: { children: ReactNode; colors: Palette }) { return <Text style={{ color: colors.muted, fontWeight: '700', fontSize: 12, letterSpacing: 0.7, textTransform: 'uppercase', marginTop: 12, marginBottom: 3 }}>{children}</Text>; }

const styles = StyleSheet.create({
  header: { minHeight: 76, paddingHorizontal: 22, paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, flexDirection: 'row', alignItems: 'center', gap: 12 },
  headerTitle: { fontSize: 20, fontWeight: '700', letterSpacing: -0.35 }, back: { width: 36, height: 42, justifyContent: 'center' },
  card: { borderWidth: 1, borderRadius: 22, padding: 18, gap: 12, shadowColor: '#000', shadowOpacity: 0.035, shadowRadius: 14, shadowOffset: { width: 0, height: 5 }, elevation: 1 },
  button: { minHeight: 48, paddingHorizontal: 16, paddingVertical: 12, justifyContent: 'center', borderRadius: 15 },
  field: { borderWidth: 1, borderRadius: 13, paddingHorizontal: 14, paddingVertical: 12, minHeight: 48, fontSize: 16 },
});
