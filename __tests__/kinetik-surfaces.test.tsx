import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AppState, Image, Modal, Platform, StyleSheet, Text, View } from 'react-native';
import * as Haptics from 'expo-haptics';

const mockAnimationControl = { finished: true };
jest.mock('react-native-reanimated', () => {
  const identity = (value: unknown) => value;
  return {
    __esModule: true,
    default: { View: require('react-native').View, Text: require('react-native').Text, ScrollView: require('react-native').ScrollView, Image: require('react-native').Image },
    Easing: { bezier: (..._points: number[]) => (value: number) => value, linear: (value: number) => value, sin: (value: number) => value, inOut: (easing: unknown) => easing },
    ReduceMotion: { Always: 'always', Never: 'never' },
    Extrapolation: { CLAMP: 'clamp' },
    useSharedValue: (value: unknown) => {
      const ref = require('react').useRef(undefined);
      if (ref.current === undefined) ref.current = { value, get() { return this.value; }, set(next: unknown) { this.value = next; } };
      return ref.current;
    },
    useFrameCallback: () => ({ setActive: jest.fn() }),
    useAnimatedRef: () => require('react').useRef(null),
    useAnimatedScrollHandler: (callback: (event: { contentOffset: { x: number } }) => void) => (event: { nativeEvent?: { contentOffset: { x: number } }; contentOffset?: { x: number } }) => callback(event.nativeEvent ?? event as { contentOffset: { x: number } }),
    useAnimatedStyle: (factory: () => object) => factory(),
    cancelAnimation: jest.fn(),
    useDerivedValue: (factory: () => unknown) => { const value = factory(); return { get: () => value }; },
    useReducedMotion: () => false,
    withTiming: (value: unknown, _config?: unknown, callback?: (finished: boolean) => void) => {
      callback?.(mockAnimationControl.finished);
      return identity(value);
    },
    withSpring: identity,
    withRepeat: identity,
    withSequence: (...values: unknown[]) => values.at(-1),
    withDelay: (_delay: number, value: unknown) => value,
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    interpolateColor: (_value: number, _input: number[], output: string[]) => output[0],
    FadeInDown: { delay: () => ({ duration: () => ({ easing: () => undefined }) }) },
  };
});
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 24, left: 0 }) }));

import { MagnetButton } from '../src/components/kinetik/magnet-button';
import { AuroraVeil } from '../src/components/kinetik/aurora-veil';
import { LiquidTabs } from '../src/components/kinetik/liquid-tabs';
import { MorphSelect } from '../src/components/kinetik/morph-select';
import { PressJelly } from '../src/components/kinetik/press-jelly';
import { Profile } from '../src/components/kinetik/screens/profile';
import { Onboarding } from '../src/components/kinetik/screens/onboarding';
import { PulseToggle } from '../src/components/kinetik/pulse-toggle';
import { StatusButton } from '../src/components/kinetik/status-button';
import { ThemeSwitch } from '../src/components/kinetik/theme-switch';
import { WordCascade } from '../src/components/kinetik/word-cascade';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { compactCount } from '../src/components/kinetik/screens/profile';
import { useHaptic, type HapticKind } from '../src/lib/kinetik/hooks/use-haptic';
import { __gestureTestManager } from './mocks/gesture-handler';
import { onboardingScenePalette } from '../src/onboarding/OnboardingScenes';
import { palettes } from '../src/ui/theme';

const wrapper = (children: React.ReactNode) => <KinetikProvider scheme="light" reduceMotion="always" haptics={false}>{children}</KinetikProvider>;
afterEach(() => { mockAnimationControl.finished = true; });

function HapticProbe() {
  const haptic = useHaptic();
  const kinds: HapticKind[] = ['tick', 'tap', 'thud', 'success', 'warning', 'error'];
  return <>{kinds.map(kind => <Text key={kind} accessibilityRole="button" onPress={() => haptic(kind)}>{kind}</Text>)}</>;
}

it('maps the semantic haptic vocabulary to the matching platform feedback', () => {
  const haptics = Haptics as jest.Mocked<typeof Haptics>;
  haptics.selectionAsync.mockClear();
  haptics.impactAsync.mockClear();
  haptics.notificationAsync.mockClear();
  render(<KinetikProvider scheme="light" reduceMotion="always" haptics><HapticProbe /></KinetikProvider>);
  for (const kind of ['tick', 'tap', 'thud', 'success', 'warning', 'error']) fireEvent.press(screen.getByText(kind));
  if (Platform.OS === 'ios' || Platform.OS === 'android') {
    expect(haptics.selectionAsync).toHaveBeenCalledTimes(1);
    expect(haptics.impactAsync).toHaveBeenNthCalledWith(1, Haptics.ImpactFeedbackStyle.Light);
    expect(haptics.impactAsync).toHaveBeenNthCalledWith(2, Haptics.ImpactFeedbackStyle.Medium);
    expect(haptics.notificationAsync).toHaveBeenNthCalledWith(1, Haptics.NotificationFeedbackType.Success);
    expect(haptics.notificationAsync).toHaveBeenNthCalledWith(2, Haptics.NotificationFeedbackType.Warning);
    expect(haptics.notificationAsync).toHaveBeenNthCalledWith(3, Haptics.NotificationFeedbackType.Error);
  } else {
    expect(haptics.selectionAsync).not.toHaveBeenCalled();
    expect(haptics.impactAsync).not.toHaveBeenCalled();
    expect(haptics.notificationAsync).not.toHaveBeenCalled();
  }
});

