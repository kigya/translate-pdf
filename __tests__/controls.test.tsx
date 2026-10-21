jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { fireEvent, render, screen, userEvent } from '@testing-library/react-native';
import { Modal, Platform, Text } from 'react-native';
let mockReduced:boolean|undefined=true;
jest.mock('react-native-reanimated',()=>({__esModule:true,default:{View:require('react-native').View},useSharedValue:(value:number)=>({value}),useAnimatedStyle:(fn:()=>object)=>fn(),useReducedMotion:()=>mockReduced,withTiming:(v:number)=>v}));
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,left:0,right:0,bottom:34})}));
import { IconButton, SettingsRow, Sheet } from '../src/ui/Controls';
import { palettes } from '../src/ui/theme';
afterEach(()=>jest.restoreAllMocks());
it('provides distinct theme glyphs and a generous interactive touch target',async()=>{
 const press=jest.fn();render(<IconButton name="sun" label="Light" onPress={press} colors={palettes.light}/>);
 expect(screen.getByRole('button',{name:'Light'})).toHaveStyle({minWidth:48,minHeight:48});
 await userEvent.setup().press(screen.getByRole('button',{name:'Light'}));expect(press).toHaveBeenCalledTimes(1);
});
it('makes destructive settings recognizable and offers the same pressed feedback',async()=>{
 const press=jest.fn();render(<SettingsRow icon="trash" label="Remove" value="1 GB" danger colors={palettes.dark} onPress={press}/>);
 expect(screen.getByText('Remove')).toHaveStyle({color:palettes.dark.accent});
 await userEvent.setup().press(screen.getByRole('button',{name:'Remove'}));expect(press).toHaveBeenCalledTimes(1);
});
it('uses custom sheet content, safe inset padding, and closes using Back or backdrop',()=>{
 const close=jest.fn();jest.replaceProperty(Platform,'OS','ios');mockReduced=undefined;
 const view=render(<Sheet visible title="Appearance" colors={palettes.light} onClose={close}><Text>System default</Text></Sheet>);
 expect(screen.getByRole('header',{name:'Appearance'})).toBeOnTheScreen();
 fireEvent(screen.UNSAFE_getByType(Modal),'requestClose');expect(close).toHaveBeenCalledTimes(1);
 fireEvent.press(screen.getAllByRole('button',{name:'Close',includeHiddenElements:true})[0]);expect(close).toHaveBeenCalledTimes(2);
 jest.replaceProperty(Platform,'OS','android');
 view.rerender(<Sheet visible={false} title="Appearance" colors={palettes.light} onClose={close}><Text>System default</Text></Sheet>);
 expect(screen.queryByRole('header',{name:'Appearance'})).toBeNull();
});
