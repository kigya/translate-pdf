jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { AppState, BackHandler } from 'react-native';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react-native-legacy';

const mockSharedValues: Array<{ value: number }> = [];
const mockStyleWorklets: Array<() => unknown> = [];
const mockRepeatCalls: Array<{ animation: unknown; iterations: number; reverse: boolean }> = [];
const mockTimingCalls: Array<{ to: number; options: Record<string, unknown> }> = [];
const mockStateListeners: Array<(state: string) => void> = [];
const mockStateRemovals: jest.Mock[] = [];
const mockRafCallbacks: Array<(time: number) => void> = [];
let mockReducedMotion: boolean | null = false;
let mockAppStateSpy: jest.SpyInstance;

jest.mock('react-native-reanimated', () => {
  const ReactModule = require('react');
  const ReactNative = require('react-native');
  const AnimatedView = ReactModule.forwardRef((props: object, ref: object) => ReactModule.createElement(ReactNative.View, { ...props, ref }));
  const mockEasing = { linear: (value: number) => value, bezier: (..._values: number[]) => (value: number) => value, sin: (value: number) => value, inOut: (easing: unknown) => easing };
  const mockFadeIn = { duration: () => undefined };
  return {
    __esModule: true,
    default: { View: AnimatedView, Text: ReactNative.Text, ScrollView: ReactNative.ScrollView, Image: ReactNative.Image, createAnimatedComponent: (component: unknown) => component, FadeIn: mockFadeIn },
    View: AnimatedView,
    FadeIn: mockFadeIn,
    Easing: mockEasing,
    ReduceMotion: { Always: 'always', Never: 'never' },
    cancelAnimation: jest.fn(),
    useReducedMotion: jest.fn(() => mockReducedMotion),
    useSharedValue: (initial: number) => {
      const ref = ReactModule.useRef(null);
      if (!ref.current) {
        ref.current = { value: initial, get() { return this.value; }, set(next: number) { this.value = next; } };
        mockSharedValues.push(ref.current);
      }
      return ref.current;
    },
    useAnimatedStyle: (worklet: () => unknown) => {
      mockStyleWorklets.push(worklet);
      return worklet();
    },
    useAnimatedProps: (worklet: () => unknown) => worklet(),
    useDerivedValue: (worklet: () => unknown) => ({ get: worklet }),
    Extrapolation: { CLAMP: 'clamp' },
    useAnimatedRef: () => ReactModule.useRef(null),
    useAnimatedScrollHandler: () => jest.fn(),
    useFrameCallback: () => ({ setActive: jest.fn() }),
    withTiming: (to: number, options: Record<string, unknown>) => {
      mockTimingCalls.push({ to, options });
      return to;
    },
    withSpring: (to: number) => to,
    withSequence: (...values: unknown[]) => values.at(-1),
    withDelay: (_delay: number, value: unknown) => value,
    withRepeat: (animation: unknown, iterations: number, reverse: boolean) => {
      mockRepeatCalls.push({ animation, iterations, reverse });
      return animation;
    },
    interpolate: (value: number, input: number[], output: number[]) => {
      const ratio = Math.max(0, Math.min(1, (value - input[0]) / (input[1] - input[0])));
      return output[0] + (output[1] - output[0]) * ratio;
    },
  };
});

jest.mock('react-native-svg', () => {
  const ReactModule = require('react');
  const ReactNative = require('react-native');
  const Svg = (props: object) => ReactModule.createElement(ReactNative.View, props);
  const Shape = (props: object) => ReactModule.createElement(ReactNative.View, props);
  return { __esModule: true, default: Svg, Svg, Path: Shape, Circle: Shape, Rect: Shape, G: Shape };
});

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
}));

import { ModelScene, ProgressScene, TranslationScene } from '../src/onboarding/OnboardingScenes';
import { GrainDrift } from '../src/components/kinetik/grain-drift';
import { ONBOARDING_VERSION, OnboardingScreen } from '../src/onboarding/OnboardingScreen';
import { createTranslator } from '../src/i18n/messages';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { AnimatedNumber, AnimatedTabBar, AppIcon, Balloons, DownloadSculpture } from '../src/ui/Motion';
import { SplashScreen } from '../src/ui/SplashScreen';
import { kinetikBrand, palettes } from '../src/ui/theme';

