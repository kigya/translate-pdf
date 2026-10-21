import React, { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ConfettiBurst, type ConfettiHandle } from '../components/kinetik/confetti-burst';
import { ScanProgress } from '../components/kinetik/scan-progress';
import { Timeline, type TimelineEvent } from '../components/kinetik/timeline';
import { PdfTranslator, type JobSnapshot, type JobStage } from '../native/PdfTranslator';
import { localeDirection, Translator } from '../i18n/messages';
import { ux } from '../i18n/ux';
import { Icon } from './Controls';
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
  extracting: 1,
  ocr: 1,
  translating: 2,
  layout: 3,
  exporting: 3,
  complete: 4,
};

export function translationActivityTimeline(job: JobSnapshot, t: Translator, locale = 'en'): TimelineEvent[] {
  const current = job.status === 'completed'
    ? 4
    : job.status === 'queued'
      ? 0
      : stageIndex[job.stage] ?? -1;
  const safeCurrent = current === 4 && job.status !== 'completed' ? 3 : current;
  return stages.map((stage, index) => ({
    key: stage.key,
    title: stage.label(t, locale, job),
    time: '',
    state: job.status === 'completed'
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
  const [preview, setPreview] = useState<{ documentId: string; uri: string } | null>(null);

  useEffect(() => {
    let live = true;
    setPreview(null);
    const sourcePage = job.documentFormat === 'docx'
      ? PdfTranslator.renderDocxPreview(job.documentId, null, false)
          .then(() => PdfTranslator.renderPage(job.documentId, 0, 420))
      : PdfTranslator.renderPage(job.documentId, 0, 420);
    sourcePage
      .then((uri) => { if (live && uri) setPreview({ documentId: job.documentId, uri }); })
      .catch(() => { if (live) setPreview(null); });
    return () => { live = false; };
  }, [job.documentId, job.documentFormat]);

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
  const completed = Math.max(0, Math.min(total, job.completedPages));
  const progress = total > 0 ? completed / total : 0;
  const progressUnit = job.progressUnit === 'segment' ? ux(locale, 'segments') : t('pageProgress');
  const timeline = translationActivityTimeline(job, t, locale);
  const running = job.status === 'running';
  const previewUri = preview?.documentId === job.documentId ? preview.uri : null;
  const mutedCaptionStyle = { color: colors.muted, fontSize: 13 } as const;
  const fallbackTextStyle = { color: colors.muted, fontSize: 14 } as const;
  const statusTitleStyle = { color: job.status === 'failed' ? colors.accent : colors.text, fontWeight: '700' as const };
  const statusErrorStyle = { color: colors.muted } as const;

  return (
    <ConfettiBurst
      ref={confetti}
      style={styles.confetti}
      colors={[colors.accent, colors.success, colors.accent, colors.muted]}
      haptics
    >
      <View style={[styles.content, { direction: localeDirection(locale) }]}>
        <ScanProgress
          source={previewUri ? { uri: previewUri } : undefined}
          imageLabel={job.documentName}
          height={212}
          progress={progress}
          done={job.status === 'completed'}
          label={activityLabel(job, t, locale)}
          doneLabel={t('completed')}
          tint={colors.accent}
          active={active && running}
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

        <Text style={mutedCaptionStyle}>
          {progressUnit}: {completed} / {total}
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
    </ConfettiBurst>
  );
}

const styles = StyleSheet.create({
  confetti: { flex: 1 },
  content: { gap: 16, padding: 18 },
  fallback: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10, padding: 20 },
  status: { gap: 5, padding: 14, borderRadius: 14 },
});
