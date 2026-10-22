import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { AccessibilityInfo, Text, View } from 'react-native';

jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  const identity = (value: unknown) => value;
  const useSharedValue = (initial: unknown) => {
    const ref = ReactRuntime.useRef(undefined);
    if (ref.current === undefined) ref.current = { value: initial, get() { return this.value; }, set(next: unknown) { this.value = next; } };
    return ref.current;
  };
  return {
    __esModule: true,
    default: { View: RN.View, Text: RN.Text },
    Easing: { bezier: () => identity, linear: identity, inOut: identity, sin: identity },
    ReduceMotion: { Always: 'always', Never: 'never' },
    useSharedValue,
    useAnimatedStyle: (factory: () => object) => factory(),
    withSpring: identity,
    withTiming: identity,
    withDelay: (_delay: number, value: unknown) => value,
    withSequence: (...values: unknown[]) => values.at(-1),
    interpolateColor: (_value: number, _input: number[], output: string[]) => output[0],
    getUseOfValueInStyleWarning: () => '',
  };
});

// The visual component contract is the target figure; frame-by-frame counting
// is covered by the dedicated hook tests and is not a native-renderer concern.
jest.mock('../src/lib/kinetik/hooks/use-count-up', () => ({ useCountUp: (target: number) => target }));

import { EmptyState } from '../src/components/kinetik/empty-state';
import { ReadMore } from '../src/components/kinetik/read-more';
import { SearchMorph } from '../src/components/kinetik/search-morph';
import { SwatchPicker, isLight } from '../src/components/kinetik/swatch-picker';
import { TipBubble } from '../src/components/kinetik/tip-bubble';
import { Timeline } from '../src/components/kinetik/timeline';
import { UsageMeter } from '../src/components/kinetik/usage-meter';
import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';

const wrap = (children: React.ReactNode, reduceMotion: 'always' | 'never' = 'always') => (
  <KinetikProvider scheme="light" reduceMotion={reduceMotion} haptics={false}>{children}</KinetikProvider>
);

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

describe('U035 display controls: SwatchPicker', () => {
  it('exposes a named radio group, changes only to a different swatch, and selects readable check ink', () => {
    const onValueChange = jest.fn();
    const view = render(wrap(
      <SwatchPicker
        accessibilityLabel="Document colour"
        value="#ffffff"
        swatches={[{ value: '#ffffff', label: 'Snow' }, { value: '#000000', label: 'Ink' }, { value: 'red', label: 'Named red' }]}
        onValueChange={onValueChange}
      />,
    ));
    const group = view.UNSAFE_getByProps({ accessibilityRole: 'radiogroup' });
    expect(group.props.accessibilityLabel).toBe('Document colour');
    expect(screen.getByRole('radio', { name: 'Snow' }).props.accessibilityState).toEqual({ checked: true });
    expect(screen.getByRole('radio', { name: 'Ink' }).props.accessibilityState).toEqual({ checked: false });
    fireEvent.press(screen.getByRole('radio', { name: 'Snow' }));
    expect(onValueChange).not.toHaveBeenCalled();
    fireEvent.press(screen.getByRole('radio', { name: 'Ink' }));
    expect(onValueChange).toHaveBeenCalledWith('#000000');
    expect(isLight('#ffffff')).toBe(true);
    expect(isLight('#000000')).toBe(false);
    expect(isLight('red')).toBe(false);
    expect(isLight('#fff')).toBe(false);
  });

  it('measures swatches without changing the selected value and snaps in reduced motion', () => {
    const view = render(wrap(
      <SwatchPicker accessibilityLabel="Label" value="#123456" haptics={false}
        swatches={[{ value: '#123456', label: 'Blue' }]} onValueChange={jest.fn()} />,
    ));
    const swatch = screen.getByRole('radio', { name: 'Blue' });
    fireEvent(swatch, 'layout', { nativeEvent: { layout: { x: 8, y: 4, width: 44, height: 44 } } });
    expect(swatch.props.accessibilityState).toEqual({ checked: true });
    expect(view.UNSAFE_getByProps({ accessibilityRole: 'radiogroup' }).props.accessibilityLabel).toBe('Label');
  });

  it('updates the ring from measured positions and animates a newly selected swatch', () => {
    const colors = [{ value: '#123456', label: 'Blue' }, { value: '#abcdef', label: 'Sky' }];
    const onValueChange = jest.fn();
    const view = render(<KinetikProvider scheme="dark" reduceMotion="never" haptics={false}>
      <SwatchPicker accessibilityLabel="Accent" value="#123456" swatches={colors} onValueChange={onValueChange} />
    </KinetikProvider>);
    const blue = screen.getByRole('radio', { name: 'Blue' });
    fireEvent(blue, 'layout', { nativeEvent: { layout: { x: 2, y: 3, width: 44, height: 44 } } });
    fireEvent(blue, 'layout', { nativeEvent: { layout: { x: 2, y: 3, width: 44, height: 44 } } });
    const sky = screen.getByRole('radio', { name: 'Sky' });
    fireEvent(sky, 'layout', { nativeEvent: { layout: { x: 50, y: 3, width: 44, height: 44 } } });
    fireEvent.press(sky);
    expect(onValueChange).toHaveBeenCalledWith('#abcdef');
    view.rerender(<KinetikProvider scheme="dark" reduceMotion="never" haptics={false}>
      <SwatchPicker accessibilityLabel="Accent" value="#abcdef" swatches={colors} onValueChange={onValueChange} />
    </KinetikProvider>);
    expect(screen.getByRole('radio', { name: 'Sky' }).props.accessibilityState).toEqual({ checked: true });
  });
});

