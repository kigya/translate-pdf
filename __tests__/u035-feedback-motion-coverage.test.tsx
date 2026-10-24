import React, { createRef } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AccessibilityInfo, AppState, View } from 'react-native';
import { cancelAnimation } from 'react-native-reanimated';

type Shared<T> = { value: T; get: () => T; set: (value: T) => void; modify?: (update: (value: T) => T) => void };
type Frame = { callback: (info: { timeSincePreviousFrame?: number | null }) => void; active: boolean; setActive: (active: boolean) => void };
type MockGesture = { id: string; enabled: boolean; handlers: Record<string, (event: { x: number }) => void> };

const mockFrames: Frame[] = [];
const mockShared: Shared<unknown>[] = [];
const mockDerived: Array<{ get: () => unknown }> = [];
const mockTransformRefresh: Array<() => void> = [];
const mockScheduledRN: Array<() => void> = [];
const mockGestures: MockGesture[] = [];
const mockReduced = { value: false };
const mockHaptic = jest.fn();
const mockAnnounce = jest.fn();
let mockAppStateListener: ((state: string) => void) | undefined;
let mockQueueRN = false;
const mockFrame = (callback: Frame['callback'], active: boolean): Frame => {
  const frame = { callback, active, setActive(next: boolean) { frame.active = next; } };
  mockFrames.push(frame);
  return frame;
};

jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  const useSharedValue = (initial: unknown) => {
    const ref = ReactRuntime.useRef(undefined);
    if (ref.current === undefined) {
      const shared = {
        value: initial,
        get() { return this.value; },
        set(next: unknown) { this.value = next; },
        modify(update: (value: unknown) => unknown) { this.value = update(this.value); },
      };
      ref.current = shared;
      mockShared.push(shared);
    }
    return ref.current;
  };
  const withTiming = (to: unknown, _config?: unknown, callback?: (finished: boolean) => void) => {
    callback?.(true);
    return to;
  };
  const identity = (to: unknown) => to;
  return {
    __esModule: true,
    Easing: { linear: identity, bezier: () => identity, inOut: identity, sin: identity },
    ReduceMotion: { Always: 'always', Never: 'never' },
    useSharedValue,
    useFrameCallback: (callback: Frame['callback'], active: boolean) => {
      const ref = ReactRuntime.useRef(undefined);
      if (ref.current === undefined) ref.current = mockFrame(callback, active);
      ref.current.callback = callback;
      return ref.current;
    },
    useDerivedValue: (factory: () => unknown) => {
      const derived = { get: factory };
      mockDerived.push(derived);
      return derived;
    },
    cancelAnimation: jest.fn(),
    withTiming,
    withSpring: identity,
    withDelay: (_delay: number, animation: unknown) => animation,
    withRepeat: identity,
    withSequence: (...animations: unknown[]) => animations.at(-1),
  };
});