it('updates liquid tabs after layout, avoids duplicate changes, and honors reduced motion', () => {
  const change = jest.fn();
  const props = {
    tabs: [{ key: 'library', label: 'Library' }, { key: 'settings', label: 'Settings', icon: <Text>Gear</Text> }],
    value: 'library',
    onChange: change,
    accessibilityLabel: 'Main navigation',
    haptics: false,
  } as const;
  const view = render(wrapper(<LiquidTabs {...props} />));
  const tablist = view.UNSAFE_getByProps({ accessibilityRole: 'tablist' });
  expect(screen.getByRole('tab', { name: 'Library' }).props.accessibilityState).toEqual({ selected: true });
  expect(screen.getByRole('tab', { name: 'Settings' })).toBeOnTheScreen();
  expect(screen.getByText('Gear')).toBeOnTheScreen();
  fireEvent(tablist, 'layout', { nativeEvent: { layout: { width: 240 } } });
  fireEvent.press(screen.getByRole('tab', { name: 'Library' }));
  expect(change).not.toHaveBeenCalled();
  fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
  expect(change).toHaveBeenCalledWith('settings');
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><LiquidTabs {...props} value="settings" color="#ab1234" activeTextColor="#ffffff" /></KinetikProvider>);
  expect(screen.getByRole('tab', { name: 'Settings' }).props.accessibilityState).toEqual({ selected: true });
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><LiquidTabs {...props} value="library" /></KinetikProvider>);
  expect(screen.getByRole('tab', { name: 'Library' }).props.accessibilityState).toEqual({ selected: true });
  const empty = render(wrapper(<LiquidTabs tabs={[]} value="missing" onChange={change} />));
  fireEvent(empty.UNSAFE_getByProps({ accessibilityRole: 'tablist' }), 'layout', { nativeEvent: { layout: { width: 0 } } });
});

