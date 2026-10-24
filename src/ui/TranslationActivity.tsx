import React, { useEffect, useRef, useState } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';

import { ConfettiBurst, type ConfettiHandle } from '../components/kinetik/confetti-burst';
import { ScanProgress } from '../components/kinetik/scan-progress';
import { Timeline, type TimelineEvent } from '../components/kinetik/timeline';
import { PdfTranslator, type JobSnapshot, type JobStage } from '../native/PdfTranslator';
import { localeDirection, Translator } from '../i18n/messages';
import { ux } from '../i18n/ux';
import { Icon } from './Controls';
import { Notice } from './components';
import { Palette } from './theme';

const stages: readonly { key: string; label: (t: Translator, locale: string, job: JobSnapshot) => string }[] = [
  { key: 'preparing', label: (t) => t('stageCopying') },
  { key: 'reading', label: (t) => t('stageExtracting') },
  { key: 'translating', label: (t, locale, job) => job.engine?.kind === 'online' ? ux(locale, 'onlineProcessing') : t('stageTranslating') },
  { key: 'building', label: (t) => t('stageLayout') },
  { key: 'complete', label: (t) => t('stageComplete') },
];

const stageIndex: Record<JobStage, number> = {
  queued: 0,
  copying: 0,
  loadingModel: 0,
  extracting: 1,
  ocr: 1,
  translating: 2,
  layout: 3,
  exporting: 3,
  complete: 4,
};

export function translationActivityTimeline(job: JobSnapshot, t: Translator, locale = 'en'): TimelineEvent[] {
  const finished = job.status === 'completed' || job.status === 'partial';
  const current = finished
    ? 4
    : job.status === 'queued'
      ? 0
      : stageIndex[job.stage] ?? -1;
  const safeCurrent = current === 4 && !finished ? 3 : current;
  return stages.map((stage, index) => ({
    key: stage.key,
    title: stage.label(t, locale, job),
    time: '',
    state: finished
      ? 'done'
      : index < safeCurrent
        ? 'done'
        : index === safeCurrent
          ? 'current'
          : 'upcoming',
  }));
}

function activityLabel(job: JobSnapshot, t: Translator, locale: string): string {
  switch (job.status) {
    case 'queued': return t('stageQueued');
    case 'paused': return t('pausedStatus');
    case 'partial': return t('partial');
    case 'failed': return t('failed');
    case 'canceled': return t('canceledStatus');
    case 'completed': return t('completed');
    case 'running':
      if (job.stage === 'complete') return t('running');
      const stage = stageIndex[job.stage];
      return stage === undefined ? t('running') : stages[stage].label(t, locale, job);
  }
}