jest.mock('react-native-worklets', () => ({ scheduleOnRN: (callback: (...args: unknown[]) => void, ...args: unknown[]) => {
  if (mockQueueRN) mockScheduledRN.push(() => callback(...args));
  else callback(...args);
} }));
jest.mock('../src/lib/kinetik/hooks/use-haptic', () => ({ useHaptic: (enabled = true) => enabled ? mockHaptic : () => undefined }));
jest.mock('../src/lib/kinetik/hooks/use-reduce-motion', () => ({ useReduceMotion: () => mockReduced.value }));
jest.mock('../src/lib/kinetik/tokens/theme', () => ({
  useKinetikTheme: () => ({ colors: {
    ember: '#c52535', ice: '#cdefff', textMuted: '#666666', textSubtle: '#888888', azure: '#1665cc',
    azureSoft: '#dceaff', success: '#167c45', danger: '#b52230', track: '#dddddd', border: '#cccccc',
  } }),
}));
jest.mock('@shopify/react-native-skia', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  const node = (name: string) => ({ children, ...props }: { children?: React.ReactNode; [key: string]: unknown }) =>
    ReactRuntime.createElement(RN.View, { ...props, testID: name.toLowerCase() }, children);
  const pathBuilder = () => {
    const path: Record<string, (...args: unknown[]) => unknown> = {};
    for (const method of ['addCircle', 'moveTo', 'lineTo', 'quadTo', 'close']) path[method] = () => path;
    path.detach = () => ({ detached: true });
    return path;
  };
  return {
    Atlas: node('Atlas'), Canvas: node('Canvas'), Circle: node('Circle'), Group: node('Group'), Path: node('Path'), Rect: node('Rect'),
    Skia: { Color: (color: string) => color, PathBuilder: { Make: pathBuilder } },
    rect: (x: number, y: number, width: number, height: number) => ({ x, y, width, height }),
    vec: (x: number, y: number) => ({ x, y }),
    useTexture: () => ({ texture: true }),
    useRSXformBuffer: (count: number, callback: (transform: { set: (...args: number[]) => void }, index: number) => void) => {
      const transforms = Array.from({ length: count }, () => ({ set: jest.fn() }));
      const refresh = () => transforms.forEach((transform, index) => callback(transform, index));
      refresh();
      mockTransformRefresh.push(refresh);
      return transforms;
    },
  };
});
jest.mock('react-native-gesture-handler', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  const createGesture = (id: string): MockGesture => {
    const gesture = { id, enabled: true, handlers: {} as MockGesture['handlers'] };
    return gesture;
  };
  const instantiate = (id: string) => {
    const gesture = createGesture(id);
    mockGestures.push(gesture);
    return {
      _gesture: gesture,
      withTestId(testId: string) { gesture.id = testId; return this; },
      enabled(value: boolean) { gesture.enabled = value; return this; },
      activeOffsetX() { return this; },
      failOffsetY() { return this; },
      onUpdate(callback: (event: { x: number }) => void) { gesture.handlers.onUpdate = callback; return this; },
      onEnd(callback: (event: { x: number }) => void) { gesture.handlers.onEnd = callback; return this; },
    };
  };
  return {
    Gesture: { Pan: () => instantiate('pan'), Tap: () => instantiate('tap'), Exclusive: (...items: { _gesture: MockGesture }[]) => ({ items }) },
    GestureDetector: ({ gesture, children }: { gesture: { items: { _gesture: MockGesture }[] }; children: React.ReactNode }) =>
      ReactRuntime.createElement(RN.View, {
        testID: 'star-rating-gestures',
        onTouchMove: (event: { nativeEvent: { x: number } }) => gesture.items[0]?._gesture.enabled && gesture.items[0]._gesture.handlers.onUpdate?.(event.nativeEvent),
        onTouchEnd: (event: { nativeEvent: { x: number } }) => gesture.items[1]?._gesture.enabled && gesture.items[1]._gesture.handlers.onEnd?.(event.nativeEvent),
      }, children),
  };
});

import { ConfettiBurst, type ConfettiHandle } from '../src/components/kinetik/confetti-burst';
import { RingSpinner } from '../src/components/kinetik/ring-spinner';
import { StarRating } from '../src/components/kinetik/star-rating';
const wrap = (children: React.ReactNode, reducedMotion: 'always' | 'never' = 'always') => {
  mockReduced.value = reducedMotion === 'always';
  return <>{children}</>;
};
const physicsFrame = () => {
  const frame = mockFrames.find((candidate) => candidate.active);
  if (!frame) throw new Error('Expected an active animation frame.');
  return frame;
};
const ringHost = (view: ReturnType<typeof render>, testID: string) =>
  view.UNSAFE_getAllByProps({ testID }).find((node) => node.props.accessibilityRole === 'progressbar')!;

beforeEach(() => {
  AppState.currentState = 'active';
  mockAppStateListener = undefined;
  mockQueueRN = false;
  mockReduced.value = false;
  mockFrames.length = 0;
  mockShared.length = 0;
  mockDerived.length = 0;
  mockTransformRefresh.length = 0;
  mockScheduledRN.length = 0;
  mockGestures.length = 0;
  mockHaptic.mockClear();
  mockAnnounce.mockClear();
  (cancelAnimation as jest.Mock).mockClear();
  jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(mockAnnounce);
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    mockAppStateListener = listener as (state: string) => void;
    return { remove: jest.fn() } as never;
  });
});

afterEach(() => jest.restoreAllMocks());

