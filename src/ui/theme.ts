import { ColorSchemeName } from 'react-native';

export type ThemeMode = 'light' | 'dark';

export const palettes = {
  light: {
    background: '#F7F6F3', surface: '#FFFFFF', raised: '#F0EFEB', text: '#20201F', muted: '#6B6A66',
    border: '#E3E1DC', accent: '#C62828', onAccent: '#FFFFFF', softAccent: '#FBE9E7', success: '#237A57',
    warning: '#A05700', shadow: '#1B191512',
  },
  dark: {
    background: '#171717', surface: '#222222', raised: '#2C2B2A', text: '#F5F3EF', muted: '#B3B0AA',
    border: '#3C3A38', accent: '#FF6B6B', onAccent: '#271313', softAccent: '#482626', success: '#72D2A8',
    warning: '#FFBF69', shadow: '#00000000',
  },
} as const;

export function resolveTheme(preference: 'system' | ThemeMode, system: ColorSchemeName): ThemeMode {
  return preference === 'system' ? (system === 'dark' ? 'dark' : 'light') : preference;
}

export type Palette = (typeof palettes)[ThemeMode];