export function TranslationActivity({
  job,
  colors,
  t,
  locale,
  active,
}: {
  job: JobSnapshot;
  colors: Palette;
  t: Translator;
  locale: string;
  active: boolean;
}) {
  const confetti = useRef<ConfettiHandle>(null);
  const transition = useRef({ jobId: job.id, status: job.status, pending: false, fired: false });
  type PreviewState = { identity: string; phase: 'loading' } | { identity: string; phase: 'error' } | { identity: string; phase: 'ready'; uri?: string };
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [retryNonce, setRetryNonce] = useState(0);
  const isFinished = job.status === 'completed' || job.status === 'partial';
  const previewIdentity = JSON.stringify(isFinished
    ? [job.id, job.status, job.documentId, job.documentFormat ?? 'pdf', job.updatedAt, job.completedPages, job.totalPages]
    : [job.id, job.documentId, job.documentFormat ?? 'pdf']);
  const previewAllowed = job.documentFormat !== 'docx' || active;
  const previewRef = useRef(previewIdentity);
  previewRef.current = previewIdentity;

  useEffect(() => {
    let live = true;
    if (!previewAllowed) return () => { live = false; };
    setPreview({ identity: previewIdentity, phase: 'loading' });
    const load = async () => {
      try {
        let uri: string | undefined;
        if (isFinished) {
          if (job.documentFormat === 'docx') {
            const rendered = await PdfTranslator.renderDocxPreview(job.documentId, job.id, true);
            if (rendered.pageCount < 1) throw new Error('No translated pages');
          }
          uri = await PdfTranslator.renderTranslatedPage(job.id, 0, 420);
          if (!uri) throw new Error('Translated preview unavailable');
        } else if (job.documentFormat === 'docx') {
          const rendered = await PdfTranslator.renderDocxPreview(job.documentId, null, false);
          if (rendered.pageCount > 0) uri = await PdfTranslator.renderPage(job.documentId, 0, 420);
        } else {
          uri = await PdfTranslator.renderPage(job.documentId, 0, 420);
        }
        if (live) setPreview({ identity: previewIdentity, phase: 'ready', uri });
      } catch {
        if (live) setPreview({ identity: previewIdentity, phase: isFinished ? 'error' : 'ready' });
      }
    };
    load();
    return () => { live = false; };
  }, [isFinished, job.documentFormat, job.documentId, job.id, previewAllowed, previewIdentity, retryNonce]);

  useEffect(() => {
    const previous = transition.current;
    if (previous.jobId !== job.id) {
      transition.current = { jobId: job.id, status: job.status, pending: false, fired: false };
      return;
    }
    const completedNow = previous.status !== 'completed' && job.status === 'completed';
    previous.status = job.status;
    if (completedNow && !previous.fired) {
      if (active) {
        confetti.current?.burst();
        previous.fired = true;
      } else {
        previous.pending = true;
      }
    }
    if (active && previous.pending && !previous.fired) {
      confetti.current?.burst();
      previous.pending = false;
      previous.fired = true;
    }
  }, [active, job.id, job.status]);

  const total = Math.max(0, job.totalPages);
  const completedPages = Math.max(0, Math.min(total, job.completedPages));
  const progress = total > 0 ? completedPages / total : 0;
  const progressUnit = job.progressUnit === 'segment' ? ux(locale, 'segments') : t('pageProgress');
  const timeline = translationActivityTimeline(job, t, locale);
  const running = job.status === 'running';
  const currentPreview = preview?.identity === previewIdentity ? preview : null;
  const previewUri = currentPreview?.phase === 'ready' ? currentPreview.uri : undefined;
  const mutedCaptionStyle = { color: colors.muted, fontSize: 13 } as const;
  const fallbackTextStyle = { color: colors.muted, fontSize: 14 } as const;
  const statusTitleStyle = { color: job.status === 'failed' ? colors.accent : colors.text, fontWeight: '700' as const };
  const statusErrorStyle = { color: colors.muted } as const;

  const content = (
    <View style={[styles.content, { direction: localeDirection(locale) }]}>
      {isFinished ? currentPreview?.phase === 'ready' && previewUri ? (
        <Image
          accessible
          accessibilityRole="image"
          accessibilityLabel={`${job.documentName || t('document')}, ${activityLabel(job, t, locale)}`}
          source={{ uri: previewUri }}
          resizeMode="contain"
          onError={() => {
            if (previewRef.current === previewIdentity) setPreview({ identity: previewIdentity, phase: 'error' });
          }}
          style={[styles.resultPreview, { backgroundColor: colors.raised }]}
        />
      ) : currentPreview?.phase === 'error' ? (
          <Notice colors={colors} text={t('unknownError')} retry={() => setRetryNonce(value => value + 1)} retryLabel={t('retry')} />
      ) : (
        <View accessible accessibilityRole="progressbar" accessibilityLabel={t('loading')} style={[styles.resultPlaceholder, { backgroundColor: colors.raised }]}>
          <Icon name="document" size={42} color={colors.accent} />
          <Text style={fallbackTextStyle}>{t('loading')}</Text>
        </View>
      ) : (
        <ScanProgress
          source={previewUri ? { uri: previewUri } : undefined}
          imageLabel={job.documentName}
          height={212}
          progress={progress}
          done={false}
          label={activityLabel(job, t, locale)}
          doneLabel={t('completed')}
          tint={colors.accent}
          active={active && running}
          reduceEffects
        >
          {!previewUri ? (
            <View style={[styles.fallback, { backgroundColor: colors.raised }]}>
              <Icon name="document" size={42} color={colors.accent} />
              <Text style={fallbackTextStyle} numberOfLines={2}>
                {job.documentName || t('document')}
              </Text>
            </View>
          ) : null}
        </ScanProgress>
      )}

      <Text style={mutedCaptionStyle}>
        {progressUnit}: {completedPages} / {total}
      </Text>
      {job.documentFormat !== 'docx' && job.currentPage !== null ? (
        <Text style={mutedCaptionStyle}>
          {t('page')} {ux(locale, 'pageOf', { page: job.currentPage + 1, total: job.totalPages })}
        </Text>
      ) : null}
      <Timeline events={timeline} playKey={job.id} currentLabel={t('running')} upcomingLabel={t('stageQueued')} />
      {job.status !== 'running' && job.status !== 'queued' ? (
        <View style={[styles.status, { backgroundColor: colors.raised }]}>
          <Text style={statusTitleStyle}>
            {activityLabel(job, t, locale)}
          </Text>
          {job.error ? <Text style={statusErrorStyle}>{job.error}</Text> : null}
        </View>
      ) : null}
    </View>
  );

  // The burst is only useful after a successful result. Keeping its Skia
  // atlas mounted during long-running jobs adds a full-screen render surface
  // to every progress frame on resource-constrained devices.
  return isFinished ? (
    <ConfettiBurst
      ref={confetti}
      style={styles.confetti}
      colors={[colors.accent, colors.success, colors.accent, colors.muted]}
      haptics
    >
      {content}
    </ConfettiBurst>
  ) : (
    <View style={styles.confetti}>{content}</View>
  );
}

const styles = StyleSheet.create({
  confetti: { flex: 1 },
  content: { gap: 16, padding: 18 },
  fallback: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10, padding: 20 },
  resultPreview: { width: '100%', height: 212, borderRadius: 16 },
  resultPlaceholder: { height: 212, borderRadius: 16, alignItems: 'center', justifyContent: 'center', gap: 10, padding: 20 },
  status: { gap: 5, padding: 14, borderRadius: 14 },
});