describe('U035 display controls: SearchMorph', () => {
  it('opens, focuses after the morph, clears in place, submits, and cancels with query cleanup', () => {
    jest.useFakeTimers();
    const focus = jest.spyOn(require('react-native').TextInput.prototype, 'focus').mockImplementation(jest.fn());
    const blur = jest.spyOn(require('react-native').TextInput.prototype, 'blur').mockImplementation(jest.fn());
    const onChangeText = jest.fn();
    const onSubmit = jest.fn();
    const onOpenChange = jest.fn();
    const view = render(wrap(<SearchMorph value="needle" onChangeText={onChangeText} onSubmit={onSubmit}
      onOpenChange={onOpenChange} placeholder="Find documents" clearLabel="Clear query"
      cancelLabel="Close search"><Text>Matching result</Text></SearchMorph>));
    expect(screen.queryByText('Matching result')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Find documents' }));
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(screen.getByText('Matching result')).toBeOnTheScreen();
    const bar = view.UNSAFE_getAllByType(View).find((node) => typeof node.props.onLayout === 'function');
    fireEvent(bar!, 'layout', { nativeEvent: { layout: { width: 320, height: 48, x: 0, y: 0 } } });
    fireEvent(screen.getByRole('button', { name: 'Close search' }), 'layout', { nativeEvent: { layout: { width: 68, height: 48, x: 0, y: 0 } } });
    fireEvent(screen.getByRole('button', { name: 'Close search' }), 'pressIn');
    fireEvent(screen.getByRole('button', { name: 'Close search' }), 'pressOut');
    act(() => { jest.advanceTimersByTime(0); });
    expect(focus).toHaveBeenCalled();
    const input = screen.getByLabelText('Find documents');
    expect(input.props.selectionColor).toBeTruthy();
    expect(input.props.cursorColor).toBe(input.props.selectionColor);
    fireEvent(input, 'submitEditing');
    expect(onSubmit).toHaveBeenCalledWith('needle');
    fireEvent.press(screen.getByRole('button', { name: 'Clear query' }));
    expect(onChangeText).toHaveBeenLastCalledWith('');
    expect(focus).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Matching result')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Close search' }));
    expect(onChangeText).toHaveBeenLastCalledWith('');
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(screen.queryByText('Matching result')).toBeNull();
    expect(blur).toHaveBeenCalled();
    view.unmount();
  });

  it('respects controlled openness and cancels a delayed focus when unmounted', () => {
    jest.useFakeTimers();
    const onOpenChange = jest.fn();
    const view = render(wrap(<SearchMorph value="" onChangeText={jest.fn()} open={false}
      onOpenChange={onOpenChange} /> , 'never'));
    fireEvent.press(screen.getByRole('button', { name: 'Search' }));
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(screen.getByRole('button', { name: 'Search' })).toBeOnTheScreen();
    expect(screen.queryByText('matching results')).toBeNull();
    view.rerender(wrap(<SearchMorph value="" onChangeText={jest.fn()} open defaultOpen={false}
      onOpenChange={onOpenChange} /> , 'never'));
    expect(screen.getByLabelText('Search')).toBeOnTheScreen();
    view.unmount();
    act(() => { jest.runOnlyPendingTimers(); });
  });
});

