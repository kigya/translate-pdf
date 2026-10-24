import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AppState, StyleSheet, Text, View } from 'react-native';

const mockPathFallback = { current: false };
const mockDerivedFactories: Array<() => unknown> = [];
const mockFrames: Array<{ callback: (info: { timeSincePreviousFrame?: number }) => void; active: boolean }> = [];
let mockPixelRects = 0;

jest.mock('expo-blur', () => {
  const ReactRuntime = require('react');
  const { View: NativeView } = require('react-native');
  return {
    BlurTargetView: ({ children, ...props }: { children?: React.ReactNode }) => ReactRuntime.createElement(NativeView, props, children),
    BlurView: (props: object) => ReactRuntime.createElement(NativeView, { ...props, testID: 'blur-view' }),
  };
});

jest.mock('react-native-gesture-handler', () => {
  const ReactRuntime = require('react');
  const { View: NativeView } = require('react-native');
  const createGesture = () => {
    const gesture: { id?: string; handlers: Record<string, (...args: any[]) => void>; [key: string]: any } = { handlers: {} };
    for (const method of ['withTestId', 'enabled', 'activeOffsetY', 'failOffsetX']) {
      gesture[method] = (...args: unknown[]) => {
        if (method === 'withTestId') gesture.id = String(args[0]);
        return gesture;
      };
    }
    for (const method of ['onUpdate', 'onEnd']) {
      gesture[method] = (callback: (...args: any[]) => void) => {
        gesture.handlers[method] = callback;
        return gesture;
      };
    }
    return gesture;
  };
  return {
    Gesture: { Pan: createGesture },
    GestureDetector: ({ gesture, children }: { gesture: typeof createGesture extends () => infer T ? T : never; children: React.ReactNode }) =>
      ReactRuntime.createElement(NativeView, {
        testID: `gesture-${gesture.id ?? 'untagged'}`,
        onTouchMove: (event: { nativeEvent?: unknown }) => gesture.handlers.onUpdate?.(event.nativeEvent ?? event),
        onTouchEnd: (event: { nativeEvent?: unknown }) => gesture.handlers.onEnd?.(event.nativeEvent ?? event),
      }, children),
  };
});

jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  const identity = (value: unknown) => value;
  const useSharedValue = (initial: unknown) => {
    const ref = ReactRuntime.useRef(undefined);
    if (ref.current === undefined) ref.current = { value: initial, get() { return this.value; }, set(next: unknown) { this.value = next; } };
    return ref.current;
  };
  const complete = (target: unknown, _config: unknown, callback?: (finished: boolean) => void) => {
    callback?.(true);
    return target;
  };
  return {
    __esModule: true,
    default: { View: RN.View, Text: RN.Text, Image: RN.Image, createAnimatedComponent: (component: unknown) => component },
    ReduceMotion: { Always: 'always', Never: 'never' },
    Easing: {
      linear: identity,
      bezier: () => identity,
      out: () => identity,
      cubic: identity,
      inOut: (value: unknown) => value,
      sin: identity,
    },
    useSharedValue,
    useAnimatedStyle: (factory: () => object) => factory(),
    useAnimatedProps: (factory: () => object) => factory(),
    useDerivedValue: (factory: () => unknown) => {
      // Reanimated evaluates worklet-backed derived values on the UI thread.
      // Evaluate once at creation here so path/grid calculations are exercised.
      factory();
      mockDerivedFactories.push(factory);
      return { get: factory };
    },
    useFrameCallback: (callback: (info: { timeSincePreviousFrame?: number }) => void) => {
      const frame = { callback, active: false };
      mockFrames.push(frame);
      return { setActive: (active: boolean) => { frame.active = active; } };
    },
    cancelAnimation: jest.fn(),
    withTiming: complete,
    withSpring: complete,
    withRepeat: (value: unknown) => value,
    withDelay: (_delay: number, value: unknown) => value,
    interpolate: (value: number, input: number[], output: number[]) => {
      const low = input[0] ?? 0;
      const high = input[1] ?? low + 1;
      const ratio = Math.max(0, Math.min(1, (value - low) / (high - low)));
      return (output[0] ?? 0) + ratio * ((output[1] ?? 0) - (output[0] ?? 0));
    },
    FadeInDown: { duration: () => ({ easing: () => undefined }) },
  };
});

