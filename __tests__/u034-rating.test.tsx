import React from 'react';
import { render, screen } from '@testing-library/react-native-legacy';

jest.mock('react-native-reanimated', () => ({
  __esModule: true,
  default: { View: require('react-native').View, Text: require('react-native').Text, ScrollView: require('react-native').ScrollView, Image: require('react-native').Image, createAnimatedComponent: (component: unknown) => component },
  FadeIn: { duration: () => undefined }, FadeInDown: { delay: () => ({ duration: () => ({ easing: () => undefined }) }) },
  useReducedMotion: () => true,
  useSharedValue: (value: unknown) => ({ value, get() { return this.value; }, set(next: unknown) { this.value = next; } }),
  useDerivedValue: (factory: () => unknown) => ({ get: factory }),
  useFrameCallback: () => ({ setActive: jest.fn() }), useAnimatedRef: () => require('react').useRef(null),
  useAnimatedStyle: (factory: () => object) => factory(), useAnimatedProps: (factory: () => object) => factory(),
  withTiming: (value: unknown) => value, withRepeat: (value: unknown) => value, withSpring: (value: unknown) => value,
  withDelay: (_delay: number, value: unknown) => value, withSequence: (...values: unknown[]) => values.at(-1),
  cancelAnimation: jest.fn(), interpolate: (_value: number, _input: number[], output: number[]) => output[0],
  interpolateColor: (_value: number, _input: number[], output: string[]) => output[0],
  Easing: { linear: (value: number) => value, bezier: () => (value: number) => value, sin: (value: number) => value, inOut: (value: unknown) => value },
}));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }) }));
jest.mock('../src/native/PdfTranslator', () => ({ PdfTranslator: {} }));
jest.mock('../src/components/kinetik/scramble-text', () => ({ ScrambleText: () => null }));
jest.mock('../src/components/kinetik/ring-spinner', () => ({ RingSpinner: () => null }));
jest.mock('../src/components/kinetik/read-more', () => ({ ReadMore: () => null }));
jest.mock('../src/components/kinetik/usage-meter', () => ({ UsageMeter: () => null }));
jest.mock('../src/components/kinetik/star-rating', () => ({
  StarRating: ({ value, onValueChange, accessibilityLabel }: { value: number; onValueChange?: (value: number) => void; accessibilityLabel: string }) => {
    const ReactRuntime = require('react');
    const RN = require('react-native');
    return ReactRuntime.createElement(RN.View, { accessibilityRole: 'adjustable', accessibilityLabel, accessibilityValue: { min: 0, max: 5, now: value } },
      ReactRuntime.createElement(RN.Text, { testID: 'stored-rating' }, String(value)),
      ...[1, 2, 3, 4, 5].map(rating => ReactRuntime.createElement(RN.Pressable, { key: rating, accessibilityRole: 'button', accessibilityLabel: `Choose rating ${rating}`, onPress: () => onValueChange?.(rating) }, ReactRuntime.createElement(RN.Text, null, rating))),
    );
  },
}));
jest.mock('../src/components/kinetik/morph-select', () => ({ MorphSelect: () => null }));
jest.mock('../src/components/kinetik/pulse-toggle', () => ({ PulseToggle: () => null }));
jest.mock('../src/components/kinetik/screens/profile', () => ({ Profile: ({ sections }: { sections: { rows: { key: string; label: string; detail?: string }[] }[] }) => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return ReactRuntime.createElement(RN.View, null, ...sections.flatMap(section => section.rows).map(row => ReactRuntime.createElement(RN.Text, { key: row.key }, row.detail ? `${row.label}: ${row.detail}` : row.label)));
} }));
jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => true }));
jest.mock('../src/ui/Motion', () => ({ AnimatedNumber: () => null, Balloons: () => null }));
jest.mock('../src/ui/Controls', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return {
    Icon: () => null,
    IconButton: () => null,
    SettingsRow: ({ label }: { label: string }) => ReactRuntime.createElement(RN.Text, null, label),
    Sheet: () => null,
  };
});
jest.mock('../src/ui/components', () => ({ Button: () => null, Field: () => null, Notice: () => null, ProgressBar: () => null, Row: ({ children }: { children: React.ReactNode }) => require('react').createElement(require('react-native').View, null, children) }));

import { SettingsScreen } from '../src/screens/AppScreens';
import type { BootstrapSnapshot } from '../src/native/PdfTranslator';
import { createTranslator } from '../src/i18n/messages';
import { palettes } from '../src/ui/theme';

const snapshot = (appRating: number) => ({
  schemaVersion: 6,
  preferences: { language: 'system', theme: 'system', appRating, librarySort: 'recent' },
  device: {}, models: [], jobs: [], documents: [], glossary: [], onboarding: { done: true },
} as unknown as BootstrapSnapshot);

describe('rating preference is not exposed in Settings', () => {
  it.each([0, 4])('does not render or mutate a saved app rating of %s', storedRating => {
    const onTheme = jest.fn();
    render(<SettingsScreen colors={palettes.light} t={createTranslator('en')} locale="en" snapshot={snapshot(storedRating)} onRoute={jest.fn()} onTheme={onTheme} onPrivacy={jest.fn()} onReplay={jest.fn()} />);

    expect(screen.queryByText('Rate Folivela')).toBeNull();
    expect(screen.queryByText(/Saved on this device/)).toBeNull();
    expect(screen.queryByRole('adjustable')).toBeNull();
    expect(screen.queryByRole('button', { name: /rating/i })).toBeNull();
    expect(onTheme).not.toHaveBeenCalled();
  });
});
