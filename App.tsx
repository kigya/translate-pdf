import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Alert,
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
import Animated, { FadeIn, useReducedMotion } from 'react-native-reanimated';
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
import { resolveTheme, palettes } from './src/ui/theme';
import {
  Button,
  Card,
  Header,
  Label,
  Notice,
  ProgressBar,
  Row,
  Screen,
} from './src/ui/components';
import {
  ONBOARDING_VERSION,
  OnboardingScreen,
} from './src/onboarding/OnboardingScreen';

import { AnimatedTabBar } from './src/ui/Motion';
import { SplashScreen } from './src/ui/SplashScreen';
import { Sheet, SettingsRow } from './src/ui/Controls';
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
import { PRIVACY_URL } from './src/domain/presentation';

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

function AppContent() {
  const scheme = useColorScheme();
  const reducedMotion = useReducedMotion() ?? false;
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const [snapshot, setSnapshot] = useState<BootstrapSnapshot | null>(null);
  const [bootstrapError, setBootstrapError] = useState('');
  const [route, setRoute] = useState<Route>('home');
  const routeStack = useRef<Route[]>([]);
  const [selectedDocument, setSelectedDocument] =
    useState<DocumentSnapshot | null>(null);
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
  const [startupReady, setStartupReady] = useState(false);
  const [splashShownAt, setSplashShownAt] = useState<number | null>(null);
  const markSplashShown = useCallback(
    () => setSplashShownAt(current => current ?? startupNow()),
    [],
  );
  const [replayIntro, setReplayIntro] = useState(false);
  const [appearanceOpen, setAppearanceOpen] = useState(false);
  const [useGlossary, setUseGlossary] = useState(true);
  const [bootComplete, setBootComplete] = useState(false);
  const [onboardingOverride, setOnboardingOverride] =
    useState<OnboardingState | null>(null);

  const preferenceTheme = snapshot?.preferences.theme ?? 'system';
  const activeTheme = useMemo(
    () => resolveTheme(preferenceTheme, scheme as ColorSchemeName),
    [preferenceTheme, scheme],
  );
  const colors = palettes[activeTheme];
  const savedLanguage = snapshot?.preferences.language ?? 'system';
  const locale: Locale =
    savedLanguage === 'system'
      ? localeSystem(snapshot?.device.systemLocale)
      : normalizeLocale(savedLanguage);
  const t = useMemo(() => createTranslator(locale), [locale]);
  const currentTranslator = useRef(t);
  currentTranslator.current = t;
  const direction = localeDirection(locale);
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
  const recommendedModel =
    snapshot?.models.find(
      model => model.id === snapshot.device.recommendedModelId,
    ) ?? null;
  const selectedModel =
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

  useEffect(() => {
    if (appState !== 'active' || !snapshot) return;
    const activeWork =
      snapshot.jobs.some(job => isRunning(job.status)) ||
      snapshot.models.some(
        model => model.status === 'downloading' || model.status === 'verifying',
      );
    if (!activeWork) return;
    const timer = setInterval(() => {
      if (!operationInFlight.current) void refresh().catch(() => undefined);
    }, 1000);
    return () => clearInterval(timer);
  }, [appState, refresh, snapshot]);

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
    if (!selectedJobId || route !== 'preview') return;
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
                value.selectedPage.index,
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
  }, [route, selectedJobId, selectedPage, previewOriginal, t, width]);

  const go = (next: Route) => {
    setInlineError('');
    routeStack.current.push(route);
    setRoute(next);
  };
  const back = () => {
    setInlineError('');
    setRoute(routeStack.current.pop() ?? 'home');
  };
  useEffect(() => {
    if (showIntro || route === 'home') return;
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        setInlineError('');
        setRoute(routeStack.current.pop() ?? 'home');
        return true;
      },
    );
    return () => subscription.remove();
  }, [showIntro, route]);
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
      () => PdfTranslator.pickPdf(),
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
    const allow = await new Promise<boolean>(resolve =>
      Alert.alert(
        ux(
          locale,
          download ? 'notificationTitle' : 'notificationTranslationTitle',
        ),
        ux(locale, 'notificationBody'),
        [
          {
            text: ux(locale, 'notNow'),
            style: 'cancel',
            onPress: () => resolve(false),
          },
          {
            text: ux(locale, 'allowNotifications'),
            onPress: () => resolve(true),
          },
        ],
        { cancelable: true, onDismiss: () => resolve(false) },
      ),
    );
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
        const accepted = await new Promise<boolean>(resolve =>
          Alert.alert(
            ux(locale, 'modelDetails'),
            `${t('gemmaTermsNotice')}\n${model.licenseUrl}`,
            [
              {
                text: ux(locale, 'cancel'),
                style: 'cancel',
                onPress: () => resolve(false),
              },
              {
                text: t('licenses'),
                onPress: () => {
                  void Linking.openURL(model.licenseUrl!).catch(
                    () => undefined,
                  );
                  resolve(false);
                },
              },
              { text: t('acceptAndDownload'), onPress: () => resolve(true) },
            ],
            { cancelable: true, onDismiss: () => resolve(false) },
          ),
        );
        if (!accepted) return;
      }
      await explainNotifications(true);
      return PdfTranslator.startModelDownload(model.id);
    });
  const pauseDownload = (model: ModelSnapshot) =>
    safely(() => PdfTranslator.pauseModelDownload(model.id));
  const deleteModel = (model: ModelSnapshot) =>
    Alert.alert(ux(locale, 'removeModel'), ux(locale, 'deleteModelInfo'), [
      { text: ux(locale, 'cancel'), style: 'cancel' },
      {
        text: ux(locale, 'confirm'),
        style: 'destructive',
        onPress: () => {
          void safely(() => PdfTranslator.deleteModel(model.id));
        },
      },
    ]);
  const clearModels = () =>
    Alert.alert(ux(locale, 'clearModels'), ux(locale, 'deleteModelInfo'), [
      { text: ux(locale, 'cancel'), style: 'cancel' },
      {
        text: ux(locale, 'confirm'),
        style: 'destructive',
        onPress: () => {
          void safely(async () => {
            for (const model of snapshot?.models ?? [])
              await PdfTranslator.deleteModel(model.id);
          });
        },
      },
    ]);
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
        await explainNotifications(false);
        return PdfTranslator.startJob({
          documentId: selectedDocument.id,
          sourceLanguage,
          targetLanguage,
          modelId: selectedModel.id,
          useGlossary: useGlossary && selectedModel.supportsGlossary !== false,
        });
      },
      job => {
        setSelectedJobId(job.id);
        setSelectedPage(0);
        go('job');
      },
    );
  };
  const selectModel = (model: ModelSnapshot) =>
    safely(
      () => PdfTranslator.setSettings({ selectedModelId: model.id }),
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
        setSnapshot(current =>
          current
            ? {
                ...current,
                preferences: { ...current.preferences, ...preferences },
              }
            : current,
        );
      },
    );
  const persistOnboarding = async (value: OnboardingState) => {
    const saved = await PdfTranslator.setOnboarding(value);
    setOnboardingOverride(saved);
  };

  if (!bootComplete) return <SplashScreen onFirstFrame={markSplashShown} />;

  if (!snapshot)
    return (
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
    return (
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
          persist={replayIntro ? async () => {} : persistOnboarding}
          onComplete={() => {
            if (replayIntro) {
              setReplayIntro(false);
              setRoute('settings');
            } else {
              setOnboardingOverride({
                version: ONBOARDING_VERSION,
                page: 3,
                done: true,
              });
              setRoute('home');
              routeStack.current = [];
            }
          }}
          ios={Platform.OS === 'ios'}
        />
      </>
    );

  const screenHeader = (title: string, subtitle?: string) => (
    <Header
      direction={direction}
      title={title}
      subtitle={subtitle}
      colors={colors}
      onBack={route === 'home' ? undefined : back}
      backLabel={t('back')}
    />
  );
  const tabBar = () => (
    <AnimatedTabBar
      direction={direction}
      colors={colors}
      labels={{ library: t('home'), settings: t('settings') }}
      bottomInset={insets.bottom}
      active={route === 'settings' ? 'settings' : 'library'}
      onChange={tab => {
        routeStack.current = [];
        setRoute(tab === 'library' ? 'home' : 'settings');
        setInlineError('');
      }}
    />
  );
  const base = { colors, t, locale, busy };
  const renderHome = () => (
    <LibraryScreen
      {...base}
      snapshot={snapshot}
      model={selectedModel}
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
    const rawRatio =
      job.totalPages > 0 ? job.completedPages / job.totalPages : 0;
    const ratio =
      job.status === 'completed' || job.status === 'partial'
        ? rawRatio
        : Math.min(0.99, rawRatio);
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
    const controls = (
      <View style={{ flexDirection: 'row', gap: 10, flexWrap: 'wrap' }}>
        {job.status === 'running' || job.status === 'queued' ? (
          <Button
            label={t('pause')}
            onPress={() => void safely(() => PdfTranslator.pauseJob(job.id))}
            colors={colors}
            variant="secondary"
            disabled={busy}
          />
        ) : job.status === 'paused' || job.status === 'failed' ? (
          <Button
            label={t(job.status === 'failed' ? 'retry' : 'resume')}
            onPress={() => void safely(() => PdfTranslator.resumeJob(job.id))}
            colors={colors}
            disabled={busy}
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
        <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }}>
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
              {t(stageKeys[job.stage])}
            </Text>
            <ProgressBar colors={colors} value={ratio} label={t('progress')} />
            <Row style={{ justifyContent: 'space-between' }}>
              <Text style={{ color: colors.text, fontWeight: '700' }}>
                {t('pageProgress')}
              </Text>
              <Text style={{ color: colors.muted }}>
                {job.completedPages} / {job.totalPages}
              </Text>
            </Row>
            {job.currentPage != null || job.status === 'queued' ? (
              <Text style={{ color: colors.muted, fontSize: 12 }}>
                {job.currentPage != null
                  ? `${t('page')} ${job.currentPage + 1}`
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
            {job.error ? <Notice text={job.error} colors={colors} /> : null}
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

  const renderPreview = () => (
    <>
      {screenHeader(
        t('previewTitle'),
        details?.job.id === selectedJobId
          ? pairLabel(details.job.sourceLanguage, details.job.targetLanguage)
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
          label={t('original')}
          colors={colors}
          variant={previewOriginal ? 'primary' : 'secondary'}
          onPress={() => setPreviewOriginal(true)}
        />
        <Button
          label={t('translated')}
          colors={colors}
          variant={!previewOriginal ? 'primary' : 'secondary'}
          onPress={() => setPreviewOriginal(false)}
        />
      </View>
      <View style={{ flex: 1, flexDirection: wide ? 'row' : 'column' }}>
        <ScrollView
          style={{ flex: wide ? 1.15 : undefined }}
          contentContainerStyle={{ padding: 18, gap: 14 }}
        >
          {renderedUri ? (
            <Image
              accessibilityLabel={`${t('page')} ${selectedPage + 1}`}
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
              {t('page')} {selectedPage + 1}
            </Text>
            <Button
              label={t('next')}
              onPress={() => setSelectedPage(selectedPage + 1)}
              colors={colors}
              variant="secondary"
              disabled={Boolean(
                details && selectedPage >= details.job.totalPages - 1,
              )}
            />
          </Row>
        </ScrollView>
        <ScrollView
          style={{ flex: 1 }}
          contentContainerStyle={{ padding: 18, gap: 13 }}
        >
          {details?.selectedPage?.error ? (
            <Notice text={details.selectedPage.error} colors={colors} />
          ) : null}
          <Card colors={colors}>
            <Text
              style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}
            >
              {t('original')}
            </Text>
            {details?.selectedPage?.blocks.length ? (
              details.selectedPage.blocks.map(block => (
                <View
                  key={block.id}
                  style={{
                    borderTopWidth: 1,
                    borderTopColor: colors.border,
                    paddingTop: 9,
                    gap: 6,
                  }}
                >
                  <Text style={{ color: colors.muted, lineHeight: 19 }}>
                    {block.sourceText}
                  </Text>
                </View>
              ))
            ) : (
              <Text style={{ color: colors.muted }}>{t('noDocuments')}</Text>
            )}
          </Card>
          <Card colors={colors}>
            <Text
              style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}
            >
              {t('translated')}
            </Text>
            {details?.selectedPage?.blocks.length ? (
              details.selectedPage.blocks.map(block => (
                <View
                  key={block.id}
                  style={{
                    borderTopWidth: 1,
                    borderTopColor: colors.border,
                    paddingTop: 9,
                    gap: 6,
                  }}
                >
                  <Text
                    style={{
                      color:
                        block.status === 'partial' || block.status === 'failed'
                          ? colors.warning
                          : colors.text,
                      lineHeight: 20,
                    }}
                  >
                    {block.translatedText ?? block.error ?? '—'}
                  </Text>
                  {block.status === 'partial' || block.status === 'failed' ? (
                    <Text style={{ color: colors.warning, fontSize: 12 }}>
                      {block.error ?? t('issues')}
                    </Text>
                  ) : null}
                </View>
              ))
            ) : (
              <Text style={{ color: colors.muted }}>{t('noDocuments')}</Text>
            )}
          </Card>
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
        </ScrollView>
      </View>
    </>
  );

  const renderSettings = () => (
    <>
      {screenHeader(t('settings'))}
      <SettingsScreen
        {...base}
        snapshot={snapshot}
        onRoute={go}
        onAppearance={() => setAppearanceOpen(true)}
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
                setSnapshot(current =>
                  current
                    ? {
                        ...current,
                        preferences: { ...current.preferences, ...preferences },
                      }
                    : current,
                );
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
                setSnapshot(current =>
                  current
                    ? {
                        ...current,
                        glossary: [
                          ...current.glossary.filter(
                            item => item.id !== entry.id,
                          ),
                          entry,
                        ],
                      }
                    : current,
                );
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
        {screenHeader(ux(locale, 'sourcePdf'))}
        <SourcePreviewScreen {...base} document={selectedDocument} />
      </>
    ) : (
      <>
        {screenHeader(t('licenses'))}
        <LicensesScreen {...base} />
      </>
    );

  return (
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
            route === 'home' || route === 'settings' ? 0 : insets.bottom,
        }}
      >
        <View
          testID="library-tab-root"
          style={{ flex: 1, display: route === 'home' ? 'flex' : 'none' }}
          accessibilityElementsHidden={route !== 'home'}
          importantForAccessibility={
            route === 'home' ? 'auto' : 'no-hide-descendants'
          }
        >
          {renderHome()}
        </View>
        <View
          testID="settings-tab-root"
          style={{ flex: 1, display: route === 'settings' ? 'flex' : 'none' }}
          accessibilityElementsHidden={route !== 'settings'}
          importantForAccessibility={
            route === 'settings' ? 'auto' : 'no-hide-descendants'
          }
        >
          {renderSettings()}
        </View>
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
      {route === 'home' || route === 'settings' ? tabBar() : null}
      <Sheet
        visible={appearanceOpen}
        title={t('appearance')}
        onClose={() => setAppearanceOpen(false)}
        colors={colors}
        closeLabel={t('back')}
      >
        {(['system', 'light', 'dark'] as const).map(value => (
          <SettingsRow
            key={value}
            icon="moon"
            label={`${t(value)}${value === preferenceTheme ? ' ✓' : ''}`}
            onPress={() =>
              void safely(
                () => PdfTranslator.setSettings({ theme: value }),
                () => setAppearanceOpen(false),
              )
            }
            colors={colors}
          />
        ))}
      </Sheet>
    </Screen>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <AppContent />
    </SafeAreaProvider>
  );
}
