function makeBridge() {
  return {
    requestNotificationPermission: jest.fn(),
    getLicenseNotices: jest.fn(),
    getLicenseNotice: jest.fn(),
    bootstrap: jest.fn(),
    pickPdf: jest.fn(),
    startModelDownload: jest.fn(),
    pauseModelDownload: jest.fn(),
    deleteModel: jest.fn(),
    startJob: jest.fn(),
    pauseJob: jest.fn(),
    resumeJob: jest.fn(),
    cancelJob: jest.fn(),
    deleteJob: jest.fn(),
    getJob: jest.fn(),
    renderPage: jest.fn(),
    renderTranslatedPage: jest.fn(),
    previewJob: jest.fn(),
    exportJob: jest.fn(),
    shareExport: jest.fn(),
    openPdf: jest.fn(),
    setOnboarding: jest.fn(),
    setSettings: jest.fn(),
    saveGlossaryEntry: jest.fn(),
    deleteGlossaryEntry: jest.fn(),
  };
}

function loadTranslator(bridge?: ReturnType<typeof makeBridge>) {
  const nativeModules = require('react-native').NativeModules as Record<
    string,
    unknown
  >;
  nativeModules.PdfTranslator = bridge;
  let api:
    | typeof import('../src/native/PdfTranslator').PdfTranslator
    | undefined;
  jest.isolateModules(() => {
    api = require('../src/native/PdfTranslator').PdfTranslator;
  });
  return api as typeof import('../src/native/PdfTranslator').PdfTranslator;
}

