#include "core.hpp"
#include "core_detail.hpp"
#include "core_operations.hpp"
#include "translation_guard.hpp"
#include "llama.h"
#include "fpdfview.h"
#include "fpdf_text.h"
#include "fpdf_edit.h"
#include "fpdf_save.h"
#include "fpdf_ppo.h"
#include "fpdf_annot.h"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <codecvt>
#include <cstdio>
#include <fstream>
#include <locale>
#include <mutex>
#include <regex>
#include <stdexcept>
#include <unistd.h>

namespace translatepdf {
using json = nlohmann::json;
namespace detail {
namespace ops {
decltype(&::FPDFAnnot_SetColor) FPDFAnnot_SetColor = &::FPDFAnnot_SetColor;
decltype(&::FPDFAnnot_SetFlags) FPDFAnnot_SetFlags = &::FPDFAnnot_SetFlags;
decltype(&::FPDFAnnot_SetRect) FPDFAnnot_SetRect = &::FPDFAnnot_SetRect;
decltype(&::FPDFAnnot_SetStringValue) FPDFAnnot_SetStringValue = &::FPDFAnnot_SetStringValue;
decltype(&::FPDFBitmap_CreateEx) FPDFBitmap_CreateEx = &::FPDFBitmap_CreateEx;
decltype(&::FPDFBitmap_Destroy) FPDFBitmap_Destroy = &::FPDFBitmap_Destroy;
decltype(&::FPDFBitmap_FillRect) FPDFBitmap_FillRect = &::FPDFBitmap_FillRect;
decltype(&::FPDFFont_Close) FPDFFont_Close = &::FPDFFont_Close;
decltype(&::FPDFFont_GetGlyphWidth) FPDFFont_GetGlyphWidth = &::FPDFFont_GetGlyphWidth;
decltype(&::FPDFPageObj_CreateTextObj) FPDFPageObj_CreateTextObj = &::FPDFPageObj_CreateTextObj;
decltype(&::FPDFPageObj_Destroy) FPDFPageObj_Destroy = &::FPDFPageObj_Destroy;
decltype(&::FPDFPageObj_GetBounds) FPDFPageObj_GetBounds = &::FPDFPageObj_GetBounds;
decltype(&::FPDFPageObj_GetMatrix) FPDFPageObj_GetMatrix = &::FPDFPageObj_GetMatrix;
decltype(&::FPDFPageObj_GetType) FPDFPageObj_GetType = &::FPDFPageObj_GetType;
decltype(&::FPDFPageObj_SetFillColor) FPDFPageObj_SetFillColor = &::FPDFPageObj_SetFillColor;
decltype(&::FPDFPageObj_Transform) FPDFPageObj_Transform = &::FPDFPageObj_Transform;
decltype(&::FPDFPage_CloseAnnot) FPDFPage_CloseAnnot = &::FPDFPage_CloseAnnot;
decltype(&::FPDFPage_CountObjects) FPDFPage_CountObjects = &::FPDFPage_CountObjects;
decltype(&::FPDFPage_CreateAnnot) FPDFPage_CreateAnnot = &::FPDFPage_CreateAnnot;
decltype(&::FPDFPage_GenerateContent) FPDFPage_GenerateContent = &::FPDFPage_GenerateContent;
decltype(&::FPDFPage_GetObject) FPDFPage_GetObject = &::FPDFPage_GetObject;
decltype(&::FPDFPage_GetRotation) FPDFPage_GetRotation = &::FPDFPage_GetRotation;
decltype(&::FPDFPage_InsertObject) FPDFPage_InsertObject = &::FPDFPage_InsertObject;
decltype(&::FPDFPage_RemoveObject) FPDFPage_RemoveObject = &::FPDFPage_RemoveObject;
decltype(&::FPDFTextObj_GetFont) FPDFTextObj_GetFont = &::FPDFTextObj_GetFont;
decltype(&::FPDFTextObj_GetFontSize) FPDFTextObj_GetFontSize = &::FPDFTextObj_GetFontSize;
decltype(&::FPDFTextObj_GetText) FPDFTextObj_GetText = &::FPDFTextObj_GetText;
decltype(&::FPDFText_ClosePage) FPDFText_ClosePage = &::FPDFText_ClosePage;
decltype(&::FPDFText_LoadFont) FPDFText_LoadFont = &::FPDFText_LoadFont;
decltype(&::FPDFText_LoadPage) FPDFText_LoadPage = &::FPDFText_LoadPage;
decltype(&::FPDFText_SetText) FPDFText_SetText = &::FPDFText_SetText;
decltype(&::FPDF_CloseDocument) FPDF_CloseDocument = &::FPDF_CloseDocument;
decltype(&::FPDF_ClosePage) FPDF_ClosePage = &::FPDF_ClosePage;
decltype(&::FPDF_CreateNewDocument) FPDF_CreateNewDocument = &::FPDF_CreateNewDocument;
decltype(&::FPDF_GetLastError) FPDF_GetLastError = &::FPDF_GetLastError;
decltype(&::FPDF_GetPageCount) FPDF_GetPageCount = &::FPDF_GetPageCount;
decltype(&::FPDF_GetPageHeightF) FPDF_GetPageHeightF = &::FPDF_GetPageHeightF;
decltype(&::FPDF_GetPageWidthF) FPDF_GetPageWidthF = &::FPDF_GetPageWidthF;
decltype(&::FPDF_ImportPagesByIndex) FPDF_ImportPagesByIndex = &::FPDF_ImportPagesByIndex;
decltype(&::FPDF_InitLibrary) FPDF_InitLibrary = &::FPDF_InitLibrary;
decltype(&::FPDF_LoadDocument) FPDF_LoadDocument = &::FPDF_LoadDocument;
decltype(&::FPDF_LoadPage) FPDF_LoadPage = &::FPDF_LoadPage;
decltype(&::FPDF_RenderPageBitmap) FPDF_RenderPageBitmap = &::FPDF_RenderPageBitmap;
decltype(&::FPDF_SaveAsCopy) FPDF_SaveAsCopy = &::FPDF_SaveAsCopy;
decltype(&::llama_backend_init) llama_backend_init = &::llama_backend_init;
decltype(&::llama_batch_get_one) llama_batch_get_one = &::llama_batch_get_one;
decltype(&::llama_chat_apply_template) llama_chat_apply_template = &::llama_chat_apply_template;
decltype(&::llama_context_default_params) llama_context_default_params = &::llama_context_default_params;
decltype(&::llama_decode) llama_decode = &::llama_decode;
decltype(&::llama_free) llama_free = &::llama_free;
decltype(&::llama_get_memory) llama_get_memory = &::llama_get_memory;
decltype(&::llama_init_from_model) llama_init_from_model = &::llama_init_from_model;
decltype(&::llama_log_set) llama_log_set = &::llama_log_set;
decltype(&::llama_memory_clear) llama_memory_clear = &::llama_memory_clear;
decltype(&::llama_model_chat_template) llama_model_chat_template = &::llama_model_chat_template;
decltype(&::llama_model_default_params) llama_model_default_params = &::llama_model_default_params;
decltype(&::llama_model_free) llama_model_free = &::llama_model_free;
decltype(&::llama_model_get_vocab) llama_model_get_vocab = &::llama_model_get_vocab;
decltype(&::llama_model_has_encoder) llama_model_has_encoder = &::llama_model_has_encoder;
decltype(&::llama_model_load_from_file) llama_model_load_from_file = &::llama_model_load_from_file;
decltype(&::llama_model_size) llama_model_size = &::llama_model_size;
decltype(&::llama_n_ctx) llama_n_ctx = &::llama_n_ctx;
decltype(&::llama_n_threads) llama_n_threads = &::llama_n_threads;
decltype(&::llama_sampler_chain_add) llama_sampler_chain_add = &::llama_sampler_chain_add;
decltype(&::llama_sampler_chain_default_params) llama_sampler_chain_default_params = &::llama_sampler_chain_default_params;
decltype(&::llama_sampler_chain_init) llama_sampler_chain_init = &::llama_sampler_chain_init;
decltype(&::llama_sampler_free) llama_sampler_free = &::llama_sampler_free;
decltype(&::llama_sampler_init_dist) llama_sampler_init_dist = &::llama_sampler_init_dist;
decltype(&::llama_sampler_init_greedy) llama_sampler_init_greedy = &::llama_sampler_init_greedy;
decltype(&::llama_sampler_init_penalties) llama_sampler_init_penalties = &::llama_sampler_init_penalties;
decltype(&::llama_sampler_init_temp) llama_sampler_init_temp = &::llama_sampler_init_temp;
decltype(&::llama_sampler_init_top_k) llama_sampler_init_top_k = &::llama_sampler_init_top_k;
decltype(&::llama_sampler_init_top_p) llama_sampler_init_top_p = &::llama_sampler_init_top_p;
decltype(&::llama_sampler_sample) llama_sampler_sample = &::llama_sampler_sample;
decltype(&::llama_token_to_piece) llama_token_to_piece = &::llama_token_to_piece;
decltype(&::llama_tokenize) llama_tokenize = &::llama_tokenize;
decltype(&::llama_vocab_bos) llama_vocab_bos = &::llama_vocab_bos;
decltype(&::llama_vocab_is_eog) llama_vocab_is_eog = &::llama_vocab_is_eog;
decltype(&::llama_vocab_n_tokens) llama_vocab_n_tokens = &::llama_vocab_n_tokens;
decltype(&::fopen) fopen = &::fopen;
decltype(&::fwrite) fwrite = &::fwrite;
decltype(&::fflush) fflush = &::fflush;
decltype(&::fsync) fsync = &::fsync;
decltype(&::fclose) fclose = &::fclose;
decltype(&::rename) rename = &::rename;
}
std::once_flag pdfInit, llamaInit;
std::mutex pdfMutex, modelMutex;
std::atomic<bool> stopped{false};
llama_model* model = nullptr;
llama_context* ctx = nullptr;
std::string loadedPath;
std::string loadedProfile;
std::u16string wide(const std::string& s) { return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.from_bytes(s); }
std::string utf8(const std::u16string& s) { return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(s); }
void initPdf() { std::call_once(pdfInit, [] { ops::FPDF_InitLibrary(); }); }
struct Doc {
 FPDF_DOCUMENT value;
 explicit Doc(const std::string& p):value(ops::FPDF_LoadDocument(p.c_str(),nullptr)) { if(!value) throw std::runtime_error("Cannot read PDF (damaged or password protected), code " + std::to_string(ops::FPDF_GetLastError())); }
 explicit Doc(FPDF_DOCUMENT d):value(d) { if(!value) throw std::runtime_error("Cannot create PDF"); }
 ~Doc(){ops::FPDF_CloseDocument(value);}
};
struct Page {
 FPDF_PAGE value;
 Page(FPDF_DOCUMENT d,int i):value(ops::FPDF_LoadPage(d,i)) { if(!value) throw std::runtime_error("Cannot read page"); }
 ~Page(){ops::FPDF_ClosePage(value);}
};
struct TextPage { FPDF_TEXTPAGE value; explicit TextPage(FPDF_PAGE p):value(ops::FPDFText_LoadPage(p)) {} ~TextPage(){if(value)ops::FPDFText_ClosePage(value);} };
struct FileWriter:FPDF_FILEWRITE {
 FILE* out;
 explicit FileWriter(FILE* f):out(f) { version=1; WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long n)->int {return ops::fwrite(data,1,n,static_cast<FileWriter*>(base)->out)==n;}; }
};
void save(FPDF_DOCUMENT d,const std::string& path) {
 const auto temp=path+".part";
 FILE* f=ops::fopen(temp.c_str(),"wb"); if(!f) throw std::runtime_error("Cannot create output: check available storage");
 FileWriter writer(f); bool ok=ops::FPDF_SaveAsCopy(d,&writer,FPDF_NO_INCREMENTAL);
 if(ops::fflush(f)!=0||ops::fsync(fileno(f))!=0)ok=false;
 if(ops::fclose(f)!=0)ok=false;
 if(!ok){std::remove(temp.c_str());throw std::runtime_error("Failed writing PDF; checkpoints retained");}
 if(ops::rename(temp.c_str(),path.c_str())!=0)throw std::runtime_error("Cannot finalize PDF");
}
std::string failure(const std::exception& e){return json{{"error",e.what()}}.dump();}
std::string objectText(FPDF_PAGEOBJECT obj,FPDF_TEXTPAGE tp) {
 auto bytes=ops::FPDFTextObj_GetText(obj,tp,nullptr,0); if(bytes<2)return {};
 std::u16string s(bytes/2,u'\0'); ops::FPDFTextObj_GetText(obj,tp,reinterpret_cast<unsigned short*>(s.data()),bytes);
 while(!s.empty()&&(s.back()==0||s.back()==u'\r'||s.back()==u'\n'))s.pop_back();
 return utf8(s);
}
uint16_t be16(const std::vector<unsigned char>& d,size_t p){return p+2<=d.size()?static_cast<uint16_t>((d[p]<<8)|d[p+1]):0;}
uint32_t be32(const std::vector<unsigned char>& d,size_t p){return p+4<=d.size()?(static_cast<uint32_t>(d[p])<<24)|(static_cast<uint32_t>(d[p+1])<<16)|(static_cast<uint32_t>(d[p+2])<<8)|d[p+3]:0;}
FontCoverage::FontCoverage(const std::vector<unsigned char>& bytes):data(bytes),subtable(0),format(0){
  if(data.size()<12)return;const uint16_t count=be16(data,4);
  for(size_t i=0;i<count;i++){size_t entry=12+i*16;if(entry+16>data.size())break;if(std::string(reinterpret_cast<const char*>(data.data()+entry),4)!="cmap")continue;
   const size_t base=be32(data,entry+8);if(base+4>data.size())break;uint16_t records=be16(data,base+2);size_t best12=0,best4=0;
   for(size_t j=0;j<records;j++){size_t record=base+4+j*8;if(record+8>data.size())break;uint16_t platform=be16(data,record),encoding=be16(data,record+2);size_t offset=base+be32(data,record+4);if(offset+2>data.size())continue;uint16_t f=be16(data,offset);
    if(f==12&&(platform==0||(platform==3&&encoding==10)))best12=offset;
    if(f==4&&(platform==0||(platform==3&&(encoding==1||encoding==0))))best4=offset;
   }
   if(best12){subtable=best12;format=12;}else if(best4){subtable=best4;format=4;}break;
  }
 }
bool FontCoverage::has(uint32_t cp)const{
  if(!subtable)return false;
  if(format==12){uint32_t groups=be32(data,subtable+12);size_t lo=0,hi=groups;while(lo<hi){size_t mid=(lo+hi)/2,entry=subtable+16+mid*12;if(entry+12>data.size())return false;uint32_t start=be32(data,entry),end=be32(data,entry+4);if(cp<start)hi=mid;else if(cp>end)lo=mid+1;else return be32(data,entry+8)+(cp-start)!=0;}return false;}
  // A selected subtable is format 12 or 4; format 12 returned above.
  if(cp<=0xffff){uint16_t segments=be16(data,subtable+6)/2;size_t ends=subtable+14,starts=ends+segments*2+2,deltas=starts+segments*2,ranges=deltas+segments*2;
   for(size_t i=0;i<segments;i++){uint16_t end=be16(data,ends+i*2),start=be16(data,starts+i*2);if(cp<start||cp>end)continue;uint16_t delta=be16(data,deltas+i*2),range=be16(data,ranges+i*2);if(range==0)return static_cast<uint16_t>(cp+delta)!=0;size_t glyphAt=ranges+i*2+range+(cp-start)*2;uint16_t glyph=be16(data,glyphAt);return glyph&&static_cast<uint16_t>(glyph+delta)!=0;} }
  return false;
 }
uint32_t codepointAt(const std::u16string& value,size_t& i) {
 uint32_t c=value[i++];
 if(c>=0xd800&&c<=0xdbff&&i<value.size()&&value[i]>=0xdc00&&value[i]<=0xdfff)c=0x10000+((c-0xd800)<<10)+(value[i++]-0xdc00);
 return c;
}
bool combining(uint32_t c) { return (c>=0x0300&&c<=0x036f)||(c>=0x1ab0&&c<=0x1aff)||(c>=0x1dc0&&c<=0x1dff)||(c>=0x20d0&&c<=0x20ff)||(c>=0xfe20&&c<=0xfe2f); }
bool cjk(uint32_t c) { return (c>=0x2e80&&c<=0x9fff)||(c>=0xf900&&c<=0xfaff)||(c>=0x20000&&c<=0x3134f)||(c>=0xac00&&c<=0xd7af); }
bool openingPunctuation(uint32_t c) { return c=='('||c=='['||c=='{'||c==0x2018||c==0x201c||c==0x3008||c==0x300a||c==0x300c||c==0x300e||c==0x3010||c==0x3014||c==0x3016||c==0x3018||c==0x301a; }
bool closingPunctuation(uint32_t c) { return c==')'||c==']'||c=='}'||c==','||c=='.'||c==':'||c==';'||c=='!'||c=='?'||c==0x2019||c==0x201d||c==0x3001||c==0x3002||c==0x3009||c==0x300b||c==0x300d||c==0x300f||c==0x3011||c==0x3015||c==0x3017||c==0x3019||c==0x301b; }
float glyphWidth(const GlyphUnit& glyph,float size) { float w=0;if(!ops::FPDFFont_GetGlyphWidth(glyph.font,glyph.codepoint,size,&w))throw std::runtime_error("Font lacks a required glyph");return w; }
float textWidth(const std::vector<GlyphUnit>& glyphs,float size) { float sum=0;for(const auto& g:glyphs)sum+=glyphWidth(g,size);return sum; }
std::vector<WrappedLine> lines(FPDF_FONT latin,FPDF_FONT cjkFont,const FontCoverage& latinCoverage,const FontCoverage* cjkCoverage,const std::string& text,float size,float maxWidth) {
 const auto all=wide(text);std::vector<WrappedLine> output;std::vector<GlyphUnit> current;
 auto emit=[&](size_t count){
  if(count==0){output.emplace_back();return;}
  WrappedLine line;for(size_t i=0;i<count;i++){const auto& g=current[i];if(!line.empty()&&line.back().font==g.font)line.back().text+=g.text;else line.push_back({g.text,g.font});}
  output.push_back(std::move(line));current.erase(current.begin(),current.begin()+count);
  while(!current.empty()&&current.front().space)current.erase(current.begin());
 };
 for(size_t pos=0;pos<all.size();) {
  if(all[pos]==u'\n'||all[pos]==u'\r'){if(all[pos]==u'\r'&&pos+1<all.size()&&all[pos+1]==u'\n')++pos;emit(current.size());++pos;continue;}
  size_t start=pos;uint32_t cp=codepointAt(all,pos);
  // The lookahead below consumes every combining mark with its base.
  FPDF_FONT chosen=nullptr;float ignored=0;
  if(latinCoverage.has(cp))chosen=latin;
  else if(cjkFont&&cjkCoverage&&cjkCoverage->has(cp))chosen=cjkFont;
  if(!chosen||!ops::FPDFFont_GetGlyphWidth(chosen,cp,size,&ignored))throw std::runtime_error("No embedded font has a required glyph");
  std::u16string unit=all.substr(start,pos-start);
  while(pos<all.size()){size_t next=pos;uint32_t mark=codepointAt(all,next);if(!combining(mark))break;unit.append(all.substr(pos,next-pos));pos=next;}
  current.push_back({std::move(unit),chosen,cp,cp==' '||cp=='\t'});
  // push_back above and the suffix check below guarantee a nonempty line.
  while(textWidth(current,size)>maxWidth){
   size_t boundary=0;
   for(size_t i=current.size()-1;i>0;--i){
    if(current[i-1].space){boundary=i-1;break;}
    if((cjk(current[i-1].codepoint)||cjk(current[i].codepoint))&&!closingPunctuation(current[i].codepoint)&&!openingPunctuation(current[i-1].codepoint)){boundary=i;break;}
   }
   if(boundary==0)throw std::runtime_error("Unbreakable text exceeds its original area");
   emit(boundary);
   if(current.empty())throw std::runtime_error("A glyph exceeds its original area");
  }
 }
 if(!current.empty())emit(current.size());
 return output;
}
void annotate(FPDF_PAGE page,const json& block,double height,const std::string& text){
 auto a=ops::FPDFPage_CreateAnnot(page,FPDF_ANNOT_TEXT);if(!a)throw std::runtime_error("Cannot attach translated text");
 // A compact, visible comment marker keeps the original readable. A block-sized
 // annotation expands its icon over the source in some viewers; NOVIEW hides it.
 const float width=ops::FPDF_GetPageWidthF(page),size=std::min(16.f,std::min(width,static_cast<float>(height)));
 const float left=std::clamp(block.value("left",0.f)-size-4.f,0.f,width-size);
 const float top=std::clamp(static_cast<float>(height-block.value("top",0.f)),size,static_cast<float>(height));
 FS_RECTF r{left,top,left+size,top-size};
 bool ok=ops::FPDFAnnot_SetRect(a,&r)&&ops::FPDFAnnot_SetFlags(a,0);
 ops::FPDFAnnot_SetColor(a,FPDFANNOT_COLORTYPE_Color,198,40,40,255);
 auto name=wide("Comment");ops::FPDFAnnot_SetStringValue(a,"Name",reinterpret_cast<const unsigned short*>(name.c_str()));
 auto s=wide(text);ok=ops::FPDFAnnot_SetStringValue(a,"Contents",reinterpret_cast<const unsigned short*>(s.c_str()))&&ok;
 auto title=wide("Translation - placement needs review");ops::FPDFAnnot_SetStringValue(a,"T",reinterpret_cast<const unsigned short*>(title.c_str()));ops::FPDFPage_CloseAnnot(a);
 if(!ok)throw std::runtime_error("Cannot save translation annotation");
}
void unload(){if(ctx){ops::llama_free(ctx);ctx=nullptr;}if(model){ops::llama_model_free(model);model=nullptr;}loadedPath.clear();loadedProfile.clear();}
bool abortInference(void*){return stopped.load();}
std::string languageName(const std::string& code){
 if(code=="ru")return "Russian";if(code=="zh-Hans")return "Simplified Chinese";if(code=="fr")return "French";
 if(code=="en")return "English";if(code=="de")return "German";if(code=="es")return "Spanish";
 throw std::runtime_error("Unsupported language pair");
}
std::string gemmaLanguageCode(const std::string& code){return code=="zh-Hans"?"zh":code;}


void appendRun(TextRun& destination,const TextRun& next,const std::string& separator) {
 auto& a=destination.block;const auto& b=next.block;
 a["text"]=a["text"].get<std::string>()+separator+b["text"].get<std::string>();
 a["sourceText"]=a["text"];
 for(const char* edge:{"left","top"})a[edge]=std::min(a[edge].get<double>(),b[edge].get<double>());
 for(const char* edge:{"right","bottom","fontSize"})a[edge]=std::max(a[edge].get<double>(),b[edge].get<double>());
 for(const auto& index:b["objectIndices"])a["objectIndices"].push_back(index);
}

json groupText(std::vector<TextRun> runs) {
 // Sort by the transformed baseline with a strict ordering. A fuzzy comparator
 // is not transitive and can scramble fragments on the same printed line.
 std::sort(runs.begin(),runs.end(),[](const TextRun& a,const TextRun& b){
  if(a.baseline!=b.baseline)return a.baseline<b.baseline;
  return a.block["left"].get<double>()<b.block["left"].get<double>();
 });
 // Establish rows before x ordering: tiny baseline differences must not visit a
 // right-hand fragment before its left-hand neighbour. Anchor each row once so
 // successive comparisons cannot drift into the following printed line.
 std::vector<std::vector<TextRun>> rows;
 for(auto& run:runs){
  int row=-1;
  if(run.horizontal)for(size_t i=0;i<rows.size();++i){
   const auto& anchor=rows[i].front();
   const double fs=std::min(anchor.block["fontSize"].get<double>(),run.block["fontSize"].get<double>());
   if(anchor.horizontal&&std::abs(anchor.baseline-run.baseline)<fs*0.15){row=static_cast<int>(i);break;}
  }
  if(row<0)rows.push_back({std::move(run)});else rows[row].push_back(std::move(run));
 }
 runs.clear();
 for(auto& row:rows){
  std::sort(row.begin(),row.end(),[](const TextRun& a,const TextRun& b){return a.block["left"].get<double>()<b.block["left"].get<double>();});
  for(auto& run:row)runs.push_back(std::move(run));
 }
 std::vector<TextRun> lineRuns;
 for(const auto& run:runs){
  int best=-1;double nearest=1e9;
  for(size_t i=0;i<lineRuns.size();++i){
   const auto& line=lineRuns[i];const auto& a=line.block;const auto& b=run.block;
   const double fs=std::max(a["fontSize"].get<double>(),b["fontSize"].get<double>());
   const double gap=b["left"].get<double>()-a["right"].get<double>();
   if(line.horizontal&&run.horizontal&&std::abs(line.baseline-run.baseline)<fs*0.15&&
      gap>=-fs*0.15&&gap<fs*0.8&&gap<nearest&&
      a["text"].get<std::string>().size()+b["text"].get<std::string>().size()+1<=1800){best=static_cast<int>(i);nearest=gap;}
  }
  if(best<0){lineRuns.push_back(run);continue;}
  auto& line=lineRuns[best];const auto text=line.block["text"].get<std::string>();
  const auto next=run.block["text"].get<std::string>();
  const bool separated=text.back()==' '||next.front()==' '||text.back()=='\t'||next.front()=='\t';
  const bool sign=text.back()=='-'||(text.size()>=3&&text.compare(text.size()-3,3,"−")==0);
  appendRun(line,run,!separated&&!sign&&nearest>run.block["fontSize"].get<double>()*0.15?" ":"");
 }
 std::sort(lineRuns.begin(),lineRuns.end(),[](const TextRun& a,const TextRun& b){
  if(a.baseline!=b.baseline)return a.baseline<b.baseline;
  return a.block["left"].get<double>()<b.block["left"].get<double>();
 });
 struct Paragraph { TextRun content;TextRun lastLine; };
 std::vector<Paragraph> paragraphs;
 for(const auto& line:lineRuns){
  int best=-1;double nearest=1e9;
  for(size_t i=0;i<paragraphs.size();++i){
   const auto& paragraph=paragraphs[i];const auto& previous=paragraph.lastLine;
   const auto& a=previous.block;const auto& b=line.block;
   const double fs=std::max(a["fontSize"].get<double>(),b["fontSize"].get<double>());
   const double advance=line.baseline-previous.baseline;
   const double gap=b["top"].get<double>()-a["bottom"].get<double>();
   if(previous.horizontal&&line.horizontal&&previous.font==line.font&&
      std::abs(a["fontSize"].get<double>()-b["fontSize"].get<double>())<fs*0.15&&
      std::abs(a["left"].get<double>()-b["left"].get<double>())<std::max(3.,fs*0.6)&&
      advance>fs*0.7&&advance<fs*1.5&&gap>=-fs*0.15&&gap<fs*0.65&&advance<nearest&&
      paragraph.content.block["text"].get<std::string>().size()+b["text"].get<std::string>().size()+1<=1800){best=static_cast<int>(i);nearest=advance;}
  }
  if(best<0){paragraphs.push_back({line,line});continue;}
  appendRun(paragraphs[best].content,line," ");paragraphs[best].lastLine=line;
 }
 json blocks=json::array();for(auto& paragraph:paragraphs)blocks.push_back(std::move(paragraph.content.block));
 return blocks;
}
}
using namespace detail;
std::string inspect(const std::string& path){try{std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);return json{{"pageCount",ops::FPDF_GetPageCount(d.value)},{"encrypted",false}}.dump();}catch(const std::exception&e){return failure(e);}}
std::string readPage(const std::string& path,int index){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);Page p(d.value,index);TextPage tp(p.value);
 const double w=ops::FPDF_GetPageWidthF(p.value),h=ops::FPDF_GetPageHeightF(p.value);std::vector<TextRun> runs;
 for(int i=0;i<ops::FPDFPage_CountObjects(p.value);i++){
  auto obj=ops::FPDFPage_GetObject(p.value,i);if(ops::FPDFPageObj_GetType(obj)!=FPDF_PAGEOBJ_TEXT)continue;
  const auto text=objectText(obj,tp.value);if(text.empty()||text.find_first_not_of(" \r\n\t")==std::string::npos)continue;
  float l=0,b=0,r=0,t=0,size=10; if(!ops::FPDFPageObj_GetBounds(obj,&l,&b,&r,&t))continue;ops::FPDFTextObj_GetFontSize(obj,&size);
  FS_MATRIX matrix{};const bool transformed=ops::FPDFPageObj_GetMatrix(obj,&matrix);
  if(transformed)size*=std::hypot(matrix.c,matrix.d);
  if(!std::isfinite(size)||size<=0)size=std::max(1.f,t-b);
  const bool horizontal=transformed&&matrix.a>0&&matrix.d>0&&std::abs(matrix.b)<0.001&&std::abs(matrix.c)<0.001;
  json block={{"id","p"+std::to_string(index)+"-o"+std::to_string(i)},{"text",text},{"sourceText",text},{"left",l},{"top",h-t},{"right",r},{"bottom",h-b},{"fontSize",size},{"source","native"},{"objectIndex",i},{"objectIndices",json::array({i})}};
  runs.push_back({std::move(block),transformed?h-matrix.f:h-t,ops::FPDFTextObj_GetFont(obj),horizontal});
 }
 const auto merged=groupText(std::move(runs));
 return json{{"index",index},{"width",w},{"height",h},{"rotation",ops::FPDFPage_GetRotation(p.value)},{"blocks",merged}}.dump();
}catch(const std::exception&e){return failure(e);}}
Raster render(const std::string& path,int index,int width){
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);Page p(d.value,index);
 width=std::clamp(width,128,1800);const double pw=ops::FPDF_GetPageWidthF(p.value),ph=ops::FPDF_GetPageHeightF(p.value);
 if(pw<=0||ph<=0)throw std::runtime_error("Invalid page dimensions");
 int height=static_cast<int>(std::ceil(width*ph/pw));if(height>4096){width=std::max(1,static_cast<int>(width*(4096.0/height)));height=4096;}
 Raster r{width,height,std::vector<uint8_t>(static_cast<size_t>(width)*height*4)};
 auto bmp=ops::FPDFBitmap_CreateEx(width,height,FPDFBitmap_BGRA,r.rgba.data(),width*4);if(!bmp)throw std::runtime_error("Cannot allocate page bitmap");
 ops::FPDFBitmap_FillRect(bmp,0,0,width,height,0xffffffff);ops::FPDF_RenderPageBitmap(bmp,p.value,0,0,width,height,0,FPDF_ANNOT);ops::FPDFBitmap_Destroy(bmp);return r;
}
std::string loadModel(const std::string& path,int context,int threads,const std::string& profile){try{
 std::lock_guard<std::mutex> lock(modelMutex);std::call_once(llamaInit,[]{ops::llama_log_set([](ggml_log_level,const char*,void*){},nullptr);ops::llama_backend_init();});stopped=false;
 if(profile!="hy-mt2"&&profile!="translategemma")throw std::runtime_error("Unknown translation profile");
 const int requestedContext=std::clamp(context,1024,4096),requestedThreads=std::clamp(threads,1,4);
 if(model&&loadedPath==path&&loadedProfile==profile&&ops::llama_n_ctx(ctx)==static_cast<uint32_t>(requestedContext)&&ops::llama_n_threads(ctx)==requestedThreads)return json{{"loaded",true},{"context",ops::llama_n_ctx(ctx)},{"threads",ops::llama_n_threads(ctx)}}.dump();unload();
 auto mp=ops::llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;mp.check_tensors=true;
 model=ops::llama_model_load_from_file(path.c_str(),mp);if(!model)throw std::runtime_error("Model cannot be loaded by this runtime");
 if(ops::llama_model_has_encoder(model)){unload();throw std::runtime_error("Model architecture does not match its pinned translation profile");}
 auto cp=ops::llama_context_default_params();cp.n_ctx=std::clamp(context,1024,4096);cp.n_batch=128;cp.n_ubatch=64;cp.n_threads=std::clamp(threads,1,4);cp.n_threads_batch=cp.n_threads;cp.offload_kqv=false;cp.abort_callback=abortInference;cp.abort_callback_data=nullptr;
 ctx=ops::llama_init_from_model(model,cp);if(!ctx){unload();throw std::runtime_error("Insufficient resources for the model context");}loadedPath=path;loadedProfile=profile;
 return json{{"loaded",true},{"context",ops::llama_n_ctx(ctx)},{"threads",ops::llama_n_threads(ctx)},{"modelBytes",ops::llama_model_size(model)},{"templateAvailable",ops::llama_model_chat_template(model,nullptr)!=nullptr}}.dump();
}catch(const std::exception&e){return failure(e);}}
std::string translate(const std::string& text,const std::string& glossary,const std::string& context,const std::string& sourceLanguage,const std::string& targetLanguage){try{
 std::lock_guard<std::mutex> lock(modelMutex);if(!ctx)throw std::runtime_error("Model is not loaded");if(stopped)throw std::runtime_error("Canceled");
 const auto sourceName=languageName(sourceLanguage),targetName=languageName(targetLanguage);
 if(sourceLanguage==targetLanguage)throw std::runtime_error("Source and target languages must differ");
 if(text.empty())return json{{"text",text},{"tokens",0},{"seconds",0},{"skipped",true}}.dump();
 if(loadedProfile!="hy-mt2"&&!glossary.empty())throw std::runtime_error("This model uses direct translation without a glossary. Choose a glossary-capable model to apply saved terms.");
 std::string instruction;
 if(!glossary.empty())instruction+="Reference the following translations:\n"+glossary+"\n\n";
 if(!context.empty())instruction+="[Background Information]\n"+context+"\n";
 if(sourceLanguage=="en"&&targetLanguage=="ru")
  instruction+="Translate the following text into Russian. Note that you should only output the translated result without any additional explanation:\n\n"+text;
 else
  instruction+="Translate the following text from "+sourceName+" ("+sourceLanguage+") into "+targetName+" ("+targetLanguage+"). Only output the translation without additional explanation:\n\n"+text;
 const char* tmpl=ops::llama_model_chat_template(model,nullptr);std::string prompt;
 if(loadedProfile=="translategemma"){
  // Preserve the pinned TranslateGemma structured template while specializing
  // its exact language metadata for each supported direction.
  const auto gemmaSource=gemmaLanguageCode(sourceLanguage),gemmaTarget=gemmaLanguageCode(targetLanguage);
  prompt="<bos><start_of_turn>user\nYou are a professional "+sourceName+" ("+gemmaSource+") to "+targetName+" ("+gemmaTarget+") translator. Your goal is to accurately convey the meaning and nuances of the original "+sourceName+" text while adhering to "+targetName+" grammar, vocabulary, and cultural sensitivities.\nProduce only the "+targetName+" translation, without any additional explanations or commentary. Please translate the following "+sourceName+" text into "+targetName+":\n\n\n"+text+"<end_of_turn>\n<start_of_turn>model\n";
 }else {
  if(!tmpl)throw std::runtime_error("GGUF lacks a verified chat template");
  llama_chat_message message{"user",instruction.c_str()};std::vector<char> formatted(instruction.size()*3+8192);
  int n=ops::llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),formatted.size());if(n<0)throw std::runtime_error("Unsupported GGUF chat template");if(n>=static_cast<int>(formatted.size())){formatted.resize(n+1);n=ops::llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),formatted.size());if(n<0||n>=static_cast<int>(formatted.size()))throw std::runtime_error("Cannot format translation prompt");}
  prompt.assign(formatted.data(),n);
 }
 const auto vocab=ops::llama_model_get_vocab(model);
 int plainCount=-ops::llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,false,true);if(plainCount<=0)throw std::runtime_error("Translation prompt contains no tokens");
 std::vector<llama_token> plain(plainCount);ops::llama_tokenize(vocab,prompt.data(),prompt.size(),plain.data(),plainCount,false,true);
 // Some templates already emit BOS. Asking the tokenizer to add it again
 // changes the actual request and can degrade translation.
 const bool addSpecial=plain.front()!=ops::llama_vocab_bos(vocab);
 int count=-ops::llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,addSpecial,true);
 // The pinned community IQ files declare EOS=3, while their Hunyuan chat
 // template ends assistant messages with this explicit token (official Q4's
 // EOS). Honour the template boundary without changing the verified GGUF.
 llama_token assistantEnd=LLAMA_TOKEN_NULL;
 if(loadedProfile=="hy-mt2"&&std::string(tmpl).find("<｜hy_Assistant｜>")!=std::string::npos&&std::string(tmpl).find("<｜hy_place▁holder▁no▁2｜>")!=std::string::npos){
  const std::string end="<｜hy_place▁holder▁no▁2｜>";llama_token candidate;
  if(ops::llama_tokenize(vocab,end.data(),static_cast<int>(end.size()),&candidate,1,false,true)==1)assistantEnd=candidate;
 }
 if(loadedProfile=="hy-mt2"&&std::string(tmpl).find("<|eos|>")!=std::string::npos){
  const std::string end="<|eos|>";llama_token candidate;
  if(ops::llama_tokenize(vocab,end.data(),static_cast<int>(end.size()),&candidate,1,false,true)==1)assistantEnd=candidate;
 }
 if(count<=0||count>static_cast<int>(ops::llama_n_ctx(ctx))-384)throw std::runtime_error("Block exceeds model context; split it without truncation");
 std::vector<llama_token> tokens(count);ops::llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),count,addSpecial,true);ops::llama_memory_clear(ops::llama_get_memory(ctx),true);
 auto start=std::chrono::steady_clock::now();
 for(int pos=0;pos<count;pos+=128){if(stopped)throw std::runtime_error("Canceled");auto batch=ops::llama_batch_get_one(tokens.data()+pos,std::min(128,count-pos));if(ops::llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Inference failed while reading input");}
 auto sampler=ops::llama_sampler_chain_init(ops::llama_sampler_chain_default_params());
 if(loadedProfile=="hy-mt2"){
 ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_penalties(ops::llama_vocab_n_tokens(vocab),64,1.05f,0.0f,0.0f));
 ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_top_k(20));
 ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_top_p(0.6f,1));
 ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_temp(0.7f));
 ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_dist(42));
 }else ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_greedy());
 std::string output;int generated=0;bool ended=false;
 int sourceTokens=-ops::llama_tokenize(vocab,text.data(),static_cast<int>(text.size()),nullptr,0,false,false);
 int limit=std::min({static_cast<int>(ops::llama_n_ctx(ctx))-count-4,std::max(96,sourceTokens*4+32),1024});
 try{for(int i=0;i<limit;i++){
  if(stopped)throw std::runtime_error("Canceled");auto token=ops::llama_sampler_sample(sampler,ctx,-1);if(assistantEnd==LLAMA_TOKEN_NULL?ops::llama_vocab_is_eog(vocab,token):token==assistantEnd){ended=true;break;}
  char buf[512];int len=ops::llama_token_to_piece(vocab,token,buf,sizeof(buf),0,false);if(len<0)throw std::runtime_error("Cannot decode model token");output.append(buf,len);generated++;
  auto batch=ops::llama_batch_get_one(&token,1);if(ops::llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Inference failed while generating output");
 }}catch(...){ops::llama_sampler_free(sampler);throw;}ops::llama_sampler_free(sampler);
 if(!ended)throw std::runtime_error("Translation exceeded context; output was not committed");
 if(output.empty())throw std::runtime_error("Model produced no translation");
 if(!preservesCriticalTokens(text,output,sourceLanguage,targetLanguage))throw std::runtime_error("Translation changed a protected number, code or negation; original retained for review");
 double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
 return json{{"text",output},{"tokens",generated},{"seconds",seconds},{"tokensPerSecond",generated/std::max(seconds,0.001)}}.dump();
}catch(const std::exception&e){return failure(e);}}
void releaseModel(){std::lock_guard<std::mutex> lock(modelMutex);unload();}
void cancel(){stopped=true;}
std::string exportPage(const std::string& source,int index,const std::string& blocksJson,const std::string& destination,const std::string& fontPath,const std::string& cjkFontPath){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc src(source);Doc dest(ops::FPDF_CreateNewDocument());int ix=index;if(!ops::FPDF_ImportPagesByIndex(dest.value,src.value,&ix,1,0))throw std::runtime_error("Cannot import original page");Page page(dest.value,0);
 std::ifstream f(fontPath,std::ios::binary);std::vector<unsigned char> data((std::istreambuf_iterator<char>(f)),{});if(data.empty())throw std::runtime_error("Cyrillic export font is missing");
 FPDF_FONT font=ops::FPDFText_LoadFont(dest.value,data.data(),data.size(),FPDF_FONT_TRUETYPE,true);if(!font)throw std::runtime_error("Cannot embed Cyrillic font");
 FPDF_FONT cjkFont=nullptr;std::vector<unsigned char> cjkData;
 if(!cjkFontPath.empty()){std::ifstream cf(cjkFontPath,std::ios::binary);cjkData.assign(std::istreambuf_iterator<char>(cf),{});if(cjkData.empty()){ops::FPDFFont_Close(font);throw std::runtime_error("Simplified Chinese export font is missing");}cjkFont=ops::FPDFText_LoadFont(dest.value,cjkData.data(),cjkData.size(),FPDF_FONT_TRUETYPE,true);if(!cjkFont){ops::FPDFFont_Close(font);throw std::runtime_error("Cannot embed Simplified Chinese font");}}
 const FontCoverage latinCoverage(data);const FontCoverage cjkCoverage(cjkData);
 const auto input=json::parse(blocksJson);const auto blocks=input.is_array()?input:input.at("blocks");double height=ops::FPDF_GetPageHeightF(page.value);int partial=0;json issues=json::array();std::vector<FPDF_PAGEOBJECT> remove;
 const auto issue=[&](const json& block,const std::string& reason,const std::string& message){partial++;issues.push_back(json{{"blockId",block.value("id",std::string{})},{"reason",reason},{"message",message}});};
 try{for(const auto& block:blocks){
  if(block.contains("translatedText")&&block["translatedText"].is_null()){issue(block,"missing_translation","No complete translation is available; the original text was retained.");continue;}
  std::string translated=block.value("translatedText",block.value("translation",std::string{}));if(translated.empty()||translated.find_first_not_of(" \t\r\n")==std::string::npos){issue(block,"missing_translation","No complete translation is available; the original text was retained.");continue;}
  double width=block.value("right",0.)-block.value("left",0.),boxHeight=block.value("bottom",0.)-block.value("top",0.);float original=block.value("fontSize",10.f),minimum=std::max(6.f,0.8f*original),size=std::max(minimum,original);std::vector<WrappedLine> wrapped;bool fits=false;
  if(block.value("source",std::string("native"))=="native"&&ops::FPDFPage_GetRotation(page.value)==0&&width>0&&boxHeight>0&&block.value("status",std::string("translated"))!="partial"){
   for(;size>=minimum-0.01f;size-=0.5f){try{wrapped=lines(font,cjkFont,latinCoverage,cjkFont?&cjkCoverage:nullptr,translated,size,width);fits=!wrapped.empty()&&wrapped.size()*size*1.15<=boxHeight+size*0.35;}catch(...){fits=false;}if(fits)break;}
  }
  if(!fits){annotate(page.value,block,height,translated);issue(block,"layout_fallback","The translation could not be placed safely at a readable size. The original remains with the full translation attached for review.");continue;}
  json indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex")&&block["objectIndex"].is_number_integer())indexes.push_back(block["objectIndex"]);
  bool safe=!indexes.empty();for(const auto& n:indexes){int oi=n.get<int>();auto obj=ops::FPDFPage_GetObject(page.value,oi);if(!obj||ops::FPDFPageObj_GetType(obj)!=FPDF_PAGEOBJ_TEXT){safe=false;break;}}
  if(!safe){annotate(page.value,block,height,translated);issue(block,"unsafe_text_object","The original text object could not be replaced safely. The translation is attached for review.");continue;}
  for(const auto& n:indexes)remove.push_back(ops::FPDFPage_GetObject(page.value,n.get<int>()));
  int row=0;for(const auto& line:wrapped){double x=block.value("left",0.);for(const auto& segment:line){auto obj=ops::FPDFPageObj_CreateTextObj(dest.value,segment.font,size);if(!obj)throw std::runtime_error("Cannot create translated text");
    if(!ops::FPDFText_SetText(obj,reinterpret_cast<const unsigned short*>(segment.text.c_str()))){ops::FPDFPageObj_Destroy(obj);throw std::runtime_error("Cannot write translated glyphs");}
    ops::FPDFPageObj_SetFillColor(obj,25,25,28,255);ops::FPDFPageObj_Transform(obj,1,0,0,1,x,height-block.value("top",0.)-size-row*size*1.15);ops::FPDFPage_InsertObject(page.value,obj);
    for(size_t i=0;i<segment.text.size();){uint32_t cp=codepointAt(segment.text,i);float advance=0;if(!ops::FPDFFont_GetGlyphWidth(segment.font,cp,size,&advance))throw std::runtime_error("Font lacks a required glyph");x+=advance;}
   }row++;
  }
 }
 std::sort(remove.begin(),remove.end());remove.erase(std::unique(remove.begin(),remove.end()),remove.end());for(auto obj:remove){if(!ops::FPDFPage_RemoveObject(page.value,obj))throw std::runtime_error("Cannot replace source text safely");ops::FPDFPageObj_Destroy(obj);}
 if(!ops::FPDFPage_GenerateContent(page.value))throw std::runtime_error("Cannot generate translated page content");save(dest.value,destination);
 }catch(...){if(cjkFont)ops::FPDFFont_Close(cjkFont);ops::FPDFFont_Close(font);throw;}if(cjkFont)ops::FPDFFont_Close(cjkFont);ops::FPDFFont_Close(font);
 return json{{"path",destination},{"partial",partial>0},{"partialBlocks",partial},{"issues",issues},{"pageCount",1}}.dump();
}catch(const std::exception&e){return failure(e);}}
std::string mergePdf(const std::string& pagesJson,const std::string& destination){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();auto paths=json::parse(pagesJson);if(!paths.is_array()||paths.empty())throw std::runtime_error("No completed pages to export");Doc d(ops::FPDF_CreateNewDocument());int i=0;for(const auto& path:paths){Doc p(path.get<std::string>());int index=0;if(!ops::FPDF_ImportPagesByIndex(d.value,p.value,&index,1,i++))throw std::runtime_error("Cannot assemble translated pages");}save(d.value,destination);Doc check(destination);if(ops::FPDF_GetPageCount(check.value)!=i)throw std::runtime_error("Export page count mismatch");return json{{"path",destination},{"pageCount",i}}.dump();
}catch(const std::exception&e){return failure(e);}}
}
