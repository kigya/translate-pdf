import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, AppStateStatus, BackHandler, ColorSchemeName, Image, Linking, Platform, Pressable, ScrollView, StatusBar, Text, View, useColorScheme, useWindowDimensions } from 'react-native';
import Animated, { FadeIn, useReducedMotion } from 'react-native-reanimated';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { BootstrapSnapshot, DocumentSnapshot, ExportedPdf, JobDetails, JobSnapshot, ModelSnapshot, OnboardingState, PdfTranslator } from './src/native/PdfTranslator';
import { localeOptions, normalizeLocale, createTranslator, localeDirection, MessageKey, Locale } from './src/i18n/messages';
import { resolveTheme, palettes } from './src/ui/theme';
import { Button, Card, Field, Header, Label, Notice, ProgressBar, Row, Screen, SectionTitle } from './src/ui/components';
import { ONBOARDING_VERSION, OnboardingScreen } from './src/onboarding/OnboardingScreen';
import { modelCanBeInstalled, modelCanTranslate, modelResourceReason, modelHasMemoryWarning } from './src/domain/modelEligibility';

import { resolveTranslationPair, translationLanguageName, translationLanguages, TranslationLanguage } from './src/domain/translationLanguages';

type Route = 'home' | 'model' | 'prepare' | 'job' | 'preview' | 'settings' | 'privacy' | 'licenses';
const BASE_ONBOARDING: OnboardingState = { version: ONBOARDING_VERSION, page: 0, done: false };
const formatBytes = (bytes?: number | null) => bytes == null || !Number.isFinite(bytes) || bytes < 0 ? '—' : bytes === 0 ? '0 B' : bytes >= 1_000_000_000 ? `${(bytes / 1_000_000_000).toFixed(2)} GB` : bytes >= 1_000_000 ? `${Math.round(bytes / 1_000_000)} MB` : bytes >= 1_000 ? `${Math.max(1, Math.round(bytes / 1_000))} KB` : `${Math.round(bytes)} B`;
const stageKeys: Record<JobSnapshot['stage'], MessageKey> = { queued: 'stageQueued', copying: 'stageCopying', extracting: 'stageExtracting', ocr: 'stageOcr', translating: 'stageTranslating', layout: 'stageLayout', exporting: 'stageExporting', complete: 'stageComplete' };
const statusKeys: Record<JobSnapshot['status'], MessageKey> = { queued: 'running', running: 'running', paused: 'pausedStatus', completed: 'completed', partial: 'partial', failed: 'failed', canceled: 'canceledStatus' };
const modelStatusKeys: Record<ModelSnapshot['status'], MessageKey> = { not_downloaded: 'modelNotDownloaded', downloading: 'modelDownloading', paused: 'modelPaused', verifying: 'modelVerifying', ready: 'modelReady', error: 'modelError' };
const resourceReasonKeys = { available: 'modelResourceUnsupported', memory: 'modelResourceMemory', disk: 'modelResourceDisk', lowMemory: 'modelResourceLowMemory', unsupported: 'modelResourceUnsupported' } satisfies Record<NonNullable<ModelSnapshot['resourceReason']>, MessageKey>;
const isRunning = (status: JobSnapshot['status']) => status === 'running' || status === 'queued';
const formatDuration = (seconds: number, unit: (key: 'seconds' | 'minutes' | 'hours') => string) => seconds < 60 ? `${Math.ceil(seconds)} ${unit('seconds')}` : seconds < 3600 ? `${Math.ceil(seconds / 60)} ${unit('minutes')}` : `${Math.floor(seconds / 3600)} ${unit('hours')} ${Math.ceil((seconds % 3600) / 60)} ${unit('minutes')}`;
const localeSystem = (nativeLocale?: string) => normalizeLocale(nativeLocale ?? Intl?.DateTimeFormat?.().resolvedOptions?.().locale ?? 'en');

