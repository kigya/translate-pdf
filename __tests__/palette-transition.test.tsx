import React, { useLayoutEffect } from 'react';
import { Image, Text, View } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native-legacy';
import { captureRef, releaseCapture } from 'react-native-view-shot';

jest.mock('react-native-reanimated', () => ({
  useSharedValue: (value: number) => ({ value, get() { return this.value; }, set(next: number) { this.value = next; } }),
  useAnimatedStyle: (fn: () => object) => fn(),
  withTiming: (value: number) => value,
}));

import { usePaletteTransition } from '../src/ui/usePaletteTransition';

function TransitionHarness({ target, reduced = false, revealOnLayout = false, onReveal }: { target: 'light' | 'dark'; reduced?: boolean; revealOnLayout?: boolean; onReveal?: (reveal: () => void) => void }) {
  const { view, mode, image, overlay, reveal } = usePaletteTransition(target, reduced);
  onReveal?.(reveal);
  useLayoutEffect(() => { if (revealOnLayout) reveal(); }, [revealOnLayout, reveal]);
  return <View ref={view} testID="captured-screen">
    <Text testID="mode">{mode}</Text>
    {image ? <Image testID="overlay" source={{ uri: image }} style={overlay as never} /> : null}
    <Text testID="image-uri">{image}</Text>
    <Text testID="opacity">{String((overlay as unknown as { opacity: number }).opacity)}</Text>
    <Text accessibilityRole="button" onPress={reveal}>Reveal complete</Text>
  </View>;
}

const capture = captureRef as jest.MockedFunction<typeof captureRef>;
const release = releaseCapture as jest.MockedFunction<typeof releaseCapture>;
beforeEach(() => { jest.clearAllMocks(); capture.mockReset(); release.mockReset(); capture.mockResolvedValue('file:///temporary.png'); });

