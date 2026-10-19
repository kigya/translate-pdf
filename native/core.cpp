#include "core.hpp"
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
namespace {
std::once_flag pdfInit, llamaInit;
std::mutex pdfMutex, modelMutex;
std::atomic<bool> stopped{false};
llama_model* model = nullptr;
llama_context* ctx = nullptr;
std::string loadedPath;
std::string loadedProfile;
std::u16string wide(const std::string& s) { return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.from_bytes(s); }
std::string utf8(const std::u16string& s) { return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(s); }
void initPdf() { std::call_once(pdfInit, [] { FPDF_InitLibrary(); }); }
struct Doc {
 FPDF_DOCUMENT value;
 explicit Doc(const std::string& p):value(FPDF_LoadDocument(p.c_str(),nullptr)) { if(!value) throw std::runtime_error("Cannot read PDF (damaged or password protected), code " + std::to_string(FPDF_GetLastError())); }
 explicit Doc(FPDF_DOCUMENT d):value(d) { if(!value) throw std::runtime_error("Cannot create PDF"); }
 ~Doc(){FPDF_CloseDocument(value);}
};
struct Page {
 FPDF_PAGE value;
 Page(FPDF_DOCUMENT d,int i):value(FPDF_LoadPage(d,i)) { if(!value) throw std::runtime_error("Cannot read page"); }
 ~Page(){FPDF_ClosePage(value);}
};
struct TextPage { FPDF_TEXTPAGE value; explicit TextPage(FPDF_PAGE p):value(FPDFText_LoadPage(p)) {} ~TextPage(){if(value)FPDFText_ClosePage(value);} };
struct FileWriter:FPDF_FILEWRITE {
 FILE* out;
 explicit FileWriter(FILE* f):out(f) { version=1; WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long n)->int {return fwrite(data,1,n,static_cast<FileWriter*>(base)->out)==n;}; }
};
void save(FPDF_DOCUMENT d,const std::string& path) {
 const auto temp=path+".part";
 FILE* f=fopen(temp.c_str(),"wb"); if(!f) throw std::runtime_error("Cannot create output: check available storage");
 FileWriter writer(f); bool ok=FPDF_SaveAsCopy(d,&writer,FPDF_NO_INCREMENTAL);
 if(fflush(f)!=0||fsync(fileno(f))!=0)ok=false;
 if(fclose(f)!=0)ok=false;
 if(!ok){std::remove(temp.c_str());throw std::runtime_error("Failed writing PDF; checkpoints retained");}
 if(std::rename(temp.c_str(),path.c_str())!=0)throw std::runtime_error("Cannot finalize PDF");
}
std::string failure(const std::exception& e){return json{{"error",e.what()}}.dump();}
std::string objectText(FPDF_PAGEOBJECT obj,FPDF_TEXTPAGE tp) {
 auto bytes=FPDFTextObj_GetText(obj,tp,nullptr,0); if(bytes<2)return {};
 std::u16string s(bytes/2,u'\0'); FPDFTextObj_GetText(obj,tp,reinterpret_cast<unsigned short*>(s.data()),bytes);
 while(!s.empty()&&(s.back()==0||s.back()==u'\r'||s.back()==u'\n'))s.pop_back();
 return utf8(s);
}
float textWidth(FPDF_FONT font,const std::u16string& text,float size) {
 float total=0;
 for(size_t i=0;i<text.size();++i){unsigned int c=text[i];if(c>=0xd800&&c<=0xdbff&&i+1<text.size()){c=0x10000+((c-0xd800)<<10)+(text[++i]-0xdc00);} float width=0; if(!FPDFFont_GetGlyphWidth(font,c,size,&width))throw std::runtime_error("Font lacks a required glyph");total+=width;}
 return total;
}
std::vector<std::u16string> lines(FPDF_FONT font,const std::string& text,float size,float width) {
 std::u16string all=wide(text),line,word;std::vector<std::u16string> out;
 auto append=[&](){ if(word.empty())return;auto candidate=line+(line.empty()?u"":u" ")+word; if(textWidth(font,candidate,size)>width&&!line.empty()){out.push_back(line);line=word;}else line=candidate; if(textWidth(font,line,size)>width)throw std::runtime_error("Unbreakable text exceeds its original area");word.clear();};
 for(char16_t c:all){if(c==u' '||c==u'\n'||c==u'\r'||c==u'\t'){append();if(c==u'\n'){out.push_back(line);line.clear();}}else word+=c;}
 append();if(!line.empty())out.push_back(line);return out;
}
void annotate(FPDF_PAGE page,const json& block,double height,const std::string& text){
 auto a=FPDFPage_CreateAnnot(page,FPDF_ANNOT_TEXT);if(!a)throw std::runtime_error("Cannot attach translated text");
 // A compact, visible comment marker keeps the original readable. A block-sized
 // annotation expands its icon over the source in some viewers; NOVIEW hides it.
 const float width=FPDF_GetPageWidthF(page),size=std::min(16.f,std::min(width,static_cast<float>(height)));
 const float left=std::clamp(block.value("left",0.f)-size-4.f,0.f,width-size);
 const float top=std::clamp(static_cast<float>(height-block.value("top",0.f)),size,static_cast<float>(height));
 FS_RECTF r{left,top,left+size,top-size};
 bool ok=FPDFAnnot_SetRect(a,&r)&&FPDFAnnot_SetFlags(a,0);
 FPDFAnnot_SetColor(a,FPDFANNOT_COLORTYPE_Color,198,40,40,255);
 auto name=wide("Comment");FPDFAnnot_SetStringValue(a,"Name",reinterpret_cast<const unsigned short*>(name.c_str()));
 auto s=wide(text);ok=FPDFAnnot_SetStringValue(a,"Contents",reinterpret_cast<const unsigned short*>(s.c_str()))&&ok;
 auto title=wide("Translation - placement needs review");FPDFAnnot_SetStringValue(a,"T",reinterpret_cast<const unsigned short*>(title.c_str()));FPDFPage_CloseAnnot(a);
 if(!ok)throw std::runtime_error("Cannot save translation annotation");
}
void unload(){if(ctx){llama_free(ctx);ctx=nullptr;}if(model){llama_model_free(model);model=nullptr;}loadedPath.clear();loadedProfile.clear();}
bool abortInference(void*){return stopped.load();}

