import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react-native-legacy';
import { Text } from 'react-native';

const mockShow = jest.fn();
jest.mock('../src/components/kinetik/toast-stack', () => ({ useToast: () => ({ show: mockShow }) }));
jest.mock('react-native-reanimated', () => {
  const ReactRuntime = require('react');
  const ReactNative = require('react-native');
  const AnimatedView = ReactRuntime.forwardRef((props: object, ref: object) => ReactRuntime.createElement(ReactNative.View, { ...props, ref }));
  return {
    __esModule: true,
    default: { createAnimatedComponent: (component: unknown) => component, View: AnimatedView },
    createAnimatedComponent: (component: unknown) => component,
    useSharedValue: (initial: number) => ({ get: () => initial, set: jest.fn() }),
    useAnimatedProps: (callback: () => unknown) => callback(),
    useAnimatedStyle: (callback: () => unknown) => callback(),
    cancelAnimation: jest.fn(),
    withTiming: jest.fn((value: unknown) => value),
    withRepeat: (value: unknown) => value,
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    Easing: { linear: (value: number) => value, inOut: (value: unknown) => value },
  };
});

import { LargeDocumentHint } from '../src/ui/LargeDocumentHint';
import { ProcessingMetrics } from '../src/ui/ProcessingMetrics';
import { FolivelaMark } from '../src/ui/FolivelaMark';
import { palettes } from '../src/ui/theme';
import type { JobSnapshot, NotificationPermissionSnapshot } from '../src/native/PdfTranslator';

const makeJob = (overrides: Partial<JobSnapshot> = {}): JobSnapshot => ({
  id: 'large-job', documentId: 'doc-large', documentName: 'Equinox.pdf', modelId: 'compact',
  sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating',
  completedPages: 2, totalPages: 101, currentPage: 2, createdAt: 1, updatedAt: 2,
  ...overrides,
});
const denied: NotificationPermissionSnapshot = { status: 'denied', requestedBefore: true, canRequest: false };

describe('Folivela 0.5 processing feedback', () => {
  afterEach(() => { cleanup(); jest.useRealTimers(); jest.clearAllMocks(); });

  it('shows the notification hint only above 100 pages and only once per job', () => {
    const onEnable = jest.fn();
    const view = render(<LargeDocumentHint jobs={[makeJob({ totalPages: 100 })]} notifications={denied} active locale="en" onEnable={onEnable} />);
    expect(mockShow).not.toHaveBeenCalled();
    view.rerender(<LargeDocumentHint jobs={[makeJob({ totalPages: 101 })]} notifications={denied} active locale="en" onEnable={onEnable} />);
    expect(mockShow).toHaveBeenCalledTimes(1);
    const toast = mockShow.mock.calls[0][0];
    expect(toast.title).toMatch(/large document/i);
    expect(toast.action.label).toBe('Enable');
    toast.action.onPress();
    expect(onEnable).toHaveBeenCalledTimes(1);
    view.rerender(<LargeDocumentHint jobs={[makeJob({ totalPages: 415 })]} notifications={denied} active locale="en" onEnable={onEnable} />);
    expect(mockShow).toHaveBeenCalledTimes(1);
    view.rerender(<LargeDocumentHint jobs={[makeJob({ id: 'queued-large', status: 'queued' })]} notifications={denied} active locale="en" onEnable={onEnable} />);
    expect(mockShow).toHaveBeenCalledTimes(2);
  });

  it('does not prompt when notifications are already granted or when the app is inactive', () => {
    const granted: NotificationPermissionSnapshot = { status: 'granted', requestedBefore: true, canRequest: false };
    const view = render(<LargeDocumentHint jobs={[makeJob()]} notifications={granted} active locale="en" onEnable={jest.fn()} />);
    view.rerender(<LargeDocumentHint jobs={[makeJob({ id: 'inactive' })]} notifications={denied} active={false} locale="en" onEnable={jest.fn()} />);
    expect(mockShow).not.toHaveBeenCalled();
  });

  it('lets elapsed stage time advance without changing native block or token counters', () => {
    jest.useFakeTimers();
    jest.setSystemTime(20_000);
    const job = makeJob({ stageStartedAt: 10_000, pageBlocks: 4, currentBlock: 1, completedPageBlocks: 1, processedTokens: 23 });
    const view = render(<><ProcessingMetrics job={job} active locale="en" colors={palettes.light} /><Text>{job.completedPageBlocks}/{job.pageBlocks}</Text></>);
    expect(screen.getByText('Block: 2 / 4')).toBeOnTheScreen();
    expect(screen.getByText('Tokens processed: 23')).toBeOnTheScreen();
    expect(screen.getByText('Time in this stage: 0:10')).toBeOnTheScreen();
    act(() => { jest.advanceTimersByTime(5_000); });
    expect(screen.getByText('Time in this stage: 0:15')).toBeOnTheScreen();
    expect(screen.getByText('Block: 2 / 4')).toBeOnTheScreen();
    expect(screen.getByText('Tokens processed: 23')).toBeOnTheScreen();
    expect(screen.getByText('1/4')).toBeOnTheScreen();
    view.unmount();
  });

it('defaults the visible block to the first block before native progress is reported', () => {
 render(<ProcessingMetrics job={makeJob({ pageBlocks: 4, currentBlock: undefined })} active locale="en" colors={palettes.light} />);
    expect(screen.getByText('Block: 1 / 4')).toBeOnTheScreen();
  });

  it('renders the Folivela mark statically with reduced motion and animates only when active', () => {
    const reanimated = require('react-native-reanimated');
    const view = render(<FolivelaMark color="#fff" active={false} reduced />);
    expect(screen.getByRole('image', { name: 'Folivela' })).toBeOnTheScreen();
    expect(reanimated.cancelAnimation).toHaveBeenCalled();
    expect(reanimated.withTiming).not.toHaveBeenCalled();
    view.rerender(<FolivelaMark color="#fff" active reduced={false} />);
    expect(reanimated.withTiming).toHaveBeenCalled();
  });
});