jest.mock('@shopify/react-native-skia', () => {
  const ReactRuntime = require('react');
  const { View: NativeView } = require('react-native');
  const node = (name: string) => (props: object) => ReactRuntime.createElement(NativeView, { ...props, testID: name });
  const builder = () => {
    const path: Record<string, unknown> = {};
    for (const method of ['moveTo', 'lineTo', 'quadTo', 'close', 'addCircle', 'addRect']) {
      path[method] = () => {
        if (method === 'addRect') mockPixelRects += 1;
        return path;
      };
    }
    path.detach = () => ({ detached: true });
    return path;
  };
  return {
    Canvas: node('canvas'), Group: node('group'), Path: node('path'),
    Skia: {
      PathBuilder: { Make: builder },
      Path: {
        MakeFromSVGString: () => mockPathFallback.current ? null : { svg: true },
        Make: () => ({ fallback: true }),
      },
      XYWHRect: (...args: number[]) => ({ rect: args }),
    },
    vec: (x: number, y: number) => ({ x, y }),
  };
});

import { MorphSheet } from '../src/components/kinetik/morph-sheet';
import { ScanProgress } from '../src/components/kinetik/scan-progress';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { RingSpinner } from '../src/components/kinetik/ring-spinner';
import { useReduceMotion } from '../src/lib/kinetik/hooks/use-reduce-motion';

const wrap = (child: React.ReactNode, reduceMotion: 'always' | 'never' = 'never', scheme: 'light' | 'dark' = 'light') => {
  return <KinetikProvider scheme={scheme} reduceMotion={reduceMotion} haptics={false}>{child}</KinetikProvider>;
};

function MotionPreferenceProbe() {
  return <Text testID="motion-preference">{String(useReduceMotion())}</Text>;
}

function layoutHost(view: ReturnType<typeof render>, width = 390, height = 800) {
  const host = view.UNSAFE_getAllByType(View).find((node) => typeof node.props.onLayout === 'function');
  if (!host) throw new Error('Expected a measurable component host');
  fireEvent(host, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width, height } } });
}

function sheetNode(view: ReturnType<typeof render>) {
  return view.UNSAFE_getByProps({ accessibilityViewIsModal: true });
}

