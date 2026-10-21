import { ColorSchemeName } from 'react-native';
import { createTheme } from '../lib/kinetik/tokens/tokens';

export type ThemeMode = 'light' | 'dark';

export const kinetikBrand = {
  light: { ember: '#C62828', onEmber: '#FFFFFF', emberSoft: '#FBE9E7', azure: '#C62828', success: '#237A57', warning: '#A05700' },
  dark: { ember: '#C62828', onEmber: '#FFFFFF', danger: '#C62828', emberSoft: '#482626', azure: '#FF6B6B' },
};
function palette(mode: ThemeMode) {
  const { colors } = createTheme(mode, kinetikBrand[mode]);
  return { background: colors.background, surface: colors.surface, raised: colors.surfaceRaised,
    text: colors.text, muted: colors.textMuted, border: colors.border,
    accent: colors.azure, onAccent: mode === 'dark' ? '#271313' : colors.onEmber, primary: colors.ember, onPrimary: colors.onEmber, softAccent: colors.emberSoft,
    success: colors.success, warning: colors.warning, shadow: 'transparent' };
}
export const palettes = { light: palette('light'), dark: palette('dark') };

export function resolveTheme(preference: 'system' | ThemeMode, system: ColorSchemeName): ThemeMode {
  return preference === 'system' ? (system === 'dark' ? 'dark' : 'light') : preference;
}

export type Palette = (typeof palettes)[ThemeMode];
