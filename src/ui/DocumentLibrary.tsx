import { BookPocket } from '../components/kinetik/book-pocket';
import { EmptyState } from '../components/kinetik/empty-state';
import { IconButton, SettingsRow, Sheet } from './Controls';
import { SwatchPicker } from '../components/kinetik/swatch-picker';
import { library050Ux, type Library050Key } from '../i18n/library050Ux';
import { libraryUx } from '../i18n/libraryUx';
import { modelPresentation } from '../domain/presentation';
import type { BootstrapSnapshot, DocumentColor, DocumentSnapshot, JobSnapshot, LibrarySort, ModelSnapshot } from '../native/PdfTranslator';
import type { Locale, Translator } from '../i18n/messages';
import type { Palette } from './theme';
import { Button, ProgressBar, Row } from './components';
import { ux } from '../i18n/ux';
import React, { useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';

export type LibraryFolder = 'all' | 'inProgress' | 'completed' | DocumentColor;
export type DocumentLibraryProps = {
  colors: Palette; t: Translator; locale: Locale; busy?: boolean;
  snapshot: BootstrapSnapshot; model?: ModelSnapshot | null;
  folder?: LibraryFolder | null; onFolderChange?: (folder: LibraryFolder | null) => void;
  onTranslate: () => void; onModel: () => void; onJob: (job: JobSnapshot) => void;
  onDocument: (document: DocumentSnapshot) => void;
  onSetColor?: (job: JobSnapshot, color: DocumentColor | null) => Promise<unknown>;
  onSort?: (sort: LibrarySort) => void; onDeleteJob?: (job: JobSnapshot) => void;
  onDeleteDocument?: (document: DocumentSnapshot) => void; onSettings?: () => void;
};
const COLORS: readonly DocumentColor[] = ['red', 'orange', 'yellow', 'green', 'blue', 'purple'];
const UNFINISHED = new Set<JobSnapshot['status']>(['queued', 'running', 'paused', 'failed']);
const COMPLETE = new Set<JobSnapshot['status']>(['completed', 'partial']);
const COLOR_HEX: Record<DocumentColor, readonly [string, string]> = {
  red: ['#C62828', '#7A1919'], orange: ['#E87524', '#8B3D13'], yellow: ['#D8A900', '#756000'],
  green: ['#27835A', '#155137'], blue: ['#3976C5', '#204273'], purple: ['#8055B8', '#4A2E71'],
};
const label = (locale: Locale, key: Library050Key) => library050Ux(locale, key);
const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();

export function visibleLibraryJobs(jobs: JobSnapshot[], query: string, sort: LibrarySort, folder?: LibraryFolder | null) {
  const filtered = jobs.filter(job => job.status !== 'canceled' &&
    (!folder || folder === 'all' || folder === 'inProgress' && UNFINISHED.has(job.status) || folder === 'completed' && COMPLETE.has(job.status) || COLORS.includes(folder as DocumentColor) && job.labelColor === folder) &&
    normalize(job.documentName).includes(normalize(query.trim())));
  const order = new Map(COLORS.map((color, index) => [color, index]));
  return filtered.sort((a, b) => sort === 'color' && !folder
    ? (a.labelColor ? order.get(a.labelColor)! : 99) - (b.labelColor ? order.get(b.labelColor)! : 99) || b.createdAt - a.createdAt
    : b.createdAt - a.createdAt);
}

function formatDate(value: number, locale: Locale) {
  try { return new Intl.DateTimeFormat(locale === 'system' ? undefined : locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); }
  catch { return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)); }
}
function shelfBooks(title: string, tones: readonly [string, string], count: number) {
  return count ? [{ id: title, title: count === 1 ? '•' : '•••', author: '', tones }] : [];
}
function jobStage(job: JobSnapshot, t: Translator) {
  if (job.status === 'failed') return t('unknownError');
  if (job.status === 'paused') return t('resume');
  if (job.status === 'queued') return t('stageQueued');
  return t(job.stage === 'ocr' ? 'stageOcr' : job.stage === 'extracting' ? 'stageExtracting' : job.stage === 'exporting' ? 'stageExporting' : job.stage === 'loadingModel' ? 'stageCopying' : 'stageTranslating');
}

