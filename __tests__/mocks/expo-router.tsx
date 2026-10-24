import React, { useSyncExternalStore } from 'react';
import { View } from 'react-native';

export type RouteParams = Record<string, string | string[] | undefined>;
let params: RouteParams = {};
const listeners = new Set<() => void>();
let rootNavigationState: { key: string } | undefined = { key: 'root' };
let navigationContainerReady = true;
const navigationListeners = new Set<() => void>();
const publish = () => listeners.forEach(listener => listener());
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};
const publishNavigation = () => navigationListeners.forEach(listener => listener());
const subscribeNavigation = (listener: () => void) => {
  navigationListeners.add(listener);
  return () => navigationListeners.delete(listener);
};
const navigationSnapshot = () => rootNavigationState;
export const navigationContainerRef = { isReady: jest.fn(() => navigationContainerReady) };
const snapshot = () => params;

const boundRouter = {
  setParams: jest.fn((next: RouteParams) => {
    params = { ...params, ...next };
    publish();
  }),
  push: jest.fn(),
  replace: jest.fn(),
  back: jest.fn(),
};

// SDK58 component actions use their provider queue even while the shared
// imperative bridge is unbound during a root-container transition.
export const router = {
  ...boundRouter,
  setParams: jest.fn((next: RouteParams) => boundRouter.setParams(next)),
};
export function useRouter() {
  return boundRouter;
}

export function useLocalSearchParams<T extends RouteParams = RouteParams>() {
  return useSyncExternalStore(subscribe, snapshot, snapshot) as T;
}

export function useRootNavigationState() {
  return useSyncExternalStore(subscribeNavigation, navigationSnapshot, navigationSnapshot);
}

export function useNavigationContainerRef() {
  return navigationContainerRef;
}

export function __setNavigationContainerReady(ready: boolean) {
  navigationContainerReady = ready;
}

export function __setRootNavigationReady(ready: boolean) {
  rootNavigationState = ready ? { key: 'root' } : undefined;
  publishNavigation();
}

export function __getRootNavigationState() {
  return rootNavigationState;
}

export function __setLocalSearchParams(next: RouteParams) {
  params = { ...next };
  publish();
}

export function __resetRouter() {
  params = {};
  rootNavigationState = { key: 'root' };
  navigationContainerReady = true;
  boundRouter.setParams.mockClear();
  router.setParams.mockClear();
  router.push.mockClear();
  router.replace.mockClear();
  router.back.mockClear();
  navigationContainerRef.isReady.mockClear();
  publish();
  publishNavigation();
}

export function Stack({ children, screenOptions }: { children?: React.ReactNode; screenOptions?: Record<string, unknown> }) {
  return <View testID="router-stack" accessibilityLabel={JSON.stringify(screenOptions)}>{children}</View>;
}
Stack.Screen = function StackScreen({ children }: { children?: React.ReactNode }) {
  return <>{children}</>;
};
export function Slot({ children }: { children?: React.ReactNode }) {
  return <>{children}</>;
}
export function Redirect({ href }: { href: string }) {
  return <View testID="router-redirect" accessibilityLabel={href} />;
}
