import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { StyleSheet, Text } from 'react-native';

const mockClock = jest.fn();
const mockReduced = { value: false };
const mockUniforms: Array<() => Record<string, unknown>> = [];
jest.mock('../src/lib/kinetik/hooks/use-reduce-motion', () => ({ useReduceMotion: () => mockReduced.value }));
jest.mock('../src/lib/kinetik/hooks/use-shader-clock', () => ({ useShaderClock: (options: unknown) => { mockClock(options); return { get: () => 2 }; } }));
jest.mock('react-native-reanimated', () => ({
  useSharedValue: (value: unknown) => ({ get: () => value }),
  useDerivedValue: (factory: () => Record<string, unknown>) => { mockUniforms.push(factory); return { get: factory }; },
}));
import { ScrambleText, scrambleFrame } from '../src/components/kinetik/scramble-text';
import { GrainDrift } from '../src/components/kinetik/grain-drift';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
const wrap = (children: React.ReactNode) => <KinetikProvider scheme="light" haptics={false}>{children}</KinetikProvider>;
afterEach(() => { jest.useRealTimers(); mockReduced.value = false; mockClock.mockClear(); mockUniforms.length = 0; });

test('scramble settles once, retains its layout, and uses the latest completion callback', () => {
  jest.useFakeTimers();
  const oldDone = jest.fn(), done = jest.fn();
  const view = render(wrap(<ScrambleText text="Model" onDone={oldDone} />));
  const texts = view.UNSAFE_getAllByType(Text);
  fireEvent(texts[0], 'layout', { nativeEvent: { layout: { width: 50, height: 24 } } });
  expect(StyleSheet.flatten(view.UNSAFE_getAllByType(Text)[1].props.style).width).toBe(98);
  expect(texts[1].props.children).not.toBe('Model');
  view.rerender(wrap(<ScrambleText text="Model" onDone={done} />));
  act(() => { jest.advanceTimersByTime(1500); });
  expect(view.UNSAFE_getAllByType(Text)[1].props.children).toBe('Model');
  expect(oldDone).not.toHaveBeenCalled(); expect(done).toHaveBeenCalledTimes(1);
  act(() => { jest.advanceTimersByTime(1500); }); expect(done).toHaveBeenCalledTimes(1);
  view.unmount(); expect(jest.getTimerCount()).toBe(0);
});

test('scramble preserves multiline width, replays new text, handles one glyph, and stops when unmounted', () => {
  jest.useFakeTimers();
  expect(scrambleFrame('A', 0, 900, 20, '?')).toEqual({ text: '?', settled: false });
  const view = render(wrap(<ScrambleText text={'Line one\nLine two'} duration={100} delay={20} />));
  fireEvent(view.UNSAFE_getAllByType(Text)[0], 'layout', { nativeEvent: { layout: { width: 170, height: 48 } } });
  expect(view.UNSAFE_getAllByType(Text)[1].props.numberOfLines).toBeUndefined();
  expect(StyleSheet.flatten(view.UNSAFE_getAllByType(Text)[1].props.style).width).toBe(170);
  act(() => { jest.advanceTimersByTime(200); });
  expect(view.UNSAFE_getAllByType(Text)[1].props.children).toBe('Line one\nLine two');
  view.rerender(wrap(<ScrambleText text="Changed" duration={100} replayKey={2} />));
  expect(view.UNSAFE_getAllByType(Text)[1].props.children).not.toBe('Changed');
  view.unmount(); expect(jest.getTimerCount()).toBe(0);
});

test('scramble honors mount opt-out and live reduced motion without announcing noise', () => {
  jest.useFakeTimers();
  const view = render(wrap(<ScrambleText text="Ready" animateOnMount={false} />));
  expect(view.UNSAFE_getAllByType(Text)[1].props.children).toBe('Ready');
  expect(jest.getTimerCount()).toBe(0);
  mockReduced.value = true;
  view.rerender(wrap(<ScrambleText text="New model" />));
  expect(view.UNSAFE_getAllByType(Text)[1].props.children).toBe('New model');
  expect(screen.getByRole('text').props.accessibilityLabel).toBe('New model');
  expect(jest.getTimerCount()).toBe(0);
});

test('grain builds theme/custom uniforms, clamps grain, and pauses offscreen or for reduced motion', () => {
  const view = render(wrap(<GrainDrift><Text>Brand</Text></GrainDrift>));
  expect(mockClock).toHaveBeenLastCalledWith({ running: true, speed: 1, frameRate: 60, start: 2 });
  expect(mockUniforms.at(-1)!()).toMatchObject({ res: [0, 0], time: 2, grain: 0.5 });
  view.rerender(wrap(<GrainDrift colors={['#111111', '#222222', '#333333', '#444444']} grain={2} speed={3} active={false} frameRate={30} />));
  expect(mockClock).toHaveBeenLastCalledWith({ running: false, speed: 3, frameRate: 30, start: 2 });
  expect(mockUniforms.at(-1)!().grain).toBe(1);
  mockReduced.value = true;
  view.rerender(wrap(<GrainDrift grain={-1} />));
  expect(mockClock.mock.calls.at(-1)![0].running).toBe(false);
  expect(mockUniforms.at(-1)!().grain).toBe(0);
});