it('covers PressJelly variants, gesture completion, reduced motion and blocked activation', () => {
  const press = jest.fn();
  const view = render(wrapper(<PressJelly onPress={press} leading={<Text>Arrow</Text>} block size="lg" variant="ice" squish={2} accessibilityHint="Saves the file">Save</PressJelly>));
  const button = screen.getByRole('button', { name: 'Save' });
  expect(button.props.accessibilityHint).toBe('Saves the file');
  expect(button).toHaveStyle({ alignSelf: 'stretch', height: 56 });
  fireEvent(button, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(1);
  const detector = screen.getByTestId('gesture-press-jelly');
  fireEvent(detector, 'touchStart');
  fireEvent(detector, 'touchEnd', { nativeEvent: { gestureSuccess: false } });
  expect(press).toHaveBeenCalledTimes(1);
  view.rerender(<KinetikProvider scheme="dark" reduceMotion="never" haptics={false}><PressJelly onPress={press} variant="surface" disabled>Unavailable</PressJelly></KinetikProvider>);
  expect(screen.getByRole('button', { name: 'Unavailable' }).props.accessibilityState).toEqual({ disabled: true, busy: false });
  fireEvent(screen.getByRole('button', { name: 'Unavailable' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(1);
  view.rerender(<KinetikProvider scheme="dark" reduceMotion="never" haptics={false}><PressJelly onPress={press}>Available</PressJelly></KinetikProvider>);
  const available = screen.getByTestId('gesture-press-jelly');
  fireEvent(available, 'touchStart');
  fireEvent(available, 'touchEnd');
  expect(press).toHaveBeenCalledTimes(2);
  view.rerender(wrapper(<PressJelly onPress={press} loading accessibilityLabel="Loading" squish={-1}><Text>Custom content</Text></PressJelly>));
  expect(screen.getByRole('button', { name: 'Loading' }).props.accessibilityState).toEqual({ disabled: false, busy: true });
  fireEvent(screen.getByRole('button', { name: 'Loading' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(2);
  view.rerender(wrapper(<PressJelly onPress={press}><Text>Unlabeled custom child</Text></PressJelly>));
  expect(screen.UNSAFE_getByProps({ accessibilityRole: 'button' }).props.accessibilityLabel).toBeUndefined();
});

it('keeps primary button fills solid without gloss or glow across both animated button types', () => {
  const press = render(wrapper(<PressJelly flatAppearance={false}>Flat action</PressJelly>));
  const pressButton = press.getByRole('button', { name: 'Flat action' });
  expect(StyleSheet.flatten(pressButton.props.style)).not.toHaveProperty('boxShadow');
  expect(press.UNSAFE_queryAllByProps({ pointerEvents: 'none' })).toHaveLength(0);
  press.unmount();

  const magnet = render(wrapper(<MagnetButton flatAppearance={false}>Magnet action</MagnetButton>));
  const node = magnet.UNSAFE_getByProps({ accessibilityRole: 'button' });
  expect(StyleSheet.flatten(node.props.style)).not.toHaveProperty('boxShadow');
  const face = StyleSheet.flatten(magnet.getByTestId('magnet-button-face').props.style);
  expect(face).toEqual(expect.objectContaining({ backgroundColor: expect.any(String) }));
  expect(face).not.toHaveProperty('experimental_backgroundImage');
  expect(face).not.toHaveProperty('boxShadow');
});

it('renders the Aurora defaults and evaluates its shader uniforms lazily through Skia', () => {
  const view = render(wrapper(<AuroraVeil><Text>Accessible content</Text></AuroraVeil>));
  expect(screen.getByText('Accessible content')).toBeOnTheScreen();
  expect(view.UNSAFE_getByProps({ pointerEvents: 'none', importantForAccessibility: 'no-hide-descendants' }).props.accessibilityElementsHidden).toBe(true);
});

it('opens and closes MorphSelect for selected, missing, disabled and undescribed options', () => {
  const change = jest.fn();
  const options = [
    { value: 'a', label: 'Alpha', description: 'First option' },
    { value: 'b', label: 'Beta', disabled: true },
    { value: 'c', label: 'Gamma' },
  ];
  const view = render(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}><MorphSelect label="Example" options={options} value="a" onValueChange={change} testID="example-select" maxVisible={2} /></KinetikProvider>);
  const trigger = screen.getByRole('button', { name: 'Example, Alpha' });
  expect(trigger.props.accessibilityState).toEqual(expect.objectContaining({ expanded: false }));
  fireEvent.press(trigger);
  expect(screen.getByRole('menuitem', { name: 'Alpha, First option' })).toBeOnTheScreen();
  const disabled = screen.getByRole('menuitem', { name: 'Beta' });
  expect(disabled.props.accessibilityState).toEqual({ selected: false, disabled: true });
  fireEvent.press(disabled);
  expect(change).not.toHaveBeenCalled();
  fireEvent.press(screen.getByRole('menuitem', { name: 'Gamma' }));
  expect(change).toHaveBeenCalledWith('c');
  view.rerender(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}><MorphSelect label="Example" options={options} value="unknown" onValueChange={change} disabled placeholder="Choose a value" /></KinetikProvider>);
  expect(screen.getByRole('button', { name: 'Example, Choose a value' })).toBeDisabled();
  expect(screen.queryByRole('menuitem', { name: 'Gamma' })).toBeNull();
});

it('keeps an animated MorphSelect open when a close animation is interrupted and commits a completed choice', () => {
  mockAnimationControl.finished = false;
  const change = jest.fn();
  render(<KinetikProvider scheme="dark" reduceMotion="never" haptics={false}><MorphSelect label="Region" options={[{ value: 'east', label: 'East' }, { value: 'west', label: 'West' }]} value={null} onValueChange={change} /></KinetikProvider>);
  fireEvent.press(screen.getByRole('button', { name: 'Region, Choose' }));
  const menu = screen.getByRole('menuitem', { name: 'West' });
  fireEvent(screen.UNSAFE_getByType(Modal), 'requestClose');
  expect(screen.getByRole('menuitem', { name: 'West' })).toBeOnTheScreen();
  expect(change).not.toHaveBeenCalled();
  mockAnimationControl.finished = true;
  fireEvent.press(menu);
  expect(change).toHaveBeenCalledWith('west');
});


it('pages onboarding illustrations, honors skip and finish, and hides inactive content', () => {
  const finish = jest.fn();
  const skip = jest.fn();
  const firstVisual = jest.fn((active: boolean) => <Text testID="first-visual">{String(active)}</Text>);
  const secondVisual = jest.fn((active: boolean) => <Text testID="second-visual">{String(active)}</Text>);
  const slides = [
    { key: 'import', tag: 'Files', title: 'Import a document', visual: firstVisual },
    { key: 'save', title: 'Compare and save', visual: secondVisual },
  ];
  const view = render(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}><Onboarding slides={slides} onFinish={finish} onSkip={skip} /></KinetikProvider>);
  fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { width: 360, height: 720 } } });
  const scroller = view.UNSAFE_getByProps({ pagingEnabled: true });
  fireEvent(scroller, 'layout', { nativeEvent: { layout: { width: 360, height: 640 } } });
  expect(screen.getByTestId('first-visual')).toHaveTextContent('true');
  expect(screen.getByText('Files')).toBeOnTheScreen();
  expect(screen.queryByRole('text', { name: 'PDF or Word', includeHiddenElements: true })).toBeNull();
  expect(screen.getByRole('text', { name: 'Import a document' })).toBeOnTheScreen();
  expect(screen.queryByRole('text', { name: 'Compare and save' })).toBeNull();
  const initialPosition = StyleSheet.flatten(screen.getByTestId('onboarding-progress-indicator').props.style).transform[0].translateX;
  expect(scroller.findAllByProps({ testID: 'onboarding-progress' })).toHaveLength(0);
  fireEvent.press(screen.getByRole('button', { name: 'Maybe later' }));
  expect(skip).toHaveBeenCalledTimes(1);
  fireEvent(scroller, 'scroll', { nativeEvent: { contentOffset: { x: 360, y: 0 } } });
  fireEvent(scroller, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: 360, y: 0 } } });
  const progress = screen.getByRole('progressbar', { name: 'Onboarding progress' });
  expect(progress.props.accessibilityValue).toEqual({ min: 1, max: 2, now: 2 });
  expect(screen.getAllByTestId('onboarding-progress')).toHaveLength(1);
  expect(progress.parent?.props.pagingEnabled).toBeUndefined();
  expect(progress.parent?.type).toBe(View);
  expect(scroller.findAllByProps({ testID: 'onboarding-progress' })).toHaveLength(0);
  const movingIndicator = screen.getByTestId('onboarding-progress-indicator');
  const movement = StyleSheet.flatten(movingIndicator.props.style).transform[0].translateX;
  expect(movement).toBeGreaterThan(initialPosition);
  expect(screen.getByTestId('second-visual')).toHaveTextContent('true');
  expect(firstVisual).toHaveBeenCalledWith(false);
  expect(screen.queryByRole('text', { name: 'Import a document' })).toBeNull();
  expect(screen.getByRole('text', { name: 'Compare and save' })).toBeOnTheScreen();
  fireEvent(screen.getByRole('button', { name: 'Get started' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(finish).toHaveBeenCalledTimes(1);
  view.rerender(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}><Onboarding slides={slides} active={false} disabled onFinish={finish} /></KinetikProvider>);
  expect(screen.getByTestId('second-visual', { includeHiddenElements: true })).toHaveTextContent('false');
  expect(screen.queryByRole('text', { name: 'Import a document' })).toBeNull();
  expect(screen.queryByRole('text', { name: 'Compare and save' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Maybe later' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Get started' })).toBeDisabled();
});

