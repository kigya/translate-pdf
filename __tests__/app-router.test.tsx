import React from 'react';
import { render, screen } from '@testing-library/react-native-legacy';

import Index from '../app/index';
import Layout from '../app/_layout';

it('starts inside a gesture root with the app navigation stack configured without animation', () => {
  render(<Layout />);
  expect(screen.getByTestId('gesture-handler-root')).toBeOnTheScreen();
  expect(screen.getByTestId('router-stack').props.accessibilityLabel).toBe('{"headerShown":false,"animation":"none"}');
});

it('redirects the root route to the document library', () => {
  render(<Index />);
  expect(screen.getByTestId('router-redirect').props.accessibilityLabel).toBe('/home');
});
