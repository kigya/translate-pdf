import { ScrambleText } from '../components/kinetik/scramble-text';
import { StatusButton } from '../components/kinetik/status-button';
import { FoldPanel } from '../components/kinetik/fold-panel';
import { ThemeSwitch } from '../components/kinetik/theme-switch';
import { UsageMeter } from '../components/kinetik/usage-meter';
import { libraryUx } from '../i18n/libraryUx';
import { library050Ux } from '../i18n/library050Ux';
import { DocumentLibrary, DocumentLibraryProps } from '../ui/DocumentLibrary';
import { MorphSelect } from '../components/kinetik/morph-select';
import { Profile } from '../components/kinetik/screens/profile';
import { PulseToggle } from '../components/kinetik/pulse-toggle';
import { ONLINE_MODEL } from '../domain/presentation';
import { useMotionReduced } from '../ui/useMotionReduced';
import React, { useEffect, useRef, useState } from 'react';
import {
  Image,
  Linking,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from 'react-native';
import Animated, {
  FadeIn,
} from 'react-native-reanimated';
import type {
  BootstrapSnapshot,
  DocumentSnapshot,
  GlossaryEntry,
  ModelSnapshot,
  ThemePreference,
} from '../native/PdfTranslator';
import { PdfTranslator } from '../native/PdfTranslator';
import type { Palette } from '../ui/theme';
import { palettes } from '../ui/theme';
import type { Locale, Translator } from '../i18n/messages';
import { localeOptions, localeSearchNames } from '../i18n/messages';
import { ux } from '../i18n/ux';
import {
  formatBytes,
  localeFlags,
  modelPresentation,
} from '../domain/presentation';
import {
  modelCanBeInstalled,
  modelCanTranslate,
  modelHasMemoryWarning,
} from '../domain/modelEligibility';
import {
  translationLanguages,
  translationLanguageClosedName,
  TranslationLanguage,
} from '../domain/translationLanguages';
import { Button, Field, Notice, ProgressBar, Row } from '../ui/components';
import { Icon, IconButton, SettingsRow, Sheet } from '../ui/Controls';
import { useDialog } from '../ui/Dialog';
import { AnimatedNumber, Balloons } from '../ui/Motion';

type Base = { colors: Palette; t: Translator; locale: Locale; busy?: boolean };
const content = {
  paddingHorizontal: 24,
  paddingTop: 12,
  paddingBottom: 32,
  gap: 22,
};
function Title({
  children,
  colors,
}: {
  children: React.ReactNode;
  colors: Palette;
}) {
  return (
    <Text style={{ color: colors.text, fontSize: 16, fontWeight: '700' }}>
      {children}
    </Text>
  );
}
function useInfo(closeLabel: string) {
  const show = useDialog();
  return (title: string, message: string) => { void show({ title, message, actions: [{ id: 'close', label: closeLabel }] }); };
}
export function LibraryScreen(props: DocumentLibraryProps) { return <DocumentLibrary {...props} />; }
export function ModelScreen({
  colors,
  t,
  locale,
  busy,
  snapshot,
  selected,
  onSelect,
  onDownload,
  onPause,
  onDelete,
}: {
  snapshot: BootstrapSnapshot;
  selected?: ModelSnapshot | null;
  onSelect: (m: ModelSnapshot) => void;
  onDownload: (m: ModelSnapshot) => void;
  onPause: (m: ModelSnapshot) => void;
  onDelete: (m: ModelSnapshot) => void;
} & Base) {
  const info = useInfo(t('back'));
  const [details, setDetails] = useState<ModelSnapshot | null>(null);
  const [openPanel, setOpenPanel] = useState<string | null>(null);
  const [licenseError, setLicenseError] = useState('');
  const [celebrate, setCelebrate] = useState(false);
  const previous = useRef(new Map(snapshot.models.map(m => [m.id, m.status])));
  const reduced = useMotionReduced();
  useEffect(() => {
    for (const model of snapshot.models) {
      if (
        model.status === 'ready' &&
        ['downloading', 'verifying'].includes(
          previous.current.get(model.id) ?? '',
        )
      )
        setCelebrate(true);
      previous.current.set(model.id, model.status);
    }
  }, [snapshot.models]);
  const status = (m: ModelSnapshot) =>
    t(
      m.status === 'ready'
        ? 'modelReady'
        : m.status === 'downloading'
        ? 'modelDownloading'
        : m.status === 'verifying'
        ? 'verifying'
        : m.status === 'paused'
        ? 'modelPaused'
        : m.status === 'error'
        ? 'modelError'
        : 'modelNotDownloaded',
    );
  return (
    <>
      <ScrollView contentContainerStyle={content}>
        <FoldPanel title={library050Ux(locale, 'online')} subtitle={ux(locale, 'onlineAvailable')} open={openPanel === 'mymemory'} onOpenChange={open => setOpenPanel(open ? 'mymemory' : null)} leading={<Icon name="globe" color={colors.accent} />}>
          <View style={{ gap: 12, paddingBottom: 14 }}>
            <ScrambleText text={ONLINE_MODEL.label} duration={420} style={{ color: colors.text, fontSize: 18, fontWeight: '700' }} />
            <Text style={{ color: colors.muted }}>{ux(locale, 'onlineQuota')}</Text>
            <SettingsRow icon="license" label={t('licenses')} value={library050Ux(locale, 'onlineLicense')} onPress={() => { setLicenseError(''); setDetails(ONLINE_MODEL); }} colors={colors} />
            <Button colors={colors} label={t('useThisModel')} disabled={busy || selected?.id === 'mymemory'} onPress={() => onSelect(ONLINE_MODEL)} />
          </View>
        </FoldPanel>
        {!snapshot.models.some(m => m.translationEnabled) ? (
          <View style={{ gap: 12 }}>
            <Title colors={colors}>{ux(locale, 'modelsUnavailable')}</Title>
            <Text style={{ color: colors.muted, lineHeight: 22 }}>
              {ux(locale, 'modelsUnavailableBody')}
            </Text>
          </View>
        ) : null}
        {snapshot.models
          .filter(m => m.translationEnabled)
          .map(model => {
            const p = modelPresentation(model),
              downloading = model.status === 'downloading' || model.status === 'verifying',
              isSelected = selected?.id === model.id,
              progress = model.bytes > 0 ? Math.min(100, Math.round(model.downloadedBytes / model.bytes * 100)) : 0,
              selectedStatus = isSelected && model.status === 'ready' ? t('selectedModel') : status(model),
              subtitle = `${formatBytes(model.bytes)} · ${p.score}% · ${downloading ? `${progress}% · ` : ''}${selectedStatus}`;
            return (
              <Animated.View
                key={model.id}
                entering={reduced ? undefined : FadeIn.duration(250)}
                style={{
                  paddingVertical: 22,
                  gap: 16,
                  borderBottomWidth: 1,
                  borderBottomColor: colors.border,
                }}
              >
                <FoldPanel title={ux(locale, p.title)} subtitle={subtitle} open={openPanel === model.id} onOpenChange={open => setOpenPanel(open ? model.id : null)} leading={<Icon name="book" color={colors.accent} />}>
                  <View style={{ gap: 12, paddingBottom: 14 }}>
                    <ScrambleText text={model.label} duration={420} style={{ color: colors.text, fontSize: 18, fontWeight: '700' }} />
                    <SettingsRow icon="license" label={t('licenses')} value={library050Ux(locale, model.licenseUrl?.includes('gemma') ? 'gemmaLicense' : 'localLicense')} onPress={() => { setLicenseError(''); setDetails(model); }} colors={colors} />
                    {licenseError ? <Notice colors={colors} text={licenseError} /> : null}
                    <Text style={{ color: model.status === 'ready' ? colors.success : colors.muted }}>{selectedStatus}</Text>
                    {downloading ? <>
                      <StatusButton status="loading" loadingLabel={model.status === 'verifying' ? t('verifying') : t('modelDownloading')} accessibilityLabel={model.status === 'verifying' ? t('verifying') : t('modelDownloading')} haptics={false}>{t('download')}</StatusButton>
                      <ProgressBar colors={colors} value={progress / 100} label={t('downloaded')} />
                      <Text style={{ color: colors.muted, fontSize: 13 }}>{formatBytes(model.downloadedBytes)} / {formatBytes(model.bytes)} · {progress}%</Text>
                      {model.status === 'downloading' ? <Button label={t('pause')} colors={colors} disabled={busy} onPress={() => onPause(model)} /> : null}
                    </> : model.status === 'ready' ? <Button label={t('useThisModel')} colors={colors} disabled={busy || isSelected || !modelCanTranslate(model, snapshot.device)} onPress={() => onSelect(model)} /> : <Button label={model.status === 'paused' ? t('resume') : model.licenseUrl?.includes('gemma') ? t('acceptAndDownload') : t('download')} colors={colors} disabled={busy || !modelCanBeInstalled(model, snapshot.device)} onPress={() => onDownload(model)} />}
                    {model.status === 'ready' || model.status === 'paused' ? <Pressable accessibilityRole="button" accessibilityLabel={ux(locale, 'removeModel')} disabled={busy} onPress={() => onDelete(model)} style={{ alignSelf: 'center', padding: 12 }}><Text style={{ color: colors.muted }}>{ux(locale, 'removeModel')}</Text></Pressable> : null}
                    {modelHasMemoryWarning(model, snapshot.device) ? <Pressable accessibilityRole="button" accessibilityLabel={t('modelMemoryWarning')} onPress={() => info(t('freeMemory'), t('modelMemoryWarning'))} style={{ minHeight: 48, justifyContent: 'center' }}><Text style={{ color: colors.warning, fontSize: 13 }}>{t('freeMemory')}: {formatBytes(snapshot.device.availableMemoryBytes)} ⓘ</Text></Pressable> : null}
                    {model.status !== 'ready' && !downloading && !modelCanBeInstalled(model, snapshot.device) ? <Notice colors={colors} text={t('modelResourceDisk')} /> : null}
                    {model.error ? <Notice colors={colors} text={model.error} /> : null}
                  </View>
                </FoldPanel>
              </Animated.View>
            );
          })}
      </ScrollView>
      <Sheet
        visible={!!details}
        title={ux(locale, 'modelDetails')}
        onClose={() => setDetails(null)}
        colors={colors}
        closeLabel={t('back')}
      >
        {details ? (
          <View style={{ gap: 16 }}>
            <ScrambleText text={details.label} duration={480} style={{ color: colors.text, fontSize: 21, fontWeight: '700' }} />

            {licenseError ? <Notice colors={colors} text={licenseError}/> : null}
            {details.licenseUrl ? (
              <SettingsRow
                icon="license"
                label={t('licenses')}
                onPress={() =>
                  void Linking.openURL(details.licenseUrl!).catch(() => setLicenseError(t('unknownError')))
                }
                colors={colors}
              />
            ) : null}
          </View>
        ) : null}
      </Sheet>
      <View pointerEvents="none" style={{ position: 'absolute', inset: 0 }}>
        <Balloons
          active={celebrate}
          reduced={reduced}
          onComplete={() => setCelebrate(false)}
        />
      </View>
    </>
  );
}
export function LanguageChoices({
  colors,
  locale,
  busy,
  selected,
  onSelect,
}: { selected: string; onSelect: (value: string) => void } & Base) {
  const [search, setSearch] = useState('');
  return (
    <View style={{ flex: 1, paddingHorizontal: 24 }}>
      <TextInput
        accessibilityLabel={ux(locale, 'search')}
        placeholder={ux(locale, 'search')}
        placeholderTextColor={colors.muted}
        selectionColor={colors.accent}
        cursorColor={colors.accent}
        selectionHandleColor={colors.accent}
        value={search}
        onChangeText={setSearch}
        autoCorrect={false}
        style={{
          color: colors.text,
          backgroundColor: colors.raised,
          borderRadius: 16,
          padding: 16,
          marginVertical: 14,
          fontSize: 16,
        }}
      />
      <ScrollView keyboardShouldPersistTaps="handled">
        {localeOptions
          .filter(([code, name]) =>
            `${code} ${name} ${localeSearchNames[code]}`
              .normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
              .includes(search.trim().normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()),
          )
          .map(([code, name]) => (
            <Pressable
              key={code}
              accessibilityRole="radio"
              accessibilityLabel={
                code === 'system' ? ux(locale, 'systemDefault') : name
              }
              accessibilityState={{
                checked: code === selected,
                disabled: !!busy,
              }}
              disabled={busy}
              onPress={() => onSelect(code)}
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                minHeight: 58,
                borderBottomWidth: 0.5,
                borderBottomColor: colors.border,
                gap: 15,
              }}
            >
              <Text style={{ fontSize: 27 }}>{localeFlags[code]}</Text>
              <Text style={{ flex: 1, fontSize: 16, color: colors.text }}>
                {code === 'system' ? ux(locale, 'systemDefault') : name}
              </Text>
              {code === selected ? (
                <Icon name="check" color={colors.accent} />
              ) : null}
            </Pressable>
          ))}
      </ScrollView>
    </View>
  );
}
export function PairPicker({
  colors,
  t,
  source,
  target,
  busy,
  onChange,
}: {
  source: TranslationLanguage;
  target: TranslationLanguage;
  onChange: (s: TranslationLanguage, t: TranslationLanguage) => void;
} & Base) {
  const { width, fontScale } = useWindowDimensions();
  const stacked = width / fontScale < 340;
  const fieldWidth = Math.max(0, (width - 96) / 2);
  const options = translationLanguages.map(([value, name]) => ({ value, label: `${localeFlags[value]} ${name}`, compactLabel: `${localeFlags[value]} ${translationLanguageClosedName(value, fieldWidth, fontScale)}` }));
  return <Row style={{ flexDirection: stacked ? 'column' : 'row', alignItems: stacked ? 'stretch' : 'flex-start' }}>
    <View style={stacked ? undefined : { flex: 1 }}><MorphSelect closeLabel={t('back')} minMenuWidth={280} label={t('sourceLanguage')} value={source} options={options} disabled={busy} onValueChange={value => onChange(value as TranslationLanguage, value === target ? source : target)} /></View>
    <IconButton name="swap" label={t('swapLanguages')} colors={colors} disabled={busy} onPress={() => onChange(target, source)} />
    <View style={stacked ? undefined : { flex: 1 }}><MorphSelect closeLabel={t('back')} minMenuWidth={280} label={t('targetLanguage')} value={target} options={options} disabled={busy} onValueChange={value => onChange(value === source ? target : source, value as TranslationLanguage)} /></View>
  </Row>;
}
export function TranslationOptionsScreen({
  colors,
  t,
  locale,
  busy,
  document,
  model,
  source,
  target,
  terms,
  useGlossary,
  onUseGlossary,
  onPair,
  onDictionary,
  onPreview,
  onModel,
  onStart,
}: {
  document: DocumentSnapshot | null;
  model?: ModelSnapshot | null;
  source: TranslationLanguage;
  target: TranslationLanguage;
  terms: number;
  useGlossary: boolean;
  onUseGlossary: (value: boolean) => void;
  onPair: (s: TranslationLanguage, t: TranslationLanguage) => void;
  onDictionary: () => void;
  onPreview: () => void;
  onModel: () => void;
  onStart: () => void;
} & Base) {
  const [thumbnail, setThumbnail] = useState('');
  useEffect(() => {
    let live = true;
    setThumbnail('');
    if (document)
      (document.format === 'docx' ? PdfTranslator.renderDocxPreview(document.id).then(() => PdfTranslator.renderPage(document.id, 0, 420)) : PdfTranslator.renderPage(document.id, 0, 420))
        .then(uri => {
          if (live) setThumbnail(uri);
        })
        .catch(() => {});
    return () => {
      live = false;
    };
  }, [document]);
  return (
    <>
      <ScrollView contentContainerStyle={content}>
        <PairPicker
          colors={colors}
          t={t}
          locale={locale}
          source={source}
          target={target}
          busy={busy}
          onChange={onPair}
        />
        {document ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={ux(locale, 'previewSource')}
            onPress={onPreview}
            style={{
              flexDirection: 'row',
              gap: 18,
              alignItems: 'center',
              paddingVertical: 20,
              borderTopWidth: 0.5,
              borderBottomWidth: 0.5,
              borderColor: colors.border,
            }}
          >
            {thumbnail ? (
              <Image
                accessibilityLabel={ux(locale, document.format === 'docx' ? 'original' : 'sourcePdf')}
                source={{ uri: thumbnail }}
                style={{
                  width: 74,
                  height: 100,
                  borderRadius: 5,
                  backgroundColor: colors.surface,
                }}
                resizeMode="contain"
              />
            ) : (
              <Icon name="document" size={64} color={colors.accent} />
            )}
            <View style={{ flex: 1, gap: 8 }}>
              <Text
                style={{ color: colors.text, fontSize: 17, fontWeight: '700' }}
              >
                {document.name}
              </Text>
              <Text style={{ color: colors.muted }}>
                {document.format === 'docx' ? document.segmentCount ?? document.pageCount : document.pageCount} {document.format === 'docx' ? ux(locale, 'segments') : t('pages')}
              </Text>
              <Text style={{ color: colors.accent, fontWeight: '600' }}>
                {ux(locale, 'previewSource')}
              </Text>
            </View>
            <Icon name="chevron" color={colors.muted} />
          </Pressable>
        ) : null}
        {model?.supportsGlossary !== false ? <View>
          <SettingsRow
            icon="book"
            label={ux(locale, 'glossary')}
            value={ux(locale, 'termsCount', { count: terms })}
            onPress={onDictionary}
            colors={colors}
          />
          <Row style={{ justifyContent: 'space-between', paddingVertical: 14 }}>
            <Text style={{ color: colors.muted }}>
              {ux(locale, 'useDictionary')}
            </Text>
            <PulseToggle
              accessibilityLabel={ux(locale, 'useDictionary')}
              value={useGlossary}
              disabled={!terms || busy}
              onValueChange={onUseGlossary}
            />
          </Row>
        </View> : null}
        <SettingsRow
          icon="storage"
          label={t('model')}
          value={
            model
              ? ux(locale, modelPresentation(model).title)
              : t('chooseModel')
          }
          onPress={onModel}
          colors={colors}
        />
      </ScrollView>
      <View
        style={{
          padding: 24,
          paddingTop: 12,
          borderTopWidth: 0.5,
          borderTopColor: colors.border,
          gap: 8,
        }}
      >
        <Button
          label={ux(locale, 'translatePdf')}
          onPress={onStart}
          colors={colors}
          disabled={
            busy ||
            !model ||
            !model.translationEnabled ||
            model.status !== 'ready' ||
            !document
          }
          loading={busy}
        />
        <Text
          style={{ color: colors.muted, fontSize: 12, textAlign: 'center' }}
        >
          {ux(locale, model?.id === 'mymemory' ? 'onlineProcessing' : 'localProcessing')}
        </Text>
      </View>
    </>
  );
}
export function SettingsScreen({
  colors,
  t,
  locale,
  snapshot,
  selectedModel,
  onRoute,
  onTheme,
  busy,
  onPrivacy,
  onReplay,
}: {
  snapshot: BootstrapSnapshot;
  selectedModel?: ModelSnapshot | null;
  onRoute: (r: 'language' | 'storage' | 'dictionary' | 'licenses') => void;
  onTheme: (theme: ThemePreference) => void;
  onPrivacy: () => void;
  onReplay: () => void;
} & Base) {
  return <View style={{ flex: 1 }}><Profile sections={[
    { rows: [
      { key: 'language', label: t('language'), icon: <Icon name="globe" color={colors.accent} />, detail: `${localeFlags[snapshot.preferences.language] ?? '🌐'} ${snapshot.preferences.language === 'system' ? ux(locale, 'systemDefault') : localeOptions.find(([id]) => id === snapshot.preferences.language)?.[1] ?? ''}`, onPress: () => onRoute('language') },
    ] },
    { title: t('appearance'), rows: [], content: <View style={{ paddingHorizontal: 16, gap: 10 }}>
      <Row style={{ justifyContent: 'space-between', minHeight: 54 }}><Text style={{ color: colors.text }}>{library050Ux(locale, 'followSystem')}</Text><PulseToggle accessibilityLabel={library050Ux(locale, 'followSystem')} value={snapshot.preferences.theme === 'system'} disabled={busy} onValueChange={value => onTheme(value ? 'system' : (colors.background === palettes.dark.background ? 'dark' : 'light'))} /></Row>
      <Row style={{ justifyContent: 'space-between', minHeight: 54 }}><Text style={{ color: colors.text }}>{library050Ux(locale, 'lightMode')} / {library050Ux(locale, 'darkMode')}</Text><ThemeSwitch value={snapshot.preferences.theme === 'system' ? (colors.background === palettes.dark.background ? 'dark' : 'light') : snapshot.preferences.theme} label={t('appearance')} stateLabel={snapshot.preferences.theme === 'dark' ? library050Ux(locale, 'darkMode') : library050Ux(locale, 'lightMode')} accessibilityLabel={t('appearance')} haptics={!busy} onValueChange={value => onTheme(value)} /></Row>
    </View> },
    { rows: [
      { key: 'storage', label: ux(locale, 'storage'), icon: <Icon name="storage" color={colors.accent} />, detail: formatBytes(snapshot.device.storage?.installedModelBytes ?? snapshot.models.reduce((sum, m) => sum + (m.status === 'ready' ? m.bytes : 0), 0)), onPress: () => onRoute('storage') },
      ...(selectedModel?.supportsGlossary === false ? [] : [{ key: 'dictionary', label: ux(locale, 'glossary'), icon: <Icon name="book" color={colors.accent} />, onPress: () => onRoute('dictionary') }]),
    ] },
    { rows: [
      { key: 'privacy', label: t('privacy'), icon: <Icon name="shield" color={colors.accent} />, onPress: onPrivacy },
      { key: 'licenses', label: t('licenses'), icon: <Icon name="license" color={colors.accent} />, onPress: () => onRoute('licenses') },
      { key: 'tutorial', label: ux(locale, 'viewTutorial'), icon: <Icon name="replay" color={colors.accent} />, onPress: onReplay },
    ] },
  ]} /></View>;
}
export function StorageScreen({
  colors,
  t,
  locale,
  snapshot,
  onDelete,
  onClear,
}: {
  snapshot: BootstrapSnapshot;
  onDelete: (m: ModelSnapshot) => void;
  onClear: () => void;
} & Base) {
  const info = useInfo(t('back'));
  const storage = snapshot.device.storage;
  const installed =
      storage?.installedModelBytes ??
      snapshot.models.reduce(
        (sum, m) => sum + (m.status === 'ready' ? m.bytes : 0),
        0,
      ),
    partial =
      storage?.partialDownloadBytes ??
      snapshot.models.reduce(
        (sum, m) => sum + (m.status === 'ready' ? 0 : m.downloadedBytes),
        0,
      );
  return (
    <ScrollView contentContainerStyle={content}>
      <UsageMeter title={ux(locale, 'storage')} segments={[{ label: ux(locale, 'modelsStored'), value: installed / 1e9 }, { label: ux(locale, 'partialDownloads'), value: partial / 1e9 }]} total={(installed + partial + (storage?.freeDiskBytes ?? snapshot.device.availableDiskBytes)) / 1e9} unit="GB" remainingLabel={libraryUx(locale, 'remaining')} overLimitLabel={libraryUx(locale, 'overLimit')} />
      <Row style={{ justifyContent: 'space-between' }}>
        <View style={{ gap: 8 }}>
          <Text style={{ color: colors.muted }}>{ux(locale, 'freeSpace')}</Text>
          <AnimatedNumber
            value={
              Math.round(
                (storage?.freeDiskBytes ?? snapshot.device.availableDiskBytes) /
                  1e8,
              ) / 10
            }
            suffix=" GB"
            color={colors.text}
          />
        </View>
        <IconButton
          name="info"
          label={ux(locale, 'storage')}
          onPress={() => info(ux(locale, 'storage'), ux(locale, 'storageInfo'))}
          colors={colors}
        />
      </Row>
      <View style={{ gap: 14 }}>
        {(
          [
            [ux(locale, 'modelsStored'), installed],
            [ux(locale, 'partialDownloads'), partial],
            ...(storage?.activeTransferBytes
              ? [[t('downloaded'), storage.activeTransferBytes] as const]
              : []),
            [ux(locale, 'availableRam'), snapshot.device.availableMemoryBytes],
          ] as const
        ).map(([label, bytes]) => (
          <Row key={label} style={{ justifyContent: 'space-between' }}>
            <Text style={{ color: colors.muted }}>{label}</Text>
            <Text style={{ color: colors.text, fontWeight: '700' }}>
              {formatBytes(bytes)}
            </Text>
          </Row>
        ))}
      </View>
      <View>
        {snapshot.models
          .filter(
            m =>
              (m.storedBytes ?? m.downloadedBytes) > 0 ||
              (m.activeTransferBytes ?? 0) > 0 ||
              m.status === 'downloading',
          )
          .map(model => (
            <SettingsRow
              key={model.id}
              icon="trash"
              label={ux(locale, modelPresentation(model).title)}
              value={
                (model.activeTransferBytes ?? 0) > 0
                  ? `${t('downloaded')}: ${formatBytes(
                      model.activeTransferBytes,
                    )}`
                  : formatBytes(model.storedBytes ?? model.downloadedBytes)
              }
              onPress={() => onDelete(model)}
              colors={colors}
            />
          ))}
      </View>
      <Button
        label={ux(locale, 'clearModels')}
        colors={colors}
        variant="danger"
        disabled={
          installed + partial + (storage?.activeTransferBytes ?? 0) === 0 &&
          !snapshot.models.some(m => m.status === 'downloading')
        }
        onPress={onClear}
      />
      {snapshot.notifications &&
      snapshot.notifications.status !== 'granted' &&
      snapshot.notifications.status !== 'not_required' ? (
        <SettingsRow
          icon="info"
          label={ux(locale, 'notifications')}
          value={ux(locale, 'notificationUnavailable')}
          onPress={() =>
            void Linking.openSettings().catch(() =>
              info(t('errorTitle'), t('unknownError')),
            )
          }
          colors={colors}
        />
      ) : null}
    </ScrollView>
  );
}
export function DictionaryScreen({
  colors,
  t,
  locale,
  busy,
  entries,
  source,
  target,
  onPair,
  onSave,
  onDelete,
}: {
  entries: GlossaryEntry[];
  source: TranslationLanguage;
  target: TranslationLanguage;
  onPair: (s: TranslationLanguage, t: TranslationLanguage) => void;
  onSave: (source: string, target: string, id?: string) => Promise<unknown>;
  onDelete: (id: string) => void;
} & Base) {
  const [search, setSearch] = useState(''),
    [editing, setEditing] = useState<GlossaryEntry | null | undefined>(
      undefined,
    ),
    [from, setFrom] = useState(''),
    [to, setTo] = useState('');
  const pair = entries.filter(
    e => e.sourceLanguage === source && e.targetLanguage === target,
  );
  return (
    <>
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={content}
      >
        <PairPicker
          colors={colors}
          t={t}
          locale={locale}
          source={source}
          target={target}
          busy={busy}
          onChange={onPair}
        />
        <Row style={{ gap: 12 }}>
          <TextInput
            accessibilityLabel={ux(locale, 'search')}
            placeholder={ux(locale, 'search')}
            placeholderTextColor={colors.muted}
            value={search}
            onChangeText={setSearch}
            selectionColor={colors.accent}
            cursorColor={colors.accent}
            selectionHandleColor={colors.accent}
            style={{
              flex: 1,
              color: colors.text,
              backgroundColor: colors.raised,
              borderRadius: 14,
              padding: 14,
            }}
          />
          <IconButton
            name="plus"
            label={t('addTerm')}
            onPress={() => {
              setEditing(null);
              setFrom('');
              setTo('');
            }}
            colors={colors}
          />
        </Row>
        {!pair.length ? (
          <Text style={{ color: colors.muted, lineHeight: 22 }}>
            {ux(locale, 'dictionaryEmpty')}
          </Text>
        ) : (
          pair
            .filter(e =>
              `${e.source} ${e.target}`
                .toLocaleLowerCase()
                .includes(search.toLocaleLowerCase()),
            )
            .map(entry => (
              <Row
                key={entry.id}
                style={{
                  borderBottomWidth: 0.5,
                  borderBottomColor: colors.border,
                }}
              >
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`${t('editTerm')}: ${entry.source}`}
                  onPress={() => {
                    setEditing(entry);
                    setFrom(entry.source);
                    setTo(entry.target);
                  }}
                  style={{
                    flex: 1,
                    minHeight: 64,
                    justifyContent: 'center',
                    gap: 4,
                  }}
                >
                  <Text
                    style={{
                      color: colors.text,
                      fontWeight: '600',
                      fontSize: 16,
                    }}
                  >
                    {entry.source}
                  </Text>
                  <Text style={{ color: colors.muted }}>{entry.target}</Text>
                </Pressable>
                <IconButton
                  name="trash"
                  label={`${t('removeTerm')}: ${entry.source}`}
                  onPress={() => onDelete(entry.id)}
                  colors={colors}
                />
              </Row>
            ))
        )}
      </ScrollView>
      <Sheet
        visible={editing !== undefined}
        title={t(editing ? 'editTerm' : 'addTerm')}
        onClose={() => setEditing(undefined)}
        colors={colors}
        closeLabel={t('back')}
      >
        <View style={{ gap: 16 }}>
          <Field
            label={t('sourceTerm')}
            value={from}
            onChangeText={setFrom}
            colors={colors}
          />
          <Field
            label={t('targetTerm')}
            value={to}
            onChangeText={setTo}
            colors={colors}
          />
          <Button
            label={t('saveGlossary')}
            colors={colors}
            disabled={busy || !from.trim() || !to.trim()}
            onPress={() => {
              void onSave(from.trim(), to.trim(), editing?.id).then(result => {
                if (result !== undefined) setEditing(undefined);
              });
            }}
          />
        </View>
      </Sheet>
    </>
  );
}
export function SourcePreviewScreen({
  colors,
  t,
  locale,
  document,
}: { document: DocumentSnapshot | null } & Base) {
  const [count, setCount] = useState(document?.format === 'docx' ? 0 : document?.pageCount ?? 0);
  const [page, setPage] = useState(0),
    [zoom, setZoom] = useState(1),
    [uri, setUri] = useState(''),
    [error, setError] = useState('');
  useEffect(() => {
    let live = true;
    setUri('');
    setError('');
    if (document)
      (document.format === 'docx' ? PdfTranslator.renderDocxPreview(document.id).then(preview => { if (!live) return ''; setCount(preview.pageCount); return PdfTranslator.renderPage(document.id, page, 1200); }) : PdfTranslator.renderPage(document.id, page, 1200))
        .then(value => {
          if (live) setUri(value);
        })
        .catch(() => {
          if (live) setError(t('unknownError'));
        });
    return () => {
      live = false;
    };
  }, [document, page, t]);
  return (
    <>
      <ScrollView
        maximumZoomScale={3}
        minimumZoomScale={1}
        centerContent
        contentContainerStyle={{
          flexGrow: 1,
          alignItems: 'center',
          justifyContent: 'center',
          padding: 16,
        }}
      >
        <ScrollView horizontal contentContainerStyle={{ alignItems: 'center' }}>
          {uri ? (
            <Image
              accessibilityLabel={`${ux(locale, document?.format === 'docx' ? 'original' : 'sourcePdf')} ${page + 1}`}
              source={{ uri }}
              style={{
                width: 330 * zoom,
                height: 470 * zoom,
                backgroundColor: colors.surface,
              }}
              resizeMode="contain"
            />
          ) : (
            <Text style={{ color: colors.muted }}>{error || t('loading')}</Text>
          )}
        </ScrollView>
      </ScrollView>
      <Row style={{ padding: 16, gap: 6, justifyContent: 'space-between' }}>
        <Button
          label={t('previous')}
          colors={colors}
          variant="quiet"
          disabled={page === 0}
          onPress={() => {
            setPage(p => p - 1);
            setZoom(1);
          }}
        />
        <Text style={{ color: colors.text }}>
          {ux(locale, 'pageOf', {
            page: page + 1,
            total: count,
          })}
        </Text>
        <IconButton
          name="plus"
          label={ux(locale, zoom < 3 ? 'zoomIn' : 'zoomOut')}
          onPress={() => setZoom(v => (v < 3 ? v + 1 : 1))}
          colors={colors}
        />
        <Button
          label={t('next')}
          colors={colors}
          variant="quiet"
          disabled={!document || page >= count - 1}
          onPress={() => {
            setPage(p => p + 1);
            setZoom(1);
          }}
        />
      </Row>
    </>
  );
}
export function LicensesScreen({ colors, t }: Base) {
  const [notices, setNotices] = useState<Array<{ id: string; title: string }>>(
      [],
    ),
    [chosen, setChosen] = useState(''),
    [text, setText] = useState(''),
    [error, setError] = useState('');
  useEffect(() => {
    let live = true;
    PdfTranslator.getLicenseNotices()
      .then(value => {
        if (live) setNotices(value);
      })
      .catch(() => {
        if (live) setError(t('unknownError'));
      });
    return () => {
      live = false;
    };
  }, [t]);
  return (
    <>
      <ScrollView contentContainerStyle={content}>
        {error ? <Notice colors={colors} text={error} /> : null}
        {notices.map(notice => (
          <SettingsRow
            key={notice.id}
            icon="license"
            label={notice.title}
            onPress={() => {
              setChosen(notice.title);
              setText('');
              void PdfTranslator.getLicenseNotice(notice.id)
                .then(setText)
                .catch(() => setText(t('unknownError')));
            }}
            colors={colors}
          />
        ))}
      </ScrollView>
      <Sheet
        visible={!!chosen}
        title={chosen}
        onClose={() => setChosen('')}
        colors={colors}
        closeLabel={t('back')}
      >
        <Text
          selectable
          style={{ color: colors.text, fontSize: 13, lineHeight: 20 }}
        >
          {text || t('loading')}
        </Text>
      </Sheet>
    </>
  );
}
