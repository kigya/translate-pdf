import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Image,
  Linking,
  Pressable,
  ScrollView,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import Animated, {
  FadeIn,
  useReducedMotion,
  useSharedValue,
  useAnimatedStyle,
  withTiming,
} from 'react-native-reanimated';
import type {
  BootstrapSnapshot,
  DocumentSnapshot,
  GlossaryEntry,
  JobSnapshot,
  ModelSnapshot,
} from '../native/PdfTranslator';
import { PdfTranslator } from '../native/PdfTranslator';
import type { Palette } from '../ui/theme';
import type { Locale, Translator } from '../i18n/messages';
import { localeOptions } from '../i18n/messages';
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
  translationLanguageName,
  TranslationLanguage,
} from '../domain/translationLanguages';
import { Button, Field, Notice, ProgressBar, Row } from '../ui/components';
import { Icon, IconButton, SettingsRow, Sheet } from '../ui/Controls';
import { AnimatedNumber, Balloons, DownloadSculpture } from '../ui/Motion';

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
function info(title: string, message: string) {
  Alert.alert(title, message);
}
export function LibraryScreen({
  colors,
  t,
  locale,
  busy,
  snapshot,
  model,
  onTranslate,
  onModel,
  onJob,
  onDocument,
}: {
  snapshot: BootstrapSnapshot;
  model?: ModelSnapshot | null;
  onTranslate: () => void;
  onModel: () => void;
  onJob: (job: JobSnapshot) => void;
  onDocument: (doc: DocumentSnapshot) => void;
} & Base) {
  const empty = !snapshot.jobs.length && !snapshot.documents.length;
  return (
    <ScrollView contentContainerStyle={content}>
      <View style={{ paddingVertical: 20, gap: 22 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
          <Icon name="document" size={42} color={colors.accent} />
          <Text
            style={{
              color: colors.text,
              fontSize: 32,
              fontWeight: '800',
              letterSpacing: -1,
            }}
          >
            {t('appName')}
          </Text>
        </View>
        <Button
          label={ux(locale, 'translatePdf')}
          onPress={onTranslate}
          colors={colors}
          loading={busy}
        />
      </View>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={t('useModel')}
        onPress={onModel}
        style={{
          paddingVertical: 15,
          borderTopWidth: 1,
          borderBottomWidth: 1,
          borderColor: colors.border,
          gap: 7,
        }}
      >
        <Row style={{ justifyContent: 'space-between' }}>
          <Text style={{ color: colors.muted, fontSize: 13 }}>
            {t('model')}
          </Text>
          <Icon name="chevron" color={colors.muted} size={18} />
        </Row>
        <Row style={{ justifyContent: 'space-between' }}>
          <Title colors={colors}>
            {model
              ? ux(locale, modelPresentation(model).title)
              : t('chooseModel')}
          </Title>
          <Text
            style={{
              color: model?.status === 'ready' ? colors.success : colors.muted,
              fontSize: 13,
            }}
          >
            {model?.status === 'ready'
              ? t('modelReady')
              : model?.status === 'downloading'
              ? `${Math.round((100 * model.downloadedBytes) / model.bytes)}%`
              : t('modelMissing')}
          </Text>
        </Row>
        {model?.status === 'downloading' || model?.status === 'verifying' ? (
          <ProgressBar
            colors={colors}
            value={model.downloadedBytes / model.bytes}
            label={t('downloaded')}
          />
        ) : null}
      </Pressable>
      {empty ? (
        <View style={{ alignItems: 'center', paddingVertical: 40, gap: 18 }}>
          <View style={{ transform: [{ rotate: '-8deg' }], opacity: 0.45 }}>
            <Icon name="document" size={76} color={colors.muted} />
          </View>
          <Text
            style={{
              color: colors.muted,
              textAlign: 'center',
              fontSize: 15,
              maxWidth: 280,
              lineHeight: 22,
            }}
          >
            {ux(locale, 'emptyLibrary')}
          </Text>
        </View>
      ) : (
        <View style={{ gap: 8 }}>
          <Title colors={colors}>{t('recent')}</Title>
          {snapshot.jobs.map(job => (
            <Pressable
              key={job.id}
              accessibilityRole="button"
              accessibilityLabel={job.documentName}
              onPress={() => onJob(job)}
              style={{
                flexDirection: 'row',
                gap: 16,
                alignItems: 'center',
                paddingVertical: 18,
                borderBottomColor: colors.border,
                borderBottomWidth: 0.5,
              }}
            >
              <Icon name="document" size={30} color={colors.accent} />
              <View style={{ flex: 1, gap: 5 }}>
                <Text
                  numberOfLines={2}
                  style={{
                    color: colors.text,
                    fontWeight: '600',
                    fontSize: 16,
                  }}
                >
                  {job.documentName}
                </Text>
                <Text style={{ color: colors.muted, fontSize: 12 }}>
                  {job.completedPages}/{job.totalPages} ·{' '}
                  {translationLanguageName(job.sourceLanguage)} →{' '}
                  {translationLanguageName(job.targetLanguage)}
                </Text>
              </View>
              <Icon
                name={job.status === 'completed' ? 'check' : 'chevron'}
                size={20}
                color={
                  job.status === 'completed' ? colors.success : colors.muted
                }
              />
            </Pressable>
          ))}
          {snapshot.documents
            .filter(
              doc => !snapshot.jobs.some(job => job.documentId === doc.id),
            )
            .map(doc => (
              <SettingsRow
                key={doc.id}
                icon="document"
                label={doc.name}
                value={`${doc.pageCount} ${t('pages')}`}
                onPress={() => onDocument(doc)}
                colors={colors}
              />
            ))}
        </View>
      )}
    </ScrollView>
  );
}
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
  const [details, setDetails] = useState<ModelSnapshot | null>(null);
  const [celebrate, setCelebrate] = useState(false);
  const previous = useRef(new Map(snapshot.models.map(m => [m.id, m.status])));
  const reduced = useReducedMotion() ?? false;
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
              downloading =
                model.status === 'downloading' || model.status === 'verifying',
              isSelected = selected?.id === model.id;
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
                <Row style={{ justifyContent: 'space-between' }}>
                  <Text
                    style={{
                      color: colors.text,
                      fontSize: 26,
                      fontWeight: '800',
                      letterSpacing: -0.7,
                    }}
                  >
                    {ux(locale, p.title)}
                  </Text>
                  <IconButton
                    name="info"
                    label={`${ux(locale, 'details')}: ${ux(locale, p.title)}`}
                    onPress={() => setDetails(model)}
                    colors={colors}
                  />
                </Row>
                <Row style={{ gap: 28, alignItems: 'flex-end' }}>
                  <View style={{ flex: 1, gap: 4 }}>
                    <Text
                      style={{
                        fontSize: 30,
                        color: colors.text,
                        fontWeight: '700',
                      }}
                    >
                      {formatBytes(model.bytes)}
                    </Text>
                    <Text style={{ color: colors.muted, fontSize: 12 }}>
                      {t('fileSize')}
                    </Text>
                  </View>
                  <View style={{ alignItems: 'flex-end', gap: 4 }}>
                    <AnimatedNumber
                      value={p.score}
                      suffix="%"
                      color={colors.accent}
                    />
                    <Pressable
                      accessibilityRole="button"
                      onPress={() =>
                        info(
                          ux(locale, 'capability'),
                          ux(locale, 'capabilityInfo'),
                        )
                      }
                      style={{ minHeight: 40, justifyContent: 'center' }}
                    >
                      <Text style={{ color: colors.muted, fontSize: 12 }}>
                        {ux(locale, 'capability')} ⓘ
                      </Text>
                    </Pressable>
                  </View>
                </Row>
                {downloading ? (
                  <>
                    <View
                      style={{
                        height: 130,
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <DownloadSculpture
                        progress={
                          model.bytes ? model.downloadedBytes / model.bytes : 0
                        }
                        active
                        color={colors.accent}
                      />
                    </View>
                    <ProgressBar
                      colors={colors}
                      value={model.downloadedBytes / model.bytes}
                      label={t('downloaded')}
                    />
                    <Text style={{ color: colors.muted, fontSize: 13 }}>
                      {formatBytes(model.downloadedBytes)} /{' '}
                      {formatBytes(model.bytes)}
                    </Text>
                  </>
                ) : null}
                <Text
                  style={{
                    color:
                      model.status === 'ready' ? colors.success : colors.muted,
                    fontSize: 13,
                  }}
                >
                  {isSelected && model.status === 'ready'
                    ? t('selectedModel')
                    : status(model)}
                </Text>
                {model.status === 'ready' ? (
                  <Button
                    label={t('useThisModel')}
                    colors={colors}
                    disabled={
                      busy ||
                      isSelected ||
                      !modelCanTranslate(model, snapshot.device)
                    }
                    onPress={() => onSelect(model)}
                  />
                ) : (
                  <Button
                    label={
                      downloading
                        ? model.status === 'verifying'
                          ? t('verifying')
                          : t('pause')
                        : model.status === 'paused'
                        ? t('resume')
                        : model.licenseUrl?.includes('gemma')
                        ? t('acceptAndDownload')
                        : t('download')
                    }
                    colors={colors}
                    disabled={
                      busy ||
                      model.status === 'verifying' ||
                      (!downloading &&
                        !modelCanBeInstalled(model, snapshot.device))
                    }
                    onPress={() =>
                      downloading ? onPause(model) : onDownload(model)
                    }
                  />
                )}
                {model.status === 'ready' || model.status === 'paused' ? (
                  <Pressable
                    accessibilityRole="button"
                    onPress={() => onDelete(model)}
                    style={{ alignSelf: 'center', padding: 12 }}
                  >
                    <Text style={{ color: colors.muted }}>
                      {ux(locale, 'removeModel')}
                    </Text>
                  </Pressable>
                ) : null}
                {modelHasMemoryWarning(model, snapshot.device) ? (
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('modelMemoryWarning')}
                    onPress={() =>
                      info(t('freeMemory'), t('modelMemoryWarning'))
                    }
                    style={{ minHeight: 48, justifyContent: 'center' }}
                  >
                    <Text style={{ color: colors.warning, fontSize: 13 }}>
                      {t('freeMemory')}:{' '}
                      {formatBytes(snapshot.device.availableMemoryBytes)} ⓘ
                    </Text>
                  </Pressable>
                ) : null}
                {!downloading &&
                model.status !== 'ready' &&
                !modelCanBeInstalled(model, snapshot.device) ? (
                  <Notice colors={colors} text={t('modelResourceDisk')} />
                ) : null}
                {model.error ? (
                  <Notice colors={colors} text={model.error} />
                ) : null}
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
            <Title colors={colors}>{details.label}</Title>
            <Text selectable style={{ color: colors.muted, lineHeight: 22 }}>
              {details.revision}
            </Text>
            <Text style={{ color: colors.muted, lineHeight: 21 }}>
              {ux(locale, 'capabilityInfo')}
            </Text>
            {details.evaluation ? (
              <Text style={{ color: colors.muted, lineHeight: 21 }}>
                {t('modelEvidenceLine', {
                  acceptable: details.evaluation.acceptableCases,
                  total: details.evaluation.totalCases,
                })}
                {'\n'}
                {t('modelEvidenceCaveat')}
              </Text>
            ) : null}
            <Text style={{ color: colors.muted, lineHeight: 21 }}>
              {t('modelVerifyOriginal')}
            </Text>
            {details.licenseUrl ? (
              <SettingsRow
                icon="license"
                label={t('licenses')}
                onPress={() =>
                  void Linking.openURL(details.licenseUrl!).catch(() =>
                    info(t('errorTitle'), t('unknownError')),
                  )
                }
                colors={colors}
              />
            ) : null}
            {details.licenseUrl?.includes('gemma') ? (
              <Text style={{ color: colors.muted }}>
                {t('gemmaTermsNotice')}
              </Text>
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
            `${code} ${name}`
              .toLocaleLowerCase()
              .includes(search.toLocaleLowerCase()),
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
  const [picker, setPicker] = useState<'source' | 'target' | null>(null);
  const turn = useSharedValue(0);
  const reduced = useReducedMotion() ?? false;
  const swapStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${turn.value}deg` }],
  }));
  return (
    <>
      <Row style={{ gap: 6, justifyContent: 'space-between' }}>
        {(['source', 'swap', 'target'] as const).map(kind =>
          kind === 'swap' ? (
            <Animated.View key={kind} style={swapStyle}>
              <IconButton
                name="swap"
                label={t('swapLanguages')}
                disabled={busy}
                onPress={() => {
                  turn.value = reduced
                    ? turn.value + 180
                    : withTiming(turn.value + 180, { duration: 300 });
                  onChange(target, source);
                }}
                colors={colors}
              />
            </Animated.View>
          ) : (
            <Pressable
              key={kind}
              accessibilityRole="button"
              accessibilityLabel={`${t(
                kind === 'source' ? 'sourceLanguage' : 'targetLanguage',
              )}: ${translationLanguageName(
                kind === 'source' ? source : target,
              )}`}
              disabled={busy}
              accessibilityState={{ disabled: !!busy }}
              onPress={() => setPicker(kind)}
              style={{
                flex: 1,
                minHeight: 90,
                justifyContent: 'center',
                alignItems: 'center',
                gap: 9,
              }}
            >
              <Text style={{ fontSize: 32 }}>
                {localeFlags[kind === 'source' ? source : target]}
              </Text>
              <Text
                numberOfLines={1}
                style={{ color: colors.text, fontSize: 14, fontWeight: '700' }}
              >
                {translationLanguageName(kind === 'source' ? source : target)}
              </Text>
            </Pressable>
          ),
        )}
      </Row>
      <Sheet
        visible={!!picker}
        title={t(picker === 'source' ? 'sourceLanguage' : 'targetLanguage')}
        onClose={() => setPicker(null)}
        colors={colors}
        closeLabel={t('back')}
      >
        {translationLanguages.map(([code, name]) => (
          <SettingsRow
            key={code}
            icon="globe"
            label={`${localeFlags[code]}  ${name}`}
            onPress={() => {
              const s =
                picker === 'source' ? code : code === source ? target : source;
              const d =
                picker === 'target' ? code : code === target ? source : target;
              onChange(s, d);
              setPicker(null);
            }}
            colors={colors}
          />
        ))}
      </Sheet>
    </>
  );
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
      PdfTranslator.renderPage(document.id, 0, 420)
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
                accessibilityLabel={ux(locale, 'sourcePdf')}
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
                {document.pageCount} {t('pages')}
              </Text>
              <Text style={{ color: colors.accent, fontWeight: '600' }}>
                {ux(locale, 'previewSource')}
              </Text>
            </View>
            <Icon name="chevron" color={colors.muted} />
          </Pressable>
        ) : null}
        <View>
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
            <Switch
              accessibilityLabel={ux(locale, 'useDictionary')}
              value={useGlossary && model?.supportsGlossary !== false}
              disabled={!terms || model?.supportsGlossary === false || busy}
              onValueChange={onUseGlossary}
              trackColor={{ false: colors.border, true: colors.accent }}
              thumbColor="#FFFFFF"
            />
          </Row>
          {model?.supportsGlossary === false ? (
            <Text style={{ color: colors.muted, fontSize: 13 }}>
              {ux(locale, 'glossaryOff')}
            </Text>
          ) : null}
        </View>
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
          {ux(locale, 'localProcessing')}
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
  onRoute,
  onAppearance,
  onPrivacy,
  onReplay,
}: {
  snapshot: BootstrapSnapshot;
  onRoute: (r: 'language' | 'storage' | 'dictionary' | 'licenses') => void;
  onAppearance: () => void;
  onPrivacy: () => void;
  onReplay: () => void;
} & Base) {
  return (
    <ScrollView contentContainerStyle={content}>
      <View>
        <SettingsRow
          icon="globe"
          label={t('language')}
          value={`${localeFlags[snapshot.preferences.language] ?? '🌐'} ${
            snapshot.preferences.language === 'system'
              ? ux(locale, 'systemDefault')
              : localeOptions.find(
                  ([id]) => id === snapshot.preferences.language,
                )?.[1] ?? ''
          }`}
          onPress={() => onRoute('language')}
          colors={colors}
        />
        <SettingsRow
          icon="moon"
          label={t('appearance')}
          value={t(snapshot.preferences.theme)}
          onPress={onAppearance}
          colors={colors}
        />
      </View>
      <View>
        <SettingsRow
          icon="storage"
          label={ux(locale, 'storage')}
          value={formatBytes(
            snapshot.device.storage?.installedModelBytes ??
              snapshot.models.reduce(
                (sum, m) => sum + (m.status === 'ready' ? m.bytes : 0),
                0,
              ),
          )}
          onPress={() => onRoute('storage')}
          colors={colors}
        />
        <SettingsRow
          icon="book"
          label={ux(locale, 'glossary')}
          value={ux(locale, 'termsCount', { count: snapshot.glossary.length })}
          onPress={() => onRoute('dictionary')}
          colors={colors}
        />
      </View>
      <View>
        <SettingsRow
          icon="shield"
          label={t('privacy')}
          onPress={onPrivacy}
          colors={colors}
        />
        <SettingsRow
          icon="license"
          label={t('licenses')}
          onPress={() => onRoute('licenses')}
          colors={colors}
        />
        <SettingsRow
          icon="replay"
          label={t('replay')}
          onPress={onReplay}
          colors={colors}
        />
      </View>
    </ScrollView>
  );
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
  const [page, setPage] = useState(0),
    [zoom, setZoom] = useState(1),
    [uri, setUri] = useState(''),
    [error, setError] = useState('');
  useEffect(() => {
    let live = true;
    setUri('');
    setError('');
    if (document)
      PdfTranslator.renderPage(document.id, page, 1200)
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
              accessibilityLabel={`${ux(locale, 'sourcePdf')} ${page + 1}`}
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
            total: document?.pageCount ?? 0,
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
          disabled={!document || page >= document.pageCount - 1}
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
