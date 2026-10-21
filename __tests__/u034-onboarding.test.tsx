import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AppState, StyleSheet } from 'react-native';

import { GrainDrift } from '../src/components/kinetik/grain-drift';
import { MagnetButton } from '../src/components/kinetik/magnet-button';
import { PressJelly } from '../src/components/kinetik/press-jelly';
import { WordCascade } from '../src/components/kinetik/word-cascade';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { createTheme } from '../src/lib/kinetik/tokens/tokens';
import { createTranslator } from '../src/i18n/messages';
import { OnboardingScreen } from '../src/onboarding/OnboardingScreen';
import { onboardingScenePalette } from '../src/onboarding/OnboardingScenes';
import { SplashScreen } from '../src/ui/SplashScreen';
import { palettes } from '../src/ui/theme';

afterEach(() => jest.restoreAllMocks());

jest.mock('react-native-reanimated', () => {
  const ReactModule = require('react');
  const ReactNative = require('react-native');
  const identity = (value: unknown) => value;
  return {
    __esModule: true,
    default: { createAnimatedComponent: (component: unknown) => component, View: ReactNative.View, Text: ReactNative.Text, ScrollView: ReactNative.ScrollView, Image: ReactNative.Image },
    useSharedValue: (value: unknown) => {
      const ref = ReactModule.useRef(undefined);
      if (ref.current === undefined) ref.current = { value, get() { return this.value; }, set(next: unknown) { this.value = next; } };
      return ref.current;
    },
    useAnimatedRef: () => ReactModule.useRef(null),
    useAnimatedScrollHandler: (callback: (event: { contentOffset: { x: number } }) => void) =>
      (event: { nativeEvent?: { contentOffset: { x: number } }; contentOffset?: { x: number } }) => callback(event.nativeEvent ?? event as { contentOffset: { x: number } }),
    useAnimatedStyle: (factory: () => object) => factory(),
    useDerivedValue: (factory: () => unknown) => ({ get: factory }),
    useFrameCallback: () => ({ setActive: jest.fn() }),
    useReducedMotion: () => false,
    cancelAnimation: jest.fn(),
    withSpring: identity,
    withTiming: identity,
    withRepeat: identity,
    withSequence: (...values: unknown[]) => values.at(-1),
    withDelay: (_delay: number, value: unknown) => value,
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    Easing: { bezier: () => identity, linear: identity, sin: identity, inOut: (easing: unknown) => easing },
  };
});

jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }) }));
jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => true }));

jest.mock('../src/components/kinetik/grain-drift', () => {
  const ReactModule = require('react');
  const ReactNative = require('react-native');
  const MockGrainDrift = (props: Record<string, unknown>) =>
    ReactModule.createElement(ReactNative.View, {
      testID: 'grain-drift-probe',
      accessibilityLabel: `grain=${props.grain};speed=${props.speed};active=${props.active};reduced=${props.reduceEffects}`,
      style: props.style,
    });
  return { GrainDrift: MockGrainDrift };
});

jest.mock('../src/ui/Motion', () => {
  const ReactModule = require('react');
  const ReactNative = require('react-native');
  const MockAppIcon = (props: { color: string; background: string }) =>
    ReactModule.createElement(ReactNative.View, {
      testID: 'splash-mark-probe',
      accessibilityLabel: 'Folivela mark',
      accessibilityHint: `foreground=${props.color};background=${props.background}`,
    });
  return { AppIcon: MockAppIcon };
});

const t = createTranslator('en');

function contrastRatio(first: string, second: string): number {
  const luminance = (hex: string) => {
    const channels = hex.replace('#', '').match(/.{2}/g)?.map((part) => parseInt(part, 16) / 255) ?? [];
    const [red, green, blue] = channels.map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
  };
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (values[0] + 0.05) / (values[1] + 0.05);
}

function layoutOnboarding(view: ReturnType<typeof render>) {
  fireEvent(screen.getByTestId('onboarding'), 'layout', {
    nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 780 } },
  });
  const pager = view.UNSAFE_getByProps({ pagingEnabled: true });
  fireEvent(pager, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 640 } } });
  for (const panel of screen.getAllByTestId('onboarding-illustration-panel', { includeHiddenElements: true })) {
    fireEvent(panel, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 312, height: 270 } } });
  }
  return pager;
}

