import { DeviceSnapshot, ModelResourceReason, ModelSnapshot } from '../native/PdfTranslator';

function hasMemoryCapacity(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  if (model.memoryEstimateEligible !== undefined) return model.memoryEstimateEligible;
  if (device.memoryEstimateEligible !== undefined) return device.memoryEstimateEligible;
  return model.id === device.recommendedModelId && device.recommendedModelAvailable;
}

function hasDiskCapacity(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  if (model.diskEstimateEligible !== undefined) return model.diskEstimateEligible;
  if (device.diskEstimateEligible !== undefined) return device.diskEstimateEligible;
  return model.id === device.recommendedModelId && device.recommendedModelAvailable;
}

/** Estimated RAM is advisory; download integrity and storage remain mandatory. */
export function modelCanBeInstalled(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  return model.checksumPinned && model.translationEnabled && hasDiskCapacity(model, device);
}

/** A ready package may be attempted unless the OS currently reports memory pressure. */
export function modelCanTranslate(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  if (model.status !== 'ready' || !model.checksumPinned || !model.translationEnabled) return false;
  return !device.systemLowMemory;
}

export function modelHasMemoryWarning(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  return !hasMemoryCapacity(model, device);
}

export function modelResourceReason(model: ModelSnapshot, device: DeviceSnapshot, forDownload: boolean): ModelResourceReason {
  if (!model.checksumPinned || !model.translationEnabled) return 'unsupported';
  if (forDownload && !hasDiskCapacity(model, device)) return 'disk';
  if (!forDownload && device.systemLowMemory) return 'lowMemory';
  if (!hasMemoryCapacity(model, device)) return 'memory';
  return 'available';
}