describe('U035 display controls: TipBubble', () => {
  it('announces an opened tip, exposes expanded state, auto closes, and calls the latest callback', () => {
    jest.useFakeTimers();
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(jest.fn());
    const onOpenChange = jest.fn();
    const view = render(wrap(<TipBubble content="Private text stays local" accessibilityLabel="Online privacy"
      duration={250} placement="bottom" align="start" width={240} onOpenChange={onOpenChange}>
      <Text>Info</Text>
    </TipBubble>));
    const anchor = screen.getByRole('button', { name: 'Online privacy' });
    expect(anchor.props.accessibilityState).toEqual({ expanded: false });
    fireEvent(anchor, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 60, height: 44 } } });
    fireEvent.press(anchor);
    expect(screen.getByText('Private text stays local', { includeHiddenElements: true })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Online privacy' }).props.accessibilityState).toEqual({ expanded: true });
    expect(announce).toHaveBeenCalledWith('Private text stays local');
    expect(onOpenChange).toHaveBeenCalledWith(true);
    act(() => { jest.advanceTimersByTime(250); });
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    view.unmount();
  });

  it('keeps a controlled bubble mounted while closed and does not schedule a zero-duration dismissal', () => {
    jest.useFakeTimers();
    const onOpenChange = jest.fn();
    const view = render(wrap(<TipBubble content="Tip text" accessibilityLabel="Help" open duration={0}
      onOpenChange={onOpenChange}><Text>Anchor</Text></TipBubble>));
    const anchor = screen.getByRole('button', { name: 'Help' });
    expect(screen.getByText('Tip text', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(anchor);
    expect(onOpenChange).toHaveBeenCalledWith(false);
    act(() => { jest.advanceTimersByTime(5000); });
    expect(onOpenChange).toHaveBeenCalledTimes(1);
    view.rerender(wrap(<TipBubble content="Tip text" accessibilityLabel="Help" open={false} duration={0}
      onOpenChange={onOpenChange}><Text>Anchor</Text></TipBubble>));
    act(() => { jest.advanceTimersByTime(100); });
    expect(screen.queryByText('Tip text')).toBeNull();
  });

  it('uses the default placement and closes after its default lifetime without a change listener', () => {
    jest.useFakeTimers();
    const view = render(wrap(<TipBubble content="Helpful hint" accessibilityLabel="Hint">
      <Text>Details</Text>
    </TipBubble>, 'never'));
    fireEvent.press(screen.getByRole('button', { name: 'Hint' }));
    expect(screen.getByText('Helpful hint', { includeHiddenElements: true })).toBeOnTheScreen();
    act(() => { jest.advanceTimersByTime(4000); });
    act(() => { jest.advanceTimersByTime(140); });
    expect(screen.queryByText('Helpful hint', { includeHiddenElements: true })).toBeNull();
    view.unmount();
  });

  it('positions an end-aligned bottom tip and lets a controlled timeout notify without changing its owner state', () => {
    jest.useFakeTimers();
    const change = jest.fn();
    const view = render(wrap(<TipBubble content="Context help" accessibilityLabel="Help" placement="bottom"
      align="end" open width={180} duration={50} onOpenChange={change}><Text>Anchor</Text></TipBubble>));
    fireEvent(screen.getByRole('button', { name: 'Help' }), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 40, height: 44 } } });
    act(() => { jest.advanceTimersByTime(50); });
    expect(change).toHaveBeenCalledWith(false);
    expect(screen.getByText('Context help', { includeHiddenElements: true })).toBeOnTheScreen();
    view.unmount();
  });
});

