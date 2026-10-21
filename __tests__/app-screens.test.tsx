import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Image, Linking } from 'react-native';
let mockReduced:boolean|undefined = true;
jest.mock('react-native-reanimated', () => ({
  __esModule:true, default:{ View:require('react-native').View, createAnimatedComponent:(c:unknown)=>c },
  useReducedMotion:()=>mockReduced, useSharedValue:(value:number)=>({value}),
  useAnimatedStyle:(fn:()=>object)=>fn(), useAnimatedProps:(fn:()=>object)=>fn(),
  withTiming:(v:number)=>v, withRepeat:(v:number)=>v, cancelAnimation:()=>{}, interpolate:(_v:number,_i:number[],o:number[])=>o[0],
  Easing: { linear: (v:number)=>v }, FadeIn:{duration:()=>undefined},
}));
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0})}));
jest.mock('../src/native/PdfTranslator',()=>({PdfTranslator:{renderPage:jest.fn(),getLicenseNotices:jest.fn(),getLicenseNotice:jest.fn()}}));
import { PdfTranslator, BootstrapSnapshot, ModelSnapshot, DocumentSnapshot } from '../src/native/PdfTranslator';
import { LanguageChoices, ModelScreen, PairPicker, StorageScreen, SourcePreviewScreen, LicensesScreen, DictionaryScreen, TranslationOptionsScreen, SettingsScreen } from '../src/screens/AppScreens';
import { DialogProvider } from '../src/ui/Dialog';
import { createTranslator } from '../src/i18n/messages';
import { ux } from '../src/i18n/ux';
import { palettes } from '../src/ui/theme';
const native=PdfTranslator as jest.Mocked<typeof PdfTranslator>;
const t=createTranslator('en'), base={ t, colors:palettes.light, locale:'en' as const };
const model={ id:'one', label:'Pinned package', revision:'hash', tier:'compact', status:'ready', bytes:1e6, downloadedBytes:1e6, checksumPinned:true, translationEnabled:true, memoryEstimateEligible:true, diskEstimateEligible:true, licenseUrl:'https://example.com/license' } as ModelSnapshot;
const document={id:'doc',name:'Source.pdf',pageCount:2,importedAt:0} as DocumentSnapshot;
const data={schemaVersion:1,preferences:{language:'system',theme:'system'},device:{availableMemoryBytes:4e9,availableDiskBytes:5e9,recommendedModelId:'one',recommendedModelAvailable:true},models:[model],jobs:[],documents:[],glossary:[],onboarding:{done:true}} as unknown as BootstrapSnapshot;
const noop=()=>{};
it('finds language autonyms, English names, codes and unaccented text independently of UI locale',()=>{
 const choose=jest.fn();render(<LanguageChoices {...base} selected="en" onSelect={choose}/>);
 const search=screen.getByLabelText('Search');
 for(const query of ['French',' francais ','FR','Français']){
  fireEvent.changeText(search,query);
  expect(screen.getByRole('radio',{name:'Français'})).toBeOnTheScreen();
 }
 fireEvent.press(screen.getByRole('radio',{name:'Français'}));expect(choose).toHaveBeenCalledWith('fr');
 fireEvent.changeText(search,'Russian');expect(screen.getByRole('radio',{name:'Русский'})).toBeOnTheScreen();
 fireEvent.changeText(search,'not-a-language');expect(screen.queryAllByRole('radio')).toHaveLength(0);
 fireEvent.changeText(search,'');expect(screen.getAllByRole('radio')).toHaveLength(31);
});
const models=(items:ModelSnapshot[],extra:object={})=><DialogProvider colors={base.colors}><ModelScreen {...base} snapshot={{...data,models:items}} onSelect={noop} onDownload={noop} onPause={noop} onDelete={noop} {...extra}/></DialogProvider>;
beforeEach(()=>{jest.clearAllMocks();mockReduced=true;native.renderPage.mockResolvedValue('file:///source.png');native.getLicenseNotices.mockResolvedValue([]);native.getLicenseNotice.mockResolvedValue('Apache License');});
afterEach(()=>jest.restoreAllMocks());
it('keeps model details to the name and license link, with styled capability and link-failure dialogs',async()=>{
 jest.spyOn(Linking,'openURL').mockRejectedValue(new Error('offline'));
 render(models([model]));
 fireEvent.press(screen.getByText('Capability rating ⓘ'));
 expect(screen.getByText(ux('en','capabilityInfo'))).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Back'}));
 fireEvent.press(screen.getByRole('button',{name:'Details: Compact'}));
 expect(screen.getByText('Pinned package')).toBeOnTheScreen();
 expect(screen.queryByText('hash')).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:'Licenses'}));
 expect(await screen.findByText(t('unknownError'))).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Back'}));
 expect(screen.queryByText('Pinned package')).toBeNull();
});
it('shows advisory memory information, disk failures, verifying progress and zero-byte packages safely',async()=>{
 const view=render(models([{...model,memoryEstimateEligible:false,diskEstimateEligible:false,status:'not_downloaded'}]));
 fireEvent.press(screen.getByRole('button',{name:t('modelMemoryWarning')}));
 expect(screen.getByText(t('modelMemoryWarning'))).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Back'}));
 expect(screen.getByText(t('modelResourceDisk'))).toBeOnTheScreen();
 view.rerender(models([{...model,status:'verifying',bytes:0,downloadedBytes:0}],{busy:true}));
 expect(screen.getByRole('button',{name:'Verifying file…'})).toBeDisabled();
 view.rerender(models([{...model,status:'ready'}]));
 expect(screen.getByRole('button',{name:'Use this model'})).toBeOnTheScreen();
});
it('handles unsupported and unselected choices without promoting unavailable models',()=>{
 const select=jest.fn();
 render(models([{...model,translationEnabled:false},{...model,id:'b',status:'not_downloaded',licenseUrl:undefined,translationEnabled:false}],{onSelect:select}));
 expect(screen.getByText(ux('en','modelsUnavailable'))).toBeOnTheScreen();
 expect(screen.queryByRole('button',{name:'Use this model'})).toBeNull();
});
it('swaps a language pair and maintains distinct source/target selections from either sheet',()=>{
 const change=jest.fn();const view=render(<PairPicker {...base} source="en" target="ru" onChange={change}/>);
 fireEvent.press(screen.getByRole('button',{name:'Swap languages'}));expect(change).toHaveBeenLastCalledWith('ru','en');
 fireEvent.press(screen.getByRole('button',{name:'Source language: English'}));
 fireEvent.press(screen.getByRole('button',{name:'🇷🇺  Русский'}));expect(change).toHaveBeenLastCalledWith('ru','en');
 fireEvent.press(screen.getByRole('button',{name:'Source language: English'}));
 fireEvent.press(screen.getByRole('button',{name:'🇫🇷  Français'}));expect(change).toHaveBeenLastCalledWith('fr','ru');
 fireEvent.press(screen.getByRole('button',{name:'Target language: Русский'}));
 fireEvent.press(screen.getByRole('button',{name:'🇬🇧  English'}));expect(change).toHaveBeenLastCalledWith('ru','en');
 fireEvent.press(screen.getByRole('button',{name:'Target language: Русский'}));
 fireEvent.press(screen.getByRole('button',{name:'🇩🇪  Deutsch'}));expect(change).toHaveBeenLastCalledWith('en','de');
 fireEvent.press(screen.getByRole('button',{name:'Source language: English'}));
 fireEvent.press(screen.getByRole('button',{name:'Back'}));
 mockReduced=false;view.rerender(<PairPicker {...base} source="en" target="ru" onChange={change}/>);
 fireEvent.press(screen.getByRole('button',{name:'Swap languages'}));expect(change).toHaveBeenLastCalledWith('ru','en');
});
it('shows actual storage and transfer bytes separately, routes cleanup, and explains local processing',async()=>{
 const remove=jest.fn(),clear=jest.fn();jest.spyOn(Linking,'openSettings').mockRejectedValue(new Error('blocked'));
 const stored={...data,models:[{...model,storedBytes:1e6},{...model,id:'b',status:'downloading' as const,storedBytes:0,downloadedBytes:0,activeTransferBytes:2e6}],notifications:{status:'denied' as const,canRequest:false,requestedBefore:true},device:{...data.device,storage:{installedModelBytes:1e6,partialDownloadBytes:3e6,activeTransferBytes:2e6,freeDiskBytes:5e9}}};
 render(<DialogProvider colors={base.colors}><StorageScreen {...base} snapshot={stored} onDelete={remove} onClear={clear}/></DialogProvider>);
 expect(screen.getByText('3 MB')).toBeOnTheScreen();expect(screen.getByText('Downloaded: 2 MB')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Storage'}));
 expect(screen.getByText(ux('en','storageInfo'))).toBeOnTheScreen();fireEvent.press(screen.getByRole('button',{name:'Back'}));
 fireEvent.press(screen.getAllByRole('button',{name:'Compact'})[0]);expect(remove).toHaveBeenCalledWith(stored.models[0]);
 fireEvent.press(screen.getByRole('button',{name:'Remove all models'}));expect(clear).toHaveBeenCalledTimes(1);
 fireEvent.press(screen.getByRole('button',{name:'Notifications'}));expect(await screen.findByText(t('unknownError'))).toBeOnTheScreen();
});
it('computes legacy storage from ready and partial files, and disables a truly empty clear action',()=>{
 const snap={...data,models:[model,{...model,id:'paused',status:'paused' as const,downloadedBytes:2e6}]};
 const view=render(<StorageScreen {...base} snapshot={snap} onDelete={noop} onClear={noop}/>);
 expect(screen.getAllByText('2 MB')).toHaveLength(2);
 view.rerender(<StorageScreen {...base} snapshot={{...data,models:[]}} onDelete={noop} onClear={noop}/>);
 expect(screen.getByRole('button',{name:'Remove all models'})).toBeDisabled();
});
it('renders full source preview, page navigation and zoom, and reports a failed renderer',async()=>{
 const view=render(<SourcePreviewScreen {...base} document={document}/>);
 expect(await screen.findByLabelText('Original PDF 1')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Zoom in'}));fireEvent.press(screen.getByRole('button',{name:'Zoom in'}));
 expect(screen.getByLabelText('Original PDF 1')).toHaveStyle({width:990});
 fireEvent.press(screen.getByRole('button',{name:'Zoom out'}));expect(screen.getByLabelText('Original PDF 1')).toHaveStyle({width:330});
 fireEvent.press(screen.getByRole('button',{name:'Next'}));expect(await screen.findByLabelText('Original PDF 2')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Previous'}));expect(await screen.findByLabelText('Original PDF 1')).toBeOnTheScreen();
 native.renderPage.mockRejectedValueOnce(new Error('broken PDF'));
 view.rerender(<SourcePreviewScreen {...base} document={{...document,id:'bad'}}/>);
 expect(await screen.findByText(t('unknownError'))).toBeOnTheScreen();
 view.rerender(<SourcePreviewScreen {...base} document={null}/>);
 expect(screen.getByText('1 / 0')).toBeOnTheScreen();expect(screen.getByRole('button',{name:'Next'})).toBeDisabled();
});
it('ignores render success and error from a preview that has already been left',async()=>{
 let resolve!:(v:string)=>void;native.renderPage.mockReturnValue(new Promise(r=>{resolve=r;}));
 const view=render(<SourcePreviewScreen {...base} document={document}/>);view.unmount();await act(async()=>resolve('file:///late.png'));
 let reject!:(e:Error)=>void;native.renderPage.mockReturnValue(new Promise((_,r)=>{reject=r;}));
 const other=render(<SourcePreviewScreen {...base} document={document}/>);other.unmount();await act(async()=>reject(new Error('late')));
});
it('loads real notices and license text, closes the sheet, and handles notice failures',async()=>{
 native.getLicenseNotices.mockResolvedValue([{id:'apache',title:'Apache 2.0'}]);
 const view=render(<LicensesScreen {...base}/>);
 fireEvent.press(await screen.findByRole('button',{name:'Apache 2.0'}));expect(await screen.findByText('Apache License')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Back'}));native.getLicenseNotice.mockRejectedValueOnce(new Error('missing'));
 fireEvent.press(screen.getByRole('button',{name:'Apache 2.0'}));expect(await screen.findByText(t('unknownError'))).toBeOnTheScreen();
 view.unmount();native.getLicenseNotices.mockRejectedValueOnce(new Error('failed'));
 render(<LicensesScreen {...base}/>);expect(await screen.findByText(t('unknownError'))).toBeOnTheScreen();
});
it('ignores license inventory success/failure after unmount',async()=>{
 let resolve!:(v:[])=>void;native.getLicenseNotices.mockReturnValue(new Promise(r=>{resolve=r;}));
 const view=render(<LicensesScreen {...base}/>);view.unmount();await act(async()=>resolve([]));
 let reject!:(e:Error)=>void;native.getLicenseNotices.mockReturnValue(new Promise((_,r)=>{reject=r;}));
 const other=render(<LicensesScreen {...base}/>);other.unmount();await act(async()=>reject(new Error('late')));
});

it('routes all compact settings destinations and survives an unknown saved locale from an older build',()=>{
 const route=jest.fn(),theme=jest.fn(),privacy=jest.fn(),replay=jest.fn();
 const props={...base,snapshot:{...data,preferences:{...data.preferences,language:'unknown' as never}},onRoute:route,onAppearance:theme,onPrivacy:privacy,onReplay:replay};
 render(<SettingsScreen {...props}/>);
 for(const [label,destination] of [['App language','language'],['Storage','storage'],['Dictionary','dictionary'],['Licenses','licenses']]) {fireEvent.press(screen.getByRole('button',{name:label}));expect(route).toHaveBeenLastCalledWith(destination);}
 fireEvent.press(screen.getByRole('button',{name:'Appearance'}));expect(theme).toHaveBeenCalled();
 fireEvent.press(screen.getByRole('button',{name:'Privacy'}));expect(privacy).toHaveBeenCalled();
 fireEvent.press(screen.getByRole('button',{name:'Replay introduction'}));expect(replay).toHaveBeenCalled();
});
it('keeps a dictionary editor open after a failed save and lets the user dismiss it',async()=>{
 const save=jest.fn().mockResolvedValue(undefined);
 render(<DictionaryScreen {...base} entries={[]} source="en" target="ru" onPair={noop} onSave={save} onDelete={noop}/>);
 fireEvent.press(screen.getByRole('button',{name:'Add term'}));
 fireEvent.changeText(screen.getByLabelText('Source term'),'  pump  ');fireEvent.changeText(screen.getByLabelText('Target term'),'  насос  ');
 fireEvent.press(screen.getByRole('button',{name:'Save term'}));await waitFor(()=>expect(save).toHaveBeenCalledWith('pump','насос',undefined));
 expect(screen.getByLabelText('Source term')).toHaveDisplayValue('  pump  ');
 fireEvent.press(screen.getByRole('button',{name:'Back'}));expect(screen.queryByLabelText('Source term')).toBeNull();
});
it('does not apply a stale options thumbnail after the document changes or the screen is left',async()=>{
 let resolve!:(v:string)=>void;native.renderPage.mockReturnValue(new Promise(r=>{resolve=r;}));
 const props={...base,document,source:'en' as const,target:'ru' as const,terms:2,useGlossary:true,onUseGlossary:noop,onPair:noop,onDictionary:noop,onPreview:noop,onModel:noop,onStart:noop};
 const view=render(<TranslationOptionsScreen {...props}/>);view.rerender(<TranslationOptionsScreen {...props} document={null}/>);
 await act(async()=>resolve('file:///stale.png'));expect(screen.UNSAFE_queryAllByType(Image)).toHaveLength(0);
 expect(screen.getByRole('button',{name:'Translate PDF'})).toBeDisabled();
 native.renderPage.mockRejectedValueOnce(new Error('bad preview'));
 view.rerender(<TranslationOptionsScreen {...props} model={undefined}/>);await act(async()=>{});
 expect(screen.getByText('Choose a model')).toBeOnTheScreen();
});
it('keeps cleanup available for a newly started zero-byte transfer and disables it for empty model placeholders',()=>{
 const placeholder={...model,status:'not_downloaded' as const,downloadedBytes:0,storedBytes:0};
 const view=render(<StorageScreen {...base} snapshot={{...data,models:[placeholder]}} onDelete={noop} onClear={noop}/>);
 expect(screen.getByRole('button',{name:'Remove all models'})).toBeDisabled();
 view.rerender(<StorageScreen {...base} snapshot={{...data,models:[{...placeholder,status:'downloading'}]}} onDelete={noop} onClear={noop}/>);
 expect(screen.getByRole('button',{name:'Remove all models'})).toBeEnabled();
});
it('animates a newly discovered ready model without celebrating an already-ready inventory',()=>{
 mockReduced=false;const view=render(models([model]));
 view.rerender(models([{...model,id:'new-ready'}]));
 expect(screen.getByText('Ready on this device')).toBeOnTheScreen();
 mockReduced=undefined;
 view.rerender(models([{...model,id:'unknown-reduced'}]));
 expect(screen.getByRole('button',{name:'Use this model'})).toBeEnabled();
});

it('falls back to static language swapping when motion availability is unknown',()=>{
 mockReduced=undefined;render(<PairPicker {...base} source="en" target="ru" onChange={noop}/>);
 fireEvent.press(screen.getByRole('button',{name:'Swap languages'}));
 expect(screen.getByRole('button',{name:'Source language: English'})).toBeOnTheScreen();
});
