#include "core.hpp"
#include "core_detail.hpp"
#include "core_operations.hpp"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <functional>
#include <cstring>
#include <cmath>
#include <utility>
#include <map>
#include <set>
#include <array>
#include <tuple>
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
int readableObjectTypeCalls=0;
FPDF_PAGEOBJECT readableNestedText=nullptr;
FPDF_PAGEOBJECT targetCharacterObject=nullptr;
FPDF_PAGEOBJECT targetPlacedTextObject=nullptr;
bool targetCharacterInjected=false,targetCharacterNonfinite=false,targetCharacterZeroArea=false;
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
void pageContextContracts(){
 const auto path=directory+"/page-context.pdf";auto document=FPDF_CreateNewDocument();auto page=FPDFPage_New(document,0,612,792);auto face=FPDFText_LoadStandardFont(document,"Helvetica");
 auto add=[&](const std::u16string& value,float size,float x,float y){auto object=FPDFPageObj_CreateTextObj(document,face,size);FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(value.c_str()));FPDFPageObj_Transform(object,1,0,0,1,x,y);FPDFPage_InsertObject(page,object);};
 add(u"SECTION OVERVIEW",24,60,744);add(u"42",24,570,750);add(u"Ordinary body copy",12,60,680);add(u"Second ordinary paragraph",12,60,650);add(u"Third ordinary paragraph",12,60,630);add(std::u16string(520,u'x'),24,60,580);
 FPDFPage_GenerateContent(page);FPDF_ClosePage(page);
 struct Writer:FPDF_FILEWRITE{FILE* file;explicit Writer(FILE* f):file(f){version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length){return static_cast<int>(fwrite(data,1,length,static_cast<Writer*>(base)->file)==length);};}};
 auto file=fopen(path.c_str(),"wb");Writer writer(file);expect(FPDF_SaveAsCopy(document,&writer,FPDF_NO_INCREMENTAL),"context fixture PDF save");fclose(file);FPDFFont_Close(face);FPDF_CloseDocument(document);
 const auto parsed=json::parse(translatepdf::readPage(path,0));expect(!parsed.contains("error"),"context fixture page reads");
 const auto context=parsed.at("context").get<std::string>();expect(context.find("SECTION OVERVIEW")!=std::string::npos,"meaningful page heading enters context");
 expect(context.find("42")==std::string::npos,"numeric page furniture is omitted from context");
 expect(context.size()==400,"page context reaches but does not exceed the 400-byte boundary");
 expect(!context.empty()&&context.back()=='x',"bounded context ends on a complete ASCII character");
}
std::string croppedFixture(int rotation,int variant=0){
 const auto path=directory+"/crop-"+std::to_string(rotation)+"-"+std::to_string(variant)+".pdf";
 auto document=FPDF_CreateNewDocument();auto page=FPDFPage_New(document,0,612,792);auto face=FPDFText_LoadStandardFont(document,"Helvetica");
 if(variant==1){FPDFPage_SetMediaBox(page,-100,-100,700,900);FPDFPage_SetCropBox(page,-50,-30,700,850);}
 else if(variant==2)FPDFPage_SetCropBox(page,-50,-50,700,850);
 else FPDFPage_SetCropBox(page,35.8681f,31.9327f,574.873f,393.562f);
 FPDFPage_SetRotation(page,rotation);
 auto add=[&](const char16_t* value,float x,float y){auto object=FPDFPageObj_CreateTextObj(document,face,18);FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(value));FPDFPageObj_Transform(object,1,0,0,1,x,y);FPDFPage_InsertObject(page,object);};
 add(u"Visible",80,300);add(u"Clipped",20,340);add(u"After",78.5f,340);add(u"Off crop", -220,300);add(u"Safe",492,260);add(u"Edge",539,260);add(u"Off bottom",100,-120);add(u"Bottom",300,29);add(u"Top",300,392);
 FPDFPage_GenerateContent(page);FPDF_ClosePage(page);
 struct Writer:FPDF_FILEWRITE{FILE* file;explicit Writer(FILE* f):file(f){version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length){return static_cast<int>(fwrite(data,1,length,static_cast<Writer*>(base)->file)==length);};}};
 auto file=fopen(path.c_str(),"wb");Writer writer(file);expect(FPDF_SaveAsCopy(document,&writer,FPDF_NO_INCREMENTAL),"cropped fixture PDF save");fclose(file);FPDFFont_Close(face);FPDF_CloseDocument(document);return path;
}
void cropGeometryContracts(){
 const double left=35.8681,bottom=31.9327,right=574.873,top=393.562;
 for(int rotation=0;rotation<4;++rotation){
  const auto path=croppedFixture(rotation);auto parsed=json::parse(translatepdf::readPage(path,0));
  expect(!parsed.contains("error")&&parsed["coordinateSpaceVersion"]==2,"cropped page uses canonical v2 coordinates");
  const double expectedWidth=rotation%2?top-bottom:right-left,expectedHeight=rotation%2?right-left:top-bottom;
  expect(std::abs(parsed["width"].get<double>()-expectedWidth)<0.02&&std::abs(parsed["height"].get<double>()-expectedHeight)<0.02,"view dimensions equal exact crop intersection");
  bool foundVisible=false,foundClipped=false,foundOff=false,foundOffBottom=false,foundBottom=false,foundTop=false,foundClippedGroup=false,foundLeftClippedGroup=false;
  for(const auto& block:parsed["blocks"]){
   const auto text=block["text"].get<std::string>();
   expect(block["coordinateSpaceVersion"]==2,"each returned native group carries v2 marker");
   if(text.find("Visible")!=std::string::npos){foundVisible=true;expect(block["coordinateSpaceVersion"]==2,"native block carries v2 marker");}
   if(text.find("Clipped")!=std::string::npos){foundClipped=true;expect(block["partialCrop"]==true,"partially cropped object is marked for review");}
   if(rotation==0&&text.find("Clipped")!=std::string::npos){foundLeftClippedGroup=true;expect(block["partialCrop"]==true&&block["objectIndices"].size()==2,"clipped first fragment propagates through a merged line");}
   if(rotation==0&&text.find("Safe")!=std::string::npos){foundClippedGroup=true;expect(block["partialCrop"]==true&&block["coordinateSpaceVersion"]==2&&block["objectIndices"].size()==2,"merged runs propagate partial clipping and coordinate version text="+text+" partial="+block["partialCrop"].dump()+" indices="+block["objectIndices"].dump());}
   if(text.find("Off crop")!=std::string::npos)foundOff=true;
   if(text.find("Off bottom")!=std::string::npos)foundOffBottom=true;
   if(text.find("Bottom")!=std::string::npos)foundBottom=block["partialCrop"]==true;
   if(text.find("Top")!=std::string::npos)foundTop=block["partialCrop"]==true;
  }
  expect(foundVisible&&foundClipped&&foundBottom&&foundTop&&(rotation==0?(foundClippedGroup&&foundLeftClippedGroup):(!foundClippedGroup&&!foundLeftClippedGroup))&&!foundOff&&!foundOffBottom,"visible content retained; four-sided clipping marked; wholly off-crop content excluded without rotated run merging");
  auto document=FPDF_LoadDocument(path.c_str(),nullptr);auto page=FPDF_LoadPage(document,0);int dx=0,dy=0;
  expect(FPDF_PageToDevice(page,0,0,1000,1000,0,100,200,&dx,&dy),"PDFium device coordinate oracle works");
  double x=0,y=0;switch(rotation){case 1:x=200-bottom;y=100-left;break;case 2:x=right-100;y=200-bottom;break;case 3:x=top-200;y=right-100;break;default:x=100-left;y=top-200;}
  const int expectedX=static_cast<int>(std::lround(x/expectedWidth*1000)),expectedY=static_cast<int>(std::lround(y/expectedHeight*1000));
  expect(std::abs(dx-expectedX)<=2&&std::abs(dy-expectedY)<=2,"canonical rotation mapping matches PDFium PageToDevice rotation="+std::to_string(rotation)+" actual="+std::to_string(dx)+","+std::to_string(dy)+" expected="+std::to_string(expectedX)+","+std::to_string(expectedY));
  for(const auto& block:parsed["blocks"]){
   const auto text=block["text"].get<std::string>();if(text.find("Visible")==std::string::npos&&text.find("Clipped")==std::string::npos)continue;
   auto point=[&](double px,double py){switch(rotation){case 1:return std::pair<double,double>{py-bottom,px-left};case 2:return std::pair<double,double>{right-px,py-bottom};case 3:return std::pair<double,double>{top-py,right-px};default:return std::pair<double,double>{px-left,top-py};}};
   bool initialized=false;double x0=0,x1=0,y0=0,y1=0;
   for(const auto& index:block["objectIndices"]){auto object=FPDFPage_GetObject(page,index.get<int>());float l=0,b=0,r=0,t=0;
    expect(FPDFPageObj_GetBounds(object,&l,&b,&r,&t),"fixture text bounds available");
    const double cl=std::max<double>(l,left),cb=std::max<double>(b,bottom),cr=std::min<double>(r,right),ct=std::min<double>(t,top);
    const std::pair<double,double> corners[]={point(cl,cb),point(cl,ct),point(cr,cb),point(cr,ct)};
    for(const auto& corner:corners){if(!initialized){x0=x1=corner.first;y0=y1=corner.second;initialized=true;}else{x0=std::min(x0,corner.first);x1=std::max(x1,corner.first);y0=std::min(y0,corner.second);y1=std::max(y1,corner.second);}}
   }
   expect(std::abs(block["left"].get<double>()-x0)<0.02&&std::abs(block["right"].get<double>()-x1)<0.02&&std::abs(block["top"].get<double>()-y0)<0.02&&std::abs(block["bottom"].get<double>()-y1)<0.02,"text block uses crop-relative rotated float bounds");
  }
  if(rotation>0){
   auto review=parsed["blocks"];for(auto& block:review)if(block["text"].get<std::string>().find("Visible")!=std::string::npos){block["source"]="ocr";block["translatedText"]="Review";block["status"]="translated";}
   const auto reviewPath=directory+"/crop-rotated-review-"+std::to_string(rotation)+".pdf";
   auto reviewExport=json::parse(translatepdf::exportPage(path,0,review.dump(),reviewPath,font));
   expect(!reviewExport.contains("error")&&reviewExport["partial"],"rotated page attaches translated review safely");
   auto reviewDocument=FPDF_LoadDocument(reviewPath.c_str(),nullptr);auto reviewPage=FPDF_LoadPage(reviewDocument,0);
   expect(FPDFPage_GetAnnotCount(reviewPage)==1,"rotated review creates one annotation");
   auto annotation=FPDFPage_GetAnnot(reviewPage,0);FS_RECTF rect{};
   expect(annotation&&FPDFAnnot_GetRect(annotation,&rect),"rotated review annotation rectangle is readable");
   expect(rect.left>=left-0.02&&rect.right<=right+0.02&&rect.bottom>=bottom-0.02&&rect.top<=top+0.02,"rotated review annotation remains inside the visible crop");
   FPDFPage_CloseAnnot(annotation);FPDF_ClosePage(reviewPage);FPDF_CloseDocument(reviewDocument);
  }
  FPDF_ClosePage(page);FPDF_CloseDocument(document);
  if(rotation==0){
   auto v2=parsed["blocks"];double expectedV2Left=0;
   for(auto& block:v2)if(block["text"].get<std::string>().find("Visible")!=std::string::npos){expectedV2Left=block["left"];block["translatedText"]="OK";block["status"]="translated";}
   const auto v2out=directory+"/crop-v2-export.pdf";auto v2export=json::parse(translatepdf::exportPage(path,0,v2.dump(),v2out,font));
   expect(!v2export.contains("error"),"v2 crop-aware checkpoint exports");
   auto v2result=json::parse(translatepdf::readPage(v2out,0));bool v2AtCanonicalPosition=false;
   for(const auto& block:v2result["blocks"])if(block["text"].get<std::string>().find("OK")!=std::string::npos)
    v2AtCanonicalPosition=std::abs(block["left"].get<double>()-expectedV2Left)<15;
   expect(v2AtCanonicalPosition,"v2 text export round-trips to canonical visible-page position");
   json legacyEdges=json::array();
   auto edgeBlock=[&](const char* id,double l,double b,double r,double t){legacyEdges.push_back(json{{"id",id},{"text",id},{"source","native"},{"objectIndex",0},{"objectIndices",json::array({0})},{"left",l},{"right",r},{"top",expectedHeight-t},{"bottom",expectedHeight-b},{"fontSize",10},{"translatedText","Review"},{"status","translated"}});};
   edgeBlock("left-edge",left-10,bottom+100,left+10,bottom+120);
   edgeBlock("right-edge",right-10,bottom+100,right+10,bottom+120);
   edgeBlock("bottom-edge",left+100,bottom-10,left+120,bottom+10);
   edgeBlock("top-edge",left+140,top-10,left+160,top+10);
   edgeBlock("fully-off-bottom",left+180,top+20,left+200,top+40);
   const auto edgePath=directory+"/crop-legacy-edges.pdf";auto edgeExport=json::parse(translatepdf::exportPage(path,0,legacyEdges.dump(),edgePath,font));
   expect(!edgeExport.contains("error")&&edgeExport["partialBlocks"]==5,"legacy coordinates clip conservatively at each crop edge and fully outside");
   auto edgeDoc=FPDF_LoadDocument(edgePath.c_str(),nullptr);auto edgePage=FPDF_LoadPage(edgeDoc,0);
   expect(FPDFPage_CountObjects(edgePage)==9&&FPDFPage_GetAnnotCount(edgePage)==5,"legacy clipped groups preserve all source objects and attach five review annotations");
   FPDF_ClosePage(edgePage);FPDF_CloseDocument(edgeDoc);
   auto partial=v2;for(auto& block:partial)if(block["text"].get<std::string>().find("Clipped")!=std::string::npos){block["translatedText"]="Review";block["status"]="translated";}
   const auto partialOut=directory+"/crop-partial-export.pdf";auto partialExport=json::parse(translatepdf::exportPage(path,0,partial.dump(),partialOut,font));
   expect(!partialExport.contains("error")&&partialExport["partial"],"partially cropped source text falls back to review");
   auto partialResult=json::parse(translatepdf::readPage(partialOut,0));bool originalClippedTextRetained=false;
   for(const auto& block:partialResult["blocks"])if(block["text"].get<std::string>().find("Clipped")!=std::string::npos)originalClippedTextRetained=true;
   expect(originalClippedTextRetained,"partial crop export preserves the source text object");
   auto legacy=parsed["blocks"];auto legacyDocument=FPDF_LoadDocument(path.c_str(),nullptr);auto legacyPage=FPDF_LoadPage(legacyDocument,0);
   for(auto& block:legacy)if(block.value("source",std::string{})=="native"){
    const int objectIndex=block["objectIndex"].get<int>();auto object=FPDFPage_GetObject(legacyPage,objectIndex);float l=0,b=0,r=0,t=0;
    expect(FPDFPageObj_GetBounds(object,&l,&b,&r,&t),"legacy fixture reads original raw bounds");
    block["left"]=l;block["right"]=r;block["top"]=parsed["height"].get<double>()-t;block["bottom"]=parsed["height"].get<double>()-b;
    block.erase("coordinateSpaceVersion");block.erase("partialCrop");
   }
   FPDF_ClosePage(legacyPage);FPDF_CloseDocument(legacyDocument);
   double expectedLeft=0;for(const auto& block:parsed["blocks"])if(block["text"].get<std::string>().find("Visible")!=std::string::npos)expectedLeft=block["left"];
   for(auto& block:legacy)if(block["text"].get<std::string>().find("Visible")!=std::string::npos){block["translatedText"]="OK";block["status"]="translated";}
   const auto out=directory+"/crop-legacy-export.pdf";auto exported=json::parse(translatepdf::exportPage(path,0,legacy.dump(),out,font));
   expect(!exported.contains("error"),"legacy crop checkpoint exports without reset");
   auto result=json::parse(translatepdf::readPage(out,0));bool translatedAtCanonicalPosition=false;
   for(const auto& block:result["blocks"])if(block["text"].get<std::string>().find("OK")!=std::string::npos)
    translatedAtCanonicalPosition=std::abs(block["left"].get<double>()-expectedLeft)<15;
   expect(translatedAtCanonicalPosition,"legacy raw-coordinate checkpoint exports at its original visible-page location");
   auto offDoc=FPDF_LoadDocument(path.c_str(),nullptr);auto offPage=FPDF_LoadPage(offDoc,0);auto offObject=FPDFPage_GetObject(offPage,3);float ol=0,ob=0,orr=0,ot=0;
   expect(FPDFPageObj_GetBounds(offObject,&ol,&ob,&orr,&ot),"legacy off-crop raw bounds available");
   json legacyOff={{"id","legacy-offcrop"},{"text","Off crop"},{"source","native"},{"objectIndex",3},{"objectIndices",json::array({3})},
    {"left",ol},{"right",orr},{"top",parsed["height"].get<double>()-ot},{"bottom",parsed["height"].get<double>()-ob},{"fontSize",18},{"translatedText","Hidden legacy text"},{"status","translated"}};
   const int originalObjects=FPDFPage_CountObjects(offPage);FPDF_ClosePage(offPage);FPDF_CloseDocument(offDoc);
   const auto offPath=directory+"/crop-legacy-off.pdf";auto offExport=json::parse(translatepdf::exportPage(path,0,json::array({legacyOff}).dump(),offPath,font));
   expect(!offExport.contains("error")&&offExport["partial"],"fully off-crop legacy item is conservatively sent to review");
   auto offResult=FPDF_LoadDocument(offPath.c_str(),nullptr);auto offResultPage=FPDF_LoadPage(offResult,0);
   expect(FPDFPage_CountObjects(offResultPage)==originalObjects&&FPDFPage_GetAnnotCount(offResultPage)==1,"off-crop legacy export preserves its hidden source object");
   FPDF_ClosePage(offResultPage);FPDF_CloseDocument(offResult);
  }
 }
 for(int variant=1;variant<=2;++variant){
  const auto path=croppedFixture(0,variant);auto parsed=json::parse(translatepdf::readPage(path,0));
  auto document=FPDF_LoadDocument(path.c_str(),nullptr);auto page=FPDF_LoadPage(document,0);FS_RECTF box{};
  expect(FPDF_GetPageBoundingBox(page,&box),"PDFium crop/media intersection available");
  expect(std::abs(parsed["width"].get<double>()-(box.right-box.left))<0.02&&std::abs(parsed["height"].get<double>()-(box.top-box.bottom))<0.02,"negative and out-of-media CropBoxes use PDFium visible intersection");
  bool found=false;for(const auto& block:parsed["blocks"])if(block["text"].get<std::string>().find("Visible")!=std::string::npos){found=true;expect(std::isfinite(block["left"].get<double>())&&std::isfinite(block["top"].get<double>()),"negative crop origin produces finite canonical coordinates");}
  expect(found,"content in negative/out-of-media crop remains readable");
  FPDF_ClosePage(page);FPDF_CloseDocument(document);
 }
}
void pdfBoundary(){
 {auto r=replace(ops::FPDF_CreateNewDocument,[]{return FPDF_DOCUMENT(nullptr);});error(exportWith(),"Cannot create PDF");}
 {auto r=replace(ops::FPDF_ImportPagesByIndex,[](FPDF_DOCUMENT,FPDF_DOCUMENT,const int*,unsigned long,int){return FPDF_BOOL(0);});error(exportWith(),"Cannot import original page");error(translatepdf::mergePdf(json::array({source}).dump(),destination),"Cannot assemble translated pages");}
 {auto r=replace(ops::FPDFText_LoadPage,[](FPDF_PAGE){return FPDF_TEXTPAGE(nullptr);});auto read=json::parse(translatepdf::readPage(source,0));expect(!read.contains("error"),"missing text page retains inspectable page");}
 {auto r=replace(ops::FPDFTextObj_GetText,[](FPDF_PAGEOBJECT,FPDF_TEXTPAGE,unsigned short*,unsigned long){return 0UL;});expect(json::parse(translatepdf::readPage(source,0))["blocks"].empty(),"empty PDF text ignored");}
 {auto r=replace(ops::FPDFTextObj_GetText,[](FPDF_PAGEOBJECT,FPDF_TEXTPAGE,unsigned short* text,unsigned long length){if(!text)return 8UL;if(length>=8){text[0]=' ';text[1]='\r';text[2]='\n';text[3]=0;}return 8UL;});expect(json::parse(translatepdf::readPage(source,0))["blocks"].empty(),"whitespace PDF text ignored");}
 {auto r=replace(ops::FPDFPageObj_GetBounds,[](FPDF_PAGEOBJECT,float*,float*,float*,float*){return FPDF_BOOL(0);});expect(json::parse(translatepdf::readPage(source,0))["blocks"].empty(),"unbounded object ignored");}
 {auto r=replace(ops::FPDF_GetPageBoundingBox,[](FPDF_PAGE,FS_RECTF*){return FPDF_BOOL(0);});expect(json::parse(translatepdf::readPage(source,0)).contains("error"),"failed visible-box lookup rejects ambiguous coordinates");}
 {auto r=replace(ops::FPDF_GetPageBoundingBox,[](FPDF_PAGE,FS_RECTF* box){*box=FS_RECTF{0,0,0,0};return FPDF_BOOL(1);});expect(json::parse(translatepdf::readPage(source,0)).contains("error"),"empty visible-box intersection is rejected");}
 {auto r=replace(ops::FPDF_GetPageBoundingBox,[](FPDF_PAGE,FS_RECTF* box){*box=FS_RECTF{0,0,10,0};return FPDF_BOOL(1);});expect(json::parse(translatepdf::readPage(source,0)).contains("error"),"zero-height visible-box intersection is rejected");}
 {auto r=replace(ops::FPDF_GetPageBoundingBox,[](FPDF_PAGE,FS_RECTF* box){*box=FS_RECTF{0,0,std::numeric_limits<float>::infinity(),100};return FPDF_BOOL(1);});expect(json::parse(translatepdf::readPage(source,0)).contains("error"),"non-finite visible-box width is rejected");}
 {auto r=replace(ops::FPDF_GetPageBoundingBox,[](FPDF_PAGE,FS_RECTF* box){*box=FS_RECTF{0,-std::numeric_limits<float>::infinity(),10,std::numeric_limits<float>::infinity()};return FPDF_BOOL(1);});expect(json::parse(translatepdf::readPage(source,0)).contains("error"),"non-finite visible-box height is rejected");}
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
std::string multiPageFixture(){
 const auto path=directory+"/merge-three-page-shard.pdf";
 auto document=FPDF_CreateNewDocument();auto face=FPDFText_LoadStandardFont(document,"Helvetica");
 for(int i=0;i<3;++i){
  auto page=FPDFPage_New(document,i,612,792);auto object=FPDFPageObj_CreateTextObj(document,face,18);
  const auto value=std::u16string(u"Shard page ")+static_cast<char16_t>(u'0'+i);
  FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(value.c_str()));FPDFPageObj_Transform(object,1,0,0,1,70,710);FPDFPage_InsertObject(page,object);
  FPDFPage_GenerateContent(page);FPDF_ClosePage(page);
 }
 struct Writer:FPDF_FILEWRITE{FILE* file;explicit Writer(FILE* f):file(f){version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length){return static_cast<int>(fwrite(data,1,length,static_cast<Writer*>(base)->file)==length);};}};
 auto file=fopen(path.c_str(),"wb");expect(file!=nullptr,"multi-page fixture file opens");Writer writer(file);const bool saved=FPDF_SaveAsCopy(document,&writer,FPDF_NO_INCREMENTAL);fclose(file);FPDFFont_Close(face);FPDF_CloseDocument(document);expect(saved,"multi-page fixture saves");return path;
}
void targetCharacterInkFailureContracts(){
 const auto path=directory+"/target-character-ink-source.pdf",output=directory+"/target-character-ink-output.pdf";
 auto document=FPDF_CreateNewDocument();auto page=FPDFPage_New(document,0,612,792);
 auto body=FPDFText_LoadStandardFont(document,"Helvetica");auto pictogram=body;
 expect(body&&pictogram,"inline-ink fixture fonts load");
 const auto add=[&](FPDF_FONT face,const std::u16string& value,float size,float x,float y){auto object=FPDFPageObj_CreateTextObj(document,face,size);expect(object!=nullptr,"inline-ink fixture text object creates");expect(FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(value.c_str())),"inline-ink fixture text sets");FPDFPageObj_Transform(object,1,0,0,1,x,y);FPDFPage_InsertObject(page,object);};
 add(body,u"Press ",12,70,710);add(pictogram,u"®",12,103,710);add(body,u" now",12,116,710);
 FPDFPage_GenerateContent(page);FPDF_ClosePage(page);
 struct Writer:FPDF_FILEWRITE{FILE* file;explicit Writer(FILE* f):file(f){version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length){return static_cast<int>(fwrite(data,1,length,static_cast<Writer*>(base)->file)==length);};}};
 auto file=fopen(path.c_str(),"wb");expect(file!=nullptr,"inline-ink fixture file opens");Writer writer(file);const bool saved=FPDF_SaveAsCopy(document,&writer,FPDF_NO_INCREMENTAL);fclose(file);FPDFFont_Close(body);FPDF_CloseDocument(document);expect(saved,"inline-ink fixture saves");
 auto parsed=json::parse(translatepdf::readPage(path,0));expect(!parsed.contains("error"),"inline-ink fixture extracts");json request=parsed.at("blocks");
 auto owned=std::find_if(request.begin(),request.end(),[](const json& block){return block.contains("symbols")&&!block["symbols"].empty();});
 expect(owned!=request.end(),"inline-ink fixture produces a source-owned pictogram: "+parsed.dump());
 const auto token=owned->at("symbols").at(0).at("token").get<std::string>();
 (*owned)["translatedText"]=token+" Коротко.";(*owned)["status"]="translated";
 const json envelope={{"layoutVersion",3},{"mode","preserve-source"},{"sourcePageIndex",0},{"blocks",request}};
 auto valid=json::parse(translatepdf::exportPage(path,0,envelope.dump(),output,font));
 expect(!valid.contains("error")&&!valid["partial"]&&valid["partialBlocks"]==0,"valid target-character ink control exports cleanly: "+valid.dump());
 const auto bytes=[&]{std::ifstream in(output,std::ios::binary);return std::string((std::istreambuf_iterator<char>(in)),{});}();expect(!bytes.empty(),"valid inline-ink output is published");
 for(const int fault:{0,1,2}){
  targetCharacterObject=nullptr;targetPlacedTextObject=nullptr;targetCharacterInjected=false;targetCharacterNonfinite=fault==1;targetCharacterZeroArea=fault==2;
  auto setText=replace(ops::FPDFText_SetText,[](FPDF_PAGEOBJECT object,FPDF_WIDESTRING text){
   if(text&&text[0]==static_cast<unsigned short>(u'К')){float size=0;if(::FPDFTextObj_GetFontSize(object,&size)&&size>5&&size<40){if(text[1]==0)targetCharacterObject=object;else targetPlacedTextObject=object;}}
   return ::FPDFText_SetText(object,text);
  });
  auto getBoundsFault=replace(ops::FPDFPageObj_GetBounds,[](FPDF_PAGEOBJECT object,float* left,float* bottom,float* right,float* top){
   const auto ok=::FPDFPageObj_GetBounds(object,left,bottom,right,top);
   if(targetCharacterZeroArea&&object==targetPlacedTextObject&&ok){targetCharacterInjected=true;*right=*left;}
   else if(!targetCharacterZeroArea&&object==targetCharacterObject&&ok){targetCharacterInjected=true;if(targetCharacterNonfinite)*left=std::numeric_limits<float>::quiet_NaN();else return FPDF_BOOL(0);}
   return ok;
  });
  auto rejected=json::parse(translatepdf::exportPage(path,0,envelope.dump(),output,font));
  expect((fault==2?targetPlacedTextObject!=nullptr:targetCharacterObject!=nullptr)&&targetCharacterInjected,"fault reaches the intended placed-text or character-ink measurement");
  const auto expectedError=fault==2?"Target translation contains no visible text":"Cannot read target character collision ink";
  expect(rejected.value("error",std::string{}).find(expectedError)!=std::string::npos,"unavailable/nonfinite/zero-area target character ink fails closed: "+rejected.dump());
  std::ifstream in(output,std::ios::binary);expect(std::string((std::istreambuf_iterator<char>(in)),{})==bytes,"character ink failure preserves prior published output");
  expect(!fs::exists(output+".preserving")&&!fs::exists(output+".preserving.part"),"character ink failure removes unpublished staging files");
 }
}
void sourcePreservingCancellationContracts(){
 const json request={{"layoutVersion",3},{"mode","preserve-source"},{"sourcePageIndex",0},{"blocks",blocks}};
 const auto preview=directory+"/preserving-preview.pdf";
 const auto render=[&]{return json::parse(translatepdf::exportPage(source,0,request.dump(),preview,font));};
 translatepdf::cancel();expect(!render().contains("error"),"checkpoint-only PDF export can start after a previously acknowledged inference cancellation");
 const auto read=[](const std::string& path){std::ifstream file(path,std::ios::binary);return std::string(std::istreambuf_iterator<char>(file),{});};
 const auto previous=read(preview);expect(!previous.empty(),"verified source-preserving preview exists before cancellation");
 {auto r=replace(ops::FPDF_SaveAsCopy,[](FPDF_DOCUMENT document,FPDF_FILEWRITE* writer,FPDF_DWORD flags){const auto ok=FPDF_SaveAsCopy(document,writer,flags);translatepdf::cancel();return ok;});
  expect(render().value("error",std::string{}).find("Canceled")!=std::string::npos,"cancellation during serialization prevents preview publication");}
 expect(read(preview)==previous&&!fs::exists(preview+".preserving")&&!fs::exists(preview+".preserving.part"),"canceled preview keeps the previously verified bytes and removes only its unpublished candidate");
 expect(!render().contains("error"),"a fresh checkpoint export succeeds without reloading model weights after cancellation");
 const auto snapshot=directory+"/preserving-snapshot.json";std::ofstream(snapshot)<<request.dump();
 const json envelope={{"layoutVersion",3},{"mode","preserve-source"},{"sourcePath",source},{"fontPath",font},{"shards",json::array({{{"sourcePageIndex",0},{"expectedPageCount",1},{"path",preview},{"blocksPath",snapshot}}})}};
 std::ofstream(destination,std::ios::binary)<<"previous document";
 {auto r=replace(ops::FPDF_SaveAsCopy,[](FPDF_DOCUMENT document,FPDF_FILEWRITE* writer,FPDF_DWORD flags){const auto ok=FPDF_SaveAsCopy(document,writer,flags);translatepdf::cancel();return ok;});error(translatepdf::mergePdf(envelope.dump(),destination),"Canceled");}
 expect(read(destination)=="previous document"&&!fs::exists(destination+".preserving"),"canceled full-document save retains source checkpoints and last published artifact");
 expect(!json::parse(translatepdf::mergePdf(envelope.dump(),destination)).contains("error"),"full-document finalization restarts independently of prior model cancellation");
}
void mergeContracts(){
 const auto three=multiPageFixture(),target=directory+"/merge-v2-result.pdf",legacy=directory+"/merge-legacy-result.pdf";
 auto envelope=[&](json shards){return json{{"layoutVersion",2},{"shards",std::move(shards)}}.dump();};
 auto merged=json::parse(translatepdf::mergePdf(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",3}},{{"path",source},{"sourcePageIndex",1},{"expectedPageCount",1}}})),target));
 expect(!merged.contains("error")&&merged["layoutVersion"]==2&&merged["pageCount"]==4,"v2 merge appends all pages across shards");
 expect(merged["sourcePageRanges"]==json::array({{{"sourcePageIndex",0},{"firstOutputPage",0},{"outputPageCount",3}},{{"sourcePageIndex",1},{"firstOutputPage",3},{"outputPageCount",1}}}),"v2 merge returns source shard ranges");
 expect(merged["pages"]==json::array({{{"outputPageIndex",0},{"sourcePageIndex",0},{"sourceOutputPageIndex",0}},{{"outputPageIndex",1},{"sourcePageIndex",0},{"sourceOutputPageIndex",1}},{{"outputPageIndex",2},{"sourcePageIndex",0},{"sourceOutputPageIndex",2}},{{"outputPageIndex",3},{"sourcePageIndex",1},{"sourceOutputPageIndex",0}}}),"v2 merge returns per-page source mapping");
 auto result=FPDF_LoadDocument(target.c_str(),nullptr);expect(result&&FPDF_GetPageCount(result)==4,"v2 saved output contains four pages");FPDF_CloseDocument(result);
 for(int i=0;i<3;++i){auto page=json::parse(translatepdf::readPage(target,i));expect(page["blocks"].size()==1&&page["blocks"][0]["text"]=="Shard page "+std::to_string(i),"v2 output preserves source page order");}
 auto last=json::parse(translatepdf::readPage(target,3));expect(last["blocks"].size()==2&&last["blocks"][0]["text"]=="A short line.","v2 output appends the next shard's first page");
 auto legacyResult=json::parse(translatepdf::mergePdf(json::array({three,source}).dump(),legacy));
 expect(!legacyResult.contains("error")&&legacyResult["pageCount"]==2&&!legacyResult.contains("layoutVersion"),"legacy path array retains one-page-per-input contract");
 auto legacyFirst=json::parse(translatepdf::readPage(legacy,0));expect(legacyFirst["blocks"].size()==1&&legacyFirst["blocks"][0]["text"]=="Shard page 0","legacy array imports only page zero from each shard");

 std::ifstream stableFile(target,std::ios::binary);const std::string stableBytes((std::istreambuf_iterator<char>(stableFile)),{});expect(!stableBytes.empty(),"destination snapshot reads completely");
 auto preserves=[&](const std::string& input,const std::string& message){error(translatepdf::mergePdf(input,target),message);std::ifstream file(target,std::ios::binary);const std::string bytes((std::istreambuf_iterator<char>(file)),{});expect(bytes==stableBytes,"rejected merge preserves existing destination");expect(!fs::exists(target+".merge")&&!fs::exists(target+".merge.part"),"rejected merge leaves no staging output");};
 preserves(json::object().dump(),"Invalid page shard envelope");
 preserves(json{{"shards",json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",3}}})}}.dump(),"Invalid page shard envelope");
 preserves(json{{"layoutVersion",1},{"shards",json::array()}}.dump(),"Invalid page shard envelope");
 preserves(envelope(json::array()),"Invalid page shard envelope");
 preserves(json::array().dump(),"No completed pages to export");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",1},{"expectedPageCount",3}}})),"sequential from zero");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",3}},{{"path",source},{"sourcePageIndex",0},{"expectedPageCount",1}}})),"sequential from zero");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",3}},{{"path",source},{"sourcePageIndex",2},{"expectedPageCount",1}}})),"sequential from zero");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",2}}})),"Source shard page count mismatch");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0}}})),"Invalid page shard descriptor");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",0}}})),"Expected page count must be positive");
 preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",2147483647LL}},{{"path",source},{"sourcePageIndex",1},{"expectedPageCount",1}}})),"Export page count overflow");
 {auto r=replace(ops::FPDF_ImportPagesByIndex,[](FPDF_DOCUMENT,FPDF_DOCUMENT,const int*,unsigned long,int){return FPDF_BOOL(0);});preserves(envelope(json::array({{{"path",three},{"sourcePageIndex",0},{"expectedPageCount",3}}})),"Cannot assemble translated pages");}
}
std::string readableGraphicFixture(){
 const auto path=directory+"/readable-graphic-source.pdf";auto document=FPDF_CreateNewDocument();auto page=FPDFPage_New(document,0,612,792);auto face=FPDFText_LoadStandardFont(document,"Helvetica");
 for(int i=0;i<2;++i){auto object=FPDFPageObj_CreateTextObj(document,face,18);const std::u16string text=i?u"Another original sentence.":u"First original sentence.";FPDFText_SetText(object,reinterpret_cast<const unsigned short*>(text.c_str()));FPDFPageObj_Transform(object,1,0,0,1,65,710-i*55);FPDFPage_InsertObject(page,object);}
 auto graphic=FPDFPageObj_CreateNewRect(300,420,130,110);expect(graphic!=nullptr,"graphic fixture creates vector shape");
 expect(FPDFPageObj_SetFillColor(graphic,0,0,0,255)&&FPDFPath_SetDrawMode(graphic,FPDF_FILLMODE_ALTERNATE,false),"graphic fixture configures filled visible shape");FPDFPage_InsertObject(page,graphic);
 FPDFPage_GenerateContent(page);FPDF_ClosePage(page);struct Writer:FPDF_FILEWRITE{FILE* file;explicit Writer(FILE* f):file(f){version=1;WriteBlock=[](FPDF_FILEWRITE* base,const void* data,unsigned long length){return static_cast<int>(fwrite(data,1,length,static_cast<Writer*>(base)->file)==length);};}};
 auto file=fopen(path.c_str(),"wb");expect(file!=nullptr,"graphic fixture output opens");Writer writer(file);const bool saved=FPDF_SaveAsCopy(document,&writer,FPDF_NO_INCREMENTAL);fclose(file);FPDFFont_Close(face);FPDF_CloseDocument(document);expect(saved,"graphic fixture saves");return path;
}
void readableExportContracts(){
 const auto output=directory+"/readable-reflow.pdf";auto sourceBlocks=blocks;expect(sourceBlocks.size()==2,"readable export fixture contains two native source blocks");
 sourceBlocks[0]["id"]="short-source";sourceBlocks[0]["translatedText"]="Короткий перевод.";sourceBlocks[0]["status"]="translated";
 sourceBlocks[1]["id"]="long-source";const std::string longRussian="Проверка системы и последовательности действий. ";std::string longTranslation;for(int i=0;i<280;++i)longTranslation+=longRussian;sourceBlocks[1]["translatedText"]=longTranslation;sourceBlocks[1]["status"]="translated";
 auto request=[&](json entries){return json{{"layoutVersion",2},{"mode","reflow"},{"blocks",std::move(entries)}}.dump();};
 auto exported=json::parse(translatepdf::exportPage(source,0,request(sourceBlocks),output,font));
 expect(!exported.contains("error")&&exported["layoutVersion"]==2&&exported["mode"]=="reflow"&&exported["sourcePageIndex"]==0,"readable export reports v2 source identity");
 expect(exported["pageCount"].get<int>()>1,"long translated paragraph reflows to multiple output pages");
 std::set<int> longPlacementPages;bool shortPlaced=false;for(const auto& placement:exported["placements"]){const int page=placement["outputPageIndex"];expect(page>=0&&page<exported["pageCount"],"readable placement maps into its output page range");if(placement.value("blockId",std::string{})=="long-source")longPlacementPages.insert(page);if(placement.value("blockId",std::string{})=="short-source")shortPlaced=true;}
 expect(shortPlaced&&longPlacementPages.size()>1,"short and multi-page text blocks have source-to-output placements");
 std::string combined;for(int i=0;i<exported["pageCount"].get<int>();++i){auto page=json::parse(translatepdf::readPage(output,i));expect(!page.contains("error"),"readable output page can be inspected");for(const auto& block:page["blocks"])combined+=block["text"].get<std::string>();auto doc=FPDF_LoadDocument(output.c_str(),nullptr);auto loadedPage=FPDF_LoadPage(doc,i);expect(doc&&loadedPage&&FPDFPage_GetAnnotCount(loadedPage)==0,"readable translated pages use visible text without review annotations");FPDF_ClosePage(loadedPage);FPDF_CloseDocument(doc);}
 expect(combined.find("Короткий перевод.")!=std::string::npos&&combined.find(longRussian)!=std::string::npos,"readable target-language text remains visible in exported page content");
 expect(combined.find("A short line.")==std::string::npos&&combined.find("Another line.")==std::string::npos,"readable export does not leave source English visible beside translated text");
 std::ifstream stableFile(output,std::ios::binary);const std::string stable((std::istreambuf_iterator<char>(stableFile)),{});expect(!stable.empty(),"readable success creates a destination snapshot");
 auto rejectsPreserving=[&](json entries,const std::string& mode,int version,const std::string& expected){auto malformed=request(std::move(entries));auto parsed=json::parse(malformed);parsed["mode"]=mode;parsed["layoutVersion"]=version;error(translatepdf::exportPage(source,0,parsed.dump(),output,font),expected);std::ifstream file(output,std::ios::binary);expect(std::string((std::istreambuf_iterator<char>(file)),{})==stable,"invalid readable export preserves prior destination");};
 for(int missingMode=0;missingMode<3;++missingMode){auto entries=sourceBlocks;if(missingMode==0){entries[0].erase("translatedText");entries[0].erase("translation");}else if(missingMode==1)entries[0]["translatedText"]=nullptr;else entries[0]["translatedText"]=" \n\t ";
  error(translatepdf::exportPage(source,0,request(entries),output,font),"target-language text block is missing");std::ifstream file(output,std::ios::binary);expect(std::string((std::istreambuf_iterator<char>(file)),{})==stable,"missing or blank translation preserves prior destination");}
 auto duplicate=sourceBlocks;duplicate[1]["id"]=duplicate[0]["id"];rejectsPreserving(duplicate,"reflow",2,"unique and nonempty");
 rejectsPreserving(sourceBlocks,"annotation",2,"Unsupported readable PDF layout version");
 rejectsPreserving(sourceBlocks,"reflow",1,"Unsupported readable PDF layout version");
 rejectsPreserving(json::array(),"reflow",2,"Some source text has no translated block");

 auto technical=sourceBlocks[0];technical["id"]="long-token";technical["translatedText"]=std::string(360,'Q');
 auto tokenSupport=sourceBlocks[1];tokenSupport["translatedText"]="Дополнительная строка.";auto tokenBlocks=json::array({technical,tokenSupport});
 auto tokenResult=json::parse(translatepdf::exportPage(source,0,request(tokenBlocks),directory+"/readable-long-token.pdf",font));expect(!tokenResult.contains("error")&&tokenResult["pageCount"]==1,"unbreakable technical token is flowed on readable page: "+tokenResult.dump());
 const auto tokenPage=json::parse(translatepdf::readPage(directory+"/readable-long-token.pdf",0));std::string tokenOutput=tokenPage["blocks"].at(0)["text"].get<std::string>();tokenOutput.erase(std::remove_if(tokenOutput.begin(),tokenOutput.end(),[](unsigned char c){return c==' '||c=='\t'||c=='\r'||c=='\n';}),tokenOutput.end());expect(tokenOutput==technical["translatedText"],"long technical token preserves every character across line breaks; expected bytes="+std::to_string(technical["translatedText"].get<std::string>().size())+" actual="+std::to_string(tokenOutput.size()));

 const auto graphicSource=readableGraphicFixture(),graphicOutput=directory+"/readable-graphic-output.pdf";auto graphicBlocks=json::parse(translatepdf::readPage(graphicSource,0))["blocks"];expect(graphicBlocks.size()==2,"graphic fixture text blocks extract");
 for(size_t i=0;i<graphicBlocks.size();++i){graphicBlocks[i]["id"]="graphic-text-"+std::to_string(i);graphicBlocks[i]["translatedText"]=i?"Второй перевод.":"Первый перевод.";graphicBlocks[i]["status"]="translated";}
 auto graphicResult=json::parse(translatepdf::exportPage(graphicSource,0,request(graphicBlocks),graphicOutput,font));expect(!graphicResult.contains("error"),"readable export handles vector graphics with translated text");
 bool figure=false;for(const auto& placement:graphicResult["placements"])figure=figure||placement.value("role",std::string{})=="figure";expect(figure,"readable export keeps source figure in its placement map");
 auto raster=translatepdf::render(graphicOutput,0,320);bool darkPixel=false;for(size_t i=0;i+3<raster.rgba.size();i+=4)if(raster.rgba[i]<100&&raster.rgba[i+1]<100&&raster.rgba[i+2]<100){darkPixel=true;break;}expect(darkPixel,"readable export renders retained figure pixels");
 const auto graphicStable=[&]{std::ifstream file(graphicOutput,std::ios::binary);return std::string((std::istreambuf_iterator<char>(file)),{});}();
 {auto r=replace(ops::FPDFBitmap_CreateEx,[](int,int,int,void*,int){return FPDF_BITMAP(nullptr);});error(translatepdf::exportPage(graphicSource,0,request(graphicBlocks),graphicOutput,font),"Cannot allocate bounded PDF figure bitmap");std::ifstream file(graphicOutput,std::ios::binary);expect(std::string((std::istreambuf_iterator<char>(file)),{})==graphicStable,"figure bitmap allocation failure preserves existing output");}
 {auto r=replace(ops::FPDFPageObj_NewImageObj,[](FPDF_DOCUMENT){return FPDF_PAGEOBJECT(nullptr);});error(translatepdf::exportPage(graphicSource,0,request(graphicBlocks),graphicOutput,font),"Cannot create readable PDF figure");std::ifstream file(graphicOutput,std::ios::binary);expect(std::string((std::istreambuf_iterator<char>(file)),{})==graphicStable,"figure object failure preserves existing output");}
 auto blank=sourceBlocks;blank[0]["id"]="  ";rejectsPreserving(blank,"reflow",2,"unique and nonempty");
 auto nestedDocument=FPDF_LoadDocument(source.c_str(),nullptr);auto nestedPage=FPDF_LoadPage(nestedDocument,0);auto nestedText=FPDFPage_GetObject(nestedPage,0);
 readableObjectTypeCalls=0;readableNestedText=nestedText;
 {auto type=replace(ops::FPDFPageObj_GetType,[](FPDF_PAGEOBJECT)->int{const int call=++readableObjectTypeCalls;if(call<=2||call==5)return FPDF_PAGEOBJ_PATH;if(call==3)return FPDF_PAGEOBJ_FORM;return FPDF_PAGEOBJ_TEXT;});auto count=replace(ops::FPDFFormObj_CountObjects,[](FPDF_PAGEOBJECT){return 1;});auto child=replace(ops::FPDFFormObj_GetObject,[](FPDF_PAGEOBJECT,unsigned long){return readableNestedText;});auto noExtractedText=replace(ops::FPDFTextObj_GetText,[](FPDF_PAGEOBJECT,FPDF_TEXTPAGE,unsigned short*,unsigned long){return 0UL;});error(translatepdf::exportPage(source,0,request(json::array()),graphicOutput,font),"embedded text structure is not supported");}
 FPDF_ClosePage(nestedPage);FPDF_CloseDocument(nestedDocument);
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
  if(mode==3){
   expect(out["issues"].empty(),"translation review does not manufacture a new layout failure");
   const auto rendered=result(translatepdf::readPage(destination,0));bool placed=false;
   for(const auto& block:rendered["blocks"])if(block["text"]==b[0]["translatedText"])placed=true;
   expect(placed,"a review-marked translation is visibly placed while retaining review state");
  }
 }
 {auto r=replace(ops::FPDFPage_GetRotation,[](FPDF_PAGE){return 1;});expect(result(exportWith())["partial"],"rotated page annotates");}
 {calls=0;auto r=replace(ops::FPDFPageObj_GetType,[](FPDF_PAGEOBJECT){return FPDF_PAGEOBJ_PATH;});expect(result(exportWith())["partial"],"non-text object cannot be removed");}
 {calls=0;auto created=replace(ops::FPDFPageObj_CreateTextObj,[](FPDF_DOCUMENT d,FPDF_FONT f,float size){calls=1;return createText(d,f,size);});auto width=replace(ops::FPDFFont_GetGlyphWidth,[](FPDF_FONT f,uint32_t cp,float size,float* w){return calls?FPDF_BOOL(0):getWidth(f,cp,size,w);});error(exportWith(),"Font lacks a required glyph");}
 {auto generated=replace(ops::FPDFPage_GenerateContent,[](FPDF_PAGE){return FPDF_BOOL(0);});error(exportWith(blocks,cjkFont),"Cannot generate translated page content");}
 result(exportWith());
}

