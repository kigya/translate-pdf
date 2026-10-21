import React, { createRef } from 'react';
import { act, render } from '@testing-library/react-native-legacy';
import { AppState, View } from 'react-native';

import { ConfettiBurst, type ConfettiHandle } from '../src/components/kinetik/confetti-burst';

type MockFrame = {
  callback: (info: { timeSincePreviousFrame?: number | null }) => void;
  active: boolean;
};

const mockFrames: MockFrame[] = [];
const mockSharedValues: Array<{ value: unknown }> = [];
const mockHaptic = jest.fn();
let mockReduced = false;
let mockAppStateListener: ((state: string) => void) | undefined;
const mockRemoveAppStateListener = jest.fn();
const originalAppState = AppState.currentState;

jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  return {
    useSharedValue: (initial: unknown) => {
      const ref = ReactRuntime.useRef(null) as { current: null | { value: unknown; get: () => unknown; set: (value: unknown) => void; modify: (update: (value: any) => any) => void } };
      if (!ref.current) {
        const shared = {
          value: initial,
          get() { return this.value; },
          set(value: unknown) { this.value = value; },
          modify(update: (value: any) => any) { this.value = update(this.value); },
        };
        ref.current = shared;
        mockSharedValues.push(shared);
      }
      return ref.current;
    },
    useFrameCallback: (callback: MockFrame['callback'], initiallyActive: boolean) => {
      const ref = ReactRuntime.useRef(null) as { current: null | MockFrame };
      if (!ref.current) {
        ref.current = { callback, active: initiallyActive };
        mockFrames.push(ref.current);
      }
      ref.current.callback = callback;
      return { setActive: (active: boolean) => { if (ref.current) ref.current.active = active; } };
    },
  };
});

jest.mock('react-native-worklets', () => ({ scheduleOnRN: (callback: () => void) => callback() }));
jest.mock('@shopify/react-native-skia', () => ({
  ...jest.requireActual('@shopify/react-native-skia'),
  useTexture: () => ({}),
}));
jest.mock('../src/lib/kinetik/hooks/use-haptic', () => ({ useHaptic: () => mockHaptic }));
jest.mock('../src/lib/kinetik/hooks/use-reduce-motion', () => ({ useReduceMotion: () => mockReduced }));
jest.mock('../src/lib/kinetik/tokens/theme', () => ({
  useKinetikTheme: () => ({ colors: { ember: '#f00', ice: '#0ff', textMuted: '#555', textSubtle: '#777' } }),
}));

const frame = () => {
  const found = mockFrames.at(-1);
  if (!found) throw new Error('The confetti frame callback was not registered.');
  return found;
};

function renderBurst(props: { trigger?: number } = {}) {
  const ref = createRef<ConfettiHandle>();
  const view = render(<ConfettiBurst ref={ref} trigger={props.trigger} haptics><View testID="content" /></ConfettiBurst>);
  return { ...view, ref };
}

beforeEach(() => {
  mockFrames.length = 0;
  mockSharedValues.length = 0;
  mockHaptic.mockClear();
  mockRemoveAppStateListener.mockClear();
  mockReduced = false;
  mockAppStateListener = undefined;
  AppState.currentState = 'active';
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    mockAppStateListener = listener as (state: string) => void;
    return { remove: mockRemoveAppStateListener } as never;
  });
});

afterEach(() => {
  jest.restoreAllMocks();
  AppState.currentState = originalAppState;
});

describe('ConfettiBurst lifecycle', () => {
  test('stops and clears an active burst in background and ignores manual and trigger requests there', () => {
    const view = renderBurst({ trigger: 1 });
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(true);
    expect(mockHaptic).toHaveBeenCalledTimes(1);
    const particles = mockSharedValues.find((shared) => Array.isArray(shared.value));
    expect((particles?.value as unknown[]).length).toBeGreaterThan(0);

    act(() => mockAppStateListener?.('background'));
    expect(frame().active).toBe(false);
    expect(particles?.value).toEqual([]);
    act(() => view.ref.current?.burst());
    view.rerender(<ConfettiBurst ref={view.ref} trigger={2} haptics><View testID="content" /></ConfettiBurst>);
    expect(frame().active).toBe(false);
    expect(mockHaptic).toHaveBeenCalledTimes(1);

    act(() => mockAppStateListener?.('active'));
    expect(frame().active).toBe(false);
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(true);
    expect(mockHaptic).toHaveBeenCalledTimes(2);
  });

  test('does not start a burst when mounted in the background and resumes only after becoming active', () => {
    AppState.currentState = 'background';
    const view = renderBurst({ trigger: 1 });

    expect(frame().active).toBe(false);
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(false);
    expect(mockHaptic).not.toHaveBeenCalled();

    act(() => mockAppStateListener?.('inactive'));
    act(() => mockAppStateListener?.('active'));
    expect(frame().active).toBe(false);
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(true);
    expect(mockHaptic).toHaveBeenCalledTimes(1);
  });

  test('turning reduced motion on cancels particles and turning it off never resumes an old burst', () => {
    const view = renderBurst();
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(true);
    const particles = mockSharedValues.find((shared) => Array.isArray(shared.value));

    mockReduced = true;
    view.rerender(<ConfettiBurst ref={view.ref} haptics><View testID="content" /></ConfettiBurst>);
    expect(frame().active).toBe(false);
    expect(particles?.value).toEqual([]);
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(false);
    expect(mockHaptic).toHaveBeenCalledTimes(1);

    mockReduced = false;
    view.rerender(<ConfettiBurst ref={view.ref} haptics><View testID="content" /></ConfettiBurst>);
    expect(frame().active).toBe(false);
    act(() => view.ref.current?.burst());
    expect(frame().active).toBe(true);
  });

  test('stops on unmount, removes the app-state listener, and ignores a late frame callback', () => {
    const view = renderBurst();
    act(() => view.ref.current?.burst());
    const frameControl = frame();
    const lateCallback = frameControl.callback;
    expect(frameControl.active).toBe(true);

    view.unmount();
    expect(frameControl.active).toBe(false);
    expect(mockRemoveAppStateListener).toHaveBeenCalledTimes(1);
    expect(() => lateCallback({ timeSincePreviousFrame: 16 })).not.toThrow();
    expect(frameControl.active).toBe(false);
  });
});
