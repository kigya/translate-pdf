#include "core.hpp"
#include "core_detail.hpp"
#include "source_layout.hpp"
#include "core_operations.hpp"
#include "translation_guard.hpp"
#include "llama.h"
#include "fpdf_doc.h"
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
#include <cctype>
#include <cmath>
#include <codecvt>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <filesystem>
#include <limits>
#include <locale>
#include <mutex>
#if defined(__ANDROID__) && defined(TRANSLATEPDF_CPU_DISPATCH)
#include <dlfcn.h>
#include "ggml-backend.h"
#endif
#include <regex>
#include <stdexcept>
#include <set>
#include <memory>
#include <unistd.h>

namespace translatepdf {
using json = nlohmann::json;
namespace {
bool jsonIntegerEquals(const json& value,int expected){
 if(!value.is_number_integer())return false;
 if(value.is_number_unsigned()){
  const uint64_t integer=value.get<uint64_t>();
  return integer<=static_cast<uint64_t>(std::numeric_limits<int>::max())&&static_cast<int>(integer)==expected;
 }
 return value.get<int64_t>()==static_cast<int64_t>(expected);
}
}
namespace detail {
bool languageInvariantAscii(const std::string& text){
 // Keep an explicit ASCII-only allowlist: non-Latin scripts, units and
 // part codes must continue through translation. Empty/blank input keeps
 // its existing separate handling. Original PDF glyphs are preserved at export.
 return text.find_first_not_of(" \t\r\n")!=std::string::npos&&
  text.find_first_not_of("0123456789 \t\r\n!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")==std::string::npos;
}
std::string fontBaseName(FPDF_FONT font){
 if(!font)return {};const auto size=ops::FPDFFont_GetBaseFontName(font,nullptr,0);if(size<2||size>1024)return {};
 std::string name(size,'\0');if(ops::FPDFFont_GetBaseFontName(font,name.data(),name.size())!=size)return {};
 if(!name.empty()&&name.back()=='\0')name.pop_back();const auto subset=name.find('+');if(subset!=std::string::npos&&subset<=6)name.erase(0,subset+1);
 std::transform(name.begin(),name.end(),name.begin(),[](unsigned char c){return static_cast<char>(std::tolower(c));});return name;
}
bool pictogramFont(const std::string& rawName){
 const auto& name=rawName;
 if(name.rfind("omsym",0)==0||name=="pi_chars_1")return true;
 if(name=="symbol"||name=="symbolmt"||name=="dingbats"||name=="zapfdingbats"||name=="zapf dingbats")return true;
 if(name.rfind("wingdings",0)==0||name.rfind("webdings",0)==0||name.find("dingbat")!=std::string::npos)return true;
 return (name.size()>=4&&name.compare(name.size()-4,4,"icon")==0)||(name.size()>=5&&name.compare(name.size()-5,5,"icons")==0);
}
bool sourceGlyphIsland(const std::string& font,const std::string& text){
 if(pictogramFont(font))return true;
 const auto first=text.find_first_not_of(" \t\r\n"),last=text.find_last_not_of(" \t\r\n");if(first==std::string::npos)return false;
 const auto value=text.substr(first,last-first+1);return value=="®"||value=="™"||value=="©";
}
std::string inlineSymbolToken(int objectIndex){
 if(objectIndex<0)throw std::runtime_error("Inline symbol has an invalid source index");
 std::string letters;unsigned value=static_cast<unsigned>(objectIndex)+1U;
 while(value){--value;letters.push_back(static_cast<char>('A'+value%26U));value/=26U;}
 std::reverse(letters.begin(),letters.end());return "[[FVSYM"+letters+"]]";
}
namespace ops {
decltype(&::FPDFAnnot_SetColor) FPDFAnnot_SetColor = &::FPDFAnnot_SetColor;
decltype(&::FPDFAnnot_SetFlags) FPDFAnnot_SetFlags = &::FPDFAnnot_SetFlags;
decltype(&::FPDFAnnot_SetRect) FPDFAnnot_SetRect = &::FPDFAnnot_SetRect;
decltype(&::FPDFAnnot_SetStringValue) FPDFAnnot_SetStringValue = &::FPDFAnnot_SetStringValue;
decltype(&::FPDFBitmap_CreateEx) FPDFBitmap_CreateEx = &::FPDFBitmap_CreateEx;
decltype(&::FPDFBitmap_Destroy) FPDFBitmap_Destroy = &::FPDFBitmap_Destroy;
decltype(&::FPDFBitmap_FillRect) FPDFBitmap_FillRect = &::FPDFBitmap_FillRect;
decltype(&::FPDFBitmap_GetBuffer) FPDFBitmap_GetBuffer = &::FPDFBitmap_GetBuffer;
decltype(&::FPDFBitmap_GetFormat) FPDFBitmap_GetFormat = &::FPDFBitmap_GetFormat;
decltype(&::FPDFBitmap_GetHeight) FPDFBitmap_GetHeight = &::FPDFBitmap_GetHeight;
decltype(&::FPDFBitmap_GetStride) FPDFBitmap_GetStride = &::FPDFBitmap_GetStride;
decltype(&::FPDFBitmap_GetWidth) FPDFBitmap_GetWidth = &::FPDFBitmap_GetWidth;
decltype(&::FPDFFont_Close) FPDFFont_Close = &::FPDFFont_Close;
decltype(&::FPDFFont_GetBaseFontName) FPDFFont_GetBaseFontName = &::FPDFFont_GetBaseFontName;
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
decltype(&::FPDFPage_New) FPDFPage_New = &::FPDFPage_New;
decltype(&::FPDFPageObj_NewImageObj) FPDFPageObj_NewImageObj = &::FPDFPageObj_NewImageObj;
decltype(&::FPDFImageObj_SetBitmap) FPDFImageObj_SetBitmap = &::FPDFImageObj_SetBitmap;
decltype(&::FPDFPageObj_SetIsActive) FPDFPageObj_SetIsActive = &::FPDFPageObj_SetIsActive;
decltype(&::FPDFFormObj_CountObjects) FPDFFormObj_CountObjects = &::FPDFFormObj_CountObjects;
decltype(&::FPDFFormObj_GetObject) FPDFFormObj_GetObject = &::FPDFFormObj_GetObject;
decltype(&::FPDFTextObj_GetFont) FPDFTextObj_GetFont = &::FPDFTextObj_GetFont;
decltype(&::FPDFTextObj_GetFontSize) FPDFTextObj_GetFontSize = &::FPDFTextObj_GetFontSize;
decltype(&::FPDFTextObj_GetRenderedBitmap) FPDFTextObj_GetRenderedBitmap = &::FPDFTextObj_GetRenderedBitmap;
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
decltype(&::FPDF_GetPageBoundingBox) FPDF_GetPageBoundingBox = &::FPDF_GetPageBoundingBox;
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
decltype(&::llama_memory_seq_cp) llama_memory_seq_cp = &::llama_memory_seq_cp;
decltype(&::llama_memory_seq_rm) llama_memory_seq_rm = &::llama_memory_seq_rm;
decltype(&::llama_model_chat_template) llama_model_chat_template = &::llama_model_chat_template;
decltype(&::llama_model_default_params) llama_model_default_params = &::llama_model_default_params;
decltype(&::llama_model_free) llama_model_free = &::llama_model_free;
decltype(&::llama_model_get_vocab) llama_model_get_vocab = &::llama_model_get_vocab;
decltype(&::llama_model_has_encoder) llama_model_has_encoder = &::llama_model_has_encoder;
decltype(&::llama_model_load_from_file) llama_model_load_from_file = &::llama_model_load_from_file;
decltype(&::llama_model_size) llama_model_size = &::llama_model_size;
decltype(&::llama_n_ctx) llama_n_ctx = &::llama_n_ctx;
decltype(&::llama_n_ctx_seq) llama_n_ctx_seq = &::llama_n_ctx_seq;
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
namespace {
size_t tokenDecodeCalls(size_t tokens,size_t batchSize){
 return tokens/batchSize+static_cast<size_t>(tokens%batchSize!=0);
}
}
bool shouldReuseExactPrefix(const std::vector<size_t>& prefixLengths,size_t commonPrefix,size_t batchSize){
 if(prefixLengths.size()<2||commonPrefix==0||batchSize==0)return false;
 size_t total=0,suffix=0;
 for(const auto length:prefixLengths){
  if(commonPrefix>length)return false;
  if(length>std::numeric_limits<size_t>::max()-total)return false;
  // Each suffix contribution is no larger than its full prefix length, so
  // the total-overflow guard also proves the suffix accumulation is safe.
  total+=length;suffix+=length-commonPrefix;
 }
 return tokenDecodeCalls(commonPrefix,batchSize)+tokenDecodeCalls(suffix,batchSize)<=tokenDecodeCalls(total,batchSize);
}
}
std::once_flag pdfInit, llamaInit;
std::mutex pdfMutex, modelMutex;
std::atomic<bool> stopped{false};
std::atomic<uint64_t> exportCancellationGeneration{0};
std::atomic<bool> weightRepackingAllowed{true};
bool loadedWeightRepackingAllowed = true;
std::atomic<int> processedInputTokens{0}, generatedTokens{0};
std::vector<llama_token> cachedPromptTokens;
llama_model* model = nullptr;
llama_context* ctx = nullptr;
std::string loadedPath;
std::string loadedProfile;
int sessionContext=2048,sessionThreads=2,sessionSequences=1;
int currentContext=0,currentThreads=0,currentSequences=0;
bool currentBatch=false,sessionBatch=false;
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
struct SourceLayoutFitError : std::runtime_error {
 json feedback;
 SourceLayoutFitError(const std::string& message,json value):std::runtime_error(message),feedback(std::move(value)){}
};
std::string objectText(FPDF_PAGEOBJECT obj,FPDF_TEXTPAGE tp) {
 auto bytes=ops::FPDFTextObj_GetText(obj,tp,nullptr,0); if(bytes<2)return {};
 std::u16string s(bytes/2,u'\0'); ops::FPDFTextObj_GetText(obj,tp,reinterpret_cast<unsigned short*>(s.data()),bytes);
 while(!s.empty()&&(s.back()==0||s.back()==u'\r'||s.back()==u'\n'))s.pop_back();
 return utf8(s);
}
bool sourceWhitespace(unsigned cp){return cp==' '||cp=='\t'||cp=='\r'||cp=='\n'||cp==0xa0;}
int checkedSourceIndex(const json& value,const char* message){
 if(!value.is_number_integer())throw std::runtime_error(message);
 if(value.is_number_unsigned()){const auto number=value.get<uint64_t>();if(number>static_cast<uint64_t>(std::numeric_limits<int>::max()))throw std::runtime_error(message);return static_cast<int>(number);}
 const auto number=value.get<int64_t>();if(number<std::numeric_limits<int>::min()||number>std::numeric_limits<int>::max())throw std::runtime_error(message);return static_cast<int>(number);
}
bool sourceHasCharacterSpan(const json& block,int objectIndex){
 const auto spans=block.value("sourceSpans",json::array());if(!spans.is_array())throw std::runtime_error("Invalid source character-span metadata");
 for(const auto& span:spans)if(checkedSourceIndex(span.at("objectIndex"),"Invalid source character-span object index")==objectIndex)return true;return false;
}
std::vector<int> sourceCharacterIndices(const json& block,int objectIndex){
 std::vector<int> result;for(const auto& span:block.value("sourceSpans",json::array()))if(checkedSourceIndex(span.at("objectIndex"),"Invalid source character-span object index")==objectIndex){
  if(!span.at("characterIndices").is_array())throw std::runtime_error("Invalid source character indexes");for(const auto& value:span.at("characterIndices"))result.push_back(checkedSourceIndex(value,"Invalid source character index"));
 }return result;
}
std::string sourceCharacterText(FPDF_TEXTPAGE page,const std::vector<int>& indexes){std::u16string result;for(int index:indexes){const auto cp=FPDFText_GetUnicode(page,index);if(cp>0xffff){result.push_back(static_cast<char16_t>(0xd800+((cp-0x10000)>>10)));result.push_back(static_cast<char16_t>(0xdc00+((cp-0x10000)&0x3ff)));}else result.push_back(static_cast<char16_t>(cp));}return utf8(result);}
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
InlineGlyph::InlineGlyph(std::string value,float width,float height):token(std::move(value)),widthEm(width),heightEm(height){}
GlyphUnit::GlyphUnit(std::u16string value,FPDF_FONT face,uint32_t cp,bool whitespace,float width,float height,std::string symbol):text(std::move(value)),font(face),codepoint(cp),space(whitespace),widthEm(width),heightEm(height),symbolToken(std::move(symbol)){}
TextSegment::TextSegment(std::u16string value,FPDF_FONT face,std::string symbol,float width,float height):text(std::move(value)),font(face),symbolToken(std::move(symbol)),widthEm(width),heightEm(height){}
TextRun::TextRun(nlohmann::json value,double y,FPDF_FONT face,bool aligned,FPDF_FONT tail):block(std::move(value)),baseline(y),font(face),horizontal(aligned),tailFont(tail){}
float glyphWidth(const GlyphUnit& glyph,float size) { if(!glyph.symbolToken.empty())return size*glyph.widthEm;float w=0;if(!ops::FPDFFont_GetGlyphWidth(glyph.font,glyph.codepoint,size,&w))throw std::runtime_error("Font lacks a required glyph");return w; }
float textWidth(const std::vector<GlyphUnit>& glyphs,float size) { float sum=0;for(const auto& g:glyphs)sum+=glyphWidth(g,size);return sum; }
std::vector<WrappedLine> lines(FPDF_FONT latin,FPDF_FONT cjkFont,const FontCoverage& latinCoverage,const FontCoverage* cjkCoverage,const std::string& text,float size,float maxWidth,bool allowTokenBreak,const std::map<uint32_t,InlineGlyph>* inlineGlyphs) {
 const auto all=wide(text);std::vector<WrappedLine> output;std::vector<GlyphUnit> current;
 auto emit=[&](size_t count){
  if(count==0){output.emplace_back();return;}
  WrappedLine line;for(size_t i=0;i<count;i++){const auto& g=current[i];if(!g.symbolToken.empty())line.push_back({g.text,nullptr,g.symbolToken,g.widthEm,g.heightEm});else if(!line.empty()&&line.back().symbolToken.empty()&&line.back().font==g.font)line.back().text+=g.text;else line.push_back({g.text,g.font,{}});}
  output.push_back(std::move(line));current.erase(current.begin(),current.begin()+count);
  while(!current.empty()&&current.front().space)current.erase(current.begin());
 };
 for(size_t pos=0;pos<all.size();) {
  if(all[pos]==u'\n'||all[pos]==u'\r'){if(all[pos]==u'\r'&&pos+1<all.size()&&all[pos+1]==u'\n')++pos;emit(current.size());++pos;continue;}
  size_t start=pos;uint32_t cp=codepointAt(all,pos);
  // The lookahead below consumes every combining mark with its base.
  FPDF_FONT chosen=nullptr;float ignored=0;float widthEm=0,heightEm=0;std::string symbolToken;
  const auto inlineGlyph=inlineGlyphs?inlineGlyphs->find(cp):std::map<uint32_t,InlineGlyph>::const_iterator{};
  if(inlineGlyphs&&inlineGlyph!=inlineGlyphs->end()){symbolToken=inlineGlyph->second.token;widthEm=inlineGlyph->second.widthEm;heightEm=inlineGlyph->second.heightEm;}
  else if(latinCoverage.has(cp))chosen=latin;
  else if(cjkFont&&cjkCoverage&&cjkCoverage->has(cp))chosen=cjkFont;
  if(symbolToken.empty()&&(!chosen||!ops::FPDFFont_GetGlyphWidth(chosen,cp,size,&ignored)))throw std::runtime_error("No embedded font has a required glyph");
  std::u16string unit=all.substr(start,pos-start);
  while(pos<all.size()){size_t next=pos;uint32_t mark=codepointAt(all,next);if(!combining(mark))break;unit.append(all.substr(pos,next-pos));pos=next;}
  current.push_back({std::move(unit),chosen,cp,cp==' '||cp=='\t',widthEm,heightEm,std::move(symbolToken)});
  // push_back above and the suffix check below guarantee a nonempty line.
  while(textWidth(current,size)>maxWidth){
   size_t boundary=0;
   for(size_t i=current.size()-1;i>0;--i){
    if(current[i-1].space){boundary=i-1;break;}
    if((cjk(current[i-1].codepoint)||cjk(current[i].codepoint))&&!closingPunctuation(current[i].codepoint)&&!openingPunctuation(current[i-1].codepoint)){boundary=i;break;}
   }
   if(boundary==0&&allowTokenBreak&&current.size()>1)boundary=current.size()-1;
   if(boundary==0)throw std::runtime_error("Unbreakable text exceeds its original area");
   emit(boundary);
   if(current.empty())throw std::runtime_error("A glyph exceeds its original area");
  }
 }
 if(!current.empty())emit(current.size());
 return output;
}
void unload(){if(ctx){ops::llama_free(ctx);ctx=nullptr;}if(model){ops::llama_model_free(model);model=nullptr;}loadedPath.clear();loadedProfile.clear();cachedPromptTokens.clear();currentContext=currentThreads=currentSequences=0;}
bool abortInference(void*){return stopped.load();}
std::string languageName(const std::string& code){
 if(code=="ru")return "Russian";if(code=="zh-Hans")return "Simplified Chinese";if(code=="fr")return "French";
 if(code=="en")return "English";if(code=="de")return "German";if(code=="es")return "Spanish";
 throw std::runtime_error("Unsupported language pair");
}
std::string gemmaLanguageCode(const std::string& code){return code=="zh-Hans"?"zh":code;}


void appendRun(TextRun& destination,const TextRun& next,const std::string& separator) {
 auto& a=destination.block;const auto& b=next.block;
 const auto leftText=a["text"].get<std::string>(),rightText=b["text"].get<std::string>();
 const auto leftModel=a.value("modelText",leftText),rightModel=b.value("modelText",rightText);
 a["text"]=leftText+separator+rightText;
 a["sourceText"]=a["text"];
 a["modelText"]=leftModel+separator+rightModel;
 a["coordinateSpaceVersion"]=2;a["partialCrop"]=a.value("partialCrop",false)||b.value("partialCrop",false);
 for(const char* edge:{"left","top"})a[edge]=std::min(a[edge].get<double>(),b[edge].get<double>());
 for(const char* edge:{"right","bottom","fontSize"})a[edge]=std::max(a[edge].get<double>(),b[edge].get<double>());
 for(const auto& index:b["objectIndices"])if(std::find(a["objectIndices"].begin(),a["objectIndices"].end(),index)==a["objectIndices"].end())a["objectIndices"].push_back(index);
 if(b.contains("sourceSpans")){if(!a.contains("sourceSpans"))a["sourceSpans"]=json::array();for(const auto& span:b["sourceSpans"])a["sourceSpans"].push_back(span);}
 if(!a.contains("symbols")||!a["symbols"].is_array())a["symbols"]=json::array();
 if(b.contains("symbols")&&b["symbols"].is_array())for(const auto& symbol:b["symbols"])a["symbols"].push_back(symbol);
 if(!a.contains("sourceRects")||!a["sourceRects"].is_array())a["sourceRects"]=json::array();
 if(b.contains("sourceRects")&&b["sourceRects"].is_array())for(const auto& rect:b["sourceRects"])a["sourceRects"].push_back(rect);
 if(a.contains("linguisticFontSize")||b.contains("linguisticFontSize")){const double before=a.value("linguisticFontSize",0.),after=b.value("linguisticFontSize",0.);a["linguisticFontSize"]=std::max(before,after);if(before<=0&&after>0)destination.font=next.font;}
 if(!b.contains("linguisticFontSize")||b.value("linguisticFontSize",0.)>0)destination.tailFont=next.tailFont?next.tailFont:next.font;
}

namespace {
double linguisticSize(const json& block){const double size=block.value("linguisticFontSize",0.);return size>0?size:block.at("fontSize").get<double>();}
bool containsSourceIcon(const json& block){return block.contains("symbols")&&block["symbols"].is_array()&&!block["symbols"].empty();}
bool dottedSourceLeader(const std::string& text){
 size_t dots=0;for(unsigned char c:text){if(c=='.'){if(++dots>=3)return true;}else if(c!=' '&&c!='\t')dots=0;}
 return text.find("\xe2\x80\xa6")!=std::string::npos;
}
bool numberedSourceItem(const std::string& text){
 size_t at=text.find_first_not_of(" \t\r\n");if(at==std::string::npos)return false;const auto first=at;while(at<text.size()&&text[at]>='0'&&text[at]<='9')++at;
 return at>first&&at+1<text.size()&&(text[at]=='.'||text[at]==')')&&(text[at+1]==' '||text[at+1]=='\t');
}
bool startsPictogramLabel(const json& block){
 const std::string text=block.value("modelText",block.value("text",std::string{}));
 size_t at=text.find_first_not_of(" \t\r\n");if(at==std::string::npos)return false;
 constexpr std::string_view prefix="[[FVSYM";
 if(text.compare(at,prefix.size(),prefix)!=0)return false;
 at+=prefix.size();const size_t letters=at;
 while(at<text.size()&&text[at]>='A'&&text[at]<='Z')++at;
 if(at==letters||text.compare(at,2,"]]" )!=0)return false;
 at+=2;at=text.find_first_not_of(" \t\r\n",at);
 return at!=std::string::npos&&text[at]==':';
}
bool openSentenceContinuation(const TextRun& previous,const TextRun& next,double fontSize,bool allowTitleCase=false){
 const std::string& before=previous.block["text"].get_ref<const std::string&>();
 const std::string& after=next.block["text"].get_ref<const std::string&>();
 auto last=before.find_last_not_of(" \t\r\n");if(last==std::string::npos)return false;
 auto first=after.find_first_not_of(" \t\r\n");if(first==std::string::npos||first>=after.size())return false;
 if(before[last]=='.'||before[last]=='?'||before[last]=='!')return false;
 // Dotted leaders are common in contents pages and must never be interpreted
 // as a sentence continuation cue.
 if(dottedSourceLeader(before)||dottedSourceLeader(after))return false;
 const unsigned char initial=static_cast<unsigned char>(after[first]);
 if(initial>=0x80||(!std::islower(initial)&&!(allowTitleCase&&std::isupper(initial))))return false;
 const auto& a=previous.block;const auto& b=next.block;
 const double aLeft=a["left"].get<double>(),aRight=a["right"].get<double>();
 const double bLeft=b["left"].get<double>(),bRight=b["right"].get<double>();
 const double leftDelta=std::abs(aLeft-bLeft);
 const bool sameColumn=leftDelta<=fontSize*2.2&&std::min(aRight,bRight)>std::max(aLeft,bLeft);
 const double sizeDelta=std::abs(linguisticSize(a)-linguisticSize(b));
 const bool boundedFontChange=sizeDelta<fontSize*.35;
 const double advance=next.baseline-previous.baseline;
 const double gap=b["top"].get<double>()-a["bottom"].get<double>();
 return previous.horizontal&&next.horizontal&&sameColumn&&boundedFontChange&&
  advance>fontSize*.7&&advance<fontSize*1.5&&gap>=-fontSize*.15&&gap<fontSize*.65;
}
}

json groupText(std::vector<TextRun> runs,const std::vector<SourceRule>* sourceRules) {
 // Superscript trademark marks keep their real source geometry, but belong
 // to the adjacent linguistic row rather than a separate translation unit.
 for(auto& mark:runs){if(!sourceGlyphIsland({},mark.block.value("sourceText",std::string{})))continue;double nearest=1e9,baseline=mark.baseline;
  const auto& m=mark.block;
  for(const auto& text:runs){if(&mark==&text||!text.horizontal||containsSourceIcon(text.block))continue;const auto& b=text.block;const double fs=linguisticSize(b),gap=std::max({0.,m.at("left").get<double>()-b.at("right").get<double>(),b.at("left").get<double>()-m.at("right").get<double>()});
   if(gap<=fs*.85&&std::abs(text.baseline-mark.baseline)<=fs*.85&&std::min(m.at("bottom").get<double>(),b.at("bottom").get<double>())>std::max(m.at("top").get<double>(),b.at("top").get<double>())+.2&&gap<nearest){nearest=gap;baseline=text.baseline;}
  }
  mark.baseline=baseline;
 }
 const auto separatedByRule=[sourceRules](const json& a,const json& b,bool horizontal){
  if(!sourceRules)return false;
  for(const auto& rule:*sourceRules){
   if(horizontal&&std::abs(rule.y0-rule.y1)<.3){
    const double y=(rule.y0+rule.y1)*.5;
    if(y>=a.at("bottom").get<double>()-.2&&y<=b.at("top").get<double>()+.2&&
       std::min(rule.x0,rule.x1)<std::min(a.at("right").get<double>(),b.at("right").get<double>())&&
       std::max(rule.x0,rule.x1)>std::max(a.at("left").get<double>(),b.at("left").get<double>()))return true;
   }else if(!horizontal&&std::abs(rule.x0-rule.x1)<.3){
    const double x=(rule.x0+rule.x1)*.5;
    if(x>=a.at("right").get<double>()-.2&&x<=b.at("left").get<double>()+.2&&
       std::min(rule.y0,rule.y1)<std::min(a.at("bottom").get<double>(),b.at("bottom").get<double>())&&
       std::max(rule.y0,rule.y1)>std::max(a.at("top").get<double>(),b.at("top").get<double>()))return true;
   }
  }
  return false;
 };
 // Sort by the transformed baseline with a strict ordering. A fuzzy comparator
 // is not transitive and can scramble fragments on the same printed line.
 std::sort(runs.begin(),runs.end(),[](const TextRun& a,const TextRun& b){
  if(a.baseline!=b.baseline)return a.baseline<b.baseline;
  return a.block["left"].get<double>()<b.block["left"].get<double>();
 });
 // Establish rows before x ordering: tiny baseline differences must not visit a
 // right-hand fragment before its left-hand neighbour. Anchor each row once so
 // successive comparisons cannot drift into the following printed line.
 // Establish linguistic rows before assigning symbols. A symbol encountered
 // a few thousandths of a point before its text must see that text's row;
 // otherwise its wider tolerance can attach it to another column's prior row.
 std::stable_partition(runs.begin(),runs.end(),[](const TextRun& run){return !containsSourceIcon(run.block);});
 std::vector<std::vector<TextRun>> rows;
 for(auto& run:runs){
  int row=-1;double nearestRow=1e9;
  if(run.horizontal)for(size_t i=0;i<rows.size();++i){
   const auto& anchor=rows[i].front();
   const double fs=std::min(linguisticSize(anchor.block),linguisticSize(run.block));
   const double tolerance=containsSourceIcon(anchor.block)||containsSourceIcon(run.block)?std::max(3.,fs*.15):fs*.15;
   const double distance=std::abs(anchor.baseline-run.baseline);
   if(anchor.horizontal&&distance<tolerance&&distance<nearestRow){row=static_cast<int>(i);nearestRow=distance;}
  }
  if(row<0)rows.push_back({std::move(run)});else rows[row].push_back(std::move(run));
 }
 runs.clear();
 std::stable_sort(rows.begin(),rows.end(),[](const std::vector<TextRun>& a,const std::vector<TextRun>& b){return a.front().baseline<b.front().baseline;});
 for(auto& row:rows){
  std::sort(row.begin(),row.end(),[](const TextRun& a,const TextRun& b){return a.block["left"].get<double>()<b.block["left"].get<double>();});
  for(auto& run:row)runs.push_back(std::move(run));
 }
 std::vector<TextRun> lineRuns;
 for(const auto& run:runs){
  int best=-1;double nearest=1e9;
  for(size_t i=0;i<lineRuns.size();++i){
   const auto& line=lineRuns[i];const auto& a=line.block;const auto& b=run.block;
   const double fs=std::max(linguisticSize(a),linguisticSize(b));
   const double gap=b["left"].get<double>()-a["right"].get<double>();
   const double tolerance=containsSourceIcon(a)||containsSourceIcon(b)?std::max(3.,fs*.15):fs*.15;
   if(line.horizontal&&run.horizontal&&std::abs(line.baseline-run.baseline)<tolerance&&
      gap>=-fs*0.15&&gap<fs*0.8&&gap<nearest&&!separatedByRule(a,b,false)&&
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
  const bool newPictogramLabel=startsPictogramLabel(line.block);
  for(size_t i=0;i<paragraphs.size();++i){
   const auto& paragraph=paragraphs[i];const auto& previous=paragraph.lastLine;
   const auto& a=previous.block;const auto& b=line.block;
   const double fs=std::max(linguisticSize(a),linguisticSize(b));
   const double advance=line.baseline-previous.baseline;
   const double gap=b["top"].get<double>()-a["bottom"].get<double>();
   const bool standardContinuation=(previous.font==line.font||previous.tailFont==line.font)&&
      std::abs(linguisticSize(a)-linguisticSize(b))<fs*0.15&&
      std::abs(a["left"].get<double>()-b["left"].get<double>())<std::max(3.,fs*0.6);
   const bool sentenceContinuation=openSentenceContinuation(previous,line,fs);
   const bool pictogramLabelContinuation=startsPictogramLabel(paragraph.content.block)&&
      openSentenceContinuation(previous,line,fs,true);
   const auto& contentsText=b.at("text").get_ref<const std::string&>();const auto referenceEnd=contentsText.find_last_not_of(" \t");
   const bool contentsContinuation=referenceEnd!=std::string::npos&&contentsText[referenceEnd]>='0'&&contentsText[referenceEnd]<='9'&&dottedSourceLeader(contentsText)&&
      std::abs(linguisticSize(a)-linguisticSize(b))<fs*.15&&std::abs(a.at("left").get<double>()-b.at("left").get<double>())<std::max(3.,fs*.6);
   const bool separateEntry=numberedSourceItem(b["text"].get<std::string>())||dottedSourceLeader(a["text"].get<std::string>());
   if(!newPictogramLabel&&!separateEntry&&!separatedByRule(a,b,true)&&previous.horizontal&&line.horizontal&&(standardContinuation||sentenceContinuation||pictogramLabelContinuation||contentsContinuation)&&
      advance>fs*0.7&&advance<fs*1.5&&gap>=-fs*0.15&&gap<fs*0.65&&advance<nearest&&
      paragraph.content.block["text"].get<std::string>().size()+b["text"].get<std::string>().size()+1<=1800){best=static_cast<int>(i);nearest=advance;}
  }
  if(best<0){paragraphs.push_back({line,line});continue;}
  appendRun(paragraphs[best].content,line," ");paragraphs[best].lastLine=line;
 }
 json blocks=json::array();for(auto& paragraph:paragraphs){auto& block=paragraph.content.block;block["fontSize"]=linguisticSize(block);block.erase("linguisticFontSize");
  // Dot leaders are visual alignment, not language. Keeping them in a model
  // request can produce hundreds of repeated punctuation tokens. Preserve the
  // source/ownership verbatim while translating only the linguistic label.
  static const std::regex leader(R"((?:\.[\t ]*){3,}([0-9]{1,4})[\t ]*$)");
  const auto model=block.value("modelText",block.at("text").get<std::string>());std::smatch reference;
  if(std::regex_search(model,reference,leader)){auto label=model.substr(0,reference.position());const auto last=label.find_last_not_of(" \t");if(last!=std::string::npos){label.resize(last+1);block["modelText"]=label;}}
  blocks.push_back(std::move(block));}
 return blocks;
}
}
using namespace detail;
namespace {
struct PageGeometry { double left,bottom,right,top,width,height;int rotation; };
struct Point { double x,y; };
PageGeometry geometry(FPDF_PAGE page){
 FS_RECTF box{};if(!ops::FPDF_GetPageBoundingBox(page,&box))throw std::runtime_error("Cannot read visible page bounds");
 const int rotation=((ops::FPDFPage_GetRotation(page)%4)+4)%4;
 const double rawWidth=box.right-box.left,rawHeight=box.top-box.bottom;
 if(!std::isfinite(rawWidth)||!std::isfinite(rawHeight)||rawWidth<=0||rawHeight<=0)throw std::runtime_error("Invalid visible page bounds");
 return {box.left,box.bottom,box.right,box.top,rotation%2?rawHeight:rawWidth,rotation%2?rawWidth:rawHeight,rotation};
}
Point toDisplay(const PageGeometry& g,double x,double y){
 switch(g.rotation){case 1:return {y-g.bottom,x-g.left};case 2:return {g.right-x,y-g.bottom};case 3:return {g.top-y,g.right-x};default:return {x-g.left,g.top-y};}
}
Point toPdf(const PageGeometry& g,double x,double y){
 switch(g.rotation){case 1:return {y+g.left,x+g.bottom};case 2:return {g.right-x,y+g.bottom};case 3:return {g.right-y,g.top-x};default:return {x+g.left,g.top-y};}
}
json displayBounds(const PageGeometry& g,double left,double bottom,double right,double top){
 const Point points[]={toDisplay(g,left,bottom),toDisplay(g,left,top),toDisplay(g,right,bottom),toDisplay(g,right,top)};
 double x0=points[0].x,x1=x0,y0=points[0].y,y1=y0;for(const auto& p:points){x0=std::min(x0,p.x);x1=std::max(x1,p.x);y0=std::min(y0,p.y);y1=std::max(y1,p.y);}
 return json{{"left",x0},{"top",y0},{"right",x1},{"bottom",y1}};
}
std::vector<detail::SourceRule> sourceRules(FPDF_PAGEOBJECT,const PageGeometry&);
std::vector<detail::SourceRule> sourcePageRules(FPDF_PAGE,const PageGeometry&);
std::vector<json> splitSourceTableBlock(FPDF_PAGE,FPDF_TEXTPAGE,FPDF_PAGEOBJECT,int,const PageGeometry&,const std::vector<detail::SourceRule>&,const json&);
std::string documentTitle(FPDF_DOCUMENT document){
 try{
  const unsigned long required=FPDF_GetMetaText(document,"Title",nullptr,0);
  if(required<4||required>8192||(required&1))return {};
  std::vector<unsigned char> bytes(required);
  if(FPDF_GetMetaText(document,"Title",bytes.data(),required)!=required)return {};
  std::u16string value;value.reserve(required/2);
  for(size_t i=0;i+1<bytes.size();i+=2){const char16_t unit=static_cast<char16_t>(bytes[i]|(static_cast<unsigned>(bytes[i+1])<<8));if(!unit)break;value.push_back(unit);}
  if(!value.empty()&&value.front()==0xfeff)value.erase(value.begin());
  auto text=utf8(value);while(!text.empty()&&std::isspace(static_cast<unsigned char>(text.front())))text.erase(text.begin());while(!text.empty()&&std::isspace(static_cast<unsigned char>(text.back())))text.pop_back();
  return text.size()>=3&&text.size()<=400?text:std::string{};
 }catch(...){return {};}
}
std::string pageContext(const json& blocks,double pageHeight,const std::string& title){
 struct Heading { double top;double size;std::string text; };
 std::vector<double> sizes;
 for(const auto& block:blocks){
  const double size=block.value("fontSize",0.);
  if(std::isfinite(size)&&size>0)sizes.push_back(size);
 }
 if(!std::isfinite(pageHeight)||pageHeight<=0)return {};
 std::sort(sizes.begin(),sizes.end());
 const double median=sizes.empty()?0.:sizes.size()%2?sizes[sizes.size()/2]:(sizes[sizes.size()/2-1]+sizes[sizes.size()/2])/2.;
 std::vector<Heading> headings;
 for(const auto& block:blocks){
  const std::string raw=block.value("text",std::string{});
  const double top=block.value("top",pageHeight);
  const double size=block.value("fontSize",0.);
  if(raw.empty()||!std::isfinite(top)||!std::isfinite(size)||top<0||top>pageHeight*.46||size<std::max(11.,median*1.18))continue;
  std::string text;bool pendingSpace=false;
  for(unsigned char c:raw){
   if(c==' '||c=='\t'||c=='\r'||c=='\n'){pendingSpace=!text.empty();continue;}
   if(pendingSpace){text.push_back(' ');pendingSpace=false;}text.push_back(static_cast<char>(c));
  }
  while(!text.empty()&&text.back()==' ')text.pop_back();
  if(text.size()<3||text.size()>1000)continue;
  bool hasLetter=false,onlyPageNumber=true;
  for(unsigned char c:text){
   if((c>='A'&&c<='Z')||(c>='a'&&c<='z')||c>=0x80)hasLetter=true;
   if(!((c>='0'&&c<='9')||c=='.'||c=='-'||c==' '))onlyPageNumber=false;
  }
  if(!hasLetter||onlyPageNumber)continue;
  // Running headers often include the page number at the very top. They are
  // navigation noise rather than a useful description of the page body.
  if(top<pageHeight*.07){
   const auto first=text.find(' '),last=text.find_last_of(' ');
   const auto numeric=[](const std::string& part){return !part.empty()&&std::all_of(part.begin(),part.end(),[](unsigned char c){return c>='0'&&c<='9';});};
   if((first!=std::string::npos&&numeric(text.substr(0,first)))||
      (last!=std::string::npos&&numeric(text.substr(last+1))))continue;
  }
  headings.push_back({top,size,std::move(text)});
 }
 std::stable_sort(headings.begin(),headings.end(),[](const Heading& a,const Heading& b){if(a.top!=b.top)return a.top<b.top;return a.size>b.size;});
 std::string context;
 if(title.size()>=3&&title.size()<=400){bool letters=false;for(unsigned char c:title)if((c>='A'&&c<='Z')||(c>='a'&&c<='z')||c>=0x80){letters=true;break;}if(letters)context=title;}
 for(const auto& heading:headings){
  bool duplicate=false;for(const auto& prior:headings){if(&prior==&heading)break;if(prior.text==heading.text){duplicate=true;break;}}
  if(duplicate)continue;
  const std::string separator=context.empty()?"":" | ";
  if(context.size()+separator.size()>=400)break;
  context+=separator;
  // Append complete UTF-8 code points only; the native context contract is
  // byte-bounded so malformed/truncated sequences never reach either platform.
  const size_t budget=400-context.size();
  for(size_t i=0;i<heading.text.size();){
   const unsigned char lead=static_cast<unsigned char>(heading.text[i]);
   size_t width=lead<0x80?1:(lead&0xe0)==0xc0?2:(lead&0xf0)==0xe0?3:(lead&0xf8)==0xf0?4:1;
   if(i+width>heading.text.size()||i+width>budget)break;
   context.append(heading.text,i,width);i+=width;
  }
  if(context.size()>=400)break;
 }
 while(!context.empty()&&context.back()==' ')context.pop_back();
 return context;
}
void annotate(FPDF_PAGE page,const json& block,const PageGeometry& g,const std::string& text){
 auto a=ops::FPDFPage_CreateAnnot(page,FPDF_ANNOT_TEXT);if(!a)throw std::runtime_error("Cannot attach translated text");
 const double size=std::min(16.,std::min(g.width,g.height));
 const double left=std::clamp(block.value("left",0.)-size-4.,0.,g.width-size);
 const double top=std::clamp(block.value("top",0.),0.,g.height-size);
 const Point points[]={toPdf(g,left,top),toPdf(g,left+size,top),toPdf(g,left,top+size),toPdf(g,left+size,top+size)};
 double x0=points[0].x,x1=x0,y0=points[0].y,y1=y0;for(const auto& p:points){x0=std::min(x0,p.x);x1=std::max(x1,p.x);y0=std::min(y0,p.y);y1=std::max(y1,p.y);}
 x0=std::clamp(x0,g.left,g.right-size);x1=x0+size;y0=std::clamp(y0,g.bottom,g.top-size);y1=y0+size;
 FS_RECTF r{static_cast<float>(x0),static_cast<float>(y1),static_cast<float>(x1),static_cast<float>(y0)};
 bool ok=ops::FPDFAnnot_SetRect(a,&r)&&ops::FPDFAnnot_SetFlags(a,0);
 ops::FPDFAnnot_SetColor(a,FPDFANNOT_COLORTYPE_Color,198,40,40,255);
 auto name=wide("Comment");ops::FPDFAnnot_SetStringValue(a,"Name",reinterpret_cast<const unsigned short*>(name.c_str()));
 auto s=wide(text);ok=ops::FPDFAnnot_SetStringValue(a,"Contents",reinterpret_cast<const unsigned short*>(s.c_str()))&&ok;
 auto title=wide("Translation - placement needs review");ops::FPDFAnnot_SetStringValue(a,"T",reinterpret_cast<const unsigned short*>(title.c_str()));ops::FPDFPage_CloseAnnot(a);
 if(!ok)throw std::runtime_error("Cannot save translation annotation");
}
json canonicalBlock(json block,const PageGeometry& g){
 if(block.value("source",std::string("native"))=="native"&&block.value("coordinateSpaceVersion",0)!=2){
  const double l=block.value("left",0.),r=block.value("right",0.);
  const double t=g.height-block.value("top",0.),b=g.height-block.value("bottom",0.);
  const double cl=std::max(l,g.left),cr=std::min(r,g.right),cb=std::max(b,g.bottom),ct=std::min(t,g.top);
  if(cr>cl&&ct>cb){const bool clipped=cl>l+0.01||cr<r-0.01||cb>b+0.01||ct<t-0.01;const auto converted=displayBounds(g,cl,cb,cr,ct);
   for(const char* edge:{"left","top","right","bottom"})block[edge]=converted[edge];if(clipped)block["partialCrop"]=true;}
  else block["partialCrop"]=true;
 }
 return block;
}
}
std::string inspect(const std::string& path){try{std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);return json{{"pageCount",ops::FPDF_GetPageCount(d.value)},{"encrypted",false}}.dump();}catch(const std::exception&e){return failure(e);}}
std::string readPage(const std::string& path,int index){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);Page p(d.value,index);TextPage tp(p.value);
 const auto page=geometry(p.value);const double w=ops::FPDF_GetPageWidthF(p.value),h=ops::FPDF_GetPageHeightF(p.value);std::vector<TextRun> runs;json artworkRects=json::array();const auto barriers=sourcePageRules(p.value,page);
 for(int i=0;i<ops::FPDFPage_CountObjects(p.value);i++){
  auto obj=ops::FPDFPage_GetObject(p.value,i);const int objectType=ops::FPDFPageObj_GetType(obj);
  if(objectType==FPDF_PAGEOBJ_IMAGE){float left=0,bottom=0,right=0,top=0;
   if(ops::FPDFPageObj_GetBounds(obj,&left,&bottom,&right,&top)){double l=std::max<double>(left,page.left),b=std::max<double>(bottom,page.bottom),r=std::min<double>(right,page.right),t=std::min<double>(top,page.top);bool rectangular=true;auto clip=FPDFPageObj_GetClipPath(obj);// GetClipPath on this valid page object returns its member handle;
    // PDFium CountPaths also returns -1 for that member's empty HasRef state.
    // https://pdfium.googlesource.com/pdfium/+/refs/heads/main/fpdfsdk/fpdf_transformpage.cpp
    const int paths=clip?std::max(0,FPDFClipPath_CountPaths(clip)):0;
    // PDF clip paths use page coordinates. Only one proven axis-aligned
    // rectangle per intersecting clip can yield an authoritative image region.
    for(int cp=0;rectangular&&cp<paths;++cp){const int count=FPDFClipPath_CountPathSegments(clip,cp);if(count!=4&&count!=5){rectangular=false;break;}std::vector<std::pair<double,double>> points;
     for(int si=0;si<count;++si){auto segment=FPDFClipPath_GetPathSegment(clip,cp,si);float x=0,y=0;if(!segment||FPDFPathSegment_GetType(segment)!=(si?FPDF_SEGMENT_LINETO:FPDF_SEGMENT_MOVETO)||(si<count-1&&FPDFPathSegment_GetClose(segment))||!FPDFPathSegment_GetPoint(segment,&x,&y)||!std::isfinite(x)||!std::isfinite(y)){rectangular=false;break;}points.emplace_back(x,y);}
     if(!rectangular)break;if(count==5){if(std::abs(points.front().first-points.back().first)>.001||std::abs(points.front().second-points.back().second)>.001){rectangular=false;break;}points.pop_back();}
     double x0=points[0].first,x1=x0,y0=points[0].second,y1=y0;for(const auto& point:points){x0=std::min(x0,point.first);x1=std::max(x1,point.first);y0=std::min(y0,point.second);y1=std::max(y1,point.second);}std::set<std::pair<int,int>> corners;
     for(size_t si=0;si<points.size();++si){const auto& a=points[si];const auto& other=points[(si+1)%points.size()];const bool atX0=std::abs(a.first-x0)<.001,atX1=std::abs(a.first-x1)<.001,atY0=std::abs(a.second-y0)<.001,atY1=std::abs(a.second-y1)<.001;if((!atX0&&!atX1)||(!atY0&&!atY1)||(std::abs(a.first-other.first)>.001&&std::abs(a.second-other.second)>.001)){rectangular=false;break;}corners.emplace(atX0?0:1,atY0?0:1);}
     if(corners.size()!=4||x1<=x0||y1<=y0){rectangular=false;break;}l=std::max(l,x0);r=std::min(r,x1);b=std::max(b,y0);t=std::min(t,y1);
    }
    if(rectangular&&std::isfinite(l)&&std::isfinite(b)&&std::isfinite(r)&&std::isfinite(t)&&r>l&&t>b){auto rect=displayBounds(page,l,b,r,t);rect["objectIndex"]=i;rect["type"]="image";rect["coordinateSpaceVersion"]=2;artworkRects.push_back(std::move(rect));}
   }
  }
  if(objectType!=FPDF_PAGEOBJ_TEXT)continue;
  const auto text=objectText(obj,tp.value);if(text.empty()||text.find_first_not_of(" \r\n\t")==std::string::npos)continue;
  float l=0,b=0,r=0,t=0,size=10; if(!ops::FPDFPageObj_GetBounds(obj,&l,&b,&r,&t))continue;ops::FPDFTextObj_GetFontSize(obj,&size);
  const double cl=std::max<double>(l,page.left),cb=std::max<double>(b,page.bottom),cr=std::min<double>(r,page.right),ct=std::min<double>(t,page.top);if(cr<=cl||ct<=cb)continue;
  const bool clipped=cl>l+0.01||cb>b+0.01||cr<r-0.01||ct<t-0.01;const auto visible=displayBounds(page,cl,cb,cr,ct);
  FS_MATRIX matrix{};const bool transformed=ops::FPDFPageObj_GetMatrix(obj,&matrix);
  if(transformed)size*=std::hypot(matrix.c,matrix.d);
  if(!std::isfinite(size)||size<=0)size=std::max(1.f,t-b);
  const bool horizontal=transformed&&matrix.a>0&&matrix.d>0&&std::abs(matrix.b)<0.001&&std::abs(matrix.c)<0.001;
  const auto font=ops::FPDFTextObj_GetFont(obj);std::string modelText=text;json symbols=json::array();const auto baseFontName=detail::fontBaseName(font);
  if(detail::sourceGlyphIsland(baseFontName,text)){const auto token=detail::inlineSymbolToken(i);modelText=token;symbols.push_back({{"token",token},{"objectIndex",i},{"sourceText",text},{"fontName",baseFontName},{"fontSize",size},{"bounds",{{"left",visible["left"]},{"top",visible["top"]},{"right",visible["right"]},{"bottom",visible["bottom"]}}}});}
  json block={{"id","p"+std::to_string(index)+"-o"+std::to_string(i)},{"text",text},{"sourceText",text},{"modelText",modelText},{"symbols",symbols},{"left",visible["left"]},{"top",visible["top"]},{"right",visible["right"]},{"bottom",visible["bottom"]},{"fontSize",size},{"linguisticFontSize",symbols.empty()?size:0.f},{"source","native"},{"coordinateSpaceVersion",2},{"partialCrop",clipped},{"objectIndex",i},{"objectIndices",json::array({i})}};
  const Point base=toDisplay(page,matrix.e,matrix.f);
  json sourceRect=visible;sourceRect["objectIndex"]=i;sourceRect["text"]=text;sourceRect["baseline"]=transformed?base.y:visible["bottom"].get<double>();sourceRect["pictogram"]=detail::sourceGlyphIsland(baseFontName,text);sourceRect["fontSize"]=size;
  block["sourceRects"]=json::array({sourceRect});
  const auto cells=splitSourceTableBlock(p.value,tp.value,obj,i,page,barriers,block);
  if(cells.empty())runs.push_back({std::move(block),transformed?base.y:visible["top"].get<double>(),font,horizontal&&page.rotation==0});
  else for(auto cell:cells)runs.push_back({std::move(cell),transformed?base.y:visible["top"].get<double>(),font,horizontal&&page.rotation==0});
 }
 const auto merged=groupText(std::move(runs),&barriers);
 const auto title=documentTitle(d.value);
 return json{{"index",index},{"width",w},{"height",h},{"rotation",page.rotation},{"coordinateSpaceVersion",2},{"documentTitle",title},{"context",pageContext(merged,page.height,title)},{"blocks",merged},{"artworkRects",artworkRects}}.dump();
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
void setWeightRepackingAllowed(bool allowed){weightRepackingAllowed.store(allowed);}
namespace detail {
// A context may change width without unmapping/revalidating the immutable weights.
void ensureContext(int context,int threads,int sequences,bool batch){
 if(ctx&&currentContext==context&&currentThreads==threads&&currentSequences==sequences&&currentBatch==batch)return;
 cachedPromptTokens.clear();
 if(ctx){ops::llama_free(ctx);ctx=nullptr;}
 auto cp=ops::llama_context_default_params();cp.n_ctx=context;cp.n_seq_max=sequences;
 cp.n_batch=128;cp.n_ubatch=batch?128:64;cp.n_threads=threads;cp.n_threads_batch=threads;
 cp.type_k=GGML_TYPE_F16;cp.type_v=GGML_TYPE_F16;cp.kv_unified=false;cp.offload_kqv=false;
 cp.abort_callback=abortInference;cp.abort_callback_data=nullptr;
 ctx=ops::llama_init_from_model(model,cp);
 if(!ctx)throw std::runtime_error("Insufficient resources for the model context");
 currentContext=context;currentThreads=threads;currentSequences=sequences;currentBatch=batch;
}
json loadSession(const std::string& path,int context,int threads,const std::string& profile,int sequences,bool batch){
 std::call_once(llamaInit,[]{ops::llama_log_set([](ggml_log_level,const char*,void*){},nullptr);
#if defined(__ANDROID__) && defined(TRANSLATEPDF_CPU_DISPATCH)
 Dl_info library{};
 if(dladdr(reinterpret_cast<void*>(&inferenceProgress),&library)&&library.dli_fname){
  std::string path(library.dli_fname);const auto separator=path.find_last_of('/');
  if(separator!=std::string::npos)ggml_backend_load_all_from_path(path.substr(0,separator).c_str());
 }
#endif
 ops::llama_backend_init();});stopped=false;
 if(profile!="hy-mt2"&&profile!="translategemma")throw std::runtime_error("Unknown translation profile");
 if(batch&&(profile!="hy-mt2"||(sequences!=1&&sequences!=4&&sequences!=8)||(context!=2048&&context!=4096)||(sequences==8&&context!=4096)))throw std::runtime_error("Unsupported Hy-MT2 batch configuration");
 const int requestedContext=std::clamp(context,1024,4096),requestedThreads=std::clamp(threads,1,4);
 const bool repacking=weightRepackingAllowed.load();
 const bool newWeights=!model||loadedPath!=path||loadedProfile!=profile||loadedWeightRepackingAllowed!=repacking;
 if(newWeights){
  unload();auto mp=ops::llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;mp.check_tensors=true;mp.use_extra_bufts=repacking;
  model=ops::llama_model_load_from_file(path.c_str(),mp);if(!model)throw std::runtime_error("Model cannot be loaded by this runtime");
  if(ops::llama_model_has_encoder(model)){unload();throw std::runtime_error("Model architecture does not match its pinned translation profile");}
  loadedPath=path;loadedProfile=profile;loadedWeightRepackingAllowed=repacking;
 }
 try{ensureContext(requestedContext,requestedThreads,sequences,batch);}catch(...){if(newWeights)unload();throw;}
 if(batch&&ops::llama_n_ctx_seq(ctx)<512)throw std::runtime_error("Batch context provides fewer than512 tokens per sequence");
 sessionContext=requestedContext;sessionThreads=requestedThreads;sessionSequences=sequences;sessionBatch=batch;
 return json{{"loaded",true},{"context",ops::llama_n_ctx(ctx)},{"threads",ops::llama_n_threads(ctx)},{"weightRepackingAllowed",repacking},{"modelBytes",ops::llama_model_size(model)},{"templateAvailable",ops::llama_model_chat_template(model,nullptr)!=nullptr},{"maxSequences",sequences}};
}
struct TranslationContentError:std::runtime_error { using std::runtime_error::runtime_error; };
bool extractInlineSymbolTokens(const std::string& text,std::vector<std::string>& tokens){
 tokens.clear();for(size_t at=0;(at=text.find("[[FV",at))!=std::string::npos;){const auto end=text.find("]]",at+4);if(end==std::string::npos)return false;const auto token=text.substr(at,end+2-at);constexpr const char* prefix="[[FVSYM";
  if(token.rfind(prefix,0)!=0||token.size()<=std::char_traits<char>::length(prefix)+2)return false;
  for(size_t i=std::char_traits<char>::length(prefix);i<token.size()-2;++i)if(token[i]<'A'||token[i]>'Z')return false;
  tokens.push_back(token);at=end+2;
 }return true;
}
bool preservesInlineSymbolTokens(const std::string& source,const std::string& translated){
 std::vector<std::string> expected,actual;if(!extractInlineSymbolTokens(source,expected)||!extractInlineSymbolTokens(translated,actual))return false;
 auto sortedExpected=expected,sortedActual=actual;std::sort(sortedExpected.begin(),sortedExpected.end());std::sort(sortedActual.begin(),sortedActual.end());
 return std::adjacent_find(sortedExpected.begin(),sortedExpected.end())==sortedExpected.end()&&sortedExpected==sortedActual;
}
bool onlyInlineSymbolTokens(const std::string& text){
 std::vector<std::string> tokens;if(!extractInlineSymbolTokens(text,tokens)||tokens.empty())return false;std::string remainder=text;
 for(const auto& token:tokens){const auto at=remainder.find(token);if(at==std::string::npos)return false;remainder.erase(at,token.size());}
 return remainder.find_first_not_of(" \t\r\n")==std::string::npos;
}
struct TranslationInput { std::vector<llama_token> tokens;llama_token assistantEnd=LLAMA_TOKEN_NULL;int sourceTokens=0; };
TranslationInput prepareInput(const std::string& text,const std::string& glossary,const std::string& context,const std::string& sourceLanguage,const std::string& targetLanguage,int capacity,bool layoutRetry=false,const std::string& layoutOptions=""){
 const auto sourceName=languageName(sourceLanguage),targetName=languageName(targetLanguage);
 if(sourceLanguage==targetLanguage)throw std::runtime_error("Source and target languages must differ");
 if(loadedProfile!="hy-mt2"&&!glossary.empty())throw std::runtime_error("This model uses direct translation without a glossary. Choose a glossary-capable model to apply saved terms.");
 std::string layoutInstruction;
 if(layoutRetry){
  if(layoutOptions.size()>256)throw std::runtime_error("Layout options exceed the 256-byte bound");
  const auto options=layoutOptions.empty()?json::object():json::parse(layoutOptions);
  if(!options.is_object())throw std::runtime_error("Layout options must be an object");
  for(const auto& entry:options.items())if(entry.key()!="attempt"&&entry.key()!="maxCharacters")throw std::runtime_error("Unknown layout option");
  const auto integerOption=[&](const char* key,int fallback,int lower,int upper){
   if(!options.contains(key))return fallback;
   const auto& value=options.at(key);if(!value.is_number_integer()||value.get<int64_t>()<lower||value.get<int64_t>()>upper)throw std::runtime_error("Invalid layout option: "+std::string(key));return value.get<int>();
  };
  const int attempt=integerOption("attempt",1,1,2),characters=integerOption("maxCharacters",0,0,10000);
  layoutInstruction=attempt==1?"Use a concise translation that preserves all meaning; this text must fit the original PDF container.\n":"Use the shortest natural translation that preserves every action, condition, negation, number, unit, and name. This is a second attempt to fit the original PDF container; use more economical wording.\n";
  if(characters>0)layoutInstruction+="The measured source container suggests a soft budget of "+std::to_string(characters)+" target characters, including spaces. Aim within this budget. Never omit meaning or truncate text to meet it.\n";
 }
 std::string instruction=layoutInstruction;
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
  if(context.size()>400)throw std::runtime_error("Document title exceeds the 400-byte context bound");
  std::string contextInstruction;
  if(!context.empty()){
   auto metadata=json{{"documentTitle",context}}.dump();
   // Metadata is untrusted data. Escape the opening character of reserved
   // chat tokens rather than allowing it to create another conversation turn.
   for(size_t at=0;(at=metadata.find('<',at))!=std::string::npos;at+=6)metadata.replace(at,1,"\\u003c");
   contextInstruction="Reference-only title: "+metadata+"\nTranslate only the source segment; preserve product/model names.\n";
  }
  const auto sourceSegment=context.empty()?text:"<source_segment>\n"+text+"\n</source_segment>";
  contextInstruction+=layoutInstruction;
  prompt="<bos><start_of_turn>user\nYou are a professional "+sourceName+" ("+gemmaSource+") to "+targetName+" ("+gemmaTarget+") translator. Your goal is to accurately convey the meaning and nuances of the original "+sourceName+" text while adhering to "+targetName+" grammar, vocabulary, and cultural sensitivities.\nProduce only the "+targetName+" translation, without any additional explanations or commentary. "+contextInstruction+"Please translate the following "+sourceName+" text into "+targetName+":\n\n\n"+sourceSegment+"<end_of_turn>\n<start_of_turn>model\n";
 }else {
  if(!tmpl)throw std::runtime_error("GGUF lacks a verified chat template");
  // The pinned 1.8B GGUF uses the Dense template. llama.cpp's built-in
  // template detector selects Hunyuan-VL first because both share the BOS and
  // Assistant markers; it serializes BOS + content + User and loses the
  // assistant generation prefix. Match the verified template exactly and
  // render its single-user, no-system branch directly. Maximum's older
  // startoftext/extra_0 format continues through its matching built-in path.
  static const std::string hyDenseTemplate=R"HYDENSE({% if messages[0]['role'] == 'system' %}{% set loop_messages = messages[1:] %}{% set system_message = messages[0]['content'] %}<｜hy_begin▁of▁sentence｜>{{ system_message }}<｜hy_place▁holder▁no▁3｜>{% else %}{% set loop_messages = messages %}<｜hy_begin▁of▁sentence｜>{% endif %}{% for message in loop_messages %}{% if message['role'] == 'user' %}<｜hy_User｜>{{ message['content'] }}{% elif message['role'] == 'assistant' %}<｜hy_Assistant｜>{{ message['content'] }}<｜hy_place▁holder▁no▁2｜>{% endif %}{% endfor %}{% if add_generation_prompt %}<｜hy_Assistant｜>{% else %}<｜hy_place▁holder▁no▁8｜>{% endif %})HYDENSE";
  if(tmpl==hyDenseTemplate){
   prompt="<｜hy_begin▁of▁sentence｜><｜hy_User｜>"+instruction+"<｜hy_Assistant｜>";
  }else{
   llama_chat_message message{"user",instruction.c_str()};std::vector<char> formatted(instruction.size()*3+8192);
   int n=ops::llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),formatted.size());if(n<0)throw std::runtime_error("Unsupported GGUF chat template");if(n>=static_cast<int>(formatted.size())){formatted.resize(n+1);n=ops::llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),formatted.size());if(n<0||n>=static_cast<int>(formatted.size()))throw std::runtime_error("Cannot format translation prompt");}
   prompt.assign(formatted.data(),n);
  }
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
 if(count<=0||count>static_cast<int>(capacity)-384)throw TranslationContentError("Block exceeds model context; split it without truncation");
 std::vector<llama_token> tokens(count);ops::llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),count,addSpecial,true);
 int sourceTokens=-ops::llama_tokenize(vocab,text.data(),static_cast<int>(text.size()),nullptr,0,false,false);
 return {std::move(tokens),assistantEnd,sourceTokens};
}
llama_sampler* makeSampler(const llama_vocab* vocab){
 auto sampler=ops::llama_sampler_chain_init(ops::llama_sampler_chain_default_params());
 if(loadedProfile=="hy-mt2"){
  ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_penalties(ops::llama_vocab_n_tokens(vocab),64,1.05f,0.0f,0.0f));
  ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_top_k(20));
  ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_top_p(0.6f,1));
  ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_temp(0.7f));
  ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_dist(42));
 }else ops::llama_sampler_chain_add(sampler,ops::llama_sampler_init_greedy());
 return sampler;
}
std::string translateLocked(const std::string& text,const std::string& glossary,const std::string& context,const std::string& sourceLanguage,const std::string& targetLanguage,bool layoutRetry=false,const std::string& layoutOptions=""){
 if(!model)throw std::runtime_error("Model is not loaded");if(stopped)throw std::runtime_error("Canceled");
 // Validate languages even for the existing empty-input fast path.
 languageName(sourceLanguage);languageName(targetLanguage);
 if(sourceLanguage==targetLanguage)throw std::runtime_error("Source and target languages must differ");
 if(text.empty())return json{{"text",text},{"tokens",0},{"seconds",0},{"skipped",true}}.dump();
 if(onlyInlineSymbolTokens(text)&&loadedProfile!="hy-mt2"&&!glossary.empty())throw std::runtime_error("This model uses direct translation without a glossary. Choose a glossary-capable model to apply saved terms.");
 if(onlyInlineSymbolTokens(text))return json{{"text",text},{"tokens",0},{"inputTokens",0},{"seconds",0},{"verbatim",true}}.dump();
 if(languageInvariantAscii(text)&&glossary.empty())return json{{"text",text},{"tokens",0},{"inputTokens",0},{"seconds",0},{"verbatim",true}}.dump();
 ensureContext(sessionBatch?2048:sessionContext,sessionThreads,1,false);
 auto input=prepareInput(text,glossary,context,sourceLanguage,targetLanguage,ops::llama_n_ctx(ctx),layoutRetry,layoutOptions);
 const auto vocab=ops::llama_model_get_vocab(model);auto& tokens=input.tokens;
 const int count=tokens.size(),sourceTokens=input.sourceTokens;
 const auto assistantEnd=input.assistantEnd;
 const int baseInput=processedInputTokens.load(),baseGenerated=generatedTokens.load();
 const auto memory=ops::llama_get_memory(ctx);
 size_t reused=0;
 if(memory&&loadedProfile=="hy-mt2"){
  // Keep only an identical token prefix; remove all old source/output suffixes.
  // Always decode the final prompt token again to obtain fresh next-token logits.
  const size_t limit=std::min(cachedPromptTokens.size(),tokens.size()-1);
  while(reused<limit&&cachedPromptTokens[reused]==tokens[reused])++reused;
  if(reused&&!ops::llama_memory_seq_rm(memory,0,static_cast<llama_pos>(reused),-1))reused=0;
 }
 if(!reused)ops::llama_memory_clear(memory,true);
 cachedPromptTokens.clear();
 auto start=std::chrono::steady_clock::now();
 for(int pos=static_cast<int>(reused);pos<count;pos+=128){if(stopped)throw std::runtime_error("Canceled");auto batch=ops::llama_batch_get_one(tokens.data()+pos,std::min(128,count-pos));if(ops::llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Inference failed while reading input");processedInputTokens=baseInput+pos+std::min(128,count-pos)-static_cast<int>(reused);}
 if(memory&&loadedProfile=="hy-mt2")cachedPromptTokens=tokens;
 auto sampler=makeSampler(vocab);
 std::string output;int generated=0;bool ended=false;
 int limit=std::min({static_cast<int>(ops::llama_n_ctx(ctx))-count-4,std::max(96,sourceTokens*4+32),1024});
 try{for(int i=0;i<limit;i++){
  if(stopped)throw std::runtime_error("Canceled");auto token=ops::llama_sampler_sample(sampler,ctx,-1);if(assistantEnd==LLAMA_TOKEN_NULL?ops::llama_vocab_is_eog(vocab,token):token==assistantEnd){ended=true;break;}
  char buf[512];int len=ops::llama_token_to_piece(vocab,token,buf,sizeof(buf),0,false);if(len<0)throw std::runtime_error("Cannot decode model token");output.append(buf,len);generated++;generatedTokens=baseGenerated+generated;
  auto batch=ops::llama_batch_get_one(&token,1);if(ops::llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Inference failed while generating output");
 }}catch(...){ops::llama_sampler_free(sampler);throw;}ops::llama_sampler_free(sampler);
 if(!ended)throw TranslationContentError("Translation exceeded context; output was not committed");
 if(output.empty())throw TranslationContentError("Model produced no translation");
 auto decoded=decodeSourceBoundaryOutput(text,output,loadedProfile=="translategemma"&&!context.empty());
 if(!decoded)throw TranslationContentError("Translation returned invalid source boundary markers; original retained for review");
 const bool boundaryDecoded=*decoded!=output;output=std::move(*decoded);
 if(!preservesInlineSymbolTokens(text,output))throw TranslationContentError("Translation changed an inline symbol token; original retained for review");
 if(!preservesCriticalTokens(text,output,sourceLanguage,targetLanguage))throw TranslationContentError("Translation changed a protected number, code or negation; original retained for review");
 double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
 return json{{"text",output},{"boundaryDecoded",boundaryDecoded},{"tokens",generated},{"inputTokens",count},{"cachedInputTokens",reused},{"seconds",seconds},{"tokensPerSecond",generated/std::max(seconds,0.001)}}.dump();
}
}
std::string loadModel(const std::string& path,int context,int threads,const std::string& profile){try{
 std::lock_guard<std::mutex> lock(modelMutex);return loadSession(path,context,threads,profile,1,false).dump();
}catch(const std::exception&e){return failure(e);}}
std::string loadModelBatch(const std::string& path,int context,int threads,const std::string& profile,int maxSequences){try{
 std::lock_guard<std::mutex> lock(modelMutex);return loadSession(path,context,threads,profile,maxSequences,true).dump();
}catch(const std::exception&e){return failure(e);}}
std::string translate(const std::string& text,const std::string& glossary,const std::string& context,const std::string& sourceLanguage,const std::string& targetLanguage){try{
 std::lock_guard<std::mutex> lock(modelMutex);processedInputTokens=0;generatedTokens=0;
 return translateLocked(text,glossary,context,sourceLanguage,targetLanguage);
}catch(const std::exception&e){return failure(e);}}
std::string translateForLayout(const std::string& text,const std::string& glossary,const std::string& context,const std::string& sourceLanguage,const std::string& targetLanguage,const std::string& layoutOptions){try{
 std::lock_guard<std::mutex> lock(modelMutex);processedInputTokens=0;generatedTokens=0;
 return translateLocked(text,glossary,context,sourceLanguage,targetLanguage,true,layoutOptions);
}catch(const std::exception&e){return failure(e);}}
namespace detail {
struct BatchRequest {
 std::string id,text,output;
 TranslationInput input;
 int limit=0,generated=0;
 bool active=false,retry=false,committed=false;
 std::string finalError;
 std::unique_ptr<llama_sampler,decltype(ops::llama_sampler_free)> sampler{nullptr,ops::llama_sampler_free};
 std::chrono::steady_clock::time_point start;
};
struct BatchEntry { llama_token token;llama_pos pos;llama_seq_id seq;bool logits; };
void decodeEntries(const std::vector<BatchEntry>& entries,bool* pendingCopies=nullptr){
 if(stopped)throw std::runtime_error("Canceled");
 std::vector<llama_token> tokens;std::vector<llama_pos> positions;
 std::vector<int32_t> counts(entries.size(),1);std::vector<llama_seq_id> sequences;
 std::vector<llama_seq_id*> sequencePointers;std::vector<int8_t> logits;
 for(const auto& entry:entries){tokens.push_back(entry.token);positions.push_back(entry.pos);sequences.push_back(entry.seq);logits.push_back(entry.logits);}
 for(auto& sequence:sequences)sequencePointers.push_back(&sequence);
 llama_batch batch{static_cast<int32_t>(entries.size()),tokens.data(),nullptr,positions.data(),counts.data(),sequencePointers.data(),logits.data()};
 if(ops::llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Batch inference failed; pending blocks retained");
 if(pendingCopies)*pendingCopies=false;
 processedInputTokens+=std::count_if(entries.begin(),entries.end(),[](const BatchEntry& e){return !e.logits;});
}
struct BatchCleanup {
 bool& pendingCopies;
 ~BatchCleanup(){
  cachedPromptTokens.clear();
  if(!ctx)return;
  if(pendingCopies){
   ops::llama_free(ctx);ctx=nullptr;
   currentContext=currentThreads=currentSequences=0;currentBatch=false;
  }else ops::llama_memory_clear(ops::llama_get_memory(ctx),false);
 }
};
}
std::string translateBatch(const std::string& requestsJson,const std::string& glossary,const std::string& background,const std::string& sourceLanguage,const std::string& targetLanguage,int64_t generation,const BatchCompletion& onCompleted){
 std::lock_guard<std::mutex> lock(modelMutex);
 std::vector<BatchRequest> requests;std::string globalError;bool sinkStopped=false;
 try{
  if(!model||!sessionBatch)throw std::runtime_error("Hy-MT2 batch session is not loaded");
  if(!onCompleted)throw std::runtime_error("Batch completion sink is required");
  auto input=json::parse(requestsJson);
  if(!input.is_array()||input.empty()||input.size()>static_cast<size_t>(sessionSequences))throw std::runtime_error("Batch requires one to maxSequences requests");
  std::set<std::string> ids;
  for(const auto& value:input){
   if(!value.is_object()||!value.contains("id")||!value["id"].is_string()||!value.contains("text")||!value["text"].is_string())throw std::runtime_error("Invalid batch request");
   const auto id=value["id"].get<std::string>();
   if(id.empty()||!ids.insert(id).second)throw std::runtime_error("Batch request IDs must be nonempty and unique");
  }
  for(const auto& value:input){BatchRequest r;r.id=value["id"].get<std::string>();r.text=value["text"].get<std::string>();requests.push_back(std::move(r));}
 }catch(const std::exception& e){return failure(e);}
 processedInputTokens=0;generatedTokens=0;
 bool pendingKvCopies=false;
 BatchCleanup cleanup{pendingKvCopies};
 const auto deliver=[&](BatchRequest& request,json response,bool solo){
  // The worker sink distinguishes Pause (commit+stop) from Cancel/stale
  // ownership (reject). Preserve an EOS already computed before the stop.
  response["id"]=request.id;response["generation"]=generation;response["soloRetry"]=solo;
  const int ack=onCompleted(response.dump());
  if(ack==1||ack==2)request.committed=true;
  if(ack!=1)sinkStopped=true;
 };
 try{
  if(stopped)throw std::runtime_error("Canceled");
  ensureContext(sessionContext,sessionThreads,sessionSequences,true);
  const int capacity=ops::llama_n_ctx_seq(ctx);
  if(capacity<512)throw std::runtime_error("Batch context provides fewer than512 tokens per sequence");
  cachedPromptTokens.clear();ops::llama_memory_clear(ops::llama_get_memory(ctx),false);
  const auto vocab=ops::llama_model_get_vocab(model);
  for(auto& request:requests){
   if(stopped)throw std::runtime_error("Canceled");
   request.start=std::chrono::steady_clock::now();
   try{
    if(request.text.empty())throw TranslationContentError("Batch request contains no source text");
    if(onlyInlineSymbolTokens(request.text)||(languageInvariantAscii(request.text)&&glossary.empty())){
     if(onlyInlineSymbolTokens(request.text)&&loadedProfile!="hy-mt2"&&!glossary.empty())throw std::runtime_error("This model uses direct translation without a glossary. Choose a glossary-capable model to apply saved terms.");
     languageName(sourceLanguage);languageName(targetLanguage);
     if(sourceLanguage==targetLanguage)throw std::runtime_error("Source and target languages must differ");
     request.retry=true;continue;
    }
    request.input=prepareInput(request.text,glossary,background,sourceLanguage,targetLanguage,2048);
    const int count=request.input.tokens.size();
    const int soloLimit=std::min({2048-count-4,std::max(96,request.input.sourceTokens*4+32),1024});
    request.limit=std::min(soloLimit,capacity-count-4);
    request.retry=count+100>capacity;
    request.active=!request.retry;
    if(request.active)request.sampler.reset(makeSampler(vocab));
   }catch(const TranslationContentError& e){request.finalError=e.what();}
  }
  std::vector<llama_seq_id> activeSequences;
  std::vector<size_t> prefixLengths;
  size_t commonPrefix=0;
  for(size_t seq=0;seq<requests.size();++seq)if(requests[seq].active){
   activeSequences.push_back(static_cast<llama_seq_id>(seq));
   const size_t length=requests[seq].input.tokens.size()-1;
   prefixLengths.push_back(length);
   commonPrefix=activeSequences.size()==1?length:std::min(commonPrefix,length);
  }
  for(size_t i=1;i<activeSequences.size()&&commonPrefix>0;++i){
   const auto& candidate=requests[activeSequences[i]].input.tokens;
   const auto& first=requests[activeSequences.front()].input.tokens;
   size_t at=0;
   while(at<commonPrefix&&candidate[at]==first[at])++at;
   commonPrefix=at;
  }
  const bool sharePrefix=ops::shouldReuseExactPrefix(prefixLengths,commonPrefix,128);
  std::vector<BatchEntry> prefixes;
  if(sharePrefix){
   const llama_seq_id donor=activeSequences.front();
   const auto& tokens=requests[donor].input.tokens;
   for(size_t pos=0;pos<commonPrefix;pos+=128){
    const auto end=std::min(pos+128,commonPrefix);
    std::vector<BatchEntry> shared;
    shared.reserve(end-pos);
    for(size_t at=pos;at<end;++at)shared.push_back({tokens[at],static_cast<llama_pos>(at),donor,false});
    decodeEntries(shared);
   }
   if(stopped)throw std::runtime_error("Canceled");
   const auto memory=ops::llama_get_memory(ctx);
   for(size_t i=1;i<activeSequences.size();++i){
    if(stopped)throw std::runtime_error("Canceled");
    pendingKvCopies=true;
    ops::llama_memory_seq_cp(memory,donor,activeSequences[i],0,-1);
   }
   for(const auto seq:activeSequences){
    const auto& prompt=requests[seq].input.tokens;
    for(size_t pos=commonPrefix;pos+1<prompt.size();++pos)
     prefixes.push_back({prompt[pos],static_cast<llama_pos>(pos),seq,false});
   }
  }else{
   for(const auto seq:activeSequences){
    const auto& tokens=requests[seq].input.tokens;
    for(size_t pos=0;pos+1<tokens.size();++pos)
     prefixes.push_back({tokens[pos],static_cast<llama_pos>(pos),seq,false});
   }
  }
  for(size_t p=0;p<prefixes.size();p+=128){
   const auto end=std::min(p+128,prefixes.size());
   decodeEntries(std::vector<BatchEntry>(prefixes.begin()+p,prefixes.begin()+end),&pendingKvCopies);
  }
  std::vector<BatchEntry> batch;
  for(size_t seq=0;seq<requests.size();++seq)if(requests[seq].active){const auto& tokens=requests[seq].input.tokens;batch.push_back({tokens.back(),static_cast<llama_pos>(tokens.size()-1),static_cast<llama_seq_id>(seq),true});}
  bool firstOutput=true;
  while(!batch.empty()&&!sinkStopped){
   decodeEntries(batch,&pendingKvCopies);
   if(firstOutput){processedInputTokens+=batch.size();firstOutput=false;}
   // Sample every lane before retiring any KV sequence: logits still belong
   // to this exact decode even when a peer finishes first.
   std::vector<llama_token> sampled;
   for(size_t i=0;i<batch.size();++i){if(stopped)break;sampled.push_back(ops::llama_sampler_sample(requests[batch[i].seq].sampler.get(),ctx,static_cast<int32_t>(i)));}
   std::vector<BatchEntry> next;
   for(size_t i=0;i<sampled.size();++i){
    auto& entry=batch[i];auto& request=requests[entry.seq];const auto token=sampled[i];
    const auto end=request.input.assistantEnd;
    if(end==LLAMA_TOKEN_NULL?ops::llama_vocab_is_eog(vocab,token):token==end){
     request.active=false;
     if(request.output.empty()||!decodeSourceBoundaryOutput(request.text,request.output,false)||!preservesInlineSymbolTokens(request.text,request.output)||!preservesCriticalTokens(request.text,request.output,sourceLanguage,targetLanguage))request.retry=true;
     else{
      const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-request.start).count();
      deliver(request,json{{"text",request.output},{"tokens",request.generated},{"inputTokens",request.input.tokens.size()},{"seconds",seconds}},false);
     }
    }else{
     if(stopped)continue;
     char buffer[512];const int len=ops::llama_token_to_piece(vocab,token,buffer,sizeof(buffer),0,false);
     if(len<0)throw std::runtime_error("Cannot decode model token");
     request.output.append(buffer,len);++request.generated;++generatedTokens;
     if(request.generated>=request.limit){request.active=false;request.retry=true;request.output.clear();}
     else next.push_back({token,entry.pos+1,entry.seq,true});
    }
    if(!request.active){request.sampler.reset();if(!ops::llama_memory_seq_rm(ops::llama_get_memory(ctx),entry.seq,0,-1))throw std::runtime_error("Cannot retire batch KV sequence; pending blocks retained");}
    if(sinkStopped)break;
   }
   if(stopped)throw std::runtime_error("Canceled");
   batch=std::move(next);
  }
  // A tentative batch answer never becomes a checkpoint. Retry the original
  // complete request once with the unchanged solo prompt/sampler/context.
  for(auto& request:requests){
   if(sinkStopped)break;
   if(stopped)throw std::runtime_error("Canceled");
   if(!request.retry)continue;
   request.sampler.reset();request.output.clear();
   std::string response;
   try{response=translateLocked(request.text,glossary,background,sourceLanguage,targetLanguage);}
   catch(const TranslationContentError& e){request.finalError=e.what();continue;}
   auto result=json::parse(response);
   deliver(request,result,!result.value("verbatim",false));
  }
 }catch(const std::exception& e){globalError=e.what();}
 catch(...){globalError="Batch completion sink failed; pending blocks retained";}
 json committed=json::array(),pending=json::array(),errors=json::array();
 for(const auto& request:requests){
  if(request.committed)committed.push_back(request.id);
  else if(!request.finalError.empty())errors.push_back(json{{"id",request.id},{"error",request.finalError}});
  else pending.push_back(request.id);
 }
 json summary={{"committedIds",committed},{"pendingIds",pending},{"finalErrors",errors},{"canceled",stopped.load()||sinkStopped}};
 if(!globalError.empty())summary["error"]=globalError;
 return summary.dump();
}
void releaseModel(){std::lock_guard<std::mutex> lock(modelMutex);unload();}
void cancel(){stopped=true;++exportCancellationGeneration;}
std::string inferenceProgress(){return json{{"processedTokens",processedInputTokens.load()+generatedTokens.load()},{"generatedTokens",generatedTokens.load()}}.dump();}
namespace {
// Explicit v2 layout. Source checkpoints stay immutable; all geometry below is
// output geometry, and every flowed text fragment records its output page.
struct ReadableFont {
 FPDF_FONT value=nullptr;
 std::vector<unsigned char> bytes;
 explicit ReadableFont(FPDF_DOCUMENT document,const std::string& path){
  std::ifstream stream(path,std::ios::binary);bytes.assign(std::istreambuf_iterator<char>(stream),{});
  if(bytes.empty())throw std::runtime_error("Readable PDF font is missing");
  value=ops::FPDFText_LoadFont(document,bytes.data(),bytes.size(),FPDF_FONT_TRUETYPE,true);
  if(!value)throw std::runtime_error("Cannot embed readable PDF font");
 }
 ~ReadableFont(){if(value)ops::FPDFFont_Close(value);}
};
struct GraphicRegion {double left,top,right,bottom;};
struct ReadableSymbol { std::string blockId;GraphicRegion bounds;int width,height;std::vector<unsigned char> pixels; };
struct InlineSymbolAsset {std::string blockId,token,sourceText,fontName;int objectIndex,width,height;GraphicRegion bounds;float widthEm,heightEm;std::vector<unsigned char> pixels;};
std::string privateUseUtf8(uint32_t codepoint){std::u16string value;value.push_back(static_cast<char16_t>(codepoint));return detail::utf8(value);}
FPDF_BITMAP renderInlineSymbol(FPDF_DOCUMENT document,FPDF_PAGE page,FPDF_PAGEOBJECT object,int& width,int& height,std::vector<unsigned char>& pixels,size_t& totalBytes){
 const float scale=3.f;FPDF_BITMAP bitmap=ops::FPDFTextObj_GetRenderedBitmap(document,page,object,scale);if(!bitmap)throw std::runtime_error("Cannot rasterize original inline symbol");
 try{
  width=ops::FPDFBitmap_GetWidth(bitmap);height=ops::FPDFBitmap_GetHeight(bitmap);const int stride=ops::FPDFBitmap_GetStride(bitmap);
  if(ops::FPDFBitmap_GetFormat(bitmap)!=FPDFBitmap_BGRA||width<=0||height<=0||width>2048||height>2048||stride<width*4)throw std::runtime_error("Inline symbol bitmap has unsupported dimensions or format");
  const size_t bytes=static_cast<size_t>(width)*static_cast<size_t>(height)*4;if(bytes>64*1024*1024-totalBytes)throw std::runtime_error("Inline symbol assets exceed the readable export memory limit");
  const auto* buffer=static_cast<const unsigned char*>(ops::FPDFBitmap_GetBuffer(bitmap));if(!buffer)throw std::runtime_error("Inline symbol bitmap has no pixels");pixels.resize(bytes);bool visible=false,contrastsWithWhite=false;
  for(int y=0;y<height;++y){const auto* row=buffer+static_cast<size_t>(y)*static_cast<size_t>(stride);auto* target=pixels.data()+static_cast<size_t>(y)*static_cast<size_t>(width)*4;std::memcpy(target,row,static_cast<size_t>(width)*4);for(int x=0;x<width;++x){const auto* pixel=target+x*4;if(pixel[3]<=8)continue;visible=true;const double luminance=.2126*pixel[2]+.7152*pixel[1]+.0722*pixel[0];if(luminance<245.)contrastsWithWhite=true;}}
  if(!visible)throw std::runtime_error("Inline symbol bitmap is blank");
  if(!contrastsWithWhite)throw std::runtime_error("Inline symbol has insufficient contrast on the white output page; saved checkpoint retained");
  totalBytes+=bytes;return bitmap;
 }catch(...){ops::FPDFBitmap_Destroy(bitmap);throw;}
}
bool overlaps(const GraphicRegion& a,const GraphicRegion& b,double padding=0){
 return a.left<=b.right+padding&&b.left<=a.right+padding&&a.top<=b.bottom+padding&&b.top<=a.bottom+padding;
}
bool hideReadableSourceText(FPDF_PAGEOBJECT object,int depth){
 if(depth>32)throw std::runtime_error("Nested PDF graphics exceed the readable layout limit");
 const int type=ops::FPDFPageObj_GetType(object);
 if(type==FPDF_PAGEOBJ_TEXT){if(depth>0)throw std::runtime_error("This page's embedded text structure is not supported for readable export");if(!ops::FPDFPageObj_SetIsActive(object,false))throw std::runtime_error("Cannot separate source text from PDF graphics");return false;}
 if(type!=FPDF_PAGEOBJ_FORM)return type==FPDF_PAGEOBJ_IMAGE||type==FPDF_PAGEOBJ_PATH||type==FPDF_PAGEOBJ_SHADING;
 bool graphics=false;const int count=ops::FPDFFormObj_CountObjects(object);
 if(count<0)throw std::runtime_error("Cannot inspect nested PDF graphics");
 for(int i=0;i<count;++i){auto child=ops::FPDFFormObj_GetObject(object,i);if(!child)throw std::runtime_error("Cannot inspect nested PDF object");graphics=hideReadableSourceText(child,depth+1)||graphics;}
 return graphics;
}
void verifyReadableTextOwnership(FPDF_PAGE page,const PageGeometry& geometry,const std::vector<json>& blocks,std::set<int>* pictogramObjects=nullptr){
 TextPage textPage(page);std::set<int> expected,owned;const int objectCount=ops::FPDFPage_CountObjects(page);
 for(int index=0;index<objectCount;++index){auto object=ops::FPDFPage_GetObject(page,index);if(!object)throw std::runtime_error("Cannot inspect source text ownership");
  if(ops::FPDFPageObj_GetType(object)!=FPDF_PAGEOBJ_TEXT)continue;
  const auto text=objectText(object,textPage.value);if(text.find_first_not_of(" \t\r\n")==std::string::npos)continue;
  float left=0,bottom=0,right=0,top=0;if(!ops::FPDFPageObj_GetBounds(object,&left,&bottom,&right,&top))throw std::runtime_error("Cannot verify source text bounds");
  if(std::min<double>(right,geometry.right)>std::max<double>(left,geometry.left)&&std::min<double>(top,geometry.top)>std::max<double>(bottom,geometry.bottom)){
   expected.insert(index);
   if(pictogramObjects&&detail::sourceGlyphIsland(detail::fontBaseName(ops::FPDFTextObj_GetFont(object)),text))pictogramObjects->insert(index);
  }
 }
 std::set<int> splitObjects,expectedCharacters,claimedCharacters;
 for(const auto& block:blocks){const auto spans=block.value("sourceSpans",json::array());if(!spans.is_array())throw std::runtime_error("Invalid source character-span metadata");for(const auto& span:spans){const int oi=checkedSourceIndex(span.at("objectIndex"),"Invalid source character-span object index");if(!expected.count(oi)||(pictogramObjects&&pictogramObjects->count(oi)))throw std::runtime_error("Source character span has invalid object ownership");const auto indexes=block.value("objectIndices",json::array());if(std::find(indexes.begin(),indexes.end(),oi)==indexes.end())throw std::runtime_error("Source character span is not owned by its block");splitObjects.insert(oi);}}
 const int characterCount=FPDFText_CountChars(textPage.value);if(characterCount<0)throw std::runtime_error("Cannot inspect source character ownership");
 for(int index=0;index<characterCount;++index){auto object=FPDFText_GetTextObject(textPage.value,index);if(!object)continue;for(int oi:splitObjects)if(object==ops::FPDFPage_GetObject(page,oi)&&!sourceWhitespace(FPDFText_GetUnicode(textPage.value,index)))expectedCharacters.insert(index);}
 const auto compact=[](const std::string& text){std::string result;for(unsigned char c:text)if(c!=' '&&c!='\t'&&c!='\r'&&c!='\n')result.push_back(static_cast<char>(c));return result;};
 for(const auto& block:blocks){
  if(block.value("source",std::string("native"))!="native"){
   for(const char* edge:{"left","top","right","bottom"})if(!block.contains(edge)||!block[edge].is_number()||!std::isfinite(block[edge].get<double>()))throw std::runtime_error("The recognized source text has invalid geometry");
   if(block.at("right").get<double>()<=block.at("left").get<double>()||block.at("bottom").get<double>()<=block.at("top").get<double>()||block.at("right").get<double>()<=0||block.at("bottom").get<double>()<=0||block.at("left").get<double>()>=geometry.width||block.at("top").get<double>()>=geometry.height)throw std::runtime_error("The recognized source text is outside this page");
   continue;
  }
  json indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex"))indexes.push_back(block.at("objectIndex"));
  if(!indexes.is_array()||indexes.empty())throw std::runtime_error("A translated block has no source text ownership");
  std::string originalText;
  for(const auto& value:indexes){if(!value.is_number_integer())throw std::runtime_error("A source text object index is invalid");const auto index=value.get<long long>();
   if(index<0||index>=objectCount||!expected.count(static_cast<int>(index)))throw std::runtime_error("Source text ownership is missing, duplicated, or stale");
   const int oi=static_cast<int>(index);auto object=ops::FPDFPage_GetObject(page,oi);
   if(splitObjects.count(oi)){
    if(!sourceHasCharacterSpan(block,oi))throw std::runtime_error("A split source object requires exact character ownership");const auto characters=sourceCharacterIndices(block,oi);if(characters.empty())throw std::runtime_error("A source character span is empty");int previous=-1;
    for(int character:characters){if(character<=previous||character<0||character>=characterCount||FPDFText_GetTextObject(textPage.value,character)!=object)throw std::runtime_error("Source character ownership is missing, duplicated, or stale");previous=character;if(!sourceWhitespace(FPDFText_GetUnicode(textPage.value,character))&&!claimedCharacters.insert(character).second)throw std::runtime_error("Source character ownership is duplicated");}
    owned.insert(oi);originalText+=sourceCharacterText(textPage.value,characters);
   }else{if(!owned.insert(oi).second)throw std::runtime_error("Source text ownership is missing, duplicated, or stale");originalText+=objectText(object,textPage.value);}
  }
  if(compact(originalText)!=compact(block.value("sourceText",block.value("text",std::string{}))))throw std::runtime_error("The saved source text no longer matches this page");
 }
 if(owned!=expected||claimedCharacters!=expectedCharacters)throw std::runtime_error("Some source text has no translated block; saved checkpoints are retained");
}
void checkExportGeneration(uint64_t generation){if(exportCancellationGeneration.load()!=generation)throw std::runtime_error("Canceled; saved checkpoints retained");}
void protectOriginal(const std::string& source,const std::string& destination){
 if(source.empty()||destination.empty())throw std::runtime_error("The source PDF cannot be overwritten");
 const auto original=std::filesystem::weakly_canonical(source);
 for(const auto& candidate:{destination,destination+".preserving",destination+".preserving.part"}){std::error_code error;
  if(original==std::filesystem::weakly_canonical(candidate)||std::filesystem::equivalent(source,candidate,error))throw std::runtime_error("The source PDF cannot be overwritten");
 }
}
detail::SourceBox sourceBox(const json& value){return {value.at("left").get<double>(),value.at("top").get<double>(),value.at("right").get<double>(),value.at("bottom").get<double>()};}
std::vector<detail::SourceRule> sourceRules(FPDF_PAGEOBJECT object,const PageGeometry& g){
 std::vector<detail::SourceRule> result;int fill=0;FPDF_BOOL stroke=false;
 if(!FPDFPath_GetDrawMode(object,&fill,&stroke))throw std::runtime_error("Cannot inspect source rule drawing mode");
 if(!stroke){
  if(fill==FPDF_FILLMODE_NONE)return result;
  float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot inspect a filled source divider");const auto bounds=displayBounds(g,l,b,r,t);const double left=bounds["left"],right=bounds["right"],top=bounds["top"],bottom=bounds["bottom"];
  if(right-left<=1.5&&bottom-top>=std::max(24.,g.height*.05))result.push_back({(left+right)*.5,top,(left+right)*.5,bottom});
  if(bottom-top<=1.5&&right-left>=std::max(24.,g.width*.05))result.push_back({left,(top+bottom)*.5,right,(top+bottom)*.5});
  return result;
 }
 FS_MATRIX m{};if(!ops::FPDFPageObj_GetMatrix(object,&m))throw std::runtime_error("Cannot read source rule matrix");
 bool previous=false;Point before{},start{};
 const int count=FPDFPath_CountSegments(object);
 for(int i=0;i<count;++i){auto segment=FPDFPath_GetPathSegment(object,i);float x=0,y=0;if(!segment||!FPDFPathSegment_GetPoint(segment,&x,&y))throw std::runtime_error("Cannot inspect source table rule");
  const auto point=toDisplay(g,m.a*x+m.c*y+m.e,m.b*x+m.d*y+m.f);const int type=FPDFPathSegment_GetType(segment);
  if(type==FPDF_SEGMENT_MOVETO){start=point;previous=true;}
  else if(type==FPDF_SEGMENT_LINETO&&previous)result.push_back({before.x,before.y,point.x,point.y});
  if(FPDFPathSegment_GetClose(segment)&&previous)result.push_back({point.x,point.y,start.x,start.y});
  before=point;
 }
 return result;
}
bool opaqueSourceRectangle(FPDF_PAGEOBJECT object,const PageGeometry& g,detail::SourceBox& bounds){
 int fill=0;FPDF_BOOL stroke=false;unsigned r=0,green=0,b=0,alpha=0;
 if(!FPDFPath_GetDrawMode(object,&fill,&stroke)||fill==FPDF_FILLMODE_NONE||FPDFPageObj_HasTransparency(object)||!FPDFPageObj_GetFillColor(object,&r,&green,&b,&alpha)||alpha!=255)return false;
 const auto clip=FPDFPageObj_GetClipPath(object);if(clip&&FPDFClipPath_CountPaths(clip)>0)return false;
 const int count=FPDFPath_CountSegments(object);if(count!=4&&count!=5)return false;FS_MATRIX m{};if(!ops::FPDFPageObj_GetMatrix(object,&m))return false;
 std::vector<Point> points;bounds={1e9,1e9,-1e9,-1e9};
 for(int i=0;i<count;++i){const auto segment=FPDFPath_GetPathSegment(object,i);float x=0,y=0;if(!segment||FPDFPathSegment_GetType(segment)!=(i==0?FPDF_SEGMENT_MOVETO:FPDF_SEGMENT_LINETO)||!FPDFPathSegment_GetPoint(segment,&x,&y))return false;const auto p=toDisplay(g,m.a*x+m.c*y+m.e,m.b*x+m.d*y+m.f);points.push_back(p);bounds.left=std::min(bounds.left,p.x);bounds.top=std::min(bounds.top,p.y);bounds.right=std::max(bounds.right,p.x);bounds.bottom=std::max(bounds.bottom,p.y);}
 if(bounds.right<=bounds.left||bounds.bottom<=bounds.top)return false;
 std::set<std::pair<int,int>> corners;
 for(size_t i=0;i<points.size();++i){const auto& p=points[i];const auto& next=points[(i+1)%points.size()];if((std::abs(p.x-bounds.left)>.001&&std::abs(p.x-bounds.right)>.001)||(std::abs(p.y-bounds.top)>.001&&std::abs(p.y-bounds.bottom)>.001)||(std::abs(p.x-next.x)>.001&&std::abs(p.y-next.y)>.001))return false;if(i<4)corners.emplace(std::abs(p.x-bounds.left)<.001?0:1,std::abs(p.y-bounds.top)<.001?0:1);}
 if(count==5&&(std::abs(points[0].x-points[4].x)>.001||std::abs(points[0].y-points[4].y)>.001))return false;
 return corners.size()==4;
}
bool ordinarySourceFragment(const std::string& value,bool letters);
// A short white rectangle painted before a fully enclosed linguistic source
// run is its paper backdrop. Keep the path unchanged, but allow the same prose
// and original inline glyphs to flow over it. Colored/stroked panels, diagrams,
// clipped shapes and rectangles without actual enclosed source text stay fixed.
bool sourceTextBackdrop(FPDF_PAGE page,FPDF_TEXTPAGE text,int pathIndex,const PageGeometry& g,const detail::SourceBox& bounds){
 auto path=ops::FPDFPage_GetObject(page,pathIndex);int fill=0;FPDF_BOOL stroke=false;unsigned r=0,green=0,b=0,a=0;
 if(!FPDFPath_GetDrawMode(path,&fill,&stroke)||stroke||!FPDFPageObj_GetFillColor(path,&r,&green,&b,&a)||r!=255||green!=255||b!=255||a!=255)return false;
 for(int i=pathIndex+1;i<ops::FPDFPage_CountObjects(page);++i){auto object=ops::FPDFPage_GetObject(page,i);if(ops::FPDFPageObj_GetType(object)!=FPDF_PAGEOBJ_TEXT)continue;const auto raw=objectText(object,text);if(detail::sourceGlyphIsland(detail::fontBaseName(ops::FPDFTextObj_GetFont(object)),raw)||!ordinarySourceFragment(raw,true))continue;
  float l=0,bottom=0,right=0,top=0,size=0;FS_MATRIX matrix{};if(!ops::FPDFPageObj_GetBounds(object,&l,&bottom,&right,&top)||!ops::FPDFTextObj_GetFontSize(object,&size)||!ops::FPDFPageObj_GetMatrix(object,&matrix))throw std::runtime_error("Cannot verify source text backdrop");const auto box=sourceBox(displayBounds(g,l,bottom,right,top));const double fs=size*std::hypot(matrix.c,matrix.d);
  if(fs>0&&bounds.right-bounds.left>=fs*4&&bounds.bottom-bounds.top<=fs*2.5&&box.left>=bounds.left-.1&&box.right<=bounds.right+.1&&box.top>=bounds.top-.1&&box.bottom<=bounds.bottom+.1)return true;
 }return false;
}
std::vector<detail::SourceRule> sourcePageRules(FPDF_PAGE page,const PageGeometry& g){
 std::vector<detail::SourceRule> rules;
 for(int i=0;i<ops::FPDFPage_CountObjects(page);++i){auto object=ops::FPDFPage_GetObject(page,i);if(ops::FPDFPageObj_GetType(object)!=FPDF_PAGEOBJ_PATH)continue;detail::SourceBox mask{};if(opaqueSourceRectangle(object,g,mask))rules=detail::occludeSourceRules(rules,mask);auto current=sourceRules(object,g);rules.insert(rules.end(),current.begin(),current.end());}
 return rules;
}
void rejectNestedSourceText(FPDF_PAGEOBJECT object,int depth){
 if(depth>32)throw std::runtime_error("Nested PDF graphics exceed the source layout limit");
 const int type=ops::FPDFPageObj_GetType(object);
 if(type==FPDF_PAGEOBJ_TEXT&&depth>0)throw std::runtime_error("Nested source text requires an explicit translation container");
 if(type==FPDF_PAGEOBJ_FORM)for(int i=0;i<ops::FPDFFormObj_CountObjects(object);++i){auto child=ops::FPDFFormObj_GetObject(object,i);if(!child)throw std::runtime_error("Cannot inspect nested source object");rejectNestedSourceText(child,depth+1);}
}
json implicitClosedSegments(const json& input){
 json result=json::array(),subpath=json::array();
 const auto finish=[&]{if(subpath.empty())return;if(subpath.size()>1&&subpath.back()["type"]==FPDF_SEGMENT_LINETO&&std::abs(subpath.front()["x"].get<double>()-subpath.back()["x"].get<double>())<.001&&std::abs(subpath.front()["y"].get<double>()-subpath.back()["y"].get<double>())<.001)subpath.erase(subpath.size()-1);subpath.back()["close"]=true;for(const auto& point:subpath)result.push_back(point);subpath=json::array();};
 for(const auto& segment:input){if(segment["type"]==FPDF_SEGMENT_MOVETO)finish();subpath.push_back(segment);}finish();return result;
}
// Compare image pixels, not their lossless PDF stream compression. Keep the
// intrinsic raster plus the masked/clipped appearance at its exact source
// matrix; no source object is transformed or rasterized into the output.
json sourceImagePixels(FPDF_DOCUMENT document,FPDF_PAGE page,FPDF_PAGEOBJECT object,const FS_MATRIX& matrix){
 constexpr uint64_t limit=64ull*1024*1024;unsigned width=0,height=0;
 if(!FPDFImageObj_GetImagePixelSize(object,&width,&height)||width==0||height==0||width>limit/4/height)throw std::runtime_error("Source image verification exceeds the bounded memory limit");
 const double renderedWidth=std::ceil(std::hypot(matrix.a,matrix.c)),renderedHeight=std::ceil(std::hypot(matrix.b,matrix.d));
 if(!std::isfinite(renderedWidth)||!std::isfinite(renderedHeight)||renderedWidth<=0||renderedHeight<=0||renderedWidth>limit/4/renderedHeight)throw std::runtime_error("Source image appearance exceeds the bounded memory limit");
 struct BitmapOwner{FPDF_BITMAP value;~BitmapOwner(){if(value)ops::FPDFBitmap_Destroy(value);}};
 const auto capture=[&](FPDF_BITMAP value){BitmapOwner bitmap{value};if(!value)throw std::runtime_error("Cannot decode original source image pixels");const int w=FPDFBitmap_GetWidth(value),h=FPDFBitmap_GetHeight(value),stride=FPDFBitmap_GetStride(value),format=FPDFBitmap_GetFormat(value);const int channels=format==FPDFBitmap_Gray?1:format==FPDFBitmap_BGR?3:(format==FPDFBitmap_BGRx||format==FPDFBitmap_BGRA)?4:0;
  if(w<=0||h<=0||channels==0||stride<0||static_cast<uint64_t>(w)>limit/4/static_cast<uint64_t>(h)||static_cast<uint64_t>(w)*channels>static_cast<uint64_t>(stride)||static_cast<uint64_t>(stride)>limit/static_cast<uint64_t>(h))throw std::runtime_error("Invalid source image bitmap geometry");
  auto* bytes=static_cast<const unsigned char*>(FPDFBitmap_GetBuffer(value));if(!bytes)throw std::runtime_error("Cannot inspect source image pixel buffer");uint64_t hash=14695981039346656037ull;
  for(int y=0;y<h;++y)for(int x=0;x<w;++x){const auto* pixel=bytes+static_cast<size_t>(y)*stride+static_cast<size_t>(x)*channels;const unsigned char rgba[]={channels==1?pixel[0]:pixel[2],channels==1?pixel[0]:pixel[1],pixel[0],format==FPDFBitmap_BGRA?pixel[3]:static_cast<unsigned char>(255)};for(unsigned char channel:rgba){hash^=channel;hash*=1099511628211ull;}}
  return json{{"width",w},{"height",h},{"rgbaContent",std::to_string(hash)}};
 };
 auto intrinsic=capture(FPDFImageObj_GetBitmap(object));if(intrinsic["width"].get<unsigned>()!=width||intrinsic["height"].get<unsigned>()!=height)throw std::runtime_error("Decoded source image dimensions changed");
 auto appearance=capture(FPDFImageObj_GetRenderedBitmap(document,page,object));FPDF_IMAGEOBJ_METADATA metadata{};if(!FPDFImageObj_GetImageMetadata(object,page,&metadata))throw std::runtime_error("Cannot inspect original source image color metadata");
 return {{"intrinsic",std::move(intrinsic)},{"appearance",std::move(appearance)},{"colorSpace",metadata.colorspace},{"bitsPerPixel",metadata.bits_per_pixel}};
}
json sourceArtworkInventory(FPDF_DOCUMENT document,FPDF_PAGE page){
 json result=json::array();TextPage text(page);const auto visible=geometry(page);
 const auto segmentValue=[](FPDF_PATHSEGMENT segment){float x=0,y=0;if(!segment||!FPDFPathSegment_GetPoint(segment,&x,&y))throw std::runtime_error("Cannot verify source vector segment");return json{{"type",FPDFPathSegment_GetType(segment)},{"close",FPDFPathSegment_GetClose(segment)!=0},{"x",x},{"y",y}};};
 const auto visit=[&](const auto& self,FPDF_PAGEOBJECT object,int depth)->void{
  if(depth>32)throw std::runtime_error("Source artwork nesting exceeds the supported limit");
  const int type=ops::FPDFPageObj_GetType(object);std::string font;
  if(type==FPDF_PAGEOBJ_TEXT){font=detail::fontBaseName(ops::FPDFTextObj_GetFont(object));if(!detail::sourceGlyphIsland(font,objectText(object,text.value)))return;}
  float l=0,b=0,r=0,t=0;FS_MATRIX m{};
  if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t)||!ops::FPDFPageObj_GetMatrix(object,&m))throw std::runtime_error("Cannot verify original artwork geometry");
  // Printer slugs and the reverse side of an imposed spread can live outside
  // CropBox. They do not form part of the visible translated source page.
  if(depth==0&&(r<=visible.left||l>=visible.right||t<=visible.bottom||b>=visible.top))return;
  json entry={{"type",type},{"depth",depth},{"bounds",{l,b,r,t}},{"matrix",{m.a,m.b,m.c,m.d,m.e,m.f}}};
  auto clip=FPDFPageObj_GetClipPath(object);json clipPaths=json::array();if(clip)for(int path=0;path<FPDFClipPath_CountPaths(clip);++path){json segments=json::array();for(int i=0;i<FPDFClipPath_CountPathSegments(clip,path);++i)segments.push_back(segmentValue(FPDFClipPath_GetPathSegment(clip,path,i)));clipPaths.push_back(implicitClosedSegments(segments));}entry["clipPaths"]=std::move(clipPaths);
  unsigned red=0,green=0,blue=0,alpha=0;if(type==FPDF_PAGEOBJ_PATH||type==FPDF_PAGEOBJ_TEXT||type==FPDF_PAGEOBJ_IMAGE){if(FPDFPageObj_GetFillColor(object,&red,&green,&blue,&alpha))entry["fillColor"]={red,green,blue,alpha};if(FPDFPageObj_GetStrokeColor(object,&red,&green,&blue,&alpha))entry["strokeColor"]={red,green,blue,alpha};}
  if(type==FPDF_PAGEOBJ_PATH){json segments=json::array();for(int i=0;i<FPDFPath_CountSegments(object);++i)segments.push_back(segmentValue(FPDFPath_GetPathSegment(object,i)));while(!segments.empty()&&segments.back()["type"]==FPDF_SEGMENT_MOVETO)segments.erase(segments.size()-1);int fill=0;FPDF_BOOL stroke=false;if(!FPDFPath_GetDrawMode(object,&fill,&stroke))throw std::runtime_error("Cannot verify source vector drawing mode");
   // PDFium serializes a single filled rectangle with `re`, which reverses
   // some equivalent vertex lists and makes the implicit fill closure explicit.
   // Compare that one proven shape geometrically; other vector paths keep their
   // actual segment order and closure rather than accepting a bounding-box match.
   const bool closed=(!segments.empty()&&segments.back()["close"].get<bool>())||(segments.size()==5&&std::abs(segments[0]["x"].get<double>()-segments[4]["x"].get<double>())<.001&&std::abs(segments[0]["y"].get<double>()-segments[4]["y"].get<double>())<.001);
   bool rectangle=(!stroke||closed)&&fill!=FPDF_FILLMODE_NONE&&(segments.size()==4||segments.size()==5);double x0=0,y0=0,x1=0,y1=0;
   if(rectangle){x0=x1=segments[0]["x"].get<double>();y0=y1=segments[0]["y"].get<double>();for(size_t i=0;i<segments.size();++i){const auto& segment=segments[i];const int expected=i==0?FPDF_SEGMENT_MOVETO:FPDF_SEGMENT_LINETO;if(segment["type"].get<int>()!=expected){rectangle=false;break;}const double x=segment["x"],y=segment["y"];x0=std::min(x0,x);x1=std::max(x1,x);y0=std::min(y0,y);y1=std::max(y1,y);}
    if(x1<=x0||y1<=y0)rectangle=false;
    std::set<std::pair<int,int>> corners;for(size_t i=0;i<std::min<size_t>(4,segments.size());++i)corners.emplace(std::abs(segments[i]["x"].get<double>()-x0)<.001?0:1,std::abs(segments[i]["y"].get<double>()-y0)<.001?0:1);if(corners.size()!=4)rectangle=false;
    if(segments.size()==5&&(std::abs(segments[0]["x"].get<double>()-segments[4]["x"].get<double>())>.001||std::abs(segments[0]["y"].get<double>()-segments[4]["y"].get<double>())>.001))rectangle=false;
    for(size_t i=0;rectangle&&i<segments.size();++i){const auto& a=segments[i];const auto& b=segments[(i+1)%segments.size()];const double ax=a["x"],ay=a["y"],bx=b["x"],by=b["y"];if((std::abs(ax-bx)>.001&&std::abs(ay-by)>.001)||!(std::abs(ax-x0)<.001||std::abs(ax-x1)<.001)||!(std::abs(ay-y0)<.001||std::abs(ay-y1)<.001))rectangle=false;}
   }
   if(rectangle)entry["filledRectangle"]={x0,y0,x1,y1};else entry["segments"]=!stroke&&fill!=FPDF_FILLMODE_NONE?implicitClosedSegments(segments):std::move(segments);
   float width=0;if(!FPDFPageObj_GetStrokeWidth(object,&width))throw std::runtime_error("Cannot verify source vector stroke");entry["drawMode"]={fill,stroke};entry["strokeWidth"]=width;entry["lineJoin"]=FPDFPageObj_GetLineJoin(object);entry["lineCap"]=FPDFPageObj_GetLineCap(object);}
  if(type==FPDF_PAGEOBJ_IMAGE)entry["imagePixels"]=sourceImagePixels(document,page,object,m);
  if(type==FPDF_PAGEOBJ_TEXT){float fontSize=0,strokeWidth=0;if(!ops::FPDFTextObj_GetFontSize(object,&fontSize)||!FPDFPageObj_GetStrokeWidth(object,&strokeWidth))throw std::runtime_error("Cannot verify original glyph style");entry["fontSize"]=fontSize;entry["textRenderMode"]=static_cast<int>(FPDFTextObj_GetTextRenderMode(object));entry["strokeWidth"]=strokeWidth;entry["font"]=font;auto value=objectText(object,text.value);const auto end=value.find_last_not_of(" \t\r\n");value=end==std::string::npos?std::string{}:value.substr(0,end+1);entry["text"]=value;}
  result.push_back(std::move(entry));
  if(type==FPDF_PAGEOBJ_FORM)for(int i=0;i<ops::FPDFFormObj_CountObjects(object);++i)self(self,ops::FPDFFormObj_GetObject(object,i),depth+1);
 };
 for(int i=0;i<ops::FPDFPage_CountObjects(page);++i){auto object=ops::FPDFPage_GetObject(page,i);if(!object)throw std::runtime_error("Cannot verify original artwork object");visit(visit,object,0);}
 return result;
}
void verifySourceArtwork(const json& before,FPDF_DOCUMENT document,FPDF_PAGE after){
 const auto actual=sourceArtworkInventory(document,after);if(before.size()!=actual.size())throw std::runtime_error("Source artwork or a pictogram was lost during PDF serialization");
 const auto equivalent=[](const auto& self,const json& a,const json& b)->bool{if(a.is_number()&&b.is_number())return std::abs(a.get<double>()-b.get<double>())<=.02;if(a.type()!=b.type()||a.size()!=b.size())return false;if(a.is_array()){for(size_t i=0;i<a.size();++i)if(!self(self,a[i],b[i]))return false;return true;}if(a.is_object()){for(auto it=a.begin();it!=a.end();++it)if(!b.contains(it.key())||!self(self,it.value(),b[it.key()]))return false;return true;}return a==b;};
 for(size_t index=0;index<before.size();++index){const auto& a=before[index];const auto& b=actual[index];
  for(const char* key:{"type","depth","font","text"})if(a.value(key,json{})!=b.value(key,json{}))throw std::runtime_error("Source artwork identity changed during PDF serialization");
  for(const char* key:{"bounds","matrix"})for(size_t n=0;n<a.at(key).size();++n)if(std::abs(a[key][n].get<double>()-b[key][n].get<double>())>.02){throw std::runtime_error("Source artwork moved during PDF serialization");}
  if(!equivalent(equivalent,a,b)){throw std::runtime_error("Source artwork pixels, vectors, clipping or style changed during PDF serialization");}
 }
}
struct SourcePreparedObjects{
 std::vector<FPDF_PAGEOBJECT> values;
 ~SourcePreparedObjects(){for(auto object:values)if(object)ops::FPDFPageObj_Destroy(object);}
};
// A contents reference belongs to the source layout, not to translation. Keep
// its font, matrix and individual glyph origins; translate only the label.
struct SourceContentsGlyph {unsigned cp;FPDF_PAGEOBJECT owner;double x,y;detail::SourceBox bounds;};
struct SourceContentsSpan {
 std::string reference;std::vector<SourceContentsGlyph> digits,dots;detail::SourceAnchor anchor{};
};
SourceContentsSpan sourceContentsSpan(FPDF_PAGE page,FPDF_TEXTPAGE text,const PageGeometry& g,const json& block,const std::set<int>& owned){
 SourceContentsSpan result;static const std::regex suffix(R"((?:\.[\t ]*){3,}([0-9]{1,4})[\t ]*$)");std::smatch match;
 const auto source=block.value("sourceText",std::string{});if(!std::regex_search(source,match,suffix))return result;
 std::vector<SourceContentsGlyph> glyphs;
 auto indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex"))indexes.push_back(block.at("objectIndex"));
 const int count=FPDFText_CountChars(text);if(count<0)throw std::runtime_error("Cannot inspect contents characters");
 for(const auto& value:indexes){const int index=value.get<int>();if(!owned.count(index))throw std::runtime_error("Contents source ownership changed");auto object=ops::FPDFPage_GetObject(page,index);
  const auto spanCharacters=sourceCharacterIndices(block,index);for(int i=0;i<count;++i){if(FPDFText_GetTextObject(text,i)!=object||(sourceHasCharacterSpan(block,index)&&std::find(spanCharacters.begin(),spanCharacters.end(),i)==spanCharacters.end()))continue;const auto cp=FPDFText_GetUnicode(text,i);if(cp==0||cp==' '||cp=='\t'||cp=='\r'||cp=='\n')continue;
   double x=0,y=0,l=0,r=0,b=0,t=0;if(!FPDFText_GetCharOrigin(text,i,&x,&y)||!FPDFText_GetCharBox(text,i,&l,&r,&b,&t))throw std::runtime_error("Cannot read original contents glyph geometry");
   glyphs.push_back({cp,object,x,y,sourceBox(displayBounds(g,l,b,r,t))});
  }
 }
 const auto number=match[1].str();if(glyphs.size()<number.size()+3)throw std::runtime_error("Contents source glyphs are incomplete");
 size_t first=glyphs.size()-number.size();for(size_t i=0;i<number.size();++i)if(glyphs[first+i].cp!=static_cast<unsigned char>(number[i]))throw std::runtime_error("Contents page reference does not match original glyphs");
 size_t dot=first;while(dot&&glyphs[dot-1].cp=='.')--dot;if(first-dot<3)throw std::runtime_error("Contents leaders do not match original glyphs");
 result.reference=number;result.digits.assign(glyphs.begin()+first,glyphs.end());result.dots.assign(glyphs.begin()+dot,glyphs.begin()+first);
 result.anchor={result.digits.front().bounds,toDisplay(g,result.digits.front().x,result.digits.front().y).y};
 for(const auto& glyph:result.digits){result.anchor.bounds.left=std::min(result.anchor.bounds.left,glyph.bounds.left);result.anchor.bounds.top=std::min(result.anchor.bounds.top,glyph.bounds.top);result.anchor.bounds.right=std::max(result.anchor.bounds.right,glyph.bounds.right);result.anchor.bounds.bottom=std::max(result.anchor.bounds.bottom,glyph.bounds.bottom);if(std::abs(toDisplay(g,glyph.x,glyph.y).y-result.anchor.baseline)>.15)throw std::runtime_error("Contents reference mixes source baselines");}
 return result;
}
void prepareSourceContentsRuns(FPDF_DOCUMENT document,const std::vector<SourceContentsGlyph>& glyphs,SourcePreparedObjects& prepared,const PageGeometry& g,const std::string& id,json& placements,const char* role="source-reference"){
 for(size_t first=0;first<glyphs.size();){size_t end=first+1;while(end<glyphs.size()&&glyphs[end].owner==glyphs[first].owner){FS_MATRIX lineMatrix{};if(!ops::FPDFPageObj_GetMatrix(glyphs[first].owner,&lineMatrix))throw std::runtime_error("Cannot verify retained source baseline");const double dx=glyphs[end].x-glyphs[first].x,dy=glyphs[end].y-glyphs[first].y,det=static_cast<double>(lineMatrix.a)*lineMatrix.d-static_cast<double>(lineMatrix.b)*lineMatrix.c;if(std::abs(det)<1e-10||std::abs((-lineMatrix.b*dx+lineMatrix.a*dy)/det)>.02)break;++end;}
  const auto source=glyphs[first].owner;auto sourceClip=FPDFPageObj_GetClipPath(source);if(sourceClip&&FPDFClipPath_CountPaths(sourceClip)>0)throw std::runtime_error("Retained source text clipping requires explicit support");if(FPDFPageObj_HasTransparency(source))throw std::runtime_error("Retained source text transparency requires explicit support");FS_MATRIX matrix{};float size=0;unsigned r=0,green=0,b=0,a=0,sr=0,sg=0,sb=0,sa=0;
  if(!ops::FPDFPageObj_GetMatrix(source,&matrix)||!ops::FPDFTextObj_GetFontSize(source,&size)||!FPDFPageObj_GetFillColor(source,&r,&green,&b,&a)||!FPDFPageObj_GetStrokeColor(source,&sr,&sg,&sb,&sa))throw std::runtime_error("Cannot retain original contents style");
  const auto mode=FPDFTextObj_GetTextRenderMode(source);if(mode!=FPDF_TEXTRENDERMODE_FILL&&mode!=FPDF_TEXTRENDERMODE_STROKE&&mode!=FPDF_TEXTRENDERMODE_FILL_STROKE)throw std::runtime_error("Contents source render mode requires explicit support");
  const double det=static_cast<double>(matrix.a)*matrix.d-static_cast<double>(matrix.b)*matrix.c;if(std::abs(det)<1e-10)throw std::runtime_error("Contents source matrix is singular");
  auto object=ops::FPDFPageObj_CreateTextObj(document,ops::FPDFTextObj_GetFont(source),size);if(!object)throw std::runtime_error("Cannot retain original contents font");prepared.values.push_back(object);
  std::u16string text;std::vector<float> positions;const auto& origin=glyphs[first];
  for(size_t i=first;i<end;++i){const auto& glyph=glyphs[i];if(glyph.cp==0||glyph.cp>0xffff||(glyph.cp>=0xd800&&glyph.cp<=0xdfff))throw std::runtime_error("Retained source text requires supported BMP glyphs");text.push_back(static_cast<char16_t>(glyph.cp));
   const double dx=glyph.x-origin.x,dy=glyph.y-origin.y;const double x=(matrix.d*dx-matrix.c*dy)/det,y=(-matrix.b*dx+matrix.a*dy)/det;if(std::abs(y)>.02)throw std::runtime_error("Contents suffix changes its source baseline");if(i>first)positions.push_back(static_cast<float>(x));
  }
  matrix.e=origin.x;matrix.f=origin.y;float width=0;
  if(!ops::FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(text.c_str()))||(!positions.empty()&&!FPDFText_SetPositions(object,positions.data(),positions.size()))||!FPDFPageObj_SetMatrix(object,&matrix)||!ops::FPDFPageObj_SetFillColor(object,r,green,b,a)||!FPDFPageObj_SetStrokeColor(object,sr,sg,sb,sa)||!FPDFTextObj_SetTextRenderMode(object,mode)||(FPDFPageObj_GetStrokeWidth(source,&width)&&!FPDFPageObj_SetStrokeWidth(object,width)))throw std::runtime_error("Cannot recreate exact contents source run");
  float l=0,bottom=0,right=0,top=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&bottom,&right,&top))throw std::runtime_error("Cannot verify retained contents bounds");auto placement=displayBounds(g,l,bottom,right,top);placement["blockId"]=id;placement["role"]=role;placement["outputPageIndex"]=0;placements.push_back(std::move(placement));first=end;
 }
}
std::vector<json> splitSourceTableBlock(FPDF_PAGE page,FPDF_TEXTPAGE text,FPDF_PAGEOBJECT object,int objectIndex,const PageGeometry& g,const std::vector<detail::SourceRule>& rules,const json& block){
 if(g.rotation!=0||block.value("partialCrop",false)||!block.at("symbols").empty())return {};
 FS_MATRIX matrix{};if(!ops::FPDFPageObj_GetMatrix(object,&matrix)||matrix.a<=0||matrix.d<=0||std::abs(matrix.b)>.001||std::abs(matrix.c)>.001)return {};
 const auto bounds=sourceBox(block);std::vector<double> cuts;
 for(const auto& rule:rules){if(std::abs(rule.x0-rule.x1)>.3)continue;const double x=(rule.x0+rule.x1)*.5;if(x>bounds.left+.8&&x<bounds.right-.8&&std::min(bounds.bottom,std::max(rule.y0,rule.y1))>std::max(bounds.top,std::min(rule.y0,rule.y1))+.1)cuts.push_back(x);}
 if(cuts.empty())return {};std::sort(cuts.begin(),cuts.end());cuts.erase(std::unique(cuts.begin(),cuts.end(),[](double a,double b){return std::abs(a-b)<.3;}),cuts.end());
 struct Cell {std::vector<int> characters;detail::SourceBox bounds{1e9,1e9,-1e9,-1e9};};std::map<size_t,Cell> cells;double firstBaseline=0;bool baseline=false;
 for(int index=0;index<FPDFText_CountChars(text);++index){if(FPDFText_GetTextObject(text,index)!=object)continue;const auto cp=FPDFText_GetUnicode(text,index);if(cp==0||cp>0x10ffff||cp=='\r'||cp=='\n')return {};double x=0,y=0,l=0,r=0,b=0,t=0;if(!FPDFText_GetCharOrigin(text,index,&x,&y)||!FPDFText_GetCharBox(text,index,&l,&r,&b,&t))return {};const auto origin=toDisplay(g,x,y);const auto box=sourceBox(displayBounds(g,l,b,r,t));
  if(!baseline){firstBaseline=origin.y;baseline=true;}else if(std::abs(firstBaseline-origin.y)>block.value("fontSize",9.)*.25)return {};
  if(!sourceWhitespace(cp))for(double cut:cuts)if(box.left<cut-.1&&box.right>cut+.1)return {};
  const double center=sourceWhitespace(cp)?origin.x:(box.left+box.right)*.5;const size_t cell=std::upper_bound(cuts.begin(),cuts.end(),center)-cuts.begin();auto& value=cells[cell];value.characters.push_back(index);
  if(!sourceWhitespace(cp)){value.bounds.left=std::min(value.bounds.left,box.left);value.bounds.top=std::min(value.bounds.top,box.top);value.bounds.right=std::max(value.bounds.right,box.right);value.bounds.bottom=std::max(value.bounds.bottom,box.bottom);}
 }
 std::vector<json> result;
 for(auto& item:cells){auto& cell=item.second;if(cell.bounds.right<=cell.bounds.left||cell.bounds.bottom<=cell.bounds.top)continue;
  while(!cell.characters.empty()&&sourceWhitespace(FPDFText_GetUnicode(text,cell.characters.front())))cell.characters.erase(cell.characters.begin());while(!cell.characters.empty()&&sourceWhitespace(FPDFText_GetUnicode(text,cell.characters.back())))cell.characters.pop_back();if(cell.characters.empty())continue;
  auto part=block;const auto value=sourceCharacterText(text,cell.characters);part["id"]=block.at("id").get<std::string>()+"-s"+std::to_string(item.first);part["text"]=value;part["sourceText"]=value;part["modelText"]=value;part["left"]=cell.bounds.left;part["top"]=cell.bounds.top;part["right"]=cell.bounds.right;part["bottom"]=cell.bounds.bottom;
  const json span={{"objectIndex",objectIndex},{"characterIndices",cell.characters}};part["sourceSpans"]=json::array({span});auto rect=part["sourceRects"][0];for(const char* edge:{"left","top","right","bottom"})rect[edge]=part[edge];rect["text"]=value;rect["characterIndices"]=cell.characters;rect["baseline"]=firstBaseline;part["sourceRects"]=json::array({rect});result.push_back(std::move(part));
 }
 return result.size()>1?result:std::vector<json>{};
}
std::vector<SourceContentsGlyph> sourceSpanGlyphs(FPDF_TEXTPAGE text,FPDF_PAGEOBJECT object,const PageGeometry& g,const std::vector<int>& indexes){std::vector<SourceContentsGlyph> result;for(int index:indexes){double x=0,y=0,l=0,r=0,b=0,t=0;if(!FPDFText_GetCharOrigin(text,index,&x,&y)||!FPDFText_GetCharBox(text,index,&l,&r,&b,&t))throw std::runtime_error("Cannot retain original source character geometry");result.push_back({FPDFText_GetUnicode(text,index),object,x,y,sourceBox(displayBounds(g,l,b,r,t))});}return result;}
struct SourceFlowGlyph {
 std::string token;FPDF_PAGEOBJECT object;FS_MATRIX matrix;detail::SourceBox bounds;double baseline,advance;
 bool sameBaselineSentence=false;
};
struct SourceFlowMove {FPDF_PAGEOBJECT object;FS_MATRIX matrix;detail::SourceBox before,after;};
bool ordinarySourceFragment(const std::string& value,bool letters){
 const auto text=wide(value);for(size_t i=0;i<text.size();){const auto cp=codepointAt(text,i);
  if((cp>='A'&&cp<='Z')||(cp>='a'&&cp<='z')||(!letters&&cp>='0'&&cp<='9')||(cp>=0xc0&&cp<=0x2af)||(cp>=0x370&&cp<=0x52f)||(cp>=0x590&&cp<=0x6ff)||detail::cjk(cp))return true;
 }return false;
}
std::vector<SourceFlowGlyph> sourceFlowGlyphs(FPDF_PAGE page,FPDF_TEXTPAGE text,const PageGeometry& g,const json& block,
 const std::vector<std::pair<std::string,detail::SourceAnchor>>& definitions,const std::vector<detail::SourceBox>& artwork){
 std::vector<SourceFlowGlyph> result;if(g.rotation!=0||definitions.empty()||block.contains("sourceSpans")||startsPictogramLabel(block))return result;
 // SourceRects are checkpoint metadata. Re-read actual object geometry before
 // permitting any glyph movement, so stale metadata cannot classify a legend
 // as prose or change a source object's baseline.
 const auto paragraph=sourceBox(block);for(const auto& art:artwork)if(std::min(paragraph.right,art.right)>std::max(paragraph.left,art.left)+.1&&std::min(paragraph.bottom,art.bottom)>std::max(paragraph.top,art.top)+.1){return {};}
 json rects=json::array();auto indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex"))indexes.push_back(block.at("objectIndex"));
 for(const auto& value:indexes){const int oi=value.get<int>();auto object=ops::FPDFPage_GetObject(page,oi);float l=0,b=0,r=0,t=0,size=0;FS_MATRIX matrix{};if(!object||!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t)||!ops::FPDFPageObj_GetMatrix(object,&matrix)||!ops::FPDFTextObj_GetFontSize(object,&size))throw std::runtime_error("Cannot verify inline paragraph geometry");auto box=displayBounds(g,l,b,r,t);const auto raw=objectText(object,text);const double baseline=toDisplay(g,matrix.e,matrix.f).y,fontSize=size*std::hypot(matrix.c,matrix.d);const double edges[]={box["left"],box["top"],box["right"],box["bottom"],baseline,fontSize};if(!std::all_of(std::begin(edges),std::end(edges),[](double edge){return std::isfinite(edge);})||box["left"]>=box["right"]||box["top"]>=box["bottom"]||box["left"]<-.5||box["top"]<-.5||box["right"]>g.width+.5||box["bottom"]>g.height+.5||fontSize<=0||fontSize>std::max(g.width,g.height)*2)return {};box["objectIndex"]=oi;box["text"]=raw;box["pictogram"]=detail::sourceGlyphIsland(detail::fontBaseName(ops::FPDFTextObj_GetFont(object)),raw);box["baseline"]=baseline;box["fontSize"]=fontSize;rects.push_back(std::move(box));}

 // Use actual, validated coordinates to recover visual order. Fixed-anchor
 // rows avoid bucket-boundary splits when source baselines differ by only a
 // few hundredths of a point; exact left-edge comparisons keep the comparator
 // a strict weak ordering.
 std::vector<json> orderedRects;orderedRects.reserve(rects.size());for(const auto& rect:rects)orderedRects.push_back(rect);
 std::stable_sort(orderedRects.begin(),orderedRects.end(),[](const json& a,const json& b){
  const double ay=a.at("baseline").get<double>(),by=b.at("baseline").get<double>();
  if(ay!=by)return ay<by;
  const double ax=a.at("left").get<double>(),bx=b.at("left").get<double>();if(ax!=bx)return ax<bx;
  return a.at("objectIndex").get<int>()<b.at("objectIndex").get<int>();
 });
 struct SourceRow {double baseline,fontSize;std::vector<json> rects;};std::vector<SourceRow> rows;
 for(const auto& rect:orderedRects){const double baseline=rect.at("baseline").get<double>(),fontSize=rect.at("fontSize").get<double>();
  if(rows.empty()||std::abs(baseline-rows.back().baseline)>std::min(fontSize,rows.back().fontSize)*.15)rows.push_back({baseline,fontSize,{rect}});
  else rows.back().rects.push_back(rect);
 }
 rects.clear();for(auto& row:rows){std::stable_sort(row.rects.begin(),row.rects.end(),[](const json& a,const json& b){const double ax=a.at("left").get<double>(),bx=b.at("left").get<double>();if(ax!=bx)return ax<bx;return a.at("objectIndex").get<int>()<b.at("objectIndex").get<int>();});for(auto& rect:row.rects)rects.push_back(std::move(rect));}

 for(const auto& definition:definitions){const json* symbol=nullptr;for(const auto& value:block.at("symbols"))if(value.at("token")==definition.first)symbol=&value;if(!symbol){return {};}
  const int oi=symbol->at("objectIndex");auto object=ops::FPDFPage_GetObject(page,oi);if(!detail::pictogramFont(detail::fontBaseName(ops::FPDFTextObj_GetFont(object)))){return {};}
  FS_MATRIX matrix{};float symbolSize=0;if(!ops::FPDFPageObj_GetMatrix(object,&matrix)||!ops::FPDFTextObj_GetFontSize(object,&symbolSize)||matrix.a<=0||matrix.d<=0||std::abs(matrix.b)>.001||std::abs(matrix.c)>.001){return {};}
  auto clip=FPDFPageObj_GetClipPath(object);if(clip&&FPDFClipPath_CountPaths(clip)>0){return {};}
  size_t at=rects.size();for(size_t i=0;i<rects.size();++i)if(rects[i].at("objectIndex").get<int>()==oi)at=i;if(at==rects.size()){return {};}
  const json *before=nullptr,*after=nullptr;
  for(size_t i=at;i>0;){--i;if(!rects[i].value("pictogram",false)&&ordinarySourceFragment(rects[i].value("text",std::string{}),true)){before=&rects[i];break;}}
  for(size_t i=at+1;i<rects.size();++i)if(!rects[i].value("pictogram",false)&&ordinarySourceFragment(rects[i].value("text",std::string{}),false)){after=&rects[i];break;}
  // A terminal punctuation-only run can be the sole source text object after
  // the final inline icon (PDFium often splits the period into its own run).
  // Accept only simple trailing punctuation after the final defined glyph;
  // any later lexical run or another icon keeps the legacy fixed placement.
  if(!after&&definition.first==definitions.back().first){
   const json* punctuation=nullptr;bool safe=true;int punctuationRuns=0;
   for(size_t i=at+1;i<rects.size();++i){const auto& candidate=rects[i];if(candidate.value("pictogram",false)){safe=false;break;}
    const auto value=candidate.value("text",std::string{});const auto first=value.find_first_not_of(" \t\r\n"),last=value.find_last_not_of(" \t\r\n");if(first==std::string::npos)continue;
    const auto suffix=value.substr(first,last-first+1);if(suffix.empty()||suffix.find_first_not_of(".,;:!?)]}")!=std::string::npos){safe=false;break;}punctuation=&candidate;++punctuationRuns;
   }
   if(safe&&punctuationRuns==1)after=punctuation;
  }
  if(!before||!after){return {};}
  const auto& anchor=definition.second;const auto& bounds=anchor.bounds;
  const auto adjacent=[&](const json& fragment,bool previous){const auto box=sourceBox(fragment);const double baseline=fragment.value("baseline",0.),size=fragment.value("fontSize",block.value("fontSize",9.)),dy=previous?anchor.baseline-baseline:baseline-anchor.baseline;
   if(std::abs(dy)<=size*.4){const double gap=previous?bounds.left-box.right:box.left-bounds.right;return gap>=-size*.25&&gap<=size*1.5;}
   return dy>size*.6&&dy<size*1.55&&box.left>=block.at("left").get<double>()-1.&&box.right<=block.at("right").get<double>()+1.;
  };
  if(!adjacent(*before,true)||!adjacent(*after,false)){return {};}
  const double actualSymbolBaseline=toDisplay(g,matrix.e,matrix.f).y;
  const double actualSymbolSize=symbolSize*std::hypot(matrix.c,matrix.d);
  const bool sameBaselineSentence=detail::sameBaselineSentenceInline(
   before->at("baseline").get<double>(),actualSymbolBaseline,after->at("baseline").get<double>(),
   before->at("fontSize").get<double>(),actualSymbolSize,after->at("fontSize").get<double>());
  const double beforeSize=before->at("fontSize").get<double>(),afterSize=after->at("fontSize").get<double>();
  const double beforeDelta=actualSymbolBaseline-before->at("baseline").get<double>(),afterDelta=after->at("baseline").get<double>()-actualSymbolBaseline;
  const double inlineTolerance=std::min({beforeSize,actualSymbolSize,afterSize})*.15;
  const bool beforeSame=std::abs(beforeDelta)<=inlineTolerance,afterSame=std::abs(afterDelta)<=inlineTolerance;
  const auto beforeText=before->at("text").get<std::string>(),afterText=after->at("text").get<std::string>();
  const auto beforeEnd=beforeText.find_last_not_of(" \t\r\n"),afterStart=afterText.find_first_not_of(" \t\r\n");
  const bool openBefore=beforeEnd!=std::string::npos&&beforeText[beforeEnd]!='.'&&beforeText[beforeEnd]!='!'&&beforeText[beforeEnd]!='?'&&beforeText[beforeEnd]!=':'&&beforeText[beforeEnd]!=';';
  const bool lowerAfter=afterStart!=std::string::npos&&((afterText[afterStart]>='a'&&afterText[afterStart]<='z')||(afterText[afterStart]>='0'&&afterText[afterStart]<='9'));
  const bool wrappedSentence=(beforeSame||afterSame)&&!sameBaselineSentence&&beforeSize>0&&afterSize>0&&actualSymbolSize>0&&
   std::abs(beforeSize-afterSize)<=std::min(beforeSize,afterSize)*.15&&actualSymbolSize<=std::max(beforeSize,afterSize)*1.5&&
   (beforeSame||(beforeDelta>beforeSize*.6&&beforeDelta<beforeSize*1.55&&openBefore))&&
   (afterSame||(afterDelta>afterSize*.6&&afterDelta<afterSize*1.55&&lowerAfter));
  for(const auto& art:artwork)if(std::min(bounds.right,art.right)>std::max(bounds.left,art.left)+.1&&std::min(bounds.bottom,art.bottom)>std::max(bounds.top,art.top)+.1){return {};}
  result.push_back({definition.first,object,matrix,bounds,anchor.baseline,bounds.right-bounds.left+2.,sameBaselineSentence||wrappedSentence});
 }
 return result;
}
struct SourceFlowPart {std::string text;const SourceFlowGlyph* glyph;};
std::vector<SourceFlowPart> sourceFlowParts(const std::string& value,const std::vector<SourceFlowGlyph>& glyphs){
 std::vector<SourceFlowPart> result;size_t cursor=0;
 while(cursor<value.size()){const auto at=value.find("[[FVSYM",cursor);if(at==std::string::npos){result.push_back({value.substr(cursor),nullptr});break;}if(at>cursor)result.push_back({value.substr(cursor,at-cursor),nullptr});
  const auto end=value.find("]]",at);if(end==std::string::npos)throw std::runtime_error("Invalid flowing source glyph token");const auto token=value.substr(at,end+2-at);const SourceFlowGlyph* found=nullptr;for(const auto& glyph:glyphs)if(glyph.token==token)found=&glyph;if(!found)throw std::runtime_error("Unknown flowing source glyph token");result.push_back({{},found});cursor=end+2;
 }return result;
}
void relocateExpectedSourceGlyph(json& artwork,const SourceFlowGlyph& glyph,const detail::SourceBox& after,const FS_MATRIX& matrix,const PageGeometry& g){
 const auto beforePdf=toPdf(g,glyph.bounds.left,glyph.bounds.top),beforeOther=toPdf(g,glyph.bounds.right,glyph.bounds.bottom);size_t matches=0;
 for(auto& entry:artwork){if(entry.at("type").get<int>()!=FPDF_PAGEOBJ_TEXT)continue;const auto& m=entry.at("matrix");const double values[]={glyph.matrix.a,glyph.matrix.b,glyph.matrix.c,glyph.matrix.d,glyph.matrix.e,glyph.matrix.f};bool same=true;for(size_t i=0;i<6;++i)if(std::abs(m[i].get<double>()-values[i])>.001)same=false;if(!same)continue;
  const auto& b=entry.at("bounds");if(std::abs(b[0].get<double>()-std::min(beforePdf.x,beforeOther.x))>.02||std::abs(b[1].get<double>()-std::min(beforePdf.y,beforeOther.y))>.02||std::abs(b[2].get<double>()-std::max(beforePdf.x,beforeOther.x))>.02||std::abs(b[3].get<double>()-std::max(beforePdf.y,beforeOther.y))>.02)continue;
  const auto p=toPdf(g,after.left,after.top),q=toPdf(g,after.right,after.bottom);entry["bounds"]={std::min(p.x,q.x),std::min(p.y,q.y),std::max(p.x,q.x),std::max(p.y,q.y)};entry["matrix"]={matrix.a,matrix.b,matrix.c,matrix.d,matrix.e,matrix.f};++matches;
 }
 if(matches!=1)throw std::runtime_error("Flowing source glyph has ambiguous artwork identity");
}
// Plan and verify every replacement before changing any source object. Only text
// objects are replaced; paths, pictures, clipping, forms and original icons stay.
json preserveSourcePage(FPDF_DOCUMENT document,int sourceIndex,const json& input,ReadableFont& latin,ReadableFont* chinese,uint64_t generation,json& expectedArtwork,bool preferFlow=true,bool applyForeignObstacles=false){
 checkExportGeneration(generation);
 const auto initialArtwork=expectedArtwork;bool preferredFlowUsed=false;
 if(!input.is_object()||!input.contains("layoutVersion")||!jsonIntegerEquals(input["layoutVersion"],3)||input.value("mode",std::string{})!="preserve-source"||!input.contains("sourcePageIndex")||!jsonIntegerEquals(input["sourcePageIndex"],sourceIndex)||!input.contains("blocks")||!input["blocks"].is_array())throw std::runtime_error("Invalid source-preserving page envelope");
 Page original(document,sourceIndex);const auto g=geometry(original.value);TextPage textPage(original.value);
 std::vector<json> blocks;std::set<std::string> ids;bool review=false;json issues=json::array();
 for(const auto& raw:input["blocks"]){auto block=canonicalBlock(raw,g);const auto id=block.value("id",std::string{});
  if(id.find_first_not_of(" \t\r\n")==std::string::npos||!ids.insert(id).second)throw std::runtime_error("Source-preserving block identities must be unique and nonempty");
  const auto translated=block.contains("translatedText")&&!block["translatedText"].is_null()?block["translatedText"].get<std::string>():block.value("translation",std::string{});
  if(translated.find_first_not_of(" \t\r\n")==std::string::npos)throw std::runtime_error("A target-language text block is missing; saved checkpoints retained");
  if(block.value("partialCrop",false))throw std::runtime_error("Partially cropped source text requires an explicit container");
  block["translatedText"]=translated;
  const auto status=block.value("status",std::string("translated"));if(status=="partial"||status=="failed"||block.value("sourceDisagreement",false)){review=true;issues.push_back({{"blockId",id},{"reason","translation_review"},{"message","The translation requires review independently of its source layout."}});}
  blocks.push_back(std::move(block));
 }
 std::set<int> expectedIcons;verifyReadableTextOwnership(original.value,g,blocks,&expectedIcons);
 // Older checkpoints classified standalone trademark glyphs as ordinary text.
 // Upgrade only these verified Unicode islands; never infer missing pictogram
 // semantics or reuse a translation that dropped the original mark.
 for(auto& block:blocks){auto definitions=block.value("symbols",json::array());auto model=block.value("modelText",block.value("sourceText",std::string{}));auto target=block.at("translatedText").get<std::string>();const auto indexes=block.value("objectIndices",json::array());bool changed=false;
  for(const auto& value:indexes){const int oi=value.get<int>();if(!expectedIcons.count(oi))continue;bool declared=false;for(const auto& symbol:definitions)if(symbol.at("objectIndex").get<int>()==oi)declared=true;if(declared)continue;
   auto object=ops::FPDFPage_GetObject(original.value,oi);const auto font=detail::fontBaseName(ops::FPDFTextObj_GetFont(object));if(detail::pictogramFont(font))continue;const auto raw=objectText(object,textPage.value);const auto first=raw.find_first_not_of(" \t\r\n"),last=raw.find_last_not_of(" \t\r\n");const auto mark=raw.substr(first,last-first+1);const auto token=detail::inlineSymbolToken(oi);
   const auto sourceAt=model.find(mark),targetAt=target.find(mark);if(sourceAt==std::string::npos||targetAt==std::string::npos)throw std::runtime_error("A saved translation omitted an original trademark glyph: "+block.at("id").get<std::string>());
   model.replace(sourceAt,mark.size(),token);target.replace(targetAt,mark.size(),token);float l=0,b=0,r=0,t=0,size=0;FS_MATRIX matrix{};if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t)||!ops::FPDFTextObj_GetFontSize(object,&size)||!ops::FPDFPageObj_GetMatrix(object,&matrix))throw std::runtime_error("Cannot restore original trademark metadata");
   definitions.push_back({{"token",token},{"objectIndex",oi},{"sourceText",raw},{"fontName",font},{"fontSize",size*std::hypot(matrix.c,matrix.d)},{"bounds",displayBounds(g,l,b,r,t)}});changed=true;
  }
  if(changed){std::sort(definitions.begin(),definitions.end(),[&](const json& a,const json& b){return std::find(indexes.begin(),indexes.end(),a.at("objectIndex"))<std::find(indexes.begin(),indexes.end(),b.at("objectIndex"));});block["symbols"]=std::move(definitions);block["modelText"]=model;block["translatedText"]=target;}
 }
 std::vector<detail::SourceLayoutUnit> units;std::vector<detail::SourceBox> artwork;const auto rules=sourcePageRules(original.value,g);
 for(const auto& b:blocks)units.push_back({b.at("id").get<std::string>(),sourceBox(b),b.value("fontSize",10.)});
 // Preserve actual native glyphs owned by separate blocks while fitting prose.
 // Their coarse paragraph containers may overlap even though their ink must not.
 std::map<int,detail::SourceBox> verifiedGlyphInk;
 for(int index:expectedIcons){auto object=ops::FPDFPage_GetObject(original.value,index);float left=0,bottom=0,right=0,top=0;
  if(!object||!ops::FPDFPageObj_GetBounds(object,&left,&bottom,&right,&top))throw std::runtime_error("Cannot read foreign source glyph bounds");
  const auto bounds=sourceBox(displayBounds(g,left,bottom,right,top));
  if(bounds.right>bounds.left&&bounds.bottom>bounds.top)verifiedGlyphInk.emplace(index,bounds);
 }
 for(int index=0;index<ops::FPDFPage_CountObjects(original.value);++index){auto object=ops::FPDFPage_GetObject(original.value,index);if(!object)throw std::runtime_error("Cannot inspect original PDF object");rejectNestedSourceText(object,0);
  const int type=ops::FPDFPageObj_GetType(object);
  if(type==FPDF_PAGEOBJ_IMAGE||type==FPDF_PAGEOBJ_FORM||type==FPDF_PAGEOBJ_SHADING){float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot read source artwork bounds");const auto bounds=sourceBox(displayBounds(g,l,b,r,t));if(bounds.right>bounds.left&&bounds.bottom>bounds.top)artwork.push_back(bounds);}
 }
 // Fixed diagram/illustration geometry is never a prose-flow destination.
 // Verified page backgrounds and white paper backdrops behind actual native
 // text permit prose flow; other vector envelopes stay protected.
 std::vector<detail::SourceBox> flowProtectedArtwork=artwork;
 for(int index=0;index<ops::FPDFPage_CountObjects(original.value);++index){auto object=ops::FPDFPage_GetObject(original.value,index);if(ops::FPDFPageObj_GetType(object)!=FPDF_PAGEOBJ_PATH)continue;detail::SourceBox rectangle{};if(opaqueSourceRectangle(object,g,rectangle)&&((rectangle.right-rectangle.left>=g.width*.9&&rectangle.bottom-rectangle.top>=g.height*.9)||sourceTextBackdrop(original.value,textPage.value,index,g,rectangle)))continue;float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot protect original diagram paths");const auto box=sourceBox(displayBounds(g,l,b,r,t));if(box.right>box.left&&box.bottom>box.top)flowProtectedArtwork.push_back(box);}
 const auto containers=detail::sourceContainers(g.width,g.height,units,artwork,rules);
 const FontCoverage latinCoverage(latin.bytes);const std::vector<unsigned char> emptyFont;
 const FontCoverage chineseCoverage(chinese?chinese->bytes:emptyFont);
 const auto segments=[&](const std::string& value,double size){return lines(latin.value,chinese?chinese->value:nullptr,latinCoverage,chinese?&chineseCoverage:nullptr,value,static_cast<float>(size),1000000,false);};
 std::map<uint32_t,double> advanceCache;std::map<std::string,double> lineAdvanceCache;
 const auto measure=[&](const std::string& value,double size){const auto known=lineAdvanceCache.find(value);if(known!=lineAdvanceCache.end())return known->second*size;
  double width=0;const auto text=wide(value);for(size_t at=0;at<text.size();){const auto cp=codepointAt(text,at);auto advance=advanceCache.find(cp);
   if(advance==advanceCache.end()){const auto font=latinCoverage.has(cp)?latin.value:chinese&&chineseCoverage.has(cp)?chinese->value:nullptr;float em=0;if(!font||!ops::FPDFFont_GetGlyphWidth(font,cp,1,&em))throw std::runtime_error("Source-layout target font lacks a required glyph");advance=advanceCache.emplace(cp,em).first;}
   width+=advance->second;
  }
  if(lineAdvanceCache.size()>=4096)lineAdvanceCache.clear();lineAdvanceCache.emplace(value,width);return width*size;
 };
 std::map<std::string,detail::SourceBox> inkCache;
 const auto inkBounds=[&](const std::string& value,double size){auto known=inkCache.find(value);
  if(known==inkCache.end()){detail::SourceBox box{0,0,0,0};bool first=true;double x=0;
   for(const auto& row:segments(value,1000))for(const auto& part:row){auto object=ops::FPDFPageObj_CreateTextObj(document,part.font,1000);if(!object)throw std::runtime_error("Cannot measure target font ink");
    float l=0,b=0,r=0,t=0;const bool measured=ops::FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(part.text.c_str()))&&ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t);ops::FPDFPageObj_Destroy(object);if(!measured)throw std::runtime_error("Cannot measure target glyph ink");
    const detail::SourceBox partBox{x+l/1000.,-t/1000.,x+r/1000.,-b/1000.};if(first){box=partBox;first=false;}else{box.left=std::min(box.left,partBox.left);box.top=std::min(box.top,partBox.top);box.right=std::max(box.right,partBox.right);box.bottom=std::max(box.bottom,partBox.bottom);}x+=measure(utf8(part.text),1);
   }
   if(inkCache.size()>=4096)inkCache.clear();known=inkCache.emplace(value,box).first;
  }
  const auto& box=known->second;return detail::SourceBox{box.left*size,box.top*size,box.right*size,box.bottom*size};
 };
 struct TargetInkPlacement {std::string blockId;detail::SourceBox bounds;};
 std::vector<TargetInkPlacement> targetInkPlacements;
 std::vector<SourceFlowMove> glyphMoves;SourcePreparedObjects prepared,retainedSymbols;std::vector<std::pair<FPDF_PAGEOBJECT,FPDF_PAGEOBJECT>> symbolReplacements;std::set<FPDF_PAGEOBJECT> remove;std::set<int> declaredIcons;json placements=json::array();
 for(size_t bi=0;bi<blocks.size();++bi){checkExportGeneration(generation);const auto& block=blocks[bi];const auto id=block.at("id").get<std::string>();auto translated=block.at("translatedText").get<std::string>();const auto sourceText=block.value("sourceText",block.value("text",std::string{}));
  if(block.value("source",std::string("native"))!="native")throw std::runtime_error("Image-only text requires a verified source-label container: "+id);
  json indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex"))indexes.push_back(block["objectIndex"]);
  std::set<int> owned;for(const auto& index:indexes)owned.insert(index.get<int>());
  std::vector<std::pair<std::string,detail::SourceAnchor>> definitions;const auto symbols=block.value("symbols",json::array());if(!symbols.is_array())throw std::runtime_error("Invalid original icon metadata");
  for(const auto& symbol:symbols){const int index=symbol.at("objectIndex").get<int>();const auto token=symbol.at("token").get<std::string>();
   if(!owned.count(index)||!expectedIcons.count(index)||!declaredIcons.insert(index).second||token!=detail::inlineSymbolToken(index))throw std::runtime_error("Original inline icon ownership changed");
   auto object=ops::FPDFPage_GetObject(original.value,index);const auto fontName=detail::fontBaseName(ops::FPDFTextObj_GetFont(object));
   if(symbol.at("sourceText").get<std::string>()!=objectText(object,textPage.value)||symbol.value("fontName",std::string{})!=fontName)throw std::runtime_error("Original inline icon identity changed");
   float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot read original icon bounds");const auto actual=displayBounds(g,l,b,r,t);const auto saved=symbol.at("bounds");
   for(const char* edge:{"left","top","right","bottom"})if(std::abs(actual.at(edge).get<double>()-saved.at(edge).get<double>())>1.)throw std::runtime_error("Original inline icon bounds changed");
   FS_MATRIX matrix{};if(!ops::FPDFPageObj_GetMatrix(object,&matrix))throw std::runtime_error("Cannot read original icon baseline");double flowBaseline=toDisplay(g,matrix.e,matrix.f).y;
   if(!detail::pictogramFont(fontName)){double nearest=1e9;const auto mark=sourceBox(actual);for(int peer:owned){if(expectedIcons.count(peer))continue;auto candidate=ops::FPDFPage_GetObject(original.value,peer);float pl=0,pb=0,pr=0,pt=0,size=0;FS_MATRIX pm{};if(!ops::FPDFPageObj_GetBounds(candidate,&pl,&pb,&pr,&pt)||!ops::FPDFTextObj_GetFontSize(candidate,&size)||!ops::FPDFPageObj_GetMatrix(candidate,&pm))throw std::runtime_error("Cannot verify trademark text row");const auto box=sourceBox(displayBounds(g,pl,pb,pr,pt));const double fs=size*std::hypot(pm.c,pm.d),baseline=toDisplay(g,pm.e,pm.f).y,gap=std::max({0.,mark.left-box.right,box.left-mark.right});if(gap<=fs*.85&&std::abs(baseline-toDisplay(g,matrix.e,matrix.f).y)<=fs*.85&&std::min(mark.bottom,box.bottom)>std::max(mark.top,box.top)+.2&&gap<nearest){nearest=gap;flowBaseline=baseline;}}}
   definitions.push_back({token,{sourceBox(actual),flowBaseline}});
  }
  if(translated==sourceText){for(int index:owned){if(expectedIcons.count(index))declaredIcons.insert(index);auto object=ops::FPDFPage_GetObject(original.value,index);if(sourceHasCharacterSpan(block,index)){prepareSourceContentsRuns(document,sourceSpanGlyphs(textPage.value,object,g,sourceCharacterIndices(block,index)),prepared,g,id,placements,"original-text");remove.insert(object);continue;}object=ops::FPDFPage_GetObject(original.value,index);
    if(!expectedIcons.count(index)){std::vector<int> characters;for(int character=0;character<FPDFText_CountChars(textPage.value);++character)if(FPDFText_GetTextObject(textPage.value,character)==object)characters.push_back(character);if(characters.empty())throw std::runtime_error("Cannot retain unchanged source character positions");prepareSourceContentsRuns(document,sourceSpanGlyphs(textPage.value,object,g,characters),prepared,g,id,placements,"original-text");remove.insert(object);continue;}
    float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot verify retained source text bounds");auto placement=displayBounds(g,l,b,r,t);placement["blockId"]=id;placement["role"]="original-text";placement["outputPageIndex"]=0;placements.push_back(std::move(placement));}continue;}
  const auto modelText=block.value("modelText",sourceText);if(!preservesInlineSymbolTokens(modelText,translated))throw std::runtime_error("Translated inline icons do not match their source: "+id);
  std::vector<std::string> sourceTokens;if(!extractInlineSymbolTokens(modelText,sourceTokens)||sourceTokens.size()!=definitions.size())throw std::runtime_error("Missing original inline icon definitions: "+id);
  bool onlySymbols=!owned.empty();for(int index:owned)if(!expectedIcons.count(index))onlySymbols=false;
  if(onlySymbols){std::vector<std::string> targetTokens;if(!extractInlineSymbolTokens(translated,targetTokens)||targetTokens!=sourceTokens)throw std::runtime_error("Original symbol target order changed: "+id);auto remainder=translated;for(const auto& definition:definitions){const auto at=remainder.find(definition.first);if(at==std::string::npos)throw std::runtime_error("Original symbol target is incomplete: "+id);remainder.erase(at,definition.first.size());}if(remainder.find_first_not_of(" \t\r\n")!=std::string::npos)throw std::runtime_error("A source-only symbol block gained lexical text: "+id);continue;}

  // Definition order follows source-object ownership and hence source reading
  // order. Moving a token across another icon would move its meaning on paper.
  std::vector<std::string> chunks;std::vector<detail::SourceAnchor> anchors;size_t cursor=0;
  for(const auto& definition:definitions){const auto at=translated.find(definition.first,cursor);if(at==std::string::npos)throw std::runtime_error("Inline icon order cannot be preserved: "+id);chunks.push_back(translated.substr(cursor,at-cursor));anchors.push_back(definition.second);cursor=at+definition.first.size();}
  chunks.push_back(translated.substr(cursor));
  const auto contents=sourceContentsSpan(original.value,textPage.value,g,block,owned);
  if(!contents.reference.empty()){
   if(!anchors.empty())throw std::runtime_error("Contents with inline symbols requires a verified label container: "+id);
   // Checkpoints from earlier builds translated the page reference as text.
   // Accept the exact unchanged suffix, then render the original source glyphs.
   const auto end=translated.find_last_not_of(" \t\r\n");if(end!=std::string::npos)translated.resize(end+1);
   static const std::regex oldLeader(R"((?:\.[\t ]*){3,}([0-9]{1,4})[\t ]*$)");std::smatch oldMatch;std::string label=sourceText;if(std::regex_search(sourceText,oldMatch,oldLeader)){label.resize(oldMatch.position());const auto end=label.find_last_not_of(" \t");label=end==std::string::npos?std::string{}:label.substr(0,end+1);}
   const bool legacyReference=modelText==sourceText||modelText==label+" "+contents.reference;
   const auto n=contents.reference.size();if(legacyReference&&translated.size()>n&&translated.compare(translated.size()-n,n,contents.reference)==0&&std::isspace(static_cast<unsigned char>(translated[translated.size()-n-1]))){translated.resize(translated.size()-n);const auto last=translated.find_last_not_of(" \t\r\n");translated=last==std::string::npos?std::string{}:translated.substr(0,last+1);}
   if(translated.empty())throw std::runtime_error("Contents translation has no linguistic label: "+id);
   chunks={translated,""};anchors.push_back(contents.anchor);
  }
  const auto fixedChunks=chunks;const auto fixedAnchors=anchors;
  auto flowGlyphs=contents.reference.empty()?sourceFlowGlyphs(original.value,textPage.value,g,block,definitions,flowProtectedArtwork):std::vector<SourceFlowGlyph>{};
  std::string flowText=translated;
  if(!flowGlyphs.empty()){
   // Keep a reference glyph and its following page number on the same line.
   for(const auto& glyph:flowGlyphs){const auto at=flowText.find(glyph.token)+glyph.token.size();auto next=at;while(next<flowText.size()&&(flowText[next]==' '||flowText[next]=='\t'))++next;if(next<flowText.size()&&flowText[next]>='0'&&flowText[next]<='9')flowText.erase(at,next-at);}
  }
  double firstBaseline=0;bool baselineKnown=false;unsigned red=25,green=25,blue=28,alpha=255;std::string sourceBoldLead;bool allBold=true,allItalic=true,stillBoldLead=true;std::vector<double> linguisticSizes;
  Point frameOrigin{},frameRight{1,0},frameDown{0,1};bool frameKnown=false,rotated=false;
  for(const auto& index:indexes){const int oi=index.get<int>();if(expectedIcons.count(oi))continue;auto object=ops::FPDFPage_GetObject(original.value,oi);FS_MATRIX matrix{};if(!ops::FPDFPageObj_GetMatrix(object,&matrix))throw std::runtime_error("Cannot read source text baseline");
   float size=0;if(!ops::FPDFTextObj_GetFontSize(object,&size))throw std::runtime_error("Cannot read original linguistic font size");const double scaledSize=size*std::hypot(matrix.c,matrix.d);if(std::isfinite(scaledSize)&&scaledSize>0)linguisticSizes.push_back(scaledSize);
   const auto base=toDisplay(g,matrix.e,matrix.f);const double sx=std::hypot(matrix.a,matrix.b),sy=std::hypot(matrix.c,matrix.d);
   if(sx<=0||sy<=0)throw std::runtime_error("Original text has a degenerate baseline: "+id);
   const auto rightPoint=toDisplay(g,matrix.e+matrix.a/sx,matrix.f+matrix.b/sx),downPoint=toDisplay(g,matrix.e-matrix.c/sy,matrix.f-matrix.d/sy);
   const Point right{rightPoint.x-base.x,rightPoint.y-base.y},down{downPoint.x-base.x,downPoint.y-base.y};
   const bool turns=std::abs(right.y)>.99||right.x<-.99||std::abs(down.x)>.99||down.y<-.99;
   if(turns&&(std::abs(right.x*down.x+right.y*down.y)>.001||std::min(std::abs(right.x),std::abs(right.y))>.001||std::min(std::abs(down.x),std::abs(down.y))>.001))throw std::runtime_error("Non-orthogonal source text needs a verified baseline: "+id);
   if(!frameKnown){frameOrigin=base;frameRight=right;frameDown=down;frameKnown=true;rotated=turns;}
   else if((rotated||turns)&&(std::abs(right.x-frameRight.x)>.001||std::abs(right.y-frameRight.y)>.001||std::abs(down.x-frameDown.x)>.001||std::abs(down.y-frameDown.y)>.001))throw std::runtime_error("Source block mixes incompatible baseline directions: "+id);
   const auto fontName=detail::fontBaseName(ops::FPDFTextObj_GetFont(object));const bool bold=fontName.find("bold")!=std::string::npos,italic=fontName.find("italic")!=std::string::npos||fontName.find("oblique")!=std::string::npos;
   const auto raw=sourceHasCharacterSpan(block,oi)?sourceCharacterText(textPage.value,sourceCharacterIndices(block,oi)):objectText(object,textPage.value);
   if(raw.find_first_not_of(" \t\r\n")!=std::string::npos){allBold=allBold&&bold;allItalic=allItalic&&italic;if(stillBoldLead&&bold)sourceBoldLead+=raw;else stillBoldLead=false;}
   if(!baselineKnown||base.y<firstBaseline){firstBaseline=base.y;baselineKnown=true;FPDFPageObj_GetFillColor(object,&red,&green,&blue,&alpha);}remove.insert(object);
  }
  if(!baselineKnown){if(!anchors.empty())firstBaseline=anchors.front().baseline;else firstBaseline=block.at("bottom").get<double>();}
  std::sort(linguisticSizes.begin(),linguisticSizes.end());const double originalSize=linguisticSizes.empty()?block.value("fontSize",10.):linguisticSizes[linguisticSizes.size()/2];
  std::string targetBoldPrefix;const auto leadEnd=sourceBoldLead.find_last_not_of(" \t\r\n");const auto asciiColon=translated.find(':'),wideColon=translated.find("：");const auto targetColon=std::min(asciiColon,wideColon);const size_t colonBytes=targetColon==wideColon?3:1;
  const bool sourceColon=leadEnd!=std::string::npos&&(sourceBoldLead[leadEnd]==':'||(leadEnd>=2&&sourceBoldLead.compare(leadEnd-2,3,"：")==0));
  if(!allBold&&sourceColon&&targetColon!=std::string::npos&&translated.substr(0,targetColon).find("[[FVSYM")==std::string::npos)targetBoldPrefix=translated.substr(0,targetColon+colonBytes);
  const auto styledInkBounds=[&](const std::string& text,double size){auto box=inkBounds(text,size);if(allItalic){box.left+=std::min(-box.top,-box.bottom)*.18;box.right+=std::max(-box.top,-box.bottom)*.18;}if(allBold||!targetBoldPrefix.empty()){const auto pad=size*.009;box.left-=pad;box.top-=pad;box.right+=pad;box.bottom+=pad;}return box;};
  auto fitContainer=containers[bi];
  std::set<int> ownedIndexes;for(const auto& index:indexes)ownedIndexes.insert(index.get<int>());
  std::vector<detail::SourceBox> foreignGlyphInk;
  if(applyForeignObstacles)for(const auto& [index,bounds]:verifiedGlyphInk)if(!ownedIndexes.count(index))foreignGlyphInk.push_back(bounds);
  if(rotated){
   if(!anchors.empty())throw std::runtime_error("Rotated inline icons need a verified local anchor: "+id);
   // Allocate whitespace in the source's own reading direction. A world-space
   // right/down-only allocation would cut off descenders for -90/180 degree
   // text, even when its actual cell has ample space in that direction.
   const auto project=[&](const Point& p){const double dx=p.x-frameOrigin.x,dy=p.y-frameOrigin.y;return Point{dx*frameRight.x+dy*frameRight.y,dx*frameDown.x+dy*frameDown.y};};
   const auto projectBox=[&](const detail::SourceBox& box){const Point corners[]={{box.left,box.top},{box.right,box.top},{box.right,box.bottom},{box.left,box.bottom}};detail::SourceBox result{1e9,1e9,-1e9,-1e9};for(const auto& p:corners){const auto q=project(p);result.left=std::min(result.left,q.x);result.top=std::min(result.top,q.y);result.right=std::max(result.right,q.x);result.bottom=std::max(result.bottom,q.y);}return result;};
   const auto pageBox=projectBox({0,0,g.width,g.height});const auto sourceOrigin=frameOrigin;
   frameOrigin={sourceOrigin.x+frameRight.x*pageBox.left+frameDown.x*pageBox.top,sourceOrigin.y+frameRight.y*pageBox.left+frameDown.y*pageBox.top};
   std::vector<detail::SourceLayoutUnit> localUnits;for(const auto& unit:units){auto box=projectBox(unit.bounds);box.left=std::max(0.,box.left);box.top=std::max(0.,box.top);localUnits.push_back({unit.id,box,unit.fontSize});}
   for(auto& box:foreignGlyphInk)box=projectBox(box);
   std::vector<detail::SourceBox> localArtwork;for(const auto& box:artwork)localArtwork.push_back(projectBox(box));
   std::vector<detail::SourceRule> localRules;for(const auto& rule:rules){const auto p=project({rule.x0,rule.y0}),q=project({rule.x1,rule.y1});localRules.push_back({p.x,p.y,q.x,q.y});}
   fitContainer=detail::sourceContainers(pageBox.right-pageBox.left,pageBox.bottom-pageBox.top,localUnits,localArtwork,localRules)[bi];firstBaseline=project(sourceOrigin).y;
  }
  const auto flowMeasure=[&](const std::string& value,double size){double width=0;for(const auto& part:sourceFlowParts(value,flowGlyphs))width+=part.glyph?part.glyph->advance:measure(part.text,size);return width;};
  const auto flowInk=[&](const std::string& value,double size){detail::SourceBox result{0,0,0,0};bool first=true;double x=0;for(const auto& part:sourceFlowParts(value,flowGlyphs)){const auto box=part.glyph?detail::SourceBox{1.,part.glyph->bounds.top-part.glyph->baseline,part.glyph->advance-1.,part.glyph->bounds.bottom-part.glyph->baseline}:styledInkBounds(part.text,size);const detail::SourceBox moved{x+box.left,box.top,x+box.right,box.bottom};if(first){result=moved;first=false;}else{result.left=std::min(result.left,moved.left);result.top=std::min(result.top,moved.top);result.right=std::max(result.right,moved.right);result.bottom=std::max(result.bottom,moved.bottom);}x+=part.glyph?part.glyph->advance:measure(part.text,size);}return result;};
  const auto fixedFit=detail::fitSourceChunks(fitContainer,fixedChunks,fixedAnchors,originalSize,firstBaseline,measure,styledInkBounds,true,foreignGlyphInk);
  auto flowFit=flowGlyphs.empty()?detail::SourceTextFit{}:detail::fitSourceChunks(fitContainer,{flowText},{},originalSize,firstBaseline,flowMeasure,flowInk,false,foreignGlyphInk);
  const auto flowGlyphPlacementError=[&](const detail::SourceBox& actual){
   const auto& box=containers[bi].bounds;
   if(actual.left<box.left-.4||actual.right>box.right+.4||actual.top<box.top-.8||actual.bottom>box.bottom+.4)return "Flowing source glyph exceeds original paragraph: ";
   for(const auto& art:flowProtectedArtwork)if(std::min(actual.right,art.right)>std::max(actual.left,art.left)+.1&&std::min(actual.bottom,art.bottom)>std::max(actual.top,art.top)+.1)return "Flowing source glyph would overlap source artwork: ";
   return "";
  };
  // The fit measures a combined text/glyph envelope. Before preferring it over
  // a valid fixed-anchor layout, verify each actual source-font glyph against
  // the unchanged artwork. A rejected optional candidate must not discard the
  // already valid fixed layout, nor weaken the final placement checks.
  if(flowFit.fits)for(const auto& placement:flowFit.placements){double x=placement.left;
   for(const auto& part:sourceFlowParts(placement.text,flowGlyphs)){
    if(part.glyph){const auto& glyph=*part.glyph;const double dx=x+1.-glyph.bounds.left,dy=placement.baseline-glyph.baseline;const detail::SourceBox actual{glyph.bounds.left+dx,glyph.bounds.top+dy,glyph.bounds.right+dx,glyph.bounds.bottom+dy};
     if(*flowGlyphPlacementError(actual))flowFit.fits=false;
     x+=glyph.advance;
    }else x+=measure(part.text,placement.fontSize)*placement.widthScale;
   }
  }
  const bool sentenceInlineEligible=!flowGlyphs.empty()&&std::all_of(flowGlyphs.begin(),flowGlyphs.end(),[](const auto& glyph){return glyph.sameBaselineSentence;});
  bool excessiveFixedWhitespace=false;
  bool reducedInternalBlankRows=false;
  constexpr double whitespaceFitEpsilon=1e-6;
  const auto occupiedVerticalExtent=[&](const detail::SourceTextFit& candidate,bool flowing){
   double top=std::numeric_limits<double>::infinity(),bottom=-std::numeric_limits<double>::infinity();
   const auto include=[&](double candidateTop,double candidateBottom){if(std::isfinite(candidateTop)&&std::isfinite(candidateBottom)&&candidateTop<candidateBottom){top=std::min(top,candidateTop);bottom=std::max(bottom,candidateBottom);}};
   if(flowing){for(const auto& placement:candidate.placements){double x=placement.left;
     for(const auto& part:sourceFlowParts(placement.text,flowGlyphs)){
      if(part.glyph){const double dy=placement.baseline-part.glyph->baseline;include(part.glyph->bounds.top+dy,part.glyph->bounds.bottom+dy);x+=part.glyph->advance;}
      else{const auto ink=styledInkBounds(part.text,placement.fontSize);include(placement.baseline+ink.top,placement.baseline+ink.bottom);x+=measure(part.text,placement.fontSize)*placement.widthScale;}
     }
    }
   }else{
    for(const auto& placement:candidate.placements){const auto ink=styledInkBounds(placement.text,placement.fontSize);include(placement.baseline+ink.top,placement.baseline+ink.bottom);}
    for(const auto& anchor:fixedAnchors)include(anchor.bounds.top,anchor.bounds.bottom);
   }
   return std::isfinite(top)&&std::isfinite(bottom)?bottom-top:0.;
  };
  if(preferFlow&&!rotated&&fixedFit.fits&&flowFit.fits&&sentenceInlineEligible&&
     flowFit.fontSize+whitespaceFitEpsilon>=fixedFit.fontSize&&
     flowFit.widthScale+whitespaceFitEpsilon>=fixedFit.widthScale){
   const double fixedVerticalExtent=occupiedVerticalExtent(fixedFit,false),flowVerticalExtent=occupiedVerticalExtent(flowFit,true);
   reducedInternalBlankRows=fixedVerticalExtent-flowVerticalExtent>1.5*originalSize;
   for(const auto& anchor:fixedAnchors){
    double occupiedRight=-std::numeric_limits<double>::infinity(),precedingTextSize=0.;
    bool lastOccupiedIsText=false,overlapsCandidate=false;
    for(const auto& placement:fixedFit.placements){
     if(std::abs(placement.baseline-anchor.baseline)>placement.fontSize*.15)continue;
     const auto ink=styledInkBounds(placement.text,placement.fontSize);
     const double textLeft=placement.left+ink.left*placement.widthScale;
     const double textRight=placement.left+ink.right*placement.widthScale;
     if(textLeft<anchor.bounds.left&&textRight>anchor.bounds.left){overlapsCandidate=true;break;}
     if(textLeft<anchor.bounds.left&&textRight<=anchor.bounds.left&&textRight>occupiedRight){
      occupiedRight=textRight;precedingTextSize=placement.fontSize;lastOccupiedIsText=true;
     }
    }
    if(overlapsCandidate||!lastOccupiedIsText)continue;
    for(const auto& prior:fixedAnchors){
     if(&prior==&anchor||std::abs(prior.baseline-anchor.baseline)>precedingTextSize*.15)continue;
     if(prior.bounds.left<anchor.bounds.left&&prior.bounds.right>anchor.bounds.left){overlapsCandidate=true;break;}
     if(prior.bounds.left<anchor.bounds.left&&prior.bounds.right<=anchor.bounds.left&&prior.bounds.right>=occupiedRight){
      occupiedRight=prior.bounds.right;lastOccupiedIsText=false;
     }
    }
    const double emptyGap=anchor.bounds.left-occupiedRight;
    if(!overlapsCandidate&&lastOccupiedIsText&&std::isfinite(emptyGap)&&emptyGap>precedingTextSize*3.){
     excessiveFixedWhitespace=true;break;
    }
   }
  }
  const bool useFlow=detail::preferSourceFlow(fixedFit,flowFit,sentenceInlineEligible&&preferFlow)||excessiveFixedWhitespace||reducedInternalBlankRows;
  preferredFlowUsed=preferredFlowUsed||(useFlow&&fixedFit.fits);
  auto fit=useFlow?flowFit:fixedFit;
  if(fit.fits&&fit.placements.empty())throw std::runtime_error("Target translation contains no visible text: "+id);
  if(useFlow){chunks={flowText};anchors.clear();}
  else{flowGlyphs.clear();chunks=fixedChunks;anchors=fixedAnchors;}
  if(!fit.fits){
   const auto& box=fitContainer.bounds;
   const double minimum=std::min(originalSize,std::max(6.,originalSize*.8));
   double width=box.right-box.left;int estimate=0;
   // Only estimate linguistic capacity when there are no inline pictograms.
   // A TOC page reference is immutable, so reserve its source-font slot first.
   // This feedback guides wording; it never changes the readable font floor or
   // permits omission. The revised page must pass the complete renderer again.
   if(definitions.empty()&&!rotated&&containers[bi].error.empty()){
    if(!contents.reference.empty())width=contents.anchor.bounds.left-.5-box.left;
    const auto units=wide(translated);int characters=0;
    for(size_t at=0;at<units.size();){codepointAt(units,at);++characters;}
    std::string measuredText=translated;for(auto& character:measuredText)if(character=='\n'||character=='\r'||character=='\t')character=' ';
    const double targetWidth=measure(measuredText,minimum)*.8;
    const double lines=contents.reference.empty()?std::clamp(std::floor((box.bottom-firstBaseline)/(minimum*1.15))+1.,1.,10000.):1.;
    if(width>0&&targetWidth>0&&characters>1)estimate=static_cast<int>(std::clamp(std::floor(characters*width*lines/targetWidth*.95),1.,static_cast<double>(std::min(10000,characters-1))));
   }
   throw SourceLayoutFitError("Target text does not fit its original source container: "+id+(containers[bi].error.empty()?std::string{}:" ("+containers[bi].error+")"),
    json{{"blockId",id},{"availableWidthPt",std::max(0.,width)},{"availableHeightPt",box.bottom-box.top},{"minimumFontSizePt",minimum},{"estimatedMaxCharacters",estimate}});
  }
  if(!contents.reference.empty()){
   if(rotated)throw std::runtime_error("Rotated contents requires a verified source reference: "+id);
   std::vector<SourceContentsGlyph> dots;
   for(const auto& dot:contents.dots){bool free=true;for(const auto& placement:fit.placements){const auto ink=styledInkBounds(placement.text,placement.fontSize);const detail::SourceBox actual{placement.left+ink.left*placement.widthScale,placement.baseline+ink.top,placement.left+ink.right*placement.widthScale,placement.baseline+ink.bottom};if(std::min(actual.bottom,dot.bounds.bottom)>std::max(actual.top,dot.bounds.top)-1.&&dot.bounds.left<actual.right+1.){free=false;break;}}
    if(free&&dot.bounds.right<contents.anchor.bounds.left-.5)dots.push_back(dot);
   }
   prepareSourceContentsRuns(document,dots,prepared,g,id,placements);prepareSourceContentsRuns(document,contents.digits,prepared,g,id,placements);
  }
  auto verifiedContainer=containers[bi].bounds;
  if(rotated){const auto& b=fitContainer.bounds;const Point corners[]={{b.left,b.top},{b.right,b.top},{b.right,b.bottom},{b.left,b.bottom}};verifiedContainer={1e9,1e9,-1e9,-1e9};for(const auto& p:corners){const double x=frameOrigin.x+frameRight.x*p.x+frameDown.x*p.y,y=frameOrigin.y+frameRight.y*p.x+frameDown.y*p.y;verifiedContainer.left=std::min(verifiedContainer.left,x);verifiedContainer.top=std::min(verifiedContainer.top,y);verifiedContainer.right=std::max(verifiedContainer.right,x);verifiedContainer.bottom=std::max(verifiedContainer.bottom,y);}}
  std::string boldRemaining=targetBoldPrefix;
  const auto targetStyleParts=[&](const std::string& text){std::vector<std::pair<std::string,bool>> parts;if(allBold){parts.push_back({text,true});return parts;}
   const auto first=boldRemaining.find_first_not_of(" \t\r\n");boldRemaining=first==std::string::npos?std::string{}:boldRemaining.substr(first);size_t count=0;while(count<text.size()&&count<boldRemaining.size()&&text[count]==boldRemaining[count])++count;
   if(count){parts.push_back({text.substr(0,count),true});boldRemaining.erase(0,count);}else if(!text.empty())boldRemaining.clear();if(count<text.size())parts.push_back({text.substr(count),false});return parts;
  };
  bool hasActualTargetTextInk=false;
  for(const auto& placement:fit.placements){double x=placement.left;
   const auto parts=flowGlyphs.empty()?std::vector<SourceFlowPart>{{placement.text,nullptr}}:sourceFlowParts(placement.text,flowGlyphs);
   for(const auto& flowPart:parts){if(flowPart.glyph){const auto& glyph=*flowPart.glyph;const double dx=x+1.-glyph.bounds.left,dy=placement.baseline-glyph.baseline;auto matrix=glyph.matrix;const auto old=toPdf(g,0,0),shift=toPdf(g,dx,dy);matrix.e+=shift.x-old.x;matrix.f+=shift.y-old.y;const detail::SourceBox actual{glyph.bounds.left+dx,glyph.bounds.top+dy,glyph.bounds.right+dx,glyph.bounds.bottom+dy};
     const auto placementError=flowGlyphPlacementError(actual);if(*placementError)throw std::runtime_error(std::string(placementError)+id);
     glyphMoves.push_back({glyph.object,matrix,glyph.bounds,actual});relocateExpectedSourceGlyph(expectedArtwork,glyph,actual,matrix,g);
     placements.push_back({{"blockId",id},{"role","inline-source-glyph"},{"symbolToken",glyph.token},{"outputPageIndex",0},{"left",actual.left},{"top",actual.top},{"right",actual.right},{"bottom",actual.bottom}});x+=glyph.advance;continue;}
   for(const auto& stylePart:targetStyleParts(flowPart.text))for(const auto& row:segments(stylePart.first,placement.fontSize))for(const auto& part:row){auto object=ops::FPDFPageObj_CreateTextObj(document,part.font,placement.fontSize);if(!object)throw std::runtime_error("Cannot create source-layout translation text");prepared.values.push_back(object);
    if(!ops::FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(part.text.c_str()))||!ops::FPDFPageObj_SetFillColor(object,red,green,blue,alpha))throw std::runtime_error("Cannot create source-layout target glyphs");
    if(stylePart.second){if(!FPDFTextObj_SetTextRenderMode(object,FPDF_TEXTRENDERMODE_FILL_STROKE)||!FPDFPageObj_SetStrokeColor(object,red,green,blue,alpha)||!FPDFPageObj_SetStrokeWidth(object,placement.fontSize*.018))throw std::runtime_error("Cannot retain original heading weight");}
    const bool italic=allItalic;
    const auto world=[&](double x,double y){return rotated?Point{frameOrigin.x+frameRight.x*x+frameDown.x*y,frameOrigin.y+frameRight.y*x+frameDown.y*y}:Point{x,y};};
    const auto p0=world(x,placement.baseline),px=world(x+placement.widthScale,placement.baseline),py=world(x+(italic?.18:0.),placement.baseline-1);
    const auto origin=toPdf(g,p0.x,p0.y),ux=toPdf(g,px.x,px.y),uy=toPdf(g,py.x,py.y);
    ops::FPDFPageObj_Transform(object,ux.x-origin.x,ux.y-origin.y,uy.x-origin.x,uy.y-origin.y,origin.x,origin.y);
    float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot verify placed target text bounds");const auto actual=sourceBox(displayBounds(g,l,b,r,t));const double actualEdges[]={actual.left,actual.top,actual.right,actual.bottom};if(!std::all_of(std::begin(actualEdges),std::end(actualEdges),[](double edge){return std::isfinite(edge);}))throw std::runtime_error("Cannot verify finite target text ink");if(actual.right>actual.left&&actual.bottom>actual.top)hasActualTargetTextInk=true;const auto& box=verifiedContainer;
    if(actual.left<box.left-.4||actual.right>box.right+.4||actual.top<box.top-.8||actual.bottom>box.bottom+.4)throw std::runtime_error("Target glyph ink exceeds its original source container: "+id);
    for(const auto& anchor:anchors)if(std::min(actual.right,anchor.bounds.right)>std::max(actual.left,anchor.bounds.left)+.1&&std::min(actual.bottom,anchor.bounds.bottom)>std::max(actual.top,anchor.bounds.top)+.1)throw std::runtime_error("Target glyph ink overlaps an original pictogram: "+id);
    const auto& partUnits=part.text;double partAdvance=0;
    if(!expectedIcons.empty())for(size_t at=0;at<partUnits.size();){const size_t start=at;const uint32_t cp=codepointAt(partUnits,at);float advance=0;if(!ops::FPDFFont_GetGlyphWidth(part.font,cp,placement.fontSize,&advance)||!std::isfinite(advance)||advance<0)throw std::runtime_error("Cannot measure a target character for foreign-glyph collision checking");
     if(cp!=' '&&cp!='\t'&&cp!='\r'&&cp!='\n'){
      auto character=ops::FPDFPageObj_CreateTextObj(document,part.font,placement.fontSize);if(!character)throw std::runtime_error("Cannot allocate target character collision metrics");
      const auto characterText=partUnits.substr(start,at-start);const bool set=ops::FPDFText_SetText(character,reinterpret_cast<const unsigned short*>(characterText.c_str()))!=0;
      if(!set){ops::FPDFPageObj_Destroy(character);throw std::runtime_error("Cannot set target character collision metrics");}
      const double characterX=x+partAdvance*placement.widthScale;const auto cp0=world(characterX,placement.baseline),cpx=world(characterX+placement.widthScale,placement.baseline),cpy=world(characterX+(italic?.18:0.),placement.baseline-1);
      const auto characterOrigin=toPdf(g,cp0.x,cp0.y),characterUx=toPdf(g,cpx.x,cpx.y),characterUy=toPdf(g,cpy.x,cpy.y);
      ops::FPDFPageObj_Transform(character,characterUx.x-characterOrigin.x,characterUx.y-characterOrigin.y,characterUy.x-characterOrigin.x,characterUy.y-characterOrigin.y,characterOrigin.x,characterOrigin.y);
      float cl=0,cb=0,cr=0,ct=0;const bool measured=ops::FPDFPageObj_GetBounds(character,&cl,&cb,&cr,&ct);ops::FPDFPageObj_Destroy(character);if(!measured||!std::isfinite(cl)||!std::isfinite(cb)||!std::isfinite(cr)||!std::isfinite(ct))throw std::runtime_error("Cannot read target character collision ink");
      auto characterBounds=sourceBox(displayBounds(g,cl,cb,cr,ct));if(stylePart.second){const double pad=placement.fontSize*.009;characterBounds.left-=pad;characterBounds.right+=pad;characterBounds.top-=pad;characterBounds.bottom+=pad;}
      if(characterBounds.right>characterBounds.left&&characterBounds.bottom>characterBounds.top)targetInkPlacements.push_back({id,characterBounds});
     }
     partAdvance+=advance;
    }
    placements.push_back({{"blockId",id},{"role","text"},{"outputPageIndex",0},{"left",actual.left},{"top",actual.top},{"right",actual.right},{"bottom",actual.bottom},{"fontSize",placement.fontSize},{"widthScale",placement.widthScale}});
    x+=measure(utf8(part.text),placement.fontSize)*placement.widthScale;
   }
   }
  }
  if(!hasActualTargetTextInk)throw std::runtime_error("Target translation contains no visible text: "+id);
 }
 if(declaredIcons!=expectedIcons)throw std::runtime_error("Some original pictograms have no verified ownership");
 auto collisionPlacements=placements;
 std::map<int,std::string> glyphOwners;
 for(const auto& block:blocks){
  auto indexes=block.value("objectIndices",json::array());
  if(indexes.is_array()&&!indexes.empty()){
   for(const auto& value:indexes){const int index=value.get<int>();if(expectedIcons.count(index))glyphOwners.emplace(index,block.at("id").get<std::string>());}
  }else if(block.contains("objectIndex")&&block.at("objectIndex").is_number_integer()){
   const int index=block.at("objectIndex").get<int>();if(expectedIcons.count(index))glyphOwners.emplace(index,block.at("id").get<std::string>());
  }
 }
 for(const int index:expectedIcons){
  auto object=ops::FPDFPage_GetObject(original.value,index);const bool moved=std::any_of(glyphMoves.begin(),glyphMoves.end(),[&](const SourceFlowMove& move){return move.object==object;});
  if(moved)continue;
  const auto bounds=verifiedGlyphInk.find(index);const auto owner=glyphOwners.find(index);
  if(bounds==verifiedGlyphInk.end()||owner==glyphOwners.end())throw std::runtime_error("Cannot verify a stationary source glyph owner");
  collisionPlacements.push_back({{"blockId",owner->second},{"role","stationary-source-glyph"},{"left",bounds->second.left},{"top",bounds->second.top},{"right",bounds->second.right},{"bottom",bounds->second.bottom}});
 }
 const auto overlaps=[](const detail::SourceBox& a,const detail::SourceBox& b){return std::min(a.right,b.right)>std::max(a.left,b.left)+.2&&std::min(a.bottom,b.bottom)>std::max(a.top,b.top)+.2;};
 for(size_t i=0;i<collisionPlacements.size();++i)for(size_t j=i+1;j<collisionPlacements.size();++j){const auto& a=collisionPlacements[i];const auto& b=collisionPlacements[j];const auto sourceOnly=[](const json& placement){const auto role=placement.value("role",std::string{});return role=="original-text"||role=="stationary-source-glyph";};if(a.at("blockId")==b.at("blockId")||(sourceOnly(a)&&sourceOnly(b)))continue;
  const auto roleA=a.value("role",std::string{}),roleB=b.value("role",std::string{});bool collision=false;
  if((roleA=="text"&&roleB=="stationary-source-glyph")||(roleB=="text"&&roleA=="stationary-source-glyph")){
   const auto& text=roleA=="text"?a:b;const auto& glyph=roleA=="stationary-source-glyph"?a:b;const std::string blockId=text.at("blockId").get<std::string>();
   const detail::SourceBox glyphBounds{glyph.at("left").get<double>(),glyph.at("top").get<double>(),glyph.at("right").get<double>(),glyph.at("bottom").get<double>()};
   const detail::SourceBox wholeTextBounds{text.at("left").get<double>(),text.at("top").get<double>(),text.at("right").get<double>(),text.at("bottom").get<double>()};
   if(overlaps(wholeTextBounds,glyphBounds))for(const auto& character:targetInkPlacements)if(character.blockId==blockId&&overlaps(character.bounds,glyphBounds)){collision=true;break;}
  }else{
   const detail::SourceBox aBounds{a.at("left").get<double>(),a.at("top").get<double>(),a.at("right").get<double>(),a.at("bottom").get<double>()};const detail::SourceBox bBounds{b.at("left").get<double>(),b.at("top").get<double>(),b.at("right").get<double>(),b.at("bottom").get<double>()};collision=overlaps(aBounds,bBounds);
  }
  if(collision){
   // All source mutations are below this verification boundary. First retry
   // a late observed collision with foreign glyph obstacles enabled, retaining
   // the same flow preference. Avoid carving around glyphs that other blocks
   // may move later. If optional flow still collides, retry with fixed fits
   // preferred while retaining the obstacle policy for this bounded replan.
   if(!applyForeignObstacles){expectedArtwork=initialArtwork;return preserveSourcePage(document,sourceIndex,input,latin,chinese,generation,expectedArtwork,preferFlow,true);}
   if(preferFlow&&preferredFlowUsed){expectedArtwork=initialArtwork;return preserveSourcePage(document,sourceIndex,input,latin,chinese,generation,expectedArtwork,false,applyForeignObstacles);}
   throw std::runtime_error("Target text overlaps another source text block: "+a.at("blockId").get<std::string>()+" / "+b.at("blockId").get<std::string>());
  }
 }
 checkExportGeneration(generation);
 // Dirty PDF stream generation can discard kerning/TJ displacements even
 // on retained multi-character pictogram runs. Recreate their exact source-font
 // character origins before serialization, just as for unchanged lexical text.
 for(int index:expectedIcons){auto object=ops::FPDFPage_GetObject(original.value,index);bool moved=false;for(const auto& move:glyphMoves)if(move.object==object)moved=true;if(moved)continue;
  std::vector<int> characters;for(int i=0;i<FPDFText_CountChars(textPage.value);++i)if(FPDFText_GetTextObject(textPage.value,i)==object)characters.push_back(i);
  if(characters.size()>1){const auto first=retainedSymbols.values.size();prepareSourceContentsRuns(document,sourceSpanGlyphs(textPage.value,object,g,characters),retainedSymbols,g,"source-symbol-"+std::to_string(index),placements,"source-symbol");if(retainedSymbols.values.size()!=first+1)throw std::runtime_error("A retained symbol run mixes source baselines");symbolReplacements.push_back({object,retainedSymbols.values.back()});}
 }
 if(textPage.value){ops::FPDFText_ClosePage(textPage.value);textPage.value=nullptr;}
 // PDFium's dirty-stream writer can lose calibrated/ICC path colors while
 // replacing text. Bind their already resolved original RGBA explicitly; keep
 // every path, matrix, clipping contour and image object in place.
 for(int i=0;i<ops::FPDFPage_CountObjects(original.value);++i){auto object=ops::FPDFPage_GetObject(original.value,i);const int objectType=ops::FPDFPageObj_GetType(object);
  if(objectType==FPDF_PAGEOBJ_IMAGE){unsigned r=0,green=0,b=0,a=0;if(FPDFPageObj_GetFillColor(object,&r,&green,&b,&a)&&!ops::FPDFPageObj_SetFillColor(object,r,green,b,a))throw std::runtime_error("Cannot preserve original image-mask color");if(FPDFPageObj_GetStrokeColor(object,&r,&green,&b,&a)&&!FPDFPageObj_SetStrokeColor(object,r,green,b,a))throw std::runtime_error("Cannot preserve original image-mask stroke color");continue;}
  if(objectType!=FPDF_PAGEOBJ_PATH)continue;
  // A trailing empty subpath paints nothing, but prevents PDFium from rewriting
  // an open stroked rectangle as `re`, which would add a visible closing edge.
  int fillMode=0;FPDF_BOOL strokes=false;const int segmentCount=FPDFPath_CountSegments(object);
  if(FPDFPath_GetDrawMode(object,&fillMode,&strokes)&&strokes&&segmentCount>=4&&segmentCount<=5){auto last=FPDFPath_GetPathSegment(object,segmentCount-1);float x=0,y=0;
   if(last&&!FPDFPathSegment_GetClose(last)&&FPDFPathSegment_GetType(last)==FPDF_SEGMENT_LINETO&&FPDFPathSegment_GetPoint(last,&x,&y)&&!FPDFPath_MoveTo(object,x,y))throw std::runtime_error("Cannot retain open source vector path");
  }
  unsigned r=0,green=0,b=0,a=0;if(FPDFPageObj_GetFillColor(object,&r,&green,&b,&a)&&!ops::FPDFPageObj_SetFillColor(object,r,green,b,a))throw std::runtime_error("Cannot preserve original vector fill color");if(FPDFPageObj_GetStrokeColor(object,&r,&green,&b,&a)&&!FPDFPageObj_SetStrokeColor(object,r,green,b,a))throw std::runtime_error("Cannot preserve original vector stroke color");}
 for(const auto& replacement:symbolReplacements){int at=-1;for(int i=0;i<ops::FPDFPage_CountObjects(original.value);++i)if(ops::FPDFPage_GetObject(original.value,i)==replacement.first){at=i;break;}if(at<0||!ops::FPDFPage_RemoveObject(original.value,replacement.first))throw std::runtime_error("Cannot retain original symbol paint order");ops::FPDFPageObj_Destroy(replacement.first);if(!FPDFPage_InsertObjectAtIndex(original.value,replacement.second,at))throw std::runtime_error("Cannot retain original symbol paint order");for(auto& value:retainedSymbols.values)if(value==replacement.second)value=nullptr;}
 for(const auto& move:glyphMoves)if(!FPDFPageObj_SetMatrix(move.object,&move.matrix))throw std::runtime_error("Cannot place original inline source glyph");
 for(auto object:remove){if(!ops::FPDFPage_RemoveObject(original.value,object))throw std::runtime_error("Cannot replace original linguistic text safely");ops::FPDFPageObj_Destroy(object);}
 for(auto& object:prepared.values){ops::FPDFPage_InsertObject(original.value,object);object=nullptr;}
 if(!ops::FPDFPage_GenerateContent(original.value))throw std::runtime_error("Cannot generate source-preserving PDF content");
 return {{"layoutVersion",3},{"mode","preserve-source"},{"sourcePageIndex",sourceIndex},{"pageCount",1},{"partial",review},{"partialBlocks",issues.size()},{"issues",issues},{"placements",placements}};
}
// The bundled fallback covers symbols absent from the primary font as well as
// CJK. Load/embed it only when an actual replacement requires one of its glyphs.
bool sourceTargetNeedsFallback(const json& input,const std::vector<unsigned char>& primaryBytes){
 const FontCoverage coverage(primaryBytes);if(!input.contains("blocks")||!input["blocks"].is_array())return false;
 for(const auto& block:input["blocks"]){const auto value=block.contains("translatedText")&&!block["translatedText"].is_null()?block["translatedText"]:block.value("translation",json{});if(!value.is_string())continue;const auto text=value.get<std::string>();if(text==block.value("sourceText",block.value("text",std::string{})))continue;
  const auto units=wide(text);for(size_t index=0;index<units.size();){const auto cp=codepointAt(units,index);if(cp!='\n'&&cp!='\r'&&cp!='\t'&&!coverage.has(cp))return true;}
 }return false;
}
std::string sourcePreservingPreview(FPDF_DOCUMENT source,int index,const json& input,const std::string& destination,const std::string& fontPath,const std::string& cjkFontPath,uint64_t generation){
 if(!input.is_object()||!input.contains("sourcePageIndex")||!jsonIntegerEquals(input["sourcePageIndex"],index))throw std::runtime_error("Source preview page identity mismatch");
 json artwork;{Page page(source,index);artwork=sourceArtworkInventory(source,page.value);}
 ReadableFont latin(source,fontPath);std::unique_ptr<ReadableFont> chinese;if(!cjkFontPath.empty()&&sourceTargetNeedsFallback(input,latin.bytes))chinese=std::make_unique<ReadableFont>(source,cjkFontPath);
 auto result=preserveSourcePage(source,index,input,latin,chinese.get(),generation,artwork);
 // Regenerate in the original resource graph before copying a preview page.
 // Importing first can change shared font/graphics resources during mutation.
 Doc output(ops::FPDF_CreateNewDocument());if(!ops::FPDF_ImportPagesByIndex(output.value,source,&index,1,0))throw std::runtime_error("Cannot import completed source page preview");
 const std::string staged=destination+".preserving";try{checkExportGeneration(generation);save(output.value,staged);{Doc check(staged);Page page(check.value,0);verifySourceArtwork(artwork,check.value,page.value);}checkExportGeneration(generation);if(ops::rename(staged.c_str(),destination.c_str())!=0)throw std::runtime_error("Cannot finalize source-preserving PDF preview");}catch(...){std::remove(staged.c_str());std::remove((staged+".part").c_str());throw;}
 result["path"]=destination;return result.dump();
}
std::string readablePage(FPDF_DOCUMENT source,int sourceIndex,const json& input,const std::string& destination,const std::string& fontPath,const std::string& cjkFontPath){
 if(input.value("layoutVersion",0)!=2||input.value("mode",std::string{})!="reflow")throw std::runtime_error("Unsupported readable PDF layout version");
 const auto& rawBlocks=input.at("blocks");if(!rawBlocks.is_array())throw std::runtime_error("Readable PDF blocks must be an array");
 Page original(source,sourceIndex);const auto originalGeometry=geometry(original.value);
 std::vector<json> blocks;std::set<std::string> ids;std::vector<double> sizes;bool review=false;json issues=json::array();
 for(const auto& raw:rawBlocks){auto block=canonicalBlock(raw,originalGeometry);const auto id=block.value("id",std::string{});
  if(id.find_first_not_of(" \t\r\n")==std::string::npos||!ids.insert(id).second)throw std::runtime_error("Readable PDF block identities must be unique and nonempty");
  std::string translated;
  if(block.contains("translatedText")&&!block["translatedText"].is_null())translated=block.at("translatedText").get<std::string>();
  else if(block.contains("translation")&&!block["translation"].is_null())translated=block.at("translation").get<std::string>();
  if(translated.find_first_not_of(" \t\r\n")==std::string::npos)throw std::runtime_error("A target-language text block is missing; saved checkpoints are retained");
  // The PUA interval belongs only to the internal inline-glyph layout bridge.
  // Reject literal input in that interval so it cannot alias a captured icon.
  const auto targetUnits=wide(translated);
  for(size_t at=0;at<targetUnits.size();){const auto cp=codepointAt(targetUnits,at);if(cp>=0xe000&&cp<=0xf8ff)throw std::runtime_error("Target text contains a reserved inline symbol codepoint; saved checkpoint retained");}
  block["translatedText"]=translated;blocks.push_back(std::move(block));
  const double size=raw.value("fontSize",10.5);if(std::isfinite(size)&&size>0)sizes.push_back(size);
  const auto status=raw.value("status",std::string("translated"));if(status=="partial"||status=="failed"||raw.value("sourceDisagreement",false))review=true;
 }
 std::set<int> sourcePictogramObjects;verifyReadableTextOwnership(original.value,originalGeometry,blocks,&sourcePictogramObjects);
 std::set<int> declaredPictogramObjects,legacyInvariantPictogramObjects;
 std::map<std::string,InlineSymbolAsset> inlineSymbols;std::map<uint32_t,detail::InlineGlyph> inlineGlyphs;std::map<std::string,uint32_t> symbolCodepoints;size_t inlineBytes=0;uint32_t nextCodepoint=0xe000;
 TextPage symbolTextPage(original.value);
 for(const auto& block:blocks){
  const auto modelText=block.value("modelText",block.value("sourceText",block.value("text",std::string{})));const auto translated=block.at("translatedText").get<std::string>();
  std::vector<std::string> modelTokens,translatedTokens;if(!extractInlineSymbolTokens(modelText,modelTokens)||!extractInlineSymbolTokens(translated,translatedTokens))throw std::runtime_error("Inline symbol token syntax is invalid; saved checkpoint retained");
  const auto definitions=block.value("symbols",json::array());if(!definitions.is_array())throw std::runtime_error("Inline symbol metadata is invalid; saved checkpoint retained");
  if(modelTokens.empty()&&definitions.empty()){
   if(!translatedTokens.empty())throw std::runtime_error("Translation contains an unknown inline symbol token; saved checkpoint retained");
   std::set<int> blockPictograms;for(const auto& index:block.value("objectIndices",json::array()))if(index.is_number_integer()&&sourcePictogramObjects.count(index.get<int>()))blockPictograms.insert(index.get<int>());
   if(!blockPictograms.empty()){
    const auto sourceText=block.value("sourceText",block.value("text",std::string{}));std::set<int> allBlockObjects;
    for(const auto& index:block.value("objectIndices",json::array()))if(index.is_number_integer())allBlockObjects.insert(index.get<int>());
    const bool legacyCheckpoint=!block.contains("modelText")&&!block.contains("symbols");
    const bool standaloneInvariant=legacyCheckpoint&&allBlockObjects==blockPictograms&&translated==sourceText&&detail::languageInvariantAscii(sourceText);
    if(!standaloneInvariant)throw std::runtime_error("Legacy checkpoint omits inline pictogram metadata; saved checkpoint retained");
    legacyInvariantPictogramObjects.insert(blockPictograms.begin(),blockPictograms.end());
   }
   continue;
  }
  if(definitions.empty()||!preservesInlineSymbolTokens(modelText,translated))throw std::runtime_error("Inline symbol token is missing, duplicated, or unknown; saved checkpoint retained");
  std::set<std::string> declaredTokens;std::set<int> blockIndexes;for(const auto& index:block.value("objectIndices",json::array()))if(index.is_number_integer())blockIndexes.insert(index.get<int>());
  std::vector<std::string> definitionTokens;
  for(const auto& definition:definitions){
   if(!definition.is_object()||!definition.contains("token")||!definition["token"].is_string()||!definition.contains("objectIndex")||!definition["objectIndex"].is_number_integer()||!definition.contains("sourceText")||!definition["sourceText"].is_string()||!definition.contains("bounds")||!definition["bounds"].is_object())throw std::runtime_error("Inline symbol metadata is incomplete; saved checkpoint retained");
   const auto token=definition["token"].get<std::string>();const int objectIndex=definition["objectIndex"].get<int>();if(token!=detail::inlineSymbolToken(objectIndex)||!declaredTokens.insert(token).second||!blockIndexes.count(objectIndex))throw std::runtime_error("Inline symbol identity does not match its source object; saved checkpoint retained");
   auto object=ops::FPDFPage_GetObject(original.value,objectIndex);if(!object||ops::FPDFPageObj_GetType(object)!=FPDF_PAGEOBJ_TEXT)throw std::runtime_error("Inline symbol source object is missing; saved checkpoint retained");
   const auto sourceText=objectText(object,symbolTextPage.value);if(sourceText!=definition["sourceText"].get<std::string>())throw std::runtime_error("Inline symbol source text changed; saved checkpoint retained");
   const auto fontName=detail::fontBaseName(ops::FPDFTextObj_GetFont(object));if(!detail::sourceGlyphIsland(fontName,sourceText)||definition.value("fontName",std::string{})!=fontName)throw std::runtime_error("Inline symbol font identity changed; saved checkpoint retained");
   declaredPictogramObjects.insert(objectIndex);
   float left=0,bottom=0,right=0,top=0;if(!ops::FPDFPageObj_GetBounds(object,&left,&bottom,&right,&top))throw std::runtime_error("Cannot verify inline symbol bounds");const double cl=std::max<double>(left,originalGeometry.left),cb=std::max<double>(bottom,originalGeometry.bottom),cr=std::min<double>(right,originalGeometry.right),ct=std::min<double>(top,originalGeometry.top);if(cr<=cl||ct<=cb)throw std::runtime_error("Inline symbol bounds are outside the source page");const auto actual=displayBounds(originalGeometry,cl,cb,cr,ct);const auto& savedBounds=definition["bounds"];
   for(const char* edge:{"left","top","right","bottom"})if(!savedBounds.contains(edge)||!savedBounds[edge].is_number()||!std::isfinite(savedBounds[edge].get<double>())||std::abs(savedBounds[edge].get<double>()-actual[edge].get<double>())>1.0)throw std::runtime_error("Inline symbol bounds changed; saved checkpoint retained");
   if(nextCodepoint>0xf8ff)throw std::runtime_error("Too many inline symbols on one page");
   InlineSymbolAsset asset;asset.blockId=block.at("id").get<std::string>();asset.token=token;asset.sourceText=sourceText;asset.fontName=fontName;asset.objectIndex=objectIndex;asset.bounds={savedBounds["left"].get<double>(),savedBounds["top"].get<double>(),savedBounds["right"].get<double>(),savedBounds["bottom"].get<double>()};
   FPDF_BITMAP bitmap=renderInlineSymbol(source,original.value,object,asset.width,asset.height,asset.pixels,inlineBytes);float fontSize=10.f;ops::FPDFTextObj_GetFontSize(object,&fontSize);ops::FPDFBitmap_Destroy(bitmap);if(!std::isfinite(fontSize)||fontSize<=0)fontSize=10.f;
   const double boxWidth=asset.bounds.right-asset.bounds.left,boxHeight=asset.bounds.bottom-asset.bounds.top;const double scale=std::min({1.5*fontSize/boxHeight,3.0*fontSize/boxWidth});asset.widthEm=static_cast<float>(boxWidth*scale/fontSize);asset.heightEm=static_cast<float>(boxHeight*scale/fontSize);if(!std::isfinite(asset.widthEm)||!std::isfinite(asset.heightEm)||asset.widthEm<=0||asset.heightEm<=0)throw std::runtime_error("Inline symbol has invalid flow dimensions");
   const uint32_t codepoint=nextCodepoint++;inlineGlyphs.emplace(codepoint,detail::InlineGlyph{token,asset.widthEm,asset.heightEm});symbolCodepoints.emplace(token,codepoint);inlineSymbols.emplace(token,std::move(asset));definitionTokens.push_back(token);
  }
  std::sort(definitionTokens.begin(),definitionTokens.end());std::sort(modelTokens.begin(),modelTokens.end());if(definitionTokens!=modelTokens||!preservesInlineSymbolTokens(modelText,translated))throw std::runtime_error("Inline symbol definitions do not match the model text; saved checkpoint retained");
 }
 std::set<int> accountedPictogramObjects=declaredPictogramObjects;accountedPictogramObjects.insert(legacyInvariantPictogramObjects.begin(),legacyInvariantPictogramObjects.end());
 if(accountedPictogramObjects!=sourcePictogramObjects)throw std::runtime_error("Pictogram source objects are missing inline metadata; saved checkpoint retained");
 std::sort(sizes.begin(),sizes.end());const double median=sizes.empty()?10.5:sizes[sizes.size()/2];
 std::vector<ReadableSymbol> symbols;std::vector<int> symbolForBlock(blocks.size(),-1);size_t symbolBytes=0;
 for(size_t i=0;i<blocks.size();++i){const auto& block=blocks[i];const auto sourceText=block.value("sourceText",block.value("text",std::string{}));const auto translated=block.at("translatedText").get<std::string>();
  if(block.value("source",std::string("native"))!="native"||translated!=sourceText||!detail::languageInvariantAscii(sourceText))continue;
  const double left=std::max(0.,block.value("left",0.)-0.5),top=std::max(0.,block.value("top",0.)-0.5),right=std::min(originalGeometry.width,block.value("right",0.)+0.5),bottom=std::min(originalGeometry.height,block.value("bottom",0.)+0.5);
  const double logicalWidth=right-left,logicalHeight=bottom-top;if(!std::isfinite(logicalWidth)||!std::isfinite(logicalHeight)||logicalWidth<=0||logicalHeight<=0)throw std::runtime_error("Invariant source glyph has invalid bounds");
  const double scale=std::min(4.,2048./std::max(logicalWidth,logicalHeight));const int width=std::max(1,static_cast<int>(std::ceil(logicalWidth*scale))),height=std::max(1,static_cast<int>(std::ceil(logicalHeight*scale)));
  const size_t bytes=static_cast<size_t>(width)*static_cast<size_t>(height)*4;if(bytes>64*1024*1024-symbolBytes)throw std::runtime_error("Invariant source glyph assets exceed the readable export memory limit");
  std::vector<unsigned char> pixels(bytes,255);auto bitmap=ops::FPDFBitmap_CreateEx(width,height,FPDFBitmap_BGRA,pixels.data(),width*4);if(!bitmap)throw std::runtime_error("Cannot allocate invariant source glyph bitmap");
  ops::FPDFBitmap_FillRect(bitmap,0,0,width,height,0xffffffff);
  ops::FPDF_RenderPageBitmap(bitmap,original.value,-static_cast<int>(std::lround(left*scale)),-static_cast<int>(std::lround(top*scale)),static_cast<int>(std::ceil(originalGeometry.width*scale)),static_cast<int>(std::ceil(originalGeometry.height*scale)),0,0);
  ops::FPDFBitmap_Destroy(bitmap);
  bool visible=false;for(size_t pixel=0;pixel+3<pixels.size();pixel+=4)if(pixels[pixel]<250||pixels[pixel+1]<250||pixels[pixel+2]<250){visible=true;break;}
  if(!visible)throw std::runtime_error("Cannot capture invariant source glyph pixels");
  symbolBytes+=bytes;symbolForBlock[i]=static_cast<int>(symbols.size());symbols.push_back({block.at("id").get<std::string>(),{left,top,right,bottom},width,height,std::move(pixels)});
 }
 std::vector<GraphicRegion> graphics;
 for(int i=0;i<ops::FPDFPage_CountObjects(original.value);++i){auto object=ops::FPDFPage_GetObject(original.value,i);if(!object)throw std::runtime_error("Cannot inspect PDF graphic object");
  if(!hideReadableSourceText(object,0))continue;
  float l=0,b=0,r=0,t=0;if(!ops::FPDFPageObj_GetBounds(object,&l,&b,&r,&t))throw std::runtime_error("Cannot read PDF graphic bounds");
  const auto bounds=displayBounds(originalGeometry,l,b,r,t);
  for(const char* edge:{"left","top","right","bottom"})if(!std::isfinite(bounds.at(edge).get<double>()))throw std::runtime_error("Invalid PDF graphic geometry");
  GraphicRegion region{std::max(0.,bounds.at("left").get<double>()-1),std::max(0.,bounds.at("top").get<double>()-1),std::min(originalGeometry.width,bounds.at("right").get<double>()+1),std::min(originalGeometry.height,bounds.at("bottom").get<double>()+1)};
  // A thin or pale stroke can be a real diagram wire/arrow. Keep all bounded
  // non-text artwork; only a truly blank rendered crop may be discarded.
  if(region.right<=region.left||region.bottom<=region.top)continue;
  if(!std::isfinite(region.left)||!std::isfinite(region.top)||!std::isfinite(region.right)||!std::isfinite(region.bottom))throw std::runtime_error("Invalid PDF graphic geometry");
  graphics.push_back(region);
 }
 for(size_t i=0;i<graphics.size();++i)for(size_t j=i+1;j<graphics.size();){
  if(overlaps(graphics[i],graphics[j],2)){auto& a=graphics[i];const auto b=graphics[j];a={std::min(a.left,b.left),std::min(a.top,b.top),std::max(a.right,b.right),std::max(a.bottom,b.bottom)};graphics.erase(graphics.begin()+j);i=static_cast<size_t>(-1);break;}else ++j;
 }
 if(graphics.size()>128)throw std::runtime_error("PDF graphics exceed the bounded readable layout limit");
 std::vector<double> starts;
 for(const auto& block:blocks)if(block.value("right",0.)-block.value("left",0.)<originalGeometry.width*.65&&block.value("top",0.)>originalGeometry.height*.09)starts.push_back(block.value("left",0.));
 std::sort(starts.begin(),starts.end());std::vector<double> columns;
 for(double x:starts)if(columns.empty()||x-columns.back()>originalGeometry.width*.18)columns.push_back(x);
 if(columns.size()>4)columns={};
 enum class ItemKind { text,figure,symbol };struct Item {ItemKind kind;size_t index;int column;double top,left;};std::vector<Item> items;
 const auto column=[&](double left,double top,double width){if(top<originalGeometry.height*.09||width>originalGeometry.width*.75)return -1;int result=0;for(size_t i=1;i<columns.size();++i)if(left>=columns[i]-median*2.2)result=static_cast<int>(i);return result;};
 for(size_t i=0;i<blocks.size();++i){const auto& b=blocks[i];const double left=b.value("left",0.),top=b.value("top",0.);if(symbolForBlock[i]>=0){const auto& symbol=symbols[static_cast<size_t>(symbolForBlock[i])];items.push_back({ItemKind::symbol,static_cast<size_t>(symbolForBlock[i]),column(symbol.bounds.left,symbol.bounds.top,symbol.bounds.right-symbol.bounds.left),symbol.bounds.top,symbol.bounds.left});}else items.push_back({ItemKind::text,i,column(left,top,b.value("right",left)-left),top,left});}
 for(size_t i=0;i<graphics.size();++i){const auto& g=graphics[i];items.push_back({ItemKind::figure,i,column(g.left,g.top,g.right-g.left),g.top,g.left});}
 std::stable_sort(items.begin(),items.end(),[](const Item& a,const Item& b){if(a.column!=b.column)return a.column<b.column;if(a.top!=b.top)return a.top<b.top;return a.left<b.left;});
 Doc output(ops::FPDF_CreateNewDocument());ReadableFont latin(output.value,fontPath);std::unique_ptr<ReadableFont> chinese;
 if(!cjkFontPath.empty())chinese=std::make_unique<ReadableFont>(output.value,cjkFontPath);
 const FontCoverage latinCoverage(latin.bytes);const std::vector<unsigned char> noChinese;
 const FontCoverage chineseCoverage(chinese?chinese->bytes:noChinese);
 constexpr double paperWidth=595.276,paperHeight=841.89,margin=36,contentWidth=paperWidth-2*margin;
 FPDF_PAGE page=nullptr;int pageIndex=-1;double cursor=margin;json placements=json::array();
 const auto finish=[&]{if(page){if(!ops::FPDFPage_GenerateContent(page))throw std::runtime_error("Cannot generate readable PDF content");ops::FPDF_ClosePage(page);page=nullptr;}};
 const auto nextPage=[&]{finish();page=ops::FPDFPage_New(output.value,++pageIndex,paperWidth,paperHeight);if(!page)throw std::runtime_error("Cannot create readable PDF page");cursor=margin;};
 try{
  nextPage();
  for(const auto& item:items){
   if(item.kind==ItemKind::symbol){const auto& symbol=symbols[item.index];const double width=symbol.bounds.right-symbol.bounds.left,height=symbol.bounds.bottom-symbol.bounds.top;const double displayScale=std::min({1.,contentWidth/width,64./height});const double drawWidth=width*displayScale,drawHeight=height*displayScale;
    if(cursor+drawHeight>paperHeight-margin)nextPage();auto bitmap=ops::FPDFBitmap_CreateEx(symbol.width,symbol.height,FPDFBitmap_BGRA,const_cast<unsigned char*>(symbol.pixels.data()),symbol.width*4);if(!bitmap)throw std::runtime_error("Cannot restore invariant source glyph bitmap");
    auto image=ops::FPDFPageObj_NewImageObj(output.value);if(!image){ops::FPDFBitmap_Destroy(bitmap);throw std::runtime_error("Cannot create readable symbol image");}
    if(!ops::FPDFImageObj_SetBitmap(nullptr,0,image,bitmap)){ops::FPDFPageObj_Destroy(image);ops::FPDFBitmap_Destroy(bitmap);throw std::runtime_error("Cannot preserve invariant source glyph pixels");}
    ops::FPDFPageObj_Transform(image,drawWidth,0,0,drawHeight,margin,paperHeight-cursor-drawHeight);ops::FPDFPage_InsertObject(page,image);ops::FPDFBitmap_Destroy(bitmap);
    placements.push_back({{"blockId",symbol.blockId},{"role","symbol"},{"outputPageIndex",pageIndex},{"left",margin},{"top",cursor},{"right",margin+drawWidth},{"bottom",cursor+drawHeight}});cursor+=drawHeight+8;continue;
   }
   if(item.kind==ItemKind::figure){const auto& g=graphics[item.index];const double width=g.right-g.left,height=g.bottom-g.top;
    const double scale=std::min(240./72.,2048./std::max(width,height));const int bitmapWidth=std::max(1,static_cast<int>(std::ceil(width*scale))),bitmapHeight=std::max(1,static_cast<int>(std::ceil(height*scale)));
    std::vector<unsigned char> pixels(static_cast<size_t>(bitmapWidth)*bitmapHeight*4,255);auto bitmap=ops::FPDFBitmap_CreateEx(bitmapWidth,bitmapHeight,FPDFBitmap_BGRA,pixels.data(),bitmapWidth*4);
    if(!bitmap)throw std::runtime_error("Cannot allocate bounded PDF figure bitmap");
    try{
     ops::FPDFBitmap_FillRect(bitmap,0,0,bitmapWidth,bitmapHeight,0xffffffff);
     ops::FPDF_RenderPageBitmap(bitmap,original.value,-static_cast<int>(std::lround(g.left*scale)),-static_cast<int>(std::lround(g.top*scale)),static_cast<int>(std::ceil(originalGeometry.width*scale)),static_cast<int>(std::ceil(originalGeometry.height*scale)),0,0);
     bool visible=false;
     for(size_t pixel=0;pixel+3<pixels.size();pixel+=4)if(pixels[pixel]<255||pixels[pixel+1]<255||pixels[pixel+2]<255){visible=true;break;}
     if(!visible){ops::FPDFBitmap_Destroy(bitmap);continue;}
     // Keep original artwork intact. Erasing an OCR rectangle can also erase
     // diagram strokes. Labels are translated in the flow and keep an explicit
     // association-review finding instead of destructively whitening pixels.
     for(const auto& block:blocks)if(block.value("source",std::string("native"))!="native"){
      const GraphicRegion text{block.value("left",0.),block.value("top",0.),block.value("right",0.),block.value("bottom",0.)};
      if(!overlaps(g,text))continue;
      issues.push_back({{"blockId",block.at("id")},{"reason","figure_label_reflow"},{"message","The translated figure label is shown as text beside the figure; verify its association."}});review=true;
     }
     const double displayScale=std::min({1.,contentWidth/width,300./height});const double drawWidth=width*displayScale,drawHeight=height*displayScale;
     if(cursor+drawHeight>paperHeight-margin)nextPage();auto image=ops::FPDFPageObj_NewImageObj(output.value);if(!image)throw std::runtime_error("Cannot create readable PDF figure");
     if(!ops::FPDFImageObj_SetBitmap(nullptr,0,image,bitmap)){ops::FPDFPageObj_Destroy(image);throw std::runtime_error("Cannot preserve PDF figure pixels");}
     ops::FPDFPageObj_Transform(image,drawWidth,0,0,drawHeight,margin,paperHeight-cursor-drawHeight);ops::FPDFPage_InsertObject(page,image);
     placements.push_back({{"figureId","p"+std::to_string(sourceIndex)+"-figure"+std::to_string(item.index)},{"role","figure"},{"outputPageIndex",pageIndex},{"left",margin},{"top",cursor},{"right",margin+drawWidth},{"bottom",cursor+drawHeight}});cursor+=drawHeight+12;
    }catch(...){ops::FPDFBitmap_Destroy(bitmap);throw;}ops::FPDFBitmap_Destroy(bitmap);continue;
   }
   const auto& block=blocks[item.index];const auto text=block.at("translatedText").get<std::string>();const double originalSize=block.value("fontSize",median);std::string flowText=text;const auto definitions=block.value("symbols",json::array());
   if(definitions.is_array())for(const auto& definition:definitions){const auto token=definition.at("token").get<std::string>();const auto codepoint=symbolCodepoints.at(token);const auto at=flowText.find(token);if(at==std::string::npos||flowText.find(token,at+token.size())!=std::string::npos)throw std::runtime_error("Inline symbol token became ambiguous during layout");flowText.replace(at,token.size(),privateUseUtf8(codepoint));}
   const auto tail=text.find_last_not_of(" \t\r\n");const bool heading=originalSize>=median*1.45&&text.size()<240&&tail!=std::string::npos&&text[tail]!='.'&&text[tail]!='!'&&text[tail]!='?';
   const float size=heading?13.5f:10.5f,baseLeading=size*1.38f;
   const auto wrapped=lines(latin.value,chinese?chinese->value:nullptr,latinCoverage,chinese?&chineseCoverage:nullptr,flowText,size,contentWidth,true,&inlineGlyphs);
   if(heading&&cursor+baseLeading*3>paperHeight-margin)nextPage();
   if(heading&&cursor>margin)cursor+=5;
   int recordedPage=-1;size_t placementIndex=0;
   for(const auto& line:wrapped){
    double lineHeight=size;
    for(const auto& segment:line)if(!segment.symbolToken.empty())lineHeight=std::max(lineHeight,static_cast<double>(size*segment.heightEm));
    const double leading=std::max(static_cast<double>(baseLeading),lineHeight+size*0.2);
    if(cursor+leading>paperHeight-margin)nextPage();double x=margin;
    const double textTop=cursor+(lineHeight-size)*0.5;
    for(const auto& segment:line){if(!segment.symbolToken.empty()){const auto found=inlineSymbols.find(segment.symbolToken);if(found==inlineSymbols.end())throw std::runtime_error("Inline symbol asset is unavailable");const auto& symbol=found->second;const double drawWidth=size*segment.widthEm,drawHeight=size*segment.heightEm;auto bitmap=ops::FPDFBitmap_CreateEx(symbol.width,symbol.height,FPDFBitmap_BGRA,const_cast<unsigned char*>(symbol.pixels.data()),symbol.width*4);if(!bitmap)throw std::runtime_error("Cannot restore inline symbol bitmap");auto image=ops::FPDFPageObj_NewImageObj(output.value);if(!image){ops::FPDFBitmap_Destroy(bitmap);throw std::runtime_error("Cannot create inline symbol image");}if(!ops::FPDFImageObj_SetBitmap(nullptr,0,image,bitmap)){ops::FPDFPageObj_Destroy(image);ops::FPDFBitmap_Destroy(bitmap);throw std::runtime_error("Cannot preserve inline symbol pixels");}const double symbolTop=cursor+(lineHeight-drawHeight)*0.5;const double imageY=paperHeight-symbolTop-drawHeight;ops::FPDFPageObj_Transform(image,drawWidth,0,0,drawHeight,x,imageY);ops::FPDFPage_InsertObject(page,image);ops::FPDFBitmap_Destroy(bitmap);placements.push_back({{"blockId",symbol.blockId},{"role","symbol"},{"symbolToken",symbol.token},{"outputPageIndex",pageIndex},{"left",x},{"top",symbolTop},{"right",x+drawWidth},{"bottom",symbolTop+drawHeight}});x+=drawWidth;continue;}auto object=ops::FPDFPageObj_CreateTextObj(output.value,segment.font,size);if(!object)throw std::runtime_error("Cannot create readable target text");
     if(!ops::FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(segment.text.c_str()))){ops::FPDFPageObj_Destroy(object);throw std::runtime_error("Cannot write readable target glyphs");}
     ops::FPDFPageObj_SetFillColor(object,25,25,28,255);ops::FPDFPageObj_Transform(object,1,0,0,1,x,paperHeight-textTop-size);ops::FPDFPage_InsertObject(page,object);
     for(size_t at=0;at<segment.text.size();){const auto cp=codepointAt(segment.text,at);float advance=0;if(!ops::FPDFFont_GetGlyphWidth(segment.font,cp,size,&advance))throw std::runtime_error("Readable PDF font lacks a required glyph");x+=advance;}
    }
    if(recordedPage!=pageIndex){placementIndex=placements.size();placements.push_back({{"blockId",block.at("id")},{"role",heading?"heading":"text"},{"outputPageIndex",pageIndex},{"left",margin},{"top",cursor},{"right",x},{"bottom",cursor+leading}});recordedPage=pageIndex;}
    else {placements[placementIndex]["right"]=std::max(placements[placementIndex]["right"].get<double>(),x);placements[placementIndex]["bottom"]=cursor+leading;}
    cursor+=leading;
   }
   cursor+=heading?8:5;
  }
  finish();save(output.value,destination);
 }catch(...){if(page)ops::FPDF_ClosePage(page);throw;}
 return json{{"layoutVersion",2},{"mode","reflow"},{"sourcePageIndex",sourceIndex},{"path",destination},{"pageCount",pageIndex+1},{"partial",review},{"partialBlocks",issues.size()},{"issues",issues},{"placements",placements}}.dump();
}
}

