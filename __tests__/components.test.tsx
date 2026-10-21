jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { render, screen } from '@testing-library/react-native';
import { fireEvent } from '@testing-library/react-native';
jest.mock('react-native-reanimated', () => ({ __esModule: true, default: { View: require('react-native').View }, useSharedValue: (value: number) => ({ value }), useAnimatedStyle: (fn: () => object) => fn(), useReducedMotion: () => true, withTiming: (v: number) => v }));
import { buttonOpacity, Card, Field, Header, Label, Notice, ProgressBar, Row, Screen, SectionTitle, Button } from '../src/ui/components';
import { palettes } from '../src/ui/theme';
import { Text, View } from 'react-native';

describe('progress display', () => {
  it('clamps native work values to the accessible 0–100 range', () => {
    render(<ProgressBar value={1.4} colors={palettes.light} label="Pages complete" />);
    const progress = screen.getByRole('progressbar', { name: 'Pages complete' });
    expect(progress).toHaveAccessibilityValue({ min: 0, max: 100, now: 100 });
  });

  it('keeps an empty native task at zero instead of implying progress', () => {
    render(<ProgressBar value={-0.2} colors={palettes.dark} label="Pages complete" />);
    expect(screen.getByRole('progressbar')).toHaveAccessibilityValue({ min: 0, max: 100, now: 0 });
  });
});

describe('direction-aware layout primitives', () => {
  it('sets both screen shells to the requested layout direction', () => {
    const { UNSAFE_getAllByType } = render(<Screen colors={palettes.light} direction="rtl"><Text>Library content</Text></Screen>);
    expect(UNSAFE_getAllByType(View)[0].props.style).toEqual(expect.objectContaining({ direction: 'rtl' }));
    expect(UNSAFE_getAllByType(View)[1].props.style).toEqual(expect.objectContaining({ direction: 'rtl' }));
    render(<Screen colors={palettes.light} maxWidth={700}><Text>Constrained layout</Text></Screen>);
    expect(screen.getByText('Constrained layout')).toBeOnTheScreen();
  });

  it('uses a right-to-left back affordance and right aligned header copy', () => {
    const onBack = jest.fn();
    render(<Header title="Settings" subtitle="Local preferences" colors={palettes.light} onBack={onBack} direction="rtl" />);

    expect(screen.getByRole('button', {name:'Back'})).toHaveStyle({width:48,height:48});
    expect(screen.getByRole('header')).toHaveStyle({ writingDirection: 'rtl', textAlign: 'right' });
  });

  it('renders a left-to-right header with optional back and trailing content', () => {
    const onBack = jest.fn();
    render(<Header title="Library" subtitle="Recent documents" colors={palettes.light} onBack={onBack} right={<Text>More</Text>} />);
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', {name:'Back'})).toHaveStyle({width:48,height:48});
    expect(screen.getByText('More')).toBeOnTheScreen();
    expect(screen.getByRole('header')).toHaveStyle({ writingDirection: 'ltr', textAlign: 'left' });
  });

  it('styles labels, cards, rows, and section titles from the selected palette', () => {
    render(<>
      <Card colors={palettes.dark}><Label colors={palettes.dark} tone="success">Local</Label><Row><Text>Source</Text><Text>Translation</Text></Row><SectionTitle colors={palettes.dark}>Glossary</SectionTitle></Card>
      <Label colors={palettes.light}>Default label</Label>
    </>);
    expect(screen.getByText('Local')).toBeOnTheScreen();
    expect(screen.getByText('Source')).toBeOnTheScreen();
    expect(screen.getByText('Glossary')).toHaveStyle({ color: palettes.dark.muted, textTransform: 'uppercase' });
    expect(screen.getByText('Default label')).toBeOnTheScreen();
  });

  it('supports editable labeled fields and preserves standard text-input props', () => {
    const onChangeText = jest.fn();
    render(<Field label="English term" value="coolant" onChangeText={onChangeText} placeholder="Type a term" colors={palettes.light} autoCapitalize="none" />);
    const input = screen.getByLabelText('English term');
    expect(input).toHaveDisplayValue('coolant');
    expect(screen.getByText('English term')).toBeOnTheScreen();
    fireEvent.changeText(input, 'reservoir');
    expect(onChangeText).toHaveBeenCalledWith('reservoir');
    expect(input).toHaveProp('autoCapitalize', 'none');
  });

  it('covers button variants, busy state, and disabled state', () => {
    const onPress = jest.fn();
    const { rerender } = render(<Button label="Remove" onPress={onPress} colors={palettes.light} variant="danger" />);
    fireEvent.press(screen.getByRole('button', { name: 'Remove' }));
    expect(onPress).toHaveBeenCalledTimes(1);
    rerender(<Button label="Saving" onPress={onPress} colors={palettes.light} loading />);
    expect(screen.getByRole('button', { name: 'Saving' }).props.accessibilityState).toEqual({ disabled: true, busy: true });
    rerender(<Button label="Unavailable" onPress={onPress} colors={palettes.light} disabled />);
    fireEvent.press(screen.getByRole('button', { name: 'Unavailable' }));
    expect(onPress).toHaveBeenCalledTimes(1);
    rerender(<Button label="Continue" onPress={onPress} colors={palettes.light} variant="secondary" accessibilityLabel="Continue action" />);
    fireEvent.press(screen.getByRole('button', { name: 'Continue action' }));
    expect(onPress).toHaveBeenCalledTimes(2);
    expect(buttonOpacity(false, true)).toBe(0.78);
    expect(buttonOpacity(false, false)).toBe(1);
    expect(buttonOpacity(true, true)).toBe(0.45);
  });

  it('supports empty, informative, and retryable notices', () => {
    const retry = jest.fn();
    const { rerender } = render(<Notice text="" colors={palettes.light} />);
    expect(screen.queryByRole('alert')).toBeNull();
    rerender(<Notice text="Model checksum verified." tone="info" colors={palettes.light} />);
    expect(screen.getByText('Model checksum verified.')).toBeOnTheScreen();
    expect(screen.queryByRole('alert')).toBeNull();
    rerender(<Notice text="Offline" colors={palettes.light} retry={retry} retryLabel="Retry download" />);
    fireEvent.press(screen.getByRole('button', { name: 'Retry download' }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('notice').props.accessibilityRole).toBe('alert');
  });
});