describe('ConfettiBurst feedback lifecycle', () => {
  it('fires a bounded custom burst, advances particles, and stops once every particle expires', () => {
    const ref = createRef<ConfettiHandle>();
    const view = render(wrap(<ConfettiBurst ref={ref} count={4.4} colors={['#ff0000']} origin={{ x: 0.2, y: 0.8 }}
      power={0.1} haptics><View testID="completion-content" /></ConfettiBurst>, 'never'));
    expect(screen.getByTestId('completion-content')).toBeOnTheScreen();
    const size = mockShared.find((entry) => entry.value && typeof entry.value === 'object' && 'width' in (entry.value as object));
    act(() => size?.set({ width: 200, height: 100 }));
    act(() => ref.current?.burst({ x: 12, y: 24 }));
    const particles = mockShared.find((entry) => Array.isArray(entry.value));
    expect((particles?.value as Array<{ x: number; y: number }>).length).toBe(4);
    expect((particles?.value as Array<{ x: number; y: number }>)[0]).toMatchObject({ x: 12, y: 24 });
    expect(mockHaptic).toHaveBeenCalledWith('success');
    expect(physicsFrame().active).toBe(true);
    act(() => mockTransformRefresh.forEach((refresh) => refresh()));
    act(() => physicsFrame().callback({}));
    const runningFrame = physicsFrame();
    mockQueueRN = true;
    act(() => {
      for (let i = 0; i < 70 && runningFrame.active; i += 1) {
        runningFrame.callback({ timeSincePreviousFrame: 50 });
        mockScheduledRN.splice(0).forEach((callback) => callback());
      }
    });
    expect((particles?.value as Array<{ age: number; life: number }>).every((particle) => particle.age >= particle.life)).toBe(true);
    expect(mockScheduledRN.length).toBe(0);
    expect(runningFrame.active).toBe(false);
    act(() => mockTransformRefresh.forEach((refresh) => refresh()));

    act(() => ref.current?.burst());
    act(() => {
      for (let i = 0; i < 70 && runningFrame.active && mockScheduledRN.length === 0; i += 1) runningFrame.callback({ timeSincePreviousFrame: 100 });
    });
    expect(mockScheduledRN).toHaveLength(1);
    act(() => ref.current?.burst());
    act(() => mockScheduledRN.splice(0).forEach((callback) => callback()));
    expect(runningFrame.active).toBe(true);
    expect((particles?.value as unknown[]).length).toBe(4);
    // The frame callback also exercises transform fallback for expired sprites.
    const atlas = view.UNSAFE_getByProps({ testID: 'atlas' });
    expect(atlas.props.transforms).toHaveLength(4);
  });

  it('clamps count and power defaults, honors reduced motion, and does not restart on a same trigger value', () => {
    const ref = createRef<ConfettiHandle>();
    const view = render(wrap(<ConfettiBurst ref={ref} count={500} haptics={false}><View /></ConfettiBurst>, 'never'));
    view.rerender(wrap(<ConfettiBurst ref={ref} count={500} trigger="done" haptics={false}><View /></ConfettiBurst>, 'never'));
    const particles = mockShared.find((entry) => Array.isArray(entry.value));
    expect((particles?.value as unknown[]).length).toBe(300);
    expect(mockHaptic).not.toHaveBeenCalled();
    const before = mockFrames.length;
    view.rerender(wrap(<ConfettiBurst ref={ref} count={0} trigger="done" haptics={false}><View /></ConfettiBurst>, 'never'));
    expect((particles?.value as unknown[]).length).toBe(300);
    expect(mockFrames.length).toBe(before);
    view.rerender(wrap(<ConfettiBurst ref={ref} count={0} haptics={false}><View /></ConfettiBurst>, 'never'));
    const frame = mockFrames[0]!;
    act(() => mockAppStateListener?.('background'));
    expect((particles?.value as unknown[]).length).toBe(0);
    act(() => frame.callback({ timeSincePreviousFrame: 16 }));
    act(() => ref.current?.burst());
    act(() => mockAppStateListener?.('active'));
    act(() => ref.current?.burst());
    expect(mockHaptic).not.toHaveBeenCalled();
    view.rerender(wrap(<ConfettiBurst ref={ref} count={0} trigger="reduced" haptics><View /></ConfettiBurst>, 'always'));
    expect(mockFrames.at(-1)?.active).toBe(false);
    act(() => ref.current?.burst());
    expect(mockHaptic).not.toHaveBeenCalled();
  });

  it('uses the default particle count, theme palette, origin, power and success feedback', () => {
    const ref = createRef<ConfettiHandle>();
    const view = render(wrap(<ConfettiBurst ref={ref}><View /></ConfettiBurst>, 'never'));
    const atlas = view.UNSAFE_getByProps({ testID: 'atlas' });
    expect(atlas.props.sprites).toHaveLength(90);
    expect(atlas.props.colors).toHaveLength(90);
    act(() => ref.current?.burst());
    const particles = mockShared.find((entry) => Array.isArray(entry.value));
    expect((particles?.value as Array<{ x: number; y: number }>)[0]).toMatchObject({ x: 0, y: 0 });
    expect(mockHaptic).toHaveBeenCalledWith('success');
    view.unmount();
  });
});

