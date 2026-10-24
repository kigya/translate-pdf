import React from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native-legacy';

jest.mock('../src/components/kinetik/search-morph', () => {
  const RN = require('react-native');
  const R = require('react');
  return { SearchMorph: ({ value, onChangeText, placeholder, clearLabel, cancelLabel, open, onOpenChange }: { value: string; onChangeText: (s: string) => void; placeholder: string; clearLabel: string; cancelLabel: string; open: boolean; onOpenChange: (open: boolean) => void }) => R.createElement(RN.View, null,
    open ? R.createElement(RN.TextInput, { accessibilityLabel: placeholder, value, onChangeText }) : R.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: placeholder, onPress: () => onOpenChange(true) }, R.createElement(RN.Text, null, placeholder)),
    open ? R.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: cancelLabel, onPress: () => { onChangeText(''); onOpenChange(false); } }, R.createElement(RN.Text, null, cancelLabel)) : null,
    open && value ? R.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: clearLabel, onPress: () => onChangeText('') }, R.createElement(RN.Text, null, clearLabel)) : null,
  ) };
});
jest.mock('../src/components/kinetik/morph-select', () => {
  const RN = require('react-native');
  const R = require('react');
  return { MorphSelect: ({ label, value, options, onValueChange }: { label: string; value: string; options: { value: string; label: string }[]; onValueChange: (v: string) => void }) => R.createElement(RN.View, null,
    R.createElement(RN.Text, null, `${label}: ${value}`), ...options.map(option => R.createElement(RN.Pressable, { key: option.value, accessibilityRole: 'button', accessibilityLabel: option.label, onPress: () => onValueChange(option.value) }, R.createElement(RN.Text, null, option.label))),
  ) };
});
jest.mock('../src/components/kinetik/swatch-picker', () => {
  const RN = require('react-native');
  const R = require('react');
  return { SwatchPicker: ({ swatches, onValueChange }: { swatches: { value: string; label: string }[]; onValueChange: (v: string) => void }) => R.createElement(RN.View, null,
    ...swatches.map(swatch => R.createElement(RN.Pressable, { key: swatch.value, accessibilityRole: 'radio', accessibilityLabel: swatch.label, onPress: () => onValueChange(swatch.value) }, R.createElement(RN.Text, null, swatch.label))),
  ) };
});
jest.mock('../src/components/kinetik/empty-state', () => {
  const RN = require('react-native');
  const R = require('react');
  return { EmptyState: ({ title, description, action }: { title: string; description?: string; action?: { label: string; onPress: () => void } }) => R.createElement(RN.View, null,
    R.createElement(RN.Text, { accessibilityRole: 'header' }, title), description ? R.createElement(RN.Text, null, description) : null,
    action ? R.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: action.label, onPress: action.onPress }, R.createElement(RN.Text, null, action.label)) : null,
  ) };
});
jest.mock('../src/components/kinetik/scramble-text', () => {
  const RN = require('react-native');
  const R = require('react');
  return { ScrambleText: ({ text }: { text: string }) => R.createElement(RN.Text, { accessibilityRole: 'text' }, text) };
});
jest.mock('../src/components/kinetik/tip-bubble', () => ({ TipBubble: ({ children, align, accessibilityLabel }: { children: React.ReactNode; align: 'start' | 'end'; accessibilityLabel: string }) => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return ReactRuntime.createElement(RN.View, { testID: 'tip-bubble', accessibilityLabel, accessibilityHint: align }, children);
} }));
jest.mock('../src/components/kinetik/book-pocket', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return { BookPocket: ({ title, count, onPress }: { title: string; count: number; onPress: () => void }) => ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: title, onPress }, ReactRuntime.createElement(RN.Text, null, `${title} ${count}`)) };
});
jest.mock('../src/ui/Controls', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return {
    Icon: ({ name }: { name: string }) => ReactRuntime.createElement(RN.Text, null, name),
    IconButton: ({ label, onPress, disabled }: { label: string; onPress: () => void; disabled?: boolean }) => ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: label, disabled, onPress }, ReactRuntime.createElement(RN.Text, null, label)),
    SettingsRow: ({ label, value, onPress }: { label: string; value?: string; onPress: () => void }) => ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: label, onPress }, ReactRuntime.createElement(RN.Text, null, value ? `${label} ${value}` : label)),
    Sheet: ({ visible, title, onClose, children }: { visible: boolean; title: string; onClose: () => void; children: React.ReactNode }) => visible ? ReactRuntime.createElement(RN.View, { testID: 'color-picker-sheet' }, ReactRuntime.createElement(RN.Text, { accessibilityRole: 'header' }, title), ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: 'Close color picker', onPress: onClose }, ReactRuntime.createElement(RN.Text, null, 'Close')), children) : null,
  };
});
jest.mock('../src/ui/components', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return {
    Button: ({ label, onPress, disabled }: { label: string; onPress: () => void; disabled?: boolean }) => ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: label, disabled, onPress }, ReactRuntime.createElement(RN.Text, null, label)),
    ProgressBar: ({ value, label }: { value: number; label: string }) => ReactRuntime.createElement(RN.View, { accessibilityLabel: label, accessibilityValue: { min: 0, max: 1, now: value } }),
    Notice: ({ text }: { text?: string }) => text ? ReactRuntime.createElement(RN.Text, { accessibilityRole: 'alert' }, text) : null,
    Row: ({ children }: { children: React.ReactNode }) => ReactRuntime.createElement(RN.View, null, children),
  };
});