it('guards disabled Next and finishes safely when paging ends with no slides', () => {
  const finish = jest.fn();
  let requestNext: () => void = () => undefined;
  const view = render(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}><Onboarding
    disabled
    slides={[]}
    onFinish={finish}
    renderAction={(_last, next) => { requestNext = next; return <Text>Action</Text>; }}
  /></KinetikProvider>);
  fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { width: 360, height: 640 } } });
  const scroller = view.UNSAFE_getByProps({ pagingEnabled: true });
  fireEvent(scroller, 'layout', { nativeEvent: { layout: { width: 360, height: 520 } } });
  fireEvent(scroller, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x: 0, y: 0 } } });
  expect(screen.queryByTestId('onboarding-progress')).toBeNull();
  act(() => requestNext());
  expect(finish).not.toHaveBeenCalled();
});

it('animates title-only onboarding scenes and hides offscreen titles from accessibility', () => {
  const slides = [
    { key: 'one', title: 'Title one' },
    { key: 'two', title: 'Title two' },
    { key: 'three', title: 'Title three' },
  ];
  const view = render(wrapper(<Onboarding slides={slides} onFinish={jest.fn()} />));
  fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { width: 360, height: 720 } } });
  const scroller = view.UNSAFE_getByProps({ pagingEnabled: true });
  fireEvent(scroller, 'layout', { nativeEvent: { layout: { width: 360, height: 620 } } });
  expect(screen.getByRole('text', { name: 'Title one' })).toBeOnTheScreen();
  expect(screen.queryByRole('text', { name: 'Title two' })).toBeNull();
  expect(screen.queryByRole('text', { name: 'Title three' })).toBeNull();
  const cascades = view.UNSAFE_getAllByType(WordCascade);
  expect(cascades.map((node) => node.props.text)).toEqual(['Title one', 'Title two', 'Title three']);
  expect(cascades.map((node) => [node.props.stagger, node.props.rise])).toEqual([[55, 14], [55, 14], [55, 14]]);
  for (const [x, index] of [[360, 1], [720, 2]] as const) {
    fireEvent(scroller, 'scroll', { nativeEvent: { contentOffset: { x, y: 0 } } });
    fireEvent(scroller, 'momentumScrollEnd', { nativeEvent: { contentOffset: { x, y: 0 } } });
    expect(screen.getByRole('progressbar', { name: 'Onboarding progress' }).props.accessibilityValue.now).toBe(index + 1);
    expect(screen.getByRole('text', { name: slides[index].title })).toBeOnTheScreen();
    for (const [page, slide] of slides.entries()) {
      if (page !== index) expect(screen.queryByRole('text', { name: slide.title })).toBeNull();
    }
  }
});

