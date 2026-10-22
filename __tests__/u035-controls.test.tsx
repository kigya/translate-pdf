import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { Dimensions, Modal, ScrollView, StyleSheet, Text } from 'react-native';

let mockMeasureRect = { x: 18, y: 300, width: 190, height: 52 };

jest.mock('react-native/Libraries/Components/Pressable/Pressable', () => {
  const ReactRuntime = require('react');
  const actual = jest.requireActual('react-native/Libraries/Components/Pressable/Pressable');
  const PressableMock = ReactRuntime.forwardRef((props: object, ref: React.Ref<unknown>) => {
    ReactRuntime.useImperativeHandle(ref, () => ({
      measureInWindow: (callback: (x: number, y: number, width: number, height: number) => void) =>
        callback(mockMeasureRect.x, mockMeasureRect.y, mockMeasureRect.width, mockMeasureRect.height),
    }), []);
    return ReactRuntime.createElement(actual.default, { ...props, ref: undefined });
  });
  return { ...actual, default: PressableMock };
});

jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  const identity = (value: unknown) => value;
  return {
    __esModule: true,
    default: { View: RN.View, Text: RN.Text, ScrollView: RN.ScrollView, Image: RN.Image },
    Easing: { linear: identity, bezier: () => identity, sin: identity, inOut: identity },
    ReduceMotion: { Always: 'always', Never: 'never' },
    useSharedValue: (value: unknown) => {
      const ref = ReactRuntime.useRef(undefined);
      if (ref.current === undefined) ref.current = { value, get() { return this.value; }, set(next: unknown) { this.value = next; } };
      return ref.current;
    },
    useAnimatedStyle: (factory: () => object) => factory(),
    useAnimatedRef: () => ReactRuntime.useRef(null),
    useAnimatedScrollHandler: (callback: (event: { contentOffset: { x: number } }) => void) =>
      (event: { nativeEvent?: { contentOffset: { x: number } }; contentOffset?: { x: number } }) => callback(event.nativeEvent ?? event as { contentOffset: { x: number } }),
    useDerivedValue: (factory: () => unknown) => ({ get: factory }),
    withTiming: (value: unknown, _config?: unknown, callback?: (finished: boolean) => void) => { callback?.(true); return value; },
    withSpring: identity,
    withDelay: (_delay: number, value: unknown) => value,
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    interpolateColor: (_value: number, _input: number[], output: string[]) => output[0],
    cancelAnimation: jest.fn(),
  };
});

jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }) }));

import { LiquidTabs } from '../src/components/kinetik/liquid-tabs';
import { MagnetButton } from '../src/components/kinetik/magnet-button';
import { MorphSelect } from '../src/components/kinetik/morph-select';
import { PopDialog } from '../src/components/kinetik/pop-dialog';
import { Onboarding } from '../src/components/kinetik/screens/onboarding';
import { WordCascade as WordCascadeComponent } from '../src/components/kinetik/word-cascade';
import { WordCascade } from '../src/components/kinetik/word-cascade';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';

const wrap = (children: React.ReactNode, reduceMotion: 'always' | 'never' = 'never') =>
  <KinetikProvider scheme="light" reduceMotion={reduceMotion} haptics={false}>{children}</KinetikProvider>;

