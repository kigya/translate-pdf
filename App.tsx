import { ReviewLayout, ReviewColumn } from './src/ui/ReviewLayout';
import { TranslationActivity } from './src/ui/TranslationActivity';
import { UiLocaleContext } from './src/i18n/UiLocaleContext';
import { WordResultScreen } from './src/screens/WordResultScreen';
import { useLocalSearchParams, useNavigationContainerRef, useRootNavigationState, useRouter } from 'expo-router';
import { KinetikProvider } from './src/lib/kinetik/tokens/theme';
import { usePaletteTransition } from './src/ui/usePaletteTransition';
import { ToastProvider } from './src/components/kinetik/toast-stack';
import { LargeDocumentHint } from './src/ui/LargeDocumentHint';
import { ProcessingMetrics } from './src/ui/ProcessingMetrics';
import type { LibraryFolder } from './src/ui/DocumentLibrary';
import { processingUx } from './src/i18n/processingUx';
import { LanguagePair } from './src/ui/LanguagePair';
import { useMotionReduced } from './src/ui/useMotionReduced';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  AppState,
  AppStateStatus,
  BackHandler,
  ColorSchemeName,
  Image,
  Linking,
  Platform,
  ScrollView,
  StatusBar,
  Text,
  View,
  useColorScheme,
  useWindowDimensions,
} from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import {
  BootstrapSnapshot,
  DocumentSnapshot,
  ExportedPdf,
  JobDetails,
  JobSnapshot,
  ModelSnapshot,
  OnboardingState,
  PdfTranslator,
} from './src/native/PdfTranslator';
import {
  normalizeLocale,
  createTranslator,
  localeDirection,
  MessageKey,
  Locale,
} from './src/i18n/messages';
import { resolveTheme, palettes, kinetikBrand } from './src/ui/theme';
import {
  Button,
  Card,
  Header,
  Label,
  Notice,
  Row,
  Screen,
} from './src/ui/components';
import {
  ONBOARDING_VERSION,
  OnboardingScreen,
} from './src/onboarding/OnboardingScreen';

import { SplashScreen } from './src/ui/SplashScreen';
import { DialogContext, useDialogController } from './src/ui/Dialog';
import {
  LibraryScreen,
  ModelScreen,
  TranslationOptionsScreen,
  SettingsScreen,
  LanguageChoices,
  StorageScreen,
  DictionaryScreen,
  SourcePreviewScreen,
  LicensesScreen,
} from './src/screens/AppScreens';
import { ux } from './src/i18n/ux';
import { PRIVACY_URL, ONLINE_MODEL } from './src/domain/presentation';

import {
  resolveTranslationPair,
  translationLanguageName,
  TranslationLanguage,
} from './src/domain/translationLanguages';

type Route =
  | 'home'
  | 'model'
  | 'prepare'
  | 'job'
  | 'preview'
  | 'settings'
  | 'privacy'
  | 'licenses'
  | 'language'
  | 'storage'
  | 'dictionary'
  | 'sourcePreview';
const startupNow = () =>
  (
    globalThis as unknown as { performance?: { now: () => number } }
  ).performance?.now() ?? Date.now();
const BASE_ONBOARDING: OnboardingState = {
  version: ONBOARDING_VERSION,
  page: 0,
  done: false,
};
const stageKeys: Record<JobSnapshot['stage'], MessageKey> = {
  queued: 'stageQueued',
  copying: 'stageCopying',
  loadingModel: 'stageQueued',
  extracting: 'stageExtracting',
  ocr: 'stageOcr',
  translating: 'stageTranslating',
  layout: 'stageLayout',
  exporting: 'stageExporting',
  complete: 'stageComplete',
};
const statusKeys: Record<JobSnapshot['status'], MessageKey> = {
  queued: 'running',
  running: 'running',
  paused: 'pausedStatus',
  completed: 'completed',
  partial: 'partial',
  failed: 'failed',
  canceled: 'canceledStatus',
};
const isRunning = (status: JobSnapshot['status']) =>
  status === 'running' || status === 'queued';
const formatDuration = (
  seconds: number,
  unit: (key: 'seconds' | 'minutes' | 'hours') => string,
) =>
  seconds < 60
    ? `${Math.ceil(seconds)} ${unit('seconds')}`
    : seconds < 3600
    ? `${Math.ceil(seconds / 60)} ${unit('minutes')}`
    : `${Math.floor(seconds / 3600)} ${unit('hours')} ${Math.ceil(
        (seconds % 3600) / 60,
      )} ${unit('minutes')}`;
const localeSystem = (nativeLocale?: string) =>
  normalizeLocale(
    nativeLocale ?? Intl?.DateTimeFormat?.().resolvedOptions?.().locale ?? 'en',
  );

const JOB_ACTIVITY_FIELDS: ReadonlyArray<keyof JobSnapshot> = [
  'id', 'status', 'stage', 'workerStopping', 'stageStartedAt', 'currentPage',
  'currentBlock', 'completedPages', 'totalPages', 'completedBlocks',
  'discoveredBlocks', 'totalBlocks', 'pageBlocks', 'completedPageBlocks',
  'processedTokens', 'generatedTokens', 'etaSeconds', 'updatedAt', 'error',
];
const MODEL_ACTIVITY_FIELDS: ReadonlyArray<keyof ModelSnapshot> = [
  'id', 'status', 'downloadedBytes', 'storedBytes', 'partialBytes',
  'activeTransferBytes', 'resourceEstimateEligible', 'memoryEstimateEligible',
  'diskEstimateEligible', 'resourceReason', 'error',
];