it('scales onboarding illustrations to measured compact slide space and keeps their panel bounded', () => {
  const native = require('react-native') as typeof import('react-native');
  const dimensionSpy = jest.spyOn(native, 'useWindowDimensions').mockReturnValue({ width: 360, height: 580, scale: 1, fontScale: 1 });
  try {
    const view = render(wrapper(<Onboarding slides={[{ key: 'compact', title: 'Compact', visual: () => <View testID="compact-visual" style={{ height: 330 }} /> }]} onFinish={jest.fn()} />));
    fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { width: 360, height: 580 } } });
    const fallbackScale = StyleSheet.flatten(screen.getByTestId('onboarding-visual-stage').props.style).transform[0].scale;
    expect(fallbackScale).toBeGreaterThanOrEqual(0.5);
    expect(fallbackScale).toBeLessThanOrEqual(0.6);
    fireEvent(view.UNSAFE_getByProps({ pagingEnabled: true }), 'layout', { nativeEvent: { layout: { width: 360, height: 340 } } });
    const stageTransforms = StyleSheet.flatten(screen.getByTestId('onboarding-visual-stage').props.style);
    const stageScale = stageTransforms.transform[0].scale;
    expect(stageScale).toBeGreaterThanOrEqual(0.5);
    expect(stageScale).toBeLessThan(1);
    expect(330 * stageScale).toBeLessThanOrEqual(232);
    expect(StyleSheet.flatten(screen.getByTestId('onboarding-progress').props.style).width).toBeGreaterThan(0);
    view.unmount();
    dimensionSpy.mockReturnValue({ width: 360, height: 680, scale: 1, fontScale: 1 });
    const fallback = render(wrapper(<Onboarding slides={[{ key: 'medium', title: 'Medium' }]} onFinish={jest.fn()} />));
    fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { width: 360, height: 680 } } });
    const mediumScale = StyleSheet.flatten(screen.getByTestId('onboarding-visual-stage').props.style).transform[0].scale;
    expect(mediumScale).toBeGreaterThan(fallbackScale);
    expect(mediumScale).toBeLessThanOrEqual(0.8);
    fallback.unmount();
  } finally {
    dimensionSpy.mockRestore();
  }
});

function luminance(hex: string): number {
  const rgb = hex.replace('#', '').match(/.{2}/g)?.map((part) => parseInt(part, 16) / 255) ?? [];
  const linear = rgb.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * (linear[0] ?? 0) + 0.7152 * (linear[1] ?? 0) + 0.0722 * (linear[2] ?? 0);
}

it('gives onboarding scene details at least 3:1 non-text contrast in both themes', () => {
  const scene = onboardingScenePalette(palettes.dark);
  const ratio = (a: string, b: string) => {
    const [high, low] = [luminance(a), luminance(b)].sort((left, right) => right - left);
    return (high + 0.05) / (low + 0.05);
  };
  expect(ratio(scene.background, scene.raised)).toBeGreaterThanOrEqual(3);
  expect(ratio(scene.surface, scene.raised)).toBeGreaterThanOrEqual(3);
  expect(ratio(scene.raised, scene.border)).toBeGreaterThanOrEqual(3);
  expect(ratio(scene.accent, scene.onAccent)).toBeGreaterThanOrEqual(3);
  const lightScene = onboardingScenePalette(palettes.light);
  expect(ratio(lightScene.background, lightScene.border)).toBeGreaterThanOrEqual(3);
  expect(ratio(lightScene.raised, lightScene.border)).toBeGreaterThanOrEqual(3);
  expect(ratio(lightScene.background, lightScene.accent)).toBeGreaterThanOrEqual(3);
  expect(ratio(lightScene.accent, lightScene.onAccent)).toBeGreaterThanOrEqual(3);
  expect(lightScene.surface).toBe(palettes.light.surface);
  expect(lightScene.accent).toBe(palettes.light.accent);
});