import { DocumentLibrary, visibleLibraryJobs } from '../src/ui/DocumentLibrary';
import type { BootstrapSnapshot, DocumentSnapshot, JobSnapshot, ModelSnapshot } from '../src/native/PdfTranslator';
import { createTranslator } from '../src/i18n/messages';
import { palettes } from '../src/ui/theme';
import { ux } from '../src/i18n/ux';
import { libraryUx } from '../src/i18n/libraryUx';
import { library050Ux } from '../src/i18n/library050Ux';

const t = createTranslator('en');
const palette = palettes.light;
const noop = () => {};
const baseSnapshot = (jobs: JobSnapshot[] = [], documents: DocumentSnapshot[] = [], librarySort: 'recent' | 'color' = 'recent') => ({
  schemaVersion: 6,
  preferences: { language: 'system', theme: 'system', librarySort },
  device: {}, models: [], jobs, documents, glossary: [], onboarding: { done: true },
} as unknown as BootstrapSnapshot);
const job = (id: string, documentName: string, createdAt: number, status: JobSnapshot['status'] = 'completed', labelColor?: JobSnapshot['labelColor']) => ({
  id, documentId: `doc-${id}`, documentName, modelId: 'local', sourceLanguage: 'en', targetLanguage: 'ru', status,
  stage: status === 'completed' ? 'complete' : 'translating', completedPages: 1, totalPages: 1, currentPage: null, createdAt, updatedAt: createdAt, labelColor,
} as JobSnapshot);
const imported = (id: string, name: string, importedAt: number): DocumentSnapshot => ({ id, name, pageCount: 12, importedAt, format: 'pdf' });
const library = (snapshot: BootstrapSnapshot, extra: Partial<React.ComponentProps<typeof DocumentLibrary>> = {}) => (
  <DocumentLibrary colors={palette} t={t} locale="en" snapshot={snapshot} folder="all" onTranslate={noop} onModel={noop} onJob={noop} onDocument={noop} {...extra} />
);

