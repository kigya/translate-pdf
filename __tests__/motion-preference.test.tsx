import React from 'react';
import { AccessibilityInfo, Text } from 'react-native';
import { act, cleanup, render, screen } from '@testing-library/react-native';
import { useMotionReduced } from '../src/ui/useMotionReduced';

let mockInitial: boolean | undefined;
jest.mock('react-native-reanimated', () => ({ useReducedMotion: () => mockInitial }));

function Preference() {
  return <Text>{useMotionReduced() ? 'reduced' : 'animated'}</Text>;
}
let resolveRead: (value: boolean) => void;
let rejectRead: (reason: Error) => void;
let change: (value: boolean) => void;
let remove: jest.Mock;
beforeEach(() => {
  mockInitial = undefined;
  remove = jest.fn();
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockImplementation(() =>
    new Promise<boolean>((resolve, reject) => { resolveRead = resolve; rejectRead = reject; }),
  );
  jest.spyOn(AccessibilityInfo, 'addEventListener').mockImplementation((event, listener) => {
    expect(event).toBe('reduceMotionChanged');
    change = listener as (value: boolean) => void;
    return { remove };
  });
});
afterEach(() => { cleanup(); jest.restoreAllMocks(); });

test('reads the current system preference and follows changes in both directions', async () => {
  const view = render(<Preference />);
  expect(screen.getByText('animated')).toBeTruthy();
  await act(async () => { resolveRead(true); });
  expect(screen.getByText('reduced')).toBeTruthy();
  act(() => change(false));
  expect(screen.getByText('animated')).toBeTruthy();
  act(() => change(true));
  expect(screen.getByText('reduced')).toBeTruthy();
  view.unmount();
  expect(remove).toHaveBeenCalledTimes(1);
});

test('a pending startup read cannot overwrite a newer native event', async () => {
  mockInitial = false;
  render(<Preference />);
  act(() => change(true));
  await act(async () => { resolveRead(false); });
  expect(screen.getByText('reduced')).toBeTruthy();
});

test('a failed native read preserves the startup preference and live events still work', async () => {
  mockInitial = true;
  render(<Preference />);
  expect(screen.getByText('reduced')).toBeTruthy();
  await act(async () => { rejectRead(new Error('preference unavailable')); });
  expect(screen.getByText('reduced')).toBeTruthy();
  act(() => change(false));
  expect(screen.getByText('animated')).toBeTruthy();
});

test('unmount cancels observation and discards the unfinished native read', async () => {
  const view = render(<Preference />);
  view.unmount();
  await act(async () => { resolveRead(true); });
  expect(remove).toHaveBeenCalledTimes(1);
});