describe('U035 display controls: ReadMore', () => {
  const longText = 'A long document description that wraps into several lines.';

  it('offers a toggle only after measured overflow and reports expanded state both ways', () => {
    const onExpandedChange = jest.fn();
    const view = render(wrap(<ReadMore testID="read-more" lines={2} moreLabel="Read full text"
      lessLabel="Fold text" onExpandedChange={onExpandedChange}>{longText}</ReadMore>));
    expect(screen.queryByRole('button')).toBeNull();
    const paragraph = screen.getByText(longText);
    fireEvent(paragraph, 'layout', { nativeEvent: { layout: { width: 120, height: 100 } } });
    const more = screen.getByRole('button', { name: 'Read full text' });
    expect(more.props.accessibilityState).toEqual({ expanded: false });
    fireEvent.press(more);
    expect(onExpandedChange).toHaveBeenCalledWith(true);
    expect(screen.getByRole('button', { name: 'Fold text' }).props.accessibilityState).toEqual({ expanded: true });
    fireEvent.press(screen.getByRole('button', { name: 'Fold text' }));
    expect(onExpandedChange).toHaveBeenLastCalledWith(false);
    view.unmount();
  });

  it('honors externally controlled expansion, custom line height and fade surface', () => {
    const onExpandedChange = jest.fn();
    const view = render(wrap(<ReadMore lines={1} expanded fadeColor="#eeeeee" textStyle={{ fontSize: 20, lineHeight: 30 }}
      onExpandedChange={onExpandedChange}>{longText}</ReadMore>, 'never'));
    fireEvent(screen.getByText(longText), 'layout', { nativeEvent: { layout: { width: 90, height: 90 } } });
    fireEvent.press(screen.getByRole('button', { name: 'Show less' }));
    expect(onExpandedChange).toHaveBeenCalledWith(false);
    view.rerender(wrap(<ReadMore lines={4}>{longText}</ReadMore>));
    fireEvent(screen.getByText(longText), 'layout', { nativeEvent: { layout: { width: 200, height: 1 } } });
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('uses default labels and owns expansion when no controlled value is provided', () => {
    render(wrap(<ReadMore>{longText}</ReadMore>));
    fireEvent(screen.getByText(longText), 'layout', { nativeEvent: { layout: { width: 100, height: 110 } } });
    fireEvent.press(screen.getByRole('button', { name: 'Read more' }));
    expect(screen.getByRole('button', { name: 'Show less' }).props.accessibilityState).toEqual({ expanded: true });
  });
});

describe('U035 display controls: EmptyState', () => {
  it('keeps the illustration decorative and runs primary and secondary recovery actions', () => {
    const primary = jest.fn();
    const secondary = jest.fn();
    const view = render(wrap(<EmptyState title="No files yet" description="Import a PDF or Word file."
      icon={<Text>Document icon</Text>} action={{ label: 'Import file', onPress: primary }}
      secondaryAction={{ label: 'Learn more', onPress: secondary }} playKey="library-empty" testID="empty" />));
    expect(screen.getByRole('header', { name: 'No files yet' })).toBeOnTheScreen();
    expect(screen.getByText('Import a PDF or Word file.')).toBeOnTheScreen();
    expect(view.UNSAFE_getByProps({ accessibilityElementsHidden: true }).props.importantForAccessibility).toBe('no-hide-descendants');
    fireEvent.press(screen.getByRole('button', { name: 'Import file' }));
    fireEvent.press(screen.getByRole('button', { name: 'Learn more' }));
    expect(primary).toHaveBeenCalledTimes(1);
    expect(secondary).toHaveBeenCalledTimes(1);
    view.rerender(wrap(<EmptyState title="No matches" playKey="search-empty" /> , 'never'));
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('supports one action at a time and animates its pressed feedback when motion is enabled', () => {
    const primary = jest.fn();
    const view = render(wrap(<EmptyState title="Filtered out" action={{ label: 'Reset filter', onPress: primary }} /> , 'never'));
    const button = screen.getByRole('button', { name: 'Reset filter' });
    fireEvent(button, 'pressIn');
    fireEvent(button, 'pressOut');
    fireEvent.press(button);
    expect(primary).toHaveBeenCalledTimes(1);
    view.rerender(wrap(<EmptyState title="No history" secondaryAction={{ label: 'Open help', onPress: primary }} />));
    fireEvent.press(screen.getByRole('button', { name: 'Open help' }));
    expect(primary).toHaveBeenCalledTimes(2);
  });
});

describe('U035 display controls: UsageMeter', () => {
  it('announces a formatted allowance, clamps negative consumption, and renders a measured bar', () => {
    const view = render(wrap(<UsageMeter title="Local storage" total={100} unit="MB"
      segments={[{ label: 'Models', value: 25 }, { label: 'Cache', value: -8 }]}
      formatValue={(value) => `${value.toFixed(0)}`} remainingLabel="available" testID="meter" />));
    const meter = view.UNSAFE_getByProps({ accessibilityRole: 'progressbar' });
    expect(meter.props.accessibilityValue).toEqual({ min: 0, max: 100, now: 25, text: '25 MB / 100 MB; 75 MB available; Models: 25 MB, Cache: -8 MB' });
    expect(screen.getByText('75 MB available')).toBeOnTheScreen();
    expect(screen.getByText('Cache')).toBeOnTheScreen();
    const bar = view.UNSAFE_getAllByType(View).find((node) => typeof node.props.onLayout === 'function');
    expect(bar).toBeDefined();
    const beforeMeasure = view.UNSAFE_getAllByType(View).length;
    fireEvent(bar!, 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 240, height: 12 } } });
    expect(view.UNSAFE_getAllByType(View).length).toBeGreaterThan(beforeMeasure);
  });

  it('handles zero allowance and exceeded totals without NaN, omits optional legend and accepts palette fallback', () => {
    const zero = render(wrap(<UsageMeter title="Empty quota" total={0} segments={[]} legend={false} />));
    expect(zero.UNSAFE_getByProps({ accessibilityRole: 'progressbar' }).props.accessibilityValue.now).toBe(0);
    expect(zero.UNSAFE_getByProps({ accessibilityRole: 'progressbar' }).props.accessibilityValue.text).toContain('0.0 / 0.0');
    zero.unmount();
    const zeroWithSegment = render(wrap(<UsageMeter title="Zero allowance" total={0} segments={[{ label: 'Overflow', value: 2 }]} legend={false} />));
    const zeroBar = zeroWithSegment.UNSAFE_getAllByType(View).find((node) => typeof node.props.onLayout === 'function');
    fireEvent(zeroBar!, 'layout', { nativeEvent: { layout: { width: 100, height: 12, x: 0, y: 0 } } });
    expect(zeroWithSegment.UNSAFE_getByProps({ accessibilityRole: 'progressbar' }).props.accessibilityValue.now).toBe(0);
    zeroWithSegment.unmount();
    const over = render(wrap(<UsageMeter title="Storage" total={10} segments={[{ label: 'A', value: 12 }, { label: 'B', value: 3 }]}
      warnAt={0.5} overLimitLabel="over limit" colors={['#111111']} legend={false} />));
    expect(over.UNSAFE_getByProps({ accessibilityRole: 'progressbar' }).props.accessibilityValue).toMatchObject({ now: 10 });
    expect(screen.getByText('5.0 over limit')).toBeOnTheScreen();
    expect(screen.queryByText('A')).toBeNull();
    const bar = over.UNSAFE_getAllByType(View).find((node) => typeof node.props.onLayout === 'function');
    fireEvent(bar!, 'layout', { nativeEvent: { layout: { width: 80, height: 12, x: 0, y: 0 } } });
    over.unmount();
  });

  it('shows the warning state before the cap and sequences segments when motion is enabled', () => {
    const view = render(wrap(<UsageMeter title="Downloads" total={100} segments={[{ label: 'First', value: 50 }, { label: 'Second', value: 40 }]}
      warnAt={0.8} /> , 'never'));
    expect(screen.getByText('10 left')).toBeOnTheScreen();
    const bar = view.UNSAFE_getAllByType(View).find((node) => typeof node.props.onLayout === 'function');
    fireEvent(bar!, 'layout', { nativeEvent: { layout: { width: 300, height: 12, x: 0, y: 0 } } });
    expect(view.UNSAFE_getByProps({ accessibilityRole: 'progressbar' }).props.accessibilityValue.now).toBe(90);
  });
});

describe('U035 display controls: Timeline', () => {
  it('announces event state and description while keeping child controls reachable', () => {
    const childAction = jest.fn();
    const view = render(wrap(<Timeline testID="translation-steps" currentLabel="in progress" upcomingLabel="up next" events={[
      { key: 'import', title: 'Import document', time: '09:00', description: 'Read source pages.' },
      { key: 'translate', title: 'Translate', time: '09:02', state: 'current', description: 'Sending one paragraph.', children: <Text accessibilityRole="button" onPress={childAction}>Pause transfer</Text> },
      { key: 'save', title: 'Save result', time: '09:04', state: 'upcoming' },
    ]} playKey={3} />));
    expect(view.UNSAFE_getByProps({ accessibilityLabel: '09:02, Translate. Sending one paragraph., in progress' }).props.accessible).toBe(true);
    expect(view.UNSAFE_getByProps({ accessibilityLabel: '09:04, Save result, up next' }).props.accessible).toBe(true);
    expect(view.UNSAFE_getByProps({ accessibilityLabel: '09:00, Import document. Read source pages.' }).props.accessible).toBe(true);
    fireEvent.press(screen.getByRole('button', { name: 'Pause transfer' }));
    expect(childAction).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Sending one paragraph.')).toBeOnTheScreen();
  });

  it('supports empty lists, defaults events to done and replays with reduce motion enabled', () => {
    const empty = render(wrap(<Timeline events={[]} testID="no-events" />));
    expect(empty.getByTestId('no-events').children).toHaveLength(0);
    empty.unmount();
    const one = render(wrap(<Timeline events={[{ key: 'done', title: 'Ready', time: 'Now' }]} playKey="refresh" />, 'always'));
    expect(screen.getByText('Ready')).toBeOnTheScreen();
    expect(screen.getByText('Now')).toBeOnTheScreen();
    one.rerender(wrap(<Timeline events={[{ key: 'done', title: 'Ready', time: 'Now', state: 'done' }]} playKey="again" />, 'always'));
    expect(screen.getByText('Ready')).toBeOnTheScreen();
  });

  it('runs sequential entrance effects without reduce motion and tolerates missing state labels', () => {
    const view = render(wrap(<Timeline events={[
      { key: 'first', title: 'Prepared', time: 'Just now' },
      { key: 'next', title: 'Waiting', time: 'Soon', state: 'upcoming' },
      { key: 'last', title: 'Saved', time: 'Later' },
    ]} /> , 'never'));
    expect(view.UNSAFE_getByProps({ accessibilityLabel: 'Just now, Prepared' }).props.accessible).toBe(true);
    expect(view.UNSAFE_getByProps({ accessibilityLabel: 'Soon, Waiting' }).props.accessible).toBe(true);
  });
});
