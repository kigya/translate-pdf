import { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo } from 'react-native';
import { useReducedMotion } from 'react-native-reanimated';

/** Reanimated's hook is a startup snapshot; native accessibility events are live. */
export function useMotionReduced(): boolean {
  const initial = useReducedMotion() ?? false;
  const [current, setCurrent] = useState<boolean>();
  const generation = useRef(0);
  useEffect(() => {
    let mounted = true;
    const request = ++generation.current;
    const subscription = AccessibilityInfo.addEventListener(
      'reduceMotionChanged',
      value => {
        ++generation.current;
        setCurrent(value);
      },
    );
    void AccessibilityInfo.isReduceMotionEnabled().then(value => {
      // A slower initial read must never overwrite a newer accessibility event.
      if (mounted && request === generation.current) setCurrent(value);
    }).catch(() => {
      // Keep the startup snapshot if the platform's preference read fails.
    });
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);
  return current ?? initial;
}