function renderBrandedSplash(onFirstFrame: () => void) {
  return render(
    <KinetikProvider scheme="light" colors={kinetikBrand.light} reduceMotion="never" haptics={false}>
      <SplashScreen onFirstFrame={onFirstFrame} />
    </KinetikProvider>,
  );
}

function layoutOnboarding() {
  fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 700 } } });
}

function activateNextAndSettle(targetPage: number) {
  fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  const pager = screen.UNSAFE_getByProps({ pagingEnabled: true });
  fireEvent(pager, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: targetPage * 360, y: 0 } } });
}

const reanimated = jest.requireMock('react-native-reanimated') as {
  cancelAnimation: jest.Mock;
  useReducedMotion: jest.Mock;
  Easing: { linear: (value: number) => number };
};

beforeAll(() => {
  mockAppStateSpy = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    mockStateListeners.push(listener as (state: string) => void);
    const remove = jest.fn();
    mockStateRemovals.push(remove);
    return { remove };
  });
});

beforeEach(() => {
  mockSharedValues.length = 0;
  mockStyleWorklets.length = 0;
  mockRepeatCalls.length = 0;
  mockTimingCalls.length = 0;
  mockStateListeners.length = 0;
  mockStateRemovals.length = 0;
  mockReducedMotion = false;
  reanimated.cancelAnimation.mockClear();
});

afterAll(() => {
  cleanup();
  mockAppStateSpy.mockRestore();
});

it('starts icon motion while React Native has not yet supplied the foreground state', () => {
  const previous = AppState.currentState;
  AppState.currentState = null as unknown as typeof AppState.currentState;
  try {
    const view = render(<AppIcon active reduced={false} />);
    expect(screen.getByRole('image', { name: 'Folivela' })).toBeOnTheScreen();
    expect(mockRepeatCalls.length).toBeGreaterThan(0);
    view.unmount();
  } finally { AppState.currentState = previous; }
});

describe('onboarding illustration placement and motion', () => {
  it('centers the visible asymmetric progress illustration around the canvas midpoint', () => {
    render(<ProgressScene colors={palettes.light} active={false} reduced />);
    const canvas = screen.getByTestId('progress-scene');
    const track = screen.getByTestId('progress-track');
    const sourcePage = screen.getByTestId('progress-source-page');
    const canvasWidth = canvas.props.style.width as number;
    const left = sourcePage.props.style.left as number;
    const width = sourcePage.props.style.width as number;
    const trackLeft = track.props.style.left as number;
    const trackRight = track.props.style.right as number;
    const visibleMin = Math.min(left, trackLeft);
    const visibleMax = Math.max(left + width, canvasWidth - trackRight);

    expect(visibleMin).toBe(28);
    expect(visibleMax).toBe(canvasWidth - 28);
    expect((visibleMin + visibleMax) / 2).toBe(canvasWidth / 2);
  });

  it('renders the accessible onboarding title at a wide screen size', () => {
    const reactNative = require('react-native') as typeof import('react-native');
    const dimensions = jest.spyOn(reactNative, 'useWindowDimensions').mockReturnValue({ width: 430, height: 900, scale: 1, fontScale: 1 });
    render(<OnboardingScreen
      colors={palettes.light}
      t={createTranslator('en')}
      initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }}
      persist={jest.fn().mockResolvedValue(undefined)}
      onComplete={jest.fn()}
      ios={false}
    />);
    layoutOnboarding();
    expect(screen.getByTestId('onboarding-root')).toBeOnTheScreen();
    expect(screen.getByLabelText(createTranslator('en')('intro1Title'), { includeHiddenElements: true })).toBeOnTheScreen();
    dimensions.mockRestore();
  });

  it('centers the orbit bead on its path rather than the scene wrapper', () => {
    render(<ModelScene colors={palettes.light} active={false} reduced />);
    const orbit = screen.getByTestId('model-orbit');
    const bead = screen.getByTestId('model-orbit-bead');
    const orbitWidth = orbit.props.style[0].width as number;
    const beadLeft = Number.parseFloat(bead.props.style.left) / 100 * orbitWidth;
    const beadCenter = beadLeft + (bead.props.style.width as number) / 2;
    expect(beadCenter).toBeCloseTo(orbitWidth / 2, 0);
  });

  it('keeps the reduced-motion scene static and starts an oscillating scene only when active', () => {
    const staticScene = render(<TranslationScene colors={palettes.light} active reduced />);
    expect(mockRepeatCalls).toHaveLength(0);
    staticScene.unmount();

    const animatedScene = render(<ModelScene colors={palettes.light} active reduced={false} />);
    expect(mockRepeatCalls).toHaveLength(1);
    expect(mockRepeatCalls.every(call => call.iterations === -1 && call.reverse)).toBe(true);
    animatedScene.unmount();
    expect(reanimated.cancelAnimation).toHaveBeenCalled();
  });
});