describe('U035 Kinetik controls', () => {
  test('activates onboarding title only after the pager settles and keeps action width and typography consistent', () => {
    const view = render(wrap(
      <Onboarding slides={[
        { key: 'one', title: 'Import documents', visual: (active) => <Text>{String(active)}</Text> },
        { key: 'two', title: 'Choose a translator' },
        { key: 'three', title: 'Save the result' },
      ]} onFinish={jest.fn()} onSkip={jest.fn()} />,
    ));
    fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 780 } } });
    const pager = view.UNSAFE_getByProps({ pagingEnabled: true });
    fireEvent(pager, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 650 } } });

    const activeTitles = () => view.UNSAFE_getAllByType(WordCascadeComponent).map((node) => node.props.active);
    expect(activeTitles()).toEqual([true, false, false]);
    fireEvent(screen.getByRole('button', { name: 'Continue' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
    expect(activeTitles()).toEqual([true, false, false]);
    fireEvent(pager, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: 360, y: 0 } } });
    expect(activeTitles()).toEqual([false, true, false]);

    const magnet = render(wrap(<MagnetButton size="lg" block field={12}>Next</MagnetButton>));
    const button = screen.getByRole('button', { name: 'Next' });
    expect(StyleSheet.flatten(button.props.style)).toEqual(expect.objectContaining({ height: 56, alignSelf: 'stretch' }));
    expect(StyleSheet.flatten(screen.getByTestId('magnet-button-face').props.style)).toEqual(expect.objectContaining({ backgroundColor: expect.any(String) }));
    const jellyLabel = StyleSheet.flatten(view.getByText('Continue').props.style);
    expect(screen.getByText('Next')).toHaveStyle({
      fontSize: jellyLabel.fontSize,
      fontWeight: jellyLabel.fontWeight,
      letterSpacing: jellyLabel.letterSpacing,
    });
    expect(magnet).toBeTruthy();
  });

  test('settles a drag with no momentum only at an exact page boundary', () => {
    const slides = [
      { key: 'one', title: 'Import documents' },
      { key: 'two', title: 'Choose a translator' },
      { key: 'three', title: 'Save the result' },
    ];
    const mountPager = () => {
      const view = render(wrap(<Onboarding slides={slides} onFinish={jest.fn()} />));
      fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 780 } } });
      const pager = view.UNSAFE_getByProps({ pagingEnabled: true });
      const activeTitles = () => view.UNSAFE_getAllByType(WordCascadeComponent).map((node) => node.props.active);
      return { view, pager, activeTitles };
    };

    const immediate = mountPager();
    fireEvent(immediate.pager, 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 360, y: 0 }, velocity: { x: 0, y: 0 } } });
    expect(immediate.activeTitles()).toEqual([false, true, false]);
    immediate.view.unmount();

    const moving = mountPager();
    fireEvent(moving.pager, 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 360, y: 0 }, velocity: { x: 0.4, y: 0 } } });
    expect(moving.activeTitles()).toEqual([true, false, false]);
    moving.view.unmount();

    const betweenPages = mountPager();
    fireEvent(betweenPages.pager, 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 350, y: 0 }, velocity: { x: 0, y: 0 } } });
    expect(betweenPages.activeTitles()).toEqual([true, false, false]);
    betweenPages.view.unmount();

    const velocityUnknown = mountPager();
    fireEvent(velocityUnknown.pager, 'scrollEndDrag', { nativeEvent: { contentOffset: { x: 360, y: 0 } } });
    expect(velocityUnknown.activeTitles()).toEqual([true, false, false]);
    velocityUnknown.view.unmount();
  });

  test('MorphSelect keeps a compact trigger label accessible by its full name and bounds a wider menu', () => {
    mockMeasureRect = { x: 18, y: 300, width: 190, height: 52 };
    const view = render(wrap(<MorphSelect
      label="Source language"
      closeLabel="Закрыть список"
      value="ru"
      minMenuWidth={280}
      options={[{ value: 'ru', label: 'Russian Federation', compactLabel: 'Русский' }, { value: 'zh', label: 'Simplified Chinese language' }]}
      onValueChange={jest.fn()}
    />));

    expect(screen.getByRole('button', { name: 'Source language, Russian Federation' })).toBeOnTheScreen();
    expect(screen.getByText('Русский')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Source language, Russian Federation' }));
    const menu = view.UNSAFE_getByProps({ accessibilityRole: 'list' });
    const menuFrame = StyleSheet.flatten(menu.props.style);
    expect(menuFrame.width).toBe(280);
    expect(menuFrame.width).toBeLessThanOrEqual(Dimensions.get('window').width - 24);
    const longOption = screen.getByRole('menuitem', { name: 'Simplified Chinese language' });
    expect(longOption.props.accessibilityLabel).toBe('Simplified Chinese language');
    expect(longOption.findAllByType(Text).every((text) => text.props.numberOfLines === undefined)).toBe(true);
    expect(screen.getByRole('button', { name: 'Закрыть список' })).toBeOnTheScreen();
    const firstOption = screen.getByRole('menuitem', { name: 'Russian Federation' });
    act(() => firstOption.props.onLayout({ nativeEvent: { layout: { x: 0, y: 0, width: 250, height: 84 } } }));
    expect(StyleSheet.flatten(view.UNSAFE_getByProps({ testID: 'morph-select-highlight' }).props.style)).toEqual(expect.objectContaining({ height: 84 }));
    const changed = wrap(<MorphSelect
      label="Source language"
      closeLabel="Закрыть список"
      value="zh"
      minMenuWidth={280}
      options={[{ value: 'ru', label: 'Russian Federation', compactLabel: 'Русский' }, { value: 'zh', label: 'Simplified Chinese language' }]}
      onValueChange={jest.fn()}
    />);
    view.rerender(changed);
    // A second render lets the static Reanimated test mock expose the shared-value update.
    view.rerender(changed);
    expect(screen.getByRole('menuitem', { name: 'Simplified Chinese language' }).props.accessibilityState).toEqual(expect.objectContaining({ selected: true }));
    fireEvent(screen.UNSAFE_getByType(Modal), 'requestClose');
  });

  test('LiquidTabs can hide visible labels while retaining each full accessible tab name', () => {
    const view = render(wrap(<LiquidTabs iconOnly tabs={[
      { key: 'library', label: 'Document library', icon: <Text>Book</Text> },
      { key: 'settings', label: 'Application settings', icon: <Text>Gear</Text> },
    ]} value="library" onChange={jest.fn()} />));

    expect(screen.queryByText('Document library')).toBeNull();
    expect(screen.getByRole('tab', { name: 'Document library' })).toHaveProp('accessibilityState', { selected: true });
    expect(screen.getByRole('tab', { name: 'Application settings' })).toBeOnTheScreen();
    expect(screen.getByText('Book')).toBeOnTheScreen();
    expect(view).toBeTruthy();
  });

  test('PopDialog stacks wrapped actions without truncation and keeps long copy scrollable', () => {
    const message = 'A long explanation. '.repeat(48);
    render(wrap(<PopDialog visible title="A long confirmation" message={message} onClose={jest.fn()} actions={[
      { label: 'Keep the original document and continue reading', onPress: jest.fn() },
      { label: 'Cancel this operation', onPress: jest.fn() },
    ]} />));

    const actionText = screen.getByText('Keep the original document and continue reading');
    expect(actionText.props.numberOfLines).toBeUndefined();
    fireEvent(actionText, 'textLayout', { nativeEvent: { lines: [{ text: 'Keep the original document' }, { text: 'and continue reading' }] } });
    expect(StyleSheet.flatten(screen.getByTestId('dialog-actions').props.style)).toEqual(expect.objectContaining({ flexDirection: 'column' }));
    expect(screen.getByText(message)).toBeOnTheScreen();
    expect(screen.UNSAFE_getByType(ScrollView).props.style).toEqual(expect.objectContaining({ maxHeight: expect.any(Number) }));
  });

  test('WordCascade renders inactive titles hidden, then reveals them after activation or reduced motion', () => {
    const view = render(wrap(<WordCascade text="A fully accessible title" active={false} />, 'never'));
    const word = view.UNSAFE_getAllByType(Text).find((node) => node.props.children?.[0] === 'A');
    expect(word).toBeDefined();
    expect(StyleSheet.flatten(word?.props.style)).toEqual(expect.objectContaining({ opacity: 0 }));
    view.rerender(wrap(<WordCascade text="A fully accessible title" active />, 'never'));
    expect(screen.getByRole('text', { name: 'A fully accessible title' })).toBeOnTheScreen();
  });
});

