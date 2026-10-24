const { createHash } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vendor = require('./licenses/kinetik-vendor-manifest.json');
// Fail closed when copied vendor code changes; owned adapters remain fully covered.
for (const component of vendor.components) {
  const hash = createHash('sha256').update(readFileSync(resolve(__dirname, component.path))).digest('hex');
  if (hash !== component.installedSha256) throw new Error(`Review vendor provenance before excluding changed code: ${component.path}`);
}

module.exports = {
  preset: '@react-native/jest-preset',
  testEnvironment: '<rootDir>/__tests__/mocks/skia-environment.js',
  testMatch: ['<rootDir>/__tests__/**/*.test.[jt]s?(x)', '<rootDir>/src/**/*.test.[jt]s?(x)'],
  testPathIgnorePatterns: ['/node_modules/', '/.local-private/', '/ios/Pods/', '/ios/vendor/'],
  modulePathIgnorePatterns: ['<rootDir>/.local-private/', '<rootDir>/ios/Pods/', '<rootDir>/ios/vendor/'],
  moduleNameMapper: {
    '^expo-router$': '<rootDir>/__tests__/mocks/expo-router.tsx',
    '^@shopify/react-native-skia$': '<rootDir>/__tests__/mocks/skia.tsx',
    '^.+\\.(bmp|gif|jpe?g|png|psd|svg|webp)$': '<rootDir>/__tests__/mocks/asset.js',
  },
  setupFilesAfterEnv: ['<rootDir>/__tests__/jest.setup.ts'],
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|expo(nent)?|@expo(nent)?/.*|react-native-svg|react-native-worklets)/)',
  ],
  collectCoverageFrom: ['App.tsx', 'app/**/*.{ts,tsx}', 'src/**/*.{ts,tsx}', '!src/**/*.test.{ts,tsx}', ...vendor.components.map(component => `!${component.path}`)],
  coverageReporters: ['json', 'json-summary', 'text-summary', 'lcov'],
  coverageThreshold: { global: { statements: 100, branches: 100, functions: 100, lines: 100 } },
};
