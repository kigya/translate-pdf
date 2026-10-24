import React from 'react';
import { Text } from 'react-native';
import { act, render } from '@testing-library/react-native-legacy';

const mockFrames: Array<(info: { timeSincePreviousFrame?: number | null }) => void> = [];
const mockActive = jest.fn();
const mockSharedValues: Array<{ value: number; get: () => number; set: (next: number) => void }> = [];
jest.mock('react-native-reanimated', () => ({
  useSharedValue: (initial: number) => {
    const shared = { value: initial, get() { return this.value; }, set(next: number) { this.value = next; } };
    mockSharedValues.push(shared);
    return shared;
  },
  useDerivedValue: (factory: () => unknown) => ({ get: factory }),
  useFrameCallback: (callback: (info: { timeSincePreviousFrame?: number | null }) => void, autoStart: boolean) => {
    mockFrames.push(callback);
    return { setActive: (value: boolean) => mockActive(value), autoStart };
  },
}));

import { useShaderClock, type ShaderClockOptions } from '../src/lib/kinetik/hooks/use-shader-clock';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { AuroraVeil } from '../src/components/kinetik/aurora-veil';

function Clock({ options }: { options: ShaderClockOptions }) {
  const time = useShaderClock(options);
  return <Text testID="clock">{time.get()}</Text>;
}

beforeEach(() => { mockFrames.length = 0; mockSharedValues.length = 0; mockActive.mockClear(); });

it('starts at the chosen time, advances at 60 Hz with a speed multiplier, and pauses when backgrounded', () => {
  const view = render(<Clock options={{ running: true, start: 2, speed: 2 }} />);
  expect(mockActive).toHaveBeenLastCalledWith(true);
  act(() => mockFrames[0]({ timeSincePreviousFrame: 16 }));
  expect(mockSharedValues[0].get()).toBeCloseTo(2.032);
  view.rerender(<Clock options={{ running: false, start: 2, speed: 2 }} />);
  expect(mockActive).toHaveBeenLastCalledWith(false);
});

it('uses the fallback delta and caps a delayed frame to avoid a large time jump', () => {
  render(<Clock options={{ running: true }} />);
  act(() => mockFrames[0]({ timeSincePreviousFrame: null }));
  expect(mockSharedValues[0].get()).toBeCloseTo(0.016);
  act(() => mockFrames[0]({ timeSincePreviousFrame: 300 }));
  expect(mockSharedValues[0].get()).toBeCloseTo(0.116);
});

it('publishes 30 Hz updates only after enough accumulated frame time', () => {
  render(<Clock options={{ running: true, frameRate: 30, speed: 0.5 }} />);
  act(() => mockFrames[0]({ timeSincePreviousFrame: 10 }));
  expect(mockSharedValues[0].get()).toBe(0);
  act(() => mockFrames[0]({ timeSincePreviousFrame: 16 }));
  expect(mockSharedValues[0].get()).toBeCloseTo(0.013);
});

it('keeps the Aurora still while effects are reduced or the view is inactive', () => {
  const view = render(<KinetikProvider scheme="dark" reduceMotion="always"><AuroraVeil active>Backdrop</AuroraVeil></KinetikProvider>);
  expect(mockActive).toHaveBeenLastCalledWith(false);
  view.rerender(<KinetikProvider scheme="dark" reduceMotion="never"><AuroraVeil active={false}>Backdrop</AuroraVeil></KinetikProvider>);
  expect(mockActive).toHaveBeenLastCalledWith(false);
  view.rerender(<KinetikProvider scheme="dark" reduceMotion="never"><AuroraVeil active>Backdrop</AuroraVeil></KinetikProvider>);
  expect(mockActive).toHaveBeenLastCalledWith(true);
});
