import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';

jest.mock('react-native', () => ({ Platform: { OS: 'web' } }));
jest.mock('../src/lib/kinetik/tokens/theme', () => ({ useKinetikConfig: () => ({ haptics: true }) }));

import * as Haptics from 'expo-haptics';
import { useHaptic } from '../src/lib/kinetik/hooks/use-haptic';

function HapticProbe() {
  const haptic = useHaptic();
  return React.createElement('button', { onClick: () => haptic() }, 'No actuator');
}

it('keeps the hook inert on unsupported platforms', () => {
  const impact = jest.spyOn(Haptics, 'impactAsync');
  const selection = jest.spyOn(Haptics, 'selectionAsync');
  let tree: TestRenderer.ReactTestRenderer;
  act(() => { tree = TestRenderer.create(<HapticProbe />); });
  act(() => { tree!.root.findByType('button').props.onClick(); });
  expect(impact).not.toHaveBeenCalled();
  expect(selection).not.toHaveBeenCalled();
  act(() => tree!.unmount());
});
