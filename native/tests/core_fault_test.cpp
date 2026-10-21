#include "core.hpp"
#include "core_detail.hpp"
#include "core_operations.hpp"
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <functional>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <unistd.h>
using nlohmann::json;
namespace fs=std::filesystem;
namespace ops=translatepdf::detail::ops;
namespace {
void expect(bool condition,const std::string& message){if(!condition)throw std::runtime_error(message);}
void error(const std::string& result,const std::string& expected){auto j=json::parse(result);expect(j.contains("error")&&j["error"].get<std::string>().find(expected)!=std::string::npos,"expected '"+expected+"', got "+result);}
template<class T> struct Replace { T& target; T before; Replace(T& slot,T value):target(slot),before(slot){slot=value;} ~Replace(){target=before;} };
template<class T,class F> Replace<T> replace(T& slot,F value){return Replace<T>(slot,static_cast<T>(value));}
std::string directory,source,destination,font,cjkFont;
json blocks;
auto getWidth=ops::FPDFFont_GetGlyphWidth;
auto getMatrix=ops::FPDFPageObj_GetMatrix;
auto getPageCount=ops::FPDF_GetPageCount;
auto createText=ops::FPDFPageObj_CreateTextObj;
int calls=0;
std::string exportWith(json value=blocks,const std::string& cjk=""){return translatepdf::exportPage(source,0,value.dump(),destination,font,cjk);}
void fixture(){
 directory=std::string(PROJECT_ROOT)+"/.local-private/core-fault-"+std::to_string(getpid());fs::create_directories(directory);source=directory+"/source.pdf";destination=directory+"/output.pdf";
 font=std::string(PROJECT_ROOT)+"/assets/fonts/NotoSans-Regular.ttf";cjkFont=std::string(PROJECT_ROOT)+"/assets/fonts/NotoSansSC-Regular.ttf";
 FPDF_InitLibrary();auto document=FPDF_CreateNewDocument();auto page=FPDFPage_New(document,0,612,792);auto face=FPDFText_LoadStandardFont(document,"Helvetica");
 for(int i=0;i<2;++i){auto text=FPDFPageObj_CreateTextObj(document,face,18);std::u16string s=i?u"Another line.":u"A short line.";FPDFText_SetText(text,reinterpret_cast<const unsigned short*>(s.c_str()));FPDFPageObj_Transform(text,1,0,0,1,70,710-i*55);FPDFPage_InsertObject(page,text);}
 FPDFPage_GenerateContent(page);FPDF_ClosePage(page);
 struct Writer:FPDF_FILEWRITE { FILE* file; Writer(FILE* f):file(f){version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length){return static_cast<int>(fwrite(data,1,length,static_cast<Writer*>(base)->file)==length);};} };
 auto file=fopen(source.c_str(),"wb");Writer writer(file);expect(FPDF_SaveAsCopy(document,&writer,FPDF_NO_INCREMENTAL),"fixture PDF save");fclose(file);FPDFFont_Close(face);FPDF_CloseDocument(document);
 auto read=json::parse(translatepdf::readPage(source,0));blocks=read.at("blocks");expect(blocks.size()==2,"fixture has two text blocks");for(auto& b:blocks){b["translatedText"]="Коротко.";b["status"]="translated";}
}
void pdfBoundary(){
 {auto r=replace(ops::FPDF_CreateNewDocument,[]{return FPDF_DOCUMENT(nullptr);});error(exportWith(),"Cannot create PDF");}
 {auto r=replace(ops::FPDF_ImportPagesByIndex,[](FPDF_DOCUMENT,FPDF_DOCUMENT,const int*,unsigned long,int){return FPDF_BOOL(0);});error(exportWith(),"Cannot import original page");error(translatepdf::mergePdf(json::array({source}).dump(),destination),"Cannot assemble translated pages");}
 {auto r=replace(ops::FPDFText_LoadPage,[](FPDF_PAGE){return FPDF_TEXTPAGE(nullptr);});auto read=json::parse(translatepdf::readPage(source,0));expect(!read.contains("error"),"missing text page retains inspectable page");}
 {auto r=replace(ops::FPDFTextObj_GetText,[](FPDF_PAGEOBJECT,FPDF_TEXTPAGE,unsigned short*,unsigned long){return 0UL;});expect(json::parse(translatepdf::readPage(source,0))["blocks"].empty(),"empty PDF text ignored");}
 {auto r=replace(ops::FPDFTextObj_GetText,[](FPDF_PAGEOBJECT,FPDF_TEXTPAGE,unsigned short* text,unsigned long length){if(!text)return 8UL;if(length>=8){text[0]=' ';text[1]='\r';text[2]='\n';text[3]=0;}return 8UL;});expect(json::parse(translatepdf::readPage(source,0))["blocks"].empty(),"whitespace PDF text ignored");}
 {auto r=replace(ops::FPDFPageObj_GetBounds,[](FPDF_PAGEOBJECT,float*,float*,float*,float*){return FPDF_BOOL(0);});expect(json::parse(translatepdf::readPage(source,0))["blocks"].empty(),"unbounded object ignored");}
 {auto r=replace(ops::FPDFPageObj_GetMatrix,[](FPDF_PAGEOBJECT,FS_MATRIX*){return FPDF_BOOL(0);});expect(json::parse(translatepdf::readPage(source,0))["blocks"].size()==2,"matrix failure keeps independent text blocks");}
 for(int mode=0;mode<5;++mode){calls=mode;auto r=replace(ops::FPDFPageObj_GetMatrix,[](FPDF_PAGEOBJECT object,FS_MATRIX* matrix){auto ok=getMatrix(object,matrix);if(calls==0)matrix->a=-1;if(calls==1)matrix->d=-1;if(calls==2)matrix->b=0.2;if(calls==3)matrix->c=0.2;if(calls==4){matrix->c=0;matrix->d=0;}return ok;});expect(!json::parse(translatepdf::readPage(source,0)).contains("error"),"non-horizontal/degenerate matrix extracts");}
 {auto r=replace(ops::FPDFTextObj_GetFontSize,[](FPDF_PAGEOBJECT,float* size){*size=std::numeric_limits<float>::quiet_NaN();return FPDF_BOOL(1);});expect(!json::parse(translatepdf::readPage(source,0)).contains("error"),"NaN size safely defaults");}
 {auto r=replace(ops::FPDF_GetPageWidthF,[](FPDF_PAGE){return 0.f;});try{translatepdf::render(source,0,200);throw std::runtime_error("render accepted zero width");}catch(const std::runtime_error& e){expect(std::string(e.what())=="Invalid page dimensions","invalid dimensions throw");}}
 {auto r=replace(ops::FPDFBitmap_CreateEx,[](int,int,int,void*,int){return FPDF_BITMAP(nullptr);});try{translatepdf::render(source,0,200);throw std::runtime_error("bitmap accepted null");}catch(const std::runtime_error& e){expect(std::string(e.what())=="Cannot allocate page bitmap","allocation error");}}
 {auto r=replace(ops::FPDFText_LoadFont,[](FPDF_DOCUMENT,const uint8_t*,uint32_t,int,FPDF_BOOL){return FPDF_FONT(nullptr);});error(exportWith(),"Cannot embed Cyrillic font");}
 {calls=0;auto r=replace(ops::FPDFText_LoadFont,[](FPDF_DOCUMENT d,const uint8_t* b,uint32_t n,int type,FPDF_BOOL cid){if(++calls==2)return FPDF_FONT(nullptr);return ::FPDFText_LoadFont(d,b,n,type,cid);});error(exportWith(blocks,cjkFont),"Cannot embed Simplified Chinese font");}
 {auto r=replace(ops::FPDFPage_CreateAnnot,[](FPDF_PAGE,FPDF_ANNOTATION_SUBTYPE){return FPDF_ANNOTATION(nullptr);});auto b=blocks;b[0]["source"]="ocr";error(exportWith(b),"Cannot attach translated text");}
 for(int mode=0;mode<3;++mode){auto b=blocks;b[0]["source"]="ocr";
 if(mode==0){auto r=replace(ops::FPDFAnnot_SetRect,[](FPDF_ANNOTATION,const FS_RECTF*){return FPDF_BOOL(0);});error(exportWith(b),"Cannot save translation annotation");}
 if(mode==1){auto r=replace(ops::FPDFAnnot_SetFlags,[](FPDF_ANNOTATION,int){return FPDF_BOOL(0);});error(exportWith(b),"Cannot save translation annotation");}
 if(mode==2){auto r=replace(ops::FPDFAnnot_SetStringValue,[](FPDF_ANNOTATION a,FPDF_BYTESTRING key,FPDF_WIDESTRING value){return std::string(key)=="Contents"?FPDF_BOOL(0): ::FPDFAnnot_SetStringValue(a,key,value);});error(exportWith(b),"Cannot save translation annotation");}}
 {auto r=replace(ops::FPDFPageObj_CreateTextObj,[](FPDF_DOCUMENT,FPDF_FONT,float){return FPDF_PAGEOBJECT(nullptr);});error(exportWith(),"Cannot create translated text");}
 {auto r=replace(ops::FPDFText_SetText,[](FPDF_PAGEOBJECT,FPDF_WIDESTRING){return FPDF_BOOL(0);});error(exportWith(),"Cannot write translated glyphs");}
 {auto r=replace(ops::FPDFPage_RemoveObject,[](FPDF_PAGE,FPDF_PAGEOBJECT){return FPDF_BOOL(0);});error(exportWith(),"Cannot replace source text safely");}
 {auto r=replace(ops::FPDFPage_GenerateContent,[](FPDF_PAGE){return FPDF_BOOL(0);});error(exportWith(),"Cannot generate translated page content");}
 {auto r=replace(ops::FPDF_GetPageCount,[](FPDF_DOCUMENT d){return getPageCount(d)+1;});error(translatepdf::mergePdf(json::array({source}).dump(),destination),"Export page count mismatch");}
}
void fileBoundary(){
 const auto part=destination+".part";
 {auto r=replace(ops::fopen,[](const char*,const char*){return static_cast<FILE*>(nullptr);});error(exportWith(),"Cannot create output");}
 {auto r=replace(ops::fwrite,[](const void*,size_t,size_t,FILE*){return size_t(0);});error(exportWith(),"Failed writing PDF");expect(!fs::exists(part),"failed writer removes partial");}
 {auto r=replace(ops::fflush,[](FILE*){return EOF;});error(exportWith(),"Failed writing PDF");expect(!fs::exists(part),"failed flush removes partial");}
 {auto r=replace(ops::fsync,[](int){return -1;});error(exportWith(),"Failed writing PDF");expect(!fs::exists(part),"failed sync removes partial");}
 {auto r=replace(ops::fclose,[](FILE* f){::fclose(f);return EOF;});error(exportWith(),"Failed writing PDF");expect(!fs::exists(part),"failed close removes partial");}
 {auto r=replace(ops::rename,[](const char*,const char*){return -1;});error(exportWith(),"Cannot finalize PDF");}
 auto value=json::parse(exportWith());expect(!value.contains("error"),"operations restored after faults");
}
void wrappingContracts(){
 using namespace translatepdf::detail;
 auto document=FPDF_CreateNewDocument();
 auto read=[](const std::string& path){std::ifstream file(path,std::ios::binary);return std::vector<unsigned char>(std::istreambuf_iterator<char>(file),{});};
 auto latinData=read(font),cjkData=read(cjkFont);
 auto latin=FPDFText_LoadFont(document,latinData.data(),latinData.size(),FPDF_FONT_TRUETYPE,true),chinese=FPDFText_LoadFont(document,cjkData.data(),cjkData.size(),FPDF_FONT_TRUETYPE,true);
 expect(latin&&chinese,"wrapping uses real embedded fonts");
 FontCoverage latinCoverage(latinData),cjkCoverage(cjkData);
 auto wrap=[&](const std::string& text,float width=1000){return lines(latin,chinese,latinCoverage,&cjkCoverage,text,10,width);};
 auto throws=[&](const std::function<void()>& action,const char* message){try{action();throw std::runtime_error("expected wrapping failure");}catch(const std::runtime_error& e){expect(std::string(e.what()).find(message)!=std::string::npos,std::string("expected ")+message+", got "+e.what());}};
 for(auto text:{"","\n","\r","\r\n","A\nB","A\rB","A\r\nB","A\n\nB","\nA\n"})expect(!wrap(text).empty()||!*text,"explicit line breaks retained");
 auto combined=wrap("éA中");expect(combined.size()==1,"combining marks stay with base");
 auto leading=wrap("́A");expect(leading.size()==1,"leading combining mark retained");
 throws([&]{wrap("A\t B");},"No embedded font");
 // A font whose cmap and vendor metrics both expose TAB must treat it as a
 // wrapping separator. Bundled Noto fonts deliberately report TAB as missing.
 {
  std::vector<unsigned char> cmap(80);
  auto put16=[&](size_t at,uint16_t value){cmap[at]=value>>8;cmap[at+1]=value&255;};
  auto put32=[&](size_t at,uint32_t value){put16(at,value>>16);put16(at+2,value&65535);};
  put16(4,1);std::copy_n("cmap",4,cmap.begin()+12);put32(20,28);put16(30,1);put16(32,3);put16(34,10);put32(36,12);put16(40,12);put32(52,1);put32(56,9);put32(60,127);put32(64,1);
  FontCoverage tabCoverage(cmap);
  auto metrics=replace(ops::FPDFFont_GetGlyphWidth,[](FPDF_FONT,uint32_t,float,float* width){*width=5;return FPDF_BOOL(1);});
  auto text=lines(latin,nullptr,tabCoverage,nullptr,"A\t B",10,100);expect(text.size()==1,"TAB mapped by font retained as whitespace");
 }

 expect(wrap("A中B").size()==1,"mixed fonts segmented");
 expect(wrap("A中文A中文",18).size()>1,"CJK break adjacent to latin");
 throws([&]{wrap("中。",11);},"Unbreakable text");
 throws([&]{wrap("(中",10);},"Unbreakable text");
 throws([&]{wrap("A",1);},"Unbreakable text");
 throws([&]{wrap("A   ",10);},"A glyph exceeds");
 throws([&]{lines(latin,nullptr,latinCoverage,nullptr,"中",10,100);},"No embedded font");
 throws([&]{lines(latin,chinese,latinCoverage,nullptr,"中",10,100);},"No embedded font");
 throws([&]{wrap("\xf4\x8f\xbf\xbf");},"No embedded font");
 {auto r=replace(ops::FPDFFont_GetGlyphWidth,[](FPDF_FONT,uint32_t,float,float*){return FPDF_BOOL(0);});throws([&]{wrap("A");},"No embedded font");throws([&]{glyphWidth({u"A",latin,'A',false},10);},"Font lacks");}
 FPDFFont_Close(chinese);FPDFFont_Close(latin);FPDF_CloseDocument(document);
}
void exportContracts(){
 auto result=[](const std::string& s){auto value=json::parse(s);expect(!value.contains("error"),"export contract result: "+s);return value;};
 {auto r=replace(ops::FPDFTextObj_GetText,[](FPDF_PAGEOBJECT,FPDF_TEXTPAGE,unsigned short* text,unsigned long){if(text)text[0]=0;return 2UL;});expect(result(translatepdf::readPage(source,0))["blocks"].empty(),"terminator-only text ignored");}
 {auto r=replace(ops::FPDF_GetPageHeightF,[](FPDF_PAGE){return 0.f;});try{translatepdf::render(source,0,200);throw std::runtime_error("accepted zero height");}catch(const std::runtime_error& e){expect(std::string(e.what())=="Invalid page dimensions","height dimension rejected");}}
 error(translatepdf::exportPage(source,0,blocks.dump(),destination,directory+"/absent"),"Cyrillic export font is missing");
 error(translatepdf::exportPage(source,0,blocks.dump(),destination,font,directory+"/absent"),"Simplified Chinese export font is missing");
 for(int mode=0;mode<12;++mode){auto b=blocks;
  if(mode==0)b[0].erase("translatedText");
  if(mode==1)b[0]["translatedText"]="";
  if(mode==2)b[0]["translatedText"]=nullptr;
  if(mode==3)b[0]["status"]="partial";
  if(mode==4)b[0]["right"]=b[0]["left"];
  if(mode==5)b[0]["bottom"]=b[0]["top"];
  if(mode==6){b[0]["objectIndices"]=json::array();b[0].erase("objectIndex");}
  if(mode==7){b[0]["objectIndices"]=json::array();b[0]["objectIndex"]="invalid";}
  if(mode==8){b[0].erase("objectIndices");}
  if(mode==9){b[0]["translatedText"]="\n";}
  if(mode==10){b[0]["bottom"]=b[0]["top"].get<double>()+1;}
  if(mode==11){b[0]["translatedText"]="longunbreakabletextwithoutspace";b[0]["right"]=b[0]["left"].get<double>()+8;}
  auto out=result(exportWith(b));expect(out["partial"].get<bool>()==(mode!=8),"unsafe/missing layout yields partial mode="+std::to_string(mode));
 }
 {auto r=replace(ops::FPDFPage_GetRotation,[](FPDF_PAGE){return 1;});expect(result(exportWith())["partial"],"rotated page annotates");}
 {calls=0;auto r=replace(ops::FPDFPageObj_GetType,[](FPDF_PAGEOBJECT){return FPDF_PAGEOBJ_PATH;});expect(result(exportWith())["partial"],"non-text object cannot be removed");}
 {calls=0;auto created=replace(ops::FPDFPageObj_CreateTextObj,[](FPDF_DOCUMENT d,FPDF_FONT f,float size){calls=1;return createText(d,f,size);});auto width=replace(ops::FPDFFont_GetGlyphWidth,[](FPDF_FONT f,uint32_t cp,float size,float* w){return calls?FPDF_BOOL(0):getWidth(f,cp,size,w);});error(exportWith(),"Font lacks a required glyph");}
 {auto generated=replace(ops::FPDFPage_GenerateContent,[](FPDF_PAGE){return FPDF_BOOL(0);});error(exportWith(blocks,cjkFont),"Cannot generate translated page content");}
 result(exportWith());
}