struct TextRun {
 json block;
 double baseline;
 FPDF_FONT font;
 bool horizontal;
};

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
std::string inspect(const std::string& path){try{std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);return json{{"pageCount",FPDF_GetPageCount(d.value)},{"encrypted",false}}.dump();}catch(const std::exception&e){return failure(e);}}
std::string readPage(const std::string& path,int index){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);Page p(d.value,index);TextPage tp(p.value);
 const double w=FPDF_GetPageWidthF(p.value),h=FPDF_GetPageHeightF(p.value);std::vector<TextRun> runs;
 for(int i=0;i<FPDFPage_CountObjects(p.value);i++){
  auto obj=FPDFPage_GetObject(p.value,i);if(FPDFPageObj_GetType(obj)!=FPDF_PAGEOBJ_TEXT)continue;
  const auto text=objectText(obj,tp.value);if(text.empty()||text.find_first_not_of(" \r\n\t")==std::string::npos)continue;
  float l=0,b=0,r=0,t=0,size=10; if(!FPDFPageObj_GetBounds(obj,&l,&b,&r,&t))continue;FPDFTextObj_GetFontSize(obj,&size);
  FS_MATRIX matrix{};const bool transformed=FPDFPageObj_GetMatrix(obj,&matrix);
  if(transformed)size*=std::hypot(matrix.c,matrix.d);
  if(!std::isfinite(size)||size<=0)size=std::max(1.f,t-b);
  const bool horizontal=transformed&&matrix.a>0&&matrix.d>0&&std::abs(matrix.b)<0.001&&std::abs(matrix.c)<0.001;
  json block={{"id","p"+std::to_string(index)+"-o"+std::to_string(i)},{"text",text},{"sourceText",text},{"left",l},{"top",h-t},{"right",r},{"bottom",h-b},{"fontSize",size},{"source","native"},{"objectIndex",i},{"objectIndices",json::array({i})}};
  runs.push_back({std::move(block),transformed?h-matrix.f:h-t,FPDFTextObj_GetFont(obj),horizontal});
 }
 const auto merged=groupText(std::move(runs));
 return json{{"index",index},{"width",w},{"height",h},{"rotation",FPDFPage_GetRotation(p.value)},{"blocks",merged}}.dump();
}catch(const std::exception&e){return failure(e);}}
Raster render(const std::string& path,int index,int width){
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc d(path);Page p(d.value,index);
 width=std::clamp(width,128,1800);const double pw=FPDF_GetPageWidthF(p.value),ph=FPDF_GetPageHeightF(p.value);
 if(pw<=0||ph<=0)throw std::runtime_error("Invalid page dimensions");
 int height=static_cast<int>(std::ceil(width*ph/pw));if(height>4096){width=std::max(1,static_cast<int>(width*(4096.0/height)));height=4096;}
 Raster r{width,height,std::vector<uint8_t>(static_cast<size_t>(width)*height*4)};
 auto bmp=FPDFBitmap_CreateEx(width,height,FPDFBitmap_BGRA,r.rgba.data(),width*4);if(!bmp)throw std::runtime_error("Cannot allocate page bitmap");
 FPDFBitmap_FillRect(bmp,0,0,width,height,0xffffffff);FPDF_RenderPageBitmap(bmp,p.value,0,0,width,height,0,FPDF_ANNOT);FPDFBitmap_Destroy(bmp);return r;
}
std::string loadModel(const std::string& path,int context,int threads,const std::string& profile){try{
 std::lock_guard<std::mutex> lock(modelMutex);std::call_once(llamaInit,[]{llama_log_set([](ggml_log_level,const char*,void*){},nullptr);llama_backend_init();});stopped=false;
 if(profile!="hy-mt2"&&profile!="translategemma")throw std::runtime_error("Unknown translation profile");
 const int requestedContext=std::clamp(context,1024,4096),requestedThreads=std::clamp(threads,1,4);
 if(model&&loadedPath==path&&loadedProfile==profile&&ctx&&llama_n_ctx(ctx)==static_cast<uint32_t>(requestedContext)&&llama_n_threads(ctx)==requestedThreads)return json{{"loaded",true},{"context",llama_n_ctx(ctx)},{"threads",llama_n_threads(ctx)}}.dump();unload();
 auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;mp.check_tensors=true;
 model=llama_model_load_from_file(path.c_str(),mp);if(!model)throw std::runtime_error("Model cannot be loaded by this runtime");
 if(llama_model_has_encoder(model)){unload();throw std::runtime_error("Model architecture does not match its pinned translation profile");}
 auto cp=llama_context_default_params();cp.n_ctx=std::clamp(context,1024,4096);cp.n_batch=128;cp.n_ubatch=64;cp.n_threads=std::clamp(threads,1,4);cp.n_threads_batch=cp.n_threads;cp.offload_kqv=false;cp.abort_callback=abortInference;cp.abort_callback_data=nullptr;
 ctx=llama_init_from_model(model,cp);if(!ctx){unload();throw std::runtime_error("Insufficient resources for the model context");}loadedPath=path;loadedProfile=profile;
 return json{{"loaded",true},{"context",llama_n_ctx(ctx)},{"threads",llama_n_threads(ctx)},{"modelBytes",llama_model_size(model)},{"templateAvailable",llama_model_chat_template(model,nullptr)!=nullptr}}.dump();
}catch(const std::exception&e){return failure(e);}}
std::string translate(const std::string& text,const std::string& glossary,const std::string& context){try{
 std::lock_guard<std::mutex> lock(modelMutex);if(!ctx||!model)throw std::runtime_error("Model is not loaded");if(stopped)throw std::runtime_error("Canceled");
 if(!std::regex_search(text,std::regex("[A-Za-z]")))return json{{"text",text},{"tokens",0},{"seconds",0},{"skipped",true}}.dump();
 if(loadedProfile!="hy-mt2"&&!glossary.empty())throw std::runtime_error("This model uses direct translation without a glossary. Choose a glossary-capable model to apply saved terms.");
 std::string instruction;
 if(!glossary.empty())instruction+="Reference the following translations:\n"+glossary+"\n\n";
 if(!context.empty())instruction+="[Background Information]\n"+context+"\n";
 instruction+="Translate the following text into Russian. Note that you should only output the translated result without any additional explanation:\n\n"+text;
 const char* tmpl=llama_model_chat_template(model,nullptr);std::string prompt;
 if(loadedProfile=="translategemma"){
  // Exact EN/RU specialization of the pinned model's embedded structured
  // template. Context and glossary are never added to the text being translated.
  prompt="<bos><start_of_turn>user\nYou are a professional English (en) to Russian (ru) translator. Your goal is to accurately convey the meaning and nuances of the original English text while adhering to Russian grammar, vocabulary, and cultural sensitivities.\nProduce only the Russian translation, without any additional explanations or commentary. Please translate the following English text into Russian:\n\n\n"+text+"<end_of_turn>\n<start_of_turn>model\n";
 }else {
  if(!tmpl)throw std::runtime_error("GGUF lacks a verified chat template");
  llama_chat_message message{"user",instruction.c_str()};std::vector<char> formatted(instruction.size()*3+8192);
  int n=llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),formatted.size());if(n<0)throw std::runtime_error("Unsupported GGUF chat template");if(n>=static_cast<int>(formatted.size())){formatted.resize(n+1);n=llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),formatted.size());if(n<0||n>=static_cast<int>(formatted.size()))throw std::runtime_error("Cannot format translation prompt");}
  prompt.assign(formatted.data(),n);
 }
 const auto vocab=llama_model_get_vocab(model);
 int plainCount=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,false,true);if(plainCount<=0)throw std::runtime_error("Translation prompt contains no tokens");
 std::vector<llama_token> plain(plainCount);llama_tokenize(vocab,prompt.data(),prompt.size(),plain.data(),plainCount,false,true);
 // Some templates already emit BOS. Asking the tokenizer to add it again
 // changes the actual request and can degrade translation.
 const bool addSpecial=plain.front()!=llama_vocab_bos(vocab);
 int count=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,addSpecial,true);
 // The pinned community IQ files declare EOS=3, while their Hunyuan chat
 // template ends assistant messages with this explicit token (official Q4's
 // EOS). Honour the template boundary without changing the verified GGUF.
 llama_token assistantEnd=LLAMA_TOKEN_NULL;
 if(loadedProfile=="hy-mt2"&&tmpl&&std::string(tmpl).find("<｜hy_Assistant｜>")!=std::string::npos&&std::string(tmpl).find("<｜hy_place▁holder▁no▁2｜>")!=std::string::npos){
  const std::string end="<｜hy_place▁holder▁no▁2｜>";llama_token candidate;
  if(llama_tokenize(vocab,end.data(),static_cast<int>(end.size()),&candidate,1,false,true)==1)assistantEnd=candidate;
 }
 if(loadedProfile=="hy-mt2"&&tmpl&&std::string(tmpl).find("<|eos|>")!=std::string::npos){
  const std::string end="<|eos|>";llama_token candidate;
  if(llama_tokenize(vocab,end.data(),static_cast<int>(end.size()),&candidate,1,false,true)==1)assistantEnd=candidate;
 }
 if(count<=0||count>static_cast<int>(llama_n_ctx(ctx))-384)throw std::runtime_error("Block exceeds model context; split it without truncation");
 std::vector<llama_token> tokens(count);llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),count,addSpecial,true);llama_memory_clear(llama_get_memory(ctx),true);
 auto start=std::chrono::steady_clock::now();
 for(int pos=0;pos<count;pos+=128){if(stopped)throw std::runtime_error("Canceled");auto batch=llama_batch_get_one(tokens.data()+pos,std::min(128,count-pos));if(llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Inference failed while reading input");}
 auto sampler=llama_sampler_chain_init(llama_sampler_chain_default_params());
 if(loadedProfile=="hy-mt2"){
 llama_sampler_chain_add(sampler,llama_sampler_init_penalties(llama_vocab_n_tokens(vocab),64,1.05f,0.0f,0.0f));
 llama_sampler_chain_add(sampler,llama_sampler_init_top_k(20));
 llama_sampler_chain_add(sampler,llama_sampler_init_top_p(0.6f,1));
 llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.7f));
 llama_sampler_chain_add(sampler,llama_sampler_init_dist(42));
 }else llama_sampler_chain_add(sampler,llama_sampler_init_greedy());
 std::string output;int generated=0;bool ended=false;
 int sourceTokens=-llama_tokenize(vocab,text.data(),static_cast<int>(text.size()),nullptr,0,false,false);
 int limit=std::min({static_cast<int>(llama_n_ctx(ctx))-count-4,std::max(96,sourceTokens*4+32),1024});
 try{for(int i=0;i<limit;i++){
  if(stopped)throw std::runtime_error("Canceled");auto token=llama_sampler_sample(sampler,ctx,-1);if(assistantEnd==LLAMA_TOKEN_NULL?llama_vocab_is_eog(vocab,token):token==assistantEnd){ended=true;break;}
  char buf[512];int len=llama_token_to_piece(vocab,token,buf,sizeof(buf),0,false);if(len<0)throw std::runtime_error("Cannot decode model token");output.append(buf,len);generated++;
  auto batch=llama_batch_get_one(&token,1);if(llama_decode(ctx,batch)!=0)throw std::runtime_error(stopped?"Canceled":"Inference failed while generating output");
 }}catch(...){llama_sampler_free(sampler);throw;}llama_sampler_free(sampler);
 if(!ended)throw std::runtime_error("Translation exceeded context; output was not committed");
 if(output.empty())throw std::runtime_error("Model produced no translation");
 if(!preservesCriticalTokens(text,output))throw std::runtime_error("Translation changed a protected number, code or negation; original retained for review");
 double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
 return json{{"text",output},{"tokens",generated},{"seconds",seconds},{"tokensPerSecond",generated/std::max(seconds,0.001)}}.dump();
}catch(const std::exception&e){return failure(e);}}
void releaseModel(){std::lock_guard<std::mutex> lock(modelMutex);unload();}
void cancel(){stopped=true;}
std::string exportPage(const std::string& source,int index,const std::string& blocksJson,const std::string& destination,const std::string& fontPath){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();Doc src(source);Doc dest(FPDF_CreateNewDocument());int ix=index;if(!FPDF_ImportPagesByIndex(dest.value,src.value,&ix,1,0))throw std::runtime_error("Cannot import original page");Page page(dest.value,0);
 std::ifstream f(fontPath,std::ios::binary);std::vector<unsigned char> data((std::istreambuf_iterator<char>(f)),{});if(data.empty())throw std::runtime_error("Cyrillic export font is missing");
 FPDF_FONT font=FPDFText_LoadFont(dest.value,data.data(),data.size(),FPDF_FONT_TRUETYPE,true);if(!font)throw std::runtime_error("Cannot embed Cyrillic font");
 const auto input=json::parse(blocksJson);const auto blocks=input.is_array()?input:input.at("blocks");double height=FPDF_GetPageHeightF(page.value);int partial=0;json issues=json::array();std::vector<FPDF_PAGEOBJECT> remove;
 const auto issue=[&](const json& block,const std::string& reason,const std::string& message){partial++;issues.push_back(json{{"blockId",block.value("id",std::string{})},{"reason",reason},{"message",message}});};
 try{for(const auto& block:blocks){
  if(block.contains("translatedText")&&block["translatedText"].is_null()){issue(block,"missing_translation","No complete translation is available; the original text was retained.");continue;}
  std::string translated=block.value("translatedText",block.value("translation",std::string{}));if(translated.empty()){issue(block,"missing_translation","No complete translation is available; the original text was retained.");continue;}
  double width=block.value("right",0.)-block.value("left",0.),boxHeight=block.value("bottom",0.)-block.value("top",0.);float original=block.value("fontSize",10.f),minimum=std::max(6.f,0.8f*original),size=std::max(minimum,original);std::vector<std::u16string> wrapped;bool fits=false;
  if(block.value("source",std::string("native"))=="native"&&FPDFPage_GetRotation(page.value)==0&&width>0&&boxHeight>0&&block.value("status",std::string("translated"))!="partial"){
   for(;size>=minimum-0.01f;size-=0.5f){try{wrapped=lines(font,translated,size,width);fits=!wrapped.empty()&&wrapped.size()*size*1.15<=boxHeight+size*0.35;}catch(...){fits=false;}if(fits)break;}
  }
  if(!fits){annotate(page.value,block,height,translated);issue(block,"layout_fallback","The translation could not be placed safely at a readable size. The original remains with the full translation attached for review.");continue;}
  json indexes=block.value("objectIndices",json::array());if(indexes.empty()&&block.contains("objectIndex")&&block["objectIndex"].is_number_integer())indexes.push_back(block["objectIndex"]);
  bool safe=!indexes.empty();for(const auto& n:indexes){int oi=n.get<int>();auto obj=FPDFPage_GetObject(page.value,oi);if(!obj||FPDFPageObj_GetType(obj)!=FPDF_PAGEOBJ_TEXT){safe=false;break;}}
  if(!safe){annotate(page.value,block,height,translated);issue(block,"unsafe_text_object","The original text object could not be replaced safely. The translation is attached for review.");continue;}
  for(const auto& n:indexes)remove.push_back(FPDFPage_GetObject(page.value,n.get<int>()));
  int row=0;for(const auto& line:wrapped){auto obj=FPDFPageObj_CreateTextObj(dest.value,font,size);if(!obj)throw std::runtime_error("Cannot create translated text");
   if(!FPDFText_SetText(obj,reinterpret_cast<const unsigned short*>(line.c_str()))){FPDFPageObj_Destroy(obj);throw std::runtime_error("Cannot write translated glyphs");}
   FPDFPageObj_SetFillColor(obj,25,25,28,255);FPDFPageObj_Transform(obj,1,0,0,1,block.value("left",0.),height-block.value("top",0.)-size-row*size*1.15);FPDFPage_InsertObject(page.value,obj);row++;
  }
 }
 std::sort(remove.begin(),remove.end());remove.erase(std::unique(remove.begin(),remove.end()),remove.end());for(auto obj:remove){if(!FPDFPage_RemoveObject(page.value,obj))throw std::runtime_error("Cannot replace source text safely");FPDFPageObj_Destroy(obj);}
 if(!FPDFPage_GenerateContent(page.value))throw std::runtime_error("Cannot generate translated page content");save(dest.value,destination);
 }catch(...){FPDFFont_Close(font);throw;}FPDFFont_Close(font);
 return json{{"path",destination},{"partial",partial>0},{"partialBlocks",partial},{"issues",issues},{"pageCount",1}}.dump();
}catch(const std::exception&e){return failure(e);}}
std::string mergePdf(const std::string& pagesJson,const std::string& destination){try{
 std::lock_guard<std::mutex> lock(pdfMutex);initPdf();auto paths=json::parse(pagesJson);if(!paths.is_array()||paths.empty())throw std::runtime_error("No completed pages to export");Doc d(FPDF_CreateNewDocument());int i=0;for(const auto& path:paths){Doc p(path.get<std::string>());int index=0;if(!FPDF_ImportPagesByIndex(d.value,p.value,&index,1,i++))throw std::runtime_error("Cannot assemble translated pages");}save(d.value,destination);Doc check(destination);if(FPDF_GetPageCount(check.value)!=i)throw std::runtime_error("Export page count mismatch");return json{{"path",destination},{"pageCount",i}}.dump();
}catch(const std::exception&e){return failure(e);}}
}