it('measures StatusButton labels and gates idle, loading, success and error actions', () => {
  const press = jest.fn();
  const view = render(wrapper(<StatusButton status="idle" onPress={press}>Translate</StatusButton>));
  const measureRows = view.UNSAFE_getAllByType(require('react-native').View).filter((node: { props: { onLayout?: unknown } }) => typeof node.props.onLayout === 'function');
  expect(measureRows.length).toBeGreaterThanOrEqual(3);
  fireEvent(measureRows[0], 'layout', { nativeEvent: { layout: { width: 98 } } });
  fireEvent(measureRows[0], 'layout', { nativeEvent: { layout: { width: 98 } } });
  fireEvent(measureRows[1], 'layout', { nativeEvent: { layout: { width: 70 } } });
  fireEvent(measureRows[2], 'layout', { nativeEvent: { layout: { width: 90 } } });
  const button = screen.getByRole('button', { name: 'Translate' });
  fireEvent(button, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(1);
  const gesture = screen.getByTestId('gesture-status-button');
  fireEvent(gesture, 'touchStart');
  fireEvent(gesture, 'touchEnd', { nativeEvent: { gestureSuccess: false } });
  expect(press).toHaveBeenCalledTimes(1);
  view.rerender(wrapper(<StatusButton status="loading" loadingLabel="Working" onPress={press}>Translate</StatusButton>));
  expect(screen.getByRole('button', { name: 'Translate, Working' }).props.accessibilityState).toEqual({ disabled: true, busy: true });
  fireEvent(screen.getByRole('button', { name: 'Translate, Working' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><StatusButton status="success" successLabel="Saved" onPress={press}>Translate</StatusButton></KinetikProvider>);
  expect(screen.getByRole('button', { name: 'Saved' })).toBeDisabled();
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><StatusButton status="error" errorLabel="Retry" onPress={press}>Translate</StatusButton></KinetikProvider>);
  expect(screen.getByRole('button', { name: 'Retry' })).toBeEnabled();
  fireEvent(screen.getByTestId('gesture-status-button'), 'touchStart');
  fireEvent(screen.getByTestId('gesture-status-button'), 'touchEnd');
  expect(press).toHaveBeenCalledTimes(2);
  view.rerender(wrapper(<StatusButton status="error" errorLabel="Retry" disabled onPress={press}>Translate</StatusButton>));
  expect(screen.getByRole('button', { name: 'Retry' })).toBeDisabled();
  fireEvent(screen.getByRole('button', { name: 'Retry' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(2);
  view.rerender(wrapper(<StatusButton status="idle" onPress={press}>Translate</StatusButton>));
  view.rerender(wrapper(<StatusButton status="error" errorLabel="Try again" onPress={press}>Translate</StatusButton>));
});

it('switches theme accessibly with and without a visible label', () => {
  const change = jest.fn();
  const view = render(wrapper(<ThemeSwitch value="light" label="Dark appearance" onValueChange={change} />));
  const labeled = screen.getByRole('switch', { name: 'Dark appearance' });
  expect(labeled.props.accessibilityState).toEqual({ checked: false });
  fireEvent.press(labeled);
  expect(change).toHaveBeenCalledWith('dark');
  view.rerender(wrapper(<ThemeSwitch value="dark" accessibilityLabel="Appearance" onValueChange={change} />));
  expect(screen.getByRole('switch', { name: 'Appearance' })).toHaveProp('accessibilityState', { checked: true });
  view.rerender(wrapper(<ThemeSwitch value="dark" label="Dark appearance" onValueChange={change} />));
  expect(screen.getByText('On')).toBeOnTheScreen();
  fireEvent.press(screen.getByRole('switch', { name: 'Dark appearance' }));
  expect(change).toHaveBeenLastCalledWith('light');
  fireEvent(screen.getByRole('switch', { name: 'Dark appearance' }), 'pressIn');
  fireEvent(screen.getByRole('switch', { name: 'Dark appearance' }), 'pressOut');
});

it('cascades multiline text, completes animated sequences and skips motion when requested', () => {
  const done = jest.fn();
  const originalState = AppState.currentState;
  Object.defineProperty(AppState, 'currentState', { configurable: true, writable: true, value: 'active' });
  const view = render(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><WordCascade text={'One  word\nnext'} delay={4} stagger={2} wordDuration={9} rise={12} onDone={done} /></KinetikProvider>);
  expect(screen.getByRole('text', { name: 'One  word\nnext' })).toBeOnTheScreen();
  expect(done).toHaveBeenCalled();
  const completedCount = done.mock.calls.length;
  mockAnimationControl.finished = false;
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><WordCascade text="Interrupted sequence" onDone={done} /></KinetikProvider>);
  expect(done).toHaveBeenCalledTimes(completedCount);
  mockAnimationControl.finished = true;
  view.rerender(<KinetikProvider scheme="light" reduceMotion="always" haptics={false}><WordCascade text="Static words" animateOnMount={false} onDone={done} /></KinetikProvider>);
  expect(screen.getByRole('text', { name: 'Static words' })).toBeOnTheScreen();
  Object.defineProperty(AppState, 'currentState', { configurable: true, writable: true, value: originalState });
});

it('supports pulse toggle labels, hints, active styling, and disabled activation', () => {
  const change = jest.fn();
  const view = render(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><PulseToggle value={false} label="Follow system" description="Use device appearance" onValueChange={change} size="sm" activeColor="#cc3344" /></KinetikProvider>);
  const control = screen.getByRole('switch', { name: 'Follow system' });
  expect(control.props.accessibilityHint).toBe('Use device appearance');
  fireEvent(control, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(change).toHaveBeenCalledWith(true);
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><PulseToggle value={true} label="Follow system" onValueChange={change} size="sm" activeColor="#cc3344" /></KinetikProvider>);
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><PulseToggle value={true} label="Follow system" onValueChange={change} size="sm" activeColor="#cc3344" /></KinetikProvider>);
  fireEvent(screen.getByTestId('gesture-pulse-toggle'), 'touchStart');
  fireEvent(screen.getByTestId('gesture-pulse-toggle'), 'touchEnd');
  expect(change).toHaveBeenLastCalledWith(false);
  fireEvent(screen.getByTestId('gesture-pulse-toggle'), 'touchStart');
  fireEvent(screen.getByTestId('gesture-pulse-toggle'), 'touchEnd', { nativeEvent: { gestureSuccess: false } });
  fireEvent(control, 'accessibilityAction', { nativeEvent: { actionName: 'escape' } });
  expect(change.mock.calls).toEqual([[true], [false]]);
  view.rerender(wrapper(<PulseToggle value={true} accessibilityLabel="Reduced motion" disabled onValueChange={change} haptics={false} />));
  const disabled = screen.getByRole('switch', { name: 'Reduced motion' });
  expect(disabled.props.accessibilityState).toEqual({ checked: true, disabled: true });
  fireEvent.press(disabled);
  expect(change.mock.calls).toEqual([[true], [false]]);
});

it('keeps Profile rows, linked plan, stats, and settings toggle usable without an account', () => {
  const edit = jest.fn();
  const plan = jest.fn();
  const row = jest.fn();
  const toggle = jest.fn();
  const signOut = jest.fn();
  const view = render(wrapper(<Profile
    user={{ name: 'Ada Lovelace', handle: 'Translator' }}
    stats={[{ label: 'Documents', value: 12_000 }, { label: 'Segments', value: 1_200_000 }]}
    plan={{ title: 'Offline ready', detail: '3 models', onPress: plan }}
    onEdit={edit}
    onSignOut={signOut}
    sections={[{ title: 'Preferences', rows: [
      { key: 'language', label: 'Language', detail: 'English', icon: <Text>Globe</Text>, onPress: row },
      { key: 'system', label: 'Follow system', toggle: { value: false, onValueChange: toggle } },
    ] }]}
  />));
  expect(screen.getByRole('header', { name: 'Ada Lovelace' })).toBeOnTheScreen();
  expect(screen.getByLabelText('12k Documents')).toBeOnTheScreen();
  expect(screen.getByLabelText('1.2M Segments')).toBeOnTheScreen();
  fireEvent.press(screen.getByRole('button', { name: 'Edit profile' }));
  fireEvent.press(screen.getByRole('button', { name: 'Offline ready, 3 models' }));
  const planCta = view.UNSAFE_getByProps({ accessibilityLabel: 'Offline ready, 3 models' });
  const planStyle = StyleSheet.flatten(planCta.props.style);
  expect(planStyle.backgroundColor).toBeTruthy();
  expect(planStyle).not.toHaveProperty('backgroundImage');
  const languageRow = screen.getByRole('button', { name: 'Language' });
  fireEvent(languageRow, 'pressIn');
  fireEvent(languageRow, 'responderGrant', { persist: jest.fn(), nativeEvent: { identifier: 1, locationX: 1, locationY: 1, pageX: 1, pageY: 1, target: 1, timestamp: Date.now() } });
  expect(StyleSheet.flatten(screen.getByRole('button', { name: 'Language' }).props.style)).toHaveProperty('backgroundColor');
  fireEvent(languageRow, 'pressOut');
  fireEvent.press(languageRow);
  fireEvent.press(screen.getByRole('switch', { name: 'Follow system' }));
  fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
  expect(edit).toHaveBeenCalledTimes(1);
  expect(plan).toHaveBeenCalledTimes(1);
  expect(row).toHaveBeenCalledTimes(1);
  expect(toggle).toHaveBeenCalledWith(true);
  expect(signOut).toHaveBeenCalledTimes(1);

  view.rerender(wrapper(<Profile sections={[{ rows: [{ key: 'fixed', label: 'Static option' }] }]} />));
  expect(screen.getByText('Static option')).toBeOnTheScreen();
  expect(screen.queryByText('Ada Lovelace')).toBeNull();
});

it('formats profile statistics and renders avatar photos and a noninteractive free plan', () => {
  expect(compactCount(-42)).toBe('-42');
  expect(compactCount(9999.7)).toBe('10,000');
  expect(compactCount(10_000)).toBe('10k');
  expect(compactCount(99_999)).toBe('100k');
  expect(compactCount(100_000)).toBe('100k');
  expect(compactCount(1_000_000)).toBe('1M');
  const view = render(wrapper(<Profile user={{ name: 'Grace Hopper', avatar: { uri: 'file:///avatar.png' } }} plan={{ title: 'Free' }} />));
  expect(screen.UNSAFE_getAllByType(Image)).toHaveLength(1);
  expect(screen.getByText('Free')).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Free' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Personal info' })).toBeDisabled();
  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><Profile user={{ name: 'Grace Hopper', avatar: { uri: 'file:///avatar.png' } }} plan={{ title: 'Free' }} /></KinetikProvider>);
  fireEvent(screen.getByRole('button', { name: 'Personal info' }), 'pressIn');
  fireEvent(screen.getByRole('button', { name: 'Personal info' }), 'pressOut');
});