function renderWelcome(overrides: Partial<React.ComponentProps<typeof OnboardingScreen>> = {}) {
  const persist = jest.fn().mockResolvedValue(undefined);
  const onComplete = jest.fn();
  const view = render(
    <KinetikProvider scheme="light" reduceMotion="always" haptics={false}>
      <OnboardingScreen
        colors={palettes.light}
        t={t}
        initialState={{ version: 1, page: 0, done: false }}
        persist={persist}
        onComplete={onComplete}
        ios={false}
        {...overrides}
      />
    </KinetikProvider>,
  );
  const pager = layoutOnboarding(view);
  return { view, pager, persist, onComplete };
}

describe('U034 onboarding composition', () => {
  it('shows exactly three animated title scenes without subtitle copy', () => {
    const { view } = renderWelcome();
    const titles = [t('intro1Title'), t('intro2Title'), t('intro3Title')];

    expect(view.UNSAFE_getAllByType(WordCascade)).toHaveLength(3);
    const visibleTitles = view.UNSAFE_getAllByProps({ accessibilityRole: 'text' }).map((node) => node.props.accessibilityLabel);
    expect(visibleTitles).toEqual(expect.arrayContaining(titles));
    for (const bodyKey of ['intro1Body', 'intro2Body', 'intro3Body'] as const) {
      expect(screen.queryByText(t(bodyKey), { includeHiddenElements: true })).toBeNull();
    }
  });

  it('keeps one centered progress frame outside the pager while only its active segment moves', () => {
    const { pager } = renderWelcome();
    const progress = screen.getByRole('progressbar', { name: t('progress') });
    const beforeFrame = StyleSheet.flatten(progress.props.style);
    const beforeSegment = StyleSheet.flatten(screen.getByTestId('onboarding-progress-indicator').props.style);

    expect(screen.getAllByRole('progressbar', { name: t('progress') })).toHaveLength(1);
    expect(beforeFrame.position).toBe('absolute');
    expect(beforeFrame.alignSelf).toBe('center');
    expect(beforeFrame.left).toBe(24);
    expect(progress.parent?.props.pagingEnabled).toBeUndefined();
    expect(pager.findAllByProps({ testID: 'onboarding-progress' })).toHaveLength(0);

    fireEvent(pager, 'scroll', { nativeEvent: { contentOffset: { x: 360, y: 0 } } });
    fireEvent(pager, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: 360, y: 0 } } });

    const afterProgress = screen.getByRole('progressbar', { name: t('progress') });
    const afterFrame = StyleSheet.flatten(afterProgress.props.style);
    const afterSegment = StyleSheet.flatten(screen.getByTestId('onboarding-progress-indicator').props.style);
    expect(afterFrame).toMatchObject({ position: beforeFrame.position, left: beforeFrame.left, width: beforeFrame.width, top: beforeFrame.top });
    expect(afterSegment.transform[0].translateX).toBeGreaterThan(beforeSegment.transform[0].translateX);
    expect(afterProgress.props.accessibilityValue).toEqual({ min: 1, max: 3, now: 2 });
    expect(screen.getAllByTestId('onboarding-progress')).toHaveLength(1);
  });

  it('uses solid Press Jelly actions for the first two scenes and a solid Magnet action on the last', () => {
    const { view } = renderWelcome();
    const primary = () => screen.getByRole('button', { name: t('next') });

    expect(screen.getByRole('button', { name: t('skip') })).toBeOnTheScreen();
    expect(screen.getAllByRole('button', { name: t('next') })).toHaveLength(1);
    expect(screen.getByTestId('onboarding-progress')).toHaveProp('accessibilityValue', { min: 1, max: 3, now: 1 });
    expect(StyleSheet.flatten(screen.getByRole('button', { name: t('skip') }).props.style)).toEqual(expect.objectContaining({ minHeight: 44 }));
    expect(JSON.stringify(view.toJSON())).not.toMatch(/LinearGradient|Gradient|gloss|glow/i);
    expect(primary()).toBeOnTheScreen();
    expect(StyleSheet.flatten(primary().props.style).backgroundColor).toBe(createTheme('light').colors.ember);
    expect(view.UNSAFE_getAllByType(PressJelly)).toHaveLength(1);
    expect(view.UNSAFE_queryAllByType(MagnetButton)).toHaveLength(0);

    fireEvent.press(primary());
    expect(view.UNSAFE_getAllByType(PressJelly)).toHaveLength(1);
    fireEvent.press(primary());
    expect(view.UNSAFE_queryAllByType(PressJelly)).toHaveLength(0);
    expect(view.UNSAFE_getAllByType(MagnetButton)).toHaveLength(1);
    expect(primary()).toBeOnTheScreen();
    expect(StyleSheet.flatten(primary().props.style).backgroundColor).toBe(createTheme('light').colors.ember);
    expect(JSON.stringify(view.toJSON())).not.toMatch(/LinearGradient|Gradient|gloss|glow/i);

    act(() => fireEvent(primary(), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } }));
    expect(screen.getByTestId('onboarding-progress')).toHaveProp('accessibilityValue', { min: 1, max: 3, now: 3 });
  });

  it('completes only from the final Next action and preserves skip as the explicit alternate', async () => {
    const { persist, onComplete } = renderWelcome();
    fireEvent.press(screen.getByRole('button', { name: t('next') }));
    fireEvent.press(screen.getByRole('button', { name: t('next') }));
    expect(persist).not.toHaveBeenCalled();

    await act(async () => {
      fireEvent(screen.getByRole('button', { name: t('next') }), 'accessibilityAction', {
        nativeEvent: { actionName: 'activate' },
      });
      await Promise.resolve();
    });

    expect(persist).toHaveBeenCalledWith({ version: 1, page: 2, done: true });
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('keeps illustration surfaces, strokes and accent foregrounds distinguishable in both themes', () => {
    for (const base of [palettes.light, palettes.dark]) {
      const colors = onboardingScenePalette(base);
      for (const [foreground, background] of [
        [colors.border, colors.surface],
        [colors.muted, colors.raised],
        [colors.accent, colors.softAccent],
        [colors.onAccent, colors.accent],
      ]) {
        expect(contrastRatio(foreground, background)).toBeGreaterThanOrEqual(3);
      }
    }
  });
});