describe('onboarding boundary behavior', () => {
  it('uses the active-state and motion defaults when system state is unavailable', () => {
    mockReducedMotion = null;
    const originalState = AppState.currentState;
    Object.defineProperty(AppState, 'currentState', { configurable: true, writable: true, value: null });
    const onboarding = render(<OnboardingScreen
      colors={palettes.light}
      t={createTranslator('en')}
      initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }}
      persist={jest.fn().mockResolvedValue(undefined)}
      onComplete={jest.fn()}
      ios={false}
    />);
    layoutOnboarding();
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeTruthy();
    onboarding.unmount();
    Object.defineProperty(AppState, 'currentState', { configurable: true, writable: true, value: originalState });
  });

  it('returns system Back to Android during first-run introduction and releases its listener', () => {
    let onBack: Parameters<typeof BackHandler.addEventListener>[1] | undefined;
    const remove = jest.fn();
    const listener = jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, callback) => {
      onBack = callback;
      return { remove };
    });
    const onboarding = render(<OnboardingScreen
      colors={palettes.light}
      t={createTranslator('en')}
      initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }}
      persist={jest.fn().mockResolvedValue(undefined)}
      onComplete={jest.fn()}
      ios={false}
    />);
    expect(onBack?.({} as never)).toBe(false);
    onboarding.unmount();
    expect(remove).toHaveBeenCalledTimes(1);
    listener.mockRestore();
  });

  it('reports a rejected final save and does not mark first-run onboarding complete', async () => {
    const persist = jest.fn().mockRejectedValue(new Error('storage unavailable'));
    const onComplete = jest.fn();
    render(<OnboardingScreen
      colors={palettes.light}
      t={createTranslator('en')}
      initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }}
      persist={persist}
      onComplete={onComplete}
      ios={false}
    />);
    layoutOnboarding();
    activateNextAndSettle(1);
    activateNextAndSettle(2);
    expect(screen.getByRole('button', { name: 'Next' })).toBeOnTheScreen();
    await act(async () => {
      fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
      await Promise.resolve();
    });
    expect(persist).toHaveBeenCalledWith({ version: ONBOARDING_VERSION, page: 2, done: true });
    expect(screen.getByText('Could not save the introduction. Try again.')).toBeTruthy();
    expect(onComplete).not.toHaveBeenCalled();
  });

  it('closes a replay from the final step without writing completion state', async () => {
    const persist = jest.fn();
    const onExit = jest.fn();
    const onboarding = render(<OnboardingScreen
      colors={palettes.light}
      t={createTranslator('en')}
      initialState={{ version: ONBOARDING_VERSION, page: 2, done: true }}
      persist={persist}
      onComplete={jest.fn()}
      ios={false}
      replay
      onExit={onExit}
    />);
    layoutOnboarding();
    activateNextAndSettle(1);
    activateNextAndSettle(2);
    expect(screen.getByRole('button', { name: 'Next' })).toBeTruthy();
    await act(async () => { fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } }); await Promise.resolve(); });
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(persist).not.toHaveBeenCalled();
    onboarding.unmount();
  });

  it('keeps short scroll offsets on the current page and uses a compact title on narrow phones', () => {
    const reactNative = require('react-native') as typeof import('react-native');
    const dimensions = jest.spyOn(reactNative, 'useWindowDimensions').mockReturnValue({ width: 320, height: 800, scale: 1, fontScale: 1 });
    const onboarding = render(<OnboardingScreen
      colors={palettes.light}
      t={createTranslator('en')}
      initialState={{ version: ONBOARDING_VERSION, page: 0, done: false }}
      persist={jest.fn().mockResolvedValue(undefined)}
      onComplete={jest.fn()}
      ios={false}
    />);
    layoutOnboarding();
    expect(screen.getByLabelText(createTranslator('en')('intro1Title'), { includeHiddenElements: true })).toBeOnTheScreen();
    const carousel = screen.UNSAFE_getAllByType(require('react-native').ScrollView).at(-1)!;
    fireEvent(carousel, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: 30, y: 0 } } });
    expect(screen.getByLabelText(createTranslator('en')('intro1Title'), { includeHiddenElements: true })).toBeOnTheScreen();
    onboarding.unmount();
    dimensions.mockRestore();
  });
});