type JobCardProps = {
  job: JobSnapshot; colors: Palette; locale: Locale; t: Translator; busy?: boolean;
  onJob: (job: JobSnapshot) => void; onOpenColor: (job: JobSnapshot) => void;
  onDeleteJob?: (job: JobSnapshot) => void;
};
function JobCard({ job, colors, locale, t, busy, onJob, onOpenColor, onDeleteJob }: JobCardProps) {
  const progress = job.totalPages > 0 ? Math.max(0, Math.min(1, job.completedPages / job.totalPages)) : 0;
  const canDelete = !!onDeleteJob && !job.workerStopping;
  return <View style={{ paddingVertical: 16, borderBottomWidth: 1, borderColor: colors.border, gap: 10 }}>
    <Pressable accessibilityRole="button" accessibilityLabel={job.documentName} onPress={() => onJob(job)} style={{ gap: 8 }}>
      <Text numberOfLines={2} style={{ color: colors.text, fontSize: 16, fontWeight: '700' }}>{job.documentName}</Text>
      <Text style={{ color: colors.muted, fontSize: 12 }}>{library050Ux(locale, 'created')}: {formatDate(job.createdAt, locale)}</Text>
      <Row style={{ justifyContent: 'space-between' }}><Text style={{ color: job.status === 'failed' ? colors.warning : colors.muted }}>{job.status === 'partial' ? library050Ux(locale, 'review') : jobStage(job, t)}</Text><Text style={{ color: colors.muted }}>{job.completedPages}/{job.totalPages} {t('pages')}</Text></Row>
      {UNFINISHED.has(job.status) ? <ProgressBar colors={colors} value={progress} label={t('progress')} /> : null}
    </Pressable>
    <Row style={{ justifyContent: 'space-between' }}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${libraryUx(locale, 'documentColor')}: ${job.documentName}, ${job.labelColor ? libraryUx(locale, job.labelColor) : libraryUx(locale, 'noColor')}`} onPress={() => onOpenColor(job)} style={{ minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <View style={{ width: 14, height: 14, borderRadius: 7, backgroundColor: job.labelColor ? COLOR_HEX[job.labelColor][0] : colors.border }} />
        <Text style={{ color: colors.muted }}>{libraryUx(locale, 'documentColor')}</Text>
      </Pressable>
      {canDelete ? <IconButton name="trash" label={`${library050Ux(locale, 'deleteJob')}: ${job.documentName}`} colors={colors} disabled={busy} onPress={() => onDeleteJob?.(job)} /> : null}
    </Row>
  </View>;
}
type ImportCardProps = { document: DocumentSnapshot; colors: Palette; locale: Locale; t: Translator; busy?: boolean; onDocument: (document: DocumentSnapshot) => void; onDeleteDocument?: (document: DocumentSnapshot) => void };
function ImportCard({ document, colors, locale, t, busy, onDocument, onDeleteDocument }: ImportCardProps) {
  return <View style={{ paddingVertical: 16, borderBottomWidth: 1, borderColor: colors.border, gap: 8 }}>
    <Pressable accessibilityRole="button" onPress={() => onDocument(document)} style={{ gap: 7 }}>
      <Text numberOfLines={2} style={{ color: colors.text, fontSize: 16, fontWeight: '700' }}>{document.name}</Text>
      <Text style={{ color: colors.muted, fontSize: 12 }}>{library050Ux(locale, 'created')}: {formatDate(document.importedAt, locale)}</Text>
      <Text style={{ color: colors.muted }}>{document.format === 'docx' ? document.segmentCount ?? document.pageCount : document.pageCount} {document.format === 'docx' ? ux(locale, 'segments') : t('pages')} · {library050Ux(locale, 'imports')}</Text>
    </Pressable>
    <Row style={{ justifyContent: 'space-between' }}>
      <Button colors={colors} label={ux(locale, 'translatePdf')} onPress={() => onDocument(document)} />
      {onDeleteDocument ? <IconButton name="trash" label={`${library050Ux(locale, 'deleteImport')}: ${document.name}`} colors={colors} disabled={busy} onPress={() => onDeleteDocument(document)} /> : null}
    </Row>
  </View>;
}

export function DocumentLibrary({ colors, t, locale, busy, snapshot, model, folder, onFolderChange, onTranslate, onModel, onJob, onDocument, onSetColor, onSort, onDeleteJob, onDeleteDocument, onSettings }: DocumentLibraryProps) {
  const [query, setQuery] = useState('');
  const [colorJob, setColorJob] = useState<JobSnapshot | null>(null);
  const [colorError, setColorError] = useState('');
  const [savingColor, setSavingColor] = useState(false);
  const jobs = useMemo(() => snapshot.jobs.filter(job => job.status !== 'canceled'), [snapshot.jobs]);
  const jobByDocument = new Set(snapshot.jobs.map(job => job.documentId));
  const imports = snapshot.documents.filter(document => !jobByDocument.has(document.id));
  const activeCount = jobs.filter(job => UNFINISHED.has(job.status)).length;
  const completeCount = jobs.filter(job => COMPLETE.has(job.status)).length;
  const allCount = jobs.length + imports.length;
  const root = folder == null;
  const title = !folder ? '' : folder === 'all' ? label(locale, 'all') : folder === 'inProgress' ? label(locale, 'inProgress') : folder === 'completed' ? label(locale, 'completed') : libraryUx(locale, folder);
  const relevant = visibleLibraryJobs(jobs, query, snapshot.preferences.librarySort ?? 'recent', folder);
  const unfinished = relevant.filter(job => UNFINISHED.has(job.status));
  const completed = relevant.filter(job => COMPLETE.has(job.status));
  const folderImports = folder === 'all' ? imports.filter(doc => normalize(doc.name).includes(normalize(query.trim()))) : [];
  const colorNames = COLORS.map(color => ({ value: color, label: libraryUx(locale, color) }));
  const openColor = (job: JobSnapshot) => { setColorError(''); setColorJob(job); };
  const setColor = async (color: DocumentColor | null) => {
    if (!colorJob || !onSetColor || savingColor) return;
    setSavingColor(true);
    try { await onSetColor(colorJob, color); setColorJob(null); }
    catch { setColorError(t('unknownError')); }
    finally { setSavingColor(false); }
  };

  return <View style={{ flex: 1, backgroundColor: colors.background }}>
    {root ? <ScrollView contentContainerStyle={{ padding: 20, gap: 14, paddingBottom: 34 }}>
      <Row style={{ justifyContent: 'space-between', alignItems: 'center' }}><Text accessibilityRole="header" style={{ color: colors.text, fontSize: 27, fontWeight: '800' }}>Folivela</Text>{onSettings ? <IconButton name="settings" label={label(locale, 'settings')} colors={colors} onPress={onSettings} /> : null}</Row>
      <Pressable accessibilityRole="button" onPress={onTranslate} style={{ padding: 18, borderRadius: 20, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, gap: 8 }}>
        <Text style={{ color: colors.text, fontWeight: '700', fontSize: 16 }}>{ux(locale, 'translatePdf')}</Text>
      </Pressable>
      <SettingsRow icon="storage" label={t('model')} value={model ? model.id === 'mymemory' ? label(locale, 'online') : ux(locale, modelPresentation(model).title) : t('chooseModel')} onPress={onModel} colors={colors} />
      <View><TextInput accessibilityLabel={libraryUx(locale, 'searchDocuments')} placeholder={libraryUx(locale, 'searchDocuments')} placeholderTextColor={colors.muted} selectionColor={colors.accent} value={query} onChangeText={setQuery} style={{ color: colors.text, backgroundColor: colors.surface, borderRadius: 14, borderWidth: 1, borderColor: colors.border, padding: 14 }} />{query ? <Pressable accessibilityRole="button" accessibilityLabel={libraryUx(locale, 'clearSearch')} onPress={() => setQuery('')} style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-end', paddingHorizontal: 10 }}><Text style={{ color: colors.accent }}>{libraryUx(locale, 'clearSearch')}</Text></Pressable> : null}</View>
      <BookPocket title={label(locale, 'all')} books={shelfBooks(label(locale, 'all'), ['#365846','#152E24'], allCount)} count={allCount} unit={label(locale, 'documents')} onPress={() => onFolderChange?.('all')} />
      <BookPocket title={label(locale, 'inProgress')} books={shelfBooks(label(locale, 'inProgress'), ['#A85831','#54271C'], activeCount)} count={activeCount} unit={label(locale, 'documents')} onPress={() => onFolderChange?.('inProgress')} />
      <BookPocket title={label(locale, 'completed')} books={shelfBooks(label(locale, 'completed'), ['#476B9B','#202D46'], completeCount)} count={completeCount} unit={label(locale, 'documents')} onPress={() => onFolderChange?.('completed')} />
      {COLORS.map(color => {
        const count = jobs.filter(job => job.labelColor === color).length;
        return <BookPocket key={color} title={libraryUx(locale, color)} books={shelfBooks(color, COLOR_HEX[color], count)} count={count} unit={label(locale, 'documents')} onPress={() => onFolderChange?.(color)} />;
      })}
    </ScrollView> : <>
      <Row style={{ paddingHorizontal: 16, paddingTop: 8, gap: 10, alignItems: 'center' }}>
        <IconButton name="arrow" label={t('back')} colors={colors} onPress={() => onFolderChange?.(null)} />
        <Text accessibilityRole="header" style={{ color: colors.text, fontSize: 22, fontWeight: '800', flex: 1 }}>{title}</Text>
        {onSort ? <Pressable accessibilityRole="button" accessibilityLabel={libraryUx(locale, 'sortDocuments')} onPress={() => onSort(snapshot.preferences.librarySort === 'color' ? 'recent' : 'color')}><Text style={{ color: colors.accent }}>{snapshot.preferences.librarySort === 'color' ? libraryUx(locale, 'sortColor') : libraryUx(locale, 'sortRecent')}</Text></Pressable> : null}
      </Row>
      <View style={{ paddingHorizontal: 20, paddingVertical: 8 }}><TextInput accessibilityLabel={libraryUx(locale, 'searchDocuments')} placeholder={libraryUx(locale, 'searchDocuments')} placeholderTextColor={colors.muted} value={query} onChangeText={setQuery} style={{ color: colors.text, backgroundColor: colors.surface, borderRadius: 14, borderWidth: 1, borderColor: colors.border, padding: 13 }} />{query ? <Pressable accessibilityRole="button" accessibilityLabel={libraryUx(locale, 'clearSearch')} onPress={() => setQuery('')} style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-end', paddingHorizontal: 10 }}><Text style={{ color: colors.accent }}>{libraryUx(locale, 'clearSearch')}</Text></Pressable> : null}</View>
      <ScrollView contentContainerStyle={{ paddingHorizontal: 20, paddingBottom: 32 }}>
        {unfinished.length ? <><Text style={{ color: colors.text, fontWeight: '700', marginTop: 10 }}>{label(locale, 'inProgress')}</Text>{unfinished.map(job => <JobCard key={job.id} job={job} colors={colors} locale={locale} t={t} busy={busy} onJob={onJob} onOpenColor={openColor} onDeleteJob={onDeleteJob} />)}</> : null}
        {folderImports.length ? <><Text style={{ color: colors.text, fontWeight: '700', marginTop: 22 }}>{label(locale, 'imports')}</Text>{folderImports.map(document => <ImportCard key={document.id} document={document} colors={colors} locale={locale} t={t} busy={busy} onDocument={onDocument} onDeleteDocument={onDeleteDocument} />)}</> : null}
        {completed.length ? <><Text style={{ color: colors.text, fontWeight: '700', marginTop: 22 }}>{label(locale, 'completed')}</Text>{completed.map(job => <JobCard key={job.id} job={job} colors={colors} locale={locale} t={t} busy={busy} onJob={onJob} onOpenColor={openColor} onDeleteJob={onDeleteJob} />)}</> : null}
        {!unfinished.length && !folderImports.length && !completed.length ? <EmptyState title={query ? libraryUx(locale, 'noDocumentsFound') : label(locale, 'empty')} description={query ? undefined : title} action={folder === 'all' && !query ? { label: ux(locale, 'translatePdf'), onPress: onTranslate } : undefined} /> : null}
      </ScrollView>
    </>}
    <Sheet visible={!!colorJob} title={libraryUx(locale, 'documentColor')} onClose={() => { if (!savingColor) setColorJob(null); }} colors={colors} closeLabel={t('back')}>
      {colorJob ? <View style={{ gap: 14 }}><Text style={{ color: colors.text }}>{colorJob.documentName}</Text>{colorError ? <Text accessibilityRole="alert" style={{ color: colors.warning }}>{colorError}</Text> : null}<SwatchPicker accessibilityLabel={libraryUx(locale, 'documentColor')} value={colorJob.labelColor ?? ''} swatches={[{ value: '', label: libraryUx(locale, 'noColor') }, ...colorNames]} onValueChange={value => { if (!savingColor) void setColor((value || null) as DocumentColor | null); }} /></View> : null}
    </Sheet>
  </View>;
}
