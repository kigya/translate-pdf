import { palettes } from '../src/ui/theme';

const luminance = (hex: string) => {
  const rgb = [1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16) / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
};
const contrast = (a: string, b: string) => {
  const [low, high] = [luminance(a), luminance(b)].sort((x, y) => x - y);
  return (high + 0.05) / (low + 0.05);
};

test.each(Object.entries(palettes))('%s normal text and action colors meet WCAG AA contrast', (_theme, palette) => {
  for (const surface of [palette.background, palette.surface, palette.raised]) {
    for (const text of [palette.text, palette.muted, palette.accent, palette.success, palette.warning]) {
      expect(contrast(text, surface)).toBeGreaterThanOrEqual(4.5);
    }
  }
  expect(contrast(palette.onAccent, palette.accent)).toBeGreaterThanOrEqual(4.5);
  expect(contrast(palette.accent, palette.softAccent)).toBeGreaterThanOrEqual(4.5);
});
