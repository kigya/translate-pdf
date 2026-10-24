import React from 'react';
import { View } from 'react-native';

const node = (name: string) => {
  const Component = ({ children, ...props }: { children?: React.ReactNode }) => React.createElement(View, props, children);
  Component.displayName = name;
  return Component;
};

const path = { moveTo: () => path, lineTo: () => path, addArc: () => path, quadTo: () => path, close: () => path, addRect: () => path, addRRect: () => path, addPath: () => path, detach: () => ({}) };

export const Canvas = node('Canvas');
export const Group = node('Group');
export const Path = node('Path');
export const Circle = node('Circle');
export const Fill = node('Fill');
export const Shader = node('Shader');
export const Blur = node('Blur');
export const ColorMatrix = node('ColorMatrix');
export const Paint = node('Paint');
export const RoundedRect = node('RoundedRect');
export const Skia = {
  Path: { MakeFromSVGString: () => ({}) },
  PathBuilder: { Make: () => path },
  RuntimeEffect: { Make: () => ({ makeShader: () => ({}) }) },
  Color: () => [0, 0, 0, 1],
};
export const vec = (x: number, y: number) => ({ x, y });
export const rect = (x: number, y: number, width: number, height: number) => ({ x, y, width, height });
export const rrect = (rectValue: unknown, rx: number, ry: number) => ({ rect: rectValue, rx, ry });

export const Rect = node('Rect');
export const Line = node('Line');
export const Atlas = node('Atlas');
export const Image = node('SkiaImage');
export const LinearGradient = node('LinearGradient');
export const useImage = () => null;
export const useRSXformBuffer = () => [];
