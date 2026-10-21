import React, { useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { BootstrapSnapshot, DocumentColor, DocumentSnapshot, JobSnapshot, LibrarySort, ModelSnapshot } from '../native/PdfTranslator';
import { Translator, localeDirection } from '../i18n/messages';
import { ux } from '../i18n/ux';
import { libraryUx } from '../i18n/libraryUx';
import { SearchMorph } from '../components/kinetik/search-morph';
import { EmptyState } from '../components/kinetik/empty-state';
import { MorphSelect } from '../components/kinetik/morph-select';
import { SwatchPicker } from '../components/kinetik/swatch-picker';
import { TipBubble } from '../components/kinetik/tip-bubble';
import { ScrambleText } from '../components/kinetik/scramble-text';
import { Palette } from './theme';
import { Button, Notice, Row } from './components';
import { Icon, SettingsRow, Sheet } from './Controls';
import { translationLanguageName } from '../domain/translationLanguages';

export const documentColors: ReadonlyArray<{ id: DocumentColor; hex: string }> = [
  { id: 'red', hex: '#E64A4A' }, { id: 'orange', hex: '#E79435' }, { id: 'yellow', hex: '#E1C347' },
  { id: 'green', hex: '#48A779' }, { id: 'blue', hex: '#558FDC' }, { id: 'purple', hex: '#AC78D8' },
];
const normalized = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
export function visibleLibraryJobs(jobs: JobSnapshot[], query: string, sort: LibrarySort): JobSnapshot[] {
  return jobs.filter(job => normalized(job.documentName).includes(normalized(query.trim()))).sort((a, b) => {
    if (sort === 'color') {
      const rank = (job: JobSnapshot) => { const found = documentColors.findIndex(c => c.id === job.labelColor); return found < 0 ? documentColors.length : found; };
      const delta = rank(a) - rank(b);
      if (delta) return delta;
    }
    return b.createdAt - a.createdAt || a.id.localeCompare(b.id);
  });
}
export type DocumentLibraryProps = {
  colors: Palette; t: Translator; locale: string; busy?: boolean; snapshot: BootstrapSnapshot; model?: ModelSnapshot | null;
  onTranslate: () => void; onModel: () => void; onJob: (job: JobSnapshot) => void; onDocument: (doc: DocumentSnapshot) => void;
  onSetColor?: (job: JobSnapshot, color: DocumentColor | null) => Promise<unknown>;
  onSort?: (sort: LibrarySort) => void;
};
export function DocumentLibrary({ colors, t, locale, busy, snapshot, model, onTranslate, onModel, onJob, onDocument, onSetColor, onSort }: DocumentLibraryProps) {
  const [query, setQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [colorJob, setColorJob] = useState<JobSnapshot | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const sort = snapshot.preferences.librarySort ?? 'recent';
  const l = (key: Parameters<typeof libraryUx>[1]) => libraryUx(locale, key);
  const jobs = useMemo(() => visibleLibraryJobs(snapshot.jobs, query, sort), [snapshot.jobs, query, sort]);
  const documents = snapshot.documents.filter(doc => !snapshot.jobs.some(job => job.documentId === doc.id) && normalized(doc.name).includes(normalized(query.trim())));
  const empty = !snapshot.jobs.length && !snapshot.documents.length;
  const saveColor = async (color: DocumentColor | null) => {
    if (!colorJob || !onSetColor || saving) return;
    setSaving(true); setError('');
    try { await onSetColor(colorJob, color); setColorJob(null); }
    catch { setError(t('unknownError')); }
    finally { setSaving(false); }
  };
  return <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ padding: 24, gap: 22, paddingBottom: 36 }}>
    {!searchOpen ? <>
    <Row><Icon name="document" size={40} color={colors.accent} /><Text style={{ color: colors.text, fontSize: 32, fontWeight: '800', letterSpacing: -1 }}>{t('appName')}</Text></Row>
    {!empty ? <Button label={ux(locale, 'translatePdf')} onPress={onTranslate} colors={colors} loading={busy} /> : null}
    <SettingsRow icon="globe" label={ux(locale, 'modelOnline')} value={ux(locale, 'onlineAvailable')} colors={colors} onPress={onModel} />
    <Pressable accessibilityRole="button" accessibilityLabel={t('useModel')} onPress={onModel} style={{ paddingVertical: 14, borderBottomWidth: 0.5, borderColor: colors.border, gap: 8 }}>
      <Text style={{ color: colors.muted, fontSize: 13 }}>{t('model')}</Text>
      <Row style={{ justifyContent: 'space-between' }}><View style={{ flex: 1 }}><ScrambleText text={model ? model.id === 'mymemory' ? ux(locale, 'modelOnline') : model.label : t('chooseModel')} duration={480} style={{ color: colors.text, fontSize: 18, fontWeight: '700' }} /></View><Icon name="chevron" color={colors.muted} size={18} /></Row>
      {model?.id === 'mymemory' ? <Text style={{ color: colors.muted }}>{ux(locale, 'onlineAvailable')}</Text> : null}
    </Pressable>
    </> : null}
    {empty ? <EmptyState title={t('emptyTitle')} description={ux(locale, 'emptyLibrary')} icon={<Icon name="document" color={colors.accent} size={32} />} action={{ label: ux(locale, 'translatePdf'), onPress: onTranslate }} /> : <>
      <Row style={{ justifyContent: 'space-between', alignItems: 'center' }}>{!searchOpen ? <Text style={{ color: colors.text, fontSize: 18, fontWeight: '700' }}>{t('recent')}</Text> : null}<View style={{ flex: 1 }}><SearchMorph open={searchOpen} onOpenChange={setSearchOpen} value={query} onChangeText={setQuery} placeholder={l('searchDocuments')} cancelLabel={ux(locale, 'cancel')} clearLabel={`${l('clearSearch')}: ${l('searchDocuments')}`} /></View></Row>
      <Row><View style={{ flex: 1 }}><MorphSelect label={l('sortDocuments')} value={sort} onValueChange={value => onSort?.(value as LibrarySort)} options={[{ value: 'recent', label: l('sortRecent') }, { value: 'color', label: l('sortColor') }]} /></View><TipBubble align={localeDirection(locale) === 'rtl' ? 'start' : 'end'} accessibilityLabel={l('documentColor')} content={l('colorHelp')}><View style={{ padding: 12 }}><Icon name="info" color={colors.accent} /></View></TipBubble></Row>
      {!jobs.length && !documents.length ? <EmptyState title={l('noDocumentsFound')} action={{ label: l('clearSearch'), onPress: () => setQuery('') }} /> : null}
      {jobs.map(job => {
        const color = documentColors.find(c => c.id === job.labelColor);
        const terminal = job.status === 'completed' || job.status === 'partial';
        return <Row key={job.id} style={{ borderBottomWidth: 0.5, borderColor: colors.border }}>
          <Pressable accessibilityRole="button" accessibilityLabel={job.documentName} onPress={() => onJob(job)} style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: 14, paddingVertical: 18 }}>
            <Icon name="document" size={30} color={color?.hex ?? colors.accent} /><View style={{ flex: 1, gap: 5 }}><Text numberOfLines={2} style={{ color: colors.text, fontSize: 16, fontWeight: '600' }}>{job.documentName}</Text><Text style={{ color: colors.muted, fontSize: 12 }}>{job.completedPages}/{job.totalPages} · {translationLanguageName(job.sourceLanguage)} → {translationLanguageName(job.targetLanguage)}</Text>{color ? <Text style={{ color: colors.muted, fontSize: 12 }}>{l(color.id)}</Text> : null}</View>
          </Pressable>
          {terminal ? <Pressable accessibilityRole="button" accessibilityLabel={`${l('documentColor')}: ${job.documentName}, ${color ? l(color.id) : l('noColor')}`} onPress={() => { setError(''); setColorJob(job); }} style={{ minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}><View style={{ width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: colors.muted, backgroundColor: color?.hex ?? 'transparent' }} /></Pressable> : null}
        </Row>;
      })}
      {documents.map(doc => <SettingsRow key={doc.id} icon="document" label={doc.name} value={`${doc.format === 'docx' ? doc.segmentCount ?? doc.pageCount : doc.pageCount} ${doc.format === 'docx' ? ux(locale, 'segments') : t('pages')}`} onPress={() => onDocument(doc)} colors={colors} />)}
    </>}
    <Sheet visible={!!colorJob} title={l('documentColor')} onClose={() => { if (!saving) setColorJob(null); }} closeLabel={t('back')} colors={colors}>
      <Notice colors={colors} text={error} />
      <Text style={{ color: colors.text, fontWeight: '600' }}>{colorJob?.documentName}</Text>
      <SwatchPicker value={documentColors.find(c => c.id === colorJob?.labelColor)?.hex ?? ''} accessibilityLabel={l('documentColor')} swatches={documentColors.map(c => ({ value: c.hex, label: l(c.id) }))} onValueChange={hex => { const color = documentColors.find(c => c.hex === hex); if (color) void saveColor(color.id); }} />
      <Button label={l('noColor')} disabled={saving} colors={colors} variant="secondary" onPress={() => void saveColor(null)} />
    </Sheet>
  </ScrollView>;
}
