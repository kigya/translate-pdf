jest.mock('../src/ui/useMotionReduced', () => ({ useMotionReduced: () => require('react-native-reanimated').useReducedMotion() ?? false }));
import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native-legacy';
import { Image, Linking, View } from 'react-native';
let mockReduced:boolean|undefined = true;
jest.mock('react-native-reanimated', () => ({
  __esModule:true, default:{ View:require('react-native').View, Text:require('react-native').Text, ScrollView:require('react-native').ScrollView, Image:require('react-native').Image, createAnimatedComponent:(c:unknown)=>c },
  useReducedMotion:()=>mockReduced, useSharedValue:(value:number)=>({value,get(){return this.value;},set(next:number){this.value=next;}}), useDerivedValue:(fn:()=>unknown)=>({get:fn}), useFrameCallback:()=>({setActive:jest.fn()}), useAnimatedRef:()=>require('react').useRef(null), useAnimatedScrollHandler:()=>jest.fn(),
  useAnimatedStyle:(fn:()=>object)=>fn(), useAnimatedProps:(fn:()=>object)=>fn(),
  withTiming:(v:number,_options?:unknown,callback?:(finished:boolean)=>void)=>{callback?.(true);return v;}, withRepeat:(v:number)=>v, withSpring:(v:number)=>v, withDelay:(_d:number,v:number)=>v, withSequence:(...v:number[])=>v.at(-1), cancelAnimation:()=>{}, interpolate:(_v:number,_i:number[],o:number[])=>o[0], interpolateColor:(_v:number,_i:number[],o:string[])=>o[0], ReduceMotion:{Always:'always',Never:'never'},
  Easing: { linear: (v:number)=>v, bezier: (..._values:number[]) => (v:number)=>v, sin: (v:number)=>v, inOut: (easing:unknown)=>easing }, FadeIn:{duration:()=>undefined}, FadeInDown:{delay:()=>({duration:()=>({easing:()=>undefined})})},
}));
jest.mock('react-native-safe-area-context',()=>({useSafeAreaInsets:()=>({top:0,right:0,bottom:0,left:0})}));
jest.mock('../src/native/PdfTranslator',()=>({PdfTranslator:{renderPage:jest.fn(),renderTranslatedPage:jest.fn(),renderDocxPreview:jest.fn(),exportDocument:jest.fn(),getLicenseNotices:jest.fn(),getLicenseNotice:jest.fn()}}));
jest.mock('../src/components/kinetik/star-rating',()=>({StarRating:({value,accessibilityLabel}:{value:number;accessibilityLabel:string})=>require('react').createElement(require('react-native').View,{accessibilityRole:'adjustable',accessibilityLabel,accessibilityValue:{min:0,max:5,now:value}})}));
jest.mock('../src/components/kinetik/ring-spinner',()=>({RingSpinner:({accessibilityLabel,progress}:{accessibilityLabel:string;progress?:number})=>require('react').createElement(require('react-native').View,{accessible:true,accessibilityRole:'progressbar',accessibilityLabel,accessibilityValue:progress===undefined?undefined:{min:0,max:100,now:Math.round(progress*100)}})}));
import { PdfTranslator, BootstrapSnapshot, ModelSnapshot, DocumentSnapshot, JobSnapshot } from '../src/native/PdfTranslator';
import { LanguageChoices, LibraryScreen, ModelScreen, PairPicker, StorageScreen, SourcePreviewScreen, LicensesScreen, DictionaryScreen, TranslationOptionsScreen, SettingsScreen } from '../src/screens/AppScreens';
import { WordResultScreen } from '../src/screens/WordResultScreen';
import { Button } from '../src/ui/components';
import { DialogProvider } from '../src/ui/Dialog';
import { createTranslator } from '../src/i18n/messages';
import { ux } from '../src/i18n/ux';
import { palettes } from '../src/ui/theme';
import { ONLINE_MODEL } from '../src/domain/presentation';
const native=PdfTranslator as jest.Mocked<typeof PdfTranslator>;
const t=createTranslator('en'), base={ t, colors:palettes.light, locale:'en' as const };
const model={ id:'one', label:'Pinned package', revision:'hash', tier:'compact', status:'ready', bytes:1e6, downloadedBytes:1e6, checksumPinned:true, translationEnabled:true, memoryEstimateEligible:true, diskEstimateEligible:true, licenseUrl:'https://example.com/license' } as ModelSnapshot;
const document={id:'doc',name:'Source.pdf',pageCount:2,importedAt:0} as DocumentSnapshot;
const data={schemaVersion:1,preferences:{language:'system',theme:'system'},device:{availableMemoryBytes:4e9,availableDiskBytes:5e9,recommendedModelId:'one',recommendedModelAvailable:true},models:[model],jobs:[],documents:[],glossary:[],onboarding:{done:true}} as unknown as BootstrapSnapshot;
const noop=()=>{};
it('finds language autonyms, English names, codes and unaccented text independently of UI locale',()=>{
 const choose=jest.fn();render(<LanguageChoices {...base} selected="en" onSelect={choose}/>);
 const search=screen.getByLabelText('Search');
 expect([search.props.cursorColor,search.props.selectionColor,search.props.selectionHandleColor]).toEqual([base.colors.accent,base.colors.accent,base.colors.accent]);
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
beforeEach(()=>{jest.clearAllMocks();mockReduced=true;native.renderPage.mockResolvedValue('file:///source.png');native.renderTranslatedPage.mockResolvedValue('file:///translated.png');native.renderDocxPreview.mockResolvedValue({uri:'file:///preview.pdf',pageCount:2} as never);native.exportDocument.mockResolvedValue({id:'saved',uri:'file:///saved.docx',displayName:'saved.docx',mimeType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document'});native.getLicenseNotices.mockResolvedValue([]);native.getLicenseNotice.mockResolvedValue('Apache License');});
afterEach(()=>jest.restoreAllMocks());
it('hides package details while folded and keeps license access within its expanded panel',async()=>{
 jest.spyOn(Linking,'openURL').mockRejectedValue(new Error('offline'));
 render(models([model]));
 expect(screen.queryByText(t('qualityPending'))).toBeNull();
 expect(screen.getByRole('button',{name:/^Compact.*70%/})).toBeOnTheScreen();
 expect(screen.queryByText('Pinned package')).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 expect(screen.getByRole('text',{name:'Pinned package'})).toBeOnTheScreen();
 expect(screen.queryByText('hash')).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:'Licenses'}));
 await waitFor(()=>expect(screen.getAllByRole('button',{name:'Licenses'})).toHaveLength(2));
 fireEvent.press(screen.getAllByRole('button',{name:'Licenses'}).at(-1)!);
 expect((await screen.findAllByText(t('unknownError'))).length).toBeGreaterThan(0);
 fireEvent.press(screen.getAllByRole('button',{name:'Back'}).at(-1)!);
 expect(screen.queryByText('Pinned package')).toBeNull();
});
it('reveals the full local model name, license and download action only when its FoldPanel opens',()=>{
 const download=jest.fn();
 render(models([{...model,status:'paused'}],{onDownload:download}));
 expect(screen.queryByText('Pinned package')).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 expect(screen.getByRole('text',{name:'Pinned package'})).toBeOnTheScreen();
 expect(screen.getByText('Apache License 2.0')).toBeOnTheScreen();
 fireEvent.press(screen.getAllByRole('button',{name:'Resume'}).at(0)!);
 expect(download).toHaveBeenCalledWith(expect.objectContaining({id:'one'}));
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 expect(screen.queryByText('Pinned package')).toBeNull();
});
it('keeps the Online panel to its tier label until expanded, then reveals MyMemory terms and one select action',()=>{
 const select=jest.fn();
 render(models([],{selected:model,onSelect:select}));
 expect(screen.queryByText('MyMemory')).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:/^Online/}));
 expect(screen.getByRole('text',{name:'MyMemory'})).toBeOnTheScreen();
 expect(screen.getByText('MyMemory Terms')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Use this model'}));
 expect(select).toHaveBeenCalledTimes(1);
 fireEvent.press(screen.getByRole('button',{name:/^Online/}));
 expect(screen.queryByText('MyMemory')).toBeNull();
});
it('opens the Online provider license from the expanded panel',async()=>{
 const openURL=jest.spyOn(Linking,'openURL').mockResolvedValue(undefined);
 render(models([]));
 fireEvent.press(screen.getByRole('button',{name:/^Online/}));
 fireEvent.press(screen.getByRole('button',{name:'Licenses'}));
 const licenseActions=await screen.findAllByRole('button',{name:'Licenses'});
 fireEvent.press(licenseActions.at(-1)!);
 await waitFor(()=>expect(openURL).toHaveBeenCalledWith(ONLINE_MODEL.licenseUrl));
});
it('removes a ready local model from its single expanded model panel',()=>{
 const remove=jest.fn();
 render(models([model],{onDelete:remove}));
 expect(screen.queryByRole('button',{name:'Remove model'})).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 fireEvent.press(screen.getByRole('button',{name:'Remove model'}));
 expect(remove).toHaveBeenCalledTimes(1);
 expect(remove).toHaveBeenCalledWith(expect.objectContaining({id:'one'}));
});
it('shows advisory memory information, disk failures, verifying progress and zero-byte packages safely',async()=>{
 const view=render(models([{...model,memoryEstimateEligible:false,diskEstimateEligible:false,status:'not_downloaded'}]));
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 fireEvent.press(screen.getByRole('button',{name:t('modelMemoryWarning')}));
 expect(screen.getByText(t('modelMemoryWarning'))).toBeOnTheScreen();
 fireEvent.press(screen.getAllByRole('button',{name:'Back'}).at(-1)!);
 expect(screen.getByText(t('modelResourceDisk'))).toBeOnTheScreen();
 view.rerender(models([{...model,status:'verifying',bytes:0,downloadedBytes:0}],{busy:true}));
 expect(screen.getAllByRole('button',{name:/Verifying file/}).some(button=>button.props.accessibilityState?.busy===true && button.props.accessibilityState?.disabled===true)).toBe(true);
 view.rerender(models([{...model,status:'ready'}]));
 expect(screen.getAllByRole('button',{name:'Use this model'})).toHaveLength(1);
});
it('handles unsupported and unselected choices without promoting unavailable models',()=>{
 const select=jest.fn();
 render(models([{...model,translationEnabled:false},{...model,id:'b',status:'not_downloaded',licenseUrl:undefined,translationEnabled:false}],{onSelect:select}));
 expect(screen.getByText(ux('en','modelsUnavailable'))).toBeOnTheScreen();
 expect(screen.queryByRole('button',{name:'Use this model'})).toBeNull();
 expect(screen.queryByText('MyMemory')).toBeNull();
});
it('swaps a language pair and maintains distinct source/target selections from either sheet',()=>{
 const change=jest.fn();const props={...base,source:'en' as const,target:'ru' as const,onChange:change};const view=render(<PairPicker {...props}/>);
 fireEvent.press(screen.getByRole('button',{name:'Swap languages'}));expect(change).toHaveBeenLastCalledWith('ru','en');
 view.rerender(<PairPicker {...props} source="ru" target="en"/>);
 fireEvent.press(screen.getAllByRole('button',{name:/^Source language/})[0]);
 fireEvent.press(screen.getByRole('menuitem',{name:/Français/}));expect(change).toHaveBeenLastCalledWith('fr','en');
 view.rerender(<PairPicker {...props} source="fr" target="en"/>);
 fireEvent.press(screen.getAllByRole('button',{name:/^Source language/})[0]);
 fireEvent.press(screen.getByRole('menuitem',{name:/English/}));expect(change).toHaveBeenLastCalledWith('en','fr');
 view.rerender(<PairPicker {...props} source="fr" target="en"/>);
 fireEvent.press(screen.getAllByRole('button',{name:/^Target language/})[0]);
 fireEvent.press(screen.getByRole('menuitem',{name:/Deutsch/}));expect(change).toHaveBeenLastCalledWith('fr','de');
 view.rerender(<PairPicker {...props} source="fr" target="en"/>);
 fireEvent.press(screen.getAllByRole('button',{name:/^Target language/})[0]);
 fireEvent.press(screen.getByRole('menuitem',{name:/Français/}));expect(change).toHaveBeenLastCalledWith('en','fr');
 fireEvent.press(screen.getAllByRole('button',{name:/^Source language/})[0]);
 fireEvent.press(screen.getByRole('button',{name:'Back'}));
 mockReduced=false;view.rerender(<PairPicker {...props}/>);
 fireEvent.press(screen.getByRole('button',{name:'Swap languages'}));expect(change).toHaveBeenLastCalledWith('ru','en');
});
it('shows actual storage and transfer bytes separately, routes cleanup, and explains local processing',async()=>{
 const remove=jest.fn(),clear=jest.fn();jest.spyOn(Linking,'openSettings').mockRejectedValue(new Error('blocked'));
 const stored={...data,models:[{...model,storedBytes:1e6},{...model,id:'b',status:'downloading' as const,storedBytes:0,downloadedBytes:0,activeTransferBytes:2e6}],notifications:{status:'denied' as const,canRequest:false,requestedBefore:true},device:{...data.device,storage:{installedModelBytes:1e6,partialDownloadBytes:3e6,activeTransferBytes:2e6,freeDiskBytes:5e9}}};
 render(<DialogProvider colors={base.colors}><StorageScreen {...base} snapshot={stored} onDelete={remove} onClear={clear}/></DialogProvider>);
 expect(screen.getByText('3 MB')).toBeOnTheScreen();expect(screen.getByText('Downloaded: 2 MB')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Storage'}));
 expect(screen.getByText(ux('en','storageInfo'))).toBeOnTheScreen();fireEvent.press(screen.getAllByRole('button',{name:'Back'}).at(-1)!);
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
 expect((await screen.findAllByText(t('unknownError'))).length).toBeGreaterThan(0);
 view.rerender(<SourcePreviewScreen {...base} document={null}/>);
 expect(screen.getByText('1 / 2')).toBeOnTheScreen();expect(screen.getByRole('button',{name:'Next'})).toBeDisabled();
});
it('keeps an empty source-preview state local when no document is selected', async()=>{
 native.renderPage.mockClear();
 native.renderDocxPreview.mockClear();
 render(<SourcePreviewScreen {...base} document={null}/>);
 expect(screen.getByRole('button',{name:'Next'})).toBeDisabled();
 expect(native.renderPage).not.toHaveBeenCalled();
 expect(native.renderDocxPreview).not.toHaveBeenCalled();
});
it('previews DOCX pages for the source and prepare flows and reports an unavailable conversion',async()=>{
 const word={id:'word-source',name:'Guide.docx',format:'docx',pageCount:4,segmentCount:12,importedAt:0} as DocumentSnapshot;
 native.renderPage.mockResolvedValue('file:///word-page.png');
 const prepare={...base,document:word,source:'en' as const,target:'ru' as const,terms:0,useGlossary:false,onUseGlossary:noop,onPair:noop,onDictionary:noop,onPreview:noop,onModel:noop,onStart:noop};
 const options=render(<TranslationOptionsScreen {...prepare}/>);
 expect(await screen.findByLabelText('Original')).toBeOnTheScreen();
 expect(native.renderDocxPreview).toHaveBeenCalledWith('word-source');
 expect(native.renderPage).toHaveBeenCalledWith('word-source',0,420);
 options.unmount();

 const preview=render(<SourcePreviewScreen {...base} document={word}/>);
 expect(await screen.findByLabelText('Original 1')).toBeOnTheScreen();
 expect(screen.getByText('1 / 2')).toBeOnTheScreen();
 expect(native.renderDocxPreview).toHaveBeenLastCalledWith('word-source');
 fireEvent.press(screen.getByRole('button',{name:'Next'}));
 expect(await screen.findByLabelText('Original 2')).toBeOnTheScreen();
 expect(native.renderPage).toHaveBeenLastCalledWith('word-source',1,1200);
 native.renderDocxPreview.mockRejectedValueOnce(new Error('conversion unavailable'));
 preview.rerender(<SourcePreviewScreen {...base} document={{...word,id:'broken-word'}}/>);
 expect((await screen.findAllByText(t('unknownError'))).length).toBeGreaterThan(0);
});
it('uses the DOCX segment-count fallback for unindexed imports in the library and prepare flow', async()=>{
 const word={id:'legacy-word',name:'Legacy.docx',format:'docx',pageCount:4,importedAt:0} as DocumentSnapshot;
 const snapshot={...data,documents:[word],jobs:[]};
 render(<LibraryScreen {...base} snapshot={snapshot} folder="all" onDocument={noop} onJob={noop} onTranslate={noop} onModel={noop} />);
 expect(screen.getByText(/4\s+segments/)).toBeOnTheScreen();
 const props={...base,document:word,source:'en' as const,target:'ru' as const,terms:0,useGlossary:false,onUseGlossary:noop,onPair:noop,onDictionary:noop,onPreview:noop,onModel:noop,onStart:noop};
 render(<TranslationOptionsScreen {...props}/>);
 expect(screen.getByText(/4\s+segments/)).toBeOnTheScreen();
 await screen.findByLabelText('Original');
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
 fireEvent.press(screen.getAllByRole('button',{name:'Back'}).at(-1)!);native.getLicenseNotice.mockRejectedValueOnce(new Error('missing'));
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
 const props={...base,snapshot:{...data,preferences:{...data.preferences,language:'unknown' as never}},onRoute:route,onTheme:theme,onPrivacy:privacy,onReplay:replay};
 render(<SettingsScreen {...props}/>);
 for(const [label,destination] of [['App language','language'],['Storage','storage'],['Dictionary','dictionary'],['Licenses','licenses']]) {fireEvent.press(screen.getByRole('button',{name:label}));expect(route).toHaveBeenLastCalledWith(destination);}
 fireEvent.press(screen.getByRole('switch',{name:'Appearance'}));expect(theme).toHaveBeenCalledWith('dark');
 fireEvent.press(screen.getByRole('button',{name:'Privacy'}));expect(privacy).toHaveBeenCalled();
 fireEvent.press(screen.getByRole('button',{name:'View tutorial'}));expect(replay).toHaveBeenCalled();
});
it('switches between follow-system and manual appearance and disables the control while busy',()=>{
 const theme=jest.fn();
 const props={...base,snapshot:data,onRoute:noop,onTheme:theme,onPrivacy:noop,onReplay:noop};
 const view=render(<SettingsScreen {...props}/>);
 const follow=screen.getByRole('switch',{name:'Use system appearance'});
 expect(follow).toBeEnabled();
 fireEvent(follow,'valueChange',false);
 expect(theme).toHaveBeenCalledWith('light');
 view.rerender(<SettingsScreen {...props} snapshot={{...data,preferences:{...data.preferences,theme:'light'}}}/>);
 fireEvent(screen.getByRole('switch',{name:'Use system appearance'}),'valueChange',true);
 expect(theme).toHaveBeenLastCalledWith('system');
 view.rerender(<SettingsScreen {...props} busy snapshot={{...data,preferences:{...data.preferences,theme:'system'}}}/>);
 expect(screen.getByRole('switch',{name:'Use system appearance'})).toBeDisabled();
});
it('renders a legacy settings snapshot with no saved theme and permits a manual theme choice',()=>{
 const theme=jest.fn();
 const legacy={...data,preferences:{language:'system'}} as unknown as BootstrapSnapshot;
 render(<SettingsScreen {...base} snapshot={legacy} onRoute={noop} onTheme={theme} onPrivacy={noop} onReplay={noop}/>);
 expect(screen.getByRole('switch',{name:'Use system appearance'}).props.accessibilityState).toEqual(expect.objectContaining({checked:false}));
 fireEvent.press(screen.getByRole('switch',{name:'Appearance'}));
 expect(theme).toHaveBeenCalledWith('dark');
});
it('uses the active dark palette when leaving system appearance from a legacy snapshot',()=>{
 const theme=jest.fn();
 const legacy={...data,preferences:{language:'system'}} as unknown as BootstrapSnapshot;
 render(<SettingsScreen {...base} colors={palettes.dark} snapshot={legacy} onRoute={noop} onTheme={theme} onPrivacy={noop} onReplay={noop}/>);
 fireEvent(screen.getByRole('switch',{name:'Use system appearance'}),'valueChange',false);
 expect(theme).toHaveBeenCalledWith('dark');
});
it('hides Dictionary from translation options and Settings while Online is selected',()=>{
 const options={...base,document:null,model:ONLINE_MODEL,source:'en' as const,target:'ru' as const,terms:2,useGlossary:true,onUseGlossary:noop,onPair:noop,onDictionary:noop,onPreview:noop,onModel:noop,onStart:noop};
 render(<TranslationOptionsScreen {...options}/>);
 expect(screen.queryByText('Dictionary')).toBeNull();
 const route=jest.fn();
 render(<SettingsScreen {...base} snapshot={data} selectedModel={ONLINE_MODEL} onRoute={route} onTheme={noop} onPrivacy={noop} onReplay={noop}/>);
 expect(screen.queryByRole('button',{name:'Dictionary'})).toBeNull();
});
it('keeps a dictionary editor open after a failed save and lets the user dismiss it',async()=>{
 const save=jest.fn().mockResolvedValue(undefined);
 render(<DictionaryScreen {...base} entries={[]} source="en" target="ru" onPair={noop} onSave={save} onDelete={noop}/>);
 const search=screen.getByLabelText('Search');
 expect([search.props.cursorColor,search.props.selectionColor,search.props.selectionHandleColor]).toEqual([base.colors.accent,base.colors.accent,base.colors.accent]);
 fireEvent.press(screen.getByRole('button',{name:'Add term'}));
 for(const label of ['Source term','Target term']){
  const input=screen.getByLabelText(label);
  expect([input.props.cursorColor,input.props.selectionColor,input.props.selectionHandleColor]).toEqual([base.colors.accent,base.colors.accent,base.colors.accent]);
 }
 fireEvent.changeText(screen.getByLabelText('Source term'),'  pump  ');fireEvent.changeText(screen.getByLabelText('Target term'),'  насос  ');
 fireEvent.press(screen.getByRole('button',{name:'Save term'}));await waitFor(()=>expect(save).toHaveBeenCalledWith('pump','насос',undefined));
 expect(screen.getByLabelText('Source term')).toHaveDisplayValue('  pump  ');
 fireEvent.press(screen.getAllByRole('button',{name:'Back'}).at(-1)!);expect(screen.queryByLabelText('Source term')).toBeNull();
});
it('filters dictionary entries by pair and search, then edits and deletes the selected entry',()=>{
 const save=jest.fn().mockResolvedValue(undefined),remove=jest.fn(),change=jest.fn();
 const entries=[
  {id:'pump',source:'pump',target:'насос',sourceLanguage:'en',targetLanguage:'ru'},
  {id:'valve',source:'valve',target:'клапан',sourceLanguage:'en',targetLanguage:'ru'},
  {id:'bonjour',source:'bonjour',target:'здравствуйте',sourceLanguage:'fr',targetLanguage:'ru'},
 ] as BootstrapSnapshot['glossary'];
 const view=render(<DictionaryScreen {...base} entries={entries} source="en" target="ru" onPair={change} onSave={save} onDelete={remove}/>);
 expect(screen.getByText('pump')).toBeOnTheScreen();
 expect(screen.getByText('valve')).toBeOnTheScreen();
 expect(screen.queryByText('bonjour')).toBeNull();
 fireEvent.changeText(screen.getByLabelText('Search'),'PUMP');
 expect(screen.getByText('pump')).toBeOnTheScreen();
 expect(screen.queryByText('valve')).toBeNull();
 fireEvent.changeText(screen.getByLabelText('Search'),'no match');
 expect(screen.queryByText('pump')).toBeNull();
 fireEvent.changeText(screen.getByLabelText('Search'),'');
 fireEvent.press(screen.getByRole('button',{name:'Edit term: pump'}));
 expect(screen.getByLabelText('Source term')).toHaveDisplayValue('pump');
 fireEvent.changeText(screen.getByLabelText('Target term'),'помпа');
 fireEvent.press(screen.getByRole('button',{name:'Save term'}));
 expect(save).toHaveBeenCalledWith('pump','помпа','pump');
 fireEvent.press(screen.getByRole('button',{name:'Remove term: valve'}));
 expect(remove).toHaveBeenCalledWith('valve');
 view.rerender(<DictionaryScreen {...base} entries={[]} source="en" target="ru" onPair={change} onSave={save} onDelete={remove}/>);
 expect(screen.getByText(ux('en','dictionaryEmpty'))).toBeOnTheScreen();
});
it('does not apply a stale options thumbnail after the document changes or the screen is left',async()=>{
 let resolve!:(v:string)=>void;native.renderPage.mockReturnValue(new Promise(r=>{resolve=r;}));
 const props={...base,document,source:'en' as const,target:'ru' as const,terms:2,useGlossary:true,onUseGlossary:noop,onPair:noop,onDictionary:noop,onPreview:noop,onModel:noop,onStart:noop};
 const view=render(<TranslationOptionsScreen {...props}/>);view.rerender(<TranslationOptionsScreen {...props} document={null}/>);
 await act(async()=>resolve('file:///stale.png'));expect(screen.UNSAFE_queryAllByType(Image)).toHaveLength(0);
 expect(screen.getByRole('button',{name:'Translate documents'})).toBeDisabled();
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
 expect(screen.getByRole('button',{name:/^Compact.*Ready on this device/})).toBeOnTheScreen();
 mockReduced=undefined;
 view.rerender(models([{...model,id:'unknown-reduced'}]));
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 expect(screen.getAllByRole('button',{name:'Use this model'}).at(-1)).toBeEnabled();
});

it('falls back to static language swapping when motion availability is unknown',()=>{
 mockReduced=undefined;render(<PairPicker {...base} source="en" target="ru" onChange={noop}/>);
 fireEvent.press(screen.getByRole('button',{name:'Swap languages'}));
 expect(screen.getAllByRole('button',{name:/^Source language/})[0]).toBeOnTheScreen();
});

it('previews DOCX as rendered pages and exports both Word and PDF independently',async()=>{
 const job={id:'word-job',documentId:'word-doc',documentName:'guide.docx',modelId:'mymemory',engine:{kind:'online',id:'mymemory'},documentFormat:'docx',progressUnit:'segment',sourceLanguage:'en',targetLanguage:'ru',status:'completed',stage:'complete',completedPages:6,totalPages:6,currentPage:null,createdAt:1,updatedAt:1,warnings:[{code:'preserved',message:'Some Word elements were preserved unchanged.'}]} as JobSnapshot;
 native.renderPage.mockResolvedValue('file:///original-page.png');
 native.renderTranslatedPage.mockResolvedValue('file:///translated-page.png');
 render(<WordResultScreen job={job} colors={base.colors} t={t} locale="en"/>);
 expect(await screen.findByLabelText('Translation 1')).toBeOnTheScreen();
 expect(screen.getByText('Some Word elements were preserved unchanged.')).toBeOnTheScreen();
 expect(screen.getByText('1 / 2')).toBeOnTheScreen();
 expect(native.renderDocxPreview).toHaveBeenCalledWith('word-doc','word-job',true);
 fireEvent.press(screen.getByRole('button',{name:'Next'}));
 expect(await screen.findByLabelText('Translation 2')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Previous'}));
 expect(await screen.findByLabelText('Translation 1')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Next'}));
 expect(await screen.findByLabelText('Translation 2')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Original'}));
 expect(await screen.findByLabelText('Original 1')).toBeOnTheScreen();
 expect(native.renderDocxPreview).toHaveBeenLastCalledWith('word-doc','word-job',false);
 fireEvent.press(screen.getByRole('button',{name:'Save Word (.docx)'}));
 await waitFor(()=>expect(native.exportDocument).toHaveBeenCalledWith('word-job','docx'));
 fireEvent.press(screen.getByRole('button',{name:'Save PDF'}));
 await waitFor(()=>expect(native.exportDocument).toHaveBeenCalledWith('word-job','pdf'));
});

it('holds the Word page geometry and zero page count until the preview is ready',async()=>{
 let resolvePreview!:(preview:{uri:string;pageCount:number})=>void;
 native.renderDocxPreview.mockReturnValueOnce(new Promise(resolve=>{resolvePreview=resolve;}) as never);
 const view=render(<WordResultScreen job={{id:'word-pending',documentId:'word-source',documentFormat:'docx'} as JobSnapshot} colors={base.colors} t={t} locale="en"/>);
 expect(screen.getByText('0 / 0')).toBeOnTheScreen();
 const placeholder=view.UNSAFE_getAllByType(View).find(node=>node.props.style && !Array.isArray(node.props.style) && node.props.style.width===330);
 expect(placeholder?.props.style).toEqual(expect.objectContaining({width:330,height:470}));
 fireEvent.press(screen.getByRole('button',{name:'Zoom in'}));
 const zoomed=view.UNSAFE_getAllByType(View).find(node=>node.props.style && !Array.isArray(node.props.style) && node.props.style.width===660);
 expect(zoomed?.props.style).toEqual(expect.objectContaining({width:660,height:940}));
 view.unmount();
 await act(async()=>{resolvePreview({uri:'file:///late-pending.pdf',pageCount:3});await Promise.resolve();});
});

it('keeps exports available after preview failure, serializes exports, and bounds physical-page navigation',async()=>{
 const job={id:'word-retry',documentId:'word-source',documentName:'notes.docx',modelId:'mymemory',engine:{kind:'online',id:'mymemory'},documentFormat:'docx',progressUnit:'segment',sourceLanguage:'en',targetLanguage:'ru',status:'partial',stage:'layout',completedPages:4,totalPages:8,currentPage:5,createdAt:1,updatedAt:1,warnings:['One unsupported item stayed unchanged.']} as JobSnapshot;
 native.renderDocxPreview.mockRejectedValueOnce(new Error('render unavailable'));
 native.renderDocxPreview.mockResolvedValueOnce({uri:'file:///one-page.pdf',pageCount:1} as never);
 let rejectExport!:(error:Error)=>void;
 native.exportDocument.mockReturnValueOnce(new Promise((_,reject)=>{rejectExport=reject;}) as never);
 render(<WordResultScreen job={job} colors={base.colors} t={t} locale="en"/>);
 expect((await screen.findAllByText(t('unknownError'))).length).toBeGreaterThan(0);
 expect(screen.getByText('One unsupported item stayed unchanged.')).toBeOnTheScreen();
 expect(screen.getByText(t('issues'))).toBeOnTheScreen();
 expect(screen.getByRole('button',{name:'Save Word (.docx)'})).toBeEnabled();

 fireEvent.press(screen.getByRole('button',{name:'Save Word (.docx)'}));
 expect(screen.getByRole('button',{name:'Save Word (.docx)'})).toBeDisabled();
 expect(screen.getByRole('button',{name:'Save PDF'})).toBeDisabled();
 const docxButton=screen.UNSAFE_getAllByType(Button).find(button=>button.props.label==='Save Word (.docx)');
 expect(docxButton).toBeDefined();
 await act(async()=>docxButton?.props.onPress?.());
 expect(native.exportDocument).toHaveBeenCalledTimes(1);
 fireEvent.press(screen.getByRole('button',{name:'Save PDF'}));
 expect(native.exportDocument).toHaveBeenCalledTimes(1);
 await act(async()=>rejectExport(new Error('write failed')));
 expect((await screen.findAllByText(t('unknownError'))).length).toBeGreaterThan(0);
 expect(screen.getByRole('button',{name:'Save PDF'})).toBeEnabled();

 fireEvent.press(screen.getByRole('button',{name:'Original'}));
 expect(await screen.findByLabelText('Original 1')).toBeOnTheScreen();
 expect(screen.getByText('1 / 1')).toBeOnTheScreen();
 expect(screen.getByRole('button',{name:'Previous'})).toBeDisabled();
 expect(screen.getByRole('button',{name:'Next'})).toBeDisabled();
 expect(native.renderDocxPreview).toHaveBeenLastCalledWith('word-source','word-retry',false);
});

it('clamps Word page navigation when a replacement preview has fewer physical pages',async()=>{
 const job={id:'word-long',documentId:'word-source',documentName:'long.docx',modelId:'mymemory',engine:{kind:'online',id:'mymemory'},documentFormat:'docx',progressUnit:'segment',sourceLanguage:'en',targetLanguage:'ru',status:'completed',stage:'complete',completedPages:30,totalPages:30,currentPage:null,createdAt:1,updatedAt:1} as JobSnapshot;
 native.renderDocxPreview.mockResolvedValue({uri:'file:///two-pages.pdf',pageCount:2} as never);
 const view=render(<WordResultScreen job={job} colors={base.colors} t={t} locale="en"/>);
 expect(await screen.findByLabelText('Translation 1')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Zoom in'}));
 fireEvent.press(screen.getByRole('button',{name:'Zoom in'}));
 expect(screen.getByRole('button',{name:'Zoom out'})).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Zoom out'}));
 expect(screen.getByRole('button',{name:'Zoom in'})).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:'Next'}));
 expect(await screen.findByLabelText('Translation 2')).toBeOnTheScreen();
 expect(screen.getByText('2 / 2')).toBeOnTheScreen();
 native.renderDocxPreview.mockResolvedValue({uri:'file:///one-page.pdf',pageCount:1} as never);
 const replacement={...job,id:'word-short'};
 // A changed job keeps the physical page index long enough for the screen to clamp it.
 view.rerender(<WordResultScreen job={replacement} colors={base.colors} t={t} locale="en"/>);
 expect(await screen.findByLabelText('Translation 1')).toBeOnTheScreen();
 expect(screen.getByText('1 / 1')).toBeOnTheScreen();
 expect(screen.getByRole('button',{name:'Previous'})).toBeDisabled();
 expect(screen.getByRole('button',{name:'Next'})).toBeDisabled();
});

it('ignores a DOCX preview or page raster that resolves after leaving the result screen',async()=>{
 let resolvePreview!:(preview:{uri:string;pageCount:number})=>void;
 native.renderDocxPreview.mockReturnValueOnce(new Promise(resolve=>{resolvePreview=resolve;}) as never);
 const first=render(<WordResultScreen job={{id:'late-preview',documentId:'doc',documentFormat:'docx'} as JobSnapshot} colors={base.colors} t={t} locale="en"/>);
 await waitFor(()=>expect(native.renderDocxPreview).toHaveBeenCalledTimes(1));
 first.unmount();
 await act(async()=>{resolvePreview({uri:'file:///late-preview.pdf',pageCount:1});await Promise.resolve();});
 expect(native.renderTranslatedPage).not.toHaveBeenCalled();

 let resolveImage!:(uri:string)=>void;
 native.renderDocxPreview.mockResolvedValueOnce({uri:'file:///preview.pdf',pageCount:1} as never);
 native.renderTranslatedPage.mockReturnValueOnce(new Promise(resolve=>{resolveImage=resolve;}) as never);
 const second=render(<WordResultScreen job={{id:'late-image',documentId:'doc',documentFormat:'docx'} as JobSnapshot} colors={base.colors} t={t} locale="en"/>);
 await waitFor(()=>expect(native.renderTranslatedPage).toHaveBeenCalledTimes(1));
 second.unmount();
 await act(async()=>{resolveImage('file:///late-image.png');await Promise.resolve();});

 let rejectPreview!:(error:Error)=>void;
 const previewsBeforeLateReject=native.renderDocxPreview.mock.calls.length;
 native.renderDocxPreview.mockReturnValueOnce(new Promise((_,reject)=>{rejectPreview=reject;}) as never);
 const third=render(<WordResultScreen job={{id:'late-error',documentId:'doc',documentFormat:'docx'} as JobSnapshot} colors={base.colors} t={t} locale="en"/>);
 await waitFor(()=>expect(native.renderDocxPreview).toHaveBeenCalledTimes(previewsBeforeLateReject+1));
 third.unmount();
 await act(async()=>{rejectPreview(new Error('late preview error'));await Promise.resolve();});
});


it('opens one MyMemory details action with its name and service terms', async () => {
 render(<ModelScreen {...base} snapshot={data} selected={null} onSelect={noop} onDownload={noop} onPause={noop} onDelete={noop}/>);
 expect(screen.queryByText('MyMemory')).toBeNull();
 fireEvent.press(screen.getByRole('button',{name:/^Online/}));
 expect(await screen.findByRole('text',{name:'MyMemory'})).toBeOnTheScreen();
 expect(screen.getByRole('button',{name:'Licenses'})).toBeOnTheScreen();
 expect(screen.queryByText('0 B')).toBeNull();
});

it('shows the Gemma license and keeps download, pause and retry actions mutually exclusive', () => {
 const gemma={...model,label:'Gemma package',licenseUrl:'https://example.com/gemma-terms',status:'not_downloaded' as const,downloadedBytes:0};
 const download=jest.fn(),pause=jest.fn();
 const view=render(models([gemma],{onDownload:download,onPause:pause}));
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 expect(screen.getByText('Gemma Terms of Use')).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:t('acceptAndDownload')}));
 expect(download).toHaveBeenCalledWith(expect.objectContaining({id:'one'}));
 expect(screen.queryByRole('button',{name:t('pause')})).toBeNull();

 view.rerender(models([{...gemma,status:'downloading',downloadedBytes:500_000}],{onDownload:download,onPause:pause}));
 expect(screen.getByText(/500 KB \/ 1 MB · 50%/)).toBeOnTheScreen();
 fireEvent.press(screen.getByRole('button',{name:t('pause')}));
 expect(pause).toHaveBeenCalledWith(expect.objectContaining({id:'one'}));
 view.rerender(models([{...gemma,status:'error',error:'checksum mismatch'}],{onDownload:download,onPause:pause}));
 expect(screen.getByText('checksum mismatch')).toBeOnTheScreen();
 expect(screen.getByRole('button',{name:t('acceptAndDownload')})).toBeOnTheScreen();
 expect(screen.queryByRole('button',{name:t('pause')})).toBeNull();
});

it('opens license details without inventing a license link for a model that has no URL', async () => {
 const unlicensed={...model,label:'Unlicensed test model',licenseUrl:undefined};
 render(models([unlicensed]));
 fireEvent.press(screen.getByRole('button',{name:/^Compact/}));
 fireEvent.press(screen.getByRole('button',{name:'Licenses'}));
 expect(await screen.findAllByRole('text',{name:'Unlicensed test model'})).toHaveLength(2);
 expect(screen.getAllByRole('button',{name:'Licenses'})).toHaveLength(1);
});