describe('U035 motion controls: MorphSheet', () => {
  it('opens and closes an accessible blurred sheet through its scrim', () => {
    const onOpenChange = jest.fn();
    const view = render(wrap(
      <MorphSheet open onOpenChange={onOpenChange} accessibilityLabel="Document actions" sheet={<Text>Action sheet content</Text>}>
        <Text>Library behind sheet</Text>
      </MorphSheet>, 'never', 'dark'));
    layoutHost(view);

    expect(screen.getByText('Library behind sheet', { includeHiddenElements: true })).toBeOnTheScreen();
    expect(screen.getByText('Action sheet content')).toBeOnTheScreen();
    expect(screen.getByTestId('blur-view')).toBeOnTheScreen();
    expect(view.getByTestId('blur-view').props.tint).toBe('dark');
    expect(screen.getByRole('button', { name: 'Close' })).toBeOnTheScreen();
    expect(sheetNode(view).props.accessibilityLabel).toBe('Document actions');
    expect(view.UNSAFE_getAllByType(View).some((node) => node.props.accessibilityElementsHidden === true)).toBe(true);
    fireEvent.press(screen.getByRole('button', { name: 'Close' }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('honors accessibility expand/collapse boundaries and close actions', () => {
    const onOpenChange = jest.fn();
    const onSnap = jest.fn();
    const view = render(wrap(
      <MorphSheet open initialSnap={0} snapPoints={[0.4, 0.9]} onOpenChange={onOpenChange} onSnap={onSnap} blur={false} sheet={<Text>Options</Text>}>
        <Text>Background</Text>
      </MorphSheet>,
    ));
    layoutHost(view);
    const sheet = sheetNode(view);

    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'expand' } });
    expect(onSnap).toHaveBeenLastCalledWith(1);
    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'expand' } });
    expect(onSnap).toHaveBeenCalledTimes(1);
    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'collapse' } });
    expect(onSnap).toHaveBeenLastCalledWith(0);
    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'collapse' } });
    expect(onSnap).toHaveBeenCalledTimes(2);
    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'close' } });
    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'escape' } });
    fireEvent(sheet, 'accessibilityAction', { nativeEvent: { actionName: 'unsupported' } });
    expect(onOpenChange).toHaveBeenNthCalledWith(1, false);
    expect(onOpenChange).toHaveBeenNthCalledWith(2, false);
  });

  it('rubber-bands an upward drag and snaps to the closest expanded stop', () => {
    const onOpenChange = jest.fn();
    const onSnap = jest.fn();
    const view = render(wrap(
      <MorphSheet open initialSnap={0} snapPoints={[0.45, 0.92]} onOpenChange={onOpenChange} onSnap={onSnap} blur={false} sheet={<Text>Sheet</Text>}>
        <Text>Content</Text>
      </MorphSheet>,
    ));
    layoutHost(view);
    const gesture = screen.getByTestId('gesture-morph-sheet');
    fireEvent(gesture, 'touchMove', { nativeEvent: { translationY: -500 } });
    fireEvent(gesture, 'touchEnd', { nativeEvent: { velocityY: 0 } });
    expect(onSnap).toHaveBeenCalledWith(1);
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('closes on a downward drag or a fast downward flick', () => {
    for (const scenario of ['drag', 'flick'] as const) {
      const onOpenChange = jest.fn();
      const view = render(wrap(
        <MorphSheet open initialSnap={scenario === 'flick' ? 1 : 0} onOpenChange={onOpenChange} blur={false} haptics={false} sheet={<Text>Sheet</Text>}>
          <Text>Content</Text>
        </MorphSheet>,
      ));
      layoutHost(view);
      const gesture = screen.getByTestId('gesture-morph-sheet');
      if (scenario === 'drag') fireEvent(gesture, 'touchMove', { nativeEvent: { translationY: 500 } });
      fireEvent(gesture, 'touchEnd', { nativeEvent: { velocityY: scenario === 'flick' ? 1501 : 0 } });
      expect(onOpenChange).toHaveBeenCalledWith(false);
      view.unmount();
    }
  });

  it('does not emit a snap event when a pan settles at its current stop', () => {
    const onSnap = jest.fn();
    const view = render(wrap(
      <MorphSheet open initialSnap={0} onOpenChange={jest.fn()} onSnap={onSnap} blur={false} sheet={<Text>Sheet</Text>}>
        <Text>Content</Text>
      </MorphSheet>,
    ));
    layoutHost(view);
    fireEvent(screen.getByTestId('gesture-morph-sheet'), 'touchEnd', { nativeEvent: { velocityY: 0 } });
    expect(onSnap).not.toHaveBeenCalled();
  });

  it('collapses safely when available snap points shrink after the sheet opens', () => {
    const onSnap = jest.fn();
    const onOpenChange = jest.fn();
    const view = render(wrap(<MorphSheet
      open
      initialSnap={2}
      snapPoints={[0.4, 0.65, 0.9]}
      onSnap={onSnap}
      onOpenChange={onOpenChange}
      blur={false}
      sheet={<Text>Dynamic snap sheet</Text>}
    ><Text>Content</Text></MorphSheet>));
    layoutHost(view);

    view.rerender(wrap(<MorphSheet
      open
      initialSnap={2}
      snapPoints={[0.5]}
      onSnap={onSnap}
      onOpenChange={onOpenChange}
      blur={false}
      sheet={<Text>Dynamic snap sheet</Text>}
    ><Text>Content</Text></MorphSheet>));
    fireEvent(sheetNode(view), 'accessibilityAction', { nativeEvent: { actionName: 'collapse' } });

    expect(onSnap).toHaveBeenCalledWith(1);
    expect(onOpenChange).not.toHaveBeenCalled();
    expect(sheetNode(view).props.accessibilityViewIsModal).toBe(true);
  });

  it('handles closed, zero-height, reduced-motion, and degenerate snap layouts', () => {
    const onOpenChange = jest.fn();
    const defaults = render(wrap(<MorphSheet open={false} onOpenChange={onOpenChange} sheet={<Text>Sheet</Text>}>
      <Text>Behind</Text>
    </MorphSheet>));
    layoutHost(defaults);
    expect(defaults.UNSAFE_getByProps({ accessibilityViewIsModal: false }).props.accessibilityLabel).toBeUndefined();
    expect(screen.getByRole('button', { name: 'Close' })).toBeOnTheScreen();
    defaults.unmount();

    const zeroHeight = render(wrap(<MorphSheet open onOpenChange={onOpenChange} snapPoints={[]} blur={false} sheet={<Text>Sheet</Text>}>
      <Text>Behind</Text>
    </MorphSheet>, 'always'));
    fireEvent(zeroHeight.UNSAFE_getAllByType(View).find((node) => node.props.onLayout)!, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 390, height: 0 } } });
    fireEvent(sheetNode(zeroHeight), 'accessibilityAction', { nativeEvent: { actionName: 'expand' } });
    fireEvent(sheetNode(zeroHeight), 'accessibilityAction', { nativeEvent: { actionName: 'collapse' } });
    fireEvent(sheetNode(zeroHeight), 'accessibilityAction', { nativeEvent: { actionName: 'escape' } });
    expect(onOpenChange).toHaveBeenCalledWith(false);
    zeroHeight.unmount();

    const oneStop = render(wrap(<MorphSheet open snapPoints={[0.5]} initialSnap={99} onOpenChange={onOpenChange} blur={false} sheet={<Text>Sheet</Text>}>
      <Text>Behind</Text>
    </MorphSheet>, 'always'));
    layoutHost(oneStop);
    fireEvent(sheetNode(oneStop), 'accessibilityAction', { nativeEvent: { actionName: 'expand' } });
    fireEvent(sheetNode(oneStop), 'accessibilityAction', { nativeEvent: { actionName: 'collapse' } });
    const closeCallsBeforePan = onOpenChange.mock.calls.length;
    fireEvent(screen.getByTestId('gesture-morph-sheet'), 'touchEnd', { nativeEvent: { velocityY: 0 } });
    expect(onOpenChange).toHaveBeenCalledTimes(closeCallsBeforePan);
    oneStop.unmount();

    const emptyStops = render(wrap(<MorphSheet open snapPoints={[]} onOpenChange={onOpenChange} blur={false} sheet={<Text>Sheet</Text>}>
      <Text>Behind</Text>
    </MorphSheet>));
    layoutHost(emptyStops);
    fireEvent(screen.getByTestId('gesture-morph-sheet'), 'touchMove', { nativeEvent: { translationY: -600 } });
    fireEvent(screen.getByTestId('gesture-morph-sheet'), 'touchEnd', { nativeEvent: { velocityY: 0 } });
    expect(onOpenChange).toHaveBeenCalledTimes(closeCallsBeforePan);
    expect(sheetNode(emptyStops).props.accessibilityViewIsModal).toBe(true);
    emptyStops.unmount();
  });
});

