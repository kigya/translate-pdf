jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { BackHandler, PanResponder } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
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
    __esModule: true, default: { createAnimatedComponent: (component: unknown) => component, View: AnimatedView }, View: AnimatedView,
    useReducedMotion: jest.fn(() => true), useSharedValue: (value: number) => ReactModule.useRef({ value }).current,
    useAnimatedStyle: (callback: () => unknown) => callback(),
    useAnimatedProps: (callback: () => unknown) => callback(), cancelAnimation: jest.fn(),
    withRepeat: jest.fn((value: unknown) => value), withTiming: (value: unknown) => value,
    interpolate: (value: number, input: number[], output: number[]) => output[0] + Math.max(0, Math.min(1, (value - input[0]) / ((input[1] ?? input[0] + 1) - input[0]))) * (output[1] - output[0]), FadeIn,
  };
});

const animationMocks = jest.requireMock('react-native-reanimated') as { cancelAnimation: jest.Mock; withRepeat: jest.Mock; useReducedMotion: jest.Mock; FadeIn: { duration: jest.Mock } };
const translator = createTranslator('en');

type OnboardingTestOptions = { persist?: jest.Mock; onComplete?: jest.Mock; direction?: 'ltr' | 'rtl'; replay?: boolean; onExit?: jest.Mock; initialState?: { version: number; page: number; done: boolean } };
function renderOnboarding({ persist = jest.fn().mockResolvedValue(undefined), onComplete = jest.fn(), direction = 'ltr', replay = false, onExit = jest.fn(), initialState = { version: ONBOARDING_VERSION, page: 0, done: false } }: OnboardingTestOptions = {}) {
  render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={translator} initialState={initialState} persist={persist} onComplete={onComplete} ios={false} direction={direction} replay={replay} onExit={onExit} /></SafeAreaProvider>);
  return { persist, onComplete, onExit };
}

describe('four-scene introduction', () => {
  it('persists Skip as completion on a first run', async () => {
    const { persist, onComplete } = renderOnboarding();
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Skip' })); await Promise.resolve(); });
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 0, done: true });
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('keeps Next as the only bottom action and makes page dots passive', () => {
    renderOnboarding();
    expect(screen.getAllByRole('button').map(control => control.props.accessibilityLabel)).toEqual(['Skip', 'Next']);
    expect(screen.getByTestId('onboarding-page-indicator')).toHaveProp('accessibilityRole', 'image');
    expect(screen.queryByRole('button', { name: 'Previous' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Page 2 of 4' })).toBeNull();
  });

  it('shows four automatic document demonstrations without illustration controls', () => {
    renderOnboarding();
    expect(screen.getByText(translator('intro1Title'))).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByTestId('ocr-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByTestId('model-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByTestId('progress-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    expect(screen.queryByText(/notification/i)).toBeNull();
    expect(screen.getAllByRole('button').map(control => control.props.accessibilityLabel)).toEqual(['Skip', 'Next']);
  });

  it('finishes from the final Next action and completes only the first-run record', async () => {
    const persist = jest.fn().mockResolvedValue(undefined);
    const onComplete = jest.fn();
    renderOnboarding({ persist, onComplete });
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Next' })); await Promise.resolve(); });
    expect(persist).toHaveBeenLastCalledWith({ version: ONBOARDING_VERSION, page: 3, done: true });
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('does not allow a duplicate completion while persistence is pending', async () => {
    const commit = jest.fn().mockResolvedValue(undefined);
    await expect(finishWhenIdle(true, commit)).resolves.toBeUndefined();
    expect(commit).not.toHaveBeenCalled();
    await finishWhenIdle(false, commit);
    expect(commit).toHaveBeenCalledTimes(1);
  });

  it('replays from the first page and system Back exits to Settings without mutating completion', async () => {
    const addListener = jest.spyOn(BackHandler, 'addEventListener').mockImplementation(() => ({ remove: jest.fn() }));
    const { persist, onExit, onComplete } = renderOnboarding({ replay: true, initialState: { version: ONBOARDING_VERSION, page: 3, done: true } });
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    const back = addListener.mock.calls.at(-1)?.[1] as (() => boolean) | undefined;
    expect(back?.()).toBe(true);
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(persist).not.toHaveBeenCalled();
    expect(onComplete).not.toHaveBeenCalled();
    addListener.mockRestore();
  });

  it('lets Skip close a replay without setting done or returning Home', () => {
    const { persist, onExit, onComplete } = renderOnboarding({ replay: true });
    fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(persist).not.toHaveBeenCalled();
    expect(onComplete).not.toHaveBeenCalled();
  });

  it('uses localized swipe direction for scene navigation', async () => {
    expect(swipePageDelta(-100, 'ltr')).toBe(1);
    expect(swipePageDelta(100, 'ltr')).toBe(-1);
    expect(swipePageDelta(100, 'rtl')).toBe(1);
    expect(swipePageDelta(-100, 'rtl')).toBe(-1);
    expect(swipePageDelta(40, 'rtl')).toBe(0);
    const createSpy = jest.spyOn(PanResponder, 'create');
    const { persist } = renderOnboarding({ direction: 'rtl' });
    const responder = createSpy.mock.results.at(-1)?.value;
    const callbacks = createSpy.mock.calls.at(-1)?.[0];
    expect(callbacks?.onMoveShouldSetPanResponder?.({} as never, { dx: 42, dy: 5 } as never)).toBe(true);
    await act(async () => { callbacks?.onPanResponderRelease?.({} as never, { dx: 100, dy: 0 } as never); await Promise.resolve(); });
    expect(screen.getByTestId('ocr-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 1, done: false });
    expect(responder).toBeDefined();
    createSpy.mockRestore();
  });

  it('applies right-to-left text direction while keeping illustration geometry left-to-right', () => {
    renderOnboarding({ direction: 'rtl' });
    expect(screen.getByTestId('onboarding-root')).toHaveStyle({ direction: 'rtl' });
    expect(screen.getByTestId('onboarding-example-scene', { includeHiddenElements: true })).toHaveStyle({ direction: 'ltr' });
    expect(screen.getByText(translator('intro1Title'))).toHaveStyle({ writingDirection: 'rtl' });
  });

  it('uses a side-by-side layout on landscape tablets and keeps the primary action full width', () => {
    const reactNative = require('react-native') as typeof import('react-native');
    const dimensions = jest.spyOn(reactNative, 'useWindowDimensions');
    dimensions.mockReturnValue({ width: 720, height: 360, scale: 1, fontScale: 1 });
    renderOnboarding();
    expect(screen.getByText(translator('intro1Title'))).toHaveStyle({ fontSize: 36, textAlign: 'left' });
    expect(screen.getByTestId('onboarding-example-scene', { includeHiddenElements: true }).props.style.width).toBeCloseTo(388.12, 1);
    expect(screen.getByRole('button', { name: 'Next' })).toHaveStyle({ minHeight: 48 });
    dimensions.mockRestore();
  });

  it('shows a useful message when a page checkpoint cannot be saved', async () => {
    const persist = jest.fn().mockRejectedValue(new Error('Disk is full.'));
    renderOnboarding({ persist });
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Next' })); await Promise.resolve(); });
    expect(screen.getByText('Could not save the introduction. Try again.')).toBeOnTheScreen();
    expect(screen.getByTestId('ocr-scene', { includeHiddenElements: true })).toBeOnTheScreen();
  });
});

describe('illustration motion lifecycle', () => {
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
    expect(screen.getByRole('image', { name: 'Translate PDF' })).toBeOnTheScreen();
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