function activityMatchesSnapshot(
  snapshot: BootstrapSnapshot,
  activity: Awaited<ReturnType<typeof PdfTranslator.getActivitySnapshot>>,
) {
  if (snapshot.jobs.length !== activity.jobs.length || snapshot.models.length !== activity.models.length) return false;
  for (let index = 0; index < snapshot.jobs.length; index++) {
    const current = snapshot.jobs[index];
    const next = activity.jobs[index];
    for (const field of JOB_ACTIVITY_FIELDS) if (current[field] !== next[field]) return false;
  }
  for (let index = 0; index < snapshot.models.length; index++) {
    const current = snapshot.models[index];
    const next = activity.models[index];
    if (current.id !== next.id) return false;
    for (const field of MODEL_ACTIVITY_FIELDS) if (current[field] !== next[field]) return false;
  }
  const currentNotifications = snapshot.notifications;
  const nextNotifications = activity.notifications;
  return currentNotifications?.status === nextNotifications?.status &&
    currentNotifications?.requestedBefore === nextNotifications?.requestedBefore &&
    currentNotifications?.canRequest === nextNotifications?.canRequest;
}

function AppContent() {
  const scheme = useColorScheme();
  const reducedMotion = useMotionReduced();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  // Once bootstrap succeeds this state is never cleared. Mutation controls are
  // only exposed below the !snapshot return, so their functional updaters retain
  // a non-null snapshot, including across refresh failures.
  const [snapshot, setSnapshot] = useState<BootstrapSnapshot | null>(null);
  const [bootstrapError, setBootstrapError] = useState('');
  const router = useRouter();
  const navigationContainerRef = useNavigationContainerRef();
  const rootNavigationState = useRootNavigationState();
  const rootNavigationKey = rootNavigationState?.key;
  const { screen, jobId: linkedJobId } = useLocalSearchParams<{ screen?: Route; jobId?: string }>();
  const route: Route = screen ?? 'home';
  const routeStack = useRef<Route[]>([]);
  const pendingRoute = useRef<{ next: Route; history: 'push' | 'pop' | 'clear' | 'preserve'; previous?: Route } | null>(null);
  const applyRoute = useCallback((intent: { next: Route; history: 'push' | 'pop' | 'clear' | 'preserve'; previous?: Route }) => {
    if (!rootNavigationKey || !navigationContainerRef.isReady()) {
      pendingRoute.current = intent;
      return false;
    }
    router.setParams({ screen: intent.next, jobId: undefined });
    if (intent.history === 'push' && intent.previous) routeStack.current.push(intent.previous);
    else if (intent.history === 'pop') routeStack.current.pop();
    else if (intent.history === 'clear') routeStack.current = [];
    pendingRoute.current = null;
    return true;
  }, [navigationContainerRef, rootNavigationKey, router]);
  const setRoute = useCallback((next: Route, history: 'push' | 'pop' | 'clear' | 'preserve' = 'preserve', previous?: Route) => {
    const intent = { next, history, previous };
    pendingRoute.current = intent;
    applyRoute(intent);
  }, [applyRoute]);
  useEffect(() => {
    const intent = pendingRoute.current;
    if (rootNavigationKey && intent) applyRoute(intent);
  }, [applyRoute, rootNavigationState, rootNavigationKey]);
  const [selectedDocument, setSelectedDocument] =
    useState<DocumentSnapshot | null>(null);
  const [libraryFolder, setLibraryFolder] = useState<LibraryFolder | null>(null);
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [details, setDetails] = useState<JobDetails | null>(null);
  const [selectedPage, setSelectedPage] = useState(0);
  const [renderedUri, setRenderedUri] = useState('');
  const [previewOriginal, setPreviewOriginal] = useState(false);
  const [exported, setExported] = useState<ExportedPdf | null>(null);
  const [inlineError, setInlineError] = useState('');
  const [busy, setBusy] = useState(false);
  const [appState, setAppState] = useState<AppStateStatus>(
    (AppState.currentState ?? 'active') as AppStateStatus,
  );
  const notificationRationaleSeen = useRef(false);
  const operationInFlight = useRef(false);
  const snapshotRequestEpoch = useRef(0);
  const refreshRequestsInFlight = useRef(0);
  const [startupReady, setStartupReady] = useState(false);
  const [splashShownAt, setSplashShownAt] = useState<number | null>(null);
  const markSplashShown = useCallback(
    () => setSplashShownAt(current => current ?? startupNow()),
    [],
  );
  const [replayIntro, setReplayIntro] = useState(false);
  const [useGlossary, setUseGlossary] = useState(true);
  const [bootComplete, setBootComplete] = useState(false);
  const [onboardingOverride, setOnboardingOverride] =
    useState<OnboardingState | null>(null);

  const preferenceTheme = snapshot?.preferences.theme ?? 'system';
  const activeTheme = useMemo(
    () => resolveTheme(preferenceTheme, scheme as ColorSchemeName),
    [preferenceTheme, scheme],
  );
  const transition = usePaletteTransition(activeTheme, reducedMotion);
  const colors = palettes[transition.mode];
  const frame = (children: React.ReactNode) => <UiLocaleContext.Provider value={locale}><KinetikProvider scheme={transition.mode} colors={kinetikBrand[transition.mode]} reduceMotion={reducedMotion ? 'always' : 'never'}><ToastProvider><LargeDocumentHint jobs={snapshot?.jobs ?? []} notifications={snapshot?.notifications} active={bootComplete && !showIntro && appState === 'active'} locale={locale} onEnable={() => { void safely(async () => { if (snapshot?.notifications?.canRequest) await PdfTranslator.requestNotificationPermission(); else await Linking.openSettings(); }); }} /><View ref={transition.view} collapsable={false} style={{ flex: 1 }}>{children}{transition.image ? <View pointerEvents="none" collapsable={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}><Animated.Image source={{ uri: transition.image }} onLoad={transition.reveal} onError={transition.reveal} style={[{ width: '100%', height: '100%' }, transition.overlay]} /></View> : null}</View></ToastProvider></KinetikProvider></UiLocaleContext.Provider>;
  const savedLanguage = snapshot?.preferences.language ?? 'system';
  const locale: Locale =
    savedLanguage === 'system'
      ? localeSystem(snapshot?.device.systemLocale)
      : normalizeLocale(savedLanguage);
  const t = useMemo(() => createTranslator(locale), [locale]);
  const currentTranslator = useRef(t);
  currentTranslator.current = t;
  const direction = localeDirection(locale);
  const { showDialog, dialog, dialogOpen } = useDialogController(colors, direction, locale);
  const onboarding =
    onboardingOverride ?? snapshot?.onboarding ?? BASE_ONBOARDING;
  const showIntro = replayIntro || !onboarding.done;
  const wide = width >= 760;
  const selectedJob =
    snapshot?.jobs.find(job => job.id === selectedJobId) ??
    snapshot?.jobs.find(
      job => job.status === 'running' || job.status === 'paused',
    ) ??
    null;
  const linkedJobExists = !!snapshot?.jobs.some(job => job.id === linkedJobId);
  useEffect(() => {
    if (linkedJobId && linkedJobExists) setSelectedJobId(linkedJobId);
  }, [linkedJobId, linkedJobExists]);
  const recommendedModel =
    snapshot?.models.find(
      model => model.id === snapshot.device.recommendedModelId,
    ) ?? null;
  const selectedModel = snapshot?.preferences.selectedEngine?.kind === 'online' ? ONLINE_MODEL :
    snapshot?.models.find(
      model =>
        model.id === snapshot.preferences.selectedModelId &&
        model.translationEnabled,
    ) ??
    (recommendedModel?.status === 'ready' && recommendedModel.translationEnabled
      ? recommendedModel
      : null) ??
    snapshot?.models.find(
      model => model.status === 'ready' && model.translationEnabled,
    ) ??
    recommendedModel;

  const { sourceLanguage, targetLanguage } = resolveTranslationPair(
    snapshot?.preferences.sourceLanguage,
    snapshot?.preferences.targetLanguage,
  );
  const pairLabel = (
    source: TranslationLanguage,
    target: TranslationLanguage,
  ) =>
    `${translationLanguageName(source)} → ${translationLanguageName(target)}`;
  const pairGlossary =
    snapshot?.glossary.filter(
      entry =>
        (entry.sourceLanguage ?? 'en') === sourceLanguage &&
        (entry.targetLanguage ?? 'ru') === targetLanguage,
    ) ?? [];

  const refresh = useCallback(async () => {
    refreshRequestsInFlight.current++;
    const request = ++snapshotRequestEpoch.current;
    try {
      const fresh = await PdfTranslator.bootstrap();
      if (request === snapshotRequestEpoch.current) {
        setSnapshot(fresh);
        setBootstrapError('');
      }
      return fresh;
    } catch (error) {
      if (request === snapshotRequestEpoch.current)
        setBootstrapError(
          error instanceof Error
            ? error.message
            : currentTranslator.current('unknownError'),
        );
      throw error;
    } finally {
      refreshRequestsInFlight.current--;
    }
  }, []);

  useEffect(() => {
    let mounted = true;
    PdfTranslator.bootstrap()
      .then(fresh => {
        if (mounted) setSnapshot(fresh);
      })
      .catch(error => {
        if (mounted)
          setBootstrapError(
            error instanceof Error
              ? error.message
              : currentTranslator.current('unknownError'),
          );
      })
      .finally(() => {
        if (mounted) setStartupReady(true);
      });
    const subscription = AppState.addEventListener('change', setAppState);
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);
  useEffect(() => {
    if (!startupReady || splashShownAt === null) return;
    const timer = setTimeout(
      () => setBootComplete(true),
      Math.max(0, 1500 - (startupNow() - splashShownAt)),
    );
    return () => clearTimeout(timer);
  }, [startupReady, splashShownAt]);

  const activeWork = !!snapshot && (snapshot.jobs.some(job => isRunning(job.status) || job.workerStopping) || snapshot.models.some(model => model.status === 'downloading' || model.status === 'verifying'));
  const pollingInterval = route === 'job' || route === 'model' ? 1000 : route === 'home' ? 4000 : null;
  useEffect(() => {
    if (appState !== 'active' || !activeWork || pollingInterval === null) return;
    let live = true;
    let polling = false;
    const timer = setInterval(async () => {
      if (polling || operationInFlight.current || refreshRequestsInFlight.current > 0) return;
      polling = true;
      const request = snapshotRequestEpoch.current;
      try {
        const activity = await PdfTranslator.getActivitySnapshot();
        if (live && request === snapshotRequestEpoch.current && !operationInFlight.current && refreshRequestsInFlight.current === 0) {
          setSnapshot(current => {
            if (!current || activityMatchesSnapshot(current, activity)) return current;
            return {
              ...current,
              jobs: activity.jobs,
              models: current.models.map(model => {
                const updated = activity.models.find(value => value.id === model.id);
                return updated ? { ...model, ...updated } : model;
              }),
              notifications: activity.notifications ?? current.notifications,
            };
          });
        }
      } catch {
        // A transient poll failure leaves the last durable snapshot usable.
      } finally { polling = false; }
    }, pollingInterval);
    return () => { live = false; clearInterval(timer); };
  }, [appState, activeWork, pollingInterval]);
  const previousRoute = useRef(route);
  useEffect(() => {
    const previous = previousRoute.current;
    previousRoute.current = route;
    if (previous === 'settings' && route !== 'settings' && appState === 'active' && !operationInFlight.current) {
      void refresh().catch(() => undefined);
    }
  }, [appState, refresh, route]);
  const previousAppState = useRef(appState);
  useEffect(() => {
    const previous = previousAppState.current;
    previousAppState.current = appState;
    if (previous !== 'active' && appState === 'active' && !operationInFlight.current) void refresh().catch(() => undefined);
  }, [appState, refresh]);

  useEffect(() => {
    if (
      snapshot &&
      !showIntro &&
      route === 'home' &&
      selectedJob?.status &&
      selectedJob.status !== 'completed' &&
      selectedJob.status !== 'partial' &&
      !selectedJobId
    ) {
      setSelectedJobId(selectedJob.id);
    }
  }, [route, selectedJob, selectedJobId, showIntro, snapshot]);

  useEffect(() => {
    if (!selectedJobId || route !== 'preview' || selectedJob?.documentFormat === 'docx') return;
    let live = true;
    setRenderedUri('');
    PdfTranslator.getJob(selectedJobId, selectedPage)
      .then(value => {
        if (!live) return;
        setDetails(value);
        if (value.selectedPage && value.selectedPage.status !== 'pending') {
          return previewOriginal
            ? PdfTranslator.renderPage(
                value.job.documentId,
                value.selectedPage.index,
                Math.min(900, width * 1.5),
              )
            : PdfTranslator.renderTranslatedPage(
                value.job.id,
                value.pdfLayout ? selectedPage : value.selectedPage.index,
                Math.min(900, width * 1.5),
              );
        }
        return '';
      })
      .then(uri => {
        if (live && uri) setRenderedUri(uri);
      })
      .catch(error => {
        if (live)
          setInlineError(
            error instanceof Error ? error.message : t('unknownError'),
          );
      });
    return () => {
      live = false;
    };
  }, [route, selectedJobId, selectedPage, previewOriginal, t, width, selectedJob?.documentFormat]);

  const go = (next: Route) => {
    setInlineError('');
    setRoute(next, 'push', route);
  };
  const back = () => {
    setInlineError('');
    const previous = routeStack.current.length > 0 ? routeStack.current[routeStack.current.length - 1] : 'home';
    setRoute(previous, 'pop');
  };
  useEffect(() => {
    if (showIntro || (route === 'home' && !libraryFolder)) return;
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        setInlineError('');
        if (route === 'home' && libraryFolder) setLibraryFolder(null);
        else {
          const previous = routeStack.current.length > 0 ? routeStack.current[routeStack.current.length - 1] : 'home';
          setRoute(previous, 'pop');
        }
        return true;
      },
    );
    return () => subscription.remove();
  }, [showIntro, route, setRoute, libraryFolder]);
  const safely = async <T,>(
    operation: () => Promise<T>,
    success?: (value: T) => void,
  ) => {
    if (operationInFlight.current) return undefined;
    operationInFlight.current = true;
    snapshotRequestEpoch.current++;
    setBusy(true);
    setInlineError('');
    try {
      const value = await operation();
      success?.(value);
      await refresh().catch(() => undefined);
      return value;
    } catch (error) {
      setInlineError(
        error instanceof Error ? error.message : t('unknownError'),
      );
      return undefined;
    } finally {
      operationInFlight.current = false;
      setBusy(false);
    }
  };


  const pickPdf = () =>
    safely(
      () => PdfTranslator.pickDocument(),
      document => {
        if (!document) return;
        setSelectedDocument(document);
        setExported(null);
        go('prepare');
      },
    );
  const explainNotifications = async (download: boolean) => {
    if (
      !snapshot?.notifications?.canRequest ||
      snapshot.preferences.notificationExplanationShown ||
      notificationRationaleSeen.current
    )
      return;
    notificationRationaleSeen.current = true;
    await PdfTranslator.setSettings({
      notificationExplanationShown: true,
    }).catch(() => undefined);
    const allow = await showDialog({
      title: ux(locale, download ? 'notificationTitle' : 'notificationTranslationTitle'),
      message: ux(locale, 'notificationBody'), icon: 'notification',
      actions: [{ id: 'later', label: ux(locale, 'notNow'), quiet: true }, { id: 'allow', label: ux(locale, 'allowNotifications') }],
    }) === 'allow';
    if (allow)
      await PdfTranslator.requestNotificationPermission().catch(
        () => undefined,
      );
  };
  const startDownload = (model: ModelSnapshot) =>
    safely(async () => {
      if (
        model.licenseUrl?.includes('gemma') &&
        !['paused', 'ready'].includes(model.status)
      ) {
        const choice = await showDialog({
          title: ux(locale, 'modelDetails'), message: t('gemmaTermsNotice'), icon: 'license',
          actions: [
            { id: 'cancel', label: ux(locale, 'cancel'), quiet: true },
            { id: 'license', label: t('licenses'), quiet: true },
            { id: 'accept', label: t('acceptAndDownload') },
          ],
        });
        if (choice === 'license') await Linking.openURL(model.licenseUrl).catch(() => undefined);
        const accepted = choice === 'accept';
        if (!accepted) return;
      }
      await explainNotifications(true);
      return PdfTranslator.startModelDownload(model.id);
    });
  const pauseDownload = (model: ModelSnapshot) =>
    safely(() => PdfTranslator.pauseModelDownload(model.id));
  const deleteModel = async (model: ModelSnapshot) => {
    const result = await showDialog({ title: ux(locale, 'removeModel'), message: ux(locale, 'deleteModelInfo'), icon: 'trash', actions: [
      { id: 'cancel', label: ux(locale, 'cancel'), quiet: true }, { id: 'delete', label: ux(locale, 'confirm'), destructive: true },
    ] });
    if (result === 'delete') await safely(() => PdfTranslator.deleteModel(model.id));
  };
  const deleteLibraryEntry = async (entry: JobSnapshot | DocumentSnapshot) => {
    const answer = await showDialog({ title: processingUx(locale, 'remove'), message: processingUx(locale, 'removeBody'), icon: 'trash', actions: [
      { id: 'cancel', label: ux(locale, 'cancel'), quiet: true }, { id: 'delete', label: ux(locale, 'confirm'), destructive: true },
    ] });
    if (answer !== 'delete') return;
    await safely(async () => {
      if ('documentId' in entry) {
        if (isRunning(entry.status) || entry.workerStopping) {
          await PdfTranslator.pauseJob(entry.id);
          const deadline = Date.now() + 60000;
          for (;;) {
            const state = await PdfTranslator.getActivitySnapshot();
            const job = state.jobs.find(value => value.id === entry.id);
            if (job && !job.workerStopping && !isRunning(job.status)) break;
            if (Date.now() >= deadline) throw new Error(processingUx(locale, 'removing'));
            await new Promise<void>(resolve => setTimeout(resolve, 250));
          }
        }
        await PdfTranslator.deleteJob(entry.id);
        setSelectedJobId(current => current === entry.id ? null : current);
      } else await PdfTranslator.deleteDocument(entry.id);
    });
  };
  const clearModels = async () => {
    const result = await showDialog({ title: ux(locale, 'clearModels'), message: ux(locale, 'deleteModelInfo'), icon: 'trash', actions: [
      { id: 'cancel', label: ux(locale, 'cancel'), quiet: true }, { id: 'delete', label: ux(locale, 'confirm'), destructive: true },
    ] });
    if (result === 'delete') await safely(async () => {
      for (const model of snapshot!.models) await PdfTranslator.deleteModel(model.id);
    });
  };
  const openPrivacy = () => {
    void safely(async () => {
      try {
        await Linking.openURL(PRIVACY_URL);
      } catch {
        throw new Error(ux(locale, 'privacyOpenError'));
      }
    });
  };
  const startTranslation = () => {
    if (!selectedDocument || !selectedModel) return;
    void safely(
      async () => {
        const online = selectedModel.id === 'mymemory';
        if (online) {
          const answer = await showDialog({ title: ux(locale, 'onlineConsentTitle'), message: ux(locale, 'onlineConsentBody'), icon: 'globe', actions: [{ id: 'cancel', label: ux(locale, 'cancel'), quiet: true }, { id: 'agree', label: ux(locale, 'onlineContinue') }] });
          if (answer !== 'agree') return null;
        }
        await explainNotifications(false);
        return PdfTranslator.startJob({
          engine: online ? { kind: 'online', id: 'mymemory' } : { kind: 'local', id: selectedModel.id },
          onlineConsent: online,
          documentId: selectedDocument.id,
          sourceLanguage,
          targetLanguage,
          modelId: selectedModel.id,
          useGlossary: useGlossary && selectedModel.supportsGlossary !== false,
        });
      },
      job => {
        if (!job) return;
        setSelectedJobId(job.id);
        setSelectedPage(0);
        go('job');
      },
    );
  };
  const selectModel = (model: ModelSnapshot) =>
    safely(
      () => PdfTranslator.setSettings(model.id === 'mymemory' ? { selectedEngine: { kind: 'online', id: 'mymemory' } } : { selectedModelId: model.id, selectedEngine: { kind: 'local', id: model.id } }),
      () => back(),
    );
  const setTranslationPair = (
    source: TranslationLanguage,
    target: TranslationLanguage,
  ) =>
    safely(
      () =>
        PdfTranslator.setSettings({
          sourceLanguage: source,
          targetLanguage: target,
        }),
      preferences => {
        setSnapshot(current => {
                  const existing = current!;
                  return {
                ...existing,
                preferences: { ...existing.preferences, ...preferences },
              };
                });
      },
    );
  const persistOnboarding = async (value: OnboardingState) => {
    const saved = await PdfTranslator.setOnboarding(value);
    setOnboardingOverride(saved);
  };

  if (!bootComplete) return frame(<SplashScreen onFirstFrame={markSplashShown} />);

  if (!snapshot)
    return frame(
      <Screen colors={colors} direction={direction}>
        <StatusBar
          barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'}
        />
        <View
          style={{ flex: 1, justifyContent: 'center', padding: 24, gap: 18 }}
        >
          <Header
            title={t('errorTitle')}
            colors={colors}
            direction={direction}
          />
          <Notice
            text={bootstrapError || t('unknownError')}
            colors={colors}
            retryLabel={t('retry')}
            retry={() => {
              void refresh().catch(error => setBootstrapError(error.message));
            }}
          />
        </View>
      </Screen>
    );

  if (showIntro)
    return frame(
      <>
        <StatusBar
          barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'}
        />
        <OnboardingScreen
          direction={direction}
          colors={colors}
          t={t}
          replay={replayIntro}
          onExit={() => {
            setReplayIntro(false);
            setRoute('settings');
          }}
          initialState={replayIntro ? BASE_ONBOARDING : onboarding}
          persist={persistOnboarding}
          onComplete={() => {
            setOnboardingOverride({ version: ONBOARDING_VERSION, page: 2, done: true });
            setRoute('home', 'clear');
          }}
          ios={Platform.OS === 'ios'}
        />
      </>
    );

  const screenHeader = (title: string, subtitle?: React.ReactNode) => (
    <Header
      direction={direction}
      title={title}
      subtitle={subtitle}
      colors={colors}
      onBack={back}
      backLabel={t('back')}
    />
  );

  const base = { colors, t, locale, busy };
  const renderHome = () => (
    <LibraryScreen
      {...base}
      snapshot={snapshot}
      model={selectedModel}
      folder={libraryFolder}
      onFolderChange={setLibraryFolder}
      onSettings={() => go('settings')}
      onDeleteJob={job => void deleteLibraryEntry(job)}
      onDeleteDocument={document => void deleteLibraryEntry(document)}
      onTranslate={() => void pickPdf()}
      onModel={() => go('model')}
      onJob={job => {
        setSelectedJobId(job.id);
        setSelectedPage(Math.max(0, job.currentPage ?? 0));
        go(
          job.status === 'completed' || job.status === 'partial'
            ? 'preview'
            : 'job',
        );
      }}
      onDocument={document => {
        setSelectedDocument(document);
        go('prepare');
      }}

        onSetColor={async (job, color) => { const changed = await PdfTranslator.setJobColor(job.id, color); await refresh(); return changed; }}
        onSort={value => void safely(() => PdfTranslator.setSettings({ librarySort: value }))}
      />
  );
  const renderModel = () => (
    <>
      {screenHeader(t('useModel'))}
      <ModelScreen
        {...base}
        snapshot={snapshot}
        selected={selectedModel}
        onSelect={model => void selectModel(model)}
        onDownload={model => void startDownload(model)}
        onPause={model => void pauseDownload(model)}
        onDelete={deleteModel}
      />
    </>
  );
  const renderPrepare = () => (
    <>
      {screenHeader(ux(locale, 'translationOptions'))}
      <TranslationOptionsScreen
        {...base}
        document={selectedDocument}
        model={selectedModel}
        source={sourceLanguage}
        target={targetLanguage}
        terms={pairGlossary.length}
        useGlossary={useGlossary}
        onUseGlossary={setUseGlossary}
        onPair={(source, target) => void setTranslationPair(source, target)}
        onDictionary={() => go('dictionary')}
        onPreview={() => go('sourcePreview')}
        onModel={() => go('model')}
        onStart={startTranslation}
      />
    </>
  );

  const renderJob = () => {
    const job =
      snapshot.jobs.find(item => item.id === selectedJobId) ?? selectedJob;
    if (!job)
      return (
        <>
          {screenHeader(t('progress'))}
          <View style={{ padding: 20 }}>
            <Notice text={t('noDocuments')} colors={colors} />
          </View>
        </>
      );

    const done = job.status === 'completed' || job.status === 'partial';
    const etaRange =
      job.etaRangeSeconds &&
      Number.isFinite(job.etaRangeSeconds.min) &&
      Number.isFinite(job.etaRangeSeconds.max) &&
      job.etaRangeSeconds.min >= 0 &&
      job.etaRangeSeconds.max >= job.etaRangeSeconds.min
        ? job.etaRangeSeconds
        : null;
    const eta =
      job.etaSeconds != null &&
      Number.isFinite(job.etaSeconds) &&
      job.etaSeconds >= 0
        ? job.etaSeconds
        : null;
    const pinnedModel = snapshot.models.find(model => model.id === job.modelId);
    const missingPinnedModel =
      job.engine?.kind !== 'online' &&
      job.completedPages < job.totalPages &&
      pinnedModel?.translationEnabled !== false &&
      pinnedModel?.status !== 'ready'
        ? pinnedModel
        : undefined;
    const redownloading =
      missingPinnedModel?.status === 'downloading' ||
      missingPinnedModel?.status === 'verifying';
    const controls = (
      <View style={{ flexDirection: 'row', gap: 10, flexWrap: 'wrap' }}>
        {job.status === 'running' || job.status === 'queued' ? (
          <Button
            label={job.workerStopping ? processingUx(locale, 'pausing') : t('pause')}
            onPress={() => void safely(() => PdfTranslator.pauseJob(job.id))}
            colors={colors}
            variant="secondary"
            disabled={busy || job.workerStopping}
          />
        ) : job.status === 'paused' || job.status === 'failed' ? (
          <Button
            label={
              missingPinnedModel
                ? t(redownloading ? 'useModel' : 'download')
                : t(job.status === 'failed' ? 'retry' : 'resume')
            }
            onPress={() => {
              if (missingPinnedModel) {
                go('model');
                if (!redownloading) void startDownload(missingPinnedModel);
              } else {
                void safely(() => PdfTranslator.resumeJob(job.id));
              }
            }}
            colors={colors}
            disabled={busy || job.workerStopping}
          />
        ) : null}
        {!done && job.status !== 'canceled' && job.status !== 'failed' ? (
          <Button
            label={t('cancel')}
            onPress={() => void safely(() => PdfTranslator.cancelJob(job.id))}
            colors={colors}
            variant="quiet"
            disabled={busy}
          />
        ) : null}
        {done ? (
          <Button
            label={t('previewTitle')}
            onPress={() => go('preview')}
            colors={colors}
          />
        ) : null}
        {done ? (
          <Button
            label={t('saveCopy')}
            onPress={() =>
              void safely(
                () => PdfTranslator.exportJob(job.id),
                value => setExported(value ?? null),
              )
            }
            colors={colors}
            variant="secondary"
            disabled={busy}
          />
        ) : null}
        {exported ? (
          <Button
            label={t('share')}
            onPress={() =>
              void safely(() => PdfTranslator.shareExport(exported.id))
            }
            colors={colors}
            variant="secondary"
            disabled={busy}
          />
        ) : null}
      </View>
    );
    return (
      <>
        {screenHeader(t('progress'), job.documentName)}
        <ScrollView testID="translation-progress-root" scrollEventThrottle={64} contentContainerStyle={{ padding: 20, gap: 16 }}>
          <Card colors={colors} style={{ padding: 22 }}>
            <Row style={{ justifyContent: 'space-between' }}>
              <Label
                colors={colors}
                tone={
                  job.status === 'failed'
                    ? 'accent'
                    : done
                    ? 'success'
                    : 'muted'
                }
              >
                {t(statusKeys[job.status])}
              </Label>
              <Text style={{ color: colors.muted, fontSize: 12 }}>
                {pairLabel(job.sourceLanguage, job.targetLanguage)}
              </Text>
            </Row>
            <Text
              style={{ fontSize: 21, color: colors.text, fontWeight: '800' }}
            >
              {job.stage === 'loadingModel' ? processingUx(locale, 'loadingModel') : job.engine?.kind === 'online' && job.stage === 'translating' ? ux(locale, 'onlineProcessing') : job.documentFormat === 'docx' && job.stage === 'exporting' ? ux(locale, 'exportDocx') : t(stageKeys[job.stage])}
            </Text>
            <ProcessingMetrics job={job} active={appState === 'active' && route === 'job'} colors={colors} locale={locale} />
            <TranslationActivity job={job} colors={colors} t={t} locale={locale} active={appState === 'active'} />
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ color: colors.text, fontWeight: '700' }}>
                {job.progressUnit === 'segment' ? ux(locale, 'segments') : t('pageProgress')}
              </Text>
              <Text style={{ color: colors.muted }}>
                {job.completedPages} / {job.totalPages}
              </Text>
            </Row>
            {job.currentPage != null || job.status === 'queued' ? (
              <Text style={{ color: colors.muted, fontSize: 12 }}>
                {job.currentPage != null
                  ? `${job.progressUnit === 'segment' ? ux(locale, 'segments') : t('page')} ${job.currentPage + 1}`
                  : t('stageQueued')}
              </Text>
            ) : null}
            {job.completedBlocks !== undefined ||
            job.discoveredBlocks !== undefined ? (
              <Text style={{ color: colors.muted, fontSize: 12 }}>
                {t('blocksCompleted')}: {job.completedBlocks ?? 0}
                {job.totalBlocks != null
                  ? ` / ${job.totalBlocks}`
                  : job.discoveredBlocks != null
                  ? ` · ${t('blocksDiscovered')}: ${job.discoveredBlocks}`
                  : ''}
              </Text>
            ) : null}
            {etaRange ? (
              <Text style={{ color: colors.muted, fontSize: 12 }}>
                {t('eta')}: ~{formatDuration(etaRange.min, key => t(key))}–
                {formatDuration(etaRange.max, key => t(key))}
              </Text>
            ) : eta != null ? (
              <Text style={{ color: colors.muted, fontSize: 12 }}>
                {t('eta')}: ~{formatDuration(eta, key => t(key))}
              </Text>
            ) : null}
            {job.error && job.status !== 'paused' ? <Notice text={job.error} colors={colors} /> : null}
            {controls}
            {exported ? (
              <Text style={{ color: colors.success, fontWeight: '700' }}>
                {t('saved')}: {exported.displayName}
              </Text>
            ) : null}
          </Card>
          <Text style={{ color: colors.muted, lineHeight: 20 }}>
            {Platform.OS === 'ios'
              ? t('iosBackground')
              : t('androidBackground')}
          </Text>
          {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
        </ScrollView>
      </>
    );
  };

  const renderPreview = () => selectedJob?.documentFormat === 'docx' ? <>{screenHeader(selectedJob.documentName)}<WordResultScreen job={selectedJob} colors={colors} t={t} locale={locale} /></> : (
    <>
      {screenHeader(
        t('previewTitle'),
        details?.job.id === selectedJobId
          ? <LanguagePair source={details.job.sourceLanguage} target={details.job.targetLanguage} colors={colors} direction={direction} />
          : undefined,
      )}
      <View
        style={{
          flexDirection: 'row',
          paddingHorizontal: 18,
          paddingTop: 8,
          gap: 10,
        }}
      >
        <Button
          label={ux(locale, 'original')}
          colors={colors}
          variant={previewOriginal ? 'primary' : 'secondary'}
          onPress={() => setPreviewOriginal(true)}
        />
        <Button
          label={ux(locale, 'target')}
          colors={colors}
          variant={!previewOriginal ? 'primary' : 'secondary'}
          onPress={() => setPreviewOriginal(false)}
        />
      </View>
      <ReviewLayout wide={wide}>
        <ReviewColumn wide={wide} weight={1.15} gap={14}>
          {renderedUri ? (
            <Image
              accessibilityLabel={`${t('page')} ${previewOriginal && details?.pdfLayout && details.selectedPage ? details.selectedPage.index + 1 : selectedPage + 1}`}
              source={{ uri: renderedUri }}
              resizeMode="contain"
              style={{
                width: '100%',
                height: wide ? 620 : 430,
                borderRadius: 14,
                backgroundColor: colors.surface,
                borderWidth: 1,
                borderColor: colors.border,
              }}
            />
          ) : (
            <Card colors={colors}>
              <Text style={{ color: colors.text, fontWeight: '800' }}>
                {t('loading')}
              </Text>
            </Card>
          )}
          <Row style={{ justifyContent: 'space-between' }}>
            <Button
              label={t('previous')}
              onPress={() => setSelectedPage(Math.max(0, selectedPage - 1))}
              colors={colors}
              variant="secondary"
              disabled={selectedPage === 0}
            />
            <Text style={{ color: colors.text, fontWeight: '700' }}>
              {t('page')} {previewOriginal && details?.pdfLayout && details.selectedPage ? details.selectedPage.index + 1 : selectedPage + 1}
            </Text>
            <Button
              label={t('next')}
              onPress={() => setSelectedPage(selectedPage + 1)}
              colors={colors}
              variant="secondary"
              disabled={Boolean(
                details && selectedPage >= (details.pdfLayout?.outputPageCount ?? details.job.totalPages) - 1,
              )}
            />
          </Row>
        </ReviewColumn>
        <ReviewColumn wide={wide} gap={13}>
          {details?.selectedPage?.error ? (
            <Notice text={details.selectedPage.error} colors={colors} />
          ) : null}
          <Card colors={colors}>
            <Text
              style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}
            >
              {t('issues')}
            </Text>
            {details?.selectedPage?.blocks.some(
              block =>
                block.status === 'partial' ||
                block.status === 'failed' ||
                block.status === 'skipped',
            ) ? (
              details.selectedPage.blocks
                .filter(
                  block =>
                    block.status === 'partial' ||
                    block.status === 'failed' ||
                    block.status === 'skipped',
                )
                .map(block => (
                  <Text
                    key={block.id}
                    style={{ color: colors.warning, lineHeight: 19 }}
                  >
                    • {block.error ?? block.sourceText}
                  </Text>
                ))
            ) : (
              <Text
                style={{
                  color:
                    details?.selectedPage?.status === 'partial'
                      ? colors.warning
                      : colors.muted,
                }}
              >
                {t(
                  details?.selectedPage?.status === 'partial'
                    ? 'issues'
                    : 'noIssues',
                )}
              </Text>
            )}
          </Card>
          <Button
            label={t('openResult')}
            onPress={() =>
              selectedJob &&
              void safely(() => PdfTranslator.previewJob(selectedJob.id))
            }
            colors={colors}
            variant="secondary"
            disabled={busy || !selectedJob}
          />
          <Button
            label={t('openOriginal')}
            onPress={() =>
              selectedJob &&
              void safely(() => PdfTranslator.openPdf(selectedJob.documentId))
            }
            colors={colors}
            variant="secondary"
            disabled={busy || !selectedJob}
          />
          <Button
            label={t('saveCopy')}
            onPress={() =>
              selectedJob &&
              void safely(
                () => PdfTranslator.exportJob(selectedJob.id),
                value => setExported(value ?? null),
              )
            }
            colors={colors}
            disabled={busy || !selectedJob}
          />

          {exported ? (
            <Button
              label={t('share')}
              onPress={() =>
                void safely(() => PdfTranslator.shareExport(exported.id))
              }
              colors={colors}
              variant="secondary"
              disabled={busy}
            />
          ) : null}
          {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
        </ReviewColumn>
      </ReviewLayout>
    </>
  );

  const renderSettings = () => (
    <>
      {screenHeader(t('settings'))}
      <SettingsScreen
        {...base}
        snapshot={snapshot}
        selectedModel={selectedModel}
        onRoute={go}
        onTheme={theme => void safely(() => PdfTranslator.setSettings({ theme }))}
        onPrivacy={openPrivacy}
        onReplay={() => setReplayIntro(true)}
      />
    </>
  );
  const renderExtra = () =>
    route === 'language' ? (
      <>
        {screenHeader(t('language'))}
        <LanguageChoices
          {...base}
          selected={savedLanguage}
          onSelect={value =>
            void safely(
              () => PdfTranslator.setSettings({ language: value }),
              preferences => {
                setSnapshot(current => {
                  const existing = current!;
                  return {
                        ...existing,
                        preferences: { ...existing.preferences, ...preferences },
                      };
                });
                back();
              },
            )
          }
        />
      </>
    ) : route === 'storage' ? (
      <>
        {screenHeader(ux(locale, 'storage'))}
        <StorageScreen
          {...base}
          snapshot={snapshot}
          onDelete={deleteModel}
          onClear={clearModels}
        />
      </>
    ) : route === 'dictionary' ? (
      <>
        {screenHeader(ux(locale, 'glossary'))}
        <DictionaryScreen
          {...base}
          entries={snapshot.glossary}
          source={sourceLanguage}
          target={targetLanguage}
          onPair={(source, target) => void setTranslationPair(source, target)}
          onSave={(source, target, id) =>
            safely(
              () =>
                PdfTranslator.saveGlossaryEntry({
                  id: id ?? `${Date.now()}`,
                  source,
                  target,
                  sourceLanguage,
                  targetLanguage,
                }),
              entry => {
                setSnapshot(current => {
                  const existing = current!;
                  return {
                        ...existing,
                        glossary: [
                          ...existing.glossary.filter(
                            item => item.id !== entry.id,
                          ),
                          entry,
                        ],
                      };
                });
              },
            )
          }
          onDelete={id =>
            void safely(() => PdfTranslator.deleteGlossaryEntry(id))
          }
        />
      </>
    ) : route === 'sourcePreview' ? (
      <>
        {screenHeader(ux(locale, selectedDocument?.format === 'docx' ? 'original' : 'sourcePdf'))}
        <SourcePreviewScreen {...base} document={selectedDocument} />
      </>
    ) : (
      <>
        {screenHeader(t('licenses'))}
        <LicensesScreen {...base} />
      </>
    );

  return frame(
    <DialogContext.Provider value={showDialog}>
    <View style={{ flex: 1 }} accessibilityElementsHidden={dialogOpen} importantForAccessibility={dialogOpen ? 'no-hide-descendants' : 'auto'}>
    <Screen colors={colors} direction={direction}>
      <StatusBar
        barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'}
      />
      <View
        style={{
          flex: 1,
          paddingTop: insets.top,
          paddingLeft: insets.left,
          paddingRight: insets.right,
          paddingBottom:
            insets.bottom,
        }}
      >
        {route === 'home' ? (
          <View testID="library-tab-root" style={{ flex: 1 }}>
            {renderHome()}
          </View>
        ) : null}
        {route === 'settings' ? (
          <View testID="settings-tab-root" style={{ flex: 1 }}>
            {renderSettings()}
          </View>
        ) : null}
        {route !== 'home' && route !== 'settings' ? (
          <Animated.View
            key={route}
            entering={reducedMotion ? undefined : FadeIn.duration(130)}
            style={{ flex: 1 }}
          >
            {route === 'model'
              ? renderModel()
              : route === 'prepare'
              ? renderPrepare()
              : route === 'job'
              ? renderJob()
              : route === 'preview'
              ? renderPreview()
              : renderExtra()}
          </Animated.View>
        ) : null}
      </View>
      {inlineError && route !== 'job' && route !== 'preview' ? (
        <View style={{ paddingHorizontal: 24, paddingVertical: 8 }}>
          <Notice text={inlineError} colors={colors} />
        </View>
      ) : null}
      {bootstrapError ? (
        <View style={{ paddingHorizontal: 24, paddingVertical: 8 }}>
          <Notice
            text={bootstrapError}
            colors={colors}
            retryLabel={t('retry')}
            retry={() => void refresh().catch(() => undefined)}
          />
        </View>
      ) : null}

    </Screen>
    </View>
    {dialog}
    </DialogContext.Provider>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <AppContent />
    </SafeAreaProvider>
  );
}
