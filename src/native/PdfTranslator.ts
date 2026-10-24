import { NativeModules } from 'react-native';
import type { TranslationLanguage } from '../domain/translationLanguages';
export type { TranslationLanguage } from '../domain/translationLanguages';

export type TranslationEngine = { kind: 'local'; id: string } | { kind: 'online'; id: 'mymemory' };
export type DocumentFormat = 'pdf' | 'docx';

export type ThemePreference = 'system' | 'light' | 'dark';
export type LanguagePreference = 'system' | string;

export type ModelStatus =
  | 'not_downloaded'
  | 'downloading'
  | 'paused'
  | 'verifying'
  | 'ready'
  | 'error';
export type DocumentColor = 'red' | 'orange' | 'yellow' | 'green' | 'blue' | 'purple';
export type LibrarySort = 'recent' | 'color';

export type JobStatus =
  | 'queued'
  | 'running'
  | 'paused'
  | 'completed'
  | 'partial'
  | 'failed'
  | 'canceled';
export type JobStage =
  | 'queued'
  | 'copying'
  | 'loadingModel'
  | 'extracting'
  | 'ocr'
  | 'translating'
  | 'layout'
  | 'exporting'
  | 'complete';
export type BlockStatus =
  | 'pending'
  | 'translated'
  | 'partial'
  | 'failed'
  | 'skipped';
export type ModelTier = 'compact' | 'balanced' | 'quality';
export type ModelResourceReason =
  | 'available'
  | 'memory'
  | 'disk'
  | 'lowMemory'
  | 'unsupported';

export interface DeviceSnapshot {
  manufacturer: string;
  model: string;
  systemLocale?: string;
  apiLevel: number;
  totalMemoryBytes: number;
  availableMemoryBytes: number;
  systemLowMemoryThresholdBytes: number;
  systemLowMemory: boolean;
  availableDiskBytes: number;
  reservedMemoryBytes: number;
  requiredPipelineMemoryBytes: number;
  requiredDiskBytes: number;
  memoryEstimateEligible: boolean;
  diskEstimateEligible: boolean;
  resourceEstimateEligible: boolean;
  recommendedModelAvailable: boolean;
  recommendedModelId: string;
  recommendationReason: string;
  recommendationVerified: boolean;
  storage?: {
    activeTransferBytes?: number;
    installedModelBytes: number;
    partialDownloadBytes: number;
    freeDiskBytes: number;
  };
}

export interface ModelSnapshot {
  id: string;
  label: string;
  revision: string;
  bytes: number;
  sha256: string;
  verified: boolean;
  checksumPinned: boolean;
  translationEnabled: boolean;
  status: ModelStatus;
  downloadedBytes: number;
  /** Actual model files on disk, including an incomplete download or resume payload. */
  storedBytes?: number;
  partialBytes?: number;
  /** URLSession transferred bytes; progress, not a measured disk footprint. */
  activeTransferBytes?: number;
  /** Relative tier label for the current comparison; it is not a safety certification. */
  tier?: ModelTier;
  /** Full-pipeline estimate excluding the system reserve. */
  requiredMemoryBytes?: number;
  memoryEstimateEligible?: boolean;
  diskEstimateEligible?: boolean;
  resourceEstimateEligible?: boolean;
  resourceReason?: ModelResourceReason;
  licenseUrl?: string;
  supportsGlossary?: boolean;
  /** Counts from the local fixed text sample; not a safety or production rating. */
  evaluation?: { acceptableCases: number; totalCases: number };
  error?: string;
}

export interface DocumentSnapshot {
  id: string;
  name: string;
  pageCount: number;
  format?: DocumentFormat;
  segmentCount?: number;
  importedAt: number;
}

