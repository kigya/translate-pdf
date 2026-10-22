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
jest.mock('../src/ui/Controls', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return {
    Icon: ({ name }: { name: string }) => ReactRuntime.createElement(RN.Text, null, name),
    SettingsRow: ({ label, value, onPress }: { label: string; value?: string; onPress: () => void }) => ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: label, onPress }, ReactRuntime.createElement(RN.Text, null, value ? `${label} ${value}` : label)),
    Sheet: ({ visible, title, onClose, children }: { visible: boolean; title: string; onClose: () => void; children: React.ReactNode }) => visible ? ReactRuntime.createElement(RN.View, { testID: 'color-picker-sheet' }, ReactRuntime.createElement(RN.Text, { accessibilityRole: 'header' }, title), ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: 'Close color picker', onPress: onClose }, ReactRuntime.createElement(RN.Text, null, 'Close')), children) : null,
  };
});
jest.mock('../src/ui/components', () => {
  const ReactRuntime = require('react');
  const RN = require('react-native');
  return {
    Button: ({ label, onPress, disabled }: { label: string; onPress: () => void; disabled?: boolean }) => ReactRuntime.createElement(RN.Pressable, { accessibilityRole: 'button', accessibilityLabel: label, disabled, onPress }, ReactRuntime.createElement(RN.Text, null, label)),
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
const library = (snapshot: BootstrapSnapshot, extra: Partial<React.ComponentProps<typeof DocumentLibrary>> = {}) => (
  <DocumentLibrary colors={palette} t={t} locale="en" snapshot={snapshot} onTranslate={noop} onModel={noop} onJob={noop} onDocument={noop} {...extra} />
);

describe('Folivela document library', () => {
  it('filters filenames without case or accent sensitivity and offers a working clear action for no matches', () => {
    const docs = [job('1', 'Café pump.pdf', 1), job('2', 'Pump housing.docx', 2)];
    const view = render(library(baseSnapshot(docs)));
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'searchDocuments') }));
    expect(screen.queryByText('Recent documents')).toBeNull();
    fireEvent.changeText(screen.getByLabelText(libraryUx('en', 'searchDocuments')), ' cafe ');
    expect(screen.getByText('Café pump.pdf')).toBeOnTheScreen();
    expect(screen.queryByText('Pump housing.docx')).toBeNull();

    fireEvent.changeText(screen.getByLabelText(libraryUx('en', 'searchDocuments')), 'no-such-manual');
    expect(screen.getByText(libraryUx('en', 'noDocumentsFound'))).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'clearSearch') }));
    expect(screen.getByText('Café pump.pdf')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('Recent documents')).toBeOnTheScreen();
    view.unmount();
  });

  it('sorts color groups in product order, leaves unlabelled entries last, and orders ties newest-first', () => {
    const jobs = [job('old-red', 'Old red', 1, 'completed', 'red'), job('blue', 'Blue', 4, 'completed', 'blue'), job('new-red', 'New red', 9, 'partial', 'red'), job('none-old', 'No label old', 2), job('orange', 'Orange', 3, 'completed', 'orange'), job('none-new', 'No label new', 10)];
    expect(visibleLibraryJobs(jobs, '', 'color').map(item => item.id)).toEqual(['new-red', 'old-red', 'orange', 'blue', 'none-new', 'none-old']);
    expect(visibleLibraryJobs(jobs, 'RED', 'recent').slice(0, 2).map(item => item.id)).toEqual(['new-red', 'old-red']);
  });

  it('shows color controls only for completed and partial jobs', () => {
    const jobs = [job('done', 'Done.pdf', 4), job('partial', 'Partial.docx', 3, 'partial'), job('running', 'Running.pdf', 2, 'running'), job('failed', 'Failed.pdf', 1, 'failed')];
    render(library(baseSnapshot(jobs)));
    expect(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Done.pdf, ${libraryUx('en', 'noColor')}` })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Partial.docx, ${libraryUx('en', 'noColor')}` })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: new RegExp(`${libraryUx('en', 'documentColor')}: Running`) })).toBeNull();
    expect(screen.queryByRole('button', { name: new RegExp(`${libraryUx('en', 'documentColor')}: Failed`) })).toBeNull();
  });

  it('keeps color picker open while saving, closes after success, and remains open with a local error on failure', async () => {
    let finishSave: (() => void) | undefined;
    const setColor = jest.fn(() => new Promise<void>(resolve => { finishSave = resolve; }));
    const view = render(library(baseSnapshot([job('done', 'Manual.pdf', 1)]), { onSetColor: setColor }));
    fireEvent.press(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Manual.pdf, ${libraryUx('en', 'noColor')}` }));
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'red') }));
    expect(setColor).toHaveBeenCalledWith(expect.objectContaining({ id: 'done' }), 'red');
    expect(screen.getByRole('header', { name: libraryUx('en', 'documentColor') })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: libraryUx('en', 'noColor') })).toBeDisabled();
    fireEvent.press(screen.getByRole('radio', { name: libraryUx('en', 'blue') }));
    fireEvent.press(screen.getByRole('button', { name: 'Close color picker' }));
    expect(setColor).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('color-picker-sheet')).toBeOnTheScreen();
    await act(async () => { finishSave?.(); });
    await waitFor(() => expect(screen.queryByRole('header', { name: libraryUx('en', 'documentColor') })).toBeNull());

    const clearSuccess = jest.fn().mockResolvedValue(undefined);
    view.rerender(library(baseSnapshot([job('clear', 'Clear label.pdf', 2, 'completed', 'red')]), { onSetColor: clearSuccess }));
    fireEvent.press(screen.getByRole('button', { name: `${libraryUx('en', 'documentColor')}: Clear label.pdf, ${libraryUx('en', 'red')}` }));
    await act(async () => { fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'noColor') })); });
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
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'noColor') }));
    expect(failed).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'done2' }), null);
    expect(screen.getByRole('header', { name: libraryUx('en', 'documentColor') })).toBeOnTheScreen();
    await act(async () => { rejectSave?.(new Error('database unavailable')); });
    expect(await within(screen.getByTestId('color-picker-sheet')).findByRole('alert')).toHaveTextContent(t('unknownError'));
    fireEvent.press(screen.getByRole('button', { name: 'Close color picker' }));
    expect(screen.queryByTestId('color-picker-sheet')).toBeNull();
  });

  it('anchors the color help tip to the logical end for LTR and start for RTL locales', () => {
    const view = render(library(baseSnapshot([job('rtl', 'كتاب.docx', 1)]), { locale: 'ar' }));
    expect(screen.getByTestId('tip-bubble').props.accessibilityHint).toBe('start');
    view.rerender(library(baseSnapshot([job('ltr', 'Book.pdf', 1)]), { locale: 'en' }));
    expect(screen.getByTestId('tip-bubble').props.accessibilityHint).toBe('end');
  });

  it('reads persisted library sort, notifies the owner of a change and uses the returned preference', () => {
    const sort = jest.fn();
    const jobs = [job('red', 'Red', 1, 'completed', 'red'), job('blue', 'Blue', 3, 'completed', 'blue')];
    const view = render(library(baseSnapshot(jobs, [], 'recent'), { onSort: sort }));
    expect(screen.getByText(`${libraryUx('en', 'sortDocuments')}: recent`)).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: libraryUx('en', 'sortColor') }));
    expect(sort).toHaveBeenCalledWith('color');
    view.rerender(library(baseSnapshot(jobs, [], 'color'), { onSort: sort }));
    expect(screen.getByText(`${libraryUx('en', 'sortDocuments')}: color`)).toBeOnTheScreen();
    expect(screen.getAllByText('Blue')[0]).toBeOnTheScreen();
  });

  it('passes the real selected model title through its animated title and wires the empty-state import action', () => {
    const model = { id: 'local-compact', label: 'Foli Small', status: 'ready' } as ModelSnapshot;
    render(library(baseSnapshot([], []), { model }));
    expect(screen.getByText('Foli Small')).toBeOnTheScreen();
    expect(screen.getByRole('text', { name: 'Foli Small' })).toBeOnTheScreen();
  });

  it('offers import from the empty library state', () => {
    const importDocument = jest.fn();
    render(library(baseSnapshot(), { onTranslate: importDocument }));
    fireEvent.press(screen.getByRole('button', { name: ux('en', 'translatePdf') }));
    expect(importDocument).toHaveBeenCalledTimes(1);
  });
});
