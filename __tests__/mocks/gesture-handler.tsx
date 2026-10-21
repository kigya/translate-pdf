import React from 'react';
import { View } from 'react-native';

type Callback = (...args: any[]) => void;
type GestureHandlers = Record<string, Callback>;
type AccessibleChildProps = {
  onAccessibilityAction?: (event: unknown) => void;
  onPress?: (...args: unknown[]) => void;
};
class GestureMock {
  testId = '';
  isEnabled = true;
  handlers: GestureHandlers = {};
  private chain() { return this; }
  withTestId(id: string) { this.testId = id; return this; }
  enabled(value: boolean) { this.isEnabled = value; return this; }
  maxDuration() { return this.chain(); }
  maxDistance() { return this.chain(); }
  activeOffsetY() { return this.chain(); }
  failOffsetX() { return this.chain(); }
  onBegin(callback: Callback) { this.handlers.onBegin = callback; return this.chain(); }
  onUpdate(callback: Callback) { this.handlers.onUpdate = callback; return this.chain(); }
  onEnd(callback: Callback) { this.handlers.onEnd = callback; return this.chain(); }
  onFinalize(callback: Callback) { this.handlers.onFinalize = callback; return this.chain(); }
  onTouchesDown(callback: Callback) { this.handlers.onTouchesDown = callback; return this.chain(); }
  onTouchesMove(callback: Callback) { this.handlers.onTouchesMove = callback; return this.chain(); }
  onTouchesUp(callback: Callback) { this.handlers.onTouchesUp = callback; return this.chain(); }
  onTouchesCancelled(callback: Callback) { this.handlers.onTouchesCancelled = callback; return this.chain(); }
  activate() { return this.chain(); }
  end() { return this.chain(); }
}
const fresh = () => new GestureMock();
const manager = { activate: jest.fn(), end: jest.fn(), fail: jest.fn() };
const event = (nativeEvent: Record<string, unknown> = {}) => {
  const fallbackTouch = { x: 24, y: 24, absoluteX: 24, absoluteY: 24 };
  const allTouches = Array.isArray(nativeEvent.allTouches) ? nativeEvent.allTouches : [fallbackTouch];
  const changedTouches = Array.isArray(nativeEvent.changedTouches) ? nativeEvent.changedTouches : [];
  return { allTouches, changedTouches, numberOfTouches: nativeEvent.numberOfTouches ?? allTouches.length, nativeEvent };
};

export const Gesture = { Tap: fresh, Pan: fresh, Manual: fresh };
export function GestureHandlerRootView({ children, testID }: { children?: React.ReactNode; testID?: string }) {
  return <View testID={testID ?? 'gesture-handler-root'}>{children}</View>;
}
export function GestureDetector({ gesture, children }: { gesture: GestureMock; children: React.ReactNode }) {
  const touch = (handler: string, e: unknown) => {
    if (!gesture.isEnabled) return;
    if (handler === 'onEnd') {
      const success = (e as { nativeEvent?: { gestureSuccess?: boolean } })?.nativeEvent?.gestureSuccess !== false;
      gesture.handlers.onEnd?.(e, success);
      return;
    }
    if (handler === 'onTouchesDown' || handler === 'onTouchesMove' || handler === 'onTouchesUp') {
      gesture.handlers[handler]?.(event((e as { nativeEvent?: Record<string, unknown> })?.nativeEvent), manager);
      return;
    }
    gesture.handlers[handler]?.(e);
  };
  const interactiveChild = React.isValidElement(children) ? React.cloneElement(children as React.ReactElement<AccessibleChildProps>, {
    onPress: (...args: unknown[]) => {
      if (!gesture.isEnabled) return;
      const accessibilityAction = (children.props as { onAccessibilityAction?: (event: unknown) => void }).onAccessibilityAction;
      if (accessibilityAction) {
        accessibilityAction({ nativeEvent: { actionName: 'activate' } });
        return;
      }
      if (gesture.handlers.onTouchesDown) {
        gesture.handlers.onTouchesDown(event(), manager);
        gesture.handlers.onTouchesUp?.(event(), manager);
        return;
      }
      gesture.handlers.onBegin?.(...args);
      gesture.handlers.onEnd?.(event(), true);
      gesture.handlers.onFinalize?.(event());
    },
  }) : children;
  return (
    <View
      testID={gesture.testId ? `gesture-${gesture.testId}` : undefined}
      onTouchStart={e => { touch('onBegin', e); touch('onTouchesDown', e); }}
      onTouchMove={e => { touch('onUpdate', e); touch('onTouchesMove', e); }}
      onTouchEnd={e => { touch('onEnd', e); touch('onTouchesUp', e); touch('onFinalize', e); }}
      onTouchCancel={e => { touch('onTouchesCancelled', e); touch('onFinalize', e); }}
    >
      {interactiveChild}
    </View>
  );
}
export const __gestureTestManager = manager;