export interface JobSnapshot {
  /** Pause has been requested; the owning worker is still saving/releasing. */
  workerStopping?: boolean;
  stageStartedAt?: number;
  currentBlock?: number;
  pageBlocks?: number;
  completedPageBlocks?: number;
  processedTokens?: number;
  generatedTokens?: number;
  labelColor?: DocumentColor | null;
  id: string;
  documentId: string;
  documentName: string;
  modelId: string;
  engine?: TranslationEngine;
  documentFormat?: DocumentFormat;
  warnings?: Array<string | { code?: string; message: string }>;
  progressUnit?: 'page' | 'segment';
  sourceLanguage: TranslationLanguage;
  targetLanguage: TranslationLanguage;
  status: JobStatus;
  stage: JobStage;
  completedPages: number;
  totalPages: number;
  completedBlocks?: number;
  discoveredBlocks?: number;
  totalBlocks?: number | null;
  etaSeconds?: number | null;
  etaRangeSeconds?: { min: number; max: number } | null;
  currentPage: number | null;
  error?: string;
  createdAt: number;
  updatedAt: number;
}

export interface TextBlock {
  id: string;
  sourceText: string;
  translatedText: string | null;
  left: number;
  top: number;
  right: number;
  bottom: number;
  fontSize: number;
  source: 'native' | 'ocr';
  objectIndex: number | null;
  objectIndices?: number[];
  status: BlockStatus;
  error?: string;
  /** Embedded PDF text disagreed with bundled local OCR for the same page region. */
  sourceDisagreement?: boolean;
}

export interface PageSnapshot {
  index: number;
  width: number;
  height: number;
  blocks: TextBlock[];
  status: 'pending' | 'running' | 'completed' | 'partial' | 'failed';
  error?: string;
}

export interface GlossaryEntry {
  id: string;
  source: string;
  target: string;
  createdAt: number;
  sourceLanguage: TranslationLanguage;
  targetLanguage: TranslationLanguage;
}

export interface BootstrapSnapshot {
  schemaVersion: number;
  device: DeviceSnapshot;
  preferences: {
    appRating?: number;
    librarySort?: LibrarySort;
    notificationExplanationShown?: boolean;
    theme: ThemePreference;
    language: LanguagePreference;
    selectedModelId?: string;
    selectedEngine?: TranslationEngine;
    sourceLanguage?: TranslationLanguage;
    targetLanguage?: TranslationLanguage;
  };
  onboarding: { version: number; page: number; done: boolean };
  models: ModelSnapshot[];
  documents: DocumentSnapshot[];
  jobs: JobSnapshot[];
  glossary: GlossaryEntry[];
  notifications?: NotificationPermissionSnapshot;
}

export type ActivitySnapshot = Pick<BootstrapSnapshot, 'jobs' | 'models' | 'notifications'>;

export type NotificationPermissionStatus =
  | 'granted'
  | 'denied'
  | 'not_determined'
  | 'unavailable'
  | 'not_required';
export interface NotificationPermissionSnapshot {
  status: NotificationPermissionStatus;
  requestedBefore: boolean;
  canRequest: boolean;
}
export interface LicenseNotice {
  id: string;
  title: string;
}

export interface JobDetails {
  job: JobSnapshot;
  pages: Array<{ index: number; status: PageSnapshot['status'] }>;
  selectedPage: PageSnapshot | null;
  /** Version 3 preserves the source page map; version 2 remains readable for older results. */
  pdfLayout?: {
    layoutVersion: 2 | 3;
    outputPageCount: number;
    sourcePageCount: number;
    pages: Array<{
      outputPageIndex: number;
      sourcePageIndex: number;
      sourceOutputPageIndex: number;
    }>;
  } | null;
}

export interface ImportedDocument extends DocumentSnapshot {}
export interface ExportedPdf {
  id: string;
  uri: string;
  displayName: string;
  mimeType?: string;
  format?: DocumentFormat;
}
export interface StartJobInput {
  engine?: TranslationEngine;
  onlineConsent?: boolean;
  documentId: string;
  sourceLanguage: TranslationLanguage;
  targetLanguage: TranslationLanguage;
  modelId: string;
  glossaryId?: string;
  /** Defaults to true for older callers; false freezes an empty glossary snapshot in this job. */
  useGlossary?: boolean;
}
export interface OnboardingState {
  version: number;
  page: number;
  done: boolean;
}
export interface SettingsUpdate {
  appRating?: number;
  librarySort?: LibrarySort;
  selectedEngine?: TranslationEngine;
  notificationExplanationShown?: boolean;
  theme?: ThemePreference;
  language?: LanguagePreference;
  selectedModelId?: string;
  sourceLanguage?: TranslationLanguage;
  targetLanguage?: TranslationLanguage;
}