it('uses the magnet button accessibility action and ignores disabled or out-of-field gestures', () => {
  const press = jest.fn();
  const view = render(wrapper(<MagnetButton onPress={press} accessibilityHint="Continue to export" haptics={false}>Continue</MagnetButton>));
  const button = screen.getByRole('button', { name: 'Continue' });
  expect(button.props.accessibilityHint).toBe('Continue to export');
  fireEvent(button, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(1);
  view.rerender(wrapper(<MagnetButton onPress={press} disabled>Continue</MagnetButton>));
  fireEvent(screen.getByRole('button', { name: 'Continue' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(1);
});

it('renders a surface magnet button with custom React content and a supplied accessible name', () => {
  const press = jest.fn();
  render(wrapper(<MagnetButton variant="surface" accessibilityLabel="Icon action" onPress={press} haptics={false}><Text>Document icon</Text></MagnetButton>));
  const control = screen.getByRole('button', { name: 'Icon action' });
  expect(screen.getByText('Document icon')).toBeOnTheScreen();
  fireEvent(control, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
  expect(press).toHaveBeenCalledTimes(1);
});

it('captures a magnet drag inside its field and releases without pressing outside its bounds', () => {
  const press = jest.fn();
  const view = render(wrapper(<MagnetButton onPress={press} haptics={false}>Drag target</MagnetButton>));
  const detector = screen.getByTestId('gesture-magnet-button');
  const button = screen.getByRole('button', { name: 'Drag target' });
  fireEvent(button, 'layout', { nativeEvent: { layout: { x: 100, y: 100, width: 80, height: 52 } } });
  fireEvent(detector, 'touchStart');
  fireEvent(detector, 'touchEnd');
  expect(press).not.toHaveBeenCalled();

  view.rerender(<KinetikProvider scheme="light" reduceMotion="never" haptics={false}><MagnetButton onPress={press} haptics={false}>Drag target</MagnetButton></KinetikProvider>);
  const inBounds = screen.getByRole('button', { name: 'Drag target' });
  fireEvent(inBounds, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 120, height: 52 } } });
  fireEvent(screen.getByTestId('gesture-magnet-button'), 'touchStart');
  fireEvent(screen.getByTestId('gesture-magnet-button'), 'touchMove');
  fireEvent(screen.getByTestId('gesture-magnet-button'), 'touchEnd');
  expect(press).toHaveBeenCalledTimes(1);
});

it('ignores an unmeasured button, activates only when a drag enters, and clears capture on exit', () => {
  const press = jest.fn();
  render(wrapper(<MagnetButton onPress={press} haptics={false}>Measured target</MagnetButton>));
  const target = screen.getByRole('button', { name: 'Measured target' });
  const detector = screen.getByTestId('gesture-magnet-button');
  __gestureTestManager.activate.mockClear();
  fireEvent(detector, 'touchStart', { nativeEvent: { allTouches: [{ x: 24, y: 24 }] } });
  expect(__gestureTestManager.activate).not.toHaveBeenCalled();

  fireEvent(target, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 120, height: 52 } } });
  fireEvent(detector, 'touchStart', { nativeEvent: { allTouches: [{ x: 200, y: 200 }] } });
  expect(__gestureTestManager.activate).not.toHaveBeenCalled();
  fireEvent(detector, 'touchMove', { nativeEvent: { allTouches: [{ x: 24, y: 24 }] } });
  expect(__gestureTestManager.activate).toHaveBeenCalledTimes(1);
  fireEvent(detector, 'touchMove', { nativeEvent: { allTouches: [{ x: 200, y: 200 }] } });
  fireEvent(detector, 'touchEnd', { nativeEvent: { allTouches: [{ x: 200, y: 200 }] } });
  expect(press).not.toHaveBeenCalled();
  fireEvent(target, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 120, height: 52 } } });
  fireEvent(detector, 'touchStart', { nativeEvent: { allTouches: [{ x: 24, y: 24 }] } });
  fireEvent(detector, 'touchCancel');
  expect(press).not.toHaveBeenCalled();
});

it('renders custom magnet content without inventing an accessible name', () => {
  render(wrapper(<MagnetButton haptics={false}><Text>Icon only</Text></MagnetButton>));
  const button = screen.UNSAFE_getByProps({ accessibilityRole: 'button' });
  expect(button.props.accessibilityLabel).toBeUndefined();
});

it('uses a stable accessible name while loading without a separate loading caption', () => {
  const view = render(wrapper(<StatusButton status="loading" loadingLabel="">Translate</StatusButton>));
  expect(screen.getByRole('button', { name: 'Translate' }).props.accessibilityState.busy).toBe(true);
  view.rerender(wrapper(<StatusButton status="loading" loadingLabel="" accessibilityLabel="Translate document">Translate</StatusButton>));
  expect(screen.getByRole('button', { name: 'Translate document' })).toBeOnTheScreen();
});