describe('typed native bridge boundary', () => {
  let bridge: ReturnType<typeof makeBridge>;
  let api: typeof import('../src/native/PdfTranslator').PdfTranslator;

  beforeEach(() => {
    bridge = makeBridge();
    api = loadTranslator(bridge);
  });

  it('parses bootstrap and nullable document picker results', async () => {
    const snapshot = {
      schemaVersion: 1,
      models: [],
      documents: [],
      jobs: [],
      glossary: [],
    };
    bridge.bootstrap.mockResolvedValue(JSON.stringify(snapshot));
    bridge.pickPdf.mockResolvedValue('null');
    expect(await api.bootstrap()).toEqual(snapshot);
    expect(await api.pickPdf()).toBeNull();
    bridge.pickPdf.mockResolvedValue('{"id":"doc-1","name":"guide.pdf"}');
    expect(await api.pickPdf()).toEqual({ id: 'doc-1', name: 'guide.pdf' });
  });

  it('forwards model operations and preserves raw thumbnail URIs', async () => {
    const model = { id: 'q4', status: 'ready' };
    bridge.startModelDownload.mockResolvedValue(JSON.stringify(model));
    bridge.pauseModelDownload.mockResolvedValue(JSON.stringify(model));
    bridge.deleteModel.mockResolvedValue(undefined);
    bridge.renderPage.mockResolvedValue('file:///private/original.png');
    bridge.renderTranslatedPage.mockResolvedValue(
      'file:///private/translated.png',
    );

    expect(await api.startModelDownload('q4')).toEqual(model);
    expect(await api.pauseModelDownload('q4')).toEqual(model);
    await api.deleteModel('q4');
    expect(await api.renderPage('doc-1', 2, 720)).toBe(
      'file:///private/original.png',
    );
    expect(await api.renderTranslatedPage('job-1', 2, 720)).toBe(
      'file:///private/translated.png',
    );
    expect(bridge.renderPage).toHaveBeenCalledWith('doc-1', 2, 720);
    expect(bridge.renderTranslatedPage).toHaveBeenCalledWith('job-1', 2, 720);
  });

  it('serializes job inputs and parses job, preview, and export snapshots', async () => {
    const input = {
      documentId: 'doc-1',
      sourceLanguage: 'en' as const,
      targetLanguage: 'ru' as const,
      modelId: 'q4',
    };
    const job = { id: 'job-1', status: 'queued' };
    const details = { job, pages: [], selectedPage: null };
    const pdf = {
      id: 'export-1',
      uri: 'content://provider/file',
      displayName: 'guide-ru.pdf',
    };
    for (const name of ['startJob', 'pauseJob', 'resumeJob', 'cancelJob'])
      bridge[name as keyof typeof bridge].mockResolvedValue(
        JSON.stringify(job),
      );
    bridge.getJob.mockResolvedValue(JSON.stringify(details));
    bridge.previewJob.mockResolvedValue(JSON.stringify(pdf));
    bridge.exportJob.mockResolvedValue(JSON.stringify(pdf));

    expect(await api.startJob(input)).toEqual(job);
    expect(bridge.startJob).toHaveBeenCalledWith(JSON.stringify(input));
    expect(await api.pauseJob('job-1')).toEqual(job);
    expect(await api.resumeJob('job-1')).toEqual(job);
    expect(await api.cancelJob('job-1')).toEqual(job);
    expect(await api.getJob('job-1')).toEqual(details);
    expect(bridge.getJob).toHaveBeenCalledWith('job-1', 0);
    expect(await api.getJob('job-1', 3)).toEqual(details);
    expect(bridge.getJob).toHaveBeenLastCalledWith('job-1', 3);
    expect(await api.previewJob('job-1')).toEqual(pdf);
    expect(await api.exportJob('job-1')).toEqual(pdf);
  });

  it('serializes preferences and glossary data and forwards void actions', async () => {
    const onboarding = { version: 1, page: 4, done: true };
    const preferences = { theme: 'dark' as const, language: 'ar' };
    const entry = {
      id: 'term-1',
      source: 'coolant',
      target: 'охлаждающая жидкость',
      sourceLanguage: 'en' as const,
      targetLanguage: 'ru' as const,
    };
    const savedEntry = { ...entry, createdAt: 42 };
    bridge.setOnboarding.mockResolvedValue(JSON.stringify(onboarding));
    bridge.setSettings.mockResolvedValue(JSON.stringify(preferences));
    bridge.saveGlossaryEntry.mockResolvedValue(JSON.stringify(savedEntry));

    expect(await api.setOnboarding(onboarding)).toEqual(onboarding);
    expect(bridge.setOnboarding).toHaveBeenCalledWith(
      JSON.stringify(onboarding),
    );
    expect(await api.setSettings({ theme: 'dark', language: 'ar' })).toEqual(
      preferences,
    );
    expect(bridge.setSettings).toHaveBeenCalledWith(
      JSON.stringify({ theme: 'dark', language: 'ar' }),
    );
    expect(await api.saveGlossaryEntry(entry)).toEqual(savedEntry);
    expect(bridge.saveGlossaryEntry).toHaveBeenCalledWith(
      JSON.stringify(entry),
    );

    await api.deleteJob('job-1');
    await api.shareExport('export-1');
    await api.openPdf('doc-1');
    await api.deleteGlossaryEntry('term-1');
    expect(bridge.deleteJob).toHaveBeenCalledWith('job-1');
    expect(bridge.shareExport).toHaveBeenCalledWith('export-1');
    expect(bridge.openPdf).toHaveBeenCalledWith('doc-1');
    expect(bridge.deleteGlossaryEntry).toHaveBeenCalledWith('term-1');
  });

  it('reads notification state and bundled notices without opening external links', async () => {
    bridge.requestNotificationPermission.mockResolvedValue(
      '{"status":"denied","requestedBefore":true,"canRequest":false}',
    );
    expect(await api.requestNotificationPermission()).toEqual({
      status: 'denied',
      requestedBefore: true,
      canRequest: false,
    });
    bridge.getLicenseNotices.mockResolvedValue(
      '[{"id":"react/LICENSE","title":"React"}]',
    );
    bridge.getLicenseNotice.mockResolvedValue('MIT license notice');
    expect(await api.getLicenseNotices()).toEqual([
      { id: 'react/LICENSE', title: 'React' },
    ]);
    expect(await api.getLicenseNotice('react/LICENSE')).toBe(
      'MIT license notice',
    );
    expect(bridge.getLicenseNotice).toHaveBeenCalledWith('react/LICENSE');
    bridge.getLicenseNotices.mockResolvedValue('invalid');
    await expect(api.getLicenseNotices()).rejects.toThrow(
      'getLicenseNotices returned invalid native data.',
    );
    bridge.requestNotificationPermission.mockResolvedValue('invalid');
    await expect(api.requestNotificationPermission()).rejects.toThrow(
      'requestNotificationPermission returned invalid native data.',
    );
  });

  it('keeps the per-job dictionary opt-out on the wire', async () => {
    bridge.startJob.mockResolvedValue('{"id":"job-1"}');
    const input = {
      documentId: 'doc-1',
      modelId: 'q4',
      sourceLanguage: 'en' as const,
      targetLanguage: 'ru' as const,
      useGlossary: false,
    };
    await api.startJob(input);
    expect(JSON.parse(bridge.startJob.mock.calls[0][0]).useGlossary).toBe(
      false,
    );
  });

  it('turns invalid native JSON into operation-specific errors', async () => {
    bridge.bootstrap.mockResolvedValue('{not json');
    await expect(api.bootstrap()).rejects.toThrow(
      'bootstrap returned invalid native data.',
    );
    bridge.pickPdf.mockResolvedValue('undefined');
    await expect(api.pickPdf()).rejects.toThrow(
      'pickPdf returned invalid native data.',
    );
  });

  it('reports a missing native module instead of inventing local state', async () => {
    const unavailable = loadTranslator(undefined);
    await expect(unavailable.bootstrap()).rejects.toThrow(
      'The PDF translator native module is unavailable on this platform.',
    );
  });
});
