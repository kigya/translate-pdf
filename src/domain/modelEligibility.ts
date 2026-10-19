import { DeviceSnapshot, ModelResourceReason, ModelSnapshot } from '../native/PdfTranslator';

function hasMemoryCapacity(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  if (model.memoryEstimateEligible !== undefined) return model.memoryEstimateEligible;
  if (device.memoryEstimateEligible !== undefined) return device.memoryEstimateEligible;
  return model.id === device.recommendedModelId && device.recommendedModelAvailable;
}

function hasDiskCapacity(model: ModelSnapshot, device: DeviceSnapshot, forDownload = true): boolean {
  // Installed packages no longer need the package's full size available on disk.
  if (model.status === 'ready' && !forDownload) return true;
  if (model.diskEstimateEligible !== undefined) return model.diskEstimateEligible;
  if (device.diskEstimateEligible !== undefined) return device.diskEstimateEligible;
  return model.id === device.recommendedModelId && device.recommendedModelAvailable;
}

/** Models can be downloaded when the pinned, enabled model fits both resource estimates. */
export function modelCanBeInstalled(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  const memoryEligible = hasMemoryCapacity(model, device);
  const diskEligible = hasDiskCapacity(model, device, true);
  const resourceEligible = model.resourceEstimateEligible ?? (memoryEligible && diskEligible);
  return model.checksumPinned && model.translationEnabled && memoryEligible && diskEligible && resourceEligible;
}

/** Ready models need runtime memory eligibility, but not package-download headroom. */
export function modelCanTranslate(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  if (model.status !== 'ready' || !model.checksumPinned || !model.translationEnabled) return false;
  return hasMemoryCapacity(model, device);
}

export function modelResourceReason(model: ModelSnapshot, device: DeviceSnapshot, forDownload: boolean): ModelResourceReason {
  if (!model.checksumPinned || !model.translationEnabled) return 'unsupported';
  if (model.resourceReason && model.resourceReason !== 'available' && !(model.status === 'ready' && !forDownload && model.resourceReason === 'disk')) return model.resourceReason;
  if (!hasMemoryCapacity(model, device)) return device.systemLowMemory ? 'lowMemory' : 'memory';
  if (forDownload && !hasDiskCapacity(model, device, true)) return 'disk';
  if (!forDownload && model.status === 'ready') return 'available';
  if (model.resourceEstimateEligible === false) return model.resourceReason && model.resourceReason !== 'available' ? model.resourceReason : 'unsupported';
  return 'available';
}
