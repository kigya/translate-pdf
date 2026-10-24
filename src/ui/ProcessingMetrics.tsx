import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { JobSnapshot } from '../native/PdfTranslator';
import { processingUx } from '../i18n/processingUx';
import { Palette } from './theme';

/** Time is a duration label only; it never drives page/block progress. */
export function ProcessingMetrics({ job, active, locale, colors }: { job: JobSnapshot; active: boolean; locale: string; colors: Palette }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!active || job.status !== 'running' || !job.stageStartedAt) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active, job.stageStartedAt, job.status]);
  const elapsed = Math.max(0, Math.floor((now - (job.stageStartedAt ?? now)) / 1000));
  return <View style={{ gap: 5 }}>
    {job.workerStopping ? <Text accessibilityLiveRegion="polite" style={{ color: colors.accent }}>{processingUx(locale, 'pausing')}</Text> : null}
    {job.pageBlocks != null && job.pageBlocks > 0 ? <Text style={{ color: colors.muted, fontSize: 12 }}>{processingUx(locale, 'currentBlock')}: {Math.min(job.pageBlocks, (job.currentBlock ?? 0) + 1)} / {job.pageBlocks}</Text> : null}
    {job.processedTokens != null && job.status === 'running' && job.stage === 'translating' ? <Text style={{ color: colors.muted, fontSize: 12 }}>{processingUx(locale, 'activity')}: {job.processedTokens}</Text> : null}
    {job.stageStartedAt && job.status === 'running' ? <Text style={{ color: colors.muted, fontSize: 12 }}>{processingUx(locale, 'elapsed')}: {Math.floor(elapsed / 60)}:{String(elapsed % 60).padStart(2, '0')}</Text> : null}
  </View>;
}