describe('motion primitives', () => {
  it('draws and moves the branded document when active, and stops both effects for reduced motion', () => {
    const icon = render(<AppIcon size={72} active draw reduced={false} background="#C62828" />);
    expect(mockTimingCalls).toContainEqual({ to: 1, options: { duration: 850 } });
    expect(mockRepeatCalls).toContainEqual({ animation: 1, iterations: -1, reverse: false });
    icon.unmount();
    expect(reanimated.cancelAnimation).toHaveBeenCalled();

    mockReducedMotion = true;
    mockTimingCalls.length = 0;
    mockRepeatCalls.length = 0;
    render(<AppIcon active reduced={undefined} draw />);
    expect(mockTimingCalls).toHaveLength(0);
    expect(mockRepeatCalls).toHaveLength(0);
  });

  it('uses the system motion preference when an explicit preference is absent', () => {
    mockReducedMotion = null;
    const icon = render(<AppIcon active draw />);
    expect(mockRepeatCalls).toHaveLength(1);
    expect(mockTimingCalls).toContainEqual({ to: 1, options: { duration: 850 } });
    icon.unmount();
  });

  it('supports default brand-mark props in an inactive context', () => {
    const icon = render(<AppIcon />);
    expect(screen.getByTestId('app-motion-icon')).toHaveStyle({ width: 56, height: 56, backgroundColor: 'transparent' });
    icon.unmount();
  });

  it('renders animated tabs with RTL positioning and commits the selected state without motion when reduced', () => {
    const onChange = jest.fn();
    mockReducedMotion = true;
    const tabs = render(<AnimatedTabBar active="library" onChange={onChange} direction="rtl" labels={{ library: 'Library', settings: 'Settings' }} bottomInset={20} />);
    const bar = screen.getByTestId('animated-tab-bar');
    expect(bar.props.style.paddingBottom).toBe(20);
    expect(bar.props.style.direction).toBe('rtl');
    expect(screen.getByTestId('tab-indicator-track')).toHaveStyle({ direction: 'ltr' });
    expect(screen.getByRole('tab', { name: 'Library' })).toHaveProp('accessibilityState', { selected: true });
    expect(screen.getByRole('tab', { name: 'Settings' })).toHaveProp('accessibilityState', { selected: false });
    fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
    expect(onChange).toHaveBeenCalledWith('settings');
    expect(mockTimingCalls).toHaveLength(0);
    tabs.unmount();
  });

  it('animates the tab selection and indicator when motion is allowed in LTR', () => {
    mockReducedMotion = false;
    const tabs = render(<AnimatedTabBar active="settings" onChange={jest.fn()} direction="ltr" />);
    expect(mockTimingCalls).toContainEqual({ to: 1, options: { duration: 260 } });
    expect(mockTimingCalls).toContainEqual({ to: 1, options: { duration: 220 } });
    expect(screen.getByRole('tab', { name: 'Library' })).toHaveProp('accessibilityState', { selected: false });
    expect(screen.getByRole('tab', { name: 'Settings' })).toHaveProp('accessibilityState', { selected: true });
    tabs.unmount();
  });

  it('falls back to the system preference for tab glyphs and the indicator', () => {
    mockReducedMotion = null;
    render(<AnimatedTabBar active="library" onChange={jest.fn()} />);
    expect(mockTimingCalls).toContainEqual({ to: 0, options: { duration: 260 } });
    expect(mockTimingCalls).toContainEqual({ to: 1, options: { duration: 220 } });
  });

  it('places active tabs correctly for LTR Library and RTL Settings', () => {
    for (const [active, direction, left] of [['library', 'ltr', '12.5%'], ['settings', 'rtl', '12.5%'], ['settings', 'ltr', '62.5%'], ['library', 'rtl', '62.5%']] as const) {
      const tabs = render(<AnimatedTabBar active={active} direction={direction} onChange={jest.fn()} />);
      expect(screen.getByTestId('tab-indicator').props.style[1].left).toBe(left);
      expect(screen.getByTestId('tab-indicator-track')).toHaveStyle({ direction: 'ltr' });
      tabs.unmount();
    }
  });

  it('shows final values immediately when inactive or reduced and animates decimal values when active', () => {
    mockReducedMotion = null;
    const systemFallback = render(<AnimatedNumber value={7} active={false} />);
    expect(systemFallback.getByText('7')).toBeTruthy();
    systemFallback.unmount();

    const reduced = render(<AnimatedNumber value={85} suffix="%" reduced />);
    expect(reduced.getByText('85%')).toBeTruthy();
    reduced.unmount();

    const request = jest.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(callback => {
      mockRafCallbacks.push(callback);
      return mockRafCallbacks.length;
    });
    const cancel = jest.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(() => undefined);
    const now = jest.spyOn(Date, 'now').mockReturnValue(1000);
    mockRafCallbacks.length = 0;
    const number = render(<AnimatedNumber value={4.5} decimals={1} suffix=" GB" reduced={false} />);
    expect(number.getByText('0.0 GB')).toBeTruthy();
    now.mockReturnValue(1340);
    act(() => mockRafCallbacks[0](1340));
    expect(number.getByText('3.9 GB')).toBeTruthy();
    now.mockReturnValue(1680);
    act(() => mockRafCallbacks[1](1680));
    expect(number.getByText('4.5 GB')).toBeTruthy();
    number.unmount();

    const pendingNumber = render(<AnimatedNumber value={40} reduced={false} />);
    const pendingFrameId = mockRafCallbacks.length;
    pendingNumber.unmount();
    expect(cancel).toHaveBeenCalledWith(pendingFrameId);

    const inactive = render(<AnimatedNumber value={12} active={false} reduced={false} />);
    expect(inactive.getByText('12')).toBeTruthy();
    inactive.unmount();
    request.mockRestore();
    cancel.mockRestore();
    now.mockRestore();
  });

  it('clamps download progress at zero and uses the system Reduce Motion fallback', () => {
    mockReducedMotion = null;
    render(<DownloadSculpture progress={-0.5} />);
    expect(screen.getByTestId('download-sculpture')).toHaveProp('accessibilityValue', { min: 0, max: 100, now: 0 });
    expect(mockRepeatCalls).toHaveLength(1);
  });

  it('celebrates each ready event once, refreshes its callback, and finishes immediately with reduced motion', () => {
    jest.useFakeTimers();
    const original = jest.fn();
    const balloons = render(<Balloons active onComplete={original} reduced={false} />);
    const latest = jest.fn();
    balloons.rerender(<Balloons active onComplete={latest} reduced={false} />);
    act(() => jest.advanceTimersByTime(1050));
    expect(original).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledTimes(1);
    balloons.rerender(<Balloons active={false} onComplete={latest} reduced={false} />);
    balloons.rerender(<Balloons active onComplete={latest} reduced={false} />);
    act(() => jest.advanceTimersByTime(1050));
    expect(latest).toHaveBeenCalledTimes(2);
    balloons.unmount();
    jest.useRealTimers();

    mockReducedMotion = true;
    mockTimingCalls.length = 0;
    const reducedCompletion = jest.fn();
    const reducedBalloons = render(<Balloons active onComplete={reducedCompletion} />);
    expect(reducedCompletion).toHaveBeenCalledTimes(1);
    expect(mockTimingCalls.some(call => call.to === 1 && call.options.duration === 1050)).toBe(false);
    reducedBalloons.unmount();
  });

  it('does not celebrate while inactive, and rearms once after returning to the foreground', () => {
    jest.useFakeTimers();
    const onComplete = jest.fn();
    const balloons = render(<Balloons onComplete={onComplete} reduced={false} />);
    expect(onComplete).not.toHaveBeenCalled();
    balloons.rerender(<Balloons active onComplete={onComplete} reduced={false} />);
    const stateListener = mockStateListeners.at(-1);
    expect(stateListener).toBeDefined();
    act(() => stateListener?.('background'));
    act(() => stateListener?.('active'));
    act(() => jest.advanceTimersByTime(1050));
    expect(onComplete).toHaveBeenCalledTimes(1);
    balloons.unmount();
    jest.useRealTimers();
  });

  it('does not start a balloon animation when Reduce Motion is unavailable and inactive is explicit', () => {
    mockReducedMotion = null;
    const balloons = render(<Balloons active={false} />);
    expect(mockTimingCalls).toHaveLength(0);
    balloons.rerender(<Balloons active reduced />);
    expect(mockTimingCalls).toHaveLength(0);
  });

  it('completes the current one-shot celebration if Reduce Motion is enabled mid-animation', () => {
    jest.useFakeTimers();
    const onComplete = jest.fn();
    const timeoutSpy = jest.spyOn(globalThis, 'setTimeout');
    const balloons = render(<Balloons active onComplete={onComplete} reduced={false} />);
    const scheduledCallback = timeoutSpy.mock.calls.find(([, delay]) => delay === 1050)?.[0] as (() => void) | undefined;
    expect(scheduledCallback).toBeDefined();
    balloons.rerender(<Balloons active onComplete={onComplete} reduced />);
    expect(onComplete).toHaveBeenCalledTimes(1);
    scheduledCallback?.(); // A timer already queued as Reduce Motion changes must remain one-shot.
    expect(onComplete).toHaveBeenCalledTimes(1);
    act(() => jest.advanceTimersByTime(1050));
    expect(onComplete).toHaveBeenCalledTimes(1);
    balloons.unmount();
    timeoutSpy.mockRestore();
    jest.useRealTimers();
  });

  it('keeps a completed reduced-motion celebration one-shot if the preference changes back', () => {
    const onComplete = jest.fn();
    const balloons = render(<Balloons active onComplete={onComplete} reduced />);
    balloons.rerender(<Balloons active onComplete={onComplete} reduced={false} />);
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(mockTimingCalls).toHaveLength(0);
    balloons.unmount();
  });
});

