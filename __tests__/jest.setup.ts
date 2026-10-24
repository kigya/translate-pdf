jest.mock('react-native-gesture-handler', () => require('./mocks/gesture-handler'));
jest.mock('react-native-worklets', () => ({
  scheduleOnRN: (callback: (...args: unknown[]) => unknown, ...args: unknown[]) => callback(...args),
  scheduleOnUI: (callback: (...args: unknown[]) => unknown, ...args: unknown[]) => callback(...args),
}));
jest.mock('expo-haptics', () => ({
  ImpactFeedbackStyle: { Light: 'light', Medium: 'medium', Heavy: 'heavy' },
  NotificationFeedbackType: { Success: 'success', Warning: 'warning', Error: 'error' },
  impactAsync: jest.fn(() => Promise.resolve()),
  notificationAsync: jest.fn(() => Promise.resolve()),
  selectionAsync: jest.fn(() => Promise.resolve()),
}));
jest.mock('expo-blur', () => {
  const React = require('react');
  const { View } = require('react-native');
  const BlurView = React.forwardRef((props: object, ref: object) => React.createElement(View, { ...props, ref }));
  const BlurTargetView = React.forwardRef((props: object, ref: object) => React.createElement(View, { ...props, ref }));
  return { BlurView, BlurTargetView };
});
jest.mock('react-native-view-shot', () => ({
  captureRef: jest.fn(() => Promise.resolve('file:///temporary-transition.png')),
  releaseCapture: jest.fn(),
}));
const { act } = require('@testing-library/react-native-legacy');
afterEach(() => act(() => require('./mocks/expo-router').__resetRouter()));
