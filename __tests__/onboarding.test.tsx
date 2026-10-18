import React from 'react';
import { AppState, BackHandler, PanResponder } from 'react-native';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { finishWhenIdle, ONBOARDING_VERSION, OnboardingScreen, swipePageDelta } from '../src/onboarding/OnboardingScreen';
import { ProgressScene, TranslationScene } from '../src/onboarding/OnboardingScenes';
import { OcrScene } from '../src/onboarding/OnboardingScenes';
import { createTranslator } from '../src/i18n/messages';
import { palettes } from '../src/ui/theme';

jest.mock('react-native-safe-area-context', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  return {
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => ReactModule.createElement(ReactNative.View, null, children),
    useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
  };
});

jest.mock('react-native-reanimated', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  const AnimatedView = ReactModule.forwardRef((props: object, ref: object) => ReactModule.createElement(ReactNative.View, { ...props, ref }));
  const FadeIn = { duration: jest.fn(() => 'fade-in') };
  return {
    __esModule: true,
    default: { View: AnimatedView },
    View: AnimatedView,
    useReducedMotion: jest.fn(() => true),
    useSharedValue: (value: number) => ReactModule.useRef({ value }).current,
    useAnimatedStyle: (callback: () => unknown) => callback(),
    cancelAnimation: jest.fn(),
    withRepeat: jest.fn((value: unknown) => value),
    withTiming: (value: unknown) => value,
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    Extrapolation: { CLAMP: 'clamp' },
    FadeIn,
  };
});

const animationMocks = jest.requireMock('react-native-reanimated') as { cancelAnimation: jest.Mock; withRepeat: jest.Mock; useReducedMotion: jest.Mock };

function renderOnboarding(persist = jest.fn().mockResolvedValue(undefined), onComplete = jest.fn(), direction: 'ltr' | 'rtl' = 'ltr') {
  render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={createTranslator('en')} initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }} persist={persist} onComplete={onComplete} ios={false} direction={direction} /></SafeAreaProvider>);
  return { persist, onComplete };
}