describe('Folivela document library', () => {
  it('filters filenames without case or accent sensitivity and offers a working clear action for no matches', () => {
    const docs = [job('1', 'Café pump.pdf', 1), job('2', 'Pump housing.docx', 2)];
    const view = render(library(baseSnapshot(docs)));
    expect(screen.queryByText('Recent documents')).toBeNull();
    fireEvent.changeText(screen.getByLabelText(libraryUx('en', 'searchDocuments')), ' cafe ');
    expect(screen.getByText('Café pump.pdf')).toBeOnTheScreen();
    expect(screen.queryByText('Pump housing.docx')).toBeNull();

    fireEvent.changeText(screen.getByLabelText(libraryUx('en', 'searchDocuments')), 'no-such-manual');
    expect(screen.getByText(libraryUx('en', 'noDocumentsFound'))).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'clearSearch') }));
    expect(screen.getByText('Café pump.pdf')).toBeOnTheScreen();
    view.unmount();
  });

  it('sorts color groups in product order, leaves unlabelled entries last, and orders ties newest-first', () => {
    const jobs = [job('old-red', 'Old red', 1, 'completed', 'red'), job('blue', 'Blue', 4, 'completed', 'blue'), job('new-red', 'New red', 9, 'partial', 'red'), job('none-old', 'No label old', 2), job('orange', 'Orange', 3, 'completed', 'orange'), job('none-new', 'No label new', 10)];
    expect(visibleLibraryJobs(jobs, '', 'color').map(item => item.id)).toEqual(['new-red', 'old-red', 'orange', 'blue', 'none-new', 'none-old']);
    expect(visibleLibraryJobs(jobs, 'RED', 'recent').slice(0, 2).map(item => item.id)).toEqual(['new-red', 'old-red']);
  });

  it('shows color controls for all non-canceled jobs', () => {
    const jobs = [job('done', 'Done.pdf', 4), job('partial', 'Partial.docx', 3, 'partial'), job('running', 'Running.pdf', 2, 'running'), job('failed', 'Failed.pdf', 1, 'failed')];
    render(library(baseSnapshot(jobs)));
    expect(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Done.pdf, ${libraryUx('en', 'noColor')}` })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Partial.docx, ${libraryUx('en', 'noColor')}` })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Running.pdf, ${libraryUx('en', 'noColor')}` })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Failed.pdf, ${libraryUx('en', 'noColor')}` })).toBeOnTheScreen();
  });

  it('keeps color picker open while saving, closes after success, and remains open with a local error on failure', async () => {
    let finishSave: (() => void) | undefined;
    const setColor = jest.fn(() => new Promise<void>(resolve => { finishSave = resolve; }));
    const view = render(library(baseSnapshot([job('done', 'Manual.pdf', 1)]), { onSetColor: setColor }));
    fireEvent.press(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Manual.pdf, ${libraryUx('en', 'noColor')}` }));
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'red') }));
    expect(setColor).toHaveBeenCalledWith(expect.objectContaining({ id: 'done' }), 'red');
    expect(screen.getByRole('header', { name: libraryUx('en', 'documentColor') })).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: libraryUx('en', 'noColor') })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'blue') }));
    fireEvent.press(screen.getByRole('button', { name: 'Close color picker' }));
    expect(setColor).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('color-picker-sheet')).toBeOnTheScreen();
    await act(async () => { finishSave?.(); });
    await waitFor(() => expect(screen.queryByRole('header', { name: libraryUx('en', 'documentColor') })).toBeNull());

    const clearSuccess = jest.fn().mockResolvedValue(undefined);
    view.rerender(library(baseSnapshot([job('clear', 'Clear label.pdf', 2, 'completed', 'red')]), { onSetColor: clearSuccess }));
    fireEvent.press(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Clear label.pdf, ${libraryUx('en', 'red')}` }));
    await act(async () => { fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'noColor') })); });
    expect(clearSuccess).toHaveBeenCalledWith(expect.objectContaining({ id: 'clear' }), null);
    await waitFor(() => expect(screen.queryByRole('header', { name: libraryUx('en', 'documentColor') })).toBeNull());

    let rejectSave: ((error: Error) => void) | undefined;
    const failed = jest.fn(() => new Promise<void>((_resolve, reject) => { rejectSave = reject; }));
    view.rerender(library(baseSnapshot([job('done2', 'Other.pdf', 2)]), { onSetColor: failed }));
    fireEvent.press(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Other.pdf, ${libraryUx('en', 'noColor')}` }));
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'blue') }));
    expect(screen.getByRole('header', { name: libraryUx('en', 'documentColor') })).toBeOnTheScreen();
    await act(async () => { rejectSave?.(new Error('database unavailable')); });
    const sheet = screen.getByTestId('color-picker-sheet');
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(t('unknownError'));
    expect(screen.getByRole('header', { name: libraryUx('en', 'documentColor') })).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'noColor') }));
    expect(failed).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'done2' }), null);
    expect(screen.getByRole('header', { name: libraryUx('en', 'documentColor') })).toBeOnTheScreen();
    await act(async () => { rejectSave?.(new Error('database unavailable')); });
    expect(await within(screen.getByTestId('color-picker-sheet')).findByRole('alert')).toHaveTextContent(t('unknownError'));
    fireEvent.press(screen.getByRole('button', { name: 'Close color picker' }));
    expect(screen.queryByTestId('color-picker-sheet')).toBeNull();
  });

  it('hides canceled jobs from lists, counts and color folders', () => {
    const jobs = [job('live', 'Active.pdf', 2, 'running', 'red'), job('cancel', 'Canceled.pdf', 3, 'canceled', 'red')];
    expect(visibleLibraryJobs(jobs, '', 'recent').map(item => item.id)).toEqual(['live']);
    expect(visibleLibraryJobs(jobs, '', 'recent', 'red').map(item => item.id)).toEqual(['live']);
    render(library(baseSnapshot(jobs), { folder: null }));
    expect(screen.getByText('All documents 1')).toBeOnTheScreen();
    expect(screen.queryByText('Canceled.pdf')).toBeNull();
  });

  it('does not resurrect canceled-only sources as imports and keeps shared active sources represented once', () => {
    const canceledOnly = { ...job('cancel-only', 'Canceled only.pdf', 5, 'canceled'), documentId: 'canceled-only' };
    const liveShared = { ...job('live-shared', 'Shared manual.pdf', 4, 'running'), documentId: 'shared' };
    const canceledShared = { ...job('canceled-shared', 'Shared manual.pdf', 3, 'canceled'), documentId: 'shared' };
    const documents = [
      imported('canceled-only', 'Canceled only.pdf', 1),
      imported('shared', 'Shared manual.pdf', 2),
      imported('raw', 'Raw manual.pdf', 0),
    ];
    const snapshot = baseSnapshot([canceledOnly, liveShared, canceledShared], documents);
    const view = render(library(snapshot, { folder: null }));

    expect(screen.getByText('All documents 2')).toBeOnTheScreen();
    view.rerender(library(snapshot, { folder: 'all' }));
    expect(screen.queryByText('Canceled only.pdf')).toBeNull();
    expect(screen.getAllByText('Shared manual.pdf')).toHaveLength(1);
    expect(screen.getByText('Raw manual.pdf')).toBeOnTheScreen();
  });

  it('groups unfinished work before raw imports and completed results, marks partial output and shows local timestamps', () => {
    const jobs = [job('running', 'Running.pdf', 4, 'running'), job('partial', 'Partial.pdf', 3, 'partial'), job('failed', 'Failed.pdf', 2, 'failed'), job('canceled', 'Canceled.pdf', 5, 'canceled')];
    const view = render(library(baseSnapshot(jobs, [imported('raw', 'Raw manual.pdf', 1)])));
    const headings = screen.getAllByText(/In progress|Ready to translate|Completed/).map(node => node.props.children);
    expect(headings.indexOf(library050Ux('en', 'inProgress'))).toBeLessThan(headings.indexOf(library050Ux('en', 'imports')));
    expect(headings.indexOf(library050Ux('en', 'imports'))).toBeLessThan(headings.indexOf(library050Ux('en', 'completed')));
    expect(screen.getByText(library050Ux('en', 'review'))).toBeOnTheScreen();
    expect(screen.getAllByText(/Added: Jan 1, 1970/).length).toBeGreaterThan(1);
    expect(screen.queryByText('Canceled.pdf')).toBeNull();
    view.unmount();
  });

  it('filters colored folders and routes deletion to the parent for jobs and unattached imports', () => {
    const onDeleteJob = jest.fn();
    const onDeleteDocument = jest.fn();
    const onJob = jest.fn();
    const jobs = [job('blue', 'Blue manual.pdf', 4, 'paused', 'blue'), job('red', 'Red manual.pdf', 3, 'completed', 'red')];
    const raw = imported('raw', 'Raw manual.pdf', 1);
    const view = render(library(baseSnapshot(jobs, [raw]), { folder: 'blue', onDeleteJob, onDeleteDocument, onJob }));
    expect(screen.getByText('Blue manual.pdf')).toBeOnTheScreen();
    expect(screen.queryByText('Red manual.pdf')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Blue manual.pdf' }));
    expect(onJob).toHaveBeenCalledWith(expect.objectContaining({ id: 'blue' }));
    fireEvent.press(screen.getByRole('button', { name: `${library050Ux('en', 'deleteJob')}: Blue manual.pdf` }));
    expect(onDeleteJob).toHaveBeenCalledWith(expect.objectContaining({ id: 'blue' }));
    view.rerender(library(baseSnapshot([], [raw]), { folder: 'all', onDeleteJob, onDeleteDocument }));
    fireEvent.press(screen.getByRole('button', { name: `${library050Ux('en', 'deleteImport')}: Raw manual.pdf` }));
    expect(onDeleteDocument).toHaveBeenCalledWith(raw);
  });

  it('reads persisted library sort, notifies the owner of a change and uses the returned preference', () => {
    const sort = jest.fn();
    const jobs = [job('red', 'Red', 1, 'completed', 'red'), job('blue', 'Blue', 3, 'completed', 'blue')];
    const view = render(library(baseSnapshot(jobs, [], 'recent'), { onSort: sort }));
    expect(screen.getByText(libraryUx('en', 'sortRecent'))).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'sortDocuments') }));
    expect(sort).toHaveBeenCalledWith('color');
    view.rerender(library(baseSnapshot(jobs, [], 'color'), { onSort: sort }));
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'sortDocuments') }));
    expect(sort).toHaveBeenLastCalledWith('recent');
    expect(screen.getByText(libraryUx('en', 'sortColor'))).toBeOnTheScreen();
    expect(screen.getAllByText('Blue')[0]).toBeOnTheScreen();
  });

  it('passes the real selected model title through its animated title and wires the empty-state import action', () => {
    const model = { id: 'local-compact', label: 'Foli Small', status: 'ready' } as ModelSnapshot;
    render(library(baseSnapshot([], []), { folder: null, model }));
    expect(screen.getByText(`Translation model ${ux('en', 'modelCompact')}`)).toBeOnTheScreen();
  });

  it('shows only the localized Online label for MyMemory in the library model preview', () => {
    const model = { id: 'mymemory', label: 'MyMemory', status: 'ready' } as ModelSnapshot;
    render(library(baseSnapshot([], []), { folder: null, model, locale: 'ru' }));
    expect(screen.getByText(`Translation model ${library050Ux('ru', 'online')}`)).toBeOnTheScreen();
    expect(screen.queryByText(/MyMemory/)).toBeNull();
  });

  it('offers import from the empty library state', () => {
    const importDocument = jest.fn();
    render(library(baseSnapshot(), { onTranslate: importDocument }));
    fireEvent.press(screen.getByRole('button', { name: ux('en', 'translatePdf') }));
    expect(importDocument).toHaveBeenCalledTimes(1);
  });

  it('routes all shelves from the BookPocket root and opens the settings shortcut', () => {
    const folderChange = jest.fn();
    const settings = jest.fn();
    render(library(baseSnapshot([job('red', 'Red.pdf', 1, 'completed', 'red')]), { folder: null, onFolderChange: folderChange, onSettings: settings }));
    fireEvent.press(screen.getByRole('button', { name: library050Ux('en', 'settings') }));
    expect(settings).toHaveBeenCalledTimes(1);
    for (const [shelf, folder] of [
      [library050Ux('en', 'all'), 'all'],
      [library050Ux('en', 'inProgress'), 'inProgress'],
      [library050Ux('en', 'completed'), 'completed'],
      ...(['red', 'orange', 'yellow', 'green', 'blue', 'purple'] as const).map(color => [libraryUx('en', color), color]),
    ] as const) {
      fireEvent.press(screen.getByRole('button', { name: shelf }));
      expect(folderChange).toHaveBeenLastCalledWith(folder);
    }
  });

  it('clears root search, opens a ready import for translation and routes Back to the root owner', () => {
    const folderChange = jest.fn();
    const onDocument = jest.fn();
    const raw = imported('raw', 'Reference manual.pdf', 1);
    const view = render(library(baseSnapshot([], [raw]), { folder: null, onFolderChange: folderChange, onDocument }));
    fireEvent.changeText(screen.getByLabelText(libraryUx('en', 'searchDocuments')), 'missing');
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'clearSearch') }));
    fireEvent.press(screen.getByRole('button', { name: library050Ux('en', 'all') }));
    view.rerender(library(baseSnapshot([], [raw]), { folder: 'all', onFolderChange: folderChange, onDocument }));
    fireEvent.press(screen.getByRole('button', { name: 'Reference manual.pdf' }));
    expect(onDocument).toHaveBeenCalledWith(raw);
    fireEvent.press(screen.getByRole('button', { name: ux('en', 'translatePdf') }));
    expect(onDocument).toHaveBeenCalledWith(raw);
    fireEvent.press(screen.getByRole('button', { name: t('back') }));
    expect(folderChange).toHaveBeenLastCalledWith(null);
  });

  it('filters in-progress and completed folders independently and covers every displayed job stage', () => {
    const jobs = [
      { ...job('queued', 'Queued.pdf', 9, 'queued'), stage: 'queued' },
      { ...job('paused', 'Paused.pdf', 8, 'paused'), stage: 'translating' },
      { ...job('failed', 'Failed.pdf', 7, 'failed'), stage: 'translating' },
      { ...job('ocr', 'Ocr.pdf', 6, 'running'), stage: 'ocr' },
      { ...job('extract', 'Extract.pdf', 5, 'running'), stage: 'extracting' },
      { ...job('export', 'Export.pdf', 4, 'running'), stage: 'exporting' },
      { ...job('loading', 'Loading.pdf', 3, 'running'), stage: 'loadingModel' },
      { ...job('translate', 'Translate.pdf', 2, 'running'), stage: 'translating' },
      { ...job('completed', 'Completed.pdf', 1, 'completed'), stage: 'complete' },
      { ...job('partial', 'Partial.pdf', 0, 'partial'), stage: 'complete' },
    ] as JobSnapshot[];
    const view = render(library(baseSnapshot(jobs), { folder: 'inProgress' }));
    for (const name of ['Queued.pdf', 'Paused.pdf', 'Failed.pdf', 'Ocr.pdf', 'Extract.pdf', 'Export.pdf', 'Loading.pdf', 'Translate.pdf']) {
      expect(screen.getByText(name)).toBeOnTheScreen();
    }
    expect(screen.getByText(t('stageOcr'))).toBeOnTheScreen();
    expect(screen.getByText(t('stageExtracting'))).toBeOnTheScreen();
    expect(screen.getByText(t('stageExporting'))).toBeOnTheScreen();
    expect(screen.getByText(t('stageCopying'))).toBeOnTheScreen();
    expect(screen.getByText(t('stageTranslating'))).toBeOnTheScreen();
    expect(screen.getByText(t('stageQueued'))).toBeOnTheScreen();
    expect(screen.getByText(t('resume'))).toBeOnTheScreen();
    expect(screen.getByText(t('unknownError'))).toBeOnTheScreen();
    view.rerender(library(baseSnapshot(jobs), { folder: 'completed' }));
    expect(screen.getByText('Completed.pdf')).toBeOnTheScreen();
    expect(screen.getByText('Partial.pdf')).toBeOnTheScreen();
    expect(screen.queryByText('Queued.pdf')).toBeNull();
  });

  it('falls back to a safe date formatter and hides deletion while a worker is stopping or the screen is busy', () => {
    const stopping = { ...job('stopping', 'Stopping.pdf', 1, 'paused'), workerStopping: true } as JobSnapshot;
    const stable = job('stable', 'Stable.pdf', 0, 'paused');
    render(library(baseSnapshot([stopping, stable]), { folder: 'all', locale: 'invalid_locale' as never, busy: true, onDeleteJob: noop }));
    const fallbackDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(0));
    expect(screen.getAllByText(`${library050Ux('en', 'created')}: ${fallbackDate}`)).toHaveLength(2);
    expect(screen.queryByRole('button', { name: `${library050Ux('en', 'deleteJob')}: Stopping.pdf` })).toBeNull();
    expect(screen.getByRole('button', { name: `${library050Ux('en', 'deleteJob')}: Stable.pdf` })).toBeDisabled();
  });

  it('keeps a read-only shelf usable when no navigation adapter was supplied', () => {
    render(library(baseSnapshot([job('read-only', 'Read only.pdf', 1)]), { folder: 'all', onFolderChange: undefined }));
    fireEvent.press(screen.getByRole('button', { name: t('back') }));
    expect(screen.getByText('Read only.pdf')).toBeOnTheScreen();
  });

  it('uses system date formatting and omits the optional sort control', () => {
    render(library(baseSnapshot([job('system-date', 'System date.pdf', 1)]), { locale: 'system' as never }));
    expect(screen.getByText(/Added:/)).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: libraryUx('en', 'sortDocuments') })).toBeNull();
  });

  it('keeps an unconfigured color picker open without calling a missing save adapter', () => {
    render(library(baseSnapshot([job('no-adapter', 'No adapter.pdf', 1)]), { folder: 'all' }));
    fireEvent.press(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: No adapter.pdf, ${libraryUx('en', 'noColor')}` }));
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'red') }));
    expect(screen.getByTestId('color-picker-sheet')).toBeOnTheScreen();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
