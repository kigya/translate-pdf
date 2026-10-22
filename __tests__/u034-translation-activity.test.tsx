import React from 'react';
import { act, render, screen } from '@testing-library/react-native-legacy';
import { createTranslator } from '../src/i18n/messages';
import type { JobSnapshot } from '../src/native/PdfTranslator';
import { PdfTranslator } from '../src/native/PdfTranslator';
import { TranslationActivity, translationActivityTimeline } from '../src/ui/TranslationActivity';
import { palettes } from '../src/ui/theme';

const mockBurst = jest.fn();

jest.mock('../src/native/PdfTranslator', () => ({
  PdfTranslator: { renderPage: jest.fn(), renderDocxPreview: jest.fn(), renderTranslatedPage: jest.fn() },
}));

jest.mock('../src/components/kinetik/confetti-burst', () => {
  const ReactRuntime = require('react');
  const Native = require('react-native');
  const MockConfetti = ReactRuntime.forwardRef(({ children }: { children: React.ReactNode }, ref: React.Ref<unknown>) => {
    ReactRuntime.useImperativeHandle(ref, () => ({ burst: mockBurst }), []);
    return ReactRuntime.createElement(Native.View, { testID: 'confetti-host' }, children);
  });
  return { ConfettiBurst: MockConfetti };
});

jest.mock('../src/components/kinetik/scan-progress', () => {
  const ReactRuntime = require('react');
  const Native = require('react-native');
  return {
    ScanProgress: ({ children, progress, done, label, active, source, imageLabel }: Record<string, unknown>) =>
      ReactRuntime.createElement(Native.View, {
        testID: 'translation-scan',
        accessibilityLabel: JSON.stringify({ progress, done, label, active, source, imageLabel }),
      }, children),
  };
});

jest.mock('../src/components/kinetik/timeline', () => {
  const ReactRuntime = require('react');
  const Native = require('react-native');
  return {
    Timeline: ({ events }: { events: Array<{ key: string; title: string; state: string }> }) =>
      ReactRuntime.createElement(Native.View, {
        testID: 'translation-timeline',
        accessibilityLabel: JSON.stringify(events.map(({ key, title, state }) => ({ key, title, state }))),
      }),
  };
});

jest.mock('../src/ui/Controls', () => {
  const ReactRuntime = require('react');
  const Native = require('react-native');
  return { Icon: ({ name }: { name: string }) => ReactRuntime.createElement(Native.Text, null, name) };
});

jest.mock('../src/ui/components', () => ({ Notice: () => null }));

const t = createTranslator('en');
const baseJob = (overrides: Partial<JobSnapshot> = {}): JobSnapshot => ({
  id: 'job-1', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model',
  sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating',
  completedPages: 3, totalPages: 10, currentPage: 3, createdAt: 1, updatedAt: 2,
  ...overrides,
});
const renderActivity = (job: JobSnapshot, active = true) => render(
  <TranslationActivity job={job} colors={palettes.light} t={t} locale="en" active={active} />,
);

beforeEach(() => {
  mockBurst.mockClear();
  (PdfTranslator.renderPage as jest.Mock).mockReturnValue(new Promise(() => {}));
  (PdfTranslator.renderDocxPreview as jest.Mock).mockResolvedValue({ uri: 'file:///source.docx.pdf', pageCount: 1 });
  (PdfTranslator.renderTranslatedPage as jest.Mock).mockResolvedValue('file:///translated.png');
});