describe('download sculpture cycle continuity', () => {
  it('uses a linear 2π cycle and returns the liquid silhouette to the same first/last frame', () => {
    render(<DownloadSculpture active reduced={false} progress={0.5} />);
    expect(mockTimingCalls[0]).toEqual({ to: Math.PI * 2, options: { duration: 5200, easing: reanimated.Easing.linear } });
    expect(mockRepeatCalls[0]).toEqual({ animation: Math.PI * 2, iterations: -1, reverse: false });

    const phase = mockSharedValues[0];
    const silhouette = mockStyleWorklets[0] as () => { transform: Array<Record<string, unknown>> };
    phase.value = 0;
    const firstFrame = silhouette();
    phase.value = Math.PI * 2;
    const lastFrame = silhouette();
    expect(Number.parseFloat(String(lastFrame.transform[0].rotate))).toBeCloseTo(Number.parseFloat(String(firstFrame.transform[0].rotate)), 8);
    for (let index = 1; index < lastFrame.transform.length; index += 1) {
      const key = Object.keys(lastFrame.transform[index])[0];
      expect(lastFrame.transform[index][key]).toBeCloseTo(firstFrame.transform[index][key] as number, 8);
    }
    expect(screen.getByTestId('download-sculpture')).toHaveProp('accessibilityValue', { min: 0, max: 100, now: 50 });
  });

  it('cancels while reduced, inactive, or backgrounded and removes its app-state listener', () => {
    mockReducedMotion = true;
    const reduced = render(<DownloadSculpture active reduced={undefined} />);
    expect(mockRepeatCalls).toHaveLength(0);
    reduced.unmount();

    mockReducedMotion = false;
    const sculpture = render(<DownloadSculpture active reduced={false} />);
    expect(mockRepeatCalls).toHaveLength(1);
    const listener = mockStateListeners[0];
    act(() => { listener('background'); });
    expect(reanimated.cancelAnimation).toHaveBeenCalled();
    expect(mockRepeatCalls).toHaveLength(1);
    sculpture.unmount();
    expect(mockStateRemovals[1]).toHaveBeenCalledTimes(1);

    const inactive = render(<DownloadSculpture active={false} reduced={false} />);
    expect(mockRepeatCalls).toHaveLength(1);
    inactive.unmount();
  });
});

