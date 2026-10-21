jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AppState } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { finishWhenIdle, ONBOARDING_VERSION, OnboardingScreen, swipePageDelta } from '../src/onboarding/OnboardingScreen';
import { ModelScene, OcrScene, ProgressScene, TranslationScene } from '../src/onboarding/OnboardingScenes';
import { createTranslator } from '../src/i18n/messages';
import { palettes } from '../src/ui/theme';
import { AnimatedNumber, AnimatedTabBar, AppIcon, Balloons, DownloadSculpture } from '../src/ui/Motion';
import { SplashScreen } from '../src/ui/SplashScreen';

jest.mock('react-native-safe-area-context', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  return { SafeAreaProvider: ({ children }: { children: React.ReactNode }) => ReactModule.createElement(ReactNative.View, null, children), useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }) };
});

jest.mock('react-native-svg', () => {
  const ReactModule = require('react');
  const ReactNative = require('react-native');
  const Svg = ReactModule.forwardRef((props: object, ref: object) => ReactModule.createElement(ReactNative.View, { ...props, ref }));
  const Shape = (props: object) => ReactModule.createElement(ReactNative.View, props);
  return { __esModule: true, default: Svg, Svg, Path: Shape, Circle: Shape, Rect: Shape, G: Shape };
});

jest.mock('react-native-reanimated', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  const AnimatedView = ReactModule.forwardRef((props: object, ref: object) => ReactModule.createElement(ReactNative.View, { ...props, ref }));
  const FadeIn = { duration: jest.fn(() => 'fade-in') };
  return {
    __esModule: true, default: { createAnimatedComponent: (component: unknown) => component, View: AnimatedView, Text: ReactNative.Text, ScrollView: ReactNative.ScrollView, Image: ReactNative.Image }, View: AnimatedView,
    useReducedMotion: jest.fn(() => true), ReduceMotion: { Always: 'always', Never: 'never' }, useSharedValue: (value: number) => ReactModule.useRef({ value, get() { return this.value; }, set(next: number) { this.value = next; } }).current,
    useFrameCallback: () => ({ setActive: jest.fn() }), useAnimatedRef: () => ReactModule.useRef(null), useDerivedValue: (fn: () => unknown) => ({ get: fn }), Extrapolation: { CLAMP: 'clamp' },
    useAnimatedScrollHandler: () => jest.fn(),
    useAnimatedStyle: (callback: () => unknown) => callback(),
    useAnimatedProps: (callback: () => unknown) => callback(), cancelAnimation: jest.fn(),
    withRepeat: jest.fn((value: unknown) => value), withTiming: (value: unknown) => value, withSpring: (value: unknown) => value, withSequence: (...values: unknown[]) => values.at(-1), withDelay: (_delay: number, value: unknown) => value,
    Easing: { bezier: (..._values: number[]) => (value: number) => value, linear: (value: number) => value, sin: (value: number) => value, inOut: (easing: unknown) => easing },
    interpolate: (value: number, input: number[], output: number[]) => output[0] + Math.max(0, Math.min(1, (value - input[0]) / ((input[1] ?? input[0] + 1) - input[0]))) * (output[1] - output[0]), FadeIn,
  };
});

const animationMocks = jest.requireMock('react-native-reanimated') as { cancelAnimation: jest.Mock; withRepeat: jest.Mock; useReducedMotion: jest.Mock; FadeIn: { duration: jest.Mock } };
const translator = createTranslator('en');

type OnboardingTestOptions = { persist?: jest.Mock; onComplete?: jest.Mock; direction?: 'ltr' | 'rtl'; replay?: boolean; onExit?: jest.Mock; initialState?: { version: number; page: number; done: boolean } };
function renderOnboarding({ persist = jest.fn().mockResolvedValue(undefined), onComplete = jest.fn(), direction = 'ltr', replay = false, onExit = jest.fn(), initialState = { version: ONBOARDING_VERSION, page: 0, done: false } }: OnboardingTestOptions = {}) {
  const view = render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={translator} initialState={initialState} persist={persist} onComplete={onComplete} ios={false} direction={direction} replay={replay} onExit={onExit} /></SafeAreaProvider>);
  fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 700 } } });
  return { persist, onComplete, onExit, view };
}