describe('RingSpinner completion and cancellation', () => {
  it('clamps known progress, stops indeterminate animation, and hides duplicate accessibility', () => {
    const view = render(wrap(<RingSpinner progress={0.65} active accessibilityHidden size={48} thickness={4} color="#123456"
      accessibilityLabel="Model download" testID="download-spinner" /> , 'never'));
    const spinner = ringHost(view, 'download-spinner');
    expect(spinner.props.accessibilityValue).toEqual({ min: 0, max: 100, now: 65 });
    expect(spinner.props.accessible).toBe(false);
    expect(spinner.props.importantForAccessibility).toBe('no-hide-descendants');
    expect(cancelAnimation).toHaveBeenCalled();
    expect(view.UNSAFE_getAllByProps({ testID: 'path' }).length).toBeGreaterThan(0);
    const arc = mockDerived.find((derived) => {
      const value = derived.get();
      return Boolean(value && typeof value === 'object' && 'from' in value && 'length' in value);
    });
    expect(arc?.get()).toMatchObject({ from: 0, length: 0.65 });
  });

  it('announces success and error, changes semantics and cancels the spinning clock when work settles', () => {
    const view = render(wrap(<RingSpinner accessibilityLabel="Export" testID="spinner" /> , 'never'));
    const spinner = ringHost(view, 'spinner');
    expect(spinner.props.accessibilityValue).toBeUndefined();
    expect(cancelAnimation).not.toHaveBeenCalled();
    const clock = mockShared.find((entry) => typeof entry.value === 'number');
    const arc = mockDerived.find((derived) => {
      const value = derived.get();
      return Boolean(value && typeof value === 'object' && 'from' in value && 'length' in value);
    });
    clock?.set(0.1);
    expect(arc?.get()).toMatchObject({ from: expect.any(Number), length: expect.any(Number) });
    clock?.set(0.75);
    expect(arc?.get()).toMatchObject({ from: expect.any(Number), length: expect.any(Number) });
    view.rerender(wrap(<RingSpinner status="success" accessibilityLabel="Export" testID="spinner" /> , 'never'));
    expect(ringHost(view, 'spinner').props.accessibilityValue).toEqual({ text: 'Done' });
    expect(mockAnnounce).toHaveBeenLastCalledWith('Done');
    expect(cancelAnimation).toHaveBeenCalled();
    view.rerender(wrap(<RingSpinner status="error" accessibilityLabel="Export" testID="spinner" /> , 'never'));
    expect(ringHost(view, 'spinner').props.accessibilityValue).toEqual({ text: 'Failed' });
    expect(mockAnnounce).toHaveBeenLastCalledWith('Failed');
  });

  it('respects inactive and reduced-motion states, and reacts to app foreground changes', () => {
    const listeners: Array<(state: string) => void> = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      listeners.push(listener as (state: string) => void);
      return { remove: jest.fn() } as never;
    });
    const view = render(wrap(<RingSpinner testID="paused" active={false} /> , 'never'));
    expect(cancelAnimation).toHaveBeenCalled();
    view.rerender(wrap(<RingSpinner testID="paused" active /> , 'always'));
    expect(cancelAnimation).toHaveBeenCalled();
    act(() => listeners.forEach((listener) => listener('background')));
    act(() => listeners.forEach((listener) => listener('active')));
    expect(ringHost(view, 'paused').props.accessibilityRole).toBe('progressbar');
    expect(mockDerived.map((derived) => derived.get()).length).toBeGreaterThan(0);
    view.rerender(wrap(<RingSpinner status="success" testID="paused" /> , 'always'));
    expect(ringHost(view, 'paused').props.accessibilityValue).toEqual({ text: 'Done' });
    expect(mockAnnounce).toHaveBeenLastCalledWith('Done');
  });
});

