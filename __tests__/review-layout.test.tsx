import React from 'react';
import { ScrollView, Text } from 'react-native';
import { render, screen } from '@testing-library/react-native-legacy';
import { ReviewColumn, ReviewLayout } from '../src/ui/ReviewLayout';

it('puts phone preview, text and export action under a single scroll owner', () => {
  const view = render(<ReviewLayout wide={false}><ReviewColumn wide={false} weight={1.15} gap={14}><Text>Page preview</Text></ReviewColumn><ReviewColumn wide={false} gap={13}><Text>Translation</Text><Text>Save a copy</Text></ReviewColumn></ReviewLayout>);
  const scrolls = view.UNSAFE_getAllByType(ScrollView);
  expect(scrolls).toHaveLength(1);
  expect(scrolls[0].findAllByType(Text).map(node => node.props.children)).toEqual(['Page preview', 'Translation', 'Save a copy']);
  expect(screen.getByText('Save a copy')).toBeOnTheScreen();
});
it('keeps independently scrolling preview and details columns on wide screens', () => {
  const view = render(<ReviewLayout wide><ReviewColumn wide weight={1.15} gap={14}><Text>Page preview</Text></ReviewColumn><ReviewColumn wide gap={13}><Text>Translation</Text><Text>Save a copy</Text></ReviewColumn></ReviewLayout>);
  const scrolls = view.UNSAFE_getAllByType(ScrollView);
  expect(scrolls).toHaveLength(2);
  expect(scrolls[0].props.style.flex).toBe(1.15);
  expect(scrolls[1].props.style.flex).toBe(1);
  expect(scrolls[0].findAllByType(Text)).toHaveLength(1);
  expect(scrolls[1].findAllByType(Text)).toHaveLength(2);
});
