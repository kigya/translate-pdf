import React from 'react';
import { Text, View } from 'react-native';
import { TranslationLanguage, translationLanguageName } from '../domain/translationLanguages';
import { Icon } from './Controls';
import { Palette } from './theme';

/** A visual icon keeps the direction marker centred independently of font metrics. */
export function LanguagePair({ source, target, colors, direction }: {
  source: TranslationLanguage; target: TranslationLanguage; colors: Palette; direction: 'ltr' | 'rtl';
}) {
  return <View accessible accessibilityLabel={`${translationLanguageName(source)} → ${translationLanguageName(target)}`} style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6, direction }}>
    <Text style={{ color: colors.muted, fontSize: 13 }}>{translationLanguageName(source)}</Text>
    <Icon name="arrow" color={colors.muted} size={16} rotation={direction === 'rtl' ? 180 : 0} />
    <Text style={{ color: colors.muted, fontSize: 13 }}>{translationLanguageName(target)}</Text>
  </View>;
}
