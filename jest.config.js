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
  collectCoverageFrom: ['App.tsx', 'app/**/*.{ts,tsx}', 'src/**/*.{ts,tsx}', '!src/**/*.test.{ts,tsx}'],
  coverageReporters: ['json', 'json-summary', 'text-summary', 'lcov'],
  coverageThreshold: { global: { statements: 100, branches: 100, functions: 100, lines: 100 } },
};