describe('translation activity', () => {
  test('passes native progress and stage labels without indeterminate progress creep', async () => {
    (PdfTranslator.renderPage as jest.Mock).mockRejectedValueOnce(new Error('preview unavailable'));
    renderActivity(baseJob({ progressUnit: 'segment' }), false);
    await act(async () => { await Promise.resolve(); });

    const scan = JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel);
    expect(scan).toEqual(expect.objectContaining({ progress: 0.3, done: false, active: false }));
    expect(screen.getByText('manual.pdf')).toBeTruthy();
    expect(screen.getByText('Page 4 / 10')).toBeTruthy();
    expect(screen.getByTestId('translation-timeline')).toBeTruthy();
  });

  test('keeps progress at zero when the native total is unknown and clamps over-reported work', () => {
    const view = renderActivity(baseJob({ totalPages: 0, completedPages: 4 }));
    let scan = JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel);
    expect(scan.progress).toBe(0);

    view.rerender(<TranslationActivity job={baseJob({ totalPages: 2, completedPages: 9 })} colors={palettes.light} t={t} locale="en" active />);
    scan = JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel);
    expect(scan.progress).toBe(1);
    expect(screen.getByText('Page 4 / 2')).toBeTruthy();
  });

  test('loads the optional source preview while retaining the activity if the preview fails', async () => {
    (PdfTranslator.renderPage as jest.Mock).mockResolvedValueOnce('file:///source-page.png');
    const view = renderActivity(baseJob());
    await act(async () => { await Promise.resolve(); });
    const scan = JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel);
    expect(scan.source).toEqual({ uri: 'file:///source-page.png' });
    expect(scan.imageLabel).toBe('manual.pdf');

    (PdfTranslator.renderPage as jest.Mock).mockRejectedValueOnce(new Error('no preview'));
    view.rerender(<TranslationActivity job={baseJob({ documentId: 'doc-2' })} colors={palettes.light} t={t} locale="en" active />);
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByTestId('translation-scan')).toBeTruthy();
    expect(screen.getByText('manual.pdf')).toBeTruthy();
  });

  test('ignores a late thumbnail response from the previous document', async () => {
    let resolveOldPreview!: (uri: string) => void;
    (PdfTranslator.renderPage as jest.Mock)
      .mockReturnValueOnce(new Promise<string>((resolve) => { resolveOldPreview = resolve; }))
      .mockResolvedValueOnce('file:///new-document.png');
    const view = renderActivity(baseJob());
    view.rerender(<TranslationActivity job={baseJob({ documentId: 'doc-2', documentName: 'new.pdf' })} colors={palettes.light} t={t} locale="en" active />);
    await act(async () => { await Promise.resolve(); });
    resolveOldPreview('file:///old-document.png');
    await act(async () => { await Promise.resolve(); });
    const scan = JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel);
    expect(scan.source).toEqual({ uri: 'file:///new-document.png' });
  });

  test('ignores a late preview failure after switching documents', async () => {
    let rejectOldPreview!: (reason: Error) => void;
    (PdfTranslator.renderPage as jest.Mock)
      .mockReturnValueOnce(new Promise<string>((_resolve, reject) => { rejectOldPreview = reject; }))
      .mockReturnValueOnce(new Promise<string>(() => {}));
    const view = renderActivity(baseJob());
    view.rerender(<TranslationActivity job={baseJob({ documentId: 'doc-2', documentName: 'new.pdf' })} colors={palettes.light} t={t} locale="en" active />);
    rejectOldPreview(new Error('old preview failed'));
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText('new.pdf')).toBeTruthy();
  });

  test('uses a document fallback when the native preview resolves without a page URI', async () => {
    (PdfTranslator.renderPage as jest.Mock).mockResolvedValueOnce(undefined);
    renderActivity(baseJob());
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText('manual.pdf')).toBeTruthy();
    expect(JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel).source).toBeUndefined();
  });

  test('labels the document fallback when the imported filename is absent', () => {
    renderActivity(baseJob({ documentName: '' }));
    expect(screen.getByText('Document')).toBeTruthy();
  });

  test('prepares the original Word preview before requesting its first page thumbnail', async () => {
    (PdfTranslator.renderPage as jest.Mock).mockResolvedValueOnce('file:///word-page.png');
    renderActivity(baseJob({ documentFormat: 'docx', documentName: 'manual.docx' }));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(PdfTranslator.renderDocxPreview).toHaveBeenCalledWith('doc-1', null, false);
    expect(PdfTranslator.renderPage).toHaveBeenCalledWith('doc-1', 0, 420);
    expect(JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel).source)
      .toEqual({ uri: 'file:///word-page.png' });
    expect(screen.queryByText('Page 4 / 10')).toBeNull();
  });

  test('uses an online translation label and keeps the PDF stage timeline page-local', () => {
    const online = baseJob({ engine: { kind: 'online', id: 'mymemory' } });
    const events = translationActivityTimeline(online, t);
    expect(events[2]?.title).toBe('Text is sent to MyMemory with your consent');
    expect(events[2]?.state).toBe('current');
  });

  test('celebrates only a new completed transition, once, while active', () => {
    const view = renderActivity(baseJob({ status: 'completed', stage: 'complete' }));
    expect(mockBurst).not.toHaveBeenCalled();
    view.rerender(<TranslationActivity job={baseJob()} colors={palettes.light} t={t} locale="en" active />);
    view.rerender(<TranslationActivity job={baseJob({ status: 'completed', stage: 'complete' })} colors={palettes.light} t={t} locale="en" active />);
    view.rerender(<TranslationActivity job={baseJob({ status: 'completed', stage: 'complete' })} colors={palettes.light} t={t} locale="en" active />);
    expect(mockBurst).toHaveBeenCalledTimes(1);

    view.rerender(<TranslationActivity job={baseJob({ id: 'old-complete', status: 'completed', stage: 'complete' })} colors={palettes.light} t={t} locale="en" active />);
    expect(mockBurst).toHaveBeenCalledTimes(1);
  });

  test('defers a new completion burst until foreground and never celebrates partial output', () => {
    const view = renderActivity(baseJob(), false);
    view.rerender(<TranslationActivity job={baseJob({ status: 'completed', stage: 'complete' })} colors={palettes.light} t={t} locale="en" active={false} />);
    expect(mockBurst).not.toHaveBeenCalled();
    view.rerender(<TranslationActivity job={baseJob({ status: 'completed', stage: 'complete' })} colors={palettes.light} t={t} locale="en" active />);
    expect(mockBurst).toHaveBeenCalledTimes(1);

    view.rerender(<TranslationActivity job={baseJob({ id: 'partial-job', status: 'partial', stage: 'complete' })} colors={palettes.light} t={t} locale="en" active />);
    expect(mockBurst).toHaveBeenCalledTimes(1);
  });

  test('keeps unknown, paused and failed stages from becoming completed timeline events', () => {
    const unknown = baseJob({ status: 'paused', stage: 'future-stage' as JobSnapshot['stage'] });
    expect(translationActivityTimeline(unknown, t).map((event) => event.state)).toEqual([
      'upcoming', 'upcoming', 'upcoming', 'upcoming', 'upcoming',
    ]);
    const failedAtEnd = translationActivityTimeline(baseJob({ status: 'failed', stage: 'complete' }), t);
    expect(failedAtEnd[3]?.state).toBe('current');
    expect(failedAtEnd[4]?.state).toBe('upcoming');
    const completed = translationActivityTimeline(baseJob({ status: 'completed', stage: 'complete' }), t);
    expect(completed.every((event) => event.state === 'done')).toBe(true);
    expect(translationActivityTimeline(baseJob({ status: 'partial', stage: 'complete' }), t).every(event => event.state === 'done')).toBe(true);
  });

  test.each([
    ['queued', 'queued', 'Waiting to start'],
    ['paused', 'translating', 'Paused'],
    ['partial', 'complete', 'Partial result'],
    ['failed', 'complete', 'Failed'],
    ['canceled', 'translating', 'Canceled'],
    ['completed', 'complete', 'Complete'],
    ['running', 'complete', 'In progress'],
    ['running', 'future-stage', 'In progress'],
  ] as const)('shows truthful status text for %s at %s', async (status, stage, expected) => {
    renderActivity(baseJob({ status, stage: stage as JobSnapshot['stage'], error: status === 'failed' ? 'Native failure' : undefined }));
    if (status === 'completed' || status === 'partial') {
      expect(screen.queryByTestId('translation-scan')).toBeNull();
      expect(await screen.findByRole('image')).toBeTruthy();
      expect(screen.getByText(expected)).toBeTruthy();
      return;
    }
    const scan = JSON.parse(screen.getByTestId('translation-scan').props.accessibilityLabel);
    expect(scan.label).toBe(expected);
    if (status === 'failed') expect(screen.getByText('Native failure')).toBeTruthy();
  });
});