describe('StarRating interaction and accessibility', () => {
  it('supports half-star dragging, clamped increments, haptics and controlled animation after commit', () => {
    const onValueChange = jest.fn();
    const view = render(wrap(<StarRating value={2} onValueChange={onValueChange} allowHalf max={4}
      accessibilityLabel="Document quality" haptics /> , 'never'));
    const rating = view.UNSAFE_getByProps({ accessibilityRole: 'adjustable' });
    expect(rating.props.accessibilityRole).toBe('adjustable');
    fireEvent(rating, 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    expect(onValueChange).toHaveBeenCalledWith(2.5);
    fireEvent(rating, 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });
    expect(onValueChange).toHaveBeenLastCalledWith(1.5);
    fireEvent(screen.getByTestId('star-rating-gestures'), 'touchMove', { nativeEvent: { x: 58 } });
    expect(onValueChange).toHaveBeenLastCalledWith(1.5);
    fireEvent(screen.getByTestId('star-rating-gestures'), 'touchMove', { nativeEvent: { x: 14 } });
    expect(onValueChange).toHaveBeenLastCalledWith(0.5);
    view.rerender(wrap(<StarRating value={0.5} onValueChange={onValueChange} allowHalf max={4}
      accessibilityLabel="Document quality" haptics /> , 'never'));
    expect(mockHaptic).toHaveBeenCalledWith('tick');
    mockDerived.forEach((derived) => derived.get());
    expect(view.UNSAFE_getByProps({ accessibilityRole: 'adjustable' }).props.accessibilityValue).toEqual({ min: 0, max: 4, now: 0.5, text: '0.5 / 4' });
  });

  it('clears a matching tap when clearable, blocks disabled input and reports read-only ratings as images', () => {
    const onValueChange = jest.fn();
    const clearable = render(wrap(<StarRating value={3} onValueChange={onValueChange} max={5} clearable testID="rating" />));
    fireEvent(clearable.getByTestId('star-rating-gestures'), 'touchEnd', { nativeEvent: { x: 100 } });
    expect(onValueChange).toHaveBeenCalledWith(0);
    clearable.unmount();
    const disabled = render(wrap(<StarRating value={2} onValueChange={onValueChange} disabled testID="rating" />));
    expect(mockGestures.slice(-2).every((gesture) => !gesture.enabled)).toBe(true);
    fireEvent(disabled.getByTestId('star-rating-gestures'), 'touchMove', { nativeEvent: { x: 120 } });
    expect(onValueChange).toHaveBeenCalledTimes(1);
    disabled.unmount();
    const readOnly = render(wrap(<StarRating value={3.5} max={5} accessibilityLabel="Stored score" />));
    expect(readOnly.getByRole('image', { name: 'Stored score: 3.5 / 5' })).toBeOnTheScreen();
    expect(readOnly.queryByTestId('star-rating-gestures')).toBeNull();
  });

  it('does not clear when clearable is false and ignores actions when disabled or at a bound', () => {
    const onValueChange = jest.fn();
    const view = render(wrap(<StarRating value={5} onValueChange={onValueChange} clearable={false} disabled={false} testID="rating" />));
    fireEvent(view.UNSAFE_getByProps({ accessibilityRole: 'adjustable' }), 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    expect(onValueChange).not.toHaveBeenCalled();
    fireEvent(view.UNSAFE_getByProps({ accessibilityRole: 'adjustable' }), 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });
    expect(onValueChange).toHaveBeenCalledWith(4);
    fireEvent(view.getByTestId('star-rating-gestures'), 'touchEnd', { nativeEvent: { x: 250 } });
    expect(onValueChange).toHaveBeenLastCalledWith(5);
    view.unmount();
    const disabled = render(wrap(<StarRating value={1} onValueChange={onValueChange} disabled testID="rating" />));
    fireEvent(disabled.UNSAFE_getByProps({ accessibilityRole: 'adjustable' }), 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    expect(onValueChange).toHaveBeenCalledTimes(2);
  });
});
