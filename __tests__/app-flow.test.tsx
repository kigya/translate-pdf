jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native-legacy';
import { AppState, BackHandler, Linking, Modal, Dimensions, Platform, StyleSheet, Image } from 'react-native';

jest.mock('react-native-safe-area-context', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  return {
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => ReactModule.createElement(ReactNative.View, null, children),
    useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
  };
});

let mockReducedMotion: boolean | undefined = true;
jest.mock('react-native-reanimated', () => {
  const ReactNative = require('react-native');
  const ReactModule = require('react');
  const AnimatedView = ReactModule.forwardRef((props: object, ref: object) => ReactModule.createElement(ReactNative.View, { ...props, ref }));
  const FadeIn = { duration: () => undefined };
  const FadeInDown = { delay: () => ({ duration: () => ({ easing: () => undefined }) }) };
  return {
    __esModule: true,
    default: { createAnimatedComponent: (component: unknown) => component, View: AnimatedView, Text: ReactNative.Text, ScrollView: ReactNative.ScrollView, Image: ReactNative.Image },
    View: AnimatedView,
    getUseOfValueInStyleWarning: () => 'Style value warning',
    useReducedMotion: () => mockReducedMotion,
    useSharedValue: (value: number) => ({ value, get() { return this.value; }, set(next: number) { this.value = next; } }),
    useAnimatedStyle: (callback: () => unknown) => callback(),
    useAnimatedProps: (callback: () => unknown) => callback(),
    useDerivedValue: (callback: () => unknown) => ({ get: callback }),
    useAnimatedScrollHandler: () => jest.fn(),
    useFrameCallback: () => ({ setActive: jest.fn() }),
    useAnimatedRef: () => ReactModule.useRef(null),
    cancelAnimation: () => undefined,
    withRepeat: (value: unknown) => value,
    withTiming: (value: unknown, _options?: unknown, callback?: (finished: boolean) => void) => { callback?.(true); return value; },
    withSpring: (value: unknown) => value,
    withDelay: (_delay: number, value: unknown) => value,
    withSequence: (...values: unknown[]) => values.at(-1),
    interpolate: (_value: number, _input: number[], output: number[]) => output[0],
    interpolateColor: (_value: number, _input: number[], output: string[]) => output[0],
    ReduceMotion: { Always: 'always', Never: 'never' },
    Easing: { bezier: (..._values: number[]) => (value: number) => value, linear: (value: number) => value, sin: (value: number) => value, inOut: (easing: unknown) => easing },
    Extrapolation: { CLAMP: 'clamp' },
    FadeIn,
    FadeInDown,
  };
});

jest.mock('../src/native/PdfTranslator', () => ({
  PdfTranslator: {
    bootstrap: jest.fn(), getActivitySnapshot: jest.fn(), pickDocument: jest.fn(), startModelDownload: jest.fn(), pauseModelDownload: jest.fn(), deleteModel: jest.fn(),
    startJob: jest.fn(), pauseJob: jest.fn(), resumeJob: jest.fn(), cancelJob: jest.fn(), deleteJob: jest.fn(), deleteDocument: jest.fn(), getJob: jest.fn(),
    renderPage: jest.fn(), renderTranslatedPage: jest.fn(), renderDocxPreview: jest.fn(), exportDocument: jest.fn(), previewJob: jest.fn(), exportJob: jest.fn(), shareExport: jest.fn(), openPdf: jest.fn(),
    setOnboarding: jest.fn(), setSettings: jest.fn(), setJobColor: jest.fn(), saveGlossaryEntry: jest.fn(), deleteGlossaryEntry: jest.fn(),
    requestPermissions: jest.fn(), requestNotificationPermission: jest.fn(),
    getLicenseNotices: jest.fn().mockResolvedValue([]), getLicenseNotice: jest.fn().mockResolvedValue(''),
  },
}));
jest.mock('../src/components/kinetik/star-rating', () => ({
  StarRating: ({ value, accessibilityLabel, onValueChange }: { value: number; accessibilityLabel: string; onValueChange?: (value: number) => void }) => {
    const ReactRuntime = require('react');
    const ReactNative = require('react-native');
    return ReactRuntime.createElement(ReactNative.View, { accessibilityRole: 'adjustable', accessibilityLabel, accessibilityValue: { min: 0, max: 5, now: value } },
      ...[1, 2, 3, 4, 5].map(rating => ReactRuntime.createElement(ReactNative.Pressable, { key: rating, accessibilityRole: 'button', accessibilityLabel: `Set rating ${rating}`, onPress: () => onValueChange?.(rating) })),
    );
  },
}));
// App-flow tests assert routing and native contracts. Kinetik drawing details
// are covered by the focused component suite; keep these routes viable with
// the deliberately small Skia Jest mock.
jest.mock('../src/components/kinetik/confetti-burst', () => {
  const ReactRuntime = require('react');
  const ReactNative = require('react-native');
  return { ConfettiBurst: ReactRuntime.forwardRef(({ children }: { children?: React.ReactNode }, _ref: unknown) => ReactRuntime.createElement(ReactNative.View, null, children)) };
});
jest.mock('../src/components/kinetik/ring-spinner', () => {
  const ReactRuntime = require('react');
  const ReactNative = require('react-native');
  return { RingSpinner: ({ label }: { label?: string }) => ReactRuntime.createElement(ReactNative.View, { accessibilityRole: 'progressbar', accessibilityLabel: label }) };
});
jest.mock('../src/components/kinetik/scan-progress', () => {
  const ReactRuntime = require('react');
  const ReactNative = require('react-native');
  return { ScanProgress: ({ children, label, progress }: { children?: React.ReactNode; label: string; progress?: number }) => ReactRuntime.createElement(ReactNative.View, null,
    children,
    ReactRuntime.createElement(ReactNative.View, { accessibilityRole: 'progressbar', accessibilityLabel: label, ...(progress === undefined ? {} : { accessibilityValue: { min: 0, max: 100, now: Math.round(progress * 100) } }) }),
  ) };
});

import App from '../App';
import { LibraryScreen, SettingsScreen, TranslationOptionsScreen, SourcePreviewScreen } from '../src/screens/AppScreens';
import { createTranslator } from '../src/i18n/messages';
import { PRIVACY_URL } from '../src/domain/presentation';
import { LargeDocumentHint } from '../src/ui/LargeDocumentHint';
import type { BootstrapSnapshot } from '../src/native/PdfTranslator';

jest.setTimeout(15_000);

const native = jest.requireMock('../src/native/PdfTranslator').PdfTranslator as Record<string, jest.Mock>;

const snapshot = (overrides: Record<string, unknown> = {}) => ({
  schemaVersion: 1,
  device: {
    manufacturer: 'Example', model: 'Example phone', apiLevel: 35, totalMemoryBytes: 8_000_000_000,
    availableMemoryBytes: 5_000_000_000, availableDiskBytes: 5_000_000_000, reservedMemoryBytes: 1_000_000_000,
    recommendedModelAvailable: true, recommendedModelId: 'model-q4', recommendationReason: 'Ready for local work.', recommendationVerified: false,
  },
  preferences: { theme: 'system', language: 'system', selectedModelId: undefined as string | undefined },
  onboarding: { version: 1, page: 4, done: true },
  models: [{ id: 'model-q4', label: 'Q4 candidate', revision: 'pinned', bytes: 1_000_000, sha256: '0123456789abcdef0123456789abcdef', verified: false, checksumPinned: true, translationEnabled: true, status: 'ready', downloadedBytes: 1_000_000 }],
  documents: [{ id: 'doc-1', name: 'manual.pdf', pageCount: 12, importedAt: 1 }],
  glossary: [],
  ...overrides,
  jobs: (Array.isArray(overrides.jobs) ? (overrides.jobs as Array<Record<string, unknown>>).map(job => ({ createdAt: 1, ...job })) : []) as Array<Record<string, unknown>>,
});

async function renderStartedApp({ fastMinimum = true, expectReady = true }: { fastMinimum?: boolean; expectReady?: boolean } = {}) {
  const originalAnimationFrame = globalThis.requestAnimationFrame;
  const rafSpy = jest.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(callback => {
    callback(0);
    return 1;
  });
  const originalTimeout = globalThis.setTimeout;
  const timeoutSpy = fastMinimum ? jest.spyOn(globalThis, 'setTimeout').mockImplementation(((handler: Parameters<typeof setTimeout>[0], timeout?: number, ...args: unknown[]) => {
    if ((timeout ?? 0) >= 1400) {
      if (typeof handler === 'function') handler(...args);
      return 1 as unknown as ReturnType<typeof setTimeout>;
    }
    return originalTimeout(handler, timeout, ...args);
  }) as typeof setTimeout) : null;
  const view = render(<App />);
  await act(async () => {
    fireEvent(screen.getByTestId('custom-splash-screen'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 800 } } });
    await Promise.resolve(); await Promise.resolve();
  });
  rafSpy.mockRestore();
  timeoutSpy?.mockRestore();
  globalThis.requestAnimationFrame = originalAnimationFrame;
  if (expectReady) await waitFor(() => expect(screen.queryByTestId('custom-splash-screen')).toBeNull(), { timeout: 3000 });
  return view;
}

async function renderApp(data: unknown) {
  native.bootstrap.mockResolvedValue(data);
  const activity = data as { jobs?: unknown[]; models?: unknown[]; notifications?: unknown };
  native.getActivitySnapshot.mockResolvedValue({ jobs: activity.jobs ?? [], models: activity.models ?? [], notifications: activity.notifications });
  return renderStartedApp();
}

function pressSettings() { fireEvent.press(screen.getByRole('button', { name: 'Settings' })); }
function pressModelSettings() {
  fireEvent.press(screen.getByRole('button', { name: 'Translation model' }));
  const localPanel = screen.queryAllByRole('button', { name: /^(Compact|Balanced|Maximum),/ })[0];
  if (localPanel) fireEvent.press(localPanel);
}
function expandOnlineModel() { fireEvent.press(screen.getByRole('button', { name: /^Online,/ })); }
async function openDocument(name: string) {
  const allFolder = screen.queryByRole('button', { name: /^All documents,/ });
  if (allFolder) fireEvent.press(allFolder);
  fireEvent.press(screen.getByRole('button', { name }));
}

function layoutOnboarding() {
  fireEvent(screen.getByTestId('onboarding'), 'layout', { nativeEvent: { layout: { x: 0, y: 0, width: 360, height: 700 } } });
}