interface NativePdfTranslatorModule {
  setJobColor(jobId: string, color: string): Promise<string>;
  bootstrap(): Promise<string>;
  getActivitySnapshot(): Promise<string>;
  pickPdf(): Promise<string>;
  pickDocument(): Promise<string>;
  renderDocxPreview(documentId: string, jobId: string | null, translated: boolean): Promise<string>;
  exportDocument(jobId: string, format: DocumentFormat): Promise<string>;
  startModelDownload(modelId: string): Promise<string>;
  pauseModelDownload(modelId: string): Promise<string>;
  deleteModel(modelId: string): Promise<string>;
  startJob(inputJson: string): Promise<string>;
  pauseJob(jobId: string): Promise<string>;
  resumeJob(jobId: string): Promise<string>;
  cancelJob(jobId: string): Promise<string>;
  deleteJob(jobId: string): Promise<string>;
  deleteDocument(documentId: string): Promise<string>;
  getJob(jobId: string, pageIndex: number): Promise<string>;
  renderPage(
    documentId: string,
    pageIndex: number,
    width: number,
  ): Promise<string>;
  renderTranslatedPage(
    jobId: string,
    pageIndex: number,
    width: number,
  ): Promise<string>;
  previewJob(jobId: string): Promise<string>;
  exportJob(jobId: string): Promise<string>;
  shareExport(exportId: string): Promise<void>;
  openPdf(documentId: string): Promise<void>;
  setOnboarding(stateJson: string): Promise<string>;
  setSettings(settingsJson: string): Promise<string>;
  saveGlossaryEntry(entryJson: string): Promise<string>;
  deleteGlossaryEntry(entryId: string): Promise<string>;
  requestNotificationPermission(): Promise<string>;
  getLicenseNotices(): Promise<string>;
  getLicenseNotice(id: string): Promise<string>;
}

const nativeModule = NativeModules.PdfTranslator as
  | NativePdfTranslatorModule
  | undefined;

function requireNativeModule(): NativePdfTranslatorModule {
  if (!nativeModule) {
    throw new Error(
      'The PDF translator native module is unavailable on this platform.',
    );
  }
  return nativeModule;
}

function parseJson<T>(raw: string, operation: string): T {
  try {
    return JSON.parse(raw) as T;
  } catch {
    throw new Error(`${operation} returned invalid native data.`);
  }
}

function parseNullable<T>(raw: string, operation: string): T | null {
  const value = parseJson<T | null>(raw, operation);
  return value === null ? null : value;
}

