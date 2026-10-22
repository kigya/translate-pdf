import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native-legacy';
import { createTranslator } from '../src/i18n/messages';
import type { JobSnapshot } from '../src/native/PdfTranslator';
import { PdfTranslator } from '../src/native/PdfTranslator';
import { TranslationActivity } from '../src/ui/TranslationActivity';
import { palettes } from '../src/ui/theme';

const mockBurst = jest.fn();

jest.mock('../src/native/PdfTranslator', () => ({
  PdfTranslator: { renderPage: jest.fn(), renderDocxPreview: jest.fn(), renderTranslatedPage: jest.fn() },
}));
jest.mock('../src/components/kinetik/confetti-burst', () => {
  const ReactRuntime = require('react');
  const Native = require('react-native');
  return { ConfettiBurst: ReactRuntime.forwardRef(({ children }: { children: React.ReactNode }, ref: React.Ref<unknown>) => {
    ReactRuntime.useImperativeHandle(ref, () => ({ burst: mockBurst }), []);
    return ReactRuntime.createElement(Native.View, { testID: 'confetti-host' }, children);
  }) };
});
jest.mock('../src/components/kinetik/scan-progress', () => ({
  ScanProgress: ({ children }: { children: React.ReactNode }) => require('react').createElement(require('react-native').View, { testID: 'translation-scan' }, children),
}));
jest.mock('../src/components/kinetik/timeline', () => ({
  Timeline: () => require('react').createElement(require('react-native').View, { testID: 'translation-timeline' }),
}));
jest.mock('../src/ui/Controls', () => ({
  Icon: () => require('react').createElement(require('react-native').Text, null, 'document'),
}));
jest.mock('../src/ui/components', () => ({
  Notice: ({ text, retry, retryLabel }: { text: string; retry: () => void; retryLabel: string }) => {
    const Native = require('react-native');
    return require('react').createElement(Native.View, { testID: 'preview-error' },
      require('react').createElement(Native.Text, null, text),
      require('react').createElement(Native.Pressable, { accessibilityRole: 'button', accessibilityLabel: retryLabel, onPress: retry },
        require('react').createElement(Native.Text, null, retryLabel)));
  },
}));

const t = createTranslator('en');
const job = (overrides: Partial<JobSnapshot> = {}): JobSnapshot => ({
  id: 'job-a', documentId: 'doc-a', documentName: 'manual.pdf', modelId: 'local-model',
  sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete',
  completedPages: 2, totalPages: 2, currentPage: null, createdAt: 1, updatedAt: 9,
  ...overrides,
});
const renderActivity = (snapshot: JobSnapshot) => render(
  <TranslationActivity job={snapshot} colors={palettes.light} t={t} locale="en" active />,
);
const flush = async () => act(async () => { await Promise.resolve(); await Promise.resolve(); });

beforeEach(() => {
  mockBurst.mockClear();
  (PdfTranslator.renderPage as jest.Mock).mockReset();
  (PdfTranslator.renderDocxPreview as jest.Mock).mockReset();
  (PdfTranslator.renderTranslatedPage as jest.Mock).mockReset();
  (PdfTranslator.renderPage as jest.Mock).mockResolvedValue('file:///original.png');
  (PdfTranslator.renderDocxPreview as jest.Mock).mockResolvedValue({ uri: 'file:///preview.pdf', pageCount: 1 });
  (PdfTranslator.renderTranslatedPage as jest.Mock).mockResolvedValue('file:///translated.png');
});