describe('branded startup handoff', () => {
  let frameCallbacks: Array<(time: number) => void>;
  let frameIds: number[];
  let requestFrame: jest.SpyInstance;
  let cancelFrame: jest.SpyInstance;

  beforeEach(() => {
    frameCallbacks = [];
    frameIds = [];
    requestFrame = jest.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(callback => {
      frameCallbacks.push(callback);
      const id = frameCallbacks.length;
      frameIds.push(id);
      return id;
    });
    cancelFrame = jest.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(() => undefined);
  });

  afterEach(() => {
    requestFrame.mockRestore();
    cancelFrame.mockRestore();
    mockReducedMotion = false;
  });

  it('starts its visible clock after two scheduled frames, only once per mounted splash', () => {
    const onFirstFrame = jest.fn();
    renderBrandedSplash(onFirstFrame);
    const splash = screen.getByTestId('custom-splash-screen');
    expect(splash.props.style.backgroundColor).toBe('#C62828');
    const grain = screen.UNSAFE_getByType(GrainDrift);
    expect(grain.props).toMatchObject({ grain: 0, speed: 3, active: (AppState.currentState ?? 'active') === 'active', reduceEffects: false });
    expect(grain.props.colors[0]).toBe(splash.props.style.backgroundColor);
    const splashStateListener = mockStateListeners.at(-1);
    expect(splashStateListener).toBeDefined();
    act(() => splashStateListener?.('background'));
    expect(screen.UNSAFE_getByType(GrainDrift).props.active).toBe(false);
    act(() => splashStateListener?.('active'));
    expect(screen.UNSAFE_getByType(GrainDrift).props.active).toBe(true);

    act(() => {
      splash.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 800 } } });
      splash.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 800 } } });
    });
    expect(requestFrame).toHaveBeenCalledTimes(1);
    expect(onFirstFrame).not.toHaveBeenCalled();
    act(() => frameCallbacks[0](0));
    expect(requestFrame).toHaveBeenCalledTimes(2);
    expect(onFirstFrame).not.toHaveBeenCalled();
    act(() => frameCallbacks[1](16));
    expect(onFirstFrame).toHaveBeenCalledTimes(1);
    act(() => splash.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 800 } } }));
    expect(onFirstFrame).toHaveBeenCalledTimes(1);
  });

  it('cancels pending frame callbacks on unmount and uses static icon motion when reduced motion is enabled', () => {
    mockReducedMotion = true;
    const splash = renderBrandedSplash(jest.fn());
    const icon = screen.getByTestId('folivela-splash-mark');
    expect(icon.parent?.props).toBeDefined();
    expect(mockRepeatCalls).toHaveLength(0);
    expect(screen.UNSAFE_getByType(GrainDrift).props).toMatchObject({ grain: 0, speed: 3, reduceEffects: true });

    const root = screen.getByTestId('custom-splash-screen');
    act(() => root.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 800 } } }));
    expect(frameIds).toEqual([1]);
    splash.unmount();
    expect(cancelFrame).toHaveBeenCalledWith(1);
  });

  it('uses the system motion preference fallback when the preference is unavailable', () => {
    mockReducedMotion = null;
    const splash = renderBrandedSplash(jest.fn());
    act(() => mockStateListeners.at(-1)?.('active'));
    expect(mockRepeatCalls.length).toBeGreaterThan(0);
    splash.unmount();
  });
});
