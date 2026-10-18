import { DeviceSnapshot, ModelSnapshot } from '../native/PdfTranslator';

/** Keep candidate visibility separate from permission to download and translate. */
export function modelCanBeInstalled(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  return model.checksumPinned && model.translationEnabled && model.id === device.recommendedModelId && device.recommendedModelAvailable;
}

export function modelCanTranslate(model: ModelSnapshot, device: DeviceSnapshot): boolean {
  return model.status === 'ready' && modelCanBeInstalled(model, device);
}