function AppContent() {
  const scheme = useColorScheme();
  const reducedMotion = useReducedMotion() ?? false;
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const [snapshot, setSnapshot] = useState<BootstrapSnapshot | null>(null);
  const [bootstrapError, setBootstrapError] = useState('');
  const [route, setRoute] = useState<Route>('home');
  const routeStack = useRef<Route[]>([]);
  const [selectedDocument, setSelectedDocument] = useState<DocumentSnapshot | null>(null);
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [details, setDetails] = useState<JobDetails | null>(null);
  const [selectedPage, setSelectedPage] = useState(0);
  const [renderedUri, setRenderedUri] = useState('');
  const [previewOriginal, setPreviewOriginal] = useState(false);
  const [exported, setExported] = useState<ExportedPdf | null>(null);
  const [inlineError, setInlineError] = useState('');
  const [busy, setBusy] = useState(false);
  const [appState, setAppState] = useState<AppStateStatus>((AppState.currentState ?? 'active') as AppStateStatus);
  const bootStart = useRef(Date.now());
  const [bootComplete, setBootComplete] = useState(false);
  const [onboardingOverride, setOnboardingOverride] = useState<OnboardingState | null>(null);
  const [glossarySource, setGlossarySource] = useState('');
  const [glossaryTarget, setGlossaryTarget] = useState('');
  const [editingGlossaryId, setEditingGlossaryId] = useState<string | null>(null);
  const [languagePicker, setLanguagePicker] = useState<'source' | 'target' | null>(null);

  const preferenceTheme = snapshot?.preferences.theme ?? 'system';
  const activeTheme = useMemo(() => resolveTheme(preferenceTheme, scheme as ColorSchemeName), [preferenceTheme, scheme]);
  const colors = palettes[activeTheme];
  const savedLanguage = snapshot?.preferences.language ?? 'system';
  const locale: Locale = savedLanguage === 'system' ? localeSystem(snapshot?.device.systemLocale) : normalizeLocale(savedLanguage);
  const t = useMemo(() => createTranslator(locale), [locale]);
  const direction = localeDirection(locale);
  const onboarding = onboardingOverride ?? snapshot?.onboarding ?? BASE_ONBOARDING;
  const showIntro = !onboarding.done;
  const wide = width >= 760;
  const selectedJob = snapshot?.jobs.find(job => job.id === selectedJobId) ?? snapshot?.jobs.find(job => job.status === 'running' || job.status === 'paused') ?? null;
  const recommendedModel = snapshot?.models.find(model => model.id === snapshot.device.recommendedModelId) ?? null;
  const selectedModel = snapshot?.models.find(model => model.id === snapshot.preferences.selectedModelId && model.translationEnabled) ?? (recommendedModel?.status === 'ready' && recommendedModel.translationEnabled ? recommendedModel : null) ?? snapshot?.models.find(model => model.status === 'ready' && model.translationEnabled) ?? recommendedModel;

  const { sourceLanguage, targetLanguage } = resolveTranslationPair(snapshot?.preferences.sourceLanguage, snapshot?.preferences.targetLanguage);
  const pairLabel = (source: TranslationLanguage, target: TranslationLanguage) => `${translationLanguageName(source)} → ${translationLanguageName(target)}`;
  const pairGlossary = snapshot?.glossary.filter(entry => (entry.sourceLanguage ?? 'en') === sourceLanguage && (entry.targetLanguage ?? 'ru') === targetLanguage) ?? [];

  const refresh = useCallback(async () => {
    const fresh = await PdfTranslator.bootstrap();
    setSnapshot(fresh);
    setBootstrapError('');
    return fresh;
  }, []);

  useEffect(() => {
    let mounted = true;
    let bootTimer: ReturnType<typeof setTimeout> | undefined;
    const initializedAt = bootStart.current;
    Promise.allSettled([PdfTranslator.bootstrap()]).then(results => {
      if (!mounted) return;
      const result = results[0];
      if (result.status === 'fulfilled') setSnapshot(result.value);
      else setBootstrapError(result.reason instanceof Error ? result.reason.message : t('unknownError'));
      const remaining = Math.max(0, 1500 - (Date.now() - initializedAt));
      bootTimer = setTimeout(() => { if (mounted) setBootComplete(true); }, remaining);
    });
    const subscription = AppState.addEventListener('change', setAppState);
    return () => { mounted = false; if (bootTimer) clearTimeout(bootTimer); subscription.remove(); };
  }, [t]);

  useEffect(() => {
    if (appState !== 'active' || !snapshot) return;
    const activeWork = snapshot.jobs.some(job => isRunning(job.status)) || snapshot.models.some(model => model.status === 'downloading' || model.status === 'verifying');
    if (!activeWork) return;
    const timer = setInterval(() => { void refresh().catch(() => undefined); }, 1000);
    return () => clearInterval(timer);
  }, [appState, refresh, snapshot]);

  useEffect(() => {
    if (snapshot && !showIntro && route === 'home' && selectedJob?.status && selectedJob.status !== 'completed' && selectedJob.status !== 'partial' && !selectedJobId) {
      setSelectedJobId(selectedJob.id);
    }
  }, [route, selectedJob, selectedJobId, showIntro, snapshot]);

  useEffect(() => {
    if (!selectedJobId || route !== 'preview') return;
    let live = true;
    setRenderedUri('');
    PdfTranslator.getJob(selectedJobId, selectedPage).then(value => {
      if (!live) return;
      setDetails(value);
      if (value.selectedPage && value.selectedPage.status !== 'pending') {
        return previewOriginal ? PdfTranslator.renderPage(value.job.documentId, value.selectedPage.index, Math.min(900, width * 1.5)) : PdfTranslator.renderTranslatedPage(value.job.id, value.selectedPage.index, Math.min(900, width * 1.5));
      }
      return '';
    }).then(uri => { if (live && uri) setRenderedUri(uri); }).catch(error => { if (live) setInlineError(error instanceof Error ? error.message : t('unknownError')); });
    return () => { live = false; };
  }, [route, selectedJobId, selectedPage, previewOriginal, t, width]);

  const go = (next: Route) => { setInlineError(''); routeStack.current.push(route); setRoute(next); };
  const back = () => { setInlineError(''); setRoute(routeStack.current.pop() ?? 'home'); };
  useEffect(() => {
    if (showIntro || route === 'home') return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => { setInlineError(''); setRoute(routeStack.current.pop() ?? 'home'); return true; });
    return () => subscription.remove();
  }, [showIntro, route]);
  const safely = async <T,>(operation: () => Promise<T>, success?: (value: T) => void) => {
    setBusy(true); setInlineError('');
    try { const value = await operation(); success?.(value); await refresh().catch(() => undefined); return value; }
    catch (error) { setInlineError(error instanceof Error ? error.message : t('unknownError')); return undefined; }
    finally { setBusy(false); }
  };

  const pickPdf = () => safely(() => PdfTranslator.pickPdf(), document => {
    if (!document) return;
    setSelectedDocument(document); setExported(null); go('prepare');
  });
  const startDownload = (model: ModelSnapshot) => safely(() => PdfTranslator.startModelDownload(model.id));
  const pauseDownload = (model: ModelSnapshot) => safely(() => PdfTranslator.pauseModelDownload(model.id));
  const deleteModel = (model: ModelSnapshot) => safely(() => PdfTranslator.deleteModel(model.id));
  const setPreference = (key: 'theme' | 'language' | 'selectedModelId', value: string) => safely(() => PdfTranslator.setSettings({ [key]: value }));
  const selectModel = (model: ModelSnapshot) => safely(() => PdfTranslator.setSettings({ selectedModelId: model.id }), () => back());
  const setTranslationPair = (source: TranslationLanguage, target: TranslationLanguage) => safely(() => PdfTranslator.setSettings({ sourceLanguage: source, targetLanguage: target }), preferences => { setSnapshot(current => current ? { ...current, preferences: { ...current.preferences, ...preferences } } : current); setLanguagePicker(null); setGlossarySource(''); setGlossaryTarget(''); setEditingGlossaryId(null); });
  const persistOnboarding = async (value: OnboardingState) => { const saved = await PdfTranslator.setOnboarding(value); setOnboardingOverride(saved); };

  if (!bootComplete) return <View style={{ flex: 1, backgroundColor: activeTheme === 'dark' ? '#171717' : '#F7F6F3', justifyContent: 'center', alignItems: 'center', paddingTop: insets.top }}><StatusBar barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'} /><View style={{ width: 62, height: 62, borderRadius: 20, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center', marginBottom: 15 }}><Text style={{ fontSize: 28, fontWeight: '900', color: colors.onAccent }}>P</Text></View><Text style={{ color: colors.text, fontSize: 20, fontWeight: '800' }}>{t('appName')}</Text><Text style={{ color: colors.muted, marginTop: 7 }}>{t('loading')}</Text></View>;

  if (!snapshot) return <Screen colors={colors} direction={direction}><StatusBar barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'} /><View style={{ flex: 1, justifyContent: 'center', padding: 24, gap: 18 }}><Header title={t('errorTitle')} colors={colors} direction={direction} /><Notice text={bootstrapError || t('unknownError')} colors={colors} retryLabel={t('retry')} retry={() => { void refresh().catch(error => setBootstrapError(error.message)); }} /></View></Screen>;

  if (showIntro) return <><StatusBar barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'} /><OnboardingScreen direction={direction} colors={colors} t={t} initialState={onboarding} persist={persistOnboarding} onComplete={() => { setOnboardingOverride({ version: ONBOARDING_VERSION, page: 3, done: true }); setRoute('home'); routeStack.current = []; }} ios={Platform.OS === 'ios'} /></>;

  const screenHeader = (title: string, subtitle?: string) => <Header direction={direction} title={title} subtitle={subtitle} colors={colors} onBack={route === 'home' ? undefined : back} backLabel={t('back')} />;
  const tabBar = () => <View style={{ flexDirection: 'row', borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.surface, paddingBottom: Math.max(8, insets.bottom), paddingTop: 7 }}>
    {(['home', 'settings'] as const).map((tab) => <Pressable key={tab} accessibilityRole="tab" accessibilityState={{ selected: route === tab }} onPress={() => { routeStack.current = []; setRoute(tab); setInlineError(''); }} style={{ flex: 1, alignItems: 'center', gap: 3, paddingVertical: 5 }}><Text style={{ fontSize: 18, color: route === tab ? colors.accent : colors.muted }}>{tab === 'home' ? '▤' : '⚙'}</Text><Text style={{ fontSize: 11, fontWeight: route === tab ? '800' : '600', color: route === tab ? colors.accent : colors.muted }}>{t(tab)}</Text></Pressable>)}
  </View>;

  const renderHome = () => <>
    {screenHeader(t('home'))}
    <ScrollView contentContainerStyle={{ padding: 20, paddingBottom: 28, gap: 17 }}>
      <Card colors={colors} style={{ padding: 22, overflow: 'hidden' }}>
        <View style={{ position: 'absolute', top: -55, right: -35, width: 185, height: 185, borderRadius: 100, backgroundColor: colors.softAccent }} />
        <Label colors={colors} tone="accent">{t('localOnly')}</Label>
        <Text style={{ fontSize: 26, lineHeight: 32, fontWeight: '800', letterSpacing: -0.5, color: colors.text, maxWidth: 480 }}>{t('welcome')}</Text>
        <Text style={{ color: colors.muted, lineHeight: 21, maxWidth: 520 }}>{t('welcomeBody')}</Text>
        <Button label={t('importPdf')} onPress={() => void pickPdf()} colors={colors} loading={busy} />
      </Card>
      <View style={{ flexDirection: wide ? 'row' : 'column', gap: 15 }}>
        <Card colors={colors} style={{ flex: wide ? 1 : undefined, minHeight: 174 }}>
          <Row style={{ justifyContent: 'space-between' }}><Text style={{ color: colors.text, fontWeight: '800', fontSize: 16 }}>{t('model')}</Text><Text style={{ fontSize: 19, color: colors.accent }}>◈</Text></Row>
          {selectedModel ? <>
            <Text style={{ color: colors.text, fontWeight: '700' }}>{selectedModel.label}</Text>
            <Label colors={colors} tone={selectedModel.status === 'ready' ? 'success' : selectedModel.status === 'error' ? 'accent' : 'muted'}>{selectedModel.status === 'ready' ? t('modelReady') : selectedModel.status === 'downloading' ? `${formatBytes(selectedModel.downloadedBytes)} / ${formatBytes(selectedModel.bytes)}` : t('modelMissing')}</Label>
            {selectedModel.status === 'downloading' || selectedModel.status === 'verifying' ? <ProgressBar colors={colors} value={selectedModel.bytes > 0 ? selectedModel.downloadedBytes / selectedModel.bytes : 0} label={t('downloaded')} /> : null}
          </> : <><Text style={{ color: colors.muted, lineHeight: 20 }}>{t('modelMissing')}</Text><Text style={{ color: colors.muted, fontSize: 12 }}>{t('unsupportedBody')}</Text></>}
          <Button label={t('useModel')} onPress={() => go('model')} colors={colors} variant="secondary" />
        </Card>
        <Card colors={colors} style={{ flex: wide ? 1 : undefined, minHeight: 174 }}>
          <Text style={{ color: colors.text, fontWeight: '800', fontSize: 16 }}>{t('recent')}</Text>
          {snapshot.jobs.length ? snapshot.jobs.slice(0, 3).map(job => <Pressable key={job.id} onPress={() => { setSelectedJobId(job.id); setSelectedPage(Math.max(0, job.currentPage ?? 0)); go(job.status === 'completed' || job.status === 'partial' ? 'preview' : 'job'); }} style={{ borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 10, gap: 6 }}><Row style={{ justifyContent: 'space-between' }}><Text numberOfLines={1} style={{ color: colors.text, fontWeight: '700', flex: 1 }}>{job.documentName}</Text><Text style={{ color: colors.muted, fontSize: 12 }}>{job.completedPages}/{job.totalPages}</Text></Row><Text style={{ color: colors.muted, fontSize: 12 }}>{t(statusKeys[job.status])}</Text></Pressable>) : null}
          {snapshot.documents.filter(document => !snapshot.jobs.some(job => job.documentId === document.id)).slice(0, 3).map(document => <Pressable key={document.id} onPress={() => { setSelectedDocument(document); go('prepare'); }} style={{ borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 10, gap: 5 }}><Row><Text numberOfLines={1} style={{ flex: 1, color: colors.text, fontWeight: '700' }}>{document.name}</Text><Text style={{ color: colors.muted, fontSize: 12 }}>{document.pageCount} {t('pages')}</Text></Row><Text style={{ color: colors.accent, fontSize: 12, fontWeight: '700' }}>{t('prepareTitle')}</Text></Pressable>)}
          {snapshot.jobs.length === 0 && snapshot.documents.length === 0 ? <Text style={{ color: colors.muted, lineHeight: 21 }}>{t('emptyTitle')} · {t('noDocuments')}</Text> : null}
        </Card>
      </View>
      {inlineError ? <Notice colors={colors} text={inlineError} /> : null}
      {bootstrapError ? <Notice colors={colors} text={bootstrapError} retryLabel={t('retry')} retry={() => { void refresh().catch(error => setBootstrapError(error.message)); }} /> : null}
    </ScrollView>{tabBar()}
  </>;

  const renderModel = () => <>
    {screenHeader(t('useModel'), t('modelChoiceBody'))}
    <ScrollView contentContainerStyle={{ padding: 20, gap: 15 }}>
      <Card colors={colors}>
        <View style={{ flexDirection: 'row', gap: 14 }}><View style={{ flex: 1 }}><Text style={{ color: colors.muted, fontSize: 12 }}>{t('freeMemory')}</Text><Text style={{ color: colors.text, fontWeight: '800', marginTop: 4 }}>{formatBytes(snapshot.device.availableMemoryBytes)}</Text></View><View style={{ flex: 1 }}><Text style={{ color: colors.muted, fontSize: 12 }}>{t('totalMemory')}</Text><Text style={{ color: colors.text, fontWeight: '800', marginTop: 4 }}>{formatBytes(snapshot.device.totalMemoryBytes)}</Text></View><View style={{ flex: 1 }}><Text style={{ color: colors.muted, fontSize: 12 }}>{t('freeDisk')}</Text><Text style={{ color: colors.text, fontWeight: '800', marginTop: 4 }}>{formatBytes(snapshot.device.availableDiskBytes)}</Text></View></View>
      </Card>
      {snapshot.models.length === 0 ? <Card colors={colors}><Text style={{ color: colors.text, fontWeight: '700' }}>{t('unsupportedTitle')}</Text><Text style={{ color: colors.muted, lineHeight: 20 }}>{t('unsupportedBody')}</Text></Card> : snapshot.models.map(model => {
        const downloading = model.status === 'downloading' || model.status === 'verifying';
        const canDownload = modelCanBeInstalled(model, snapshot.device);
        const canSelect = modelCanTranslate(model, snapshot.device);
        const resourceReason = modelResourceReason(model, snapshot.device, model.status !== 'ready');
        const isSelected = selectedModel?.id === model.id;
        const requiresTermsAcceptance = model.licenseUrl === 'https://ai.google.dev/gemma/terms';
        const showsTermsAcceptance = requiresTermsAcceptance && !['ready', 'downloading', 'verifying', 'paused'].includes(model.status);
        const tierKey: MessageKey = model.tier === 'quality' ? 'modelTierQuality' : model.tier === 'balanced' ? 'modelTierBalanced' : 'modelTierCompact';
        const tradeoffKey: MessageKey = model.tier === 'quality' ? 'modelTierTradeoffQuality' : model.tier === 'balanced' ? 'modelTierTradeoffBalanced' : 'modelTierTradeoffCompact';
        return <Card key={model.id} colors={colors}>
          <Row style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}><Text style={{ flex: 1, color: colors.text, fontSize: 17, fontWeight: '800' }}>{model.label}</Text><View style={{ alignItems: 'flex-end', gap: 5 }}><Label colors={colors} tone={model.status === 'ready' ? 'success' : model.status === 'error' ? 'accent' : 'muted'}>{t(modelStatusKeys[model.status])}</Label>{isSelected ? <Label colors={colors} tone="accent">{t('selectedModel')}</Label> : null}</View></Row>
          <Label colors={colors} tone="accent">{t(tierKey)}</Label>
          <Text style={{ color: colors.muted, fontSize: 13, lineHeight: 19 }}>{t(tradeoffKey)}</Text>
          {sourceLanguage === 'en' && targetLanguage === 'ru' && model.evaluation && Number.isInteger(model.evaluation.acceptableCases) && Number.isInteger(model.evaluation.totalCases) && model.evaluation.totalCases > 0 && model.evaluation.acceptableCases >= 0 && model.evaluation.acceptableCases <= model.evaluation.totalCases ? <View accessibilityRole="text"><Text style={{ color: colors.text, fontSize: 13, fontWeight: '700' }}>{t('modelEvidenceLine', { acceptable: model.evaluation.acceptableCases, total: model.evaluation.totalCases })}</Text><Text style={{ color: colors.muted, fontSize: 12 }}>{t('modelEvidenceCaveat')}</Text></View> : null}
          {sourceLanguage !== 'en' || targetLanguage !== 'ru' ? <Text style={{ color: colors.muted, fontSize: 12 }}>{t('modelPairUnrated')}</Text> : null}
          <View style={{ flexDirection: wide ? 'row' : 'column', gap: 6 }}>
            <Text style={{ color: colors.muted, flex: 1, fontSize: 12 }}>{t('fileSize')}: {formatBytes(model.bytes)}</Text>
            <Text style={{ color: colors.muted, flex: 1, fontSize: 12 }}>{t('modelMemoryEstimate')}: {model.requiredMemoryBytes ? formatBytes(model.requiredMemoryBytes) : '—'}</Text>
          </View>
          {downloading ? <><ProgressBar colors={colors} value={model.bytes ? model.downloadedBytes / model.bytes : 0} label={t('downloaded')} /><Text style={{ color: colors.muted, fontSize: 12 }}>{t('downloaded')}: {formatBytes(model.downloadedBytes)} / {formatBytes(model.bytes)}</Text></> : null}
          {model.licenseUrl ? <Pressable accessibilityRole="link" accessibilityLabel={`${t('licenses')}: ${model.label}`} onPress={() => void safely(() => Linking.openURL(model.licenseUrl!))}><Text style={{ color: colors.accent, textDecorationLine: 'underline', paddingVertical: 5 }}>{t('licenses')}</Text></Pressable> : null}
          {showsTermsAcceptance ? <Notice colors={colors} text={t('gemmaTermsNotice')} /> : null}
          <View style={{ flexDirection: 'row', gap: 9, flexWrap: 'wrap' }}>
            {model.status === 'ready' ? <><Button label={t('useThisModel')} accessibilityLabel={`${t('useThisModel')}: ${model.label}`} onPress={() => void selectModel(model)} colors={colors} disabled={busy || !canSelect || isSelected} /><Button label={t('delete')} variant="danger" onPress={() => void deleteModel(model)} colors={colors} disabled={busy} /></> : model.status === 'downloading' || model.status === 'verifying' ? <Button label={model.status === 'verifying' ? t('verifying') : t('pause')} onPress={() => void pauseDownload(model)} colors={colors} disabled={busy} /> : <Button label={model.status === 'paused' ? t('resume') : requiresTermsAcceptance ? t('acceptAndDownload') : t('download')} onPress={() => void startDownload(model)} colors={colors} loading={busy} disabled={busy || !canDownload} />}
          </View>
          {!canSelect && model.status === 'ready' ? <Notice text={t(resourceReasonKeys[resourceReason])} colors={colors} /> : null}
          {!canDownload && model.status !== 'ready' && !downloading ? <Notice text={t(resourceReasonKeys[modelResourceReason(model, snapshot.device, true)])} colors={colors} /> : null}
          {modelHasMemoryWarning(model, snapshot.device) ? <Notice colors={colors} tone="info" text={t('modelMemoryWarning')} /> : null}
          {model.error ? <Notice text={model.error} colors={colors} /> : null}
        </Card>;
      })}
      {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
    </ScrollView>
  </>;

  const renderPrepare = () => <>
    {screenHeader(t('prepareTitle'))}
    <ScrollView contentContainerStyle={{ padding: 20, gap: 15 }}>
      <Card colors={colors}>
        {(['source', 'target'] as const).map(kind => <View key={kind} style={{ gap: 6 }}>
          <Button label={`${t(kind === 'source' ? 'sourceLanguage' : 'targetLanguage')}: ${translationLanguageName(kind === 'source' ? sourceLanguage : targetLanguage)}`} onPress={() => setLanguagePicker(languagePicker === kind ? null : kind)} colors={colors} variant="secondary" disabled={busy} />
          {languagePicker === kind ? <View accessibilityRole="radiogroup" style={{ gap: 4 }}>{translationLanguages.map(([code, name]) => <Pressable key={code} accessibilityRole="radio" accessibilityLabel={name} accessibilityState={{ checked: code === (kind === 'source' ? sourceLanguage : targetLanguage), disabled: busy }} disabled={busy} onPress={() => {
            const source = kind === 'source' ? code : code === sourceLanguage ? targetLanguage : sourceLanguage;
            const target = kind === 'target' ? code : code === targetLanguage ? sourceLanguage : targetLanguage;
            void setTranslationPair(source, target);
          }} style={{ minHeight: 48, justifyContent: 'center', paddingHorizontal: 14, borderRadius: 12, backgroundColor: code === (kind === 'source' ? sourceLanguage : targetLanguage) ? colors.softAccent : colors.surface }}><Text style={{ color: colors.text }}>{name}</Text></Pressable>)}</View> : null}
        </View>)}
        <Button label={t('swapLanguages')} onPress={() => void setTranslationPair(targetLanguage, sourceLanguage)} colors={colors} variant="quiet" disabled={busy} />
      </Card>
      {selectedModel && pairGlossary.length > 0 && selectedModel.supportsGlossary === false ? <Notice colors={colors} text={t('modelGlossaryUnsupported')} /> : null}
      {selectedDocument ? <Card colors={colors}><Label colors={colors} tone="accent">{pairLabel(sourceLanguage, targetLanguage)}</Label><Text style={{ color: colors.text, fontSize: 19, fontWeight: '800' }}>{selectedDocument.name}</Text><Text style={{ color: colors.muted }}>{selectedDocument.pageCount} {t('pages')}</Text></Card> : null}
      <Card colors={colors}>
        <Text style={{ color: colors.text, fontSize: 18, fontWeight: '800' }}>{t('terms')}</Text><Text style={{ color: colors.muted, lineHeight: 20 }}>{t('termsBody')}</Text>
        <View style={{ flexDirection: wide ? 'row' : 'column', gap: 10 }}><View style={{ flex: 1 }}><Field label={t('sourceTerm')} value={glossarySource} onChangeText={setGlossarySource} colors={colors} /></View><View style={{ flex: 1 }}><Field label={t('targetTerm')} value={glossaryTarget} onChangeText={setGlossaryTarget} colors={colors} /></View></View>
        <Button label={t(editingGlossaryId ? 'saveGlossary' : 'addTerm')} onPress={() => {
          if (!glossarySource.trim() || !glossaryTarget.trim()) return;
          void safely(() => PdfTranslator.saveGlossaryEntry({ id: editingGlossaryId ?? `${Date.now()}`, source: glossarySource.trim(), target: glossaryTarget.trim(), sourceLanguage, targetLanguage }), () => { setGlossarySource(''); setGlossaryTarget(''); setEditingGlossaryId(null); });
        }} colors={colors} variant="secondary" disabled={busy || !glossarySource.trim() || !glossaryTarget.trim()} />
        {pairGlossary.length === 0 ? <Text style={{ color: colors.muted, fontSize: 13 }}>{t('noGlossary')}</Text> : pairGlossary.map(entry => <Row key={entry.id} style={{ paddingVertical: 6, borderTopWidth: 1, borderTopColor: colors.border, justifyContent: 'space-between' }}><Pressable accessibilityRole="button" accessibilityLabel={`${t('editTerm')}: ${entry.source}`} onPress={() => { setGlossarySource(entry.source); setGlossaryTarget(entry.target); setEditingGlossaryId(entry.id); }} style={{ flex: 1, paddingVertical: 4 }}><Text style={{ color: colors.text }}>{entry.source}  →  {entry.target}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t('removeTerm')} onPress={() => void safely(() => PdfTranslator.deleteGlossaryEntry(entry.id))}><Text style={{ color: colors.accent, padding: 7 }}>×</Text></Pressable></Row>)}
      </Card>
      {!selectedModel || !modelCanTranslate(selectedModel, snapshot.device) ? <Card colors={colors}><Text style={{ color: colors.text, fontWeight: '800' }}>{t('noModel')}</Text><Button label={t('chooseModel')} onPress={() => go('model')} colors={colors} /></Card> : <Card colors={colors}><Text style={{ color: colors.muted }}>{t('model')}: {selectedModel.label}</Text><Notice colors={colors} tone="info" text={Platform.OS === 'ios' ? t('iosBackground') : t('androidBackground')} /><Notice colors={colors} tone="info" text={t('modelVerifyOriginal')} />{modelHasMemoryWarning(selectedModel, snapshot.device) ? <Notice colors={colors} text={t('modelMemoryWarning')} /> : null}<Button label={t('translate')} onPress={() => {
        if (!selectedDocument || !selectedModel) return;
        void safely(() => PdfTranslator.startJob({ documentId: selectedDocument.id, sourceLanguage, targetLanguage, modelId: selectedModel.id }), job => { setSelectedJobId(job.id); setSelectedPage(0); go('job'); });
      }} colors={colors} loading={busy} disabled={busy || (pairGlossary.length > 0 && selectedModel.supportsGlossary === false)} /></Card>}
      {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
    </ScrollView>
  </>;

  const renderJob = () => {
    const job = snapshot.jobs.find(item => item.id === selectedJobId) ?? selectedJob;
    if (!job) return <>{screenHeader(t('progress'))}<View style={{ padding: 20 }}><Notice text={t('noDocuments')} colors={colors} /></View></>;
    const rawRatio = job.totalPages > 0 ? job.completedPages / job.totalPages : 0;
    const ratio = job.status === 'completed' || job.status === 'partial' ? rawRatio : Math.min(0.99, rawRatio);
    const done = job.status === 'completed' || job.status === 'partial';
    const etaRange = job.etaRangeSeconds && Number.isFinite(job.etaRangeSeconds.min) && Number.isFinite(job.etaRangeSeconds.max) && job.etaRangeSeconds.min >= 0 && job.etaRangeSeconds.max >= job.etaRangeSeconds.min ? job.etaRangeSeconds : null;
    const eta = job.etaSeconds != null && Number.isFinite(job.etaSeconds) && job.etaSeconds >= 0 ? job.etaSeconds : null;
    const controls = <View style={{ flexDirection: 'row', gap: 10, flexWrap: 'wrap' }}>
      {job.status === 'running' || job.status === 'queued' ? <Button label={t('pause')} onPress={() => void safely(() => PdfTranslator.pauseJob(job.id))} colors={colors} variant="secondary" disabled={busy} /> : job.status === 'paused' || job.status === 'failed' ? <Button label={t(job.status === 'failed' ? 'retry' : 'resume')} onPress={() => void safely(() => PdfTranslator.resumeJob(job.id))} colors={colors} disabled={busy} /> : null}
      {!done && job.status !== 'canceled' && job.status !== 'failed' ? <Button label={t('cancel')} onPress={() => void safely(() => PdfTranslator.cancelJob(job.id))} colors={colors} variant="quiet" disabled={busy} /> : null}
      {done ? <Button label={t('previewTitle')} onPress={() => go('preview')} colors={colors} /> : null}
      {done ? <Button label={t('saveCopy')} onPress={() => void safely(() => PdfTranslator.exportJob(job.id), value => setExported(value ?? null))} colors={colors} variant="secondary" disabled={busy} /> : null}
      {exported ? <Button label={t('share')} onPress={() => void safely(() => PdfTranslator.shareExport(exported.id))} colors={colors} variant="secondary" disabled={busy} /> : null}
    </View>;
    return <>
      {screenHeader(t('progress'), job.documentName)}
      <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }}>
        <Card colors={colors} style={{ padding: 22 }}>
          <Row style={{ justifyContent: 'space-between' }}><Label colors={colors} tone={job.status === 'failed' ? 'accent' : done ? 'success' : 'muted'}>{t(statusKeys[job.status])}</Label><Text style={{ color: colors.muted, fontSize: 12 }}>{pairLabel(job.sourceLanguage, job.targetLanguage)}</Text></Row>
          <Text style={{ fontSize: 21, color: colors.text, fontWeight: '800' }}>{t(stageKeys[job.stage])}</Text>
          <ProgressBar colors={colors} value={ratio} label={t('progress')} />
          <Row style={{ justifyContent: 'space-between' }}><Text style={{ color: colors.text, fontWeight: '700' }}>{t('pageProgress')}</Text><Text style={{ color: colors.muted }}>{job.completedPages} / {job.totalPages}</Text></Row>
          {job.currentPage != null || job.status === 'queued' ? <Text style={{ color: colors.muted, fontSize: 12 }}>{job.currentPage != null ? `${t('page')} ${job.currentPage + 1}` : t('stageQueued')}</Text> : null}
          {job.completedBlocks !== undefined || job.discoveredBlocks !== undefined ? <Text style={{ color: colors.muted, fontSize: 12 }}>{t('blocksCompleted')}: {job.completedBlocks ?? 0}{job.totalBlocks != null ? ` / ${job.totalBlocks}` : job.discoveredBlocks != null ? ` · ${t('blocksDiscovered')}: ${job.discoveredBlocks}` : ''}</Text> : null}
          {etaRange ? <Text style={{ color: colors.muted, fontSize: 12 }}>{t('eta')}: ~{formatDuration(etaRange.min, key => t(key))}–{formatDuration(etaRange.max, key => t(key))}</Text> : eta != null ? <Text style={{ color: colors.muted, fontSize: 12 }}>{t('eta')}: ~{formatDuration(eta, key => t(key))}</Text> : null}
          {job.error ? <Notice text={job.error} colors={colors} /> : null}
          {controls}
          {exported ? <Text style={{ color: colors.success, fontWeight: '700' }}>{t('saved')}: {exported.displayName}</Text> : null}
        </Card>
        <Text style={{ color: colors.muted, lineHeight: 20 }}>{Platform.OS === 'ios' ? t('iosBackground') : t('androidBackground')}</Text>
        {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
      </ScrollView>
    </>;
  };

  const renderPreview = () => <>
    {screenHeader(t('previewTitle'), details?.job.id === selectedJobId ? pairLabel(details.job.sourceLanguage, details.job.targetLanguage) : undefined)}
    <View style={{ flexDirection: 'row', paddingHorizontal: 18, paddingTop: 8, gap: 10 }}><Button label={t('original')} colors={colors} variant={previewOriginal ? 'primary' : 'secondary'} onPress={() => setPreviewOriginal(true)} /><Button label={t('translated')} colors={colors} variant={!previewOriginal ? 'primary' : 'secondary'} onPress={() => setPreviewOriginal(false)} /></View>
    <View style={{ flex: 1, flexDirection: wide ? 'row' : 'column' }}>
      <ScrollView style={{ flex: wide ? 1.15 : undefined }} contentContainerStyle={{ padding: 18, gap: 14 }}>
        {renderedUri ? <Image accessibilityLabel={`${t('page')} ${selectedPage + 1}`} source={{ uri: renderedUri }} resizeMode="contain" style={{ width: '100%', height: wide ? 620 : 430, borderRadius: 14, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border }} /> : <Card colors={colors}><Text style={{ color: colors.text, fontWeight: '800' }}>{t('loading')}</Text></Card>}
        <Row style={{ justifyContent: 'space-between' }}><Button label={t('previous')} onPress={() => setSelectedPage(Math.max(0, selectedPage - 1))} colors={colors} variant="secondary" disabled={selectedPage === 0} /><Text style={{ color: colors.text, fontWeight: '700' }}>{t('page')} {selectedPage + 1}</Text><Button label={t('next')} onPress={() => setSelectedPage(selectedPage + 1)} colors={colors} variant="secondary" disabled={Boolean(details && selectedPage >= details.job.totalPages - 1)} /></Row>
      </ScrollView>
      <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 18, gap: 13 }}>
        {details?.selectedPage?.error ? <Notice text={details.selectedPage.error} colors={colors} /> : null}
        <Card colors={colors}><Text style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}>{t('original')}</Text>{details?.selectedPage?.blocks.length ? details.selectedPage.blocks.map(block => <View key={block.id} style={{ borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 9, gap: 6 }}><Text style={{ color: colors.muted, lineHeight: 19 }}>{block.sourceText}</Text></View>) : <Text style={{ color: colors.muted }}>{t('noDocuments')}</Text>}</Card>
        <Card colors={colors}><Text style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}>{t('translated')}</Text>{details?.selectedPage?.blocks.length ? details.selectedPage.blocks.map(block => <View key={block.id} style={{ borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 9, gap: 6 }}><Text style={{ color: block.status === 'partial' || block.status === 'failed' ? colors.warning : colors.text, lineHeight: 20 }}>{block.translatedText ?? block.error ?? '—'}</Text>{block.status === 'partial' || block.status === 'failed' ? <Text style={{ color: colors.warning, fontSize: 12 }}>{block.error ?? t('issues')}</Text> : null}</View>) : <Text style={{ color: colors.muted }}>{t('noDocuments')}</Text>}</Card>
        <Card colors={colors}><Text style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}>{t('issues')}</Text>{details?.selectedPage?.blocks.some(block => block.status === 'partial' || block.status === 'failed' || block.status === 'skipped') ? details.selectedPage.blocks.filter(block => block.status === 'partial' || block.status === 'failed' || block.status === 'skipped').map(block => <Text key={block.id} style={{ color: colors.warning, lineHeight: 19 }}>• {block.error ?? block.sourceText}</Text>) : <Text style={{ color: details?.selectedPage?.status === 'partial' ? colors.warning : colors.muted }}>{t(details?.selectedPage?.status === 'partial' ? 'issues' : 'noIssues')}</Text>}</Card>
        <Button label={t('openResult')} onPress={() => selectedJob && void safely(() => PdfTranslator.previewJob(selectedJob.id))} colors={colors} variant="secondary" disabled={busy || !selectedJob} />
        <Button label={t('openOriginal')} onPress={() => selectedJob && void safely(() => PdfTranslator.openPdf(selectedJob.documentId))} colors={colors} variant="secondary" disabled={busy || !selectedJob} />
        <Button label={t('saveCopy')} onPress={() => selectedJob && void safely(() => PdfTranslator.exportJob(selectedJob.id), value => setExported(value ?? null))} colors={colors} disabled={busy || !selectedJob} />
        {exported ? <Button label={t('share')} onPress={() => void safely(() => PdfTranslator.shareExport(exported.id))} colors={colors} variant="secondary" disabled={busy} /> : null}
        {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
      </ScrollView>
    </View>
  </>;

  const renderSettings = () => <>
    {screenHeader(t('settings'))}
    <ScrollView contentContainerStyle={{ padding: 20, gap: 15 }}>
      <Card colors={colors}><SectionTitle colors={colors}>{t('appearance')}</SectionTitle>{(['system', 'light', 'dark'] as const).map(value => <Pressable key={value} accessibilityRole="radio" accessibilityState={{ checked: preferenceTheme === value }} onPress={() => void setPreference('theme', value)} style={{ minHeight: 48, borderTopWidth: 1, borderTopColor: colors.border, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}><Text style={{ color: colors.text }}>{t(value as MessageKey)}</Text><View style={{ width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: preferenceTheme === value ? colors.accent : colors.muted, alignItems: 'center', justifyContent: 'center' }}>{preferenceTheme === value ? <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: colors.accent }} /> : null}</View></Pressable>)}</Card>
      <Card colors={colors}><SectionTitle colors={colors}>{t('language')}</SectionTitle>{localeOptions.map(([code, name]) => <Pressable key={code} accessibilityRole="radio" accessibilityState={{ checked: savedLanguage === code }} onPress={() => void setPreference('language', code)} style={{ minHeight: 45, borderTopWidth: 1, borderTopColor: colors.border, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}><Text style={{ color: colors.text }}>{name}</Text>{savedLanguage === code ? <Text style={{ color: colors.accent, fontWeight: '800' }}>✓</Text> : null}</Pressable>)}</Card>
      <Card colors={colors}><Text style={{ color: colors.text, fontSize: 16, fontWeight: '800' }}>{t('localOnly')}</Text><Text style={{ color: colors.muted, lineHeight: 20 }}>{t('privacyBody')}</Text><Button label={t('privacy')} onPress={() => go('privacy')} colors={colors} variant="secondary" /><Button label={t('licenses')} onPress={() => go('licenses')} colors={colors} variant="secondary" /><Button label={t('replay')} onPress={() => void safely(() => PdfTranslator.setOnboarding({ version: ONBOARDING_VERSION, page: 0, done: false }), setOnboardingOverride)} colors={colors} loading={busy} variant="quiet" /></Card>
      {inlineError ? <Notice text={inlineError} colors={colors} /> : null}
    </ScrollView>{tabBar()}
  </>;

  const renderInfo = (kind: 'privacy' | 'licenses') => <>{screenHeader(t(kind))}<ScrollView contentContainerStyle={{ padding: 20 }}><Card colors={colors}><Label colors={colors} tone="accent">{t('localOnly')}</Label><Text style={{ color: colors.text, fontSize: 18, fontWeight: '800' }}>{t(kind)}</Text><Text style={{ color: colors.muted, lineHeight: 23 }}>{t(kind === 'privacy' ? 'privacyBody' : 'licensesBody')}</Text></Card></ScrollView></>;

  return <Screen colors={colors} direction={direction}>
    <StatusBar barStyle={activeTheme === 'dark' ? 'light-content' : 'dark-content'} />
    <Animated.View key={route} entering={reducedMotion ? undefined : FadeIn.duration(130)} style={{ flex: 1, paddingTop: insets.top, paddingLeft: insets.left, paddingRight: insets.right, paddingBottom: route === 'home' || route === 'settings' ? 0 : insets.bottom }}>
      {route === 'home' ? renderHome() : route === 'model' ? renderModel() : route === 'prepare' ? renderPrepare() : route === 'job' ? renderJob() : route === 'preview' ? renderPreview() : route === 'settings' ? renderSettings() : renderInfo(route)}
    </Animated.View>
  </Screen>;
}

export default function App() { return <SafeAreaProvider><AppContent /></SafeAreaProvider>; }
