module.exports = {
  preset: '@react-native/jest-preset',
  testMatch: ['<rootDir>/__tests__/**/*.test.[jt]s?(x)', '<rootDir>/src/**/*.test.[jt]s?(x)'],
  testPathIgnorePatterns: ['/node_modules/', '/.local-private/', '/ios/Pods/', '/ios/vendor/'],
  modulePathIgnorePatterns: ['<rootDir>/.local-private/', '<rootDir>/ios/Pods/', '<rootDir>/ios/vendor/'],
  collectCoverageFrom: ['App.tsx', 'src/**/*.{ts,tsx}'],
  coverageReporters: ['json', 'json-summary', 'text-summary', 'lcov'],
  coverageThreshold: { global: { statements: 100, branches: 100, functions: 100, lines: 100 } },
};
