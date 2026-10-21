jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Modal, Text, Pressable } from 'react-native';
let mockReduced: boolean | undefined = true;
jest.mock('react-native-reanimated', () => ({
  __esModule: true,
  default: { View: require('react-native').View },
  useReducedMotion: () => mockReduced,
  useSharedValue: (value: number) => ({ value }),
  useAnimatedStyle: (fn: () => object) => fn(),
  withTiming: (v: number) => v,
}));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }) }));
import { DialogProvider, useDialog } from '../src/ui/Dialog';
import { palettes } from '../src/ui/theme';
const resolved = jest.fn();
function Launch({ twice = false }: { twice?: boolean }) {
  const show = useDialog();
  return <Pressable accessibilityRole="button" accessibilityLabel="Open" onPress={() => {
    void show({ title: 'Remove weights?', message: 'Documents stay on this device.', icon: 'trash', actions: [{ id: 'cancel', label: 'Keep', quiet: true }, { id: 'remove', label: 'Remove', destructive: true }] }).then(resolved);
    if (twice) void show({ title: 'Second', message: 'Queued information', actions: [{ id: 'ok', label: 'Done' }] }).then(resolved);
  }}><Text>Open</Text></Pressable>;
}
beforeEach(() => { resolved.mockClear(); mockReduced = true; });
afterEach(() => jest.useRealTimers());
it('presents an app-owned accessible modal, hides background actions, and resolves its actual choice', async () => {
  render(<DialogProvider colors={palettes.dark}><Launch/></DialogProvider>);
  fireEvent.press(screen.getByRole('button', { name: 'Open' }));
  expect(screen.getByRole('header', { name: 'Remove weights?' })).toBeOnTheScreen();
  expect(screen.getByText('Documents stay on this device.')).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Open' })).toBeNull();
  await act(async () => fireEvent.press(screen.getByRole('button', { name: 'Remove' })));
  expect(resolved).toHaveBeenCalledWith('remove');
  expect(screen.getByRole('button', { name: 'Open' })).toBeOnTheScreen();
});
it('queues requests without losing a promise and handles Back and backdrop cancellation', async () => {
  render(<DialogProvider colors={palettes.light}><Launch twice/></DialogProvider>);
  fireEvent.press(screen.getByRole('button', { name: 'Open' }));
  await act(async () => fireEvent(screen.UNSAFE_getByType(Modal), 'requestClose'));
  expect(resolved).toHaveBeenNthCalledWith(1, null);
  expect(screen.getByText('Second')).toBeOnTheScreen();
  await act(async () => fireEvent.press(screen.getByTestId('dialog-backdrop', { includeHiddenElements: true })));
  expect(resolved).toHaveBeenNthCalledWith(2, null);
});
it('animates entry/exit and ignores duplicate presses while exiting', async () => {
  jest.useFakeTimers(); mockReduced = undefined;
  const view=render(<DialogProvider colors={palettes.light}><Launch/></DialogProvider>);
  fireEvent.press(screen.getByRole('button', { name: 'Open' }));
  fireEvent.press(screen.getByRole('button', { name: 'Keep' }));
  fireEvent.press(screen.getByRole('button', { name: 'Remove' }));
  expect(resolved).not.toHaveBeenCalled();
  await act(async () => jest.advanceTimersByTime(160));
  expect(resolved).toHaveBeenCalledTimes(1);
  expect(resolved).toHaveBeenCalledWith('cancel');
  view.unmount();
});
it('settles pending and queued promises on unmount and cancels its exit timer', async () => {
  jest.useFakeTimers(); mockReduced = false;
  const view=render(<DialogProvider colors={palettes.light}><Launch twice/></DialogProvider>);
  fireEvent.press(screen.getByRole('button', { name: 'Open' }));
  fireEvent.press(screen.getByRole('button', { name: 'Remove' }));
  await act(async () => view.unmount());
  expect(resolved.mock.calls).toEqual([[null],[null]]);
  act(() => jest.runOnlyPendingTimers());
  expect(resolved).toHaveBeenCalledTimes(2);
});
it('fails closed if a standalone caller has no dialog host', async () => {
  render(<Launch/>);
  await act(async () => fireEvent.press(screen.getByRole('button', { name: 'Open' })));
  expect(resolved).toHaveBeenCalledWith(null);
});

it('follows the selected app RTL direction independently of the system direction',()=>{
 render(<DialogProvider colors={palettes.dark} direction="rtl"><Launch/></DialogProvider>);
 fireEvent.press(screen.getByRole('button',{name:'Open'}));
 expect(screen.getByRole('header',{name:'Remove weights?'})).toHaveStyle({writingDirection:'rtl',textAlign:'right'});
 expect(screen.getByText('Documents stay on this device.')).toHaveStyle({writingDirection:'rtl',textAlign:'right'});
});