describe('U035 motion controls: ScanProgress', () => {
  const originalAppState = AppState.currentState;
  afterEach(() => {
    AppState.currentState = originalAppState;
    mockPathFallback.current = false;
    mockDerivedFactories.length = 0;
    mockFrames.length = 0;
    mockPixelRects = 0;
    jest.restoreAllMocks();
  });

  it('shows known progress over supplied media and stops motion when the app backgrounds', () => {
    AppState.currentState = 'active';
    const listeners: Array<(state: string) => void> = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      listeners.push(listener as (state: string) => void);
      return { remove: jest.fn() } as never;
    });
    const view = render(wrap(<ScanProgress
      source={{ uri: 'local-document-preview' }}
      progress={0.35}
      label="Reading document"
      imageLabel="Page preview"
      height={180}
      tint="#aa3344"
      active
    ><Text>Preview children</Text></ScanProgress>));

    expect(screen.getByRole('image', { name: 'Page preview' })).toBeOnTheScreen();
    expect(screen.getByText('Preview children')).toBeOnTheScreen();
    expect(screen.getByRole('progressbar', { name: 'Reading document' }).props.accessibilityValue).toEqual({ min: 0, max: 100, now: 35 });
    expect(view.UNSAFE_getByType(RingSpinner).props.active).toBe(true);
    const card = view.UNSAFE_getAllByType(View).find((node) => node.props.accessibilityLabel === 'Page preview');
    expect(card).toBeDefined();
    expect(view.UNSAFE_getAllByType(View).some(node => {
      const style = StyleSheet.flatten(node.props.style);
      return !!style && 'boxShadow' in style;
    })).toBe(true);
    act(() => card!.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 180, height: 180 } } }));
    expect(view.UNSAFE_getAllByProps({ testID: 'canvas' }).length).toBeGreaterThanOrEqual(2);
    const pill = view.UNSAFE_getAllByType(View).find((node) => node.props.accessibilityLabel === 'Reading document' && node.props.accessibilityRole === 'progressbar' && node.props.accessible === true);
    expect(pill).toBeDefined();
    fireEvent(screen.getByRole('progressbar', { name: 'Reading document' }), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 260, height: 60 } } });
    expect(pill!.props.accessibilityValue).toEqual({ min: 0, max: 100, now: 35 });
    expect(view.UNSAFE_getAllByProps({ testID: 'canvas' }).length).toBeGreaterThanOrEqual(3);

    act(() => listeners.forEach((listener) => listener('background')));
    expect(view.UNSAFE_getByType(RingSpinner).props.active).toBe(false);
    view.unmount();
  });

  it('uses a native determinate progress track without mounting Skia while heavy processing is active', () => {
    AppState.currentState = 'active';
    const view = render(wrap(<ScanProgress label="Translating a large document" imageLabel="Reference page" progress={0.25} active reduceEffects />));
    const card = view.UNSAFE_getAllByType(View).find(node => node.props.accessibilityLabel === 'Reference page');
    act(() => card!.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 180, height: 180 } } }));
    expect(mockPixelRects).toBe(0);
    expect(view.UNSAFE_queryAllByType(RingSpinner)).toHaveLength(0);
    expect(screen.getByRole('progressbar', { name: 'Translating a large document' }).props.accessibilityValue.now).toBe(25);
    expect(view.UNSAFE_getAllByType(View).some(node => {
      const style = StyleSheet.flatten(node.props.style);
      return !!style && 'boxShadow' in style;
    })).toBe(false);
    fireEvent(screen.getByRole('progressbar', { name: 'Translating a large document' }), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 260, height: 60 } } });
    expect(view.UNSAFE_getByProps({ testID: 'low-effects-progress-track' })).toBeDefined();
    expect(StyleSheet.flatten(view.UNSAFE_getByProps({ testID: 'low-effects-progress-fill' }).props.style).width).toBe('25%');
    expect(view.UNSAFE_queryAllByProps({ testID: 'canvas' })).toHaveLength(0);
    view.rerender(wrap(<ScanProgress label="Translating a large document" progress={1.25} active reduceEffects />));
    expect(screen.getByRole('progressbar', { name: 'Translating a large document' }).props.accessibilityValue).toEqual({ min: 0, max: 100, now: 100 });
    expect(StyleSheet.flatten(view.UNSAFE_getByProps({ testID: 'low-effects-progress-fill' }).props.style).width).toBe('100%');
    expect(view.UNSAFE_queryAllByProps({ testID: 'canvas' })).toHaveLength(0);
    for (const invalidProgress of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
      view.rerender(wrap(<ScanProgress label="Translating a large document" progress={invalidProgress} active reduceEffects />));
      expect(screen.getByRole('progressbar', { name: 'Translating a large document' }).props.accessibilityValue).toBeUndefined();
      expect(view.UNSAFE_queryByProps({ testID: 'low-effects-progress-fill' })).toBeNull();
    }
    view.unmount();
  });

  it('keeps unknown progress indeterminate and completes accessibly without Skia in low-effects mode', () => {
    const view = render(wrap(<ScanProgress label="Preparing the document" active={false} reduceEffects />));
    expect(screen.getByRole('progressbar', { name: 'Preparing the document' }).props.accessibilityValue).toBeUndefined();
    expect(view.UNSAFE_getByProps({ testID: 'low-effects-progress-indicator' })).toBeDefined();
    expect(view.UNSAFE_queryByProps({ testID: 'low-effects-progress-fill' })).toBeNull();
    expect(view.UNSAFE_queryAllByProps({ testID: 'canvas' })).toHaveLength(0);

    view.rerender(wrap(<ScanProgress label="Preparing the document" done doneLabel="Document ready" reduceEffects />));
    expect(screen.getByRole('progressbar', { name: 'Document ready' }).props.accessibilityValue).toEqual({ min: 0, max: 100, now: 100 });
    expect(view.UNSAFE_getByProps({ testID: 'low-effects-progress-fill' })).toBeDefined();
    expect(StyleSheet.flatten(view.UNSAFE_getByProps({ testID: 'low-effects-progress-fill' }).props.style).width).toBe('100%');
    expect(view.UNSAFE_queryAllByProps({ testID: 'canvas' })).toHaveLength(0);
    view.unmount();
  });

  it('keeps unknown work indeterminate, respects reduced motion, and only completes when done is explicit', () => {
    const view = render(wrap(<><MotionPreferenceProbe /><ScanProgress label="Waiting for document" active={false} imageLabel="Original page" /></> , 'always'));
    expect(screen.getByTestId('motion-preference')).toHaveTextContent('true');
    expect(screen.getByRole('progressbar', { name: 'Waiting for document' }).props.accessibilityValue).toBeUndefined();
    const card = view.UNSAFE_getAllByType(View).find((node) => node.props.accessibilityLabel === 'Original page');
    expect(card).toBeDefined();
    act(() => card!.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 180, height: 100 } } }));
    expect(view.UNSAFE_getAllByProps({ testID: 'canvas' })).toHaveLength(2);
    expect(view.UNSAFE_getByType(RingSpinner).props.active).toBe(false);

    view.rerender(wrap(<ScanProgress label="Waiting for document" done doneLabel="Document ready" progress={1} /> , 'always'));
    expect(screen.getByRole('progressbar', { name: 'Document ready' }).props.accessibilityValue).toEqual({ min: 0, max: 100, now: 100 });
    fireEvent(screen.getByRole('progressbar', { name: 'Document ready' }), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 260, height: 60 } } });
    expect(screen.queryByRole('progressbar', { name: 'Loading' })).toBeNull();
    expect(screen.getAllByTestId('canvas')).toHaveLength(1);
  });

  it('supports the SVG ring fallback and animates the explicit finished state', () => {
    mockPathFallback.current = true;
    const view = render(wrap(<ScanProgress label="Finishing" done tint="#3266bb" active={false} /> , 'never'));
    fireEvent(screen.getByRole('progressbar', { name: 'Analysis complete' }), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 250, height: 60 } } });
    expect(screen.getByRole('progressbar', { name: 'Analysis complete' }).props.accessibilityValue).toEqual({ min: 0, max: 100, now: 100 });
    expect(view.getAllByTestId('path').length).toBeGreaterThan(0);
  });

  it('advances the visible pixel scan across the measured page while active', () => {
    AppState.currentState = 'active';
    const view = render(wrap(<ScanProgress
      label="Scanning measured page"
      imageLabel="Measured page"
      active
      height={99}
    />));
    const card = view.UNSAFE_getAllByType(View).find((node) => node.props.accessibilityLabel === 'Measured page');
    expect(card).toBeDefined();
    act(() => card!.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 180, height: 99 } } }));
    expect(view.UNSAFE_getAllByProps({ testID: 'canvas' }).length).toBeGreaterThan(0);
    expect(mockPixelRects).toBeGreaterThan(0);
    const beforeSweep = mockPixelRects;

    act(() => {
      for (let index = 0; index < 20; index += 1) {
        mockFrames.filter((frame) => frame.active).forEach((frame) => frame.callback({ timeSincePreviousFrame: 100 }));
      }
      mockDerivedFactories.forEach((factory) => factory());
    });

    expect(mockPixelRects).toBeGreaterThan(beforeSweep);
    expect(view.UNSAFE_getByType(RingSpinner).props.active).toBe(true);
  });
});
