import { createTranslator, localeOptions, normalizeLocale } from '../src/i18n/messages';
import { resolveTheme } from '../src/ui/theme';
import { DeviceSnapshot, ModelSnapshot } from '../src/native/PdfTranslator';
import { modelCanBeInstalled, modelCanTranslate } from '../src/domain/modelEligibility';

const device: DeviceSnapshot = { manufacturer: 'Demo', model: 'Demo', apiLevel: 35, totalMemoryBytes: 8e9, availableMemoryBytes: 4e9, systemLowMemoryThresholdBytes: 512e6, systemLowMemory: false, availableDiskBytes: 8e9, reservedMemoryBytes: 1e9, requiredPipelineMemoryBytes: 1.5e9, requiredDiskBytes: 1.2e9, memoryEstimateEligible: true, diskEstimateEligible: true, resourceEstimateEligible: true, recommendedModelAvailable: true, recommendedModelId: 'q4', recommendationReason: 'Current free memory is sufficient.', recommendationVerified: true };
const model: ModelSnapshot = { id: 'q4', label: 'Q4 candidate', revision: 'pinned', bytes: 1e9, sha256: 'pinned', verified: false, checksumPinned: true, translationEnabled: true, status: 'not_downloaded', downloadedBytes: 0 };

describe('interface preferences', () => {
  it('follows the system theme until the user selects a fixed theme', () => {
    expect(resolveTheme('system', 'dark')).toBe('dark');
    expect(resolveTheme('system', 'light')).toBe('light');
    expect(resolveTheme('light', 'dark')).toBe('light');
    expect(resolveTheme('dark', 'light')).toBe('dark');
  });

  it('normalizes supported device locale variants and falls back safely', () => {
    expect(normalizeLocale('pt_PT')).toBe('pt-BR');
    expect(normalizeLocale('zh-CN')).toBe('zh-Hans');
    expect(normalizeLocale('ru-RU')).toBe('ru');
    expect(normalizeLocale('xx-YY')).toBe('en');
  });

  it('offers all 30 product locales and localizes the core controls', () => {
    expect(localeOptions.filter(([locale]) => locale !== 'system')).toHaveLength(30);
    expect(createTranslator('ru')('importPdf')).toBe('Импортировать PDF');
    expect(createTranslator('fr')('settings')).toBe('Paramètres');
    expect(createTranslator('en')('settings')).toBe('Settings');
  });

  it('allows the pinned, enabled memory recommendation without claiming quality is benchmarked', () => {
    expect(modelCanBeInstalled(model, device)).toBe(true);
    expect(modelCanTranslate({ ...model, status: 'ready' }, device)).toBe(true);
    expect(modelCanTranslate(model, device)).toBe(false);
    expect(modelCanBeInstalled({ ...model, translationEnabled: false }, device)).toBe(false);
    expect(modelCanBeInstalled({ ...model, checksumPinned: false }, device)).toBe(false);
    expect(modelCanBeInstalled(model, { ...device, recommendedModelAvailable: false })).toBe(false);
  });
});