describe('U034 Grain Drift splash', () => {
  it('uses the required still-grain red brand surface and pauses/resumes with app state', () => {
    let onStateChange: ((state: 'active' | 'background' | 'inactive') => void) | undefined;
    const addListener = jest.spyOn(AppState, 'addEventListener').mockImplementation(((event, listener) => {
      if (event === 'change') onStateChange = listener as (state: 'active' | 'background' | 'inactive') => void;
      return { remove: jest.fn() };
    }) as typeof AppState.addEventListener);

    const view = render(
      <KinetikProvider scheme="dark" reduceMotion="always" haptics={false}>
        <SplashScreen onFirstFrame={jest.fn()} />
      </KinetikProvider>,
    );
    const grain = view.UNSAFE_getByType(GrainDrift);
    const brandSurface = StyleSheet.flatten(screen.getByTestId('custom-splash-screen').props.style).backgroundColor;
    const logo = screen.getByTestId('splash-mark-probe');

    expect(grain.props).toMatchObject({ grain: 0, speed: 3, reduceEffects: true });
    expect(brandSurface).toBeTruthy();
    expect(logo.props.accessibilityHint).toContain(`background=${brandSurface}`);
    expect(StyleSheet.flatten(grain.props.style)).toMatchObject({ position: 'absolute', top: 0, right: 0, bottom: 0, left: 0 });

    act(() => onStateChange?.('background'));
    expect(view.UNSAFE_getByType(GrainDrift).props.active).toBe(false);
    act(() => onStateChange?.('active'));
    expect(view.UNSAFE_getByType(GrainDrift).props.active).toBe(true);

    view.unmount();
    addListener.mockRestore();
  });

  it('cancels the pending native frame callback when splash unmounts', () => {
    jest.spyOn(AppState, 'addEventListener').mockImplementation((() => ({ remove: jest.fn() })) as typeof AppState.addEventListener);
    const oldRequest = globalThis.requestAnimationFrame;
    const oldCancel = globalThis.cancelAnimationFrame;
    const callbacks: Array<(time: number) => void> = [];
    const cancel = jest.fn();
    globalThis.requestAnimationFrame = ((callback: (time: number) => void) => {
      callbacks.push(callback);
      return callbacks.length;
    }) as typeof globalThis.requestAnimationFrame;
    globalThis.cancelAnimationFrame = cancel;

    const view = render(
      <KinetikProvider scheme="light" reduceMotion="always" haptics={false}>
        <SplashScreen onFirstFrame={jest.fn()} />
      </KinetikProvider>,
    );
    fireEvent(screen.getByTestId('custom-splash-screen'), 'layout', {
      nativeEvent: { layout: { x: 0, y: 0, width: 320, height: 640 } },
    });
    expect(callbacks).toHaveLength(1);
    view.unmount();
    expect(cancel).toHaveBeenCalledWith(1);

    globalThis.requestAnimationFrame = oldRequest;
    globalThis.cancelAnimationFrame = oldCancel;
  });
});