it('captures before switching palettes, releases a replaced capture, and reveals it away', async () => {
  const view = render(<TransitionHarness target="light" />);
  view.rerender(<TransitionHarness target="dark" />);
  await waitFor(() => expect(screen.getByTestId('mode')).toHaveTextContent('dark'));
  expect(capture).toHaveBeenCalledWith(expect.anything(), { format: 'png', result: 'tmpfile' });
  expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///temporary.png');
  expect(screen.getByTestId('overlay')).toBeOnTheScreen();
  fireEvent.press(screen.getByText('Reveal complete'));
  expect(screen.getByTestId('opacity')).toHaveTextContent('0');

  capture.mockResolvedValueOnce('file:///second.png');
  view.rerender(<TransitionHarness target="light" />);
  await waitFor(() => expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///second.png'));
  expect(release).toHaveBeenCalledWith('file:///temporary.png');
  view.unmount();
  expect(release).toHaveBeenCalledWith('file:///second.png');
});

it('switches immediately for reduced motion and skips capture when capture fails', async () => {
  const view = render(<TransitionHarness target="light" />);
  view.rerender(<TransitionHarness target="dark" reduced />);
  expect(screen.getByTestId('mode')).toHaveTextContent('dark');
  expect(capture).not.toHaveBeenCalled();
  fireEvent.press(screen.getByText('Reveal complete'));
  expect(screen.getByTestId('opacity')).toHaveTextContent('0');
  capture.mockRejectedValueOnce(new Error('capture unavailable'));
  view.rerender(<TransitionHarness target="light" reduced={false} />);
  await waitFor(() => expect(screen.getByTestId('mode')).toHaveTextContent('light'));
  expect(screen.getByTestId('image-uri')).toBeEmptyElement();
});

it('releases a capture that resolves after the transition host unmounts', async () => {
  let resolve!: (uri: string) => void;
  capture.mockReturnValueOnce(new Promise(resolveCapture => { resolve = resolveCapture; }));
  const view = render(<TransitionHarness target="light" />);
  view.rerender(<TransitionHarness target="dark" />);
  await waitFor(() => expect(capture).toHaveBeenCalledTimes(1));
  expect(screen.getByTestId('mode')).toHaveTextContent('light');
  view.unmount();
  await act(async () => { resolve('file:///late.png'); await Promise.resolve(); await Promise.resolve(); });
  await waitFor(() => expect(release).toHaveBeenCalledWith('file:///late.png'));
});

it('discards a stale capture after the requested theme changes again', async () => {
  let resolve!: (uri: string) => void;
  capture.mockReturnValueOnce(new Promise(resolveCapture => { resolve = resolveCapture; }));
  const view = render(<TransitionHarness target="light" />);
  view.rerender(<TransitionHarness target="dark" />);
  await waitFor(() => expect(capture).toHaveBeenCalledTimes(1));
  view.rerender(<TransitionHarness target="light" />);
  await act(async () => { resolve('file:///obsolete.png'); await Promise.resolve(); });
  expect(release).toHaveBeenCalledWith('file:///obsolete.png');
  expect(screen.getByTestId('mode')).toHaveTextContent('light');
  expect(screen.getByTestId('image-uri')).toBeEmptyElement();
});

it('ignores a capture rejection after the screen has unmounted', async () => {
  let reject!: (error: Error) => void;
  capture.mockReturnValueOnce(new Promise((_, rejectCapture) => { reject = rejectCapture; }));
  const view = render(<TransitionHarness target="light" />);
  view.rerender(<TransitionHarness target="dark" />);
  await waitFor(() => expect(capture).toHaveBeenCalledTimes(1));
  view.unmount();
  await act(async () => { reject(new Error('late capture failure')); await Promise.resolve(); });
  expect(release).not.toHaveBeenCalled();
});

it('fades out, removes the overlay, and releases its temporary image after reveal', async () => {
  jest.useFakeTimers();
  try {
    const view = render(<TransitionHarness target="light" />);
    view.rerender(<TransitionHarness target="dark" />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId('overlay')).toBeOnTheScreen();
    fireEvent.press(screen.getByText('Reveal complete'));
    act(() => { jest.advanceTimersByTime(280); });
    expect(screen.queryByTestId('overlay')).toBeNull();
    expect(release).toHaveBeenCalledWith('file:///temporary.png');
  } finally {
    jest.useRealTimers();
  }
});

it('cancels an old fade when a replacement capture arrives and immediately discards when reduced motion turns on', async () => {
  jest.useFakeTimers();
  try {
    const view = render(<TransitionHarness target="light" />);
    view.rerender(<TransitionHarness target="dark" />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    fireEvent.press(screen.getByText('Reveal complete'));

    capture.mockResolvedValueOnce('file:///replacement.png');
    view.rerender(<TransitionHarness target="light" />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///replacement.png');
    expect(release).toHaveBeenCalledWith('file:///temporary.png');
    act(() => { jest.advanceTimersByTime(280); });
    expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///replacement.png');

    view.rerender(<TransitionHarness target="dark" reduced />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId('image-uri')).toBeEmptyElement();
    expect(release).toHaveBeenCalledWith('file:///replacement.png');
  } finally {
    jest.useRealTimers();
  }
});

it('ignores a stale reveal after capture release and can reveal in the reduced-motion commit window', async () => {
  let staleReveal: (() => void) | undefined;
  const view = render(<TransitionHarness target="light" onReveal={reveal => { staleReveal = reveal; }} />);
  view.rerender(<TransitionHarness target="dark" onReveal={reveal => { staleReveal = reveal; }} />);
  await waitFor(() => expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///temporary.png'));
  view.rerender(<TransitionHarness target="dark" reduced onReveal={reveal => { staleReveal = reveal; }} />);
  expect(screen.getByTestId('image-uri')).toBeEmptyElement();
  act(() => staleReveal?.());
  expect(release).toHaveBeenCalledWith('file:///temporary.png');

  view.rerender(<TransitionHarness target="light" reduced={false} />);
  await waitFor(() => expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///temporary.png'));
  view.rerender(<TransitionHarness target="light" reduced revealOnLayout />);
  expect(screen.getByTestId('opacity')).toHaveTextContent('0');
  expect(screen.getByTestId('image-uri')).toBeEmptyElement();
  expect(release).toHaveBeenCalledWith('file:///temporary.png');
});

it('ignores a fade callback that was already queued when a replacement capture wins', async () => {
  jest.useFakeTimers();
  const schedule = jest.spyOn(globalThis, 'setTimeout');
  try {
    const view = render(<TransitionHarness target="light" />);
    view.rerender(<TransitionHarness target="dark" />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    fireEvent.press(screen.getByText('Reveal complete'));
    const scheduled = schedule.mock.calls as unknown as Array<[() => void, number]>;
    const staleCallback = scheduled.map(([callback, delay]) => delay === 280 ? callback : undefined).find(Boolean);
    expect(staleCallback).toBeDefined();

    capture.mockResolvedValueOnce('file:///newer.png');
    view.rerender(<TransitionHarness target="light" />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///newer.png');
    act(() => staleCallback?.());
    expect(screen.getByTestId('image-uri')).toHaveTextContent('file:///newer.png');
    expect(release).toHaveBeenCalledWith('file:///temporary.png');
    expect(release).not.toHaveBeenCalledWith('file:///newer.png');
  } finally {
    schedule.mockRestore();
    jest.useRealTimers();
  }
});
