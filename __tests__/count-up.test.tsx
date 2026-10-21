import React from 'react';
import { act, render, screen } from '@testing-library/react-native-legacy';
import { Text } from 'react-native';

import { useCountUp, type CountUpOptions } from '../src/lib/kinetik/hooks/use-count-up';

let now = 0;
let nextFrame = 1;
let frames = new Map<number, (time: number) => void>();
function Counter({ target, options }: { target: number; options?: CountUpOptions }) {
  const value = useCountUp(target, options);
  return <Text testID="count">{value}</Text>;
}
function advanceFrame(milliseconds: number) {
  now += milliseconds;
  const frame = frames.entries().next().value;
  if (frame) {
    const [id, callback] = frame;
    frames.delete(id);
    act(() => callback(now));
  }
}

beforeEach(() => {
  now = 0;
  nextFrame = 1;
  frames = new Map();
  jest.spyOn(Date, 'now').mockImplementation(() => now);
  jest.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(callback => {
    const id = nextFrame++;
    frames.set(id, callback);
    return id;
  });
  jest.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(id => { if (id != null) frames.delete(id); });
});
afterEach(() => jest.restoreAllMocks());

it('eases toward the target and settles exactly on it', () => {
  render(<Counter target={100} options={{ duration: 1000 }} />);
  expect(screen.getByTestId('count')).toHaveTextContent('0');
  advanceFrame(500);
  expect(Number(screen.getByTestId('count').props.children)).toBeGreaterThan(95);
  expect(Number(screen.getByTestId('count').props.children)).toBeLessThan(100);
  advanceFrame(500);
  expect(screen.getByTestId('count')).toHaveTextContent('100');
  expect(frames.size).toBe(0);
});

it('continues a retargeted count from its current value and cancels the old frame', () => {
  const view = render(<Counter target={100} options={{ duration: 1000 }} />);
  advanceFrame(500);
  const beforeRetarget = Number(screen.getByTestId('count').props.children);
  view.rerender(<Counter target={200} options={{ duration: 1000 }} />);
  expect(frames.size).toBe(1);
  advanceFrame(500);
  expect(Number(screen.getByTestId('count').props.children)).toBeGreaterThan(beforeRetarget);
  advanceFrame(500);
  expect(screen.getByTestId('count')).toHaveTextContent('200');
});

it('sets an initial instant value and immediately handles zero duration', () => {
  const view = render(<Counter target={40} options={{ instant: true }} />);
  expect(screen.getByTestId('count')).toHaveTextContent('40');
  expect(frames.size).toBe(0);
  view.rerender(<Counter target={12} options={{ duration: 0 }} />);
  expect(screen.getByTestId('count')).toHaveTextContent('12');
  expect(frames.size).toBe(0);
});

it('starts from the requested value and cancels pending work on unmount', () => {
  const view = render(<Counter target={9} options={{ from: 4, duration: 1000 }} />);
  expect(screen.getByTestId('count')).toHaveTextContent('4');
  expect(frames.size).toBe(1);
  const pending = frames.values().next().value;
  view.unmount();
  expect(frames.size).toBe(0);
  expect(pending).toBeDefined();
  act(() => pending?.(now));
});

it('settles immediately when the current value already equals the target and applies defaults', () => {
  const view = render(<Counter target={0} />);
  expect(screen.getByTestId('count')).toHaveTextContent('0');
  expect(frames.size).toBe(0);
  view.rerender(<Counter target={1} />);
  expect(frames.size).toBe(1);
});