describe('four-scene introduction', () => {
  it('stores Skip as completion without starting an import or job', async () => {
    const { persist, onComplete } = renderOnboarding();
    expect(screen.getByText('Example')).toBeOnTheScreen();
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
      await Promise.resolve();
    });
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 0, done: true });
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('does not start a second completion while one is saving', async () => {
    const commit = jest.fn().mockResolvedValue(undefined);
    await expect(finishWhenIdle(true, commit)).resolves.toBeUndefined();
    expect(commit).not.toHaveBeenCalled();
    await finishWhenIdle(false, commit);
    expect(commit).toHaveBeenCalledTimes(1);
  });

  it('offers manual next and back navigation across all four scenes', () => {
    renderOnboarding();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Bring labels into the translation')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Choose a model for your device')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Keep control of long jobs')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Previous' }));
    expect(screen.getByText('Choose a model for your device')).toBeOnTheScreen();
  });

  it('applies RTL to the reading flow while keeping the EN/RU example diagram LTR', () => {
    renderOnboarding(jest.fn().mockResolvedValue(undefined), jest.fn(), 'rtl');
    expect(screen.getByTestId('onboarding-root')).toHaveStyle({ direction: 'rtl' });
    expect(screen.getByTestId('onboarding-example-scene')).toHaveStyle({ direction: 'ltr' });
    expect(screen.getByText('Read the manual in your language')).toHaveStyle({ writingDirection: 'rtl' });
  });

  it('falls back to active, full-motion defaults when platform accessibility state is unavailable', () => {
    const currentStateDescriptor = Object.getOwnPropertyDescriptor(AppState, 'currentState');
    Object.defineProperty(AppState, 'currentState', { configurable: true, value: null });
    animationMocks.useReducedMotion.mockReturnValueOnce(null as unknown as boolean);
    const fadeInDuration = (jest.requireMock('react-native-reanimated') as { FadeIn: { duration: jest.Mock } }).FadeIn.duration;
    fadeInDuration.mockClear();
    const intro = render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={createTranslator('en')} initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }} persist={jest.fn().mockResolvedValue(undefined)} onComplete={jest.fn()} ios={false} /></SafeAreaProvider>);
    expect(screen.getByTestId('onboarding-animated-content')).toHaveProp('entering', 'fade-in');
    expect(fadeInDuration).toHaveBeenCalledWith(230);
    intro.unmount();
    if (currentStateDescriptor) Object.defineProperty(AppState, 'currentState', currentStateDescriptor);
    else delete (AppState as unknown as { currentState?: string | null }).currentState;
  });

  it('uses compact portrait typography and a side-by-side landscape layout', () => {
    const reactNative = require('react-native') as typeof import('react-native');
    const dimensions = jest.spyOn(reactNative, 'useWindowDimensions');
    dimensions.mockReturnValue({ width: 320, height: 700, scale: 1, fontScale: 1 });
    const portrait = render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={createTranslator('en')} initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }} persist={jest.fn().mockResolvedValue(undefined)} onComplete={jest.fn()} ios={false} /></SafeAreaProvider>);
    expect(screen.getByText('Read the manual in your language')).toHaveStyle({ fontSize: 27 });
    expect(screen.getByTestId('onboarding-example-scene').props.style).toEqual(expect.objectContaining({ width: 262 }));
    portrait.unmount();

    dimensions.mockReturnValue({ width: 720, height: 360, scale: 1, fontScale: 1 });
    render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={createTranslator('en')} initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }} persist={jest.fn().mockResolvedValue(undefined)} onComplete={jest.fn()} ios={false} /></SafeAreaProvider>);
    expect(screen.getByText('Read the manual in your language')).toHaveStyle({ fontSize: 36, textAlign: 'left' });
    expect(screen.getByTestId('onboarding-example-scene').props.style.width).toBeCloseTo(388.12, 1);
    dimensions.mockRestore();
  });

  it('maps touch swipes to next/back in each locale reading direction', () => {
    expect(swipePageDelta(-100, 'ltr')).toBe(1);
    expect(swipePageDelta(100, 'ltr')).toBe(-1);
    expect(swipePageDelta(100, 'rtl')).toBe(1);
    expect(swipePageDelta(-100, 'rtl')).toBe(-1);
    expect(swipePageDelta(40, 'rtl')).toBe(0);

    renderOnboarding(jest.fn().mockResolvedValue(undefined), jest.fn(), 'rtl');
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Bring labels into the translation')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Previous' }));
    expect(screen.getByText('Read the manual in your language')).toBeOnTheScreen();
  });

  it('connects the RTL responder callbacks to scene navigation', async () => {
    const createSpy = jest.spyOn(PanResponder, 'create');
    const { persist } = renderOnboarding(jest.fn().mockResolvedValue(undefined), jest.fn(), 'rtl');
    const responder = createSpy.mock.calls[0][0];
    expect(responder.onMoveShouldSetPanResponder?.({} as never, { dx: 42, dy: 5 } as never)).toBe(true);
    expect(responder.onMoveShouldSetPanResponder?.({} as never, { dx: 42, dy: 40 } as never)).toBe(false);
    await act(async () => { responder.onPanResponderRelease?.({} as never, { dx: 0, dy: 0 } as never); await Promise.resolve(); });
    expect(persist).not.toHaveBeenCalled();
    for (let index = 0; index < 3; index += 1) {
      await act(async () => { responder.onPanResponderRelease?.({} as never, { dx: 100, dy: 0 } as never); await Promise.resolve(); });
    }
    expect(screen.getByText('Keep control of long jobs')).toBeOnTheScreen();
    await act(async () => { responder.onPanResponderRelease?.({} as never, { dx: -100, dy: 0 } as never); await Promise.resolve(); });
    expect(screen.getByText('Choose a model for your device')).toBeOnTheScreen();
    for (let index = 0; index < 2; index += 1) {
      await act(async () => { responder.onPanResponderRelease?.({} as never, { dx: -100, dy: 0 } as never); await Promise.resolve(); });
    }
    expect(screen.getByText('Read the manual in your language')).toBeOnTheScreen();
    expect(responder.onMoveShouldSetPanResponder?.({} as never, { dx: 20, dy: 0 } as never)).toBe(false);
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 1, done: false });
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 0, done: false });
    createSpy.mockRestore();
  });

  it('uses the hardware back gesture only when a previous scene exists', async () => {
    const addListener = jest.spyOn(BackHandler, 'addEventListener').mockImplementation(() => ({ remove: jest.fn() }));
    renderOnboarding();
    const back = addListener.mock.calls[0][1] as () => boolean;
    expect(back()).toBe(false);
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await act(async () => { expect(back()).toBe(true); await Promise.resolve(); });
    expect(screen.getByText('Read the manual in your language')).toBeOnTheScreen();
    addListener.mockRestore();
  });

  it('shows a useful message when saving a changed onboarding page fails', async () => {
    const persist = jest.fn().mockRejectedValue(new Error('Disk is full.'));
    renderOnboarding(persist);
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Next' })); await Promise.resolve(); });
    expect(screen.getByText('Could not save the introduction. Try again.')).toBeOnTheScreen();
    expect(screen.getByText('Bring labels into the translation')).toBeOnTheScreen();
  });

  it('lets a page indicator jump directly to a selected scene', () => {
    renderOnboarding();
    fireEvent.press(screen.getByRole('button', { name: 'Page 3 of 4' }));
    expect(screen.getByText('Choose a model for your device')).toBeOnTheScreen();
  });

  it('cancels the scene loop when the scene becomes inactive', () => {
    animationMocks.useReducedMotion.mockReturnValue(false);
    animationMocks.withRepeat.mockClear();
    animationMocks.cancelAnimation.mockClear();
    const scene = render(<TranslationScene colors={palettes.light} t={createTranslator('en')} active reduced={false} />);
    expect(animationMocks.withRepeat).toHaveBeenCalled();
    animationMocks.cancelAnimation.mockClear();
    scene.rerender(<TranslationScene colors={palettes.light} t={createTranslator('en')} active={false} reduced={false} />);
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
    scene.unmount();
    animationMocks.useReducedMotion.mockReturnValue(true);
  });

  it('keeps a reduced-motion scene static and cancels its loop when hidden', () => {
    animationMocks.cancelAnimation.mockClear();
    animationMocks.withRepeat.mockClear();
    const staticScene = render(<TranslationScene colors={palettes.light} t={createTranslator('en')} active reduced />);
    expect(animationMocks.withRepeat).not.toHaveBeenCalled();
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
    staticScene.unmount();

    animationMocks.cancelAnimation.mockClear();
    const movingScene = render(<TranslationScene colors={palettes.light} t={createTranslator('en')} active reduced={false} />);
    expect(animationMocks.withRepeat).toHaveBeenCalledTimes(1);
    movingScene.unmount();
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
  });

  it('lets screen-reader actions and visible controls adjust the comparison view', () => {
    const createSpy = jest.spyOn(PanResponder, 'create');
    const staticExample = render(<TranslationScene colors={palettes.light} t={createTranslator('en')} active={false} reduced />);
    const actions = screen.getByRole('adjustable').props.onAccessibilityAction as (event: { nativeEvent: { actionName: string } }) => void;
    act(() => actions({ nativeEvent: { actionName: 'increment' } }));
    act(() => actions({ nativeEvent: { actionName: 'decrement' } }));
    const original = screen.getByRole('button', { name: 'Tap to show original' });
    const translated = screen.getByRole('button', { name: 'Tap to show translation' });
    fireEvent.press(original);
    expect(original).toHaveStyle({ backgroundColor: palettes.light.accent });
    fireEvent.press(translated);
    expect(translated).toHaveStyle({ backgroundColor: palettes.light.accent });
    const gestures = createSpy.mock.calls[0][0];
    expect(gestures.onMoveShouldSetPanResponder?.({} as never, { dx: 2 } as never)).toBe(false);
    expect(gestures.onMoveShouldSetPanResponder?.({} as never, { dx: 4 } as never)).toBe(true);
    gestures.onPanResponderMove?.({} as never, { dx: 900 } as never);
    gestures.onPanResponderRelease?.({} as never, { dx: -900 } as never);
    staticExample.unmount();
    createSpy.mockRestore();

    const animated = render(<TranslationScene colors={palettes.light} t={createTranslator('en')} active={false} reduced={false} />);
    fireEvent.press(screen.getByRole('button', { name: 'Tap to show original' }));
    fireEvent.press(screen.getByRole('button', { name: 'Tap to show translation' }));
    animated.unmount();
  });

  it('pauses scene motion when hidden and lets the scan example toggle its label', () => {
    animationMocks.cancelAnimation.mockClear();
    animationMocks.withRepeat.mockClear();
    const hidden = render(<OcrScene colors={palettes.light} t={createTranslator('en')} active={false} reduced={false} />);
    expect(animationMocks.withRepeat).not.toHaveBeenCalled();
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
    const inlet = screen.getByRole('button', { name: 'Inlet label' });
    const outlet = screen.getByRole('button', { name: 'Outlet label' });
    expect(screen.getByText('Cooling system')).toBeOnTheScreen();
    expect(inlet).toHaveProp('accessibilityState', { selected: true });
    fireEvent.press(outlet);
    expect(outlet).toHaveProp('accessibilityState', { selected: true });
    expect(screen.getByText('Outlet connection · Выходной патрубок')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Tap to show original' }));
    expect(screen.getByText('Original text')).toBeOnTheScreen();
    expect(screen.getByText('OUTLET')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Tap to show translation' }));
    expect(screen.getByText('Russian text')).toBeOnTheScreen();
    expect(screen.getByText('ВЫХОД')).toBeOnTheScreen();
    hidden.unmount();
  });

  it('pauses and resumes the illustrative job using its visible controls', () => {
    animationMocks.withRepeat.mockClear();
    render(<ProgressScene colors={palettes.light} t={createTranslator('en')} active reduced={false} ios />);
    expect(screen.getByText('Translating page 18 of 74')).toBeOnTheScreen();
    expect(screen.getByTestId('progress-demo-card')).toHaveStyle({ height: 250 });
    expect(screen.getByTestId('progress-saved-result').props.style).not.toEqual(expect.objectContaining({ position: 'absolute', bottom: -14, right: -30 }));
    expect(screen.queryByTestId('progress-platform-limit')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Pause' }));
    expect(screen.getByRole('button', { name: 'Resume' })).toBeOnTheScreen();
    expect(screen.getAllByText('Paused')).toHaveLength(2);
    fireEvent.press(screen.getByRole('button', { name: 'Resume' }));
    expect(screen.getByRole('button', { name: 'Pause' })).toBeOnTheScreen();
    expect(screen.getByText('Translating page 18 of 74')).toBeOnTheScreen();
    expect(animationMocks.withRepeat).toHaveBeenCalledTimes(2);
  });

  it('keeps the illustrative job static when reduced motion is active', () => {
    animationMocks.cancelAnimation.mockClear();
    animationMocks.withRepeat.mockClear();
    render(<ProgressScene colors={palettes.light} t={createTranslator('en')} active reduced ios={false} />);
    expect(animationMocks.withRepeat).not.toHaveBeenCalled();
    expect(animationMocks.cancelAnimation).toHaveBeenCalled();
    expect(screen.queryByTestId('progress-platform-limit')).toBeNull();
  });

  it.each([
    [false, 'Android shows a foreground notification'],
    [true, 'iOS may pause longer work'],
  ])('keeps the %s background explanation in the page copy, outside the scaled illustration', (ios, expectedCopy) => {
    render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={createTranslator('en')} initialState={{ version: ONBOARDING_VERSION, page: 3, done: false }} persist={jest.fn().mockResolvedValue(undefined)} onComplete={jest.fn()} ios={ios} /></SafeAreaProvider>);
    expect(screen.getByText(new RegExp(expectedCopy))).toBeOnTheScreen();
    expect(within(screen.getByTestId('onboarding-example-scene')).queryByTestId('progress-platform-limit')).toBeNull();
    expect(screen.getByTestId('progress-platform-limit').props.style).toEqual(expect.objectContaining({ marginTop: 8, fontSize: 12 }));
  });

  it('reserves the scaled scene height once and offsets the full-size artwork inside it', () => {
    const reactNative = require('react-native') as typeof import('react-native');
    const dimensions = jest.spyOn(reactNative, 'useWindowDimensions').mockReturnValue({ width: 360, height: 260, scale: 1, fontScale: 1 });
    render(<SafeAreaProvider><OnboardingScreen colors={palettes.light} t={createTranslator('en')} initialState={{ version: ONBOARDING_VERSION, page: 3, done: false }} persist={jest.fn().mockResolvedValue(undefined)} onComplete={jest.fn()} ios={false} /></SafeAreaProvider>);
    expect(screen.getByTestId('onboarding-example-scene')).toHaveStyle({ height: 148.5 });
    expect(screen.getByTestId('onboarding-scene-scale-wrapper')).toHaveStyle({ height: 330, top: -90.75, transform: [{ scale: 0.45 }] });
    dimensions.mockRestore();
  });

  it('persists completed onboarding after the last scene', async () => {
    const { persist, onComplete } = renderOnboarding();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent.press(screen.getByRole('button', { name: 'Done' }));

    await waitFor(() => expect(persist).toHaveBeenLastCalledWith({ version: ONBOARDING_VERSION, page: 3, done: true }));
    expect(persist.mock.calls.every(([state]) => state.page >= 0 && state.page < 4)).toBe(true);
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('keeps onboarding open and shows a retry message when persistence fails', async () => {
    const persist = jest.fn().mockRejectedValue(new Error('Local storage is unavailable.'));
    const { onComplete } = renderOnboarding(persist);
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
      await Promise.resolve();
    });

    expect(screen.getByText('Could not save the introduction. Try again.')).toBeOnTheScreen();
    expect(onComplete).not.toHaveBeenCalled();
    expect(screen.getByText('Read the manual in your language')).toBeOnTheScreen();
  });
});

describe('English–Russian comparison illustration', () => {
  it('renders one fixed English underlay and one clipped Russian layer on a narrow phone', () => {
    const reactNative = require('react-native') as typeof import('react-native');
    const dimensions = jest.spyOn(reactNative, 'useWindowDimensions').mockReturnValue({ width: 320, height: 700, scale: 1, fontScale: 1 });
    render(<TranslationScene colors={palettes.light} t={createTranslator('en')} active reduced />);

    expect(screen.getByTestId('translation-scene')).toHaveStyle({ width: 262 });
    const russianWindowStyles = screen.getByTestId('comparison-russian-window').props.style as Array<Record<string, unknown>>;
    expect(parseFloat(String(russianWindowStyles[1].width))).toBeCloseTo(55);
    expect(screen.getByTestId('comparison-russian-canvas')).toHaveStyle({ width: 238, height: 72 });
    expect(screen.getByTestId('comparison-english-canvas')).toHaveStyle({ width: 238, height: 72 });
    expect(screen.getAllByText(/Check when cold/)).toHaveLength(1);
    expect(screen.queryByTestId('comparison-english-window')).toBeNull();
    expect(screen.getAllByText(/Проверяйте на холодную/)).toHaveLength(1);
    dimensions.mockRestore();
  });
});