describe('completed translation preview', () => {
  test('renders the translated PDF page and never mounts activity progress', async () => {
    renderActivity(job());
    await flush();

    expect(PdfTranslator.renderTranslatedPage).toHaveBeenCalledWith('job-a', 0, 420);
    expect(PdfTranslator.renderPage).not.toHaveBeenCalled();
    expect(screen.queryByTestId('translation-scan')).toBeNull();
    expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///translated.png' });
    expect(screen.getByTestId('translation-timeline')).toBeTruthy();
  });

  test('renders translated DOCX to a local PDF before requesting its translated first page', async () => {
    renderActivity(job({ documentFormat: 'docx', documentName: 'manual.docx' }));
    await flush();

    expect(PdfTranslator.renderDocxPreview).toHaveBeenCalledWith('doc-a', 'job-a', true);
    expect(PdfTranslator.renderTranslatedPage).toHaveBeenCalledWith('job-a', 0, 420);
    expect(PdfTranslator.renderPage).not.toHaveBeenCalled();
    expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///translated.png' });
  });

  test('shows loading then a localized retry notice without changing completed job state', async () => {
    (PdfTranslator.renderTranslatedPage as jest.Mock)
      .mockRejectedValueOnce(new Error('private native error'))
      .mockResolvedValueOnce('file:///retry-translated.png');
    const snapshot = job();
    renderActivity(snapshot);
    expect(screen.getByRole('progressbar', { name: 'Preparing your library…' })).toBeTruthy();
    await flush();

    expect(screen.getByTestId('preview-error')).toBeTruthy();
    expect(screen.getByText(t('unknownError'))).toBeTruthy();
    expect(screen.getByText('Complete')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: t('retry') }));
    await flush();

    expect(PdfTranslator.renderTranslatedPage).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///retry-translated.png' });
    expect(screen.getByText('Complete')).toBeTruthy();
  });

  test('replaces source activity with translated preview when job completes', async () => {
    (PdfTranslator.renderPage as jest.Mock).mockResolvedValue('file:///original.png');
    const view = render(<TranslationActivity job={job({ status: 'running', stage: 'translating' })} colors={palettes.light} t={t} locale="en" active />);
    await flush();
    expect(screen.getByTestId('translation-scan')).toBeTruthy();

    view.rerender(<TranslationActivity job={job({ status: 'completed', stage: 'complete', updatedAt: 10 })} colors={palettes.light} t={t} locale="en" active />);
    expect(screen.queryByTestId('translation-scan')).toBeNull();
    expect(screen.queryByRole('image')).toBeNull();
    await flush();
    expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///translated.png' });
    expect(PdfTranslator.renderPage).toHaveBeenCalledTimes(1);
    expect(PdfTranslator.renderTranslatedPage).toHaveBeenCalledWith('job-a', 0, 420);
  });

  test('ignores a late translated page from a previous job', async () => {
    let resolveOld!: (uri: string) => void;
    (PdfTranslator.renderTranslatedPage as jest.Mock)
      .mockReturnValueOnce(new Promise<string>(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce('file:///job-b.png');
    const view = renderActivity(job());
    view.rerender(<TranslationActivity job={job({ id: 'job-b', documentId: 'doc-b', documentName: 'new.pdf' })} colors={palettes.light} t={t} locale="en" active />);
    await flush();
    resolveOld('file:///job-a-late.png');
    await flush();

    expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///job-b.png' });
    expect(screen.queryByLabelText(/job-a-late/)).toBeNull();
  });

  test('ignores a late preparation error after switching to a different completed result', async () => {
    let rejectOld!: (error: Error) => void;
    (PdfTranslator.renderTranslatedPage as jest.Mock)
      .mockReturnValueOnce(new Promise<string>((_resolve, reject) => { rejectOld = reject; }))
      .mockResolvedValueOnce('file:///new-result.png');
    const view = renderActivity(job());
    view.rerender(<TranslationActivity job={job({ id: 'job-b', documentId: 'doc-b', updatedAt: 10 })} colors={palettes.light} t={t} locale="en" active />);
    await flush();
    rejectOld(new Error('old private error'));
    await flush();

    expect(screen.queryByTestId('preview-error')).toBeNull();
    expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///new-result.png' });
  });
});


test('treats a zero-page DOCX conversion as a recoverable preview error', async () => {
  (PdfTranslator.renderDocxPreview as jest.Mock).mockResolvedValueOnce({ uri: 'file:///empty.pdf', pageCount: 0 });
  renderActivity(job({ documentFormat: 'docx' }));
  await flush();
  expect(screen.getByTestId('preview-error')).toBeTruthy();
  expect(PdfTranslator.renderTranslatedPage).not.toHaveBeenCalled();
});

test('rejects an empty rendered image URI and lets the user retry', async () => {
  (PdfTranslator.renderTranslatedPage as jest.Mock).mockResolvedValueOnce('');
  renderActivity(job());
  await flush();
  expect(screen.getByTestId('preview-error')).toBeTruthy();
  fireEvent.press(screen.getByRole('button', { name: t('retry') }));
  await flush();
  expect(screen.getByRole('image').props.source).toEqual({ uri: 'file:///translated.png' });
});

test('image decoding failure replaces the completed preview with a retry action', async () => {
  renderActivity(job());
  await flush();
  fireEvent(screen.getByRole('image'), 'error', { nativeEvent: { error: 'decode failed' } });
  expect(screen.queryByRole('image')).toBeNull();
  expect(screen.getByRole('button', { name: t('retry') })).toBeTruthy();
  expect(screen.getByText('Complete')).toBeTruthy();
});

test('an old image error cannot replace the preview for a newly selected job', async () => {
  const view = renderActivity(job());
  await flush();
  const previousError = screen.getByRole('image').props.onError;
  view.rerender(<TranslationActivity job={job({ id: 'job-b' })} colors={palettes.light} t={t} locale="en" active />);
  await flush();
  act(() => previousError());
  expect(screen.queryByTestId('preview-error')).toBeNull();
  expect(screen.getByRole('image')).toBeTruthy();
});