describe('three-step Expo onboarding', () => {
  it('does not run Finish while a save is already in flight', async () => {
    const finish = jest.fn().mockResolvedValue(undefined);
    await finishWhenIdle(true, finish);
    expect(finish).not.toHaveBeenCalled();
    await finishWhenIdle(false, finish);
    expect(finish).toHaveBeenCalledTimes(1);
  });

  it('persists Skip as completion on a first run', async () => {
    const { persist, onComplete } = renderOnboarding();
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Skip' })); await Promise.resolve(); });
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 2, done: true });
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('presents three title-only steps for importing, translating, and comparing documents', () => {
    renderOnboarding();
    const titles = [translator('intro1Title'), translator('intro2Title'), translator('intro3Title')];
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    for (const title of titles) expect(screen.getByLabelText(title, { includeHiddenElements: true })).toBeOnTheScreen();
    for (const key of ['intro1Body', 'intro2Body', 'intro3Body'] as const) {
      expect(screen.queryByText(translator(key), { includeHiddenElements: true })).toBeNull();
    }
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByTestId('model-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByTestId('progress-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Next' })).toBeOnTheScreen();
  });

  it('saves completion only when Finish is pressed on the final step', async () => {
    const persist = jest.fn().mockResolvedValue(undefined);
    const onComplete = jest.fn();
    renderOnboarding({ persist, onComplete });
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(persist).not.toHaveBeenCalled();
    await act(async () => { fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } }); await Promise.resolve(); });
    expect(persist).toHaveBeenLastCalledWith({ version: ONBOARDING_VERSION, page: 2, done: true });
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('keeps replay completion unchanged and exits through Skip', () => {
    const { persist, onExit, onComplete } = renderOnboarding({ replay: true, initialState: { version: ONBOARDING_VERSION, page: 2, done: true } });
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(persist).not.toHaveBeenCalled();
    expect(onComplete).not.toHaveBeenCalled();
  });

  it('uses the active direction and preserves swipe threshold behavior', () => {
    expect(swipePageDelta(-100, 'ltr')).toBe(1);
    expect(swipePageDelta(100, 'rtl')).toBe(1);
    expect(swipePageDelta(-100, 'rtl')).toBe(-1);
    expect(swipePageDelta(40, 'rtl')).toBe(0);
    const { persist } = renderOnboarding({ direction: 'rtl' });
    expect(screen.getByTestId('onboarding-root')).toHaveStyle({ direction: 'rtl' });
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    expect(persist).not.toHaveBeenCalled();
  });

  it('shows a recovery message when completion cannot be saved', async () => {
    const persist = jest.fn().mockRejectedValue(new Error('Disk is full.'));
    renderOnboarding({ persist });
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await act(async () => { fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } }); await Promise.resolve(); });
    expect(screen.getByText('Could not save the introduction. Try again.')).toBeOnTheScreen();
    expect(screen.getByTestId('progress-scene', { includeHiddenElements: true })).toBeOnTheScreen();
  });
});