/** Typed JS boundary for the local PDF translation native module. */
export const PdfTranslator = {
  async bootstrap(): Promise<BootstrapSnapshot> {
    return parseJson(await requireNativeModule().bootstrap(), 'bootstrap');
  },

  async getActivitySnapshot(): Promise<ActivitySnapshot> {
    return parseJson(await requireNativeModule().getActivitySnapshot(), 'getActivitySnapshot');
  },

  async pickPdf(): Promise<ImportedDocument | null> {
    return parseNullable(await requireNativeModule().pickPdf(), 'pickPdf');
  },

  async pickDocument(): Promise<ImportedDocument | null> {
    return parseNullable(await requireNativeModule().pickDocument(), 'pickDocument');
  },
  async renderDocxPreview(documentId: string, jobId: string | null = null, translated = false): Promise<{uri: string; pageCount: number}> {
    return parseJson(await requireNativeModule().renderDocxPreview(documentId, jobId, translated), 'renderDocxPreview');
  },
  async exportDocument(jobId: string, format: DocumentFormat): Promise<ExportedPdf> {
    return parseJson(await requireNativeModule().exportDocument(jobId, format), 'exportDocument');
  },

  async startModelDownload(modelId: string): Promise<ModelSnapshot> {
    return parseJson(
      await requireNativeModule().startModelDownload(modelId),
      'startModelDownload',
    );
  },
  async pauseModelDownload(modelId: string): Promise<ModelSnapshot> {
    return parseJson(
      await requireNativeModule().pauseModelDownload(modelId),
      'pauseModelDownload',
    );
  },
  async deleteModel(modelId: string): Promise<void> {
    await requireNativeModule().deleteModel(modelId);
  },

  async requestNotificationPermission(): Promise<NotificationPermissionSnapshot> {
    return parseJson(
      await requireNativeModule().requestNotificationPermission(),
      'requestNotificationPermission',
    );
  },

  async getLicenseNotices(): Promise<LicenseNotice[]> {
    return parseJson(
      await requireNativeModule().getLicenseNotices(),
      'getLicenseNotices',
    );
  },

  async getLicenseNotice(id: string): Promise<string> {
    return requireNativeModule().getLicenseNotice(id);
  },

  async startJob(input: StartJobInput): Promise<JobSnapshot> {
    return parseJson(
      await requireNativeModule().startJob(JSON.stringify(input)),
      'startJob',
    );
  },
  async pauseJob(jobId: string): Promise<JobSnapshot> {
    return parseJson(await requireNativeModule().pauseJob(jobId), 'pauseJob');
  },
  async resumeJob(jobId: string): Promise<JobSnapshot> {
    return parseJson(await requireNativeModule().resumeJob(jobId), 'resumeJob');
  },
  async cancelJob(jobId: string): Promise<JobSnapshot> {
    return parseJson(await requireNativeModule().cancelJob(jobId), 'cancelJob');
  },
  async deleteJob(jobId: string): Promise<void> {
    await requireNativeModule().deleteJob(jobId);
  },
  async deleteDocument(documentId: string): Promise<void> {
    await requireNativeModule().deleteDocument(documentId);
  },
  async getJob(jobId: string, pageIndex = 0): Promise<JobDetails> {
    return parseJson(
      await requireNativeModule().getJob(jobId, pageIndex),
      'getJob',
    );
  },
  /** Resolves an Android file:// URI in the bounded private thumbnail cache. */
  async renderPage(
    documentId: string,
    pageIndex: number,
    width: number,
  ): Promise<string> {
    return requireNativeModule().renderPage(documentId, pageIndex, width);
  },
  /** Resolves a private file:// thumbnail URI for the translated job page. */
  async renderTranslatedPage(
    jobId: string,
    pageIndex: number,
    width: number,
  ): Promise<string> {
    return requireNativeModule().renderTranslatedPage(jobId, pageIndex, width);
  },
  async previewJob(jobId: string): Promise<ExportedPdf> {
    return parseJson(
      await requireNativeModule().previewJob(jobId),
      'previewJob',
    );
  },
  async exportJob(jobId: string): Promise<ExportedPdf> {
    return parseJson(await requireNativeModule().exportJob(jobId), 'exportJob');
  },
  async shareExport(exportId: string): Promise<void> {
    await requireNativeModule().shareExport(exportId);
  },
  async openPdf(documentId: string): Promise<void> {
    await requireNativeModule().openPdf(documentId);
  },

  async setOnboarding(state: OnboardingState): Promise<OnboardingState> {
    return parseJson(
      await requireNativeModule().setOnboarding(JSON.stringify(state)),
      'setOnboarding',
    );
  },
  async setJobColor(jobId: string, color: DocumentColor | null): Promise<JobSnapshot> {
    return parseJson(await requireNativeModule().setJobColor(jobId, color ?? ''), 'setJobColor');
  },
  async setSettings(
    settings: SettingsUpdate,
  ): Promise<BootstrapSnapshot['preferences']> {
    return parseJson(
      await requireNativeModule().setSettings(JSON.stringify(settings)),
      'setSettings',
    );
  },
  async saveGlossaryEntry(
    entry: Pick<
      GlossaryEntry,
      'id' | 'source' | 'target' | 'sourceLanguage' | 'targetLanguage'
    >,
  ): Promise<GlossaryEntry> {
    return parseJson(
      await requireNativeModule().saveGlossaryEntry(JSON.stringify(entry)),
      'saveGlossaryEntry',
    );
  },
  async deleteGlossaryEntry(entryId: string): Promise<void> {
    await requireNativeModule().deleteGlossaryEntry(entryId);
  },
};
