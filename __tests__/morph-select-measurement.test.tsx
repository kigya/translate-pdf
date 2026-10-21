import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react-native-legacy';
let mockPressedTriggerStyle: unknown;

jest.mock('react-native/Libraries/Components/Pressable/Pressable', () => {
  const ReactModule = require('react');
  const actual = jest.requireActual('react-native/Libraries/Components/Pressable/Pressable');
  const Pressable = ReactModule.forwardRef((props: object, ref: React.Ref<unknown>) => {
    const pressable = props as { accessibilityLabel?: string; style?: (state: { pressed: boolean }) => unknown };
    if (pressable.accessibilityLabel === 'Reduced, One' && typeof pressable.style === 'function') {
      mockPressedTriggerStyle = pressable.style({ pressed: true });
    }
    ReactModule.useImperativeHandle(ref, () => ({
      measureInWindow: (callback: (x: number, y: number, width: number, height: number) => void) => callback(18, 9000, 250, 52),
    }), []);
    return ReactModule.createElement(actual.default, { ...props, ref: undefined });
  });
  return { ...actual, default: Pressable };
});

jest.mock('react-native-reanimated', () => {
  const identity = (value: unknown) => value;
  const ReactModule = require('react');
  const { View, Text, ScrollView } = require('react-native');
  return {
    __esModule: true,
    default: { View, Text, ScrollView },
    Easing: { bezier: (..._values: number[]) => identity, linear: identity, sin: identity, inOut: identity },
    ReduceMotion: { Always: 'always', Never: 'never' },
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    useAnimatedStyle: (factory: () => object) => factory(),
    useSharedValue: (value: unknown) => {
      const ref = ReactModule.useRef(undefined);
      if (ref.current === undefined) ref.current = { value, get() { return this.value; }, set(next: unknown) { this.value = next; } };
      return ref.current;
    },
    withDelay: (_delay: number, value: unknown) => value,
    withSpring: identity,
    withTiming: (value: unknown, _config: unknown, callback?: (finished: boolean) => void) => { callback?.(true); return value; },
  };
});

import { Modal, View } from 'react-native';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { MorphSelect } from '../src/components/kinetik/morph-select';

it('uses measured geometry to place the menu above a low trigger and closes via escape, header and scrim', () => {
  const view = render(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}>
    <MorphSelect label="Measured" options={[{ value: 'one', label: 'One' }, { value: 'two', label: 'Two' }]} value="one" onValueChange={jest.fn()} />
  </KinetikProvider>);

  fireEvent.press(screen.getByRole('button', { name: 'Measured, One' }));
  const menu = view.UNSAFE_getByProps({ accessibilityRole: 'list' });
  expect(menu.props.style).toEqual(expect.arrayContaining([expect.objectContaining({ left: 18, width: 250, flexDirection: 'column-reverse' })]));
  expect(screen.getByRole('menuitem', { name: 'Two' })).toBeOnTheScreen();
  fireEvent(screen.UNSAFE_getByType(Modal), 'requestClose');
  expect(screen.queryByRole('menuitem', { name: 'Two' })).toBeNull();

  fireEvent.press(screen.getByRole('button', { name: 'Measured, One' }));
  fireEvent.press(within(screen.UNSAFE_getByType(Modal)).getByRole('button', { name: 'Measured, One' }));
  expect(screen.queryByRole('menuitem', { name: 'Two' })).toBeNull();

  fireEvent.press(screen.getByRole('button', { name: 'Measured, One' }));
  fireEvent.press(screen.getByRole('button', { name: 'Close list' }));
  expect(screen.queryByRole('menuitem', { name: 'Two' })).toBeNull();

  fireEvent.press(screen.getByRole('button', { name: 'Measured, One' }));
  fireEvent(screen.UNSAFE_getByType(Modal).findByType(View), 'accessibilityEscape');
  expect(screen.queryByRole('menuitem', { name: 'Two' })).toBeNull();
});

it('uses the reduced-motion transition and ignores a stale row index from a malformed option list', () => {
  const onValueChange = jest.fn();
  const malformed = [{ value: 'one', label: 'One' }] as Array<{ value: string; label: string }>;
  Object.defineProperty(malformed, 'map', {
    value: (callback: (option: { value: string; label: string }, index: number) => unknown) => [callback(malformed[0], 99)],
  });
  const view = render(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}>
    <MorphSelect label="Reduced" options={malformed} value="one" onValueChange={onValueChange} />
  </KinetikProvider>);
  const trigger = screen.getByRole('button', { name: 'Reduced, One' });
  expect(mockPressedTriggerStyle).toEqual(expect.arrayContaining([expect.objectContaining({ backgroundColor: expect.any(String) })]));
  fireEvent.press(trigger);
  fireEvent.press(screen.getByRole('menuitem', { name: 'One' }));
  expect(onValueChange).not.toHaveBeenCalled();
  view.unmount();
});