describe('illustration motion lifecycle', () => {
  it('stops and resumes the active scene when the app backgrounds and returns', () => {
    let changeState: ((state: 'active' | 'background' | 'inactive') => void) | undefined;
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      changeState = listener as (state: 'active' | 'background' | 'inactive') => void;
      return { remove: jest.fn() };
    });
    animationMocks.useReducedMotion.mockReturnValue(false);
    animationMocks.withRepeat.mockClear();
    const { view: onboarding } = renderOnboarding();
    act(() => changeState?.('active'));
    expect(animationMocks.withRepeat).toHaveBeenCalledTimes(1);
    act(() => changeState?.('background'));
    expect(animationMocks.withRepeat).toHaveBeenCalledTimes(1);
    act(() => changeState?.('active'));
    expect(animationMocks.withRepeat).toHaveBeenCalledTimes(2);
    onboarding.unmount();
    jest.restoreAllMocks();
    animationMocks.useReducedMotion.mockReturnValue(true);
  });

  it('starts automatic scene motion only while active and motion is allowed', () => {
    animationMocks.useReducedMotion.mockReturnValue(false);
    animationMocks.withRepeat.mockClear();
    const hidden = render(<TranslationScene colors={palettes.light} active={false} reduced={false} />);
    expect(animationMocks.withRepeat).not.toHaveBeenCalled();
    hidden.unmount();
    const active = render(<OcrScene colors={palettes.light} active reduced={false} />);
    expect(animationMocks.withRepeat).toHaveBeenCalledTimes(1);
    active.unmount();
    animationMocks.useReducedMotion.mockReturnValue(true);
  });

  it('freezes the animated document and cancels loops for Reduce Motion or an inactive app', () => {
    animationMocks.cancelAnimation.mockClear();
    animationMocks.withRepeat.mockClear();
    const reduced = render(<ModelScene colors={palettes.light} active reduced />);
    expect(animationMocks.withRepeat).not.toHaveBeenCalled();
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
    reduced.unmount();
    const inactive = render(<ProgressScene colors={palettes.light} active={false} reduced={false} />);
    expect(animationMocks.withRepeat).not.toHaveBeenCalled();
    inactive.unmount();
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
  });

  it('renders an accessible brand mark and tab navigation with theme-aware selectable labels', () => {
    const icon = render(<AppIcon size={64} background={palettes.dark.accent} active={false} reduced />);
    expect(screen.getByRole('image', { name: 'Folivela' })).toBeOnTheScreen();
    icon.unmount();
    const onChange = jest.fn();
    render(<AnimatedTabBar active="settings" onChange={onChange} colors={palettes.dark} labels={{ library: 'Files', settings: 'Preferences' }} bottomInset={12} />);
    expect(screen.getByRole('tab', { name: 'Preferences' })).toHaveProp('accessibilityState', { selected: true });
    fireEvent.press(screen.getByRole('tab', { name: 'Files' }));
    expect(onChange).toHaveBeenCalledWith('library');
  });

  it('mirrors the moving tab indicator to match RTL tab order', () => {
    render(<AnimatedTabBar active="library" direction="rtl" onChange={jest.fn()} colors={palettes.light} />);
    expect(screen.getByTestId('tab-indicator').props.style).toEqual(expect.arrayContaining([expect.objectContaining({ left: '62.5%' })]));
  });

  it('shows a clamped download progress and a reduced-motion numeric value', () => {
    render(<><AnimatedNumber value={95} suffix="%" reduced fontSize={40} /><AnimatedNumber value={4.5} suffix=" GB" reduced decimals={1} /><DownloadSculpture progress={140} active={false} reduced /></>);
    expect(screen.getByText('95%')).toBeOnTheScreen();
    expect(screen.getByText('4.5 GB')).toBeOnTheScreen();
    expect(screen.getByText('95%').props.style).toEqual(expect.objectContaining({ fontSize: 40 }));
    expect(screen.getByTestId('download-sculpture')).toHaveProp('accessibilityValue', { min: 0, max: 100, now: 100 });
  });

  it('fires the one-shot completion callback for a reduced-motion ready celebration', () => {
    const onComplete = jest.fn();
    render(<Balloons active reduced onComplete={onComplete} />);
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('keeps one active celebration callback stable across rerenders and rearms for a new Ready event', () => {
    jest.useFakeTimers();
    const completion = jest.fn();
    const view = render(<Balloons active reduced={false} onComplete={() => completion()} />);
    view.rerender(<Balloons active reduced={false} onComplete={() => completion()} />);
    act(() => { jest.advanceTimersByTime(1050); });
    expect(completion).toHaveBeenCalledTimes(1);
    view.rerender(<Balloons active={false} reduced={false} onComplete={() => completion()} />);
    view.rerender(<Balloons active reduced={false} onComplete={() => completion()} />);
    act(() => { jest.advanceTimersByTime(1050); });
    expect(completion).toHaveBeenCalledTimes(2);
    jest.useRealTimers();
  });

  it('notifies startup timing only after the splash has had two native frame opportunities', () => {
    const original = globalThis.requestAnimationFrame;
    const frames: Array<Parameters<typeof globalThis.requestAnimationFrame>[0]> = [];
    globalThis.requestAnimationFrame = ((callback: (time: number) => void) => { frames.push(callback); return frames.length; }) as typeof globalThis.requestAnimationFrame;
    const onFirstFrame = jest.fn();
    const splash = render(<SplashScreen onFirstFrame={onFirstFrame} />);
    fireEvent(screen.getByTestId('custom-splash-screen'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 320, height: 640 } } });
    expect(onFirstFrame).not.toHaveBeenCalled();
    act(() => frames.shift()?.(16));
    expect(onFirstFrame).not.toHaveBeenCalled();
    act(() => frames.shift()?.(32));
    expect(onFirstFrame).toHaveBeenCalledTimes(1);
    splash.unmount();
    globalThis.requestAnimationFrame = original;
  });
});
