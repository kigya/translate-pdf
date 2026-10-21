import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AccessibilityInfo, Platform, Text } from 'react-native';
import { Skia } from '@shopify/react-native-skia';
import { useHaptic } from '../src/lib/kinetik/hooks/use-haptic';
import { useReduceMotion } from '../src/lib/kinetik/hooks/use-reduce-motion';
import { KinetikProvider, useKinetikConfig } from '../src/lib/kinetik/tokens/theme';
import { colorUniform, runtimeEffect } from '../src/lib/kinetik/shaders/sksl';
import { glow, withAlpha } from '../src/lib/kinetik/tokens/tokens';
import { gradient } from '../src/lib/kinetik/tokens/gradient';

function ConfigProbe() {
  const { theme } = useKinetikConfig();
  return <Text>{theme.scheme}</Text>;
}

function MotionProbe() {
  return <Text>{useReduceMotion() ? 'reduced' : 'motion'}</Text>;
}

function DefaultHapticProbe() {
  const haptic = useHaptic();
  return <Text accessibilityRole="button" onPress={() => haptic()}>Tap</Text>;
}

afterEach(() => jest.restoreAllMocks());

it('uses system theme defaults outside and inside the provider, including palette overrides', () => {
  const useColorScheme = jest.spyOn(require('react-native'), 'useColorScheme').mockReturnValue('light');
  const fallback = render(<ConfigProbe />);
  expect(screen.getByText('light')).toBeOnTheScreen();
  fallback.unmount();

  const provided = render(<KinetikProvider colors={{ ember: '#123456' }}><ConfigProbe /></KinetikProvider>);
  expect(screen.getByText('light')).toBeOnTheScreen();
  expect(useColorScheme).toHaveBeenCalled();
  provided.unmount();
  useColorScheme.mockReturnValue('dark');
  const darkFallback = render(<ConfigProbe />);
  expect(screen.getByText('dark')).toBeOnTheScreen();
  darkFallback.unmount();
  const darkProvider = render(<KinetikProvider><ConfigProbe /></KinetikProvider>);
  expect(screen.getByText('dark')).toBeOnTheScreen();
  darkProvider.unmount();
});

it('subscribes to the system reduced-motion preference, follows changes and removes the listener', async () => {
  let notify: ((value: boolean) => void) | undefined;
  const remove = jest.fn();
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation((_event, listener) => {
    notify = listener as (value: boolean) => void;
    return { remove };
  });

  const view = render(<KinetikProvider scheme="light"><MotionProbe /></KinetikProvider>);
  await act(async () => Promise.resolve());
  expect(screen.getByText('motion')).toBeOnTheScreen();
  act(() => notify?.(true));
  expect(screen.getByText('reduced')).toBeOnTheScreen();
  view.unmount();
  expect(remove).toHaveBeenCalledTimes(1);
});

it('ignores an unresolved system motion read after unmount and tolerates rejected reads', async () => {
  let resolveRead: ((value: boolean) => void) | undefined;
  const remove = jest.fn();
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockImplementation(() => new Promise((resolve) => { resolveRead = resolve; }));
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockReturnValue({ remove });
  const view = render(<KinetikProvider scheme="light"><MotionProbe /></KinetikProvider>);
  view.unmount();
  await act(async () => resolveRead?.(true));
  expect(remove).toHaveBeenCalledTimes(1);

  jest.restoreAllMocks();
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockRejectedValue(new Error('system setting unavailable'));
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockReturnValue({ remove: jest.fn() });
  render(<KinetikProvider scheme="light"><MotionProbe /></KinetikProvider>);
  await act(async () => Promise.resolve());
  expect(screen.getByText('motion')).toBeOnTheScreen();
});

it('uses the default tap haptic and safely absorbs a platform rejection', async () => {
  const haptics = require('expo-haptics') as typeof import('expo-haptics');
  const impact = jest.spyOn(haptics, 'impactAsync').mockRejectedValue(new Error('no actuator'));
  render(<KinetikProvider scheme="light" haptics><DefaultHapticProbe /></KinetikProvider>);
  fireEvent.press(screen.getByRole('button', { name: 'Tap' }));
  expect(impact).toHaveBeenCalledWith(haptics.ImpactFeedbackStyle.Light);
  await act(async () => Promise.resolve());
});

it('caches compiled shader effects and reports compile failure without caching it', () => {
  const make = jest.spyOn(Skia.RuntimeEffect, 'Make');
  const effect = { makeShader: jest.fn(() => ({})) };
  make.mockReturnValue(effect as never);
  expect(runtimeEffect('unique shader source')).toBe(effect);
  expect(runtimeEffect('unique shader source')).toBe(effect);
  expect(make).toHaveBeenCalledTimes(1);

  make.mockReturnValue(null as never);
  expect(() => runtimeEffect('invalid shader source')).toThrow('Kinetik: shader failed to compile');
  expect(() => runtimeEffect('invalid shader source')).toThrow('Kinetik: shader failed to compile');
  expect(make).toHaveBeenCalledTimes(3);
});

it('normalizes partial shader color channels and handles common and malformed alpha inputs', () => {
  const color = jest.spyOn(Skia, 'Color').mockReturnValue([undefined, 0.25, null, undefined] as never);
  expect(colorUniform('#123456')).toEqual([0, 0.25, 0, 1]);
  expect(color).toHaveBeenCalledWith('#123456');
  color.mockReturnValue([0.1, 0.2, 0.3, 0.4] as never);
  expect(colorUniform('#654321')).toEqual([0.1, 0.2, 0.3, 0.4]);
  color.mockReturnValue([0.1, undefined, 0.3, 0.4] as never);
  expect(colorUniform('#partial')).toEqual([0.1, 0, 0.3, 0.4]);
  color.mockRestore();
  expect(withAlpha('#AABBCC', 0.4)).toBe('rgba(170, 187, 204, 0.4)');
  expect(withAlpha('#ffffff', 2)).toBe('rgba(255, 255, 255, 1)');
  expect(withAlpha('#000000', -2)).toBe('rgba(0, 0, 0, 0)');
  expect(withAlpha('rgb(1, 2, 3)', 0.2)).toBe('rgb(1, 2, 3)');
  expect(glow('#abcdef')).toContain('0px 6px 20px');
  expect(glow('#abcdef', 'strong')).toContain('0px 10px 32px');
});

it('selects the web gradient style only on web and restores the active platform after the assertion', () => {
  const original = Platform.OS;
  try {
    Object.defineProperty(Platform, 'OS', { configurable: true, value: 'web' });
    expect(gradient('linear-gradient(red, blue)')).toEqual({ backgroundImage: 'linear-gradient(red, blue)' });
    Object.defineProperty(Platform, 'OS', { configurable: true, value: original });
    expect(gradient('linear-gradient(black, white)')).toEqual({ experimental_backgroundImage: 'linear-gradient(black, white)' });
  } finally {
    Object.defineProperty(Platform, 'OS', { configurable: true, value: original });
  }
});
