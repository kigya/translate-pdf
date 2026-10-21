import React, { useEffect, useState } from 'react';
import { Image, ScrollView, Text, View } from 'react-native';
import { JobSnapshot, PdfTranslator } from '../native/PdfTranslator';
import { Button, Notice, Row } from '../ui/components';
import { Palette } from '../ui/theme';
import { Translator } from '../i18n/messages';
import { ux } from '../i18n/ux';

/** Word text segments and rendered pages have independent counts and pagination. */
export function WordResultScreen({ job, colors, t, locale }: { job: JobSnapshot; colors: Palette; t: Translator; locale: string }) {
  const [original, setOriginal] = useState(false);
  const [page, setPage] = useState(0);
  const [count, setCount] = useState(0);
  const [uri, setUri] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [zoom, setZoom] = useState(1);
  useEffect(() => {
    let live = true;
    setUri(''); setError('');
    PdfTranslator.renderDocxPreview(job.documentId, job.id, !original).then(async preview => {
      if (!live) return;
      setCount(preview.pageCount);
      const bounded = Math.max(0, Math.min(page, preview.pageCount - 1));
      if (bounded !== page) { setPage(bounded); return; }
      const image = original ? await PdfTranslator.renderPage(job.documentId, bounded, 1100) : await PdfTranslator.renderTranslatedPage(job.id, bounded, 1100);
      if (live) setUri(image);
    }).catch(() => { if (live) setError(t('unknownError')); });
    return () => { live = false; };
  }, [job.id, job.documentId, original, page, t]);
  const save = async (format: 'docx' | 'pdf') => {
    if (busy) return;
    setBusy(true); setError('');
    try { await PdfTranslator.exportDocument(job.id, format); }
    catch { setError(t('unknownError')); }
    finally { setBusy(false); }
  };
  return <ScrollView contentContainerStyle={{ padding: 20, gap: 16 }}>
    <Text style={{ color: colors.muted }}>{ux(locale, 'wordPreviewNotice')}</Text>
    <Row><Button label={ux(locale, original ? 'target' : 'original')} colors={colors} variant="secondary" onPress={() => { setOriginal(value => !value); setPage(0); }} /><Button label={ux(locale, zoom < 3 ? 'zoomIn' : 'zoomOut')} colors={colors} variant="quiet" onPress={() => setZoom(value => value < 3 ? value + 1 : 1)} /></Row>
    <ScrollView horizontal maximumZoomScale={3} minimumZoomScale={1}>
      {uri ? <Image accessibilityLabel={`${ux(locale, original ? 'original' : 'target')} ${page + 1}`} source={{ uri }} style={{ width: 330 * zoom, height: 470 * zoom, backgroundColor: colors.surface }} resizeMode="contain" /> : <View style={{ width: 330 * zoom, height: 470 * zoom, justifyContent: 'center', alignItems: 'center' }}><Text style={{ color: colors.muted }}>{error || t('loading')}</Text></View>}
    </ScrollView>
    <Row style={{ justifyContent: 'space-between' }}><Button label={t('previous')} colors={colors} variant="quiet" disabled={page <= 0} onPress={() => setPage(value => value - 1)} /><Text style={{ color: colors.text }}>{count > 0 ? page + 1 : 0} / {count}</Text><Button label={t('next')} colors={colors} variant="quiet" disabled={page >= count - 1} onPress={() => setPage(value => value + 1)} /></Row>
    {(job.warnings ?? []).map((warning, index) => <Notice key={index} colors={colors} tone="info" text={typeof warning === 'string' ? warning : warning.message} />)}
    {job.status === 'partial' ? <Notice colors={colors} tone="info" text={t('issues')} /> : null}
    <Button label={ux(locale, 'exportDocx')} colors={colors} loading={busy} onPress={() => void save('docx')} />
    <Button label={ux(locale, 'exportPdf')} colors={colors} disabled={busy} variant="secondary" onPress={() => void save('pdf')} />
    <Notice colors={colors} text={error} />
  </ScrollView>;
}
