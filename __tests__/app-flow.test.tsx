import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AppState, StyleSheet, TextInput } from 'react-native';

jest.mock('react-native-safe-area-context', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  return {
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => ReactModule.createElement(ReactNative.View, null, children),
    useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
  };
});

jest.mock('react-native-reanimated', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  const AnimatedView = ReactModule.forwardRef((props: object, ref: object) => ReactModule.createElement(ReactNative.View, { ...props, ref }));
  const FadeIn = { duration: () => undefined };
  return {
    __esModule: true,
    default: { View: AnimatedView },
    View: AnimatedView,
    useReducedMotion: () => true,
    useSharedValue: (value: number) => ({ value }),
    useAnimatedStyle: (callback: () => unknown) => callback(),
    cancelAnimation: () => undefined,
    withRepeat: (value: unknown) => value,
    withTiming: (value: unknown) => value,
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    Extrapolation: { CLAMP: 'clamp' },
    FadeIn,
  };
});

jest.mock('../src/native/PdfTranslator', () => ({
  PdfTranslator: {
    bootstrap: jest.fn(), pickPdf: jest.fn(), startModelDownload: jest.fn(), pauseModelDownload: jest.fn(), deleteModel: jest.fn(),
    startJob: jest.fn(), pauseJob: jest.fn(), resumeJob: jest.fn(), cancelJob: jest.fn(), getJob: jest.fn(),
    renderPage: jest.fn(), renderTranslatedPage: jest.fn(), previewJob: jest.fn(), exportJob: jest.fn(), shareExport: jest.fn(), openPdf: jest.fn(),
    setOnboarding: jest.fn(), setSettings: jest.fn(), saveGlossaryEntry: jest.fn(), deleteGlossaryEntry: jest.fn(),
    requestPermissions: jest.fn(), requestNotificationPermission: jest.fn(),
  },
}));

import App from '../App';

jest.setTimeout(15_000);

const native = jest.requireMock('../src/native/PdfTranslator').PdfTranslator as Record<string, jest.Mock>;

const snapshot = (overrides: Record<string, unknown> = {}) => ({
  schemaVersion: 1,
  device: {
    manufacturer: 'Example', model: 'Example phone', apiLevel: 35, totalMemoryBytes: 8_000_000_000,
    availableMemoryBytes: 5_000_000_000, availableDiskBytes: 5_000_000_000, reservedMemoryBytes: 1_000_000_000,
    recommendedModelAvailable: true, recommendedModelId: 'model-q4', recommendationReason: 'Ready for local work.', recommendationVerified: false,
  },
  preferences: { theme: 'system', language: 'system' },
  onboarding: { version: 1, page: 4, done: true },
  models: [{ id: 'model-q4', label: 'Q4 candidate', revision: 'pinned', bytes: 1_000_000, sha256: '0123456789abcdef0123456789abcdef', verified: false, checksumPinned: true, translationEnabled: true, status: 'ready', downloadedBytes: 1_000_000 }],
  documents: [{ id: 'doc-1', name: 'manual.pdf', pageCount: 12, importedAt: 1 }],
  jobs: [], glossary: [],
  ...overrides,
});

async function renderApp(data: unknown) {
  native.bootstrap.mockResolvedValue(data);
  const view = render(<App />);
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  await waitFor(() => expect(screen.queryByText('Preparing your library…')).toBeNull(), { timeout: 3000 });
  return view;
}