struct ModelState {
 decltype(llama_context_params{}.abort_callback) abortCallback=nullptr;
 void* abortData=nullptr;
 int context=2048,threads=2,sequences=1,contextCreates=0,capacityOverride=0,modelFreed=0,contextFreed=0,samplerFreed=0,modelLoads=0;
 int templateCalls=0,samples=0,decodes=0,tokenCalls=0,templateEndOverride=0;
 int loadFailure=0,templateMode=0,tokenMode=0,decodeMode=0,sampleMode=0,pieceMode=0;
 bool cacheTestMode=false,fakeMemory=false,seqRmSuccess=true,repacking=true;
 int memoryClears=0,seqRmCalls=0,seqCpCalls=0,seqCpThrowAt=0,seqCpCancelAt=0,badMemoryClears=0,contextIdentity=0;
 llama_pos seqRmStart=0,seqRmEnd=0;
 bool batchMode=false,customPromptMode=false,copyPending=false;int customPromptLength=64,customPromptCommon=60;
 std::vector<std::tuple<int,int,int>> queuedCopies;
 std::vector<std::tuple<int,int,int>> decodedEntries;
 std::vector<std::vector<int>> logitHistory;
 std::vector<int> copySources,copyDestinations;
 int sourceTokensOverride=0,promptTokensOverride=0,soloLength=1,sampleCancelAt=0,sampleTotal=0;bool eogCancel=false,tokenCancel=false;
 std::array<int,8> lengths{1,2,3,4,1,1,1,1},positions{0,0,0,0,0,0,0,0};
 std::map<llama_sampler*,int> samplerSamples;
 std::vector<int> lastLogitSequences;
 int seenBatchEntries=0,seenPrefixCalls=0;
 std::string lastPrompt;
 const char* chat="default";
} state;
void resetTranslation(){state.templateCalls=state.samples=state.decodes=state.tokenCalls=0;state.templateMode=state.tokenMode=state.decodeMode=state.sampleMode=state.pieceMode=0;state.templateEndOverride=0;state.chat="default";}
std::string translate(const std::string& text="Hello",const std::string& glossary="",const std::string& context=""){return translatepdf::translate(text,glossary,context,"en","ru");}
void modelBoundary(){
 translatepdf::releaseModel();
 auto load=replace(ops::llama_model_load_from_file,[](const char*,llama_model_params p){++state.modelLoads;state.repacking=p.use_extra_bufts;expect(p.load_mode==LLAMA_LOAD_MODE_MMAP,"both weight strategies keep mmap enabled");return state.loadFailure==1?nullptr:reinterpret_cast<llama_model*>(0x1000);});
 auto encoder=replace(ops::llama_model_has_encoder,[](const llama_model*){return state.loadFailure==2;});
 auto init=replace(ops::llama_init_from_model,[](llama_model*,llama_context_params p){state.abortCallback=p.abort_callback;state.abortData=p.abort_callback_data;state.context=p.n_ctx;state.threads=p.n_threads;state.sequences=p.n_seq_max;++state.contextCreates;++state.contextIdentity;state.positions.fill(0);state.queuedCopies.clear();state.copyPending=false;return state.loadFailure==3?nullptr:reinterpret_cast<llama_context*>(0x2000);});
 auto freeModel=replace(ops::llama_model_free,[](llama_model*){++state.modelFreed;});
 auto freeContext=replace(ops::llama_free,[](llama_context*){++state.contextFreed;state.positions.fill(0);state.queuedCopies.clear();state.copyPending=false;});
 auto ctxSize=replace(ops::llama_n_ctx,[](const llama_context*){return uint32_t(state.context);});
 auto ctxCapacity=replace(ops::llama_n_ctx_seq,[](const llama_context*){return uint32_t(state.capacityOverride?state.capacityOverride:state.context/state.sequences);});
 auto ctxThreads=replace(ops::llama_n_threads,[](llama_context*){return int32_t(state.threads);});
 auto modelSize=replace(ops::llama_model_size,[](const llama_model*){return uint64_t(1234);});
 auto tmpl=replace(ops::llama_model_chat_template,[](const llama_model*,const char*){return state.chat;});
 auto vocab=replace(ops::llama_model_get_vocab,[](const llama_model*){return reinterpret_cast<const llama_vocab*>(0x3000);});
 auto ctxMemory=replace(ops::llama_get_memory,[](const llama_context*){return state.fakeMemory?reinterpret_cast<llama_memory_t>(0x4000):llama_memory_t(nullptr);});
 auto clearMemory=replace(ops::llama_memory_clear,[](llama_memory_t,bool){++state.memoryClears;if(state.copyPending)++state.badMemoryClears;state.positions.fill(0);if(!state.copyPending)state.queuedCopies.clear();if(state.decodeMode==1)translatepdf::cancel();});
 auto copyMemory=replace(ops::llama_memory_seq_cp,[](llama_memory_t,llama_seq_id source,llama_seq_id destination,llama_pos begin,llama_pos end){
  expect(state.batchMode&&begin==0&&end==-1,"shared prefix uses the pinned full-range copy");
  expect(source>=0&&source<state.sequences&&destination>=0&&destination<state.sequences&&source!=destination,"KV copy maps distinct configured sequences");
  ++state.seqCpCalls;state.copySources.push_back(source);state.copyDestinations.push_back(destination);state.copyPending=true;state.queuedCopies.emplace_back(source,destination,state.positions[source]);state.positions[destination]=state.positions[source];
  if(state.seqCpCancelAt==state.seqCpCalls)translatepdf::cancel();
  if(state.seqCpThrowAt==state.seqCpCalls)throw std::runtime_error("fake-seq-cp-throw");
 });
 auto removeMemory=replace(ops::llama_memory_seq_rm,[](llama_memory_t,int32_t,llama_pos start,llama_pos end){++state.seqRmCalls;state.seqRmStart=start;state.seqRmEnd=end;return state.seqRmSuccess;});
 auto bos=replace(ops::llama_vocab_bos,[](const llama_vocab*){return llama_token(1);});
 auto numTokens=replace(ops::llama_vocab_n_tokens,[](const llama_vocab*){return int32_t(16);});
 auto eog=replace(ops::llama_vocab_is_eog,[](const llama_vocab*,llama_token token){if(state.eogCancel&&token==9)translatepdf::cancel();return token==9;});
 auto tokenizer=replace(ops::llama_tokenize,[](const llama_vocab*,const char* text,int32_t length,llama_token* tokens,int32_t max,bool special,bool parse){
  ++state.tokenCalls;
  if(parse&&length>1&&max!=1)state.lastPrompt.assign(text,length);
  if(state.tokenCancel&&!parse)translatepdf::cancel();
  if(state.tokenMode==1)return int32_t(0);
  if(parse&&length>0&&text[0]=='<'&&max==1){if(state.tokenMode==4)return int32_t(-2);tokens[0]=state.templateEndOverride?state.templateEndOverride:9;return int32_t(1);}
  if(state.cacheTestMode&&parse){const int count=(length+1)/2;if(!tokens)return -count;for(int i=0;i<std::min(count,max);++i){const int offset=i*2;const unsigned first=static_cast<unsigned char>(text[offset]);const unsigned second=offset+1<length?static_cast<unsigned char>(text[offset+1]):0;tokens[i]=static_cast<llama_token>(first*257U+second+10U);}return count;}
  if(state.batchMode&&state.customPromptMode&&special&&parse){const int count=state.customPromptLength;if(!tokens)return -count;const int request=std::max(0,state.templateCalls-1);for(int i=0;i<std::min(count,max);++i)tokens[i]=i<state.customPromptCommon?2:static_cast<llama_token>(1000+request*100+i);return count;}
  int count=parse?(state.promptTokensOverride?state.promptTokensOverride:4):(state.sourceTokensOverride?state.sourceTokensOverride:2);
  if(state.tokenMode==2&&special)count=0;
  if(state.tokenMode==3)count=4096;
  if(!tokens)return -int32_t(count);
  for(int i=0;i<std::min(count,max);++i)tokens[i]=state.tokenMode==5?1:2;
  return int32_t(count);
 });
 auto format=replace(ops::llama_chat_apply_template,[](const char*,const llama_chat_message* messages,size_t count,bool,char* buffer,int32_t length){
  int call=++state.templateCalls;
  if(state.cacheTestMode){const std::string prompt=std::string("PROMPT:")+(count?messages[0].content:"")+"\nEND";if(length<static_cast<int>(prompt.size()))return -1;std::memcpy(buffer,prompt.data(),prompt.size());return static_cast<int>(prompt.size());}
  if(state.templateMode==1)return -1;
  if(state.templateMode>=2&&call==1)return length+16;
  if(state.templateMode==3&&call==2)return -1;
  if(state.templateMode==4&&call==2)return length;
  if(length>=6)std::memcpy(buffer,"prompt",6);return 6;
 });
 auto decoder=replace(ops::llama_decode,[](llama_context*,llama_batch batch){
  if(state.batchMode&&batch.seq_id){
   for(const auto& copy:state.queuedCopies){const auto [source,destination,position]=copy;expect(state.positions[source]==position,"queued KV copy donor is not mutated before decode");expect(state.positions[destination]==position,"queued KV copy peer begins at exact donor position");}
   state.lastLogitSequences.clear();bool logits=false;
   for(int i=0;i<batch.n_tokens;++i){
    expect(batch.n_seq_id[i]==1,"batch entry belongs to exactly one independent seq");const int seq=batch.seq_id[i][0];
    expect(seq>=0&&seq<state.sequences,"batch seq fits configured capacity");
    expect(batch.pos[i]==state.positions[seq]++,"batch positions advance independently without missing source");
    state.decodedEntries.emplace_back(batch.token[i],batch.pos[i],seq);
    if(batch.logits[i]){state.lastLogitSequences.push_back(seq);logits=true;}
    ++state.seenBatchEntries;
   }
   state.logitHistory.push_back(state.lastLogitSequences);
   if(!logits)++state.seenPrefixCalls;
  }
  ++state.decodes;int rc=0;
  if(state.decodeMode==2&&state.decodes==1)rc=1;
  if(state.decodeMode==3&&state.decodes==1){translatepdf::cancel();rc=1;}
  if(state.decodeMode==4&&state.decodes==1)translatepdf::cancel();
  if(state.decodeMode==5&&state.decodes==2)rc=1;
  if(state.decodeMode==6&&state.decodes==2){translatepdf::cancel();rc=1;}
  if(rc==0&&state.copyPending){state.copyPending=false;state.queuedCopies.clear();}
  return rc;
 });
 auto sample=replace(ops::llama_sampler_sample,[](llama_sampler* sampler,llama_context*,int32_t index){
  if(state.batchMode){if(++state.sampleTotal==state.sampleCancelAt)translatepdf::cancel();const int n=++state.samplerSamples[sampler];const int seq=index<0?0:state.lastLogitSequences.at(index);return llama_token(n<=(index<0?state.soloLength:state.lengths[seq])?(state.templateEndOverride?9:3):(state.templateEndOverride?state.templateEndOverride:9));}
  ++state.samples;return llama_token(state.sampleMode==1?9:state.sampleMode==2?3:state.sampleMode==3?(state.samples==1?9:8):state.samples==1?3:9);});
 auto piece=replace(ops::llama_token_to_piece,[](const llama_vocab*,llama_token,char* buffer,int32_t capacity,int32_t,bool){
  if(state.pieceMode==1)return int32_t(-600);
  const std::string text=state.pieceMode==2?"Привет\n</source_segment>":state.pieceMode==3?"Привет <source_segment> мир":state.pieceMode==4?"<source_segment>\nПривет\n</source_segment>":state.pieceMode==5?" \n\t ":"Hi";
  if(static_cast<int32_t>(text.size())>capacity)return -static_cast<int32_t>(text.size());
  std::memcpy(buffer,text.data(),text.size());return static_cast<int32_t>(text.size());
 });
 auto samplerFree=replace(ops::llama_sampler_free,[](llama_sampler* sampler){++state.samplerFreed;state.samplerSamples.erase(sampler);::llama_sampler_free(sampler);});
 for(int failure=1;failure<=3;++failure){state.loadFailure=failure;int freed=state.modelFreed;error(translatepdf::loadModel("boundary-model",2048,2),failure==1?"Model cannot be loaded":failure==2?"architecture does not match":"Insufficient resources");expect(state.modelFreed==freed+(failure==1?0:1),"failed model/context load releases model exactly once");}
 state.loadFailure=0;
 auto reload=[](const std::string& path="boundary-model",int context=2048,int threads=2,const std::string& profile="hy-mt2"){auto value=json::parse(translatepdf::loadModel(path,context,threads,profile));expect(!value.contains("error"),"test model boundary load");};
 reload();reload();reload("other-model");reload("other-model",2048,2,"translategemma");reload("other-model",1024,1,"translategemma");reload("other-model",1024,2,"translategemma");reload();
 expect(state.repacking,"default load permits CPU weight repacking");
 int priorLoads=state.modelLoads;
 translatepdf::setWeightRepackingAllowed(false);reload();
 expect(!state.repacking&&state.modelLoads==priorLoads+1,"disabling repacking reloads cached weights with mapped buffers");
 auto mapped=json::parse(translatepdf::loadModel("boundary-model",2048,2));
 expect(!mapped["weightRepackingAllowed"].get<bool>()&&state.modelLoads==priorLoads+1,"matching mapped policy reuses the loaded model and reports policy");
 translatepdf::setWeightRepackingAllowed(true);reload();
 expect(state.repacking&&state.modelLoads==priorLoads+2,"restoring repacking reloads mapped weights");
 expect(state.abortCallback&&!state.abortCallback(state.abortData),"loaded backend receives an active cancellation callback");
 translatepdf::cancel();expect(state.abortCallback(state.abortData),"backend callback observes cancellation without waiting for a model lock");error(translate(),"Canceled");reload();
 expect(!state.abortCallback(state.abortData),"reloaded worker clears backend cancellation before decoding");
 for(int mode=1;mode<=4;++mode){resetTranslation();state.templateMode=mode;if(mode==2){auto j=json::parse(translate());expect(j.value("text","")=="Hi","retry with requested template buffer succeeds");}else error(translate(),mode==1?"Unsupported GGUF chat template":"Cannot format translation prompt");}
 // Use the actual pinned GGUF Jinja, rather than a mock name, to catch the
 // built-in Hunyuan-VL/Dense detector collision. Golden order also matches
 // canonical llama-completion --jinja tokenization (BOS, User, content, Assistant).
 std::ifstream denseFile(std::string(PROJECT_ROOT)+"/native/tests/fixtures/hy-mt2-dense-chat-template.jinja");
 const std::string denseTemplate{std::istreambuf_iterator<char>(denseFile),{}};
 expect(!denseTemplate.empty(),"pinned Dense template fixture exists");
 resetTranslation();state.chat=denseTemplate.c_str();
 auto dense=json::parse(translate("Turn the ignition off."));
 const std::string denseInstruction="Translate the following text into Russian. Note that you should only output the translated result without any additional explanation:\n\nTurn the ignition off.";
 expect(dense.value("text","")=="Hi"&&state.templateCalls==0,"Dense bypasses the incorrect heuristic formatter");
 expect(state.lastPrompt=="<｜hy_begin▁of▁sentence｜><｜hy_User｜>"+denseInstruction+"<｜hy_Assistant｜>","Dense has exactly one BOS, User before instruction and Assistant at generation");
 resetTranslation();state.chat=denseTemplate.c_str();state.templateEndOverride=8;state.sampleMode=3;
 expect(json::parse(translate()).value("text","")=="Hi"&&state.samples==2,"Dense stops at explicit assistant token, not the conflicting generic vocabulary EOG");
 resetTranslation();state.chat=denseTemplate.c_str();translate("Turn the ignition off.","ignition=зажигание","Vehicle manual");
 expect(state.lastPrompt=="<｜hy_begin▁of▁sentence｜><｜hy_User｜>Reference the following translations:\nignition=зажигание\n\n[Background Information]\nVehicle manual\n"+denseInstruction+"<｜hy_Assistant｜>","glossary and context remain inside the Dense user turn");
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
 for(const auto& text:{"[[FVSYMA]]"," \t[[FVSYMA]] [[FVSYMB]]\n"}){
  resetTranslation();const auto unchanged=json::parse(translate(text));
  expect(unchanged.value("text","")==text&&unchanged.value("verbatim",false)&&unchanged.value("tokens",-1)==0&&state.decodes==0&&state.samples==0,
   "source-owned pictograms alone are preserved without model inference");
 }
 resetTranslation();error(translate("[[FVSYMA]] press now"),"changed an inline symbol token");

 // Exercise prefix reuse against a non-null opaque memory handle. The fake
 // memory is never dereferenced; seq_rm and decode record the cache contract.
 state.cacheTestMode=true;state.fakeMemory=true;state.seqRmSuccess=true;state.seqRmCalls=state.memoryClears=0;
 reload();resetTranslation();
 auto first=json::parse(translate("Cache identical text"));
 expect(!first.contains("error")&&first.value("inputTokens",0)>1&&first.value("cachedInputTokens",-1)==0,"initial cache prompt is fully prefetched");
 resetTranslation();int decodedBefore=state.decodes;auto identical=json::parse(translate("Cache identical text"));
 expect(identical.value("cachedInputTokens",-1)==identical.value("inputTokens",0)-1,"identical prompt re-decodes its final token");
 expect(state.seqRmCalls==1&&state.seqRmStart==identical.value("inputTokens",0)-1&&state.seqRmEnd==-1,"identical prompt removes only the prior generated suffix");
 expect(state.decodes-decodedBefore==2,"cache hit decodes the final input token and generated token");

 // Changed/shorter leading context reuses only the exact common token prefix.
 resetTranslation();auto longContext=json::parse(translate("Context sample","","Background ABCDE"));
 resetTranslation();auto shortContext=json::parse(translate("Context sample","","Background AB"));
 expect(shortContext.value("cachedInputTokens",-1)>0&&shortContext.value("cachedInputTokens",0)<shortContext.value("inputTokens",0)-1,"shorter context reuses only its shared prefix");
 resetTranslation();translatepdf::translate("Glossary sample","TERM=one","","en","ru");
 resetTranslation();auto changedGlossary=json::parse(translatepdf::translate("Glossary sample","TERM=two","","en","ru"));
 expect(changedGlossary.value("cachedInputTokens",-1)>0&&changedGlossary.value("cachedInputTokens",0)<changedGlossary.value("inputTokens",0)-1,"changed glossary stops reuse at its first mismatch");

 // A failed sequence trim clears memory and safely falls back to full prefill.
 state.seqRmSuccess=false;int clearsBefore=state.memoryClears;resetTranslation();auto trimFailure=json::parse(translate("Glossary sample","TERM=two"));
 expect(trimFailure.value("cachedInputTokens",-1)==0&&state.memoryClears==clearsBefore+1,"failed sequence removal clears memory and prefills all tokens");
 state.seqRmSuccess=true;

 // Failed prefill must not publish a reusable prompt; retry starts from zero.
 resetTranslation();state.decodeMode=2;error(translate("Prefill retry"),"reading input");int removesBeforeRetry=state.seqRmCalls;
 resetTranslation();auto prefillRetry=json::parse(translate("Prefill retry"));
 expect(prefillRetry.value("cachedInputTokens",-1)==0&&state.seqRmCalls==removesBeforeRetry,"prefill failure leaves no cache for retry");

 // A cancelled generation keeps the successfully prefetched prompt, while
 // the next run trims generated tokens and still re-decodes the prompt tail.
 resetTranslation();state.decodeMode=6;error(translate("Generation retry"),"Canceled");
 reload();resetTranslation();auto generationRetry=json::parse(translate("Generation retry"));
 expect(generationRetry.value("cachedInputTokens",-1)==generationRetry.value("inputTokens",0)-1,"generation cancellation retains only the reusable prompt");

 // An exact configuration load preserves memory; release/reload clears it.
 int loadsBeforeHit=state.modelLoads;reload();expect(state.modelLoads==loadsBeforeHit,"same configuration load is a cache-preserving hit");
 translatepdf::releaseModel();int loadsBeforeReload=state.modelLoads;reload();expect(state.modelLoads==loadsBeforeReload+1,"released model reloads a fresh context");
 resetTranslation();auto afterReload=json::parse(translate("Generation retry"));
 expect(afterReload.value("cachedInputTokens",-1)==0,"release and reload clears cached prompt tokens");

 // TranslateGemma has sliding-window KV semantics and must never use this cache.
 reload("boundary-model",2048,2,"translategemma");resetTranslation();int trimsBeforeGemma=state.seqRmCalls;
 auto gemmaFirst=json::parse(translate("Gemma bypass"));resetTranslation();auto gemmaSecond=json::parse(translate("Gemma bypass"));
 expect(!gemmaFirst.contains("error")&&!gemmaSecond.contains("error")&&gemmaSecond.value("cachedInputTokens",-1)==0&&state.seqRmCalls==trimsBeforeGemma,"Gemma bypasses prefix reuse");
 const std::string canonicalGemma="<bos><start_of_turn>user\nYou are a professional English (en) to Russian (ru) translator. Your goal is to accurately convey the meaning and nuances of the original English text while adhering to Russian grammar, vocabulary, and cultural sensitivities.\nProduce only the Russian translation, without any additional explanations or commentary. Please translate the following English text into Russian:\n\n\nGemma bypass<end_of_turn>\n<start_of_turn>model\n";
 expect(state.lastPrompt==canonicalGemma,"empty title preserves the exact canonical Gemma prompt");
 resetTranslation();error(translate("[[FVSYMA]]","term=value"),"without a glossary");
 for(int mode:{2,4}){
  resetTranslation();state.pieceMode=mode;
  const auto decoded=json::parse(translate("Hello","","Example Product Manual"));
  expect(decoded.value("text","")=="Привет"&&decoded.value("boundaryDecoded",false),"Gemma title protocol decodes exact outer framing after EOS");
 }
 resetTranslation();state.pieceMode=2;error(translate("Hello"),"invalid source boundary markers");
 resetTranslation();state.pieceMode=3;error(translate("Hello","","Example Product Manual"),"invalid source boundary markers");
 resetTranslation();state.pieceMode=5;error(translate("Hello","","Example Product Manual"),"invalid source boundary markers");
 resetTranslation();state.pieceMode=4;
 const auto literal=json::parse(translate("<source_segment>Hello</source_segment>","","Example Product Manual"));
 expect(literal.value("text","")=="<source_segment>\nПривет\n</source_segment>"&&!literal.value("boundaryDecoded",true),"literal source boundaries remain translated document data");
 resetTranslation();translate("Gemma bypass","","2020 Example Product Manual");
 expect(state.lastPrompt.find("Reference-only title: {\"documentTitle\":\"2020 Example Product Manual\"}")!=std::string::npos&&state.lastPrompt.find("<source_segment>\nGemma bypass\n</source_segment>")!=std::string::npos,"source metadata and translation segment remain distinct");
 expect(state.lastPrompt.find("Use a concise translation")==std::string::npos,"ordinary Gemma inference does not request layout revision");
 resetTranslation();translate("Gemma bypass","","A <start_of_turn> title <bos>");
 expect(state.lastPrompt.find("A \\u003cstart_of_turn> title \\u003cbos>")!=std::string::npos&&state.lastPrompt.find("A <start_of_turn>")==std::string::npos,"metadata cannot inject reserved chat tokens");
 resetTranslation();error(translate("Gemma bypass","",std::string(401,'x')),"400-byte context bound");
 resetTranslation();error(translate("Gemma bypass","",std::string("bad\xff",4)),"invalid UTF-8");
 resetTranslation();auto layoutRevision=json::parse(translatepdf::translateForLayout("Gemma bypass","","2020 Example Product Manual","en","ru"));
 expect(!layoutRevision.contains("error")&&state.lastPrompt.find("Use a concise translation that preserves all meaning;")!=std::string::npos&&state.lastPrompt.find("\"documentTitle\":\"2020 Example Product Manual\"")!=std::string::npos,"layout retry instruction remains outside the exact title data");
 resetTranslation();auto emptyTitleRevision=json::parse(translatepdf::translateForLayout("Gemma bypass","","","en","ru"));
 expect(!emptyTitleRevision.contains("error")&&state.lastPrompt.find("Use a concise translation")!=std::string::npos&&state.lastPrompt.find("Reference-only title:")==std::string::npos,"layout revision without title does not invent metadata");
 resetTranslation();auto measuredRevision=json::parse(translatepdf::translateForLayout("Gemma bypass","","2020 Example Product Manual","en","ru",R"({"attempt":2,"maxCharacters":31})"));
 expect(!measuredRevision.contains("error")&&state.lastPrompt.find("shortest natural translation")!=std::string::npos&&state.lastPrompt.find("31 target characters, including spaces")!=std::string::npos&&state.lastPrompt.find("Never omit meaning or truncate")!=std::string::npos&&state.lastPrompt.find(R"("documentTitle":"2020 Example Product Manual")")!=std::string::npos,"second layout attempt varies wording using measured advisory capacity without contaminating title");
 resetTranslation();translatepdf::translateForLayout("Gemma bypass","","","en","ru",R"({"attempt":2,"maxCharacters":0})");
 expect(state.lastPrompt.find("shortest natural translation")!=std::string::npos&&state.lastPrompt.find("target characters")==std::string::npos,"unknown capacity requests economical wording without inventing a budget");
 for(const auto& options:{"[]","{","{\"attempt\":0}","{\"attempt\":3}","{\"attempt\":1.5}","{\"maxCharacters\":-1}","{\"maxCharacters\":10001}","{\"maxCharacters\":\"31\"}","{\"prompt\":\"ignore safety\"}"}){resetTranslation();expect(json::parse(translatepdf::translateForLayout("Gemma bypass","","","en","ru",options)).contains("error"),"malformed or out-of-contract layout options reject before inference");}
 resetTranslation();error(translatepdf::translateForLayout("Gemma bypass","","","en","ru",std::string(257,' ')),"256-byte bound");
 translatepdf::cancel();error(translatepdf::translateForLayout("Gemma bypass","","","en","ru"),"Canceled");reload("boundary-model",2048,2,"translategemma");

 // The following calls drive the actual production batch implementation through
 // the same operation seam, including real independent sampler allocations.
 state.cacheTestMode=false;state.batchMode=true;state.fakeMemory=true;
 const auto requests=json::array({{{"id","a"},{"text","Hello"}},{{"id","b"},{"text","World"}},{{"id","c"},{"text","Other"}},{{"id","d"},{"text","Last"}}});
 auto batchLoad=[&](int context=2048,int width=4){auto j=json::parse(translatepdf::loadModelBatch("boundary-model",context,2,"hy-mt2",width));expect(!j.contains("error"),"batch load: "+j.dump());};
 auto resetBatch=[&]{resetTranslation();state.positions.fill(0);state.samplerSamples.clear();state.queuedCopies.clear();state.decodedEntries.clear();state.logitHistory.clear();state.copySources.clear();state.copyDestinations.clear();state.copyPending=false;state.seqCpCalls=state.seqCpThrowAt=state.seqCpCancelAt=0;state.customPromptMode=false;state.customPromptLength=64;state.customPromptCommon=60;state.sourceTokensOverride=state.promptTokensOverride=0;state.soloLength=1;state.lengths={1,2,3,4,1,1,1,1};state.seenBatchEntries=state.seenPrefixCalls=0;state.sampleCancelAt=state.sampleTotal=0;state.eogCancel=state.tokenCancel=false;};
 std::vector<json> delivered;
 auto sink=[&](const std::string& text){delivered.push_back(json::parse(text));return 1;};
 auto batchRun=[&](json values=nullptr,const translatepdf::BatchCompletion& callback=translatepdf::BatchCompletion{}){if(values.is_null())values=requests;return json::parse(translatepdf::translateBatch(values.dump(),"","","en","ru",71,callback?callback:sink));};
 error(translatepdf::translateBatch(requests.dump(),"","","en","ru",71,sink),"session is not loaded");
 for(int width:{0,2,8})error(translatepdf::loadModelBatch("boundary-model",2048,2,"hy-mt2",width),"Unsupported Hy-MT2");
 error(translatepdf::loadModelBatch("boundary-model",1024,2,"hy-mt2",4),"Unsupported Hy-MT2");
 error(translatepdf::loadModelBatch("boundary-model",2048,2,"translategemma",4),"Unsupported Hy-MT2");
 expect(!ops::shouldReuseExactPrefix({},0)&&!ops::shouldReuseExactPrefix({60},60)&&!ops::shouldReuseExactPrefix({60,60},0),"prefix sharing requires multiple active lanes and nonempty common input");
 expect(!ops::shouldReuseExactPrefix({100,100},50,0),"prefix sharing is disabled when physical microbatch size is invalid");
 expect(ops::shouldReuseExactPrefix({100,100},50)&&ops::shouldReuseExactPrefix({129,129},127),"prefix sharing accepts equal or lower physical prefill-call cost");
 expect(!ops::shouldReuseExactPrefix({50,50,50,50},1),"prefix sharing skips a short prefix that adds a weight pass");
 expect(!ops::shouldReuseExactPrefix({10,20},11),"invalid prefix longer than one prompt is rejected");
 expect(!ops::shouldReuseExactPrefix({static_cast<size_t>(-1),1},1),"overflowing prefill cost is rejected");
 batchLoad();resetBatch();delivered.clear();
 const int loadsBeforeBatch=state.modelLoads;
 auto successful=batchRun();
 expect(successful["committedIds"].size()==4&&successful["pendingIds"].empty()&&successful["finalErrors"].empty(),"independent requests commit only after EOS");
 expect(delivered.size()==4&&delivered[0]["generation"]==71&&!delivered[0]["soloRetry"].get<bool>(),"callback binds generation and batch provenance");
 expect(delivered[0]["tokens"]==1&&delivered[3]["tokens"]==4,"per-sequence sampler state and unequal EOS remain independent");
 expect(state.seenPrefixCalls==1&&state.seqCpCalls==3&&state.copySources[0]==0&&state.copyDestinations[0]==1,"shared prefix decodes once from the first active lane then copies to peers");
 resetBatch();delivered.clear();state.chat=denseTemplate.c_str();state.templateEndOverride=8;
 const auto denseBatch=batchRun();
 expect(denseBatch["committedIds"].size()==4&&denseBatch["finalErrors"].empty()&&delivered.size()==4,"Dense batch finishes all lanes at their explicit assistant stop token");
 expect(state.sampleTotal==14&&delivered[3]["tokens"]==4&&delivered[3]["text"]=="HiHiHiHi","batch ignores conflicting generic EOG before the distinct assistant stop");
 expect(state.templateCalls==0&&state.lastPrompt=="<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate the following text into Russian. Note that you should only output the translated result without any additional explanation:\n\nLast<｜hy_Assistant｜>","batch uses the same verified Dense single-user serialization");

 expect(state.seenBatchEntries==17&&state.positions[0]==0&&state.positions[3]==0,"physical decode entries include one donor prefix plus all final prompts and generated tokens; cleanup resets KV positions");
 expect(state.samplerSamples.empty(),"samplers cleaned after all EOS");
 auto progress=json::parse(translatepdf::inferenceProgress());expect(progress["generatedTokens"]==10&&progress["processedTokens"]==17,"batch progress counts physical shared input and actual generated tokens");
 resetBatch();state.customPromptMode=true;state.customPromptLength=64;state.customPromptCommon=60;
 auto shortShared=batchRun(json::array({requests[0],requests[1]}));
 expect(shortShared["committedIds"].size()==2&&state.seqCpCalls==0&&state.seenPrefixCalls==1&&state.seenBatchEntries==131,"production skips shared prefix when its suffix would add a physical prefill call");
 resetBatch();state.customPromptMode=true;state.customPromptLength=101;state.customPromptCommon=50;
 auto divergentShared=batchRun(json::array({requests[0],requests[1]}));
 expect(divergentShared["committedIds"].size()==2&&state.seqCpCalls==1&&state.copySources[0]==0&&state.copyDestinations[0]==1,"production admits exact prefix with divergent nonempty suffixes when physical prefill calls do not increase");
 expect(state.seenPrefixCalls==2&&state.seenBatchEntries==155&&state.decodedEntries.size()==155,"shared prefix plus both 50-token suffixes and final prompts are physically decoded exactly once");
 for(int pos=0;pos<50;++pos){const auto [token,at,seq]=state.decodedEntries[pos];expect(token==2&&at==pos&&seq==0,"shared prefix entries retain donor positions");}
 for(int offset=0;offset<50;++offset){const auto [donorToken,donorPos,donorSeq]=state.decodedEntries[50+offset];const auto [peerToken,peerPos,peerSeq]=state.decodedEntries[100+offset];expect(donorToken==1050+offset&&donorPos==50+offset&&donorSeq==0,"donor suffix retains unique prompt tokens and positions");expect(peerToken==1150+offset&&peerPos==50+offset&&peerSeq==1,"peer suffix retains unique prompt tokens and copied positions");}
 expect(std::get<0>(state.decodedEntries[150])==1100&&std::get<1>(state.decodedEntries[150])==100&&std::get<2>(state.decodedEntries[150])==0&&std::get<0>(state.decodedEntries[151])==1200&&std::get<1>(state.decodedEntries[151])==100&&std::get<2>(state.decodedEntries[151])==1&&state.logitHistory.size()==5&&state.logitHistory[2]==std::vector<int>({0,1}),"divergent lanes preserve final prompt tokens, positions, and logits");
 resetBatch();state.customPromptMode=true;state.customPromptLength=64;state.customPromptCommon=0;
 auto disjointPrompts=batchRun(json::array({requests[0],requests[1],requests[2]}));
 expect(disjointPrompts["committedIds"].size()==3&&state.seqCpCalls==0&&state.seenPrefixCalls==2&&state.seenBatchEntries==198,"three valid prompts with zero common prefix use the independent-prefix path");
 for(int seq=0;seq<3;++seq)for(int pos=0;pos<63;++pos){const auto [token,at,actualSeq]=state.decodedEntries[seq*63+pos];expect(token==1000+seq*100+pos&&at==pos&&actualSeq==seq,"each disjoint prompt remains complete at its original sequence positions");}
 expect(std::get<0>(state.decodedEntries[189])==1063&&std::get<1>(state.decodedEntries[189])==63&&std::get<2>(state.decodedEntries[189])==0&&std::get<0>(state.decodedEntries[190])==1163&&std::get<1>(state.decodedEntries[190])==63&&std::get<2>(state.decodedEntries[190])==1&&std::get<0>(state.decodedEntries[191])==1263&&std::get<1>(state.decodedEntries[191])==63&&std::get<2>(state.decodedEntries[191])==2&&state.logitHistory.size()==6&&state.logitHistory[2]==std::vector<int>({0,1,2}),"zero-prefix prompts retain all final tokens and logits");
 resetBatch();state.promptTokensOverride=300;auto multiChunk=batchRun();
 expect(multiChunk["committedIds"].size()==4&&state.seenPrefixCalls==3&&state.seqCpCalls==3&&state.seenBatchEntries==313,"long shared prefix uses all chunks once and counts physical prompt plus generation tokens");
 resetBatch();auto nonzeroDonor=batchRun(json::array({{{"id","empty-first"},{"text",""}},requests[1],requests[2],requests[3]}));
 expect(nonzeroDonor["finalErrors"].size()==1&&nonzeroDonor["committedIds"].size()==3&&state.seqCpCalls==2&&state.copySources[0]==1,"first active request donates prefix from nonzero sequence");
 json requests8=json::array();for(int i=0;i<8;++i)requests8.push_back({{"id","wide-"+std::to_string(i)},{"text","Text lane"+std::string(1,static_cast<char>('A'+i))}});
 batchLoad(4096,8);resetBatch();auto wide=batchRun(requests8);
 expect(wide["committedIds"].size()==8&&wide["pendingIds"].empty()&&state.sequences==8&&state.seqCpCalls==7,"eight independent lanes share one prefix in the allowed 4096 context");
 error(translatepdf::loadModelBatch("boundary-model",2048,2,"hy-mt2",8),"Unsupported Hy-MT2");
 batchLoad();resetBatch();
 state.decodeMode=4;const int freeBeforePreCopy=state.contextFreed,clearBeforePreCopy=state.memoryClears;auto preCopyCancel=batchRun();
 expect(preCopyCancel["canceled"]==true&&state.seqCpCalls==0&&state.contextFreed==freeBeforePreCopy&&state.memoryClears==clearBeforePreCopy+2,"pause before enqueue clears an ordinary reusable context");
 batchLoad();resetBatch();
 auto assertQuarantined=[&](const std::string& label,const json& outcome,int oldContextFrees,int oldClears,size_t expectedPending=4){
  expect(outcome["pendingIds"].size()==expectedPending&&outcome["committedIds"].empty(),label+" leaves every unacknowledged lane pending");
  expect(state.seqCpCalls>0&&state.contextFreed==oldContextFrees+1&&state.memoryClears==oldClears+1&&state.badMemoryClears==0,label+" destroys pending-copy context without post-enqueue memory_clear");
  const int identity=state.contextIdentity;batchLoad();
  expect(state.contextIdentity==identity+1&&state.modelLoads==loadsBeforeBatch,label+" allocates a fresh context without reloading weights");
 };
 auto verifyFreshAfterQuarantine=[&](const std::string& label){
  resetBatch();const int identity=state.contextIdentity;const int loads=state.modelLoads;
  auto fresh=batchRun(json::array({requests[0]}));
  expect(fresh["committedIds"].size()==1&&state.seqCpCalls==0&&state.queuedCopies.empty()&&state.seenBatchEntries==5&&state.contextIdentity==identity&&state.modelLoads==loads,label+" runs successfully in the recreated context without stale copies or weight reload");
 };
 int freeBeforeCopy=0,clearBeforeCopy=0;
 resetBatch();state.seqCpCancelAt=1;freeBeforeCopy=state.contextFreed;clearBeforeCopy=state.memoryClears;
 auto cancelAfterLastCopy=batchRun(json::array({requests[0],requests[1]}));
 expect(cancelAfterLastCopy["canceled"]==true&&state.seqCpCalls==1&&state.decodedEntries.size()==3,"two-lane cancel after final queued copy reaches decodeEntries stop guard");
 assertQuarantined("cancel after last copy before next decode",cancelAfterLastCopy,freeBeforeCopy,clearBeforeCopy,2);
 verifyFreshAfterQuarantine("request after final-copy cancellation");
 resetBatch();state.seqCpCancelAt=1;freeBeforeCopy=state.contextFreed;clearBeforeCopy=state.memoryClears;
 auto copyCancel=batchRun();assertQuarantined("cancel after copy enqueue",copyCancel,freeBeforeCopy,clearBeforeCopy);
 resetBatch();state.seqCpThrowAt=1;freeBeforeCopy=state.contextFreed;clearBeforeCopy=state.memoryClears;
 auto copyThrow=batchRun();assertQuarantined("copy exception",copyThrow,freeBeforeCopy,clearBeforeCopy);
 resetBatch();state.decodeMode=5;freeBeforeCopy=state.contextFreed;clearBeforeCopy=state.memoryClears;
 auto queuedDecodeFailure=batchRun();assertQuarantined("decode failure with queued copy",queuedDecodeFailure,freeBeforeCopy,clearBeforeCopy);
 verifyFreshAfterQuarantine("ordinary post-quarantine request");
 resetBatch();state.customPromptMode=true;state.customPromptLength=101;state.customPromptCommon=50;state.decodeMode=5;freeBeforeCopy=state.contextFreed;clearBeforeCopy=state.memoryClears;
 auto suffixDecodeFailure=batchRun(json::array({requests[0],requests[1]}));
 expect(state.seqCpCalls==1&&state.seenPrefixCalls==2&&state.decodedEntries.size()==150,"decode failure is injected on the first suffix batch after prefix copy");
 assertQuarantined("first suffix decode failure",suffixDecodeFailure,freeBeforeCopy,clearBeforeCopy,2);
 verifyFreshAfterQuarantine("request after suffix decode failure");
 resetBatch();state.customPromptMode=true;state.customPromptLength=101;state.customPromptCommon=50;state.decodeMode=6;freeBeforeCopy=state.contextFreed;clearBeforeCopy=state.memoryClears;
 auto suffixDecodeCancel=batchRun(json::array({requests[0],requests[1]}));
 expect(state.seqCpCalls==1&&state.seenPrefixCalls==2&&state.decodedEntries.size()==150,"cancel is injected on the first suffix batch after prefix copy");
 assertQuarantined("first suffix decode cancellation",suffixDecodeCancel,freeBeforeCopy,clearBeforeCopy,2);
 verifyFreshAfterQuarantine("request after suffix decode cancellation");
 batchLoad();resetBatch();
 resetBatch();delivered.clear();state.lengths={4,1,3,2};auto unequal=batchRun();
 expect(delivered[0]["id"]=="b"&&delivered[1]["id"]=="d"&&unequal["committedIds"].size()==4,"checkpoint callback is immediate and may finish out of source order");
 resetBatch();delivered.clear();auto soloAfter=json::parse(translate("Solo after batch"));
 expect(!soloAfter.contains("error")&&state.modelLoads==loadsBeforeBatch&&state.sequences==1,"legacy solo restores context without reloading weights");
 resetBatch();delivered.clear();batchRun();expect(state.modelLoads==loadsBeforeBatch&&state.sequences==4,"next batch recreates only KV context");
 for(const auto& invalid:{json::object(),json::array(),json::array({requests[0],requests[0]}),json::array({{{"id",""},{"text","Hello"}}}),json::array({{{"id",1},{"text","Hello"}}}),json::array({{{"id","a"}}}),json::array({"non-object"}),json::array({{{"text","Hello"}}}),json::array({{{"id","a"},{"text",8}}})}){
  auto j=batchRun(invalid);expect(j.contains("error")&&!j.contains("committedIds"),"malformed request rejected before callbacks");
 }
 error(translatepdf::translateBatch("not-json","","","en","ru",71,sink),"parse error");
 error(translatepdf::translateBatch(requests.dump(),"","","en","ru",71,{}),"sink is required");
 auto tooMany=requests;tooMany.push_back({{"id","e"},{"text","Extra"}});expect(batchRun(tooMany).contains("error"),"max width admission enforced");
 resetBatch();delivered.clear();auto empty=batchRun(json::array({{{"id","empty"},{"text",""}}}));expect(empty["finalErrors"].size()==1&&delivered.empty(),"empty request never becomes accepted translation");
 for(int ack:{0,2,8}){resetBatch();delivered.clear();auto stoppedResult=batchRun(requests,[&](const std::string& r){delivered.push_back(json::parse(r));return ack;});expect(delivered.size()==1&&stoppedResult["canceled"]==true&&stoppedResult["committedIds"].size()==(ack==2?1:0),"sink ACK commits then stops or rejects then stops");}
 resetBatch();delivered.clear();auto thrown=batchRun(requests,[](const std::string&)->int{throw std::runtime_error("sink-disk-error");});expect(thrown["pendingIds"].size()==4&&thrown["error"]=="sink-disk-error","sink exception leaves all unacknowledged IDs pending");
 resetBatch();auto unknownThrown=batchRun(requests,[](const std::string&)->int{throw 7;});expect(unknownThrown["error"].get<std::string>().find("sink failed")!=std::string::npos,"nonstandard sink exception is fail closed");
 resetBatch();translatepdf::cancel();auto canceledBefore=batchRun();expect(canceledBefore["canceled"]==true&&state.decodes==0,"batch entry never resets a pending cancel");batchLoad();
 for(int mode:{1,2,3,4,5,6}){resetBatch();state.decodeMode=mode;auto stoppedResult=batchRun();expect(stoppedResult.contains("error")&&stoppedResult["committedIds"].empty()&&stoppedResult["pendingIds"].size()==4,"decode/cancel failure leaves uncommitted blocks pending");batchLoad();}
 resetBatch();delivered.clear();auto cancelSink=batchRun(requests,[&](const std::string& r){delivered.push_back(json::parse(r));translatepdf::cancel();return 1;});expect(cancelSink["committedIds"].size()==1&&cancelSink["canceled"]==true,"earlier acknowledged block survives cancel before peer delivery");batchLoad();
 resetBatch();state.pieceMode=1;auto badPiece=batchRun();expect(badPiece["pendingIds"].size()==4&&badPiece.contains("error"),"piece decoder failure never publishes partial text");
 resetBatch();state.seqRmSuccess=false;auto badRelease=batchRun();expect(badRelease["committedIds"].size()==1&&badRelease["pendingIds"].size()==3,"KV release failure preserves already committed peer");state.seqRmSuccess=true;
 // More than70 source tokens is allowed when the real answer fits. The old
 // conservative full4x budget would reject this prompt at512 capacity.
 resetBatch();delivered.clear();state.sourceTokensOverride=80;state.promptTokensOverride=200;auto optimistic=batchRun();expect(optimistic["committedIds"].size()==4&&delivered[0]["soloRetry"]==false,"optimistic admission does not force full4x output headroom");
 // Force one lane to run past its batch budget; only its full solo retry may
 // be accepted and peers are durably delivered before that retry begins.
 resetBatch();delivered.clear();state.sourceTokensOverride=80;state.promptTokensOverride=400;state.lengths={108,1,1,1};auto overflow=batchRun();expect(overflow["committedIds"].size()==4&&delivered.back()["id"]=="a"&&delivered.back()["soloRetry"]==true&&delivered.back()["tokens"]==1,"overflow discards tentative answer and retries full original once");expect(state.modelLoads==loadsBeforeBatch,"fallback context change retains weights");
 resetBatch();delivered.clear();state.promptTokensOverride=413;auto direct=batchRun();expect(direct["committedIds"].size()==4&&delivered[0]["soloRetry"]==true&&state.seenBatchEntries==0,"large prompt uses complete solo fallback directly");
 resetBatch();delivered.clear();state.promptTokensOverride=412;state.lengths={1,1,1,1};auto boundary=batchRun();expect(boundary["committedIds"].size()==4&&delivered[0]["soloRetry"]==false,"exact96 token headroom remains legal");
 resetBatch();state.promptTokensOverride=1665;auto tooLong=batchRun();expect(tooLong["finalErrors"].size()==4&&state.decodes==0,"source exceeding original solo context is rejected without truncation");
 resetBatch();delivered.clear();auto guard=batchRun(json::array({{{"id","number"},{"text","Keep 128"}}}));expect(guard["finalErrors"].size()==1&&delivered.empty(),"failed protected guard gets one full solo retry then final review error");
 resetBatch();delivered.clear();state.lengths={0,0,0,0};auto emptyAnswers=batchRun();expect(emptyAnswers["committedIds"].size()==4&&delivered[0]["soloRetry"]==true,"empty batch answer retries genuine nonempty solo EOS");
 resetBatch();state.promptTokensOverride=413;state.soloLength=1000;auto soloLimit=batchRun(json::array({requests[0]}));expect(soloLimit["finalErrors"].size()==1&&soloLimit["committedIds"].empty(),"solo output limit never commits partial answer");
 resetBatch();state.promptTokensOverride=413;state.soloLength=0;auto soloEmpty=batchRun(json::array({requests[0]}));expect(soloEmpty["finalErrors"].size()==1,"empty solo output remains final error");
 resetBatch();state.promptTokensOverride=413;state.decodeMode=2;auto soloOperational=batchRun(json::array({requests[0]}));expect(soloOperational["pendingIds"].size()==1&&soloOperational["finalErrors"].empty(),"solo operational failure stays resumable pending");
 resetBatch();state.promptTokensOverride=413;state.decodeMode=3;auto soloCancel=batchRun(json::array({requests[0]}));expect(soloCancel["canceled"]==true&&soloCancel["pendingIds"].size()==1,"solo retry does not swallow cancellation");batchLoad();
 resetBatch();state.promptTokensOverride=413;state.tokenCancel=true;auto beforeSolo=batchRun(json::array({requests[0]}));expect(beforeSolo["canceled"]==true&&state.decodes==0&&beforeSolo["pendingIds"].size()==1,"cancellation after admission starts no solo fallback inference");batchLoad();
 resetBatch();state.templateMode=1;auto badTemplate=batchRun();expect(badTemplate["pendingIds"].size()==4&&badTemplate.contains("error"),"invalid model template stops pending work");
 resetBatch();state.capacityOverride=511;auto badCapacity=batchRun();expect(badCapacity["pendingIds"].size()==4&&badCapacity.contains("error"),"actual runtime capacity is enforced");error(translatepdf::loadModelBatch("boundary-model",2048,2,"hy-mt2",4),"fewer than512");state.capacityOverride=0;batchLoad();
 resetBatch();state.loadFailure=3;const int modelBeforeFailure=state.modelFreed;error(translatepdf::loadModelBatch("boundary-model",4096,2,"hy-mt2",4),"Insufficient resources");expect(state.modelFreed==modelBeforeFailure,"context-only allocation failure retains mapped weights");auto noContext=batchRun();expect(noContext["pendingIds"].size()==4&&noContext.contains("error"),"failed context can be retried without dereferencing null");state.loadFailure=0;batchLoad(4096,1);resetBatch();auto widthOne=batchRun(json::array({requests[0]}));expect(widthOne["committedIds"].size()==1,"explicit one-sequence batch configuration supported");
 resetBatch();batchLoad();
 for(const char* chat:{"<｜hy_Assistant｜><｜hy_place▁holder▁no▁2｜>","<|eos|>"}){resetBatch();state.chat=chat;expect(batchRun()["committedIds"].size()==4,"batch honors pinned explicit assistant end token");}

 resetBatch();state.lengths={4,1,3,2};auto lateAck=batchRun(requests,[](const std::string&){return 2;});expect(lateAck["committedIds"].size()==1,"sink stop after earlier running lane does not decode it again");
 for(int at:{1,4}){resetBatch();state.sampleCancelAt=at;auto canceledSample=batchRun();expect(canceledSample["canceled"]==true&&canceledSample["committedIds"].empty(),"cancel between samples or before output processing is observed");batchLoad();}
 resetBatch();state.eogCancel=true;delivered.clear();auto pausedEog=batchRun(requests,[&](const std::string& value){delivered.push_back(json::parse(value));return 2;});expect(pausedEog["canceled"]==true&&pausedEog["committedIds"].size()==1&&delivered.size()==1,"Pause between computed EOS and delivery preserves authoritative ACK2 checkpoint");batchLoad();
 resetBatch();state.eogCancel=true;auto canceledEog=batchRun(requests,[](const std::string&){return 0;});expect(canceledEog["canceled"]==true&&canceledEog["committedIds"].empty(),"Cancel at computed EOS lets authoritative sink reject stale/canceled job");batchLoad();
 resetBatch();auto canceledLast=batchRun(json::array({requests[0]}),[](const std::string&){translatepdf::cancel();return 1;});expect(canceledLast["committedIds"].size()==1&&canceledLast["canceled"]==true,"cancel after last EOS is retained before fallback iteration");batchLoad();
 resetBatch();auto sameLanguages=json::parse(translatepdf::translateBatch(requests.dump(),"","","en","en",71,sink));expect(sameLanguages.contains("error"),"batch validates distinct languages");
 resetBatch();auto otherLanguages=json::parse(translatepdf::translateBatch(requests.dump(),"","","fr","de",71,sink));expect(otherLanguages["committedIds"].size()==4,"batch preserves all supported language prompts");
 resetBatch();auto enFrench=json::parse(translatepdf::translateBatch(requests.dump(),"","","en","fr",71,sink));expect(enFrench["committedIds"].size()==4,"batch supports English to non-Russian target");
 const auto invariantRequests=json::array({{{"id","number"},{"text","0 148."}},{{"id","icon"},{"text",">"}}});
 resetBatch();delivered.clear();auto invariant=batchRun(invariantRequests,[&](const std::string& value){delivered.push_back(json::parse(value));return 1;});
 expect(invariant["committedIds"].size()==2&&state.seenBatchEntries==0&&state.sampleTotal==0,"language-invariant batch blocks do not decode or sample");
 expect(delivered[0]["text"]=="0 148."&&delivered[1]["text"]==">"&&delivered[0]["tokens"]==0&&delivered[0]["verbatim"]==true&&delivered[0]["soloRetry"]==false,"verbatim callback reports exact source and actual zero inference");
 resetBatch();auto invariantPaused=batchRun(invariantRequests,[](const std::string&){return 2;});expect(invariantPaused["committedIds"].size()==1&&invariantPaused["pendingIds"].size()==1&&invariantPaused["canceled"]==true,"verbatim callback obeys durable Pause ACK");
 resetBatch();auto invariantRejected=batchRun(invariantRequests,[](const std::string&){return 0;});expect(invariantRejected["committedIds"].empty()&&invariantRejected["pendingIds"].size()==2,"verbatim callback obeys stale/cancel rejection");
 resetBatch();auto invariantSame=json::parse(translatepdf::translateBatch(invariantRequests.dump(),"","","en","en",71,sink));expect(invariantSame.contains("error"),"verbatim batch still validates distinct languages");
 resetBatch();auto invariantUnknown=json::parse(translatepdf::translateBatch(invariantRequests.dump(),"","","invalid","ru",71,sink));expect(invariantUnknown.contains("error"),"verbatim batch still validates supported languages");
 // An invariant lane keeps its source ID, but never takes a KV sequence or
 // sampler from ordinary peers. Their exact source-position IDs still matter.
 const auto mixedInvariant=json::array({invariantRequests[0],requests[1],requests[2],requests[3]});
 const std::vector<std::string> mixedOrder{"b","d","c","number"};
 const std::map<std::string,std::string> mixedText{{"b","Hi"},{"c","HiHiHi"},{"d","HiHi"},{"number","0 148."}};
 auto checkMixedCallbacks=[&]{
  for(size_t i=0;i<delivered.size();++i){
   const auto id=delivered[i].at("id").get<std::string>();
   expect(i<mixedOrder.size()&&id==mixedOrder[i]&&delivered[i]["generation"]==71,"mixed callbacks retain original lane identity and run generation in completion order");
   expect(delivered[i]["text"]==mixedText.at(id)&&delivered[i]["soloRetry"]==false,"mixed callback preserves the complete independent peer or exact invariant source");
   expect(delivered[i].value("verbatim",false)==(id=="number"),"only the invariant lane reports verbatim provenance");
   expect(delivered[i]["tokens"]==(id=="number"?0:id=="b"?1:id=="d"?2:3),"mixed callbacks keep actual per-lane generated token counts");
  }
 };
 resetBatch();delivered.clear();state.lengths={0,1,3,2,1,1,1,1};const int trimsBeforeMixed=state.seqRmCalls;
 auto mixedComplete=batchRun(mixedInvariant);checkMixedCallbacks();
 expect(mixedComplete["committedIds"]==json::array({"number","b","c","d"})&&mixedComplete["pendingIds"].empty()&&mixedComplete["finalErrors"].empty()&&delivered.size()==4,"mixed ordinary and invariant lanes each commit exactly once after their own accepted callback");
 expect(state.copySources==std::vector<int>({1,1})&&state.copyDestinations==std::vector<int>({2,3})&&state.seqRmCalls==trimsBeforeMixed+3,"nonzero ordinary donor copies only to active peers, which retire independently");
 expect(state.seenBatchEntries==12&&state.sampleTotal==9&&state.samplerSamples.empty(),"mixed input decodes one three-token prefix, three final prompts and six generated peer tokens without numeric inference");
 for(const auto& entry:state.decodedEntries)expect(std::get<2>(entry)!=0,"invariant lane0 never enters prefill or generation");
 expect(state.logitHistory.size()>1&&state.logitHistory[1]==std::vector<int>({1,2,3}),"mixed final prompts expose logits for original active sequence IDs only");
 for(const int ack:{0,2})for(const std::string stopId:{std::string("b"),std::string("number")}){
  resetBatch();delivered.clear();state.lengths={0,1,3,2,1,1,1,1};
  auto outcome=batchRun(mixedInvariant,[&](const std::string& value){auto response=json::parse(value);delivered.push_back(response);return response["id"]==stopId?ack:1;});
  checkMixedCallbacks();const size_t earlier=stopId=="number"?3:0,accepted=earlier+(ack==2?1:0);
  expect(delivered.size()==earlier+1&&outcome["committedIds"].size()==accepted&&outcome["pendingIds"].size()==4-accepted&&outcome["finalErrors"].empty()&&outcome["canceled"]==true,"mixed ACK2 commits its exact owner then stops; ACK0 rejects it and preserves only previously accepted peers");
  expect((std::find(outcome["committedIds"].begin(),outcome["committedIds"].end(),stopId)!=outcome["committedIds"].end())==(ack==2),"mixed stop callback cannot commit a rejected owner");
 }
 resetBatch();auto glossaryNumbers=json::parse(translatepdf::translateBatch(invariantRequests.dump(),"1 = first","","en","ru",71,sink));expect(state.seenBatchEntries>0&&!glossaryNumbers["finalErrors"].empty(),"an explicit glossary cannot be bypassed by invariant source handling; model output still faces its numeric guard");
 error(translatepdf::loadModel("boundary-model",2048,2,"unknown"),"Unknown translation profile");
 batchLoad(2048,1);resetBatch();expect(!json::parse(translate("Solo width one")).contains("error"),"single-lane batch switches same-size context policy without weight reload");
 reload("boundary-model",2048,2,"translategemma");resetTranslation();error(translate("Hello","saved term"),"without a glossary");
 resetTranslation();error(translatepdf::translate("Hello","","","en","en"),"must differ");expect(json::parse(translate(""))["skipped"]==true,"legacy empty fast path preserved");
 resetTranslation();auto invariantSolo=json::parse(translate(">"));expect(invariantSolo["text"]==">"&&invariantSolo["tokens"]==0&&invariantSolo["inputTokens"]==0&&invariantSolo["verbatim"]==true&&state.decodes==0,"solo numeric/punctuation source bypasses model decoding");
 error(translatepdf::translate("42","","","en","en"),"must differ");
 error(translate("42","saved term"),"without a glossary");
 translatepdf::releaseModel();expect(batchRun().contains("error"),"unloaded batch rejects before accessing model");
 resetBatch();state.batchMode=false;
 reload();state.cacheTestMode=false;state.fakeMemory=false;
 translatepdf::releaseModel();error(translate(),"Model is not loaded");
}

}
void realBatch(const std::string& modelPath,const std::string& manifest){
 json cases;std::ifstream(manifest)>>cases;expect(cases.size()==4,"real control has four sources");
 translatepdf::setWeightRepackingAllowed(false);
 auto loaded=json::parse(translatepdf::loadModelBatch(modelPath,2048,4,"hy-mt2",4));expect(!loaded.contains("error"),"real pinned batch model loads");
 std::map<std::string,std::string> solo;
 for(const auto& item:cases){auto out=json::parse(translatepdf::translate(item["text"],"","","en","ru"));expect(!out.contains("error"),"real solo control EOS+guard");solo[item["id"]]=out["text"];}
 std::vector<json> completed;
 auto summary=json::parse(translatepdf::translateBatch(cases.dump(),"","","en","ru",88,[&](const std::string& text){completed.push_back(json::parse(text));return 1;}));
 expect(summary["committedIds"].size()==4&&summary["finalErrors"].empty()&&summary["pendingIds"].empty(),"real batch all EOS+guard");
 int equal=0;for(const auto& out:completed){expect(!out["text"].get<std::string>().empty()&&out["generation"]==88,"real completed callback identity");equal+=solo[out["id"]]==out["text"];}
 std::cout<<json{{"realBatchSummary",summary},{"realBatchResults",completed},{"sameAsSolo",equal}}<<"\n";

 auto paused=json::parse(translatepdf::translateBatch(cases.dump(),"","","en","ru",90,[&](const std::string&){translatepdf::cancel();return 2;}));
 expect(paused["committedIds"].size()==1&&paused["pendingIds"].size()==3&&paused["canceled"]==true,"real EOS checkpoint acknowledged before pause");
 json remaining=json::array();for(const auto& item:cases)if(item["id"]!=paused["committedIds"][0])remaining.push_back(item);
 expect(!json::parse(translatepdf::loadModelBatch(modelPath,2048,4,"hy-mt2",4)).contains("error"),"real next worker load resets prior cancel without weight substitution");
 auto resumed=json::parse(translatepdf::translateBatch(remaining.dump(),"","","en","ru",91,[](const std::string&){return 1;}));
 expect(resumed["committedIds"].size()==3&&resumed["pendingIds"].empty(),"real resumed call computes only uncommitted requests");
 std::cout<<json{{"realPause",paused},{"realResume",resumed}}<<"\n";
 const json protectedCases=json::array({{{"id","numbers"},{"text","Keep 128 mm and 5W-30. Do not remove the cover."}},{{"id","negation"},{"text","Do not use water. Use oil."}}});
 auto quality=json::parse(translatepdf::translateBatch(protectedCases.dump(),"","","en","ru",89,[&](const std::string& text){auto out=json::parse(text);expect(!out["text"].get<std::string>().empty(),"real protected output nonempty");std::cout<<out<<"\n";return 1;}));
 expect(quality["committedIds"].size()==2&&quality["finalErrors"].empty(),"real numbers and negation have EOS+guard");
 // Exercise the shipping 8-lane context and exact-prefix path with actual
 // pinned weights, in addition to the deterministic vendor-boundary fixtures.
 auto loaded8=json::parse(translatepdf::loadModelBatch(modelPath,4096,4,"hy-mt2",8));
 expect(!loaded8.contains("error")&&loaded8["maxSequences"]==8,"real eight-lane model context loads");
 json eight=json::array();
 for(int round=0;round<2;++round)for(const auto& item:cases)
  eight.push_back({{"id",item["id"].get<std::string>()+"-eight-"+std::to_string(round)},{"text",item["text"]}});
 std::vector<json> completed8;
 auto summary8=json::parse(translatepdf::translateBatch(eight.dump(),"","","en","ru",92,[&](const std::string& text){
  auto out=json::parse(text);expect(out["generation"]==92&&!out["text"].get<std::string>().empty(),"real eight-lane callback identity and text");
  completed8.push_back(std::move(out));return 1;
 }));
 expect(summary8["committedIds"].size()==8&&summary8["pendingIds"].empty()&&summary8["finalErrors"].empty(),"real eight-lane sources all reach EOS and guard");
 std::cout<<json{{"realEightLaneSummary",summary8},{"realEightLaneResults",completed8}}<<"\n";
 translatepdf::releaseModel();
}
int main(int argc,char** argv){try{fixture();pageContextContracts();cropGeometryContracts();pdfBoundary();fileBoundary();sourcePreservingCancellationContracts();targetCharacterInkFailureContracts();mergeContracts();readableExportContracts();wrappingContracts();exportContracts();modelBoundary();if(argc==3)realBatch(argv[1],argv[2]);fs::remove_all(directory);std::cout<<"Core vendor/file fault contracts passed\n";return 0;}catch(const std::exception& e){std::cerr<<e.what()<<'\n';fs::remove_all(directory);return 1;}}
