const ReactNativeEnvironment = require('@react-native/jest-preset/jest/react-native-env.js');
const CanvasKitInit = require('canvaskit-wasm/bin/full/canvaskit');

module.exports = class SkiaJestEnvironment extends ReactNativeEnvironment {
  async setup() {
    await super.setup();
    this.global.CanvasKit = await CanvasKitInit({});
  }
};
