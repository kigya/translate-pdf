import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { Text } from 'react-native';

jest.mock('@shopify/react-native-skia', () => {
  const ReactRuntime = require('react');
  const { View } = require('react-native');
  const node = (name: string) => (props: object) => ReactRuntime.createElement(View, { ...props, testID: name });
  const path = {
    moveTo() { return this; }, lineTo() { return this; }, quadTo() { return this; }, close() { return this; },
    addCircle() { return this; }, detach() { return {}; },
  };
  return {
    Canvas: node('canvas'), Group: node('group'), Path: node('path'),
    Skia: {
      PathBuilder: { Make: () => ({ ...path }) },
      Path: { MakeFromSVGString: () => ({}), Make: () => ({}) },
      XYWHRect: () => ({}), Color: () => [0, 0, 0, 1],
    },
    vec: (x: number, y: number) => ({ x, y }),
    rect: () => ({}),
  };
});

jest.mock('react-native-gesture-handler', () => {
  const gesture = () => {
    const chain: Record<string, unknown> = {};
    for (const method of ['withTestId', 'enabled', 'activeOffsetX', 'activeOffsetY', 'failOffsetX', 'failOffsetY', 'onUpdate', 'onEnd']) {
      chain[method] = () => chain;
    }
    return chain;
  };
  return {
    Gesture: { Pan: gesture, Tap: gesture, Exclusive: (...items: unknown[]) => items[0] },
    GestureDetector: ({ children }: { children: React.ReactNode }) => children,
  };
});

jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  const identity = (value: unknown) => value;
  const useMockSharedValue = (initial: unknown) => {
    const ref = ReactRuntime.useRef(undefined);
    if (ref.current === undefined) {
      ref.current = { value: initial, get() { return this.value; }, set(value: unknown) { this.value = value; } };
    }
    return ref.current;
  };
  return {
    __esModule: true,
    default: {
      View: require('react-native').View,
      Text: require('react-native').Text,
      ScrollView: require('react-native').ScrollView,
      Image: require('react-native').Image,
    },
    Easing: { linear: identity, out: () => identity, cubic: identity, bezier: () => identity, inOut: identity, sin: identity },
    ReduceMotion: { Always: 'always', Never: 'never' },
    useSharedValue: useMockSharedValue,
    useAnimatedStyle: (factory: () => object) => factory(),
    useDerivedValue: (factory: () => unknown) => ({ get: factory }),
    useFrameCallback: () => ({ setActive: jest.fn() }),
    useReducedMotion: () => true,
    cancelAnimation: jest.fn(),
    withTiming: identity,
    withSpring: identity,
    withRepeat: identity,
    withSequence: (...values: unknown[]) => values.at(-1),
    withDelay: (_delay: number, value: unknown) => value,
    interpolateColor: (_value: number, _input: number[], output: string[]) => output[0],
    FadeInDown: { duration: () => ({ easing: () => undefined }) },
  };
});

import { KinetikProvider } from '../src/lib/kinetik/tokens/theme';
import { ScanProgress } from '../src/components/kinetik/scan-progress';
import { SearchMorph } from '../src/components/kinetik/search-morph';
import { StarRating } from '../src/components/kinetik/star-rating';
import { UsageMeter } from '../src/components/kinetik/usage-meter';
import { Timeline } from '../src/components/kinetik/timeline';
import { TipBubble } from '../src/components/kinetik/tip-bubble';

const wrap = (child: React.ReactNode) => (
  <KinetikProvider scheme="light" reduceMotion="always" haptics={false}>{child}</KinetikProvider>
);

describe('U034 Kinetik localization and honest progress', () => {
  it('uses the caller-provided clear label and clears the controlled query', () => {
    const onChangeText = jest.fn();
    render(wrap(
      <SearchMorph value="folio" onChangeText={onChangeText} open clearLabel="Clear query" />,
    ));

    fireEvent.press(screen.getByRole('button', { name: 'Clear query' }));
    expect(onChangeText).toHaveBeenCalledWith('');
  });

  it('reports numeric usage and localized remainder labels without English sentence glue', () => {
    render(wrap(
      <UsageMeter
        title="Allowance"
        total={10}
        segments={[{ label: 'Documents', value: 12 }]}
        remainingLabel="remaining"
        overLimitLabel="above limit"
      />,
    ));

    const value = screen.getByRole('progressbar', { name: 'Allowance' }).props.accessibilityValue;
    expect(value.text).toBe('12 / 10; 2.0 above limit; Documents: 12');
    expect(value.text).not.toContain('of');
    expect(value.text).not.toContain('used');
  });

  it('uses a language-neutral value/max string for editable and read-only ratings', () => {
    const { rerender } = render(wrap(
      <StarRating value={3} onValueChange={jest.fn()} accessibilityLabel="Local rating" haptics={false} />,
    ));
    expect(screen.getByRole('adjustable', { name: 'Local rating' }).props.accessibilityValue.text).toBe('3 / 5');

    rerender(wrap(<StarRating value={4} accessibilityLabel="Saved rating" haptics={false} />));
    expect(screen.getByRole('image', { name: 'Saved rating: 4 / 5' })).toBeOnTheScreen();
  });

  it('keeps unselected stars visibly outlined in both themes', () => {
    const { createTheme } = require('../src/lib/kinetik/tokens/tokens');
    for (const scheme of ['light', 'dark'] as const) {
      const view = render(<KinetikProvider scheme={scheme} reduceMotion="always" haptics={false}><StarRating value={0} onValueChange={jest.fn()} accessibilityLabel="Local rating" /></KinetikProvider>);
      const outlines = screen.getAllByTestId('path').filter(node => node.props.style === 'stroke');
      expect(outlines).toHaveLength(5);
      expect(outlines.every(node => node.props.color === createTheme(scheme).colors.textMuted && node.props.strokeWidth >= 1.5)).toBe(true);
      view.unmount();
    }
  });

  it('keeps unknown progress indeterminate while paused and only completes on explicit done', () => {
    const { UNSAFE_getByType, rerender } = render(wrap(
      <ScanProgress label="Translating" active={false} />,
    ));
    const progress = screen.getByRole('progressbar', { name: 'Translating' });
    expect(progress.props.accessibilityValue).toBeUndefined();
    expect(UNSAFE_getByType(require('../src/components/kinetik/ring-spinner').RingSpinner).props.active).toBe(false);

    rerender(wrap(<ScanProgress label="Translating" done />));
    expect(screen.getByRole('progressbar', { name: 'Analysis complete' }).props.accessibilityValue).toEqual({ min: 0, max: 100, now: 100 });
  });

  it('uses caller-localized timeline state labels and omits an unlocalized tip hint', () => {
    render(wrap(<>
      <Timeline currentLabel="In progress" upcomingLabel="Waiting" events={[
        { key: 'current', title: 'Translating', time: '09:00', state: 'current' },
        { key: 'next', title: 'Exporting', time: '09:01', state: 'upcoming' },
      ]} />
      <TipBubble content="Extra context" accessibilityLabel="Document details" open duration={0}>
        <Text>Info</Text>
      </TipBubble>
    </>));

    expect(screen.getByLabelText('09:00, Translating, In progress')).toBeOnTheScreen();
    expect(screen.getByLabelText('09:01, Exporting, Waiting')).toBeOnTheScreen();
    const tip = screen.getByRole('button', { name: 'Document details' });
    expect(tip.props.accessibilityHint).toBeUndefined();
    expect(tip.props.accessibilityState).toEqual({ expanded: true });
  });
});