describe('native-backed app flows', () => {
  beforeEach(() => {
    cleanup();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('surfaces model-download errors without requesting OS permissions', async () => {
    await renderApp(snapshot({ models: [{ ...snapshot().models[0], status: 'error', error: 'Pinned checksum did not match.' }] }));
    fireEvent.press(screen.getByRole('button', { name: 'Model settings' }));

    expect(screen.getByText('Download error')).toBeOnTheScreen();
    expect(screen.getByText('Pinned checksum did not match.')).toBeOnTheScreen();
    expect(native.startModelDownload).not.toHaveBeenCalled();

    native.startModelDownload.mockRejectedValue(new Error('The network is offline.'));
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Download model' }));
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });
    expect(native.startModelDownload).toHaveBeenCalledWith('model-q4');
    expect(screen.getByText('The network is offline.')).toBeOnTheScreen();
    expect(native.requestPermissions).not.toHaveBeenCalled();
    expect(native.requestNotificationPermission).not.toHaveBeenCalled();
  });

  it('blocks a checksum-pinned compact candidate from starting an unvalidated translation', async () => {
    const compact = { ...snapshot().models[0], id: 'model-iq3', label: 'Compact candidate', status: 'ready', translationEnabled: false };
    await renderApp(snapshot({ models: [compact], device: { ...snapshot().device, recommendedModelId: compact.id } }));
    fireEvent.press(screen.getByText('manual.pdf'));

    expect(await screen.findByText('Download a model before starting translation.')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Choose a model' })).toBeOnTheScreen();
    expect(native.startJob).not.toHaveBeenCalled();
    expect(native.startModelDownload).not.toHaveBeenCalled();
    expect(native.requestPermissions).not.toHaveBeenCalled();
    fireEvent.press(screen.getByRole('button', { name: 'Choose a model' }));
    expect(await screen.findByText('Compact candidate')).toBeOnTheScreen();
  });

  it('explains a device without an installable model instead of presenting download controls', async () => {
    await renderApp(snapshot({ models: [], device: { ...snapshot().device, recommendedModelAvailable: false, recommendationReason: 'This device has insufficient free memory.' } }));
    fireEvent.press(screen.getByRole('button', { name: 'Model settings' }));

    expect(screen.getByText('Android preview')).toBeOnTheScreen();
    expect(screen.getByText('This device has insufficient free memory.')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Download' })).toBeNull();
    expect(native.startModelDownload).not.toHaveBeenCalled();
  });

  it('keeps a paused offline job resumable and reports the failed retry without permission preflight', async () => {
    const job = {
      id: 'job-1', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru',
      status: 'paused', stage: 'translating', completedPages: 2, totalPages: 12, completedBlocks: 8, discoveredBlocks: 10,
      currentPage: 2, createdAt: 1, updatedAt: 2,
    };
    await renderApp(snapshot({ jobs: [job] }));
    fireEvent.press(screen.getByText('manual.pdf'));

    expect(screen.getByText('Paused')).toBeOnTheScreen();
    expect(screen.getByText('Page 3')).toBeOnTheScreen();
    native.resumeJob.mockRejectedValue(new Error('Device is offline. Resume when online.'));
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Resume' }));
      await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
    });

    expect(native.resumeJob).toHaveBeenCalledWith('job-1');
    expect(screen.getByText('Device is offline. Resume when online.')).toBeOnTheScreen();
    expect(native.requestPermissions).not.toHaveBeenCalled();
    expect(native.requestNotificationPermission).not.toHaveBeenCalled();
  });

  it('offers retry for a failed job while keeping pause, cancel, and completed-preview controls hidden', async () => {
    const failedJob = { id: 'failed-1', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'failed', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 0, error: 'The model file could not be read.', createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [failedJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));

    expect(await screen.findByText('The model file could not be read.')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Cancel translation' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Review translation' })).toBeNull();
    native.resumeJob.mockResolvedValue({ ...failedJob, status: 'running' });
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(native.resumeJob).toHaveBeenCalledWith('failed-1'));
  });

  it('opens the local privacy and license explanations from settings and returns to the prior screen', async () => {
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
    fireEvent.press(screen.getByRole('button', { name: 'Privacy' }));
    expect(await screen.findByText(/PDFs, extracted text, glossary entries and translations stay on your device/)).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByRole('tab', { name: 'Settings' })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Licenses' }));
    expect(await screen.findByText(/Review their notices before redistribution/)).toBeOnTheScreen();
  });

  it('shows bootstrap failure and recovers through the visible retry action', async () => {
    native.bootstrap.mockRejectedValueOnce(new Error('Native database is unavailable.'));
    render(<App />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(await screen.findByText('Native database is unavailable.', {}, { timeout: 3000 })).toBeOnTheScreen();

    native.bootstrap.mockResolvedValue(snapshot());
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      await Promise.resolve(); await Promise.resolve();
    });
    expect(await screen.findByText('Your manuals stay yours.', {}, { timeout: 3000 })).toBeOnTheScreen();
  });

  it('uses the localized fallback for a bootstrap rejection that is not an Error', async () => {
    native.bootstrap.mockRejectedValueOnce('database unavailable');
    render(<App />);
    expect(await screen.findByText('The operation did not finish. Your local files are still available.', {}, { timeout: 3000 })).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Your manuals stay yours.', {}, { timeout: 3000 })).toBeOnTheScreen();
  });

  it('replays the introduction from settings and persists the incomplete state', async () => {
    native.setOnboarding.mockResolvedValue({ version: 1, page: 0, done: false });
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Replay introduction' }));
      await Promise.resolve(); await Promise.resolve();
    });

    expect(native.setOnboarding).toHaveBeenCalledWith({ version: 1, page: 0, done: false });
    expect(screen.getByText('Read the manual in your language')).toBeOnTheScreen();
  });

  it('finishes onboarding through the native-valid final page and enters the library', async () => {
    native.setOnboarding
      .mockResolvedValueOnce({ version: 1, page: 3, done: false })
      .mockResolvedValueOnce({ version: 1, page: 3, done: true });
    await renderApp(snapshot({ onboarding: { version: 1, page: 0, done: false } }));

    fireEvent.press(screen.getByRole('button', { name: 'Page 4 of 4' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Done' })).toBeOnTheScreen());
    fireEvent.press(screen.getByRole('button', { name: 'Done' }));

    await waitFor(() => expect(native.setOnboarding).toHaveBeenLastCalledWith({ version: 1, page: 3, done: true }));
    expect(await screen.findByRole('tab', { name: 'Library' })).toBeOnTheScreen();
    expect(screen.queryByText('Read the manual in your language')).toBeNull();
  });

  it('keeps the library empty when import is canceled and reports picker failures', async () => {
    native.pickPdf.mockResolvedValueOnce(null);
    await renderApp(snapshot({ documents: [] }));
    fireEvent.press(screen.getByRole('button', { name: 'Import a PDF' }));
    await waitFor(() => expect(native.pickPdf).toHaveBeenCalledTimes(1));
    expect(screen.getByText(/Your library is ready/)).toBeOnTheScreen();
    expect(screen.queryByText('Prepare translation')).toBeNull();

    native.pickPdf.mockRejectedValueOnce(new Error('The document provider is unavailable.'));
    fireEvent.press(screen.getByRole('button', { name: 'Import a PDF' }));
    expect(await screen.findByText('The document provider is unavailable.')).toBeOnTheScreen();
    native.pickPdf.mockRejectedValueOnce('picker failed without an Error object');
    fireEvent.press(screen.getByRole('button', { name: 'Import a PDF' }));
    expect(await screen.findByText('The operation did not finish. Your local files are still available.')).toBeOnTheScreen();
    expect(native.requestPermissions).not.toHaveBeenCalled();
  });

  it('persists appearance and language choices from settings', async () => {
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
    native.setSettings.mockResolvedValue({ theme: 'dark', language: 'ru' });

    fireEvent.press(screen.getByRole('radio', { name: 'Dark' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ theme: 'dark' }));
    fireEvent.press(screen.getByRole('radio', { name: 'Русский' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ language: 'ru' }));
  });

  it('shows and retries a localized home refresh failure after the UI language changes', async () => {
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
    native.setSettings.mockResolvedValue({ theme: 'system', language: 'ru' });
    native.bootstrap
      .mockResolvedValueOnce(snapshot({ preferences: { theme: 'system', language: 'ru' } }))
      .mockRejectedValueOnce(new Error('Не удалось обновить библиотеку.'));

    fireEvent.press(screen.getByRole('radio', { name: 'Русский' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ language: 'ru' }));
    fireEvent.press(screen.getByRole('tab', { name: 'Библиотека' }));
    expect(await screen.findByText('Не удалось обновить библиотеку.')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeOnTheScreen();

    native.bootstrap.mockResolvedValue(snapshot({ preferences: { theme: 'system', language: 'ru' } }));
    fireEvent.press(screen.getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(screen.queryByText('Не удалось обновить библиотеку.')).toBeNull());
    expect(native.bootstrap).toHaveBeenCalled();
  });

  it('applies safe-area insets to the app header and non-tab route content', async () => {
    const safeArea = jest.requireMock('react-native-safe-area-context') as { useSafeAreaInsets: jest.Mock };
    jest.spyOn(safeArea, 'useSafeAreaInsets').mockReturnValue({ top: 24, right: 8, bottom: 16, left: 8 });
    await renderApp(snapshot());
    const animatedContent = () => {
      let node: ReturnType<typeof screen.getByRole> | null = screen.getByRole('header');
      while (node) {
        const style = StyleSheet.flatten(node.props.style);
        if (style?.paddingTop != null) return style;
        node = node.parent;
      }
      return undefined;
    };
    expect(animatedContent()).toEqual(expect.objectContaining({ paddingTop: 24, paddingLeft: 8, paddingRight: 8, paddingBottom: 0 }));

    fireEvent.press(screen.getByRole('button', { name: 'Model settings' }));
    expect(animatedContent()).toEqual(expect.objectContaining({ paddingTop: 24, paddingLeft: 8, paddingRight: 8, paddingBottom: 16 }));
  });

  it('surfaces onboarding persistence errors while leaving replay available', async () => {
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('tab', { name: 'Settings' }));
    native.setOnboarding.mockRejectedValueOnce(new Error('Preferences could not be saved.'));
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Replay introduction' })); await Promise.resolve(); });

    expect(await screen.findByText('Preferences could not be saved.')).toBeOnTheScreen();
    expect(screen.queryByText('Read the manual in your language')).toBeNull();
  });

  it('adds, edits, and removes a glossary term with explicit confirmation from the store', async () => {
    const doc = { id: 'picked', name: 'Boiler guide.pdf', pageCount: 3, importedAt: 2 };
    const term = { id: 'term-1', source: 'radiator', target: 'радиатор', createdAt: 3 };
    native.pickPdf.mockResolvedValue(doc);
    native.saveGlossaryEntry.mockResolvedValue(term);
    native.deleteGlossaryEntry.mockResolvedValue(undefined);
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Import a PDF' }));
    expect(await screen.findByText('Boiler guide.pdf')).toBeOnTheScreen();

    native.bootstrap.mockResolvedValue(snapshot({ glossary: [term], documents: [doc] }));
    fireEvent.changeText(screen.UNSAFE_getAllByType(TextInput)[0], ' radiator ');
    fireEvent.changeText(screen.UNSAFE_getAllByType(TextInput)[1], ' радиатор ');
    fireEvent.press(screen.getByRole('button', { name: 'Add term' }));
    await waitFor(() => expect(native.saveGlossaryEntry).toHaveBeenCalledWith({ id: expect.any(String), source: 'radiator', target: 'радиатор' }));

    fireEvent.press(screen.getByRole('button', { name: 'Edit term: radiator' }));
    fireEvent.changeText(screen.UNSAFE_getAllByType(TextInput)[1], 'батарея');
    fireEvent.press(screen.getByRole('button', { name: 'Save term' }));
    await waitFor(() => expect(native.saveGlossaryEntry).toHaveBeenLastCalledWith({ id: 'term-1', source: 'radiator', target: 'батарея' }));

    fireEvent.press(screen.getByRole('button', { name: 'Remove term' }));
    await waitFor(() => expect(native.deleteGlossaryEntry).toHaveBeenCalledWith('term-1'));
  });

  it('starts a translated job with the selected model and exposes pause, resume, and cancel actions', async () => {
    const doc = { id: 'picked', name: 'Boiler guide.pdf', pageCount: 3, importedAt: 2 };
    const runningJob = {
      id: 'job-new', documentId: 'picked', documentName: doc.name, modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru',
      status: 'running', stage: 'translating', completedPages: 1, totalPages: 3, completedBlocks: 4, discoveredBlocks: 6, totalBlocks: null,
      currentPage: 0, createdAt: 4, updatedAt: 5,
    };
    native.pickPdf.mockResolvedValue(doc);
    native.startJob.mockResolvedValue(runningJob);
    native.pauseJob.mockResolvedValue({ ...runningJob, status: 'paused' });
    native.resumeJob.mockResolvedValue(runningJob);
    native.cancelJob.mockResolvedValue({ ...runningJob, status: 'canceled' });
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Import a PDF' }));
    expect(await screen.findByText('Boiler guide.pdf')).toBeOnTheScreen();
    native.bootstrap
      .mockResolvedValueOnce(snapshot({ jobs: [runningJob], documents: [doc] }))
      .mockResolvedValueOnce(snapshot({ jobs: [{ ...runningJob, status: 'paused' }], documents: [doc] }))
      .mockResolvedValueOnce(snapshot({ jobs: [runningJob], documents: [doc] }))
      .mockResolvedValueOnce(snapshot({ jobs: [{ ...runningJob, status: 'canceled' }], documents: [doc] }));
    fireEvent.press(screen.getByRole('button', { name: 'Translate PDF' }));
    await waitFor(() => expect(native.startJob).toHaveBeenCalledWith({ documentId: 'picked', sourceLanguage: 'en', targetLanguage: 'ru', modelId: 'model-q4' }));
    expect(await screen.findByText('Translating locally')).toBeOnTheScreen();
    expect(screen.getByText('Page 1')).toBeOnTheScreen();
    expect(screen.getByText(/Blocks translated: 4/)).toBeOnTheScreen();
    expect(screen.getByText(/Blocks found so far: 6/)).toBeOnTheScreen();

    fireEvent.press(screen.getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(native.pauseJob).toHaveBeenCalledWith('job-new'));
    fireEvent.press(screen.getByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(native.resumeJob).toHaveBeenCalledWith('job-new'));
    fireEvent.press(screen.getByRole('button', { name: 'Cancel translation' }));
    await waitFor(() => expect(native.cancelJob).toHaveBeenCalledWith('job-new'));
  });

  it('loads original and translated preview pages and supports result export and sharing', async () => {
    const completeJob = {
      id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru',
      status: 'completed', stage: 'complete', completedPages: 1, totalPages: 1, completedBlocks: 1, discoveredBlocks: 1, totalBlocks: 1,
      currentPage: 0, createdAt: 4, updatedAt: 5,
    };
    const details = {
      job: { ...completeJob, totalPages: 2 }, pages: [{ index: 0, status: 'completed' }, { index: 1, status: 'completed' }],
      selectedPage: { index: 0, width: 612, height: 792, status: 'completed', blocks: [{ id: 'b1', sourceText: 'Radiator', translatedText: 'Радиатор', left: 10, top: 10, right: 40, bottom: 20, fontSize: 12, source: 'native', objectIndex: 1, status: 'translated' }] },
    };
    native.getJob.mockImplementation(async (_jobId: string, pageIndex: number) => ({ ...details, selectedPage: { ...details.selectedPage, index: pageIndex } }));
    native.renderTranslatedPage.mockResolvedValue('file:///private/translated.png');
    native.renderPage.mockResolvedValue('file:///private/original.png');
    native.exportJob.mockResolvedValue({ id: 'export-1', uri: 'content://export-1', displayName: 'manual-translated.pdf' });
    native.shareExport.mockResolvedValue(undefined);
    await renderApp(snapshot({ jobs: [completeJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));
    expect(await screen.findByText('Радиатор')).toBeOnTheScreen();
    await waitFor(() => expect(native.renderTranslatedPage).toHaveBeenCalledWith('job-done', 0, expect.any(Number)));

    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 1));
    expect(await screen.findByLabelText('Page 2')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Original text' }));
    await waitFor(() => expect(native.renderPage).toHaveBeenCalledWith('doc-1', 1, expect.any(Number)));
    fireEvent.press(screen.getByRole('button', { name: 'Previous' }));
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 0));
    fireEvent.press(screen.getByRole('button', { name: 'Open translated PDF' }));
    await waitFor(() => expect(native.previewJob).toHaveBeenCalledWith('job-done'));
    fireEvent.press(screen.getByRole('button', { name: 'Open original PDF' }));
    await waitFor(() => expect(native.openPdf).toHaveBeenCalledWith('doc-1'));
    fireEvent.press(screen.getByRole('button', { name: 'Save a copy' }));
    await waitFor(() => expect(native.exportJob).toHaveBeenCalledWith('job-done'));
    fireEvent.press(screen.getByRole('button', { name: 'Share' }));
    await waitFor(() => expect(native.shareExport).toHaveBeenCalledWith('export-1'));
  });

  it('offers preview, save, and share actions when a newly started job completes immediately', async () => {
    const doc = { id: 'picked', name: 'Boiler guide.pdf', pageCount: 1, importedAt: 2 };
    const completeJob = { id: 'job-fast', documentId: doc.id, documentName: doc.name, modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 3, updatedAt: 4 };
    native.pickPdf.mockResolvedValue(doc);
    native.startJob.mockResolvedValue(completeJob);
    native.exportJob.mockResolvedValue({ id: 'export-fast', uri: 'content://export-fast', displayName: 'Boiler guide translated.pdf' });
    native.shareExport.mockResolvedValue(undefined);
    native.getJob.mockResolvedValue({ job: completeJob, pages: [{ index: 0, status: 'completed' }], selectedPage: { index: 0, width: 600, height: 800, status: 'completed', blocks: [] } });
    native.renderTranslatedPage.mockResolvedValue('file:///private/fast-preview.png');
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Import a PDF' }));
    expect(await screen.findByText('Boiler guide.pdf')).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot({ documents: [doc], jobs: [completeJob] }));
    fireEvent.press(screen.getByRole('button', { name: 'Translate PDF' }));
    await waitFor(() => expect(native.startJob).toHaveBeenCalled());
    expect(await screen.findByText('Translation complete')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Save a copy' }));
    await waitFor(() => expect(native.exportJob).toHaveBeenCalledWith('job-fast'));
    fireEvent.press(screen.getByRole('button', { name: 'Share' }));
    await waitFor(() => expect(native.shareExport).toHaveBeenCalledWith('export-fast'));
    fireEvent.press(screen.getByRole('button', { name: 'Review translation' }));
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-fast', 0));
  });

  it('pauses a partial model download and deletes the verified local model', async () => {
    const downloading = { ...snapshot().models[0], status: 'downloading', downloadedBytes: 450_000 };
    native.pauseModelDownload.mockResolvedValue({ ...downloading, status: 'paused' });
    native.deleteModel.mockResolvedValue(undefined);
    await renderApp(snapshot({ models: [downloading] }));
    fireEvent.press(screen.getByRole('button', { name: 'Model settings' }));
    expect(screen.getByText(/439 KB \/ 977 KB/)).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot({ models: [{ ...downloading, status: 'ready', downloadedBytes: downloading.bytes }] }));
    fireEvent.press(screen.getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(native.pauseModelDownload).toHaveBeenCalledWith('model-q4'));

    expect(await screen.findByRole('button', { name: 'Delete' })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(native.deleteModel).toHaveBeenCalledWith('model-q4'));
    expect(native.requestPermissions).not.toHaveBeenCalled();
  });

  it('resumes an already paused model download without starting device permission requests', async () => {
    const paused = { ...snapshot().models[0], status: 'paused', downloadedBytes: 220_000 };
    native.startModelDownload.mockResolvedValue({ ...paused, status: 'downloading' });
    native.bootstrap.mockResolvedValue(snapshot({ models: [paused] }));
    await renderApp(snapshot({ models: [paused] }));
    fireEvent.press(screen.getByRole('button', { name: 'Model settings' }));

    expect(screen.getByRole('button', { name: 'Resume' })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(native.startModelDownload).toHaveBeenCalledWith('model-q4'));
    expect(native.requestPermissions).not.toHaveBeenCalled();
  });

  it('formats measured job ETAs and shows discovered versus fully counted blocks', async () => {
    const jobs = [
      { id: 'seconds', documentId: 'doc-1', documentName: 'Seconds.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 0, totalPages: 12, completedBlocks: 2, discoveredBlocks: 5, totalBlocks: null, etaSeconds: 45, etaRangeSeconds: null, currentPage: 0, createdAt: 1, updatedAt: 1 },
      { id: 'minutes', documentId: 'doc-1', documentName: 'Minutes.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 5, totalPages: 12, completedBlocks: 8, discoveredBlocks: 9, totalBlocks: 10, etaSeconds: null, etaRangeSeconds: { min: 60, max: 125 }, currentPage: 4, createdAt: 1, updatedAt: 1 },
      { id: 'hours', documentId: 'doc-1', documentName: 'Hours.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, completedBlocks: 1, discoveredBlocks: 2, totalBlocks: null, etaSeconds: 3660, etaRangeSeconds: null, currentPage: null, createdAt: 1, updatedAt: 1 },
    ];
    await renderApp(snapshot({ jobs }));
    fireEvent.press(screen.getByText('Seconds.pdf'));
    expect(await screen.findByText(/Estimated time remaining: ~45 sec/)).toBeOnTheScreen();
    expect(screen.getByText(/Blocks translated: 2 · Blocks found so far: 5/)).toBeOnTheScreen();

    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    fireEvent.press(screen.getByText('Minutes.pdf'));
    expect(await screen.findByText(/Estimated time remaining: ~1 min–3 min/)).toBeOnTheScreen();
    expect(screen.getByText(/Blocks translated: 8 \/ 10/)).toBeOnTheScreen();

    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    fireEvent.press(screen.getByText('Hours.pdf'));
    expect(await screen.findByText(/Estimated time remaining: ~1 h 1 min/)).toBeOnTheScreen();

  });

  it('does not render a pending preview page until native page extraction is complete', async () => {
    const completeJob = { id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 2, currentPage: 0, createdAt: 1, updatedAt: 2 };
    const pendingDetails = { job: completeJob, pages: [{ index: 0, status: 'pending' }], selectedPage: { index: 0, width: 600, height: 800, status: 'pending', blocks: [] } };
    native.getJob.mockResolvedValueOnce(pendingDetails);
    await renderApp(snapshot({ jobs: [completeJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));
    expect(await screen.findByText('Preparing your library…')).toBeOnTheScreen();
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 0));
    expect(native.renderTranslatedPage).not.toHaveBeenCalled();

  });

  it('reports a non-Error rejection from native preview data with the localized fallback', async () => {
    const completeJob = { id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    native.getJob.mockRejectedValue('unstructured native failure');
    await renderApp(snapshot({ jobs: [completeJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));
    expect(await screen.findByText('The operation did not finish. Your local files are still available.')).toBeOnTheScreen();
  });

  it('ignores a stale preview response after the user returns to the library', async () => {
    const completeJob = { id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    let resolveDetails: ((value: unknown) => void) | undefined;
    native.getJob.mockImplementation(() => new Promise(resolve => { resolveDetails = resolve; }));
    await renderApp(snapshot({ jobs: [completeJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 0));
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));

    await act(async () => {
      resolveDetails?.({ job: completeJob, pages: [], selectedPage: null });
      await Promise.resolve();
    });
    expect(screen.getByRole('tab', { name: 'Library' })).toBeOnTheScreen();
    expect(screen.queryByText('The operation did not finish. Your local files are still available.')).toBeNull();
  });

  it('shows partial block warnings and reports a missing original thumbnail', async () => {
    const partialJob = { id: 'job-partial', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    native.getJob.mockResolvedValue({ job: partialJob, pages: [{ index: 0, status: 'partial' }], selectedPage: { index: 0, width: 600, height: 800, status: 'partial', error: 'Some scanned labels need review.', blocks: [{ id: 'b1', sourceText: 'Outlet', translatedText: null, left: 10, top: 10, right: 30, bottom: 20, fontSize: 11, source: 'ocr', objectIndex: null, status: 'partial', error: 'OCR and embedded text disagree.' }] } });
    native.renderTranslatedPage.mockResolvedValue('file:///private/partial.png');
    native.renderPage.mockRejectedValue(new Error('Original page preview is unavailable.'));
    await renderApp(snapshot({ jobs: [partialJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));
    expect(await screen.findByText('Some scanned labels need review.')).toBeOnTheScreen();
    await waitFor(() => expect(screen.getAllByText('OCR and embedded text disagree.')).toHaveLength(2));
    fireEvent.press(screen.getByRole('button', { name: 'Original text' }));
    expect(await screen.findByText('Original page preview is unavailable.')).toBeOnTheScreen();
  });

  it('retains the review warning when partial output has no individual issue blocks', async () => {
    const partialJob = { id: 'job-partial', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    native.getJob.mockResolvedValue({ job: partialJob, pages: [{ index: 0, status: 'partial' }], selectedPage: { index: 0, width: 600, height: 800, status: 'partial', blocks: [] } });
    await renderApp(snapshot({ jobs: [partialJob] }));
    fireEvent.press(screen.getByText('manual.pdf'));
    expect(await screen.findByText('Needs review')).toBeOnTheScreen();
    expect(screen.queryByText('No flagged blocks on this page.')).toBeNull();
  });

  it('cancels the minimum splash timer when unmounted before the library opens', async () => {
    const removeAppState = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove: removeAppState } as ReturnType<typeof AppState.addEventListener>);
    const scheduledHandle = 642 as unknown as ReturnType<typeof setTimeout>;
    const timerSpy = jest.spyOn(globalThis, 'setTimeout').mockReturnValue(scheduledHandle);
    const clearSpy = jest.spyOn(globalThis, 'clearTimeout').mockImplementation(() => undefined);
    native.bootstrap.mockResolvedValue(snapshot());
    const view = render(<App />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(timerSpy).toHaveBeenCalled();
    view.unmount();
    expect(clearSpy).toHaveBeenCalledWith(scheduledHandle);
    expect(removeAppState).toHaveBeenCalledTimes(1);
  });

  it('does not schedule splash completion or update state when bootstrap settles after unmount', async () => {
    let resolveBootstrap: ((value: unknown) => void) | undefined;
    native.bootstrap.mockImplementation(() => new Promise(resolve => { resolveBootstrap = resolve; }));
    const setTimeoutSpy = jest.spyOn(globalThis, 'setTimeout');
    const view = render(<App />);
    view.unmount();
    await act(async () => {
      resolveBootstrap?.(snapshot());
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(setTimeoutSpy).not.toHaveBeenCalled();
  });

  it('cleans up AppState, hardware-back, and active polling subscriptions on unmount', async () => {
    let appStateListener: ((state: string) => void) | undefined;
    const appStateRemove = jest.fn();
    const backRemove = jest.fn();
    const appStateSpy = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      appStateListener = listener as (state: string) => void;
      return { remove: appStateRemove } as ReturnType<typeof AppState.addEventListener>;
    });
    const backHandler = require('react-native').BackHandler;
    const backSpy = jest.spyOn(backHandler, 'addEventListener').mockImplementation(() => ({ remove: backRemove }));
    const intervalSpy = jest.spyOn(globalThis, 'setInterval').mockReturnValue(987 as unknown as ReturnType<typeof setInterval>);
    const clearSpy = jest.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
    const active = { id: 'job-active', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 1 };
    native.bootstrap.mockResolvedValue(snapshot({ jobs: [active] }));
    const view = render(<App />);
    await act(async () => { await new Promise<void>(resolve => setTimeout(resolve, 1600)); });
    expect(screen.queryByText('Preparing your library…')).toBeNull();
    expect(appStateListener).toBeDefined();
    await act(async () => { appStateListener?.('active'); await Promise.resolve(); });
    expect(intervalSpy).toHaveBeenCalled();
    native.bootstrap.mockRejectedValueOnce(new Error('Transient refresh failure.'));
    await act(async () => { (intervalSpy.mock.calls[0][0] as () => void)(); await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByText('Your manuals stay yours.')).toBeOnTheScreen();
    await act(async () => { appStateListener?.('background'); await Promise.resolve(); });
    expect(clearSpy).toHaveBeenCalledWith(987);
    await act(async () => { appStateListener?.('active'); await Promise.resolve(); });
    expect(intervalSpy).toHaveBeenCalledTimes(2);
    fireEvent.press(screen.getByText('manual.pdf'));
    expect(backSpy).toHaveBeenCalledWith('hardwareBackPress', expect.any(Function));

    view.unmount();
    expect(appStateRemove).toHaveBeenCalledTimes(1);
    expect(backRemove).toHaveBeenCalledTimes(1);
    expect(clearSpy).toHaveBeenCalledWith(987);
    appStateSpy.mockRestore();
    backSpy.mockRestore();
    intervalSpy.mockRestore();
    clearSpy.mockRestore();
  });
});
