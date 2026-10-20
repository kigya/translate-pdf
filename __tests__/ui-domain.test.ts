import { createTranslator, localeOptions, normalizeLocale } from '../src/i18n/messages';
import { resolveTheme } from '../src/ui/theme';
import { DeviceSnapshot, ModelSnapshot } from '../src/native/PdfTranslator';
import { modelCanBeInstalled, modelCanTranslate, modelResourceReason } from '../src/domain/modelEligibility';

const device: DeviceSnapshot = { manufacturer: 'Demo', model: 'Demo', apiLevel: 35, totalMemoryBytes: 8e9, availableMemoryBytes: 4e9, systemLowMemoryThresholdBytes: 512e6, systemLowMemory: false, availableDiskBytes: 8e9, reservedMemoryBytes: 1e9, requiredPipelineMemoryBytes: 1.5e9, requiredDiskBytes: 1.2e9, memoryEstimateEligible: true, diskEstimateEligible: true, resourceEstimateEligible: true, recommendedModelAvailable: true, recommendedModelId: 'q4', recommendationReason: 'Current free memory is sufficient.', recommendationVerified: true };
const model: ModelSnapshot = { id: 'q4', label: 'Q4 candidate', revision: 'pinned', bytes: 1e9, sha256: 'pinned', verified: false, checksumPinned: true, translationEnabled: true, status: 'not_downloaded', downloadedBytes: 0, tier: 'quality', requiredMemoryBytes: 2e9, memoryEstimateEligible: true, diskEstimateEligible: true, resourceEstimateEligible: true, resourceReason: 'available' };

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
    expect(createTranslator('ru')('importPdf')).toBe('Перевести PDF');
    expect(createTranslator('fr')('settings')).toBe('Paramètres');
    expect(createTranslator('en')('settings')).toBe('Settings');
  });

  it('allows any enabled pinned model when its per-model memory and disk estimates pass', () => {
    expect(modelCanBeInstalled(model, device)).toBe(true);
    expect(modelCanTranslate({ ...model, status: 'ready' }, device)).toBe(true);
    expect(modelCanTranslate(model, device)).toBe(false);
    expect(modelCanBeInstalled({ ...model, translationEnabled: false }, device)).toBe(false);
    expect(modelCanBeInstalled({ ...model, checksumPinned: false }, device)).toBe(false);
    const independentlyEligible = { ...model, id: 'other-pinned-model' };
    expect(modelCanBeInstalled(independentlyEligible, { ...device, recommendedModelAvailable: false })).toBe(true);
  });

  it('permits an attempt below the estimated envelope while retaining the warning', () => {
    const blocked = { ...model, memoryEstimateEligible: false, resourceEstimateEligible: false, resourceReason: 'memory' as const };
    expect(modelCanBeInstalled(blocked, device)).toBe(true);
    expect(modelCanTranslate({ ...blocked, status: 'ready' }, device)).toBe(true);
    expect(modelResourceReason(blocked, device, false)).toBe('memory');
    expect(modelCanTranslate({ ...blocked, status: 'ready' }, { ...device, systemLowMemory: true })).toBe(false);
  });

  it('does not require full package disk headroom again for an installed model', () => {
    const installed = { ...model, status: 'ready' as const, diskEstimateEligible: false, resourceEstimateEligible: true };
    expect(modelCanBeInstalled(installed, device)).toBe(false);
    expect(modelCanTranslate(installed, device)).toBe(true);
  });

  it('allows a ready model whose combined resource flag is false only because disk is low', () => {
    const installed = {
      ...model,
      status: 'ready' as const,
      diskEstimateEligible: false,
      resourceEstimateEligible: false,
      resourceReason: 'disk' as const,
    };
    expect(modelCanBeInstalled(installed, device)).toBe(false);
    expect(modelCanTranslate(installed, device)).toBe(true);
    expect(modelResourceReason(installed, device, false)).toBe('available');
  });
});