std::string exportPage(const std::string& source,int index,const std::string& blocksJson,const std::string& destination,const std::string& fontPath,const std::string& cjkFontPath){try{
 const auto generation=exportCancellationGeneration.load();std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc src(source);const auto request=json::parse(blocksJson);if(request.is_object()&&request.value("layoutVersion",0)==3){protectOriginal(source,destination);return sourcePreservingPreview(src.value,index,request,destination,fontPath,cjkFontPath,generation);}if(request.is_object()&&request.contains("layoutVersion"))return readablePage(src.value,index,request,destination,fontPath,cjkFontPath);Doc dest(ops::FPDF_CreateNewDocument());int ix=index;if(!ops::FPDF_ImportPagesByIndex(dest.value,src.value,&ix,1,0))throw std::runtime_error("Cannot import original page");Page page(dest.value,0);
 std::ifstream f(fontPath,std::ios::binary);std::vector<unsigned char> data((std::istreambuf_iterator<char>(f)),{});if(data.empty())throw std::runtime_error("Cyrillic export font is missing");
 FPDF_FONT font=ops::FPDFText_LoadFont(dest.value,data.data(),data.size(),FPDF_FONT_TRUETYPE,true);if(!font)throw std::runtime_error("Cannot embed Cyrillic font");
 FPDF_FONT cjkFont=nullptr;std::vector<unsigned char> cjkData;
 if(!cjkFontPath.empty()){std::ifstream cf(cjkFontPath,std::ios::binary);cjkData.assign(std::istreambuf_iterator<char>(cf),{});if(cjkData.empty()){ops::FPDFFont_Close(font);throw std::runtime_error("Simplified Chinese export font is missing");}cjkFont=ops::FPDFText_LoadFont(dest.value,cjkData.data(),cjkData.size(),FPDF_FONT_TRUETYPE,true);if(!cjkFont){ops::FPDFFont_Close(font);throw std::runtime_error("Cannot embed Simplified Chinese font");}}
 const FontCoverage latinCoverage(data);const FontCoverage cjkCoverage(cjkData);
 const auto pageGeometry=geometry(page.value);const auto input=json::parse(blocksJson);const auto blocks=input.is_array()?input:input.at("blocks");int partial=0;bool translationNeedsReview=false;json issues=json::array();std::vector<FPDF_PAGEOBJECT> remove;
 const auto issue=[&](const json& block,const std::string& reason,const std::string& message){partial++;issues.push_back(json{{"blockId",block.value("id",std::string{})},{"reason",reason},{"message",message}});};
 try{for(const auto& sourceBlock:blocks){const auto block=canonicalBlock(sourceBlock,pageGeometry);
  // Review state describes translation confidence, not whether nonempty target
  // text may be placed. A previous layout finding must not make fitting sticky.
  const auto status=block.value("status",std::string("translated"));
  if(status=="partial"||status=="failed"||block.value("sourceDisagreement",false))translationNeedsReview=true;
  if(block.contains("translatedText")&&block["translatedText"].is_null()){issue(block,"missing_translation","No complete translation is available; the original text was retained.");continue;}
  std::string translated=block.value("translatedText",block.value("translation",std::string{}));if(translated.empty()||translated.find_first_not_of(" \t\r\n")==std::string::npos){issue(block,"missing_translation","No complete translation is available; the original text was retained.");continue;}
  // Identical native text needs no replacement. This also preserves custom-font
  // pictograms whose extracted ASCII representation is not their visible glyph.
  if(block.value("source",std::string("native"))=="native"&&translated==block.value("sourceText",block.value("text",std::string{})))continue;
  double width=block.value("right",0.)-block.value("left",0.),boxHeight=block.value("bottom",0.)-block.value("top",0.);float original=block.value("fontSize",10.f),minimum=std::max(6.f,0.8f*original),size=std::max(minimum,original);std::vector<WrappedLine> wrapped;bool fits=false;
  if(block.value("source",std::string("native"))=="native"&&pageGeometry.rotation==0&&width>0&&boxHeight>0&&!block.value("partialCrop",false)){
   for(;size>=minimum-0.01f;size-=0.5f){try{wrapped=lines(font,cjkFont,latinCoverage,cjkFont?&cjkCoverage:nullptr,translated,size,width);fits=!wrapped.empty()&&wrapped.size()*size*1.15<=boxHeight+size*0.35;}catch(...){fits=false;}if(fits)break;}
  }
  if(!fits){annotate(page.value,block,pageGeometry,translated);issue(block,"layout_fallback","The translation could not be placed safely at a readable size. The original remains with the full translation attached for review.");continue;}
  json indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex")&&block["objectIndex"].is_number_integer())indexes.push_back(block["objectIndex"]);
  bool safe=!indexes.empty();for(const auto& n:indexes){int oi=n.get<int>();auto obj=ops::FPDFPage_GetObject(page.value,oi);if(!obj||ops::FPDFPageObj_GetType(obj)!=FPDF_PAGEOBJ_TEXT){safe=false;break;}}
  if(!safe){annotate(page.value,block,pageGeometry,translated);issue(block,"unsafe_text_object","The original text object could not be replaced safely. The translation is attached for review.");continue;}
  for(const auto& n:indexes)remove.push_back(ops::FPDFPage_GetObject(page.value,n.get<int>()));
  int row=0;for(const auto& line:wrapped){double x=block.value("left",0.);for(const auto& segment:line){auto obj=ops::FPDFPageObj_CreateTextObj(dest.value,segment.font,size);if(!obj)throw std::runtime_error("Cannot create translated text");
    if(!ops::FPDFText_SetText(obj,reinterpret_cast<const unsigned short*>(segment.text.c_str()))){ops::FPDFPageObj_Destroy(obj);throw std::runtime_error("Cannot write translated glyphs");}
    ops::FPDFPageObj_SetFillColor(obj,25,25,28,255);ops::FPDFPageObj_Transform(obj,1,0,0,1,x+pageGeometry.left,pageGeometry.top-block.value("top",0.)-size-row*size*1.15);ops::FPDFPage_InsertObject(page.value,obj);
    for(size_t i=0;i<segment.text.size();){uint32_t cp=codepointAt(segment.text,i);float advance=0;if(!ops::FPDFFont_GetGlyphWidth(segment.font,cp,size,&advance))throw std::runtime_error("Font lacks a required glyph");x+=advance;}
   }row++;
  }
 }
 std::sort(remove.begin(),remove.end());remove.erase(std::unique(remove.begin(),remove.end()),remove.end());for(auto obj:remove){if(!ops::FPDFPage_RemoveObject(page.value,obj))throw std::runtime_error("Cannot replace source text safely");ops::FPDFPageObj_Destroy(obj);}
 if(!ops::FPDFPage_GenerateContent(page.value))throw std::runtime_error("Cannot generate translated page content");save(dest.value,destination);
 }catch(...){if(cjkFont)ops::FPDFFont_Close(cjkFont);ops::FPDFFont_Close(font);throw;}if(cjkFont)ops::FPDFFont_Close(cjkFont);ops::FPDFFont_Close(font);
 return json{{"path",destination},{"partial",partial>0||translationNeedsReview},{"partialBlocks",partial},{"issues",issues},{"pageCount",1}}.dump();
}catch(const SourceLayoutFitError&e){return json{{"error",e.what()},{"layoutFeedback",e.feedback}}.dump();}catch(const std::exception&e){return failure(e);}}
std::string mergePdf(const std::string& pagesJson,const std::string& destination){try{
 const auto generation=exportCancellationGeneration.load();std::lock_guard<std::mutex> lock(pdfMutex);initPdf();auto input=json::parse(pagesJson);
 if(input.is_object()&&input.contains("layoutVersion")&&jsonIntegerEquals(input["layoutVersion"],3)){
  if(input.value("mode",std::string{})!="preserve-source"||!input.contains("shards")||!input["shards"].is_array()||input["shards"].empty())throw std::runtime_error("Invalid source-preserving document envelope");
  const auto source=input.at("sourcePath").get<std::string>();protectOriginal(source,destination);checkExportGeneration(generation);Doc original(source);const int count=ops::FPDF_GetPageCount(original.value);
  if(count<=0||input["shards"].size()!=static_cast<size_t>(count))throw std::runtime_error("Every original page must have exactly one translated snapshot");
  ReadableFont latin(original.value,input.at("fontPath").get<std::string>());std::unique_ptr<ReadableFont> chinese;const auto cjk=input.value("cjkFontPath",std::string{});
  json ranges=json::array(),pageMap=json::array();std::vector<json> artwork;bool partial=false;size_t partialBlocks=0;
  for(int index=0;index<count;++index){checkExportGeneration(generation);const auto& shard=input["shards"][index];
   if(!shard.is_object()||!shard.contains("sourcePageIndex")||!jsonIntegerEquals(shard["sourcePageIndex"],index)||!shard.contains("expectedPageCount")||!jsonIntegerEquals(shard["expectedPageCount"],1))throw std::runtime_error("Source-preserving pages must have an ordered identity map");
   Doc preview(shard.at("path").get<std::string>());if(ops::FPDF_GetPageCount(preview.value)!=1)throw std::runtime_error("A source-preserving preview must contain exactly one page");
   {Page a(original.value,index),b(preview.value,0);const auto ga=geometry(a.value),gb=geometry(b.value);if(ga.rotation!=gb.rotation||std::abs(ga.left-gb.left)>.01||std::abs(ga.top-gb.top)>.01||std::abs(ga.right-gb.right)>.01||std::abs(ga.bottom-gb.bottom)>.01)throw std::runtime_error("Source-preserving preview page geometry changed");artwork.push_back(sourceArtworkInventory(original.value,a.value));}
   std::ifstream stream(shard.at("blocksPath").get<std::string>());if(!stream)throw std::runtime_error("A completed page snapshot is missing");json snapshot;stream>>snapshot;
   if(!snapshot.is_object()||!snapshot.contains("sourcePageIndex")||!jsonIntegerEquals(snapshot["sourcePageIndex"],index)||!snapshot.contains("blocks")||!snapshot["blocks"].is_array())throw std::runtime_error("A translated page snapshot has the wrong source identity");
   snapshot["layoutVersion"]=3;snapshot["mode"]="preserve-source";if(!chinese&&!cjk.empty()&&sourceTargetNeedsFallback(snapshot,latin.bytes))chinese=std::make_unique<ReadableFont>(original.value,cjk);const auto result=preserveSourcePage(original.value,index,snapshot,latin,chinese.get(),generation,artwork.back());{Page check(preview.value,0);verifySourceArtwork(artwork.back(),preview.value,check.value);}partial=partial||result.at("partial").get<bool>();partialBlocks+=result.at("partialBlocks").get<size_t>();
   ranges.push_back({{"sourcePageIndex",index},{"firstOutputPage",index},{"outputPageCount",1}});pageMap.push_back({{"outputPageIndex",index},{"sourcePageIndex",index},{"sourceOutputPageIndex",0}});
  }
  const std::string staged=destination+".preserving";
  try{checkExportGeneration(generation);save(original.value,staged);{Doc check(staged);if(ops::FPDF_GetPageCount(check.value)!=count)throw std::runtime_error("Source-preserving document page count changed");for(int index=0;index<count;++index){checkExportGeneration(generation);Page page(check.value,index);verifySourceArtwork(artwork[index],check.value,page.value);}}checkExportGeneration(generation);if(ops::rename(staged.c_str(),destination.c_str())!=0)throw std::runtime_error("Cannot finalize source-preserving document");}
  catch(...){std::remove(staged.c_str());std::remove((staged+".part").c_str());throw;}
  return json{{"path",destination},{"layoutVersion",3},{"mode","preserve-source"},{"pageCount",count},{"sourcePageRanges",ranges},{"pages",pageMap},{"partial",partial},{"partialBlocks",partialBlocks}}.dump();
 }
 if(input.is_array()){
  if(input.empty())throw std::runtime_error("No completed pages to export");
  Doc assembled(ops::FPDF_CreateNewDocument());if(!assembled.value)throw std::runtime_error("Cannot create PDF");
  int outputCount=0;
  for(const auto& value:input){if(!value.is_string()||value.get<std::string>().empty())throw std::runtime_error("Invalid legacy page path");Doc shard(value.get<std::string>());int firstPage=0;
   if(!ops::FPDF_ImportPagesByIndex(assembled.value,shard.value,&firstPage,1,outputCount))throw std::runtime_error("Cannot assemble translated pages");
   if(outputCount==std::numeric_limits<int>::max())throw std::runtime_error("Export page count overflow");++outputCount;
  }
  if(ops::FPDF_GetPageCount(assembled.value)!=outputCount)throw std::runtime_error("Export page count mismatch");
  const std::string staged=destination+".merge";
  try{save(assembled.value,staged);Doc check(staged);if(ops::FPDF_GetPageCount(check.value)!=outputCount)throw std::runtime_error("Export page count mismatch");if(ops::rename(staged.c_str(),destination.c_str())!=0)throw std::runtime_error("Cannot finalize PDF");}
  catch(...){std::remove(staged.c_str());std::remove((staged+".part").c_str());throw;}
  return json{{"path",destination},{"pageCount",outputCount}}.dump();
 }
 if(!input.is_object()||!input.contains("layoutVersion")||!input["layoutVersion"].is_number_integer()||input["layoutVersion"].get<int>()!=2||!input.contains("shards")||!input["shards"].is_array()||input["shards"].empty())throw std::runtime_error("Invalid page shard envelope");
 struct Shard { std::string path;int sourcePageIndex;int expectedPageCount; };
 std::vector<Shard> shards;shards.reserve(input["shards"].size());long long totalExpected=0;size_t expectedIndex=0;
 for(const auto& value:input["shards"]){
  if(!value.is_object()||!value.contains("path")||!value["path"].is_string()||value["path"].get<std::string>().empty()||!value.contains("sourcePageIndex")||!value["sourcePageIndex"].is_number_integer()||!value.contains("expectedPageCount")||!value["expectedPageCount"].is_number_integer())throw std::runtime_error("Invalid page shard descriptor");
  long long sourceIndex=0,count=0;try{sourceIndex=value["sourcePageIndex"].get<long long>();count=value["expectedPageCount"].get<long long>();}catch(...){throw std::runtime_error("Invalid page shard descriptor");}
  if(sourceIndex<0||static_cast<unsigned long long>(sourceIndex)!=expectedIndex)throw std::runtime_error("Page shard source indices must be sequential from zero");
  if(count<=0||count>std::numeric_limits<int>::max())throw std::runtime_error("Expected page count must be positive and bounded");
  if(count>std::numeric_limits<int>::max()-totalExpected)throw std::runtime_error("Export page count overflow");
  totalExpected+=count;shards.push_back({value["path"].get<std::string>(),static_cast<int>(sourceIndex),static_cast<int>(count)});++expectedIndex;
 }
 Doc assembled(ops::FPDF_CreateNewDocument());if(!assembled.value)throw std::runtime_error("Cannot create PDF");
 json ranges=json::array(),pageMap=json::array();int firstOutput=0;
 for(const auto& shard:shards){Doc source(shard.path);const int actualCount=ops::FPDF_GetPageCount(source.value);
  if(actualCount!=shard.expectedPageCount)throw std::runtime_error("Source shard page count mismatch");
  std::vector<int> pageIndices(static_cast<size_t>(actualCount));for(int i=0;i<actualCount;++i)pageIndices[static_cast<size_t>(i)]=i;
  if(!ops::FPDF_ImportPagesByIndex(assembled.value,source.value,pageIndices.data(),static_cast<unsigned long>(pageIndices.size()),firstOutput))throw std::runtime_error("Cannot assemble translated pages");
  ranges.push_back({{"sourcePageIndex",shard.sourcePageIndex},{"firstOutputPage",firstOutput},{"outputPageCount",actualCount}});
  for(int sourceOutput=0;sourceOutput<actualCount;++sourceOutput)pageMap.push_back({{"outputPageIndex",firstOutput+sourceOutput},{"sourcePageIndex",shard.sourcePageIndex},{"sourceOutputPageIndex",sourceOutput}});
  firstOutput+=actualCount;
 }
 if(firstOutput!=totalExpected||ops::FPDF_GetPageCount(assembled.value)!=firstOutput)throw std::runtime_error("Export page count mismatch");
 const std::string staged=destination+".merge";
 try{save(assembled.value,staged);Doc check(staged);if(ops::FPDF_GetPageCount(check.value)!=firstOutput)throw std::runtime_error("Export page count mismatch");if(ops::rename(staged.c_str(),destination.c_str())!=0)throw std::runtime_error("Cannot finalize PDF");}
 catch(...){std::remove(staged.c_str());std::remove((staged+".part").c_str());throw;}
 return json{{"path",destination},{"layoutVersion",2},{"pageCount",firstOutput},{"sourcePageRanges",ranges},{"pages",pageMap}}.dump();
}catch(const std::exception&e){return failure(e);}}
}