describe('native-backed app flows', () => {
  beforeEach(() => {
    cleanup();
    mockReducedMotion = true;
    jest.clearAllMocks();
    native.bootstrap.mockReset().mockResolvedValue(snapshot());
    native.getActivitySnapshot.mockReset().mockResolvedValue({ jobs: [], models: snapshot().models, notifications: undefined });
    native.renderPage.mockResolvedValue('file:///private/source.png');
    native.renderDocxPreview.mockResolvedValue({ uri: 'file:///private/word-preview.pdf', pageCount: 2 });
    jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove: jest.fn() });
    jest.spyOn(BackHandler, 'addEventListener').mockReturnValue({ remove: jest.fn() });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('renders a noninteractive captured palette overlay while the new theme is revealed', async () => {
    mockReducedMotion = false;
    await renderApp(snapshot({ preferences: { theme: 'light', language: 'en' } }));
    pressSettings();
    native.setSettings.mockResolvedValue({ theme: 'dark', language: 'en' });
    native.bootstrap.mockResolvedValue(snapshot({ preferences: { theme: 'dark', language: 'en' } }));
    fireEvent.press(screen.getByRole('switch', { name: 'Appearance' }));
    await waitFor(() => expect(require('react-native-view-shot').captureRef).toHaveBeenCalled());
    const image = screen.UNSAFE_getAllByType(Image).find(node => node.props.source?.uri === 'file:///temporary-transition.png');
    expect(image).toBeDefined();
    expect(image!.parent!.props.pointerEvents).toBe('none');
    fireEvent(image!, 'load');
    fireEvent(image!, 'error');
  });

  it('ignores source preview completion after leaving the document', async () => {
    let finish!: (preview: { uri: string; pageCount: number }) => void;
    native.renderDocxPreview.mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    const view = render(<SourcePreviewScreen document={{ id: 'late', name: 'late.docx', format: 'docx', pageCount: 1, importedAt: 1 }} colors={require('../src/ui/theme').palettes.light} t={createTranslator('en')} locale="en" />);
    view.unmount();
    await act(async () => { finish({ uri: 'file:///late.pdf', pageCount: 2 }); await Promise.resolve(); });
    expect(native.renderDocxPreview).toHaveBeenCalledWith('late');
    expect(native.renderPage).not.toHaveBeenCalled();
  });

  it('surfaces model-download errors without requesting OS permissions', async () => {
    await renderApp(snapshot({ models: [{ ...snapshot().models[0], status: 'error', error: 'Pinned checksum did not match.' }] }));
    pressModelSettings();

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

  it('keeps translation disabled for a checksum-pinned model that is not approved for translation', async () => {
    const compact = { ...snapshot().models[0], id: 'model-iq3', label: 'Compact candidate', status: 'ready', translationEnabled: false };
    await renderApp(snapshot({ models: [compact], device: { ...snapshot().device, recommendedModelId: compact.id } }));
    await openDocument('manual.pdf');

    expect(await screen.findByText('Translation Options')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Translate documents' })).toBeDisabled();
    expect(native.startJob).not.toHaveBeenCalled();
    expect(native.startModelDownload).not.toHaveBeenCalled();
    expect(native.requestPermissions).not.toHaveBeenCalled();
    expect(screen.getByText('Compact')).toBeOnTheScreen();
  });

  it('persists a ready model choice and starts a new job with that selected model', async () => {
    const model = snapshot().models[0];
    const models = [
      { ...model, id: 'model-compact', label: 'Compact candidate', status: 'ready', tier: 'compact', requiredMemoryBytes: 1_000, memoryEstimateEligible: true, diskEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available', licenseUrl: 'https://example.org/compact-license' },
      { ...model, id: 'model-balanced', label: 'Balanced candidate', status: 'ready', tier: 'balanced', requiredMemoryBytes: 2_000, memoryEstimateEligible: true, diskEstimateEligible: false, resourceEstimateEligible: false, resourceReason: 'disk' },
      { ...model, id: 'model-quality', label: 'Quality candidate', status: 'ready', tier: 'quality', requiredMemoryBytes: 3_000, memoryEstimateEligible: true, diskEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available', evaluation: { acceptableCases: 29, totalCases: 44 } },
    ];
    let data = snapshot({ models, preferences: { theme: 'system', language: 'system', selectedModelId: 'model-compact' }, device: { ...snapshot().device, recommendedModelId: 'model-compact' } });
    native.bootstrap.mockImplementation(async () => data);
    native.setSettings.mockImplementation(async (settings: Record<string, string>) => {
      data = { ...data, preferences: { ...data.preferences, ...settings } };
      return data.preferences;
    });
    await renderStartedApp();
    native.bootstrap.mockImplementation(async () => data);
    pressModelSettings();

    expect(await screen.findByRole('button', { name: /^Compact,/ })).toBeOnTheScreen();
    expect(await screen.findByRole('button', { name: /^Balanced,/ })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: /^Maximum,/ })).toBeOnTheScreen();
    const tierPanels = screen.getAllByRole('button').map(node => String(node.props.accessibilityLabel ?? ''));
    expect(tierPanels.filter(label => label.includes('70%'))).toHaveLength(1);
    expect(tierPanels.some(label => label.includes('85%'))).toBe(true);
    expect(tierPanels.some(label => label.includes('95%'))).toBe(true);
    expect(screen.queryByText(/Meaning preserved in/)).toBeNull();
    expect(screen.queryByText(createTranslator('en')('qualityPending'))).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: /^Maximum,/ }));
    expect(await screen.findByRole('text', { name: 'Quality candidate' })).toBeOnTheScreen();
    expect(screen.queryByText(/Meaning preserved in 29 of 44 test passages/)).toBeNull();
    expect(screen.queryByText(/Evidence covers only 44 technical English→Russian passages/)).toBeNull();
    expect(screen.getByRole('button', { name: 'Licenses' })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: /^Compact,/ }));
    expect(await screen.findByRole('text', { name: 'Compact candidate' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Licenses' })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: /^Balanced,/ }));
    fireEvent.press(screen.getByRole('button', { name: 'Use this model' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ selectedModelId: 'model-balanced', selectedEngine: { kind: 'local', id: 'model-balanced' } }));
    expect(data.preferences.selectedModelId).toBe('model-balanced');

    await openDocument('manual.pdf');
    expect(await screen.findByText('Translation Options')).toBeOnTheScreen();
    native.startJob.mockResolvedValue({ id: 'job-new', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-balanced', sourceLanguage: 'en', targetLanguage: 'ru', status: 'queued', stage: 'queued', completedPages: 0, totalPages: 12, currentPage: null, createdAt: 1, updatedAt: 1 });
    fireEvent(screen.getByRole('button', { name: 'Translate documents' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
    await waitFor(() => expect(native.startJob).toHaveBeenCalledWith({ documentId: 'doc-1', sourceLanguage: 'en', targetLanguage: 'ru', modelId: 'model-balanced', engine: { kind: 'local', id: 'model-balanced' }, onlineConsent: false, useGlossary: true }));
  });

  it('persists MyMemory as the selected engine and requires consent before sending document text', async () => {
    let data = snapshot() as unknown as BootstrapSnapshot;
    native.bootstrap.mockImplementation(async () => data);
    native.setSettings.mockImplementation(async settings => {
      data = { ...data, preferences: { ...data.preferences, ...settings } };
      return data.preferences;
    });
    await renderStartedApp();
    pressModelSettings();
    expandOnlineModel();
    expect(await screen.findByRole('text', { name: 'MyMemory' })).toBeOnTheScreen();
    fireEvent.press(screen.getAllByRole('button', { name: 'Use this model' })[0]);
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ selectedEngine: { kind: 'online', id: 'mymemory' } }));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });
    expect(data.preferences.selectedEngine).toEqual({ kind: 'online', id: 'mymemory' });
    expect(native.startModelDownload).not.toHaveBeenCalled();

    await openDocument('manual.pdf');
    expect(await screen.findByText('Translation Options')).toBeOnTheScreen();
    act(() => screen.getByRole('button', { name: 'Translate documents' }).props.onAccessibilityAction({ nativeEvent: { actionName: 'activate' } }));
    expect(await screen.findByText('Translate online with MyMemory?')).toBeOnTheScreen();
    expect(screen.getByText(/sent to MyMemory.*stored long term/i)).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByText('Translate online with MyMemory?')).toBeNull());
    expect(native.startJob).not.toHaveBeenCalled();

    native.startJob.mockResolvedValue({ id: 'online-job', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'mymemory', engine: { kind: 'online', id: 'mymemory' }, sourceLanguage: 'en', targetLanguage: 'ru', status: 'queued', stage: 'queued', completedPages: 0, totalPages: 12, currentPage: null, createdAt: 1, updatedAt: 1 });
    act(() => screen.getByRole('button', { name: 'Translate documents' }).props.onAccessibilityAction({ nativeEvent: { actionName: 'activate' } }));
    fireEvent.press(await screen.findByRole('button', { name: 'Agree and translate' }));
    await waitFor(() => expect(native.startJob).toHaveBeenCalledWith({ documentId: 'doc-1', sourceLanguage: 'en', targetLanguage: 'ru', modelId: 'mymemory', engine: { kind: 'online', id: 'mymemory' }, onlineConsent: true, useGlossary: false }));
    expect(native.startModelDownload).not.toHaveBeenCalled();
  });

  it('allows downloading below the estimated memory envelope and shows an advisory', async () => {
    const blocked = { ...snapshot().models[0], id: 'model-blocked', label: 'Blocked candidate', status: 'not_downloaded', tier: 'compact', memoryEstimateEligible: false, diskEstimateEligible: true, resourceEstimateEligible: false, resourceReason: 'memory' };
    await renderApp(snapshot({ models: [blocked], device: { ...snapshot().device, recommendedModelId: 'model-q4' } }));
    pressModelSettings();

    expect(await screen.findByRole('button',{name:createTranslator('en')('modelMemoryWarning')})).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Download model' })).toBeEnabled();
    native.startModelDownload.mockResolvedValue(blocked);
    fireEvent.press(screen.getByRole('button', { name: 'Download model' }));
    await waitFor(() => expect(native.startModelDownload).toHaveBeenCalledWith('model-blocked'));
  });

  it('shows zero bytes downloaded as a measured zero rather than an unavailable value', async () => {
    const downloading = { ...snapshot().models[0], status: 'downloading', bytes: 1_000_000_000, downloadedBytes: 0 };
    await renderApp(snapshot({ models: [downloading] }));
    pressModelSettings();

    expect(screen.getByRole('button', { name: /Compact, 1.00 GB · 70% · 0%/ })).toBeOnTheScreen();
    expect(screen.queryByText(/Meaning preserved in/)).not.toBeOnTheScreen();
  });

  it('formats package sizes in decimal units at KB, MB, and GB boundaries', async () => {
    const sizes = [999, 1_000, 999_499, 1_000_000, 999_499_999, 1_000_000_000];
    const models = sizes.map((bytes, index) => ({ ...snapshot().models[0], id: `size-${index}`, label: `Size ${index}`, status: 'not_downloaded', bytes }));
    await renderApp(snapshot({ models }));
    pressModelSettings();

    const labels = screen.getAllByRole('button').map(node => String(node.props.accessibilityLabel ?? ''));
    expect(labels.some(label => label.includes('999 B'))).toBe(true);
    expect(labels.some(label => label.includes('1 KB'))).toBe(true);
    expect(labels.some(label => label.includes('999 KB'))).toBe(true);
    expect(labels.some(label => label.includes('1 MB'))).toBe(true);
    expect(labels.some(label => label.includes('999 MB'))).toBe(true);
    expect(labels.some(label => label.includes('1.00 GB'))).toBe(true);
  });

  it('shows Gemma license acceptance before a new download while keeping resume distinct', async () => {
    const base = snapshot().models[0];
    const gemma = { ...base, id: 'gemma-new', label: 'TranslateGemma', tier: 'quality', status: 'not_downloaded', licenseUrl: 'https://ai.google.dev/gemma/terms', memoryEstimateEligible: true, diskEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available' };
    const paused = { ...gemma, id: 'gemma-paused', label: 'Paused Gemma', status: 'paused' };
    await renderApp(snapshot({ models: [gemma, paused], device: { ...snapshot().device, recommendedModelId: gemma.id } }));
    pressModelSettings();

    expect(await screen.findByRole('text', { name: 'TranslateGemma' })).toBeOnTheScreen();
    expect(screen.queryByText(createTranslator('en')('gemmaTermsNotice'))).toBeNull();
    expect(screen.getByRole('button', { name: 'Accept and download' })).toBeOnTheScreen();
    fireEvent.press(screen.getAllByRole('button', { name: /^Maximum,/ })[1]);
    expect(screen.getByRole('button', { name: 'Resume' })).toBeOnTheScreen();
    fireEvent.press(screen.getAllByRole('button', { name: /^Maximum,/ })[0]);
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Accept and download' })); await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByText(createTranslator('en')('gemmaTermsNotice'))).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Accept and download' }));
    await waitFor(() => expect(native.startModelDownload).toHaveBeenCalledWith('gemma-new'));

  });

  it('offers a per-job dictionary toggle when the chosen model cannot use saved terms', async () => {
    const unsupported = {
      ...snapshot().models[0], status: 'ready', supportsGlossary: false,
      memoryEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available',
    };
    await renderApp(snapshot({ models: [unsupported], glossary: [{ id: 'g1', source: 'bearing', target: 'подшипник', createdAt: 1 }] }));
    await openDocument('manual.pdf');

    expect(await screen.findByText('Translation Options')).toBeOnTheScreen();
    expect(screen.queryByRole('switch', { name: 'Use dictionary' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Dictionary' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Translate documents' })).toBeEnabled();
    expect(native.startJob).not.toHaveBeenCalled();
  });

  it('keeps a stored job model unchanged when the new-job selection changes', async () => {
    const model = snapshot().models[0];
    const job = { id: 'job-legacy', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'translating', completedPages: 2, totalPages: 12, currentPage: 2, createdAt: 1, updatedAt: 2 };
    const models = [
      { ...model, id: 'model-q4', label: 'Existing job model', status: 'ready', tier: 'balanced', memoryEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available' },
      { ...model, id: 'model-new', label: 'New job model', status: 'ready', tier: 'quality', memoryEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available' },
    ];
    let data = snapshot({ models, jobs: [job], preferences: { theme: 'system', language: 'system', selectedModelId: 'model-q4' } });
    native.bootstrap.mockImplementation(async () => data);
    native.setSettings.mockImplementation(async (settings: Record<string, string>) => {
      data = { ...data, preferences: { ...data.preferences, ...settings } };
      return data.preferences;
    });
    native.resumeJob.mockResolvedValue({ ...job, status: 'running' });
    await renderApp(data);
    native.bootstrap.mockImplementation(async () => data);
    pressModelSettings();
    fireEvent.press(screen.getByRole('button', { name: /^Maximum,/ }));
    fireEvent.press(screen.getByRole('button', { name: 'Use this model' }));
    await waitFor(() => expect(data.preferences.selectedModelId).toBe('model-new'));
    await openDocument('manual.pdf');
    fireEvent.press(await screen.findByRole('button', { name: 'Resume' }));

    await waitFor(() => expect(native.resumeJob).toHaveBeenCalledWith('job-legacy'));
    expect(data.jobs[0].modelId).toBe('model-q4');
    expect(native.startJob).not.toHaveBeenCalled();
  });

  it('explains a device without an installable model instead of presenting download controls', async () => {
    await renderApp(snapshot({ models: [], device: { ...snapshot().device, recommendedModelAvailable: false, recommendationReason: 'This device has insufficient free memory.' } }));
    pressModelSettings();

    expect(screen.getByText('No model available')).toBeOnTheScreen();
    expect(screen.getByText('This device cannot use the available models yet.')).toBeOnTheScreen();
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
    await openDocument('manual.pdf');

    expect(screen.getAllByText('Paused').length).toBeGreaterThan(0);
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

  it('offers the saved job exact model for redownload after cleanup without resuming or changing its model', async () => {
    const model = { ...snapshot().models[0], status: 'not_downloaded' };
    const job = { id: 'paused-cleanup', documentId: 'doc-1', documentName: 'manual.pdf', modelId: model.id, sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    const data = snapshot({ models: [model], jobs: [job] });
    native.startModelDownload.mockResolvedValue({ ...model, status: 'downloading' });
    await renderApp(data);
    await openDocument('manual.pdf');
    expect(screen.queryByRole('button', { name: 'Resume' })).toBeNull();
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Download model' })); });
    await waitFor(() => expect(native.startModelDownload).toHaveBeenCalledWith(model.id));
    expect(screen.getByText('Model settings')).toBeOnTheScreen();
    expect(native.resumeJob).not.toHaveBeenCalled();
    expect(native.startJob).not.toHaveBeenCalled();
    expect(data.jobs[0].modelId).toBe(model.id);
  });

  it('opens a saved job active model transfer without starting a duplicate download', async () => {
    const model = { ...snapshot().models[0], status: 'downloading' };
    const job = { id: 'paused-transfer', documentId: 'doc-1', documentName: 'manual.pdf', modelId: model.id, sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ models: [model], jobs: [job] }));
    await openDocument('manual.pdf');
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    pressModelSettings();
    expect(screen.getByText('Model settings')).toBeOnTheScreen();
    expect(native.startModelDownload).not.toHaveBeenCalled();
    expect(native.resumeJob).not.toHaveBeenCalled();
  });

  it('resumes export-only saved work without requiring deleted model weights again', async () => {
    const model = { ...snapshot().models[0], status: 'not_downloaded' };
    const job = { id: 'paused-export', documentId: 'doc-1', documentName: 'manual.pdf', modelId: model.id, sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'exporting', completedPages: 12, totalPages: 12, currentPage: 11, createdAt: 1, updatedAt: 2 };
    native.resumeJob.mockResolvedValue(job);
    await renderApp(snapshot({ models: [model], jobs: [job] }));
    await openDocument('manual.pdf');
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: 'Resume' })); });
    expect(native.resumeJob).toHaveBeenCalledWith('paused-export');
    expect(native.startModelDownload).not.toHaveBeenCalled();
  });

  it('offers retry for a failed job while keeping pause, cancel, and completed-preview controls hidden', async () => {
    const failedJob = { id: 'failed-1', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'failed', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 0, error: 'The model file could not be read.', createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [failedJob] }));
    await openDocument('manual.pdf');

    expect((await screen.findAllByText('The model file could not be read.')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Cancel translation' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Review translation' })).toBeNull();
    native.resumeJob.mockResolvedValue({ ...failedJob, status: 'running' });
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(native.resumeJob).toHaveBeenCalledWith('failed-1'));
  });

  it('opens the published privacy policy and displays real bundled license notices', async () => {
    await renderApp(snapshot());
    const openUrl = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    pressSettings();
    fireEvent.press(screen.getByRole('button', { name: 'Privacy' }));
    await waitFor(() => expect(openUrl).toHaveBeenCalledWith(PRIVACY_URL));
    native.getLicenseNotices.mockResolvedValue([{ id: 'apache-2', title: 'Apache 2.0' }]);
    native.getLicenseNotice.mockResolvedValue('Review their notices before redistribution.');
    fireEvent.press(screen.getByRole('button', { name: 'Licenses' }));
    expect(await screen.findByText('Apache 2.0')).toBeOnTheScreen();
    fireEvent.press(screen.getByText('Apache 2.0'));
    expect(await screen.findByText('Review their notices before redistribution.')).toBeOnTheScreen();
    openUrl.mockRestore();
  });

  it('shows bootstrap failure and recovers through the visible retry action', async () => {
    native.bootstrap.mockRejectedValueOnce(new Error('Native database is unavailable.'));
    await renderStartedApp();
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(await screen.findByText('Native database is unavailable.', {}, { timeout: 3000 })).toBeOnTheScreen();

    native.bootstrap.mockResolvedValue(snapshot());
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      await Promise.resolve(); await Promise.resolve();
    });
    expect(await screen.findByRole('button', { name: 'Settings' }, { timeout: 3000 })).toBeOnTheScreen();
  });

  it('uses the localized fallback for a bootstrap rejection that is not an Error', async () => {
    native.bootstrap.mockRejectedValueOnce('database unavailable');
    await renderStartedApp();
    expect(await screen.findByText('The operation did not finish. Your local files are still available.', {}, { timeout: 3000 })).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('button', { name: 'Settings' }, { timeout: 3000 })).toBeOnTheScreen();
  });

  it('uses the localized fallback when an Error-free bootstrap refresh fails and retries it', async () => {
    await renderApp(snapshot());
    pressSettings();
    native.setSettings.mockResolvedValue({ theme: 'dark', language: 'system' });
    native.bootstrap.mockRejectedValueOnce('refresh failed without Error');
    fireEvent.press(screen.getByRole('switch', { name: 'Appearance' }));
    expect(await screen.findByText('The operation did not finish. Your local files are still available.')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();

    native.bootstrap.mockRejectedValueOnce('retry failed without Error');
    const bootstrapCountBeforeRetry = native.bootstrap.mock.calls.length;
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(native.bootstrap).toHaveBeenCalledTimes(bootstrapCountBeforeRetry + 1));
    expect(await screen.findByText('The operation did not finish. Your local files are still available.')).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot({ preferences: { theme: 'dark', language: 'system' } }));
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull());
  });

  it('replays the introduction without changing onboarding completion state', async () => {
    await renderApp(snapshot());
    pressSettings();
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'View tutorial' }));
      await Promise.resolve(); await Promise.resolve();
    });
    layoutOnboarding();

    expect(native.setOnboarding).not.toHaveBeenCalled();
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    const backListener = (BackHandler.addEventListener as jest.Mock).mock.calls.at(-1)?.[1] as (() => boolean) | undefined;
    await act(async () => { backListener?.(); });
    expect(screen.getByRole('header', { name: 'Settings' })).toBeOnTheScreen();
  });

  it('finishes onboarding through the native-valid final page and enters the library', async () => {
    native.setOnboarding.mockResolvedValueOnce({ version: 1, page: 2, done: true });
    await renderApp(snapshot({ onboarding: { version: 1, page: 0, done: false } }));
    layoutOnboarding();

    expect(screen.queryByRole('button', { name: 'Page 4 of 4' })).toBeNull();
    fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    fireEvent(screen.getByRole('button', { name: 'Next' }), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });

    await waitFor(() => expect(native.setOnboarding).toHaveBeenLastCalledWith({ version: 1, page: 2, done: true }));
    expect(await screen.findByRole('button', { name: 'Settings' })).toBeOnTheScreen();
    expect(screen.queryByTestId('translation-scene', { includeHiddenElements: true })).toBeNull();
  });

  it('consumes hardware back before root navigation is ready without popping route history', async () => {
    const routerMock = require('./mocks/expo-router');
    const globalRouter = require('expo-router').router;
    const boundRouter = require('expo-router').useRouter();
    let hardwareBack: (() => boolean) | undefined;
    (BackHandler.addEventListener as jest.Mock).mockImplementation((_event, listener) => {
      hardwareBack = listener;
      return { remove: jest.fn() };
    });
    routerMock.__setLocalSearchParams({ screen: 'settings' });
    routerMock.__setRootNavigationReady(false);
    await renderApp(snapshot());

    await act(async () => { expect(hardwareBack?.()).toBe(true); });
    expect(globalRouter.setParams).not.toHaveBeenCalled();
    expect(screen.getByRole('header', { name: 'Settings' })).toBeOnTheScreen();

    await act(async () => { routerMock.__setRootNavigationReady(true); });
    await waitFor(() => expect(boundRouter.setParams).toHaveBeenCalledWith({ screen: 'home', jobId: undefined }));
    expect(await screen.findByRole('button', { name: /^All documents,/ })).toBeOnTheScreen();
  });

  it('checks the live navigation ref from a stale hardware-back callback even when a root key exists', async () => {
    const routerMock = require('./mocks/expo-router');
    const boundRouter = require('expo-router').useRouter();
    let hardwareBack: (() => boolean) | undefined;
    (BackHandler.addEventListener as jest.Mock).mockImplementation((_event, listener) => {
      hardwareBack = listener;
      return { remove: jest.fn() };
    });
    await renderApp(snapshot());
    pressSettings();
    await waitFor(() => expect(boundRouter.setParams).toHaveBeenLastCalledWith({ screen: 'settings', jobId: undefined }));
    const committedCalls = boundRouter.setParams.mock.calls.length;

    routerMock.__setNavigationContainerReady(false);
    await act(async () => { expect(hardwareBack?.()).toBe(true); });
    expect(routerMock.__getRootNavigationState()).toEqual({ key: 'root' });
    expect(routerMock.navigationContainerRef.isReady).toHaveBeenCalled();
    expect(boundRouter.setParams).toHaveBeenCalledTimes(committedCalls);
    expect(screen.getByRole('header', { name: 'Settings' })).toBeOnTheScreen();

    routerMock.__setNavigationContainerReady(true);
    await act(async () => { routerMock.__setRootNavigationReady(true); });
    await waitFor(() => expect(boundRouter.setParams).toHaveBeenLastCalledWith({ screen: 'home', jobId: undefined }));
  });

  it('queues go until navigation is ready and preserves the pushed route for Back', async () => {
    const routerMock = require('./mocks/expo-router');
    const boundRouter = require('expo-router').useRouter();
    routerMock.__setRootNavigationReady(false);
    await renderApp(snapshot());

    pressSettings();
    expect(boundRouter.setParams).not.toHaveBeenCalled();
    expect(await screen.findByRole('button', { name: 'Translation model' })).toBeOnTheScreen();

    await act(async () => { routerMock.__setRootNavigationReady(true); });
    await waitFor(() => expect(boundRouter.setParams).toHaveBeenCalledWith({ screen: 'settings', jobId: undefined }));
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    await waitFor(() => expect(boundRouter.setParams).toHaveBeenLastCalledWith({ screen: 'home', jobId: undefined }));
    expect(await screen.findByRole('button', { name: /^All documents,/ })).toBeOnTheScreen();
  });

  it('queues onboarding completion and clears route history only after root readiness', async () => {
    const routerMock = require('./mocks/expo-router');
    const boundRouter = require('expo-router').useRouter();
    routerMock.__setRootNavigationReady(false);
    native.setOnboarding.mockResolvedValueOnce({ version: 1, page: 2, done: true });
    await renderApp(snapshot({ onboarding: { version: 1, page: 0, done: false } }));
    layoutOnboarding();
    fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
    await waitFor(() => expect(native.setOnboarding).toHaveBeenLastCalledWith({ version: 1, page: 2, done: true }));
    expect(boundRouter.setParams).not.toHaveBeenCalled();

    await act(async () => { routerMock.__setRootNavigationReady(true); });
    await waitFor(() => expect(boundRouter.setParams).toHaveBeenCalledWith({ screen: 'home', jobId: undefined }));
    expect(await screen.findByRole('button', { name: /^All documents,/ })).toBeOnTheScreen();
  });

  it('renders a cold linked translation job while the root navigation state is still unavailable', async () => {
    const routerMock = require('./mocks/expo-router');
    routerMock.__setLocalSearchParams({ screen: 'job', jobId: 'job-linked' });
    routerMock.__setRootNavigationReady(false);
    const job = { id: 'job-linked', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'translating', completedPages: 2, totalPages: 12, currentPage: 2, createdAt: 1, updatedAt: 1 };
    await renderApp(snapshot({ jobs: [job] }));

    expect(await screen.findByTestId('translation-progress-root')).toBeOnTheScreen();
    expect(screen.getByText('2 / 12')).toBeOnTheScreen();
    expect(require('expo-router').useRouter().setParams).not.toHaveBeenCalled();
  });

  it('unmounts inactive library and settings trees while keeping the active job tree stable across progress polls', async () => {
    const routerModule = require('expo-router');
    routerModule.__setLocalSearchParams({ screen: 'job', jobId: 'job-resource' });
    const job = { id: 'job-resource', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 4, totalPages: 12, currentPage: 4, createdAt: 1, updatedAt: 2 };
    const polls: Array<() => void | Promise<void>> = [];
    const pollingDelays: number[] = [];
    const appStateListeners: Array<(state: string) => void> = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => { appStateListeners.push(listener as (state: string) => void); return { remove: jest.fn() }; });
    jest.spyOn(globalThis, 'setInterval').mockImplementation((callback, delay) => { polls.push(callback as () => void | Promise<void>); pollingDelays.push(delay ?? 0); return 456 as unknown as ReturnType<typeof setInterval>; });
    const updatedJob = { ...job, completedPages: 5, currentPage: 5, updatedAt: 3 };
    await renderApp(snapshot({ jobs: [job] }));
    native.getActivitySnapshot.mockResolvedValue({ jobs: [updatedJob], models: snapshot().models, notifications: undefined });
    const progressRoot = screen.getByTestId('translation-progress-root');
    expect(screen.UNSAFE_queryByType(LibraryScreen)).toBeNull();
    expect(screen.UNSAFE_queryByType(SettingsScreen)).toBeNull();
    expect(screen.getByText('4 / 12')).toBeOnTheScreen();

    await act(async () => { appStateListeners.forEach(listener => listener('active')); await Promise.resolve(); });
    expect(pollingDelays).toContain(1000);
    await act(async () => { for (const poll of polls) await poll(); });
    expect(native.getActivitySnapshot).toHaveBeenCalled();
    expect(screen.getByText('5 / 12')).toBeOnTheScreen();
    expect(screen.getByTestId('translation-progress-root')).toBe(progressRoot);
    expect(screen.UNSAFE_queryByType(LibraryScreen)).toBeNull();
    expect(screen.UNSAFE_queryByType(SettingsScreen)).toBeNull();
  });

  it('preserves the selected BookPocket folder across settings and Back remounts', async () => {
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    expect(screen.getByRole('header', { name: 'All documents' })).toBeOnTheScreen();
    const library = screen.UNSAFE_getByType(LibraryScreen);

    act(() => library.props.onSettings());
    expect(await screen.findByRole('header', { name: 'Settings' })).toBeOnTheScreen();
    expect(screen.UNSAFE_queryByType(LibraryScreen)).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));

    expect(await screen.findByRole('header', { name: 'All documents' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Back' })).toBeOnTheScreen();
    expect(screen.UNSAFE_getByType(LibraryScreen)).toBeDefined();
  });

  it('shows the native installed-storage total when Settings is mounted', async () => {
    const data = snapshot({ device: { ...snapshot().device, storage: { installedModelBytes: 2048 } } });
    await renderApp(data);
    pressSettings();
    expect(await screen.findByText('2 KB')).toBeOnTheScreen();
  });

  it('falls back to ready-model sizes when native installed-storage data is unavailable', async () => {
    const model = snapshot().models[0];
    const data = snapshot({
      models: [{ ...model, status: 'paused' }],
    });
    await renderApp(data);
    pressSettings();
    expect(await screen.findByText('0 B')).toBeOnTheScreen();
  });

  it('opens imported document options when the shared imperative bridge is unavailable', async () => {
    const globalRouter = require('expo-router').router;
    const bridge = jest.spyOn(globalRouter, 'setParams').mockImplementation(() => {
      throw new Error('The imperative router is unavailable before the first render has finished.');
    });
    try {
      native.pickDocument.mockResolvedValueOnce(snapshot().documents[0]);
      await renderApp(snapshot());
      fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
      expect(await screen.findByRole('button', { name: 'Preview original' })).toBeOnTheScreen();
      expect(bridge).not.toHaveBeenCalled();
      expect(screen.queryByText('The imperative router is unavailable before the first render has finished.')).toBeNull();
    } finally {
      bridge.mockRestore();
    }
  });

  it('keeps the library empty when import is canceled and reports picker failures', async () => {
    native.pickDocument.mockResolvedValueOnce(null);
    await renderApp(snapshot({ documents: [] }));
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await waitFor(() => expect(native.pickDocument).toHaveBeenCalledTimes(1));
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    expect(screen.queryByText('manual.pdf')).toBeNull();
    expect(screen.queryByText('Recent documents')).toBeNull();

    native.pickDocument.mockRejectedValueOnce(new Error('The document provider is unavailable.'));
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    expect(await screen.findByText('The document provider is unavailable.')).toBeOnTheScreen();
    native.pickDocument.mockRejectedValueOnce('picker failed without an Error object');
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    expect(await screen.findByText('The operation did not finish. Your local files are still available.')).toBeOnTheScreen();
    expect(native.requestPermissions).not.toHaveBeenCalled();
  });

  it('persists appearance and language choices from settings', async () => {
    let data = snapshot({ preferences: { theme: 'light', language: 'system' } });
    native.bootstrap.mockImplementation(async () => data);
    native.setSettings.mockImplementation(async (update: Record<string, unknown>) => {
      data = { ...data, preferences: { ...data.preferences, ...update } };
      return data.preferences;
    });
    await renderApp(data);
    native.bootstrap.mockImplementation(async () => data);
    pressSettings();

    expect(screen.queryByRole('button', { name: 'Appearance' })).toBeNull();
    fireEvent.press(screen.getByRole('switch', { name: 'Appearance' }));
    await waitFor(() => expect(data.preferences.theme).toBe('dark'));
    fireEvent.press(screen.getByRole('switch', { name: 'Appearance' }));
    await waitFor(() => expect(data.preferences.theme).toBe('light'));
    fireEvent.press(screen.getByRole('switch', { name: 'Use system appearance' }));
    await waitFor(() => expect(data.preferences.theme).toBe('system'));
    fireEvent.press(screen.getByText('App language'));
    fireEvent.press(screen.getByRole('radio', { name: 'Русский' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ language: 'ru' }));
  });

  it('exposes follow-system and light-dark switches without an appearance sheet', async () => {
    await renderApp(snapshot({ preferences: { theme: 'light', language: 'system' } }));
    pressSettings();
    expect(screen.getByRole('switch', { name: 'Use system appearance' })).toBeOnTheScreen();
    expect(screen.getByRole('switch', { name: 'Appearance' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Appearance' })).toBeNull();
    fireEvent.press(screen.getByRole('switch', { name: 'Appearance' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ theme: 'dark' }));
  });

  it.each([0, 4])('keeps the stored app rating %s out of Settings without writing preferences', async appRating => {
    await renderApp(snapshot({ preferences: { theme: 'system', language: 'system', appRating } }));
    pressSettings();

    expect(screen.queryByText('Rate Folivela')).toBeNull();
    expect(screen.queryByText(/Saved on this device/)).toBeNull();
    expect(screen.queryByRole('adjustable')).toBeNull();
    expect(native.setSettings).not.toHaveBeenCalled();
  });

  it('renders DOCX source preview and online segment progress through the application routes', async () => {
    const source = { id: 'source-word', name: 'Source.docx', format: 'docx', pageCount: 3, segmentCount: 14, importedAt: 1 };
    const online = { id: 'online-word', documentId: 'online-doc', documentName: 'Online.docx', documentFormat: 'docx', progressUnit: 'segment', modelId: 'mymemory', engine: { kind: 'online', id: 'mymemory' }, sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 4, totalPages: 14, currentPage: 3, createdAt: 1, updatedAt: 2 };
    const exporting = { id: 'export-word', documentId: 'export-doc', documentName: 'Export.docx', documentFormat: 'docx', progressUnit: 'segment', modelId: 'mymemory', engine: { kind: 'online', id: 'mymemory' }, sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'exporting', completedPages: 14, totalPages: 14, currentPage: null, createdAt: 1, updatedAt: 2 };
    const completed = { id: 'done-word', documentId: 'done-doc', documentName: 'Done.docx', documentFormat: 'docx', progressUnit: 'segment', modelId: 'mymemory', engine: { kind: 'online', id: 'mymemory' }, sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete', completedPages: 14, totalPages: 14, currentPage: null, createdAt: 1, updatedAt: 2 };
    const docs = [source, { id: 'online-doc', name: 'Online.docx', format: 'docx', pageCount: 1, segmentCount: 14, importedAt: 1 }, { id: 'export-doc', name: 'Export.docx', format: 'docx', pageCount: 1, segmentCount: 14, importedAt: 1 }, { id: 'done-doc', name: 'Done.docx', format: 'docx', pageCount: 1, segmentCount: 14, importedAt: 1 }];
    await renderApp(snapshot({ documents: docs, jobs: [online, exporting, completed] }));

    await openDocument('Source.docx');
    expect(await screen.findByText('Preview original')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Preview original' }));
    await waitFor(() => expect(native.renderDocxPreview).toHaveBeenCalledWith('source-word'));
    expect(await screen.findByLabelText('Original 1')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));

    await openDocument('Online.docx');
    expect((await screen.findAllByText('Text is sent to MyMemory with your consent')).length).toBeGreaterThan(0);
    expect(screen.getByText('segments')).toBeOnTheScreen();
    expect(screen.getByText('4 / 14')).toBeOnTheScreen();
    expect(screen.getByText('segments 4')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));

    await openDocument('Export.docx');
    expect(await screen.findByText('Save Word (.docx)')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    await openDocument('Done.docx');
    await waitFor(() => expect(native.renderDocxPreview).toHaveBeenCalledWith('done-doc', 'done-word', true));
  });

  it('shows and retries a localized home refresh failure after the UI language changes', async () => {
    await renderApp(snapshot());
    pressSettings();
    native.setSettings.mockResolvedValue({ theme: 'system', language: 'ru' });
    native.bootstrap.mockRejectedValueOnce(new Error('Не удалось обновить библиотеку.'));

    fireEvent.press(screen.getByText('App language'));
    fireEvent.press(screen.getByRole('radio', { name: 'Русский' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ language: 'ru' }));
    expect(await screen.findByText('Не удалось обновить библиотеку.')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeOnTheScreen();

    native.bootstrap.mockResolvedValue(snapshot({ preferences: { theme: 'system', language: 'ru' } }));
    const bootstrapCountBeforeRetry = native.bootstrap.mock.calls.length;
    fireEvent.press(screen.getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(native.bootstrap).toHaveBeenCalledTimes(bootstrapCountBeforeRetry + 1));
    expect(screen.queryByRole('button', { name: 'Повторить' })).toBeNull();
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
    pressModelSettings();
    expect(animatedContent()).toEqual(expect.objectContaining({ paddingTop: 24, paddingLeft: 8, paddingRight: 8, paddingBottom: 16 }));
  });

  it('replays the introduction without writing onboarding progress', async () => {
    await renderApp(snapshot());
    pressSettings();
    fireEvent.press(screen.getByRole('button', { name: 'View tutorial' }));
    layoutOnboarding();
    expect(screen.getByTestId('translation-scene', { includeHiddenElements: true })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Skip' }));
    const backToHome = screen.queryByRole('button', { name: 'Back' });
    if (backToHome) fireEvent.press(backToHome);
    expect(screen.getByRole('button', { name: 'Settings' })).toBeOnTheScreen();
    expect(native.setOnboarding).not.toHaveBeenCalled();
  });

  it('adds, edits, and removes a dictionary term from its separate settings screen', async () => {
    let glossary: Array<Record<string, unknown>> = [];
    let savedId = '';
    native.bootstrap.mockImplementation(async () => snapshot({ glossary }));
    native.saveGlossaryEntry.mockImplementation(async (entry: Record<string, unknown>) => {
      savedId = String(entry.id);
      const saved = { ...entry, createdAt: 3 };
      glossary = [...glossary.filter(item => item.id !== savedId), saved];
      return saved;
    });
    native.deleteGlossaryEntry.mockImplementation(async (id: string) => {
      glossary = glossary.filter(item => item.id !== id);
      return undefined;
    });
    await renderApp(snapshot());
    native.bootstrap.mockImplementation(async () => snapshot({ glossary }));
    pressSettings();
    fireEvent.press(screen.getByText('Dictionary'));
    fireEvent.press(screen.getByRole('button', { name: 'Add term' }));
    fireEvent.changeText(screen.getByLabelText('Source term'), ' radiator ');
    fireEvent.changeText(screen.getByLabelText('Target term'), ' радиатор ');
    fireEvent.press(screen.getByRole('button', { name: 'Save term' }));
    await waitFor(() => expect(native.saveGlossaryEntry).toHaveBeenCalledWith({ id: expect.any(String), source: 'radiator', target: 'радиатор', sourceLanguage: 'en', targetLanguage: 'ru' }));

    fireEvent.press(screen.getByRole('button', { name: 'Edit term: radiator' }));
    fireEvent.changeText(screen.getByLabelText('Target term'), 'батарея');
    fireEvent.press(screen.getByRole('button', { name: 'Save term' }));
    await waitFor(() => expect(native.saveGlossaryEntry).toHaveBeenLastCalledWith({ id: savedId, source: 'radiator', target: 'батарея', sourceLanguage: 'en', targetLanguage: 'ru' }));

    fireEvent.press(screen.getByRole('button', { name: 'Remove term: radiator' }));
    await waitFor(() => expect(native.deleteGlossaryEntry).toHaveBeenCalledWith(savedId));
  });

  it('starts a translated job with the selected model and exposes pause, resume, and cancel actions', async () => {
    const doc = { id: 'picked', name: 'Boiler guide.pdf', pageCount: 3, importedAt: 2 };
    const runningJob = {
      id: 'job-new', documentId: 'picked', documentName: doc.name, modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru',
      status: 'running', stage: 'translating', completedPages: 1, totalPages: 3, completedBlocks: 4, discoveredBlocks: 6, totalBlocks: null,
      currentPage: 0, createdAt: 4, updatedAt: 5,
    };
    native.pickDocument.mockResolvedValue(doc);
    native.startJob.mockResolvedValue(runningJob);
    native.pauseJob.mockResolvedValue({ ...runningJob, status: 'paused' });
    native.resumeJob.mockResolvedValue(runningJob);
    native.cancelJob.mockResolvedValue({ ...runningJob, status: 'canceled' });
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    expect(await screen.findByText('Boiler guide.pdf')).toBeOnTheScreen();
    native.bootstrap
      .mockResolvedValueOnce(snapshot({ jobs: [runningJob], documents: [doc] }))
      .mockResolvedValueOnce(snapshot({ jobs: [{ ...runningJob, status: 'paused' }], documents: [doc] }))
      .mockResolvedValueOnce(snapshot({ jobs: [runningJob], documents: [doc] }))
      .mockResolvedValueOnce(snapshot({ jobs: [{ ...runningJob, status: 'canceled' }], documents: [doc] }));
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await waitFor(() => expect(native.startJob).toHaveBeenCalledWith({ documentId: 'picked', sourceLanguage: 'en', targetLanguage: 'ru', modelId: 'model-q4', engine: { kind: 'local', id: 'model-q4' }, onlineConsent: false, useGlossary: true }));
    expect((await screen.findAllByText('Translating locally')).length).toBeGreaterThan(0);
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
    await openDocument('manual.pdf');
    await waitFor(() => expect(native.renderTranslatedPage).toHaveBeenCalledWith('job-done', 0, expect.any(Number)));
    expect(screen.queryByText('Radiator')).toBeNull();
    expect(screen.queryByText('Радиатор')).toBeNull();

    fireEvent.press(screen.getByRole('button', { name: 'Translation' }));
    await waitFor(() => expect(screen.getByLabelText('Page 1')).toHaveProp('source', { uri: 'file:///private/translated.png' }));

    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 1));
    expect(await screen.findByLabelText('Page 2')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Original' }));
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

  it('pages through overflow output and maps each original toggle back to its source checkpoint', async () => {
    const completeJob = {
      id: 'job-overflow', documentId: 'doc-1', documentName: 'overflow.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru',
      status: 'completed', stage: 'complete', completedPages: 2, totalPages: 2, completedBlocks: 2, discoveredBlocks: 2, totalBlocks: 2,
      currentPage: 0, createdAt: 4, updatedAt: 5,
    };
    const mapping = [
      { outputPageIndex: 0, sourcePageIndex: 0, sourceOutputPageIndex: 0 },
      { outputPageIndex: 1, sourcePageIndex: 0, sourceOutputPageIndex: 1 },
      { outputPageIndex: 2, sourcePageIndex: 1, sourceOutputPageIndex: 0 },
    ];
    native.getJob.mockImplementation(async (_jobId: string, outputIndex: number) => ({
      job: completeJob, pages: [{ index: 0, status: 'completed' }, { index: 1, status: 'completed' }],
      selectedPage: { index: mapping[outputIndex].sourcePageIndex, width: 612, height: 792, status: 'completed', blocks: [] },
      pdfLayout: { layoutVersion: 2, sourcePageCount: 2, outputPageCount: 3, pages: mapping },
    }));
    native.renderTranslatedPage.mockResolvedValue('file:///private/overflow.png');
    native.renderPage.mockResolvedValue('file:///private/original.png');
    await renderApp(snapshot({ jobs: [completeJob] }));
    await openDocument('overflow.pdf');
    await waitFor(() => expect(native.renderTranslatedPage).toHaveBeenLastCalledWith('job-overflow', 0, expect.any(Number)));
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(native.renderTranslatedPage).toHaveBeenLastCalledWith('job-overflow', 1, expect.any(Number)));
    expect(screen.getByRole('button', { name: 'Next' })).not.toBeDisabled();
    fireEvent.press(screen.getByRole('button', { name: 'Original' }));
    await waitFor(() => expect(native.renderPage).toHaveBeenLastCalledWith('doc-1', 0, expect.any(Number)));
    expect(await screen.findByLabelText('Page 1')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(native.renderPage).toHaveBeenLastCalledWith('doc-1', 1, expect.any(Number)));
    fireEvent.press(screen.getByRole('button', { name: 'Translation' }));
    await waitFor(() => expect(native.renderTranslatedPage).toHaveBeenLastCalledWith('job-overflow', 2, expect.any(Number)));
    expect(await screen.findByLabelText('Page 3')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('offers preview, save, and share actions when a newly started job completes immediately', async () => {
    const doc = { id: 'picked', name: 'Boiler guide.pdf', pageCount: 1, importedAt: 2 };
    const completeJob = { id: 'job-fast', documentId: doc.id, documentName: doc.name, modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 3, updatedAt: 4 };
    native.pickDocument.mockResolvedValue(doc);
    native.startJob.mockResolvedValue(completeJob);
    native.exportJob.mockResolvedValue({ id: 'export-fast', uri: 'content://export-fast', displayName: 'Boiler guide translated.pdf' });
    native.shareExport.mockResolvedValue(undefined);
    native.getJob.mockResolvedValue({ job: completeJob, pages: [{ index: 0, status: 'completed' }], selectedPage: { index: 0, width: 600, height: 800, status: 'completed', blocks: [] } });
    native.renderTranslatedPage.mockResolvedValue('file:///private/fast-preview.png');
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    expect(await screen.findByText('Boiler guide.pdf')).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot({ documents: [doc], jobs: [completeJob] }));
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await waitFor(() => expect(native.startJob).toHaveBeenCalled());
    expect((await screen.findAllByText('Translation complete')).length).toBeGreaterThan(0);
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
    pressModelSettings();
    expect(screen.getByText(/450 KB \/ 1 MB/)).toBeOnTheScreen();
    native.bootstrap.mockResolvedValue(snapshot({ models: [{ ...downloading, status: 'ready', downloadedBytes: downloading.bytes }] }));
    fireEvent.press(screen.getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(native.pauseModelDownload).toHaveBeenCalledWith('model-q4'));

    expect(await screen.findByRole('button', { name: 'Remove model' })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Remove model' }));
    fireEvent.press(screen.getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(native.deleteModel).toHaveBeenCalledWith('model-q4'));
    expect(native.requestPermissions).not.toHaveBeenCalled();
  });

  it('resumes an already paused model download without starting device permission requests', async () => {
    const paused = { ...snapshot().models[0], status: 'paused', downloadedBytes: 220_000 };
    native.startModelDownload.mockResolvedValue({ ...paused, status: 'downloading' });
    native.bootstrap.mockResolvedValue(snapshot({ models: [paused] }));
    await renderApp(snapshot({ models: [paused] }));
    pressModelSettings();

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
    await openDocument('Seconds.pdf');
    expect(await screen.findByText(/Estimated time remaining: ~45 sec/)).toBeOnTheScreen();
    expect(screen.getByText(/Blocks translated: 2 · Blocks found so far: 5/)).toBeOnTheScreen();

    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    await openDocument('Minutes.pdf');
    expect(await screen.findByText(/Estimated time remaining: ~1 min–3 min/)).toBeOnTheScreen();
    expect(screen.getByText(/Blocks translated: 8 \/ 10/)).toBeOnTheScreen();

    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    await openDocument('Hours.pdf');
    expect(await screen.findByText(/Estimated time remaining: ~1 h 1 min/)).toBeOnTheScreen();

  });

  it('does not render a pending preview page until native page extraction is complete', async () => {
    const completeJob = { id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 2, currentPage: 0, createdAt: 1, updatedAt: 2 };
    const pendingDetails = { job: completeJob, pages: [{ index: 0, status: 'pending' }], selectedPage: { index: 0, width: 600, height: 800, status: 'pending', blocks: [] } };
    native.getJob.mockResolvedValueOnce(pendingDetails);
    await renderApp(snapshot({ jobs: [completeJob] }));
    await openDocument('manual.pdf');
    expect(await screen.findByText('Preparing your library…')).toBeOnTheScreen();
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 0));
    expect(native.renderTranslatedPage).not.toHaveBeenCalled();

  });

  it('reports a non-Error rejection from native preview data with the localized fallback', async () => {
    const completeJob = { id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    native.getJob.mockRejectedValue('unstructured native failure');
    await renderApp(snapshot({ jobs: [completeJob] }));
    await openDocument('manual.pdf');
    expect(await screen.findByText('The operation did not finish. Your local files are still available.')).toBeOnTheScreen();
  });

  it('ignores a stale preview response after the user returns to the library', async () => {
    const completeJob = { id: 'job-done', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    let resolveDetails: ((value: unknown) => void) | undefined;
    native.getJob.mockImplementation(() => new Promise(resolve => { resolveDetails = resolve; }));
    await renderApp(snapshot({ jobs: [completeJob] }));
    await openDocument('manual.pdf');
    await waitFor(() => expect(native.getJob).toHaveBeenCalledWith('job-done', 0));
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));

    await act(async () => {
      resolveDetails?.({ job: completeJob, pages: [], selectedPage: null });
      await Promise.resolve();
    });
    expect(screen.getByText('All documents')).toBeOnTheScreen();
    const backToHome = screen.queryByRole('button', { name: 'Back' });
    if (backToHome) fireEvent.press(backToHome);
    expect(screen.getByRole('button', { name: 'Settings' })).toBeOnTheScreen();
    expect(screen.queryByText('The operation did not finish. Your local files are still available.')).toBeNull();
  });

  it('shows partial block warnings and reports a missing original thumbnail', async () => {
    const partialJob = { id: 'job-partial', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    native.getJob.mockResolvedValue({ job: partialJob, pages: [{ index: 0, status: 'partial' }], selectedPage: { index: 0, width: 600, height: 800, status: 'partial', error: 'Some scanned labels need review.', blocks: [{ id: 'b1', sourceText: 'Outlet', translatedText: null, left: 10, top: 10, right: 30, bottom: 20, fontSize: 11, source: 'ocr', objectIndex: null, status: 'partial', error: 'OCR and embedded text disagree.' }] } });
    native.renderTranslatedPage.mockResolvedValue('file:///private/partial.png');
    native.renderPage.mockRejectedValue(new Error('Original page preview is unavailable.'));
    await renderApp(snapshot({ jobs: [partialJob] }));
    await openDocument('manual.pdf');
    expect(await screen.findByText('Some scanned labels need review.')).toBeOnTheScreen();
    expect(screen.getByText('Needs review')).toBeOnTheScreen();
    expect(screen.queryByText('OCR and embedded text disagree.')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Original' }));
    expect(await screen.findByText('Original page preview is unavailable.')).toBeOnTheScreen();
  });

  it('retains the review warning when partial output has no individual issue blocks', async () => {
    const partialJob = { id: 'job-partial', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'partial', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    native.getJob.mockResolvedValue({ job: partialJob, pages: [{ index: 0, status: 'partial' }], selectedPage: { index: 0, width: 600, height: 800, status: 'partial', blocks: [] } });
    await renderApp(snapshot({ jobs: [partialJob] }));
    await openDocument('manual.pdf');
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
    const view = await renderStartedApp({ fastMinimum: false, expectReady: false });
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(timerSpy).toHaveBeenCalled();
    view.unmount();
    expect(clearSpy).toHaveBeenCalledWith(scheduledHandle);
    expect(removeAppState).toHaveBeenCalled();
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
    const appStateRemovals: jest.Mock[] = [];
    const backRemove = jest.fn();
    const appStateSpy = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      appStateListener = listener as (state: string) => void;
      const remove = jest.fn();
      appStateRemovals.push(remove);
      return { remove } as ReturnType<typeof AppState.addEventListener>;
    });
    const backHandler = require('react-native').BackHandler;
    const backSpy = jest.spyOn(backHandler, 'addEventListener').mockImplementation(() => ({ remove: backRemove }));
    const intervalSpy = jest.spyOn(globalThis, 'setInterval').mockReturnValue(987 as unknown as ReturnType<typeof setInterval>);
    const clearSpy = jest.spyOn(globalThis, 'clearInterval').mockImplementation(() => undefined);
    const active = { id: 'job-active', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 1 };
    native.bootstrap.mockResolvedValue(snapshot({ jobs: [active] }));
    const view = await renderStartedApp();
    expect(appStateListener).toBeDefined();
    await act(async () => { appStateListener?.('active'); await Promise.resolve(); });
    expect(intervalSpy).toHaveBeenCalled();
    native.getActivitySnapshot.mockRejectedValueOnce(new Error('Transient refresh failure.'));
    await act(async () => { (intervalSpy.mock.calls[0][0] as () => void)(); await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByRole('button', { name: 'Settings' })).toBeOnTheScreen();
    await act(async () => { appStateListener?.('background'); await Promise.resolve(); });
    expect(clearSpy).toHaveBeenCalledWith(987);
    await act(async () => { appStateListener?.('active'); await Promise.resolve(); });
    expect(intervalSpy).toHaveBeenCalledTimes(3);
    await openDocument('manual.pdf');
    expect(backSpy).toHaveBeenCalledWith('hardwareBackPress', expect.any(Function));

    view.unmount();
    expect(appStateRemovals.length).toBeGreaterThan(0);
    for (const remove of appStateRemovals) expect(remove).toHaveBeenCalledTimes(1);
    expect(backRemove).toHaveBeenCalled();
    expect(clearSpy).toHaveBeenCalledWith(987);
    appStateSpy.mockRestore();
    backSpy.mockRestore();
    intervalSpy.mockRestore();
    clearSpy.mockRestore();
  });
  it('persists a document language pair, swaps it atomically, and starts with that pair', async () => {
    const doc = { id: 'doc-languages', name: 'Guide.pdf', pageCount: 1, importedAt: 2 };
    let data = snapshot({ preferences: { theme: 'system', language: 'system', sourceLanguage: 'en', targetLanguage: 'ru' } });
    native.pickDocument.mockResolvedValue(doc);
    native.setSettings.mockImplementation(async (settings: Record<string, string>) => {
      data = { ...data, preferences: { ...data.preferences, ...settings } };
      return data.preferences;
    });
    await renderApp(data);
    native.bootstrap.mockImplementation(async () => data);
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await screen.findByText('Guide.pdf');
    fireEvent.press(screen.getByRole('button', { name: /^Source language/ }));
    fireEvent.press(screen.getByRole('menuitem', { name: /Français/ }));
    await screen.findByRole('button', { name: /^Source language/ });
    expect(native.setSettings).toHaveBeenLastCalledWith({ sourceLanguage: 'fr', targetLanguage: 'ru' });
    fireEvent.press(screen.getByRole('button', { name: /^Target language/ }));
    fireEvent.press(screen.getByRole('menuitem', { name: /Deutsch/ }));
    await screen.findByRole('button', { name: /^Target language/ });
    fireEvent.press(screen.getByRole('button', { name: 'Swap languages' }));
    await screen.findByRole('button', { name: /^Source language/ });
    expect(native.setSettings).toHaveBeenLastCalledWith({ sourceLanguage: 'de', targetLanguage: 'fr' });
    native.startJob.mockResolvedValue({ id: 'job-languages', documentId: doc.id, documentName: doc.name, modelId: 'model-q4', sourceLanguage: 'de', targetLanguage: 'fr', status: 'queued', stage: 'queued', completedPages: 0, totalPages: 1, currentPage: null });
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await waitFor(() => expect(native.startJob).toHaveBeenCalledWith({ documentId: doc.id, modelId: 'model-q4', sourceLanguage: 'de', targetLanguage: 'fr', engine: { kind: 'local', id: 'model-q4' }, onlineConsent: false, useGlossary: true }));
  });

  it('keeps the old pair on save failure and applies a confirmed save even if refresh fails', async () => {
    native.pickDocument.mockResolvedValue({ id: 'doc-pair', name: 'Pair.pdf', pageCount: 1, importedAt: 2 });
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await screen.findByText('Pair.pdf');
    native.setSettings.mockRejectedValueOnce(new Error('Cannot save languages.'));
    fireEvent.press(screen.getByRole('button', { name: /^Source language/ }));
    fireEvent.press(screen.getByRole('menuitem', { name: /Français/ }));
    await screen.findByText('Cannot save languages.');
    expect(screen.getByRole('button', { name: /^Source language/ })).toBeOnTheScreen();
    native.setSettings.mockResolvedValue({ theme: 'system', language: 'system', sourceLanguage: 'fr', targetLanguage: 'ru' });
    native.bootstrap.mockRejectedValue(new Error('Refresh unavailable.'));
    fireEvent.press(screen.getByRole('button', { name: /^Source language/ }));
    fireEvent.press(screen.getByRole('menuitem', { name: /Français/ }));
    await screen.findByRole('button', { name: /^Source language/ });
  });

  it('does not present translation-quality samples as measured accuracy', async () => {
    const model = { ...snapshot().models[0], evaluation: { acceptableCases: 31, totalCases: 44 } };
    await renderApp(snapshot({ models: [model], preferences: { theme: 'system', language: 'system', sourceLanguage: 'fr', targetLanguage: 'zh-Hans' } }));
    pressModelSettings();
    expect(screen.queryByText(/Meaning preserved in/)).toBeNull();
    expect(screen.getByRole('button', { name: /^Compact,/ })).toBeOnTheScreen();
    expect(screen.queryByText(createTranslator('en')('qualityPending'))).toBeNull();
    expect(screen.getByRole('button', { name: /^Compact,/ })).toBeOnTheScreen();
  });

  it.each(['allow','later','dismiss'] as const)('explains a first download then handles notification %s without blocking work', async choice => {
    const candidate = { ...snapshot().models[0], status: 'not_downloaded' };
    const data = snapshot({ models: [candidate], notifications: { status: 'not_determined', canRequest: true } });
    native.setSettings.mockRejectedValueOnce(new Error('Preference write unavailable'));
    native.requestNotificationPermission.mockRejectedValueOnce(new Error('OS dialog dismissed'));
    native.startModelDownload.mockResolvedValue(candidate);
    await renderApp(data);
    pressModelSettings();
    await act(async () => { fireEvent.press(screen.getByRole('button',{name:'Download model'})); await Promise.resolve(); });
    expect(await screen.findByText('Keep track of your download')).toBeOnTheScreen();
    if(choice==='dismiss') fireEvent(screen.UNSAFE_getAllByType(Modal).find(v=>v.props.visible)!, 'requestClose');
    else fireEvent.press(screen.getByRole('button',{name:choice==='allow'?'Allow notifications':'Not now'}));
    await waitFor(()=>expect(native.startModelDownload).toHaveBeenCalledWith('model-q4'));
    expect(native.requestNotificationPermission).toHaveBeenCalledTimes(choice==='allow'?1:0);
    // Retrying the operation does not ask again even if saving the explanation flag failed.
    fireEvent.press(screen.getByRole('button',{name:'Download model'}));
    await waitFor(()=>expect(native.startModelDownload).toHaveBeenCalledTimes(2));
  });
  it('requests notifications at the first translation and still starts after a decline', async()=>{
    const data=snapshot({ notifications:{status:'not_determined',canRequest:true} });
    native.pickDocument.mockResolvedValue(data.documents[0]);native.setSettings.mockResolvedValue(data.preferences);
    native.startJob.mockResolvedValue({id:'new'});
    await renderApp(data);fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));
    await screen.findByRole('button',{name:'Preview original'});
    fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));
    await screen.findByText('Keep track of your translation');
    fireEvent.press(screen.getByRole('button',{name:'Not now'}));
    await waitFor(()=>expect(native.startJob).toHaveBeenCalled());
    expect(native.requestNotificationPermission).not.toHaveBeenCalled();
  });
  it.each([true, false])('routes the large-document hint action through current notification access (canRequest=%s)', async canRequest => {
    const job = { id: `large-${canRequest}`, documentId: 'doc-1', documentName: 'Equinox.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 415, currentPage: 0, createdAt: 1, updatedAt: 2 };
    const data = snapshot({ jobs: [job], notifications: { status: canRequest ? 'not_determined' : 'denied', requestedBefore: !canRequest, canRequest } });
    const openSettings = jest.spyOn(Linking, 'openSettings').mockResolvedValue(undefined);
    await renderApp(data);
    const hint = screen.UNSAFE_getByType(LargeDocumentHint);
    expect(hint.props.jobs).toEqual(expect.arrayContaining([expect.objectContaining({ id: job.id, totalPages: 415 })]));
    await act(async () => { hint.props.onEnable(); await Promise.resolve(); });
    if (canRequest) {
      await waitFor(() => expect(native.requestNotificationPermission).toHaveBeenCalledTimes(1));
      expect(openSettings).not.toHaveBeenCalled();
    } else {
      await waitFor(() => expect(openSettings).toHaveBeenCalledTimes(1));
      expect(native.requestNotificationPermission).not.toHaveBeenCalled();
    }
  });
  it.each(['cancel','license'] as const)('does not download Gemma after choosing %s instead of accepting',async choice=>{
    const candidate={...snapshot().models[0],status:'not_downloaded',licenseUrl:'https://ai.google.dev/gemma/terms'};
    const link=jest.spyOn(Linking,'openURL').mockRejectedValueOnce(new Error('Unavailable'));
    await renderApp(snapshot({models:[candidate]}));pressModelSettings();
    fireEvent.press(screen.getByRole('button',{name:'Accept and download'}));
    await screen.findByText(createTranslator('en')('gemmaTermsNotice'));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button',{name:choice==='license'?'Licenses':'Cancel'}));
    expect(native.startModelDownload).not.toHaveBeenCalled();
    if(choice==='license') await waitFor(()=>expect(link).toHaveBeenCalledWith(candidate.licenseUrl));
  });
  it('confirms bulk model cleanup while preserving the document library and supports cancel',async()=>{
    const models=[snapshot().models[0],{...snapshot().models[0],id:'second'}];
    native.deleteModel.mockResolvedValue(undefined);
    await renderApp(snapshot({models}));pressSettings();
    fireEvent.press(screen.getByRole('button',{name:'Storage'}));
    fireEvent.press(screen.getByRole('button',{name:'Remove all models'}));
    fireEvent.press(screen.getByRole('button',{name:'Cancel'}));expect(native.deleteModel).not.toHaveBeenCalled();
    fireEvent.press(screen.getByRole('button',{name:'Remove all models'}));
    fireEvent.press(screen.getByRole('button',{name:'Remove'}));
    await waitFor(()=>expect(native.deleteModel.mock.calls).toEqual([['model-q4'],['second']]));
    fireEvent.press(screen.getByRole('button',{name:'Back'}));fireEvent.press(screen.getByRole('button',{name:'Back'}));
    await openDocument('manual.pdf');
  });
  it('uses OS Back to unwind a settings child and then returns to the library',async()=>{
    await renderApp(snapshot());pressSettings();
    fireEvent.press(screen.getByRole('button',{name:'App language'}));
    const subscription=BackHandler.addEventListener as jest.Mock;
    act(()=>expect(subscription.mock.calls.at(-1)[1]()).toBe(true));
    expect(screen.getByRole('switch',{name:'Use system appearance'})).toBeOnTheScreen();
    act(()=>subscription.mock.calls.at(-1)[1]());
    expect(screen.getByRole('button',{name:'Translate documents'})).toBeOnTheScreen();
  });
  it('keeps Resume disabled until the native worker has finished acknowledging pause', async () => {
    const stopping = { id: 'pausing-job', documentId: 'doc-1', documentName: 'Stopping.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'ocr', completedPages: 0, totalPages: 415, currentPage: 0, workerStopping: true, createdAt: 10, updatedAt: 11 };
    await renderApp(snapshot({ jobs: [stopping] }));
    await openDocument('Stopping.pdf');
    expect(screen.getByRole('button', { name: 'Resume' })).toBeDisabled();
    expect(screen.getByText('Pausing…')).toBeOnTheScreen();
    expect(native.resumeJob).not.toHaveBeenCalled();
  });
  it('opens the job selected by an OS notification deep link', async () => {
    const first = { id: 'first-job', documentId: 'doc-1', documentName: 'First.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 10, createdAt: 1, updatedAt: 2 };
    const linked = { ...first, id: 'notification-job', documentName: 'Notification.pdf', status: 'paused', workerStopping: true };
    require('expo-router').__setLocalSearchParams({ screen: 'job', jobId: linked.id });
    await renderApp(snapshot({ jobs: [first, linked] }));
    expect(await screen.findByText('Notification.pdf')).toBeOnTheScreen();
    expect(screen.queryByText('First.pdf')).toBeNull();
    expect(screen.getByRole('button', { name: 'Resume' })).toBeDisabled();
  });
  it('polls activity single-flight and discards a response invalidated by a user mutation', async () => {
    let tick!: () => void;
    const originalInterval = globalThis.setInterval;
    const interval = jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      if (delay === 4000) { tick = callback; return 654 as unknown as ReturnType<typeof setInterval>; }
      return originalInterval(callback, delay);
    }) as typeof setInterval);
    const active = { id: 'light-poll', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 2, totalPages: 12, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [active] }));
    act(() => (AppState.addEventListener as jest.Mock).mock.calls.filter(([event]) => event === 'change').forEach(([, callback]) => callback('active')));
    await waitFor(() => expect(interval).toHaveBeenCalled());
    let finishPoll!: (value: unknown) => void;
    native.getActivitySnapshot.mockReturnValueOnce(new Promise(resolve => { finishPoll = resolve; }));
    await act(async () => { tick(); await Promise.resolve(); });
    await act(async () => { tick(); await Promise.resolve(); });
    expect(native.getActivitySnapshot).toHaveBeenCalledTimes(1);
    let finishPicker!: (value: unknown) => void;
    native.pickDocument.mockReturnValueOnce(new Promise(resolve => { finishPicker = resolve; }));
    fireEvent.press(screen.getByRole('button', { name: 'Translate documents' }));
    await waitFor(() => expect(native.pickDocument).toHaveBeenCalledTimes(1));
    await act(async () => {
      finishPoll({ jobs: [{ ...active, completedPages: 11 }], models: snapshot().models, notifications: undefined });
      await Promise.resolve(); await Promise.resolve();
    });
    expect(screen.UNSAFE_getByType(LibraryScreen).props.snapshot.jobs[0].completedPages).toBe(2);
    await act(async () => finishPicker(null));
  });
  it('uses route-aware activity polling and pauses it on base Settings', async () => {
    const scheduled: Array<{ callback: () => void; delay?: number; timer: number }> = [];
    const cleared: unknown[] = [];
    jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      const timer = scheduled.length + 1;
      scheduled.push({ callback, delay, timer });
      return timer as unknown as ReturnType<typeof setInterval>;
    }) as typeof setInterval);
    jest.spyOn(globalThis, 'clearInterval').mockImplementation(timer => { cleared.push(timer); });
    const active = { id: 'route-poll', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, createdAt: 1, updatedAt: 2 };
    const candidate = { ...snapshot().models[0], status: 'downloading', downloadedBytes: 20 };
    const routerModule = require('./mocks/expo-router');
    await renderApp(snapshot({ jobs: [active], models: [candidate] }));
    act(() => (AppState.addEventListener as jest.Mock).mock.calls.filter(([event]) => event === 'change').forEach(([, callback]) => callback('active')));
    await waitFor(() => expect(scheduled.some(timer => timer.delay === 4000)).toBe(true));
    const pollIntervals = () => scheduled.filter(timer => timer.delay === 1000 || timer.delay === 4000);
    expect(pollIntervals().at(-1)?.delay).toBe(4000);

    const homeTimer = pollIntervals().at(-1)?.timer;
    act(() => routerModule.__setLocalSearchParams({ screen: 'settings' }));
    expect(cleared).toContain(homeTimer);
    expect(pollIntervals()).toHaveLength(1);
    act(() => routerModule.__setLocalSearchParams({ screen: 'model' }));
    expect(pollIntervals().at(-1)?.delay).toBe(1000);
    const modelTimer = pollIntervals().at(-1)?.timer;

    act(() => routerModule.__setLocalSearchParams({ screen: 'settings' }));
    expect(cleared).toContain(modelTimer);
    expect(pollIntervals()).toHaveLength(2);
  });

  it('refreshes after leaving Settings and avoids replacing an unchanged activity snapshot', async () => {
    let tick!: () => void;
    const originalInterval = globalThis.setInterval;
    jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      if (delay === 4000) { tick = callback; return 654 as unknown as ReturnType<typeof setInterval>; }
      return originalInterval(callback, delay);
    }) as typeof setInterval);
    const active = { id: 'same-activity', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, createdAt: 1, updatedAt: 2 };
    const data = snapshot({ jobs: [active] });
    const appStateDescriptor = Object.getOwnPropertyDescriptor(AppState, 'currentState')!;
    Object.defineProperty(AppState, 'currentState', { value: 'active', configurable: true });
    try {
      await renderApp(data);
      await waitFor(() => expect(tick).toBeDefined());
      const unchanged = screen.UNSAFE_getByType(LibraryScreen).props.snapshot;
      native.getActivitySnapshot.mockResolvedValue({ jobs: data.jobs, models: data.models, notifications: undefined });
      await act(async () => tick());
      expect(screen.UNSAFE_getByType(LibraryScreen).props.snapshot).toBe(unchanged);

      const notificationState = { status: 'denied', requestedBefore: true, canRequest: false };
      native.getActivitySnapshot.mockResolvedValue({ jobs: data.jobs, models: data.models, notifications: notificationState });
      await act(async () => tick());
      expect(screen.UNSAFE_getByType(LibraryScreen).props.snapshot).not.toBe(unchanged);
      expect(screen.UNSAFE_getByType(LibraryScreen).props.snapshot.notifications).toEqual(notificationState);

      pressSettings();
      expect(await screen.findByRole('header', { name: 'Settings' })).toBeOnTheScreen();
      const bootstrapCalls = native.bootstrap.mock.calls.length;
      let finishRefresh!: (value: unknown) => void;
      native.bootstrap.mockReturnValueOnce(new Promise(resolve => { finishRefresh = resolve; }));
      await act(async () => {
        fireEvent.press(screen.getByRole('button', { name: 'Back' }));
        await waitFor(() => expect(native.bootstrap).toHaveBeenCalledTimes(bootstrapCalls + 1));
        finishRefresh(data);
        await Promise.resolve();
        await Promise.resolve();
      });
    } finally {
      Object.defineProperty(AppState, 'currentState', appStateDescriptor);
    }
  });

  it('merges activity contract changes by model ID and discovers newly added jobs', async () => {
    let tick!: () => void;
    const originalInterval = globalThis.setInterval;
    jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      if (delay === 4000) { tick = callback; return 655 as unknown as ReturnType<typeof setInterval>; }
      return originalInterval(callback, delay);
    }) as typeof setInterval);
    const active = { id: 'contract-job', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, createdAt: 1, updatedAt: 2 };
    const knownModels = [
      { ...snapshot().models[0], id: 'model-q4', downloadedBytes: 100 },
      { ...snapshot().models[0], id: 'model-retained', label: 'Known catalog model', downloadedBytes: 200 },
    ];
    const data = snapshot({ jobs: [active], models: knownModels });
    const appStateDescriptor = Object.getOwnPropertyDescriptor(AppState, 'currentState')!;
    Object.defineProperty(AppState, 'currentState', { value: 'active', configurable: true });
    try {
      await renderApp(data);
      act(() => (AppState.addEventListener as jest.Mock).mock.calls.filter(([event]) => event === 'change').forEach(([, callback]) => callback('active')));
      await waitFor(() => expect(tick).toBeDefined());

      // The native snapshot has the same model count but a new/unknown row at
      // the first position. Merge by ID and retain the known row omitted by a
      // partial response instead of replacing the catalog with that response.
      native.getActivitySnapshot.mockResolvedValueOnce({
        jobs: data.jobs,
        models: [
          { ...knownModels[0], id: 'new-native-row' },
          { ...knownModels[0], downloadedBytes: 350 },
        ],
        notifications: undefined,
      });
      await act(async () => { tick(); await Promise.resolve(); await Promise.resolve(); });
      const merged = screen.UNSAFE_getByType(LibraryScreen).props.snapshot as BootstrapSnapshot;
      expect(merged.models.map(model => model.id)).toEqual(['model-q4', 'model-retained']);
      expect(merged.models[0].downloadedBytes).toBe(350);
      expect(merged.models[1]).toBe(knownModels[1]);

      const discovered = { ...active, id: 'new-native-job', documentName: 'new-job.pdf', status: 'paused', completedPages: 0, updatedAt: 3 };
      native.getActivitySnapshot.mockResolvedValueOnce({ jobs: [...data.jobs, discovered], models: merged.models, notifications: undefined });
      await act(async () => { tick(); await Promise.resolve(); await Promise.resolve(); });
      await waitFor(() => {
        const latest = screen.UNSAFE_getByType(LibraryScreen).props.snapshot as BootstrapSnapshot;
        expect(latest.jobs.map(job => job.id)).toEqual(['contract-job', 'new-native-job']);
      });
    } finally {
      Object.defineProperty(AppState, 'currentState', appStateDescriptor);
    }
  });

  it('blocks a duplicate in-flight import and a disabled preparation callback without a document',async()=>{
    let finish!:(v:unknown)=>void;native.pickDocument.mockReturnValue(new Promise(r=>{finish=r;}));
    await renderApp(snapshot({models:[]}));
    const translate=screen.UNSAFE_getByType(LibraryScreen).props.onTranslate;
    act(()=>{translate();translate();});expect(native.pickDocument).toHaveBeenCalledTimes(1);
    await act(async()=>finish(null));
    expect(native.startJob).not.toHaveBeenCalled();
    // The preparation component's imperative callback must remain safe after state invalidation.
    const doc=snapshot().documents[0];native.pickDocument.mockResolvedValue(doc);
    fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));
    await screen.findByText('manual.pdf');
    act(()=>screen.UNSAFE_getByType(TranslationOptionsScreen).props.onStart());
    expect(native.startJob).not.toHaveBeenCalled();
    await act(async()=>{});
  });
  it('opens the original preview before translating, then returns without starting work',async()=>{
    native.renderPage.mockResolvedValue('file:///preview.png');
    native.pickDocument.mockResolvedValue(snapshot().documents[0]);
    await renderApp(snapshot());fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));
    await screen.findByRole('button',{name:'Preview original'});
    fireEvent.press(screen.getByRole('button',{name:'Preview original'}));
    expect(await screen.findByLabelText('Original PDF 1')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button',{name:'Back'}));
    expect(screen.getByText('Translation Options')).toBeOnTheScreen();expect(native.startJob).not.toHaveBeenCalled();
    await act(async()=>{});
  });
  it('surfaces a failed public policy link with the localized app-owned error',async()=>{
    jest.spyOn(Linking,'openURL').mockRejectedValueOnce(new Error('No browser'));
    await renderApp(snapshot());pressSettings();
    fireEvent.press(screen.getByRole('button',{name:'Privacy'}));
    expect(await screen.findByText('Could not open the privacy policy. Check your connection and try again.')).toBeOnTheScreen();
  });
  it('keeps inline theme choices available and completes a replay with Next',async()=>{
    await renderApp(snapshot());pressSettings();
    fireEvent.press(screen.getByRole('switch',{name:'Appearance'}));
    await waitFor(()=>expect(native.setSettings).toHaveBeenCalledWith({theme:'dark'}));
    expect(screen.queryByRole('button',{name:'Appearance'})).toBeNull();
    fireEvent.press(screen.getByRole('button',{name:'View tutorial'}));
    layoutOnboarding();
    for(let i=0;i<2;i++) await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Next'})));
    await act(async()=>fireEvent(screen.getByRole('button',{name:'Next'}), 'accessibilityAction', { nativeEvent: { actionName: 'activate' } }));
    await waitFor(()=>expect(screen.getByRole('switch',{name:'Use system appearance'})).toBeOnTheScreen());
    expect(native.setOnboarding).not.toHaveBeenCalled();
  });
  it('keeps a failed startup retry visible and ignores a later rejected bootstrap after unmount',async()=>{
    native.bootstrap.mockRejectedValue(new Error('Unavailable'));
    const view=await renderStartedApp();expect(await screen.findByText('Unavailable')).toBeOnTheScreen();
    fireEvent.press(screen.getAllByRole('button',{name:'Try again'}).at(-1)!);await act(async()=>{});
    expect(screen.getByText('Unavailable')).toBeOnTheScreen();view.unmount();
    let fail!:(e:Error)=>void;native.bootstrap.mockReturnValue(new Promise((_,r)=>{fail=r;}));
    const other=render(<App/>);other.unmount();await act(async()=>fail(new Error('late')));
  });

  it('routes optional preparation tools and persists an explicit dictionary opt-out',async()=>{
    const term={id:'g',source:'pump',target:'насос',sourceLanguage:'en',targetLanguage:'ru'};
    native.pickDocument.mockResolvedValue(snapshot().documents[0]);
    await renderApp(snapshot({glossary:[term]}));fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));
    await screen.findByRole('button',{name:'Preview original'});
    fireEvent(screen.getByRole('switch',{name:'Use dictionary'}),'valueChange',false);
    fireEvent.press(screen.getByRole('button',{name:'Dictionary'}));expect(screen.getByRole('button',{name:'Edit term: pump'})).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button',{name:'Back'}));
    fireEvent.press(screen.getByRole('button',{name:'Translation model'}));expect(screen.getByText('Model settings')).toBeOnTheScreen();
  });
  it('keeps unknown job totals at zero, labels a queued job, and handles canceled export',async()=>{
    const job={id:'q',documentId:'doc-1',documentName:'Queued.pdf',modelId:'model-q4',sourceLanguage:'en',targetLanguage:'ru',status:'queued',stage:'queued',completedPages:0,totalPages:0,currentPage:null,discoveredBlocks:0,etaRangeSeconds:{min:NaN,max:Infinity}};
    await renderApp(snapshot({jobs:[job]}));await openDocument('Queued.pdf');
    expect(screen.getByText('Pages complete')).toBeOnTheScreen();
    expect(screen.getByText('0 / 0')).toBeOnTheScreen();
    expect(screen.getByText('Blocks translated: 0 · Blocks found so far: 0')).toBeOnTheScreen();
    expect(screen.getAllByText('Waiting to start').length).toBeGreaterThan(0);
  });
  it('renders tablet preview with failed/skipped blocks, a missing translation, and a canceled export',async()=>{
    const dimensions=jest.spyOn(Dimensions,'get').mockReturnValue({width:1000,height:800,scale:1,fontScale:1});
    const job={id:'partial',documentId:'doc-1',documentName:'Mixed.pdf',modelId:'model-q4',sourceLanguage:'en',targetLanguage:'ru',status:'partial',stage:'complete',completedPages:1,totalPages:1,currentPage:0};
    const blocks=[{id:'f',sourceText:'Warning',translatedText:null,status:'failed'},{id:'s',sourceText:'123',translatedText:null,status:'skipped'},{id:'p',sourceText:'Check',translatedText:'Проверьте',status:'partial'}];
    native.getJob.mockResolvedValue({job,pages:[{index:0,status:'partial'}],selectedPage:{index:0,status:'partial',blocks}});
    native.renderPage.mockResolvedValue('file:///original.png');native.renderTranslatedPage.mockResolvedValue('file:///translated.png');native.exportJob.mockResolvedValue(null);
    await renderApp(snapshot({jobs:[job]}));await openDocument('Mixed.pdf');
    expect(await screen.findByLabelText('Page 1')).toHaveStyle({height:620});
    expect(screen.getByText('Needs review')).toBeOnTheScreen();
    expect(screen.queryByText('Warning')).toBeNull();expect(screen.queryByText('Проверьте')).toBeNull();
    fireEvent.press(screen.getByRole('button',{name:'Original'}));await waitFor(()=>expect(native.renderPage).toHaveBeenCalled());
    fireEvent.press(screen.getByRole('button',{name:'Translation'}));await waitFor(()=>expect(native.renderTranslatedPage).toHaveBeenCalledTimes(2));
    fireEvent.press(screen.getByRole('button',{name:'Save a copy'}));await waitFor(()=>expect(native.exportJob).toHaveBeenCalled());
    expect(screen.queryByRole('button',{name:'Share'})).toBeNull();dimensions.mockRestore();
  });
  it('animates child navigation when motion is enabled and tolerates an unavailable preference API',async()=>{
    mockReducedMotion=false;await renderApp(snapshot());pressSettings();
    fireEvent.press(screen.getByRole('button',{name:'App language'}));expect(screen.getByRole('radio',{name:'English'})).toBeOnTheScreen();
  });
  it('keeps a transient lightweight polling error from resetting the current library',async()=>{
    let tick!:()=>void;const originalInterval=globalThis.setInterval;
    jest.spyOn(globalThis,'setInterval').mockImplementation(((fn:()=>void,ms?:number)=>{if(ms===4000){tick=fn;return 42 as unknown as ReturnType<typeof setInterval>;}return originalInterval(fn,ms);}) as typeof setInterval);
    await renderApp(snapshot({models:[{...snapshot().models[0],status:'verifying'}]}));
    act(()=>(AppState.addEventListener as jest.Mock).mock.calls.filter(([event])=>event==='change').forEach(([,fn])=>fn('active')));
    native.getActivitySnapshot.mockRejectedValueOnce('unexpected native rejection');await act(async()=>tick());
    expect(screen.queryByText(createTranslator('en')('unknownError'))).toBeNull();
    expect(screen.UNSAFE_getByType(LibraryScreen).props.snapshot.models[0].status).toBe('verifying');
  });

  it('handles missing platform clocks/locales and an initially unknown app state',async()=>{
    const clock=Object.getOwnPropertyDescriptor(globalThis,'performance');
    const state=Object.getOwnPropertyDescriptor(AppState,'currentState')!;
    try {
      Object.defineProperty(globalThis,'performance',{value:undefined,configurable:true});
      Object.defineProperty(AppState,'currentState',{value:null,configurable:true});
      jest.spyOn(Intl,'DateTimeFormat').mockReturnValue({resolvedOptions:()=>({locale:undefined})} as unknown as Intl.DateTimeFormat);
      mockReducedMotion=undefined;
      await renderApp(snapshot());expect(screen.getByText('Folivela')).toBeOnTheScreen();
    } finally {
      if(clock) Object.defineProperty(globalThis,'performance',clock);
      Object.defineProperty(AppState,'currentState',state);
    }
  });
  it.each(['resolve','reject'] as const)('ignores a stale refresh %s after a newer user operation',async outcome=>{
    let tick!:()=>void;const originalInterval=globalThis.setInterval;
    jest.spyOn(globalThis,'setInterval').mockImplementation(((fn:()=>void,ms?:number)=>{if(ms===4000){tick=fn;return 42 as unknown as ReturnType<typeof setInterval>;}return originalInterval(fn,ms);}) as typeof setInterval);
    await renderApp(snapshot({models:[{...snapshot().models[0],status:'downloading'}]}));
    let resolve!:(v:unknown)=>void, reject!:(e:Error)=>void;
    native.bootstrap.mockImplementationOnce(()=>new Promise((a,b)=>{resolve=a;reject=b;}));
    await act(async () => { (AppState.addEventListener as jest.Mock).mock.calls.filter(([event])=>event==='change').forEach(([,fn])=>fn('active')); await Promise.resolve(); await Promise.resolve(); });
    await act(async()=>tick());
    native.pickDocument.mockResolvedValueOnce(null);fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));
    await waitFor(()=>expect(native.pickDocument).toHaveBeenCalled());
    await act(async()=>{if(outcome==='resolve')resolve(snapshot({documents:[]}));else reject(new Error('Stale failure')); await Promise.resolve(); await Promise.resolve();});
    expect(screen.UNSAFE_getByType(LibraryScreen).props.snapshot.documents[0].name).toBe('manual.pdf');
    expect(screen.queryByText('Stale failure')).toBeNull();
  });
  it('allows a user to cancel single-model removal without deleting files',async()=>{
    await renderApp(snapshot());pressModelSettings();
    fireEvent.press(screen.getByRole('button',{name:'Remove model'}));
    fireEvent.press(screen.getByRole('button',{name:'Cancel'}));expect(native.deleteModel).not.toHaveBeenCalled();
  });
  it('routes dictionary language changes, and handles model-only completed job fields on Android',async()=>{
    native.setSettings.mockResolvedValue({theme:'system',language:'system',sourceLanguage:'fr',targetLanguage:'ru'});
    await renderApp(snapshot());pressSettings();fireEvent.press(screen.getByRole('button',{name:'Dictionary'}));
    fireEvent.press(screen.getByRole('button',{name:/^Source language/}));fireEvent.press(screen.getByRole('menuitem',{name:/Français/}));
    await waitFor(()=>expect(native.setSettings).toHaveBeenCalledWith({sourceLanguage:'fr',targetLanguage:'ru'}));
  });
  it('shows known completed block counts without a discovered total and cancels saving a new result',async()=>{
    jest.replaceProperty(Platform,'OS','android');
    const doc=snapshot().documents[0];const job={id:'done-new',documentId:doc.id,documentName:doc.name,modelId:'model-q4',sourceLanguage:'en',targetLanguage:'ru',status:'completed',stage:'complete',completedPages:1,totalPages:1,completedBlocks:2,currentPage:0};
    native.pickDocument.mockResolvedValue(doc);native.startJob.mockResolvedValue(job);native.exportJob.mockResolvedValue(null);
    native.bootstrap.mockResolvedValue(snapshot());await renderStartedApp();
    native.bootstrap.mockResolvedValue(snapshot({jobs:[job]}));
    fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));await screen.findByRole('button',{name:'Preview original'});
    fireEvent.press(screen.getByRole('button',{name:'Translate documents'}));await screen.findByText('Blocks translated: 2');
    fireEvent.press(screen.getByRole('button',{name:'Save a copy'}));await waitFor(()=>expect(native.exportJob).toHaveBeenCalledWith('done-new'));
    expect(screen.queryByRole('button',{name:'Share'})).toBeNull();
  });

  it('renders dark theme consistently in library, onboarding and startup error states',async()=>{
    jest.spyOn(require('react-native'),'useColorScheme').mockReturnValue('dark');
    const view=await renderApp(snapshot({preferences:{theme:'dark',language:'system'}}));
    expect(screen.getByText('Folivela')).toBeOnTheScreen();
    pressSettings();fireEvent.press(screen.getByRole('button',{name:'Back'}));
    expect(screen.getByRole('button',{name:'Translate documents'})).toBeOnTheScreen();view.unmount();
    const intro=await renderApp(snapshot({preferences:{theme:'dark',language:'system'},onboarding:{version:1,page:0,done:false}}));
    expect(screen.getByRole('button',{name:'Skip'})).toBeOnTheScreen();intro.unmount();
    native.bootstrap.mockRejectedValueOnce(new Error(''));
    await renderStartedApp();expect(await screen.findByText(createTranslator('en')('unknownError'))).toBeOnTheScreen();
  });
  it('persists a translated document color and library sort through native preferences', async () => {
    const job = { id: 'color-job', documentId: 'doc-1', documentName: 'Color.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'completed', stage: 'complete', completedPages: 1, totalPages: 1, currentPage: 0, createdAt: 1, updatedAt: 2 };
    let data = snapshot({ jobs: [job] });
    native.bootstrap.mockImplementation(async () => data);
    native.setJobColor.mockImplementation(async (_id, color) => {
      const changed = { ...job, labelColor: color };
      data = { ...data, jobs: [changed] };
      return changed;
    });
    native.setSettings.mockImplementation(async settings => {
      data = { ...data, preferences: { ...data.preferences, ...settings } };
      return data.preferences;
    });
    await renderStartedApp();
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    fireEvent.press(screen.getByRole('button', { name: 'Color label: Color.pdf, No color' }));
    fireEvent.press(screen.getByRole('radio', { name: 'Red' }));
    await waitFor(() => expect(native.setJobColor).toHaveBeenCalledWith('color-job', 'red'));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Color label: Color.pdf, Red' })).toBeOnTheScreen());
    fireEvent.press(screen.getByRole('button', { name: 'Sort documents' }));
    await waitFor(() => expect(native.setSettings).toHaveBeenCalledWith({ librarySort: 'color' }));
  });

  it('ignores an error from preview data after navigating away',async()=>{
    const job={id:'late-error',documentId:'doc-1',documentName:'Late.pdf',modelId:'model-q4',sourceLanguage:'en',targetLanguage:'ru',status:'completed',stage:'complete',completedPages:1,totalPages:1,currentPage:0};
    let fail!:(error:Error)=>void;native.getJob.mockReturnValue(new Promise((_,reject)=>{fail=reject;}));
    await renderApp(snapshot({jobs:[job]}));
    act(()=>(AppState.addEventListener as jest.Mock).mock.calls.filter(([event])=>event==='change').forEach(([,fn])=>fn('active')));
    await openDocument('Late.pdf');fireEvent.press(screen.getByRole('button',{name:'Back'}));
    await act(async()=>fail(new Error('Late failure')));expect(screen.queryByText('Late failure')).toBeNull();
  });

  it('waits for an active translation pause before deleting the job and saved progress', async () => {
    const job = { id: 'delete-active', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [job] }));
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    native.pauseJob.mockResolvedValueOnce({ ...job, workerStopping: true });
    native.getActivitySnapshot
      .mockResolvedValueOnce({ jobs: [{ ...job, workerStopping: true }], models: snapshot().models })
      .mockResolvedValueOnce({ jobs: [{ ...job, status: 'paused', workerStopping: false }], models: snapshot().models });
    native.deleteJob.mockResolvedValueOnce(undefined);
    fireEvent.press(screen.getByRole('button', { name: 'Delete translation: manual.pdf' }));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(native.deleteJob).toHaveBeenCalledWith(job.id));
    expect(native.pauseJob).toHaveBeenCalledWith(job.id);
    expect(native.getActivitySnapshot).toHaveBeenCalledTimes(2);
  });

  it('keeps an active translation when pause acknowledgment times out', async () => {
    const job = { id: 'delete-timeout', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'ocr', completedPages: 0, totalPages: 415, currentPage: 0, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [job] }));
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    native.pauseJob.mockResolvedValueOnce({ ...job, workerStopping: true });
    native.getActivitySnapshot.mockResolvedValue({ jobs: [{ ...job, workerStopping: true }], models: snapshot().models });
    jest.spyOn(Date, 'now').mockReturnValueOnce(1_000).mockReturnValueOnce(61_001);
    fireEvent.press(screen.getByRole('button', { name: 'Delete translation: manual.pdf' }));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByText('Waiting for the translation to finish pausing.')).toBeOnTheScreen();
    expect(native.pauseJob).toHaveBeenCalledWith(job.id);
    expect(native.deleteJob).not.toHaveBeenCalled();
  });

  it('reports an active job deletion failure after its pause has completed', async () => {
    const job = { id: 'delete-failed', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'ocr', completedPages: 0, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [job] }));
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    native.pauseJob.mockResolvedValueOnce({ ...job, status: 'paused', workerStopping: false });
    native.getActivitySnapshot.mockResolvedValueOnce({ jobs: [{ ...job, status: 'paused', workerStopping: false }], models: snapshot().models });
    native.deleteJob.mockRejectedValueOnce(new Error('Saved progress could not be removed.'));
    fireEvent.press(screen.getByRole('button', { name: 'Delete translation: manual.pdf' }));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByText('Saved progress could not be removed.')).toBeOnTheScreen();
    expect(native.pauseJob).toHaveBeenCalledWith(job.id);
    expect(native.deleteJob).toHaveBeenCalledWith(job.id);
  });

  it('supports canceling, failing, and retrying deletion of an untranslated import', async () => {
    const document = { id: 'raw-import', name: 'Raw import.pdf', pageCount: 2, importedAt: 1 };
    const pausedJob = { id: 'paused-job', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'ocr', completedPages: 0, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ documents: [snapshot().documents[0], document], jobs: [pausedJob] }));
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    fireEvent.press(screen.getByRole('button', { name: 'Delete translation: manual.pdf' }));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button', { name: 'Cancel' }));
    expect(native.deleteJob).not.toHaveBeenCalled();
    expect(native.pauseJob).not.toHaveBeenCalled();

    native.deleteDocument.mockRejectedValueOnce(new Error('Document removal failed.'));
    fireEvent.press(screen.getByRole('button', { name: 'Delete document: Raw import.pdf' }));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('Document removal failed.')).toBeOnTheScreen();
    expect(native.deleteDocument).toHaveBeenCalledWith(document.id);

    native.deleteDocument.mockResolvedValueOnce(undefined);
    fireEvent.press(screen.getByRole('button', { name: 'Delete document: Raw import.pdf' }));
    fireEvent.press(within(screen.getByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(native.deleteDocument).toHaveBeenCalledTimes(2));
  });

  it('shows the separate model preparation stage for a running job', async () => {
    const job = { id: 'loading-stage', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'loadingModel', completedPages: 0, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [job] }));
    await openDocument('manual.pdf');
    expect(screen.getByText('Preparing translation model')).toBeOnTheScreen();
  });

  it('opens model settings instead of starting a duplicate transfer for a pinned model already downloading', async () => {
    const model = { ...snapshot().models[0], status: 'downloading' };
    const job = { id: 'redownloading', documentId: 'doc-1', documentName: 'manual.pdf', modelId: model.id, sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ models: [model], jobs: [job] }));
    await openDocument('manual.pdf');
    fireEvent.press(screen.getByRole('button', { name: 'Model settings' }));
    expect(screen.getByText('Model settings')).toBeOnTheScreen();
    expect(native.startModelDownload).not.toHaveBeenCalled();
    expect(native.resumeJob).not.toHaveBeenCalled();
  });

  it('uses the route fallback when Back is pressed from a directly linked job', async () => {
    const job = { id: 'direct-back', documentId: 'doc-1', documentName: 'Direct.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    const routerModule = require('expo-router');
    routerModule.__setLocalSearchParams({ screen: 'job', jobId: job.id });
    await renderApp(snapshot({ jobs: [job] }));
    expect(await screen.findByText('Direct.pdf')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(await screen.findByRole('button', { name: 'Translate documents' })).toBeOnTheScreen();
    routerModule.__setLocalSearchParams({});
  });

  it('returns from a library folder with the hardware Back handler', async () => {
    await renderApp(snapshot());
    fireEvent.press(screen.getByRole('button', { name: /^All documents,/ }));
    const backHandler = BackHandler.addEventListener as jest.Mock;
    const callback = backHandler.mock.calls.at(-1)?.[1] as (() => boolean) | undefined;
    expect(callback).toBeDefined();
    act(() => expect(callback?.()).toBe(true));
    expect(screen.getByRole('button', { name: /^All documents,/ })).toBeOnTheScreen();
  });

  it('uses the home fallback for hardware Back from a directly linked settings route', async () => {
    const routerModule = require('expo-router');
    routerModule.__setLocalSearchParams({ screen: 'settings' });
    await renderApp(snapshot());
    const callback = (BackHandler.addEventListener as jest.Mock).mock.calls.at(-1)?.[1] as (() => boolean) | undefined;
    expect(callback).toBeDefined();
    act(() => expect(callback?.()).toBe(true));
    expect(await screen.findByRole('button', { name: 'Translate documents' })).toBeOnTheScreen();
    routerModule.__setLocalSearchParams({});
  });

  it('deletes a selected paused job without requesting another pause', async () => {
    const job = { id: 'selected-delete', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'ocr', completedPages: 0, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 2 };
    const routerModule = require('expo-router');
    routerModule.__setLocalSearchParams({ screen: 'job', jobId: job.id });
    await renderApp(snapshot({ jobs: [job] }));
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeOnTheScreen();
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    routerModule.__setLocalSearchParams({ screen: 'home' });
    expect(await screen.findByRole('button', { name: 'Translate documents' })).toBeOnTheScreen();
    native.deleteJob.mockResolvedValueOnce(undefined);
    act(() => screen.UNSAFE_getByType(LibraryScreen).props.onDeleteJob(job));
    fireEvent.press(within(await screen.findByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(native.deleteJob).toHaveBeenCalledWith(job.id));
    expect(native.pauseJob).not.toHaveBeenCalled();
    routerModule.__setLocalSearchParams({});
  });

  it('preserves a newer job selection when an older job finishes deleting', async () => {
    const deleting = { id: 'delete-old', documentId: 'doc-1', documentName: 'Older.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'ocr', completedPages: 0, totalPages: 12, currentPage: 0, createdAt: 1, updatedAt: 2 };
    const selected = { ...deleting, id: 'select-new', documentName: 'Newer.pdf', status: 'paused' };
    let acknowledge!: (state: unknown) => void;
    await renderApp(snapshot({ jobs: [deleting, selected] }));
    native.getJob.mockResolvedValue({ job: selected, pages: [], selectedPage: { index: 0, status: 'paused', blocks: [] } });
    native.pauseJob.mockResolvedValueOnce({ ...deleting, workerStopping: true });
    native.getActivitySnapshot.mockReturnValueOnce(new Promise(resolve => { acknowledge = resolve; }));
    native.deleteJob.mockResolvedValueOnce(undefined);
    act(() => screen.UNSAFE_getByType(LibraryScreen).props.onDeleteJob(deleting));
    fireEvent.press(within(await screen.findByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(native.pauseJob).toHaveBeenCalledWith(deleting.id));
    act(() => screen.UNSAFE_getByType(LibraryScreen).props.onJob(selected));
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeOnTheScreen();
    await act(async () => {
      acknowledge({ jobs: [{ ...deleting, status: 'paused', workerStopping: false }, selected], models: snapshot().models });
      await Promise.resolve();
    });
    await waitFor(() => expect(native.deleteJob).toHaveBeenCalledWith(deleting.id));
    expect(screen.getByText('Newer.pdf')).toBeOnTheScreen();
    expect(screen.queryByText('Older.pdf')).toBeNull();
  });

  it('shows the pausing label while a running job is waiting for worker cleanup', async () => {
    const job = { id: 'pausing-label', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'ocr', completedPages: 0, totalPages: 12, currentPage: 0, workerStopping: true, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [job] }));
    await openDocument('manual.pdf');
    expect(screen.getByRole('button', { name: 'Pausing…' })).toBeDisabled();
    expect(screen.getAllByText('Pausing…').length).toBeGreaterThan(0);
  });

  it('pauses and waits when a saved job is already marked workerStopping', async () => {
    const job = { id: 'stopping-delete', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'paused', stage: 'ocr', completedPages: 0, totalPages: 12, currentPage: 0, workerStopping: true, createdAt: 1, updatedAt: 2 };
    await renderApp(snapshot({ jobs: [job] }));
    native.pauseJob.mockResolvedValueOnce({ ...job, workerStopping: true });
    native.getActivitySnapshot.mockResolvedValueOnce({ jobs: [{ ...job, workerStopping: false }], models: snapshot().models });
    native.deleteJob.mockResolvedValueOnce(undefined);
    act(() => screen.UNSAFE_getByType(LibraryScreen).props.onDeleteJob(job));
    fireEvent.press(within(await screen.findByTestId('app-dialog')).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(native.deleteJob).toHaveBeenCalledWith(job.id));
    expect(native.pauseJob).toHaveBeenCalledWith(job.id);
  });

// PRIVATE STAGED: insert these tests inside existing app-flow.test.tsx describe.
// Reuses native/snapshot/renderApp/pressSettings plus existing act/screen/waitFor.
// No imports, production changes or direct hook/private-state manipulation.

  it('refreshes model disk eligibility while the unchanged memory warning remains advisory', async () => {
    let tick!: () => void | Promise<void>;
    const originalInterval = globalThis.setInterval;
    jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      if (delay === 1000) { tick = callback; return 768 as unknown as ReturnType<typeof setInterval>; }
      return originalInterval(callback, delay);
    }) as typeof setInterval);
    const descriptor = Object.getOwnPropertyDescriptor(AppState, 'currentState')!;
    Object.defineProperty(AppState, 'currentState', { value: 'active', configurable: true });
    const routerMock = require('./mocks/expo-router');
    const active = { id: 'eligibility-background-job', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    const blocked = { ...snapshot().models[0], tier: 'compact', status: 'paused', downloadedBytes: 0,
      memoryEstimateEligible: false, diskEstimateEligible: false,
      resourceEstimateEligible: false, resourceReason: 'memory' };
    try {
      routerMock.__setLocalSearchParams({ screen: 'model' });
      await renderApp(snapshot({ jobs: [active], models: [blocked] }));
      fireEvent.press(screen.getByRole('button', { name: /^Compact,/ }));
      expect(screen.getByRole('button', { name: 'Resume' })).toBeDisabled();
      await waitFor(() => expect(tick).toBeDefined());
      native.getActivitySnapshot.mockResolvedValue({ jobs: [active],
        models: [{ ...blocked, diskEstimateEligible: true }], notifications: undefined });

      await act(async () => { await tick(); });

      expect(screen.getByRole('button', { name: 'Resume' })).toBeEnabled();
      const modelScreen = require('../src/screens/AppScreens').ModelScreen;
      const shown = screen.UNSAFE_getByType(modelScreen).props.snapshot.models[0];
      expect(shown.diskEstimateEligible).toBe(true);
      expect(shown.memoryEstimateEligible).toBe(false);
      expect(shown.resourceReason).toBe('memory');
      expect(native.startModelDownload).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(AppState, 'currentState', descriptor);
    }
  });

  it('holds job polling until the Settings-return bootstrap finishes and then observes new progress', async () => {
    let tick!: () => void | Promise<void>;
    const originalInterval = globalThis.setInterval;
    jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      if (delay === 1000) { tick = callback; return 769 as unknown as ReturnType<typeof setInterval>; }
      return originalInterval(callback, delay);
    }) as typeof setInterval);
    const descriptor = Object.getOwnPropertyDescriptor(AppState, 'currentState')!;
    Object.defineProperty(AppState, 'currentState', { value: 'active', configurable: true });
    const routerMock = require('./mocks/expo-router');
    const active = { id: 'settings-refresh-job', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    const fresh = snapshot({ jobs: [{ ...active, completedPages: 2, currentPage: 2, updatedAt: 3 }] });
    let finishRefresh: ((value: unknown) => void) | undefined;
    try {
      await renderApp(snapshot({ jobs: [active] }));
      pressSettings();
      expect(await screen.findByRole('header', { name: 'Settings' })).toBeOnTheScreen();
      const bootstrapCalls = native.bootstrap.mock.calls.length;
      native.bootstrap.mockReturnValueOnce(new Promise(resolve => { finishRefresh = resolve; }));
      native.getActivitySnapshot.mockClear();
      await act(async () => { routerMock.__setLocalSearchParams({ screen: 'job', jobId: active.id }); });
      await waitFor(() => expect(native.bootstrap).toHaveBeenCalledTimes(bootstrapCalls + 1));
      await waitFor(() => expect(tick).toBeDefined());

      await act(async () => { await tick(); await tick(); });
      expect(native.getActivitySnapshot).not.toHaveBeenCalled();
      expect(screen.getByText('1 / 12')).toBeOnTheScreen();
      await act(async () => { finishRefresh!(fresh); await Promise.resolve(); });
      expect(screen.getByText('2 / 12')).toBeOnTheScreen();
      native.getActivitySnapshot.mockResolvedValue({
        jobs: [{ ...active, completedPages: 3, currentPage: 3, updatedAt: 4 }],
        models: fresh.models, notifications: undefined,
      });
      await act(async () => { await tick(); });
      expect(native.getActivitySnapshot).toHaveBeenCalledTimes(1);
      expect(screen.getByText('3 / 12')).toBeOnTheScreen();
    } finally {
      finishRefresh?.(fresh);
      Object.defineProperty(AppState, 'currentState', descriptor);
    }
  });

  it('keeps the current job usable when Settings-return refresh fails and resumes polling afterward', async () => {
    let tick!: () => void | Promise<void>;
    const originalInterval = globalThis.setInterval;
    jest.spyOn(globalThis, 'setInterval').mockImplementation(((callback: () => void, delay?: number) => {
      if (delay === 1000) { tick = callback; return 770 as unknown as ReturnType<typeof setInterval>; }
      return originalInterval(callback, delay);
    }) as typeof setInterval);
    const descriptor = Object.getOwnPropertyDescriptor(AppState, 'currentState')!;
    Object.defineProperty(AppState, 'currentState', { value: 'active', configurable: true });
    const routerMock = require('./mocks/expo-router');
    const active = { id: 'settings-refresh-retry-job', documentId: 'doc-1', documentName: 'manual.pdf', modelId: 'model-q4', sourceLanguage: 'en', targetLanguage: 'ru', status: 'running', stage: 'translating', completedPages: 1, totalPages: 12, currentPage: 1, createdAt: 1, updatedAt: 2 };
    const data = snapshot({ jobs: [active] });
    let failRefresh: ((error: Error) => void) | undefined;
    try {
      await renderApp(data);
      pressSettings();
      expect(await screen.findByRole('header', { name: 'Settings' })).toBeOnTheScreen();
      native.bootstrap.mockReturnValueOnce(new Promise((_resolve, reject) => { failRefresh = reject; }));
      native.getActivitySnapshot.mockClear();
      await act(async () => { routerMock.__setLocalSearchParams({ screen: 'job', jobId: active.id }); });
      await waitFor(() => expect(failRefresh).toBeDefined());
      await waitFor(() => expect(tick).toBeDefined());
      await act(async () => { failRefresh!(new Error('temporary bootstrap storage failure')); await Promise.resolve(); await Promise.resolve(); });

      expect(screen.getByTestId('translation-progress-root')).toBeOnTheScreen();
      expect(screen.getByText('1 / 12')).toBeOnTheScreen();
      expect(screen.getByText('temporary bootstrap storage failure')).toBeOnTheScreen();
      native.getActivitySnapshot.mockResolvedValue({ jobs: [{ ...active, completedPages: 2, currentPage: 2, updatedAt: 3 }],
        models: data.models, notifications: undefined });
      await act(async () => { await tick(); });
      expect(native.getActivitySnapshot).toHaveBeenCalledTimes(1);
      expect(screen.getByText('2 / 12')).toBeOnTheScreen();
      expect(native.resumeJob).not.toHaveBeenCalled();
    } finally {
      failRefresh?.(new Error('fixture cleanup'));
      Object.defineProperty(AppState, 'currentState', descriptor);
    }
  });


});