struct ModelState {
 int context=2048,threads=2,modelFreed=0,contextFreed=0,samplerFreed=0;
 int templateCalls=0,samples=0,decodes=0,tokenCalls=0;
 int loadFailure=0,templateMode=0,tokenMode=0,decodeMode=0,sampleMode=0,pieceMode=0;
 const char* chat="default";
} state;
void resetTranslation(){state.templateCalls=state.samples=state.decodes=state.tokenCalls=0;state.templateMode=state.tokenMode=state.decodeMode=state.sampleMode=state.pieceMode=0;state.chat="default";}
std::string translate(const std::string& text="Hello"){return translatepdf::translate(text,"","","en","ru");}
void modelBoundary(){
 translatepdf::releaseModel();
 auto load=replace(ops::llama_model_load_from_file,[](const char*,llama_model_params){return state.loadFailure==1?nullptr:reinterpret_cast<llama_model*>(0x1000);});
 auto encoder=replace(ops::llama_model_has_encoder,[](const llama_model*){return state.loadFailure==2;});
 auto init=replace(ops::llama_init_from_model,[](llama_model*,llama_context_params p){state.context=p.n_ctx;state.threads=p.n_threads;return state.loadFailure==3?nullptr:reinterpret_cast<llama_context*>(0x2000);});
 auto freeModel=replace(ops::llama_model_free,[](llama_model*){++state.modelFreed;});
 auto freeContext=replace(ops::llama_free,[](llama_context*){++state.contextFreed;});
 auto ctxSize=replace(ops::llama_n_ctx,[](const llama_context*){return uint32_t(state.context);});
 auto ctxThreads=replace(ops::llama_n_threads,[](llama_context*){return int32_t(state.threads);});
 auto modelSize=replace(ops::llama_model_size,[](const llama_model*){return uint64_t(1234);});
 auto tmpl=replace(ops::llama_model_chat_template,[](const llama_model*,const char*){return state.chat;});
 auto vocab=replace(ops::llama_model_get_vocab,[](const llama_model*){return reinterpret_cast<const llama_vocab*>(0x3000);});
 auto ctxMemory=replace(ops::llama_get_memory,[](const llama_context*){return llama_memory_t(nullptr);});
 auto clearMemory=replace(ops::llama_memory_clear,[](llama_memory_t,bool){if(state.decodeMode==1)translatepdf::cancel();});
 auto bos=replace(ops::llama_vocab_bos,[](const llama_vocab*){return llama_token(1);});
 auto numTokens=replace(ops::llama_vocab_n_tokens,[](const llama_vocab*){return int32_t(16);});
 auto eog=replace(ops::llama_vocab_is_eog,[](const llama_vocab*,llama_token token){return token==9;});
 auto tokenizer=replace(ops::llama_tokenize,[](const llama_vocab*,const char* text,int32_t length,llama_token* tokens,int32_t max,bool special,bool parse){
  ++state.tokenCalls;
  if(state.tokenMode==1)return int32_t(0);
  if(parse&&length>0&&text[0]=='<'&&max==1){if(state.tokenMode==4)return int32_t(-2);tokens[0]=9;return int32_t(1);}
  int count=parse?4:2;
  if(state.tokenMode==2&&special)count=0;
  if(state.tokenMode==3)count=4096;
  if(!tokens)return -int32_t(count);
  for(int i=0;i<std::min(count,max);++i)tokens[i]=state.tokenMode==5?1:2;
  return int32_t(count);
 });
 auto format=replace(ops::llama_chat_apply_template,[](const char*,const llama_chat_message*,size_t,bool,char* buffer,int32_t length){
  int call=++state.templateCalls;
  if(state.templateMode==1)return -1;
  if(state.templateMode>=2&&call==1)return length+16;
  if(state.templateMode==3&&call==2)return -1;
  if(state.templateMode==4&&call==2)return length;
  if(length>=6)std::memcpy(buffer,"prompt",6);return 6;
 });
 auto decoder=replace(ops::llama_decode,[](llama_context*,llama_batch){
  ++state.decodes;
  if(state.decodeMode==2&&state.decodes==1)return int32_t(1);
  if(state.decodeMode==3&&state.decodes==1){translatepdf::cancel();return int32_t(1);}
  if(state.decodeMode==4&&state.decodes==1)translatepdf::cancel();
  if(state.decodeMode==5&&state.decodes==2)return int32_t(1);
  if(state.decodeMode==6&&state.decodes==2){translatepdf::cancel();return int32_t(1);}
  return int32_t(0);
 });
 auto sample=replace(ops::llama_sampler_sample,[](llama_sampler*,llama_context*,int32_t){++state.samples;return llama_token(state.sampleMode==1?9:state.sampleMode==2?3:state.samples==1?3:9);});
 auto piece=replace(ops::llama_token_to_piece,[](const llama_vocab*,llama_token,char* buffer,int32_t,int32_t,bool){if(state.pieceMode==1)return int32_t(-600);std::memcpy(buffer,"Hi",2);return int32_t(2);});
 auto samplerFree=replace(ops::llama_sampler_free,[](llama_sampler* sampler){++state.samplerFreed;::llama_sampler_free(sampler);});
 for(int failure=1;failure<=3;++failure){state.loadFailure=failure;int freed=state.modelFreed;error(translatepdf::loadModel("boundary-model",2048,2),failure==1?"Model cannot be loaded":failure==2?"architecture does not match":"Insufficient resources");expect(state.modelFreed==freed+(failure==1?0:1),"failed model/context load releases model exactly once");}
 state.loadFailure=0;
 auto reload=[](const std::string& path="boundary-model",int context=2048,int threads=2,const std::string& profile="hy-mt2"){auto value=json::parse(translatepdf::loadModel(path,context,threads,profile));expect(!value.contains("error"),"test model boundary load");};
 reload();reload();reload("other-model");reload("other-model",2048,2,"translategemma");reload("other-model",1024,1,"translategemma");reload("other-model",1024,2,"translategemma");reload();
 translatepdf::cancel();error(translate(),"Canceled");reload();
 for(int mode=1;mode<=4;++mode){resetTranslation();state.templateMode=mode;if(mode==2){auto j=json::parse(translate());expect(j.value("text","")=="Hi","retry with requested template buffer succeeds");}else error(translate(),mode==1?"Unsupported GGUF chat template":"Cannot format translation prompt");}
 resetTranslation();state.chat=nullptr;error(translate(),"lacks a verified chat template");
 for(int mode=1;mode<=3;++mode){resetTranslation();state.tokenMode=mode;error(translate(),mode==1?"contains no tokens":"exceeds model context");}
 resetTranslation();state.tokenMode=5;expect(json::parse(translate()).value("text","")=="Hi","existing BOS does not add special token");
 for(const char* chat:{"<｜hy_Assistant｜>","<｜hy_Assistant｜><｜hy_place▁holder▁no▁2｜>","<|eos|>"}){
  resetTranslation();state.chat=chat;expect(json::parse(translate()).value("text","")=="Hi","template end token obeyed");
  resetTranslation();state.chat=chat;state.tokenMode=4;expect(json::parse(translate()).value("text","")=="Hi","unresolved end token falls back to vocabulary EOG");
 }
 for(int mode=1;mode<=6;++mode){resetTranslation();state.decodeMode=mode;int freed=state.samplerFreed;error(translate(),mode==2?"reading input":mode==5?"generating output":"Canceled");expect(state.samplerFreed==freed+(mode>=4?1:0),"sampler freed exactly once after generation failures");reload();}
 resetTranslation();state.pieceMode=1;int freed=state.samplerFreed;error(translate(),"Cannot decode model token");expect(state.samplerFreed==freed+1,"token decode failure releases sampler");
 resetTranslation();state.sampleMode=1;error(translate(),"produced no translation");
 resetTranslation();state.sampleMode=2;error(translate(),"exceeded context");
 resetTranslation();error(translate("Keep 128"),"changed a protected number");
 resetTranslation();expect(json::parse(translate()).value("text","")=="Hi","successful boundary response");
 translatepdf::releaseModel();error(translate(),"Model is not loaded");
}

}
int main(){try{fixture();pdfBoundary();fileBoundary();wrappingContracts();exportContracts();modelBoundary();fs::remove_all(directory);std::cout<<"Core vendor/file fault contracts passed\n";return 0;}catch(const std::exception& e){std::cerr<<e.what()<<'\n';fs::remove_all(directory);return 1;}}
