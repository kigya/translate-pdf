import React from 'react';
import { ScrollView, View } from 'react-native';

/** One vertical scroll owner on phones; independently scrollable columns on tablets. */
export function ReviewLayout({ wide, children }: { wide: boolean; children: React.ReactNode }) {
  return wide ? <View style={{ flex: 1, flexDirection: 'row' }}>{children}</View> : <ScrollView style={{ flex: 1 }}>{children}</ScrollView>;
}
export function ReviewColumn({ wide, weight = 1, gap, children }: { wide: boolean; weight?: number; gap: number; children: React.ReactNode }) {
  return wide ? <ScrollView style={{ flex: weight }} contentContainerStyle={{ padding: 18, gap }}>{children}</ScrollView> : <View style={{ padding: 18, gap }}>{children}</View>;
}
