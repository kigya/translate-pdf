#include "docx.hpp"
#include "miniz.h"
#include "miniz_zip.h"
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <functional>
#include <cstdint>
#include <csignal>
#include <sys/resource.h>

using nlohmann::json;
namespace fs=std::filesystem;
static void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
static void add(mz_zip_archive& z,const char* name,const std::string& value){require(mz_zip_writer_add_mem(&z,name,value.data(),value.size(),MZ_DEFAULT_COMPRESSION)!=0,"zip add failed");}
static std::string writeDocx(const fs::path& path,const std::string& body,const std::string& extraRels="",bool unsafeMember=false,bool duplicate=false,bool embedded=false){
  mz_zip_archive z{};require(mz_zip_writer_init_file(&z,path.string().c_str(),0)!=0,"zip init failed");
  add(z,"[Content_Types].xml",R"(<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/></Types>)");
  add(z,"_rels/.rels",R"(<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>)");
  add(z,"word/document.xml",body);
  add(z,"word/_rels/document.xml.rels",R"(<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdExt" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.invalid/" TargetMode="External"/></Relationships>)"+extraRels);
  add(z,"word/footnotes.xml",R"(<?xml version="1.0"?><w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:footnote w:id="1"><w:p><w:r><w:t>note text</w:t></w:r></w:p></w:footnote></w:footnotes>)");
  const std::string image("\0PNG\xff",5); add(z,"word/media/image1.png",image);
  require(mz_zip_writer_add_mem(&z,"word/",nullptr,0,MZ_DEFAULT_COMPRESSION)!=0,"zip directory add failed");
  if(embedded)add(z,"word/embeddings/unsupported.bin","opaque");
  if(unsafeMember)add(z,"../../escape.txt","unsafe");
  if(duplicate)add(z,"word/document.xml",body);
  require(mz_zip_writer_finalize_archive(&z)!=0,"zip finalize failed");mz_zip_writer_end(&z);return image;
}
static json parse(const std::string& s){auto v=json::parse(s);if(v.contains("error"))throw std::runtime_error(std::string("unexpected api error: ")+v["error"].get<std::string>());return v;}
static void expectError(const std::string& s){require(json::parse(s).contains("error"),"invalid input was accepted");}

using Parts=std::vector<std::pair<std::string,std::string>>;
static const std::string wordNamespace="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
static std::string documentXML(const std::string& body){return "<w:document xmlns:w=\""+wordNamespace+"\"><w:body>"+body+"</w:body></w:document>";}
static Parts minimalParts(const std::string& body){return {
 {"[Content_Types].xml","<Types><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"},
 {"_rels/.rels","<Relationships><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"},
 {"word/document.xml",documentXML(body)}};}
static void writeParts(const fs::path& path,const Parts& parts){
 mz_zip_archive z{};require(mz_zip_writer_init_file(&z,path.string().c_str(),0)!=0,"fixture ZIP open failed");
 for(const auto& part:parts)add(z,part.first.c_str(),part.second);
 require(mz_zip_writer_finalize_archive(&z)!=0,"fixture ZIP finalization failed");mz_zip_writer_end(&z);
}
static void errorContains(const std::string& response,const std::string& expected){
 const auto value=json::parse(response);require(value.contains("error"),"unsafe fixture was accepted");
 if(value["error"].get<std::string>().find(expected)==std::string::npos)throw std::runtime_error("Wrong rejection: "+value.dump()+" expected "+expected);
}
// Mutate actual untrusted ZIP central-directory metadata, rather than mocking the parser.
static void patchCentral(const fs::path& path,const std::string& name,size_t field,uint32_t value,size_t width){
 std::ifstream in(path,std::ios::binary);std::string bytes((std::istreambuf_iterator<char>(in)),{});in.close();bool patched=false;
 auto u16=[&](size_t at){return static_cast<unsigned char>(bytes.at(at))|(static_cast<unsigned char>(bytes.at(at+1))<<8);};
 for(size_t at=0;at+46<=bytes.size();++at){
  if(bytes.compare(at,4,std::string("PK\1\2",4))!=0)continue;
  auto length=u16(at+28);if(bytes.substr(at+46,length)!=name)continue;
  for(size_t i=0;i<width;++i)bytes.at(at+field+i)=static_cast<char>((value>>(8*i))&255);
  patched=true;break;
 }
 require(patched,"fixture ZIP member metadata missing");std::ofstream out(path,std::ios::binary|std::ios::trunc);out.write(bytes.data(),bytes.size());
}
static std::string readMember(const fs::path& path,const std::string& name){
 mz_zip_archive z{};require(mz_zip_reader_init_file(&z,path.string().c_str(),0)!=0,"roundtrip ZIP open failed");
 const auto index=mz_zip_reader_locate_file(&z,name.c_str(),nullptr,0);require(index>=0,"preserved part missing");
 size_t count=0;char* data=static_cast<char*>(mz_zip_reader_extract_to_heap(&z,index,&count,0));require(data!=nullptr,"roundtrip member corrupt");
 std::string value(data,count);mz_free(data);mz_zip_reader_end(&z);return value;
}
static void runAdditionalCases(const fs::path& root){
 const std::string paragraph="<w:p><w:r><w:t>safe</w:t></w:r></w:p>";
 auto parts=minimalParts(paragraph);auto source=root/"minimal.docx";writeParts(source,parts);
 errorContains(translatepdf::inspectDocx((root/"missing.docx").string()),"missing");
 auto wrongExtension=root/"wrong.txt";fs::copy_file(source,wrongExtension);errorContains(translatepdf::inspectDocx(wrongExtension.string()),"Only .docx");
 auto uppercase=root/"UPPER.DOCX";fs::copy_file(source,uppercase);require(parse(translatepdf::inspectDocx(uppercase.string()))["segmentCount"]==1,"uppercase extension rejected");
 auto noExtension=root/"word-package";fs::copy_file(source,noExtension);require(parse(translatepdf::inspectDocx(noExtension.string()))["segmentCount"]==1,"content-valid extensionless DOCX rejected");
 auto nonZIP=root/"not-zip.docx";{std::ofstream out(nonZIP);out<<std::string(100,'x');}errorContains(translatepdf::inspectDocx(nonZIP.string()),"ZIP");
 auto huge=root/"huge.docx";{std::ofstream out(huge,std::ios::binary);out.seekp(128ull*1024*1024);out.put('x');}errorContains(translatepdf::inspectDocx(huge.string()),"too large");fs::remove(huge);
 for(size_t missing=0;missing<3;++missing){auto copy=parts;copy.erase(copy.begin()+missing);auto path=root/("missing-part-"+std::to_string(missing)+".docx");writeParts(path,copy);errorContains(translatepdf::inspectDocx(path.string()),"valid Word DOCX");}
 auto rejectPart=[&](const std::string& name,size_t index,const std::string& text,const std::string& reason){auto copy=parts;copy[index].second=text;auto path=root/(name+".docx");writeParts(path,copy);errorContains(translatepdf::inspectDocx(path.string()),reason);};
 rejectPart("wrong-main-type",0,"<Types><Override PartName=\"/other.xml\" ContentType=\"text/plain\"/></Types>","valid Word DOCX document part");
 rejectPart("macro-type",0,"<Types><Override ContentType=\"application/vnd.ms-word.document.macroEnabled.main+xml\"/></Types>","Macro-enabled");
 rejectPart("macro-default",0,"<Types><Default Extension=\"docm\"/></Types>","Macro-enabled");
 rejectPart("wrong-root-type",1,"<Relationships><Relationship Type=\"other\" Target=\"word/document.xml\"/></Relationships>","main document relationship");
 rejectPart("wrong-root-target",1,"<Relationships><Relationship Type=\"x/officeDocument\" Target=\"elsewhere.xml\"/></Relationships>","main document relationship");
 rejectPart("wrong-namespace",2,"<document xmlns:q=\"urn:other\"><p/></document>","namespace");
 rejectPart("malformed-xml",2,"<w:document>","malformed");
 rejectPart("lowercase-dtd",2,"<!doctype doc><doc/>","document types");
 rejectPart("no-paragraphs",2,documentXML("<w:body/>"),"no readable paragraphs");
 rejectPart("oversized-xml",2,documentXML(std::string(8*1024*1024,' ')),"XML part exceeds");
 auto macro=parts;macro.emplace_back("word/vbaProject.bin","opaque");auto macroFile=root/"macro-bin.docx";writeParts(macroFile,macro);errorContains(translatepdf::inspectDocx(macroFile.string()),"Macro-enabled");
 for(const auto& unsafe:std::vector<std::string>{"/absolute.xml","word\\escape.xml","./relative.xml","word/../escape.xml"}){
  auto bad=parts;const std::string safe(unsafe.size(),'x');bad.emplace_back(safe,"unsafe");auto file=root/("unsafe-"+std::to_string(unsafe.size())+".docx");writeParts(file,bad);
  std::ifstream input(file,std::ios::binary);std::string bytes((std::istreambuf_iterator<char>(input)),{});input.close();size_t offset=0;while((offset=bytes.find(safe,offset))!=std::string::npos){bytes.replace(offset,safe.size(),unsafe);offset+=safe.size();}std::ofstream output(file,std::ios::binary|std::ios::trunc);output.write(bytes.data(),bytes.size());output.close();errorContains(translatepdf::inspectDocx(file.string()),"unsafe");
 }
 auto metadataCase=[&](const std::string& name,size_t field,uint32_t value,size_t width,const std::string& reason){auto path=root/(name+".docx");writeParts(path,parts);patchCentral(path,"word/document.xml",field,value,width);errorContains(translatepdf::inspectDocx(path.string()),reason);};
 metadataCase("encrypted",8,1,2,"Encrypted");metadataCase("unknown-compression",10,99,2,"unsupported ZIP");
 metadataCase("expansion-bomb",24,256u*1024*1024+1,4,"expands beyond");metadataCase("oversized-part",24,64u*1024*1024+1,4,"oversized package part");
 metadataCase("crc-corrupt",16,0,4,"integrity validation");
 auto many=parts;for(int i=0;i<4094;++i)many.emplace_back("unused/"+std::to_string(i),"");auto manyFile=root/"too-many-members.docx";writeParts(manyFile,many);errorContains(translatepdf::inspectDocx(manyFile.string()),"too many ZIP entries");
 std::string emptyParagraphs;for(int i=0;i<100001;++i)emptyParagraphs+="<w:p/>";rejectPart("too-many-segments",2,documentXML(emptyParagraphs),"too many text segments");
 auto badRelationships=parts;badRelationships.emplace_back("word/_rels/document.xml.rels","<broken>");auto badRels=root/"bad-rels.docx";writeParts(badRels,badRelationships);errorContains(translatepdf::inspectDocx(badRels.string()),"malformed");
 errorContains(translatepdf::readDocxSegment(source.string(),-1),"out of range");errorContains(translatepdf::readDocxSegment(source.string(),1),"out of range");
 errorContains(translatepdf::readDocxSegment(nonZIP.string(),0),"ZIP");
 errorContains(translatepdf::exportDocx(source.string(),"{}",(root/"bad-json.docx").string()),"JSON array");
 expectError(translatepdf::exportDocx(source.string(),"[",(root/"malformed-json.docx").string()));
 errorContains(translatepdf::exportDocx(source.string(),"[]",""),"output path is empty");
 errorContains(translatepdf::exportDocx(source.string(),"[]",(root/"missing-folder/output.docx").string()),"Cannot create");
 auto block=parse(translatepdf::readDocxSegment(source.string(),0))["blocks"][0];block["translatedText"]="translation"; // Default status is translated.
 auto target=root/"default-status.docx";auto response=parse(translatepdf::exportDocx(source.string(),json::array({nullptr,3,json::object(),json{{"id",42}},json{{"id","missing"}},json{{"id","invalid"},{"translatedText",nullptr}},json{{"blocks",nullptr}},block}).dump(),target.string()));
 require(!response["partial"].get<bool>()&&parse(translatepdf::readDocxSegment(target.string(),0))["blocks"][0]["text"]=="translation","valid block/default status was discarded with malformed siblings");
 block["status"]="failed";auto failedOutput=root/"failed-block.docx";require(parse(translatepdf::exportDocx(source.string(),json::array({block}).dump(),failedOutput.string()))["partial"],"failed translation was not marked partial");
 require(parse(translatepdf::readDocxSegment(failedOutput.string(),0))["blocks"][0]["text"]=="safe","failed translation replaced original text");
 // Empty/whitespace paragraphs are structure, not failed translations.
 auto spaced=parts;spaced[2].second=documentXML(paragraph+"<w:p/><w:p><w:r><w:t xml:space=\"preserve\"> \t\n </w:t></w:r></w:p>");auto spacedSource=root/"whitespace.docx",spacedOutput=root/"whitespace-output.docx";writeParts(spacedSource,spaced);
 auto spacedBlock=parse(translatepdf::readDocxSegment(spacedSource.string(),0))["blocks"][0];spacedBlock["translatedText"]="translated";spacedBlock["status"]="translated";
 require(!parse(translatepdf::exportDocx(spacedSource.string(),json::array({spacedBlock}).dump(),spacedOutput.string()))["partial"].get<bool>(),"blank paragraphs falsely marked completed translation partial");
 require(parse(translatepdf::readDocxSegment(spacedOutput.string(),1))["blocks"][0]["text"]=="","empty paragraph gained content");
 require(parse(translatepdf::readDocxSegment(spacedOutput.string(),2))["blocks"][0]["text"]==" \t\n ","whitespace paragraph structure changed");
 // Header/footer/endnote ordering must not alter stable part IDs; all opaque assets survive export.
 auto rich=parts;rich.emplace_back("word/footer2.xml",documentXML(paragraph));rich.emplace_back("word/header2.xml",documentXML(paragraph));rich.emplace_back("word/header1.xml",documentXML(paragraph));rich.emplace_back("word/endnotes.xml",documentXML(paragraph));rich.emplace_back("word/footnotes.xml",documentXML(paragraph));rich.emplace_back("word/styles.xml","<styles>opaque-style</styles>");rich.emplace_back("word/altChunk1.html","<p>preserve me</p>");rich.emplace_back("word/headerExtra.bin","opaque");
 auto richSource=root/"rich.docx",richOutput=root/"rich-output.docx";writeParts(richSource,rich);auto richInfo=parse(translatepdf::inspectDocx(richSource.string()));require(richInfo["segmentCount"]==6,"headers/footer/notes not extracted");
 const std::vector<std::string> ordered={"document.xml","header1.xml","header2.xml","footer2.xml","footnotes.xml","endnotes.xml"};json richBlocks=json::array();
 for(int i=0;i<6;++i){auto b=parse(translatepdf::readDocxSegment(richSource.string(),i))["blocks"][0];require(b["id"].get<std::string>().find(ordered[i])!=std::string::npos,"document part ordering changed");b["translatedText"]="changed";richBlocks.push_back(b);}
 parse(translatepdf::exportDocx(richSource.string(),richBlocks.dump(),richOutput.string()));require(readMember(richOutput,"word/styles.xml")==rich[8].second,"styles changed");require(readMember(richOutput,"word/altChunk1.html")==rich[9].second,"unsupported element changed");
 // Default namespace is valid OOXML; CRLF must become one typed break and XML-sensitive text must roundtrip.
 auto defaults=parts;defaults[2].second="<document xmlns=\""+wordNamespace+"\"><body><p><r><t>old</t><cr/><br type=\"column\"/></r></p></body></document>";auto defaultSource=root/"default-namespace.docx",defaultOutput=root/"default-namespace-output.docx";writeParts(defaultSource,defaults);
 auto defaultBlock=parse(translatepdf::readDocxSegment(defaultSource.string(),0))["blocks"][0];defaultBlock["translatedText"]="<one>&\r\ntwo\rthree";defaultBlock["status"]="translated";parse(translatepdf::exportDocx(defaultSource.string(),json::array({defaultBlock}).dump(),defaultOutput.string()));require(parse(translatepdf::readDocxSegment(defaultOutput.string(),0))["blocks"][0]["text"]=="<one>&\ntwo\nthree","default namespace/CRLF/XML escaping roundtrip failed");require(readMember(defaultOutput,"word/document.xml").find("column")!=std::string::npos,"column break was discarded");
 // An unreadable opaque part must abort export without overwriting an existing destination.
 auto opaque=parts;opaque.emplace_back("word/media/image.bin","original-image-bytes");auto corruptOpaque=root/"corrupt-image.docx";writeParts(corruptOpaque,opaque);patchCentral(corruptOpaque,"word/media/image.bin",16,0,4);
 auto protectedOutput=root/"protected-output.docx";{std::ofstream out(protectedOutput);out<<"existing destination";}
 errorContains(translatepdf::exportDocx(corruptOpaque.string(),"[]",protectedOutput.string()),"integrity validation");{std::ifstream in(protectedOutput);std::string value((std::istreambuf_iterator<char>(in)),{});require(value=="existing destination","failed export overwrote destination");}
 // Text boxes have nested paragraphs; rewriting their parent must keep each paragraph's controls independent.
 auto nested=parts;nested[2].second=documentXML("<w:p><w:r><w:t>outer</w:t><w:drawing><w:txbxContent><w:p><w:r><w:t>inner</w:t><w:br/><w:t>end</w:t></w:r></w:p></w:txbxContent></w:drawing></w:r></w:p>");auto nestedSource=root/"nested.docx",nestedOutput=root/"nested-output.docx";writeParts(nestedSource,nested);
 auto outer=parse(translatepdf::readDocxSegment(nestedSource.string(),0))["blocks"][0];outer["translatedText"]="translated outer";outer["status"]="translated";parse(translatepdf::exportDocx(nestedSource.string(),json::array({outer}).dump(),nestedOutput.string()));require(parse(translatepdf::readDocxSegment(nestedOutput.string(),1))["blocks"][0]["text"]=="inner\nend","parent rewrite changed nested text-box controls");
 // Cumulative expanded length is bounded even when every individual member is below 64 MiB.
 auto combined=parts;for(int i=0;i<5;++i)combined.emplace_back("word/media/part"+std::to_string(i),"small");auto combinedFile=root/"combined-expansion.docx";writeParts(combinedFile,combined);for(int i=0;i<5;++i)patchCentral(combinedFile,"word/media/part"+std::to_string(i),24,60u*1024*1024,4);errorContains(translatepdf::inspectDocx(combinedFile.string()),"expands beyond");
 // A declared main part with the wrong MIME type is distinct from an absent declaration.
 rejectPart("wrong-content-mime",0,"<Types><Override PartName=\"/word/document.xml\" ContentType=\"text/plain\"/></Types>","valid Word DOCX document part");
 auto disguisedMacro=parts;disguisedMacro.emplace_back("custom/vbaProject-data.bin","opaque");auto disguisedMacroFile=root/"disguised-macro.docx";writeParts(disguisedMacroFile,disguisedMacro);errorContains(translatepdf::inspectDocx(disguisedMacroFile.string()),"Macro-enabled");
 // Every XML/relationship part is preflighted before any preview library can unpack it.
 auto oversizedRels=parts;oversizedRels.emplace_back("word/_rels/optional.rels",std::string(8*1024*1024+1,' '));oversizedRels.emplace_back("a","");oversizedRels.emplace_back("word/embeddings/","");auto oversizedRelsFile=root/"oversized-optional-rels.docx";writeParts(oversizedRelsFile,oversizedRels);errorContains(translatepdf::inspectDocx(oversizedRelsFile.string()),"XML part exceeds");
 auto unsafeStyles=parts;unsafeStyles.emplace_back("word/styles.xml","<!DOCTYPE styles [<!ENTITY local SYSTEM 'file:///private/document'>]><styles>&local;</styles>");auto stylesDTD=root/"styles-dtd.docx";writeParts(stylesDTD,unsafeStyles);errorContains(translatepdf::inspectDocx(stylesDTD.string()),"document types");
 std::string nestedStyle;for(int i=0;i<1000;++i)nestedStyle+="<style>";for(int i=0;i<1000;++i)nestedStyle+="</style>";unsafeStyles.back().second=nestedStyle;auto stylesDeep=root/"styles-deep.docx";writeParts(stylesDeep,unsafeStyles);errorContains(translatepdf::inspectDocx(stylesDeep.string()),"nesting");
 auto malformedTheme=parts;malformedTheme.emplace_back("word/theme/theme1.xml","<theme>");auto themeFile=root/"theme-malformed.docx";writeParts(themeFile,malformedTheme);errorContains(translatepdf::inspectDocx(themeFile.string()),"malformed");
 // Bound disk-write failures without filling the disk: restore process limits before assertions/profile output.
 struct FileSizeLimit {
  struct rlimit original{};void (*handler)(int)=SIG_DFL;
  explicit FileSizeLimit(rlim_t bytes){require(getrlimit(RLIMIT_FSIZE,&original)==0,"cannot read file-size limit");handler=std::signal(SIGXFSZ,SIG_IGN);auto limited=original;limited.rlim_cur=bytes;require(setrlimit(RLIMIT_FSIZE,&limited)==0,"cannot set controlled file-size limit");}
  ~FileSizeLimit(){setrlimit(RLIMIT_FSIZE,&original);std::signal(SIGXFSZ,handler);}
 };
 for(rlim_t limit:{0,50,200,400}){
  std::string result;{FileSizeLimit bounded(limit);result=translatepdf::exportDocx(source.string(),"[]",(root/("disk-limit-"+std::to_string(limit)+".docx")).string());}
  errorContains(result,"Cannot");
 }
 // Fail writes while preserving directories, replacing text, and copying an opaque asset.
 std::string incompressible;uint32_t state=0x81726354;for(int i=0;i<50000;++i){state^=state<<13;state^=state>>17;state^=state<<5;incompressible+="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[state%62];}
 auto largeBlock=parse(translatepdf::readDocxSegment(source.string(),0))["blocks"][0];largeBlock["translatedText"]=incompressible;largeBlock["status"]="translated";
 std::string textWriteFailure;{FileSizeLimit bounded(1000);textWriteFailure=translatepdf::exportDocx(source.string(),json::array({largeBlock}).dump(),(root/"text-write-failed.docx").string());}errorContains(textWriteFailure,"Cannot write translated Word text");
 auto largeAsset=parts;largeAsset.emplace_back("word/media/asset.bin",incompressible);auto largeAssetFile=root/"large-asset.docx";writeParts(largeAssetFile,largeAsset);std::string assetWriteFailure;{FileSizeLimit bounded(1000);assetWriteFailure=translatepdf::exportDocx(largeAssetFile.string(),"[]",(root/"asset-write-failed.docx").string());}errorContains(assetWriteFailure,"Cannot preserve DOCX package part");
 Parts manyDirectories;for(int i=0;i<30;++i)manyDirectories.emplace_back("word/"+std::string(400,'d')+std::to_string(i)+"/","");manyDirectories.insert(manyDirectories.end(),parts.begin(),parts.end());auto directorySource=root/"long-directories.docx";writeParts(directorySource,manyDirectories);std::string directoryWriteFailure;{FileSizeLimit bounded(1000);directoryWriteFailure=translatepdf::exportDocx(directorySource.string(),"[]",(root/"directory-write-failed.docx").string());}errorContains(directoryWriteFailure,"Cannot preserve DOCX package directory");
 // Literal UTF-8 must be scalar-valid before XML parsing or segmentation.
 const std::vector<std::string> invalidUtf8={std::string("\x80",1),std::string("\xc0\xaf",2),std::string("\xc1\xbf",2),std::string("\xc2\x20",2),std::string("\xe0\x80\xaf",3),std::string("\xed\xa0\x80",3),std::string("\xf0\x80\x80\xaf",4),std::string("\xf4\x90\x80\x80",4),std::string("\xf5\x80\x80\x80",4),std::string("\xff",1),std::string("\xef\xbf\xbe",3),std::string("\xef\xbf\xbf",3),std::string(1,'\0'),std::string(1,'\1'),std::string(1,'\v')};
 for(size_t i=0;i<invalidUtf8.size();++i)rejectPart("invalid-utf8-"+std::to_string(i),2,documentXML("<w:p><w:r><w:t>"+invalidUtf8[i]+"</w:t></w:r></w:p>"),"DOCX contains");
 for(const auto& truncated:std::vector<std::string>{std::string("\xc2",1),std::string("\xe2\x82",2),std::string("\xf0\x9f\x91",3)})rejectPart("truncated-"+std::to_string(truncated.size()),2,documentXML(paragraph)+truncated,"malformed UTF-8");
 // Numeric entities cannot bypass validation to inject NUL, controls, surrogates or overflowing scalars.
 for(const auto& entity:std::vector<std::string>{"&#0;","&#1;","&#11;","&#xD800;","&#xDFFF;","&#xFFFE;","&#xFFFF;","&#x110000;","&#4294967296;","&#99999999999999999999999999999;"})rejectPart("invalid-reference-"+std::to_string(entity.size()),2,documentXML("<w:p><w:r><w:t>before"+entity+"after</w:t></w:r></w:p>"),"DOCX contains");
 auto validReferences=minimalParts("<w:p><w:r><w:t>&#9;&#10;&#13;&#x7F;&#x80;&#x7FF;&#x800;&#xD7FF;&#xE000;&#xFFFD;&#x10000;&#x10FFFF;&#128512;&#x1F600;&amp;#0;</w:t></w:r></w:p>");auto referencesFile=root/"valid-references.docx";writeParts(referencesFile,validReferences);require(parse(translatepdf::inspectDocx(referencesFile.string()))["segmentCount"]==1,"valid XML references were rejected");require(parse(translatepdf::readDocxSegment(referencesFile.string(),0))["blocks"][0]["text"].get<std::string>().find(u8"😀😀&#0;")!=std::string::npos,"valid decimal/hex references or escaped literal reference changed");
 auto literalReferences=minimalParts("<!-- literal &#0; and &#999999999999999999999; --><w:p><w:r><w:t><![CDATA[&#0; &#xD800; &unknown;]]></w:t></w:r></w:p>");auto literalFile=root/"literal-reference-syntax.docx";writeParts(literalFile,literalReferences);require(parse(translatepdf::readDocxSegment(literalFile.string(),0))["blocks"][0]["text"]=="&#0; &#xD800; &unknown;","CDATA/comment literal entity syntax was interpreted as an XML reference");
 rejectPart("invalid-attribute-reference",2,documentXML("<w:p><w:pPr w:tag=\"&#0;\"/></w:p>"),"DOCX contains");
 for(const auto& malformed:std::vector<std::string>{"&#;","&#x;","&#xZZ;","&#12z;","&#999"})rejectPart("malformed-reference-"+std::to_string(malformed.size()),2,documentXML("<w:p><w:r><w:t>"+malformed+"</w:t></w:r></w:p>"),"DOCX contains");
 rejectPart("unterminated-cdata",2,documentXML(paragraph)+"<![CDATA[","malformed XML");rejectPart("unterminated-comment",2,documentXML(paragraph)+"<!--","malformed XML");
 for(const auto& ending:std::vector<std::string>{"&#","&#x","&#12"})rejectPart("truncated-reference-"+std::to_string(ending.size()),2,documentXML(paragraph)+ending,"DOCX contains");
 rejectPart("one-past-maximum-reference",2,documentXML("<w:p><w:r><w:t>&#1114112;</w:t></w:r></w:p>"),"invalid XML character reference");
 auto lowercaseHex=minimalParts("<w:p><w:r><w:t>&#xfffd;</w:t></w:r></w:p>");auto lowercaseFile=root/"lowercase-hex-reference.docx";writeParts(lowercaseFile,lowercaseHex);require(parse(translatepdf::readDocxSegment(lowercaseFile.string(),0))["blocks"][0]["text"]==u8"�","valid lowercase hexadecimal reference changed");
 // Every paragraph boundary strategy must preserve source bytes and grapheme clusters.
 const std::vector<std::string> tails={u8"a\U000e0100",u8"a\ufe10",u8"é",u8"漢",u8"😀",u8"a\u1ab0",u8"a\u1dc0",u8"a\u20d0",u8"a\ufe20",u8"❤️",u8"👍🏽",u8"👩‍💻",u8"🇺🇸🇨🇦", " word ","\tword\t","\nword\n"};
 for(size_t i=0;i<tails.size();++i){std::string value(1799,'x');value+=tails[i];value+=std::string(100,'z');auto file=root/("unicode-"+std::to_string(i)+".docx");auto fixture=minimalParts("<w:p><w:r><w:t xml:space=\"preserve\">"+value+"</w:t></w:r></w:p>");writeParts(file,fixture);auto count=parse(translatepdf::inspectDocx(file.string()))["segmentCount"].get<int>();std::string recovered;for(int n=0;n<count;++n){auto piece=parse(translatepdf::readDocxSegment(file.string(),n))["blocks"][0]["text"].get<std::string>();require(piece.size()<=1800,"Unicode segment exceeded byte bound");recovered+=piece;}require(recovered==value,"paragraph segmentation dropped Unicode/whitespace");}
 const std::vector<std::string> boundaries={std::string(1796,'x')+u8"👩‍💻",std::string(1793,'x')+u8"👩‍💻",std::string(1792,'x')+u8"🇺🇸🇨🇦",std::string(700,'x')+" "+std::string(1100,'y'),std::string(1200,'x')+" \t\n"+std::string(800,'y'),std::string(1200,'x')+"\t "+std::string(800,'y')};
 for(size_t i=0;i<boundaries.size();++i){auto value=boundaries[i]+std::string(100,'z');auto file=root/("boundary-"+std::to_string(i)+".docx");writeParts(file,minimalParts("<w:p><w:r><w:t xml:space=\"preserve\">"+value+"</w:t></w:r></w:p>"));auto count=parse(translatepdf::inspectDocx(file.string()))["segmentCount"].get<int>();std::string recovered;for(int n=0;n<count;++n)recovered+=parse(translatepdf::readDocxSegment(file.string(),n))["blocks"][0]["text"].get<std::string>();require(recovered==value,"boundary preference lost grapheme or whitespace");}
 std::string unbreakable="a";for(int i=0;i<1000;++i)unbreakable+=u8"́";rejectPart("oversized-grapheme",2,documentXML("<w:p><w:r><w:t>"+unbreakable+"</w:t></w:r></w:p>"),"safely split");
}

int main(){
 try {
  auto root=fs::current_path()/".local-private/docx-core-test-fixtures";fs::remove_all(root);fs::create_directories(root);
  auto src=root/"sample.docx",dst=root/"translated.docx";
  std::string longText(1799,'x');longText+=u8"á";for(int i=0;i<300;i++)longText+=u8"漢";
  std::string regional(1796,'r');regional+=u8"🇺🇸";
  std::string body=R"(<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t xml:space="preserve">Hello &amp; </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>world</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>)"+longText+R"(</w:t></w:r></w:p><w:p><w:pPr/></w:p><w:p><w:r><w:t>)"+regional+R"(</w:t></w:r></w:p></w:body></w:document>)";
  const auto image=writeDocx(src,body);
  auto info=parse(translatepdf::inspectDocx(src.string()));
  require(info["format"]=="docx" && info["segmentCount"].get<int>()==8,"paragraph segmentation/count incorrect");
  auto first=parse(translatepdf::readDocxSegment(src.string(),0));
  require(first["blocks"][0]["text"]=="Hello & world","paragraph extraction/XML entity decode failed");
  require(first["blocks"][0]["id"].get<std::string>().find("#chunk:0")!=std::string::npos,"stable chunk ID missing");
  require(!first["warnings"].empty(),"external relationship should be reported inertly");
  std::string many=translatepdf::readDocxSegment(src.string(),3);
  require(parse(many)["blocks"][0]["text"].get<std::string>().size()<=1800,"oversize paragraph was not bounded");
  require(parse(translatepdf::readDocxSegment(src.string(),2))["blocks"][0]["text"].get<std::string>().size()<=1800,"UTF-8 scalar boundary exceeded segment bound");
  require(parse(translatepdf::readDocxSegment(src.string(),3))["blocks"][0]["text"].get<std::string>().rfind(u8"á",0)==0,"combining character sequence was split");
  require(parse(translatepdf::readDocxSegment(src.string(),6))["blocks"][0]["text"].get<std::string>().rfind(u8"🇺🇸",0)==0,"regional indicator grapheme was split");
  json blocks=json::array();
  for(int i=0;i<info["segmentCount"].get<int>();i++){
    auto page=parse(translatepdf::readDocxSegment(src.string(),i)); auto b=page["blocks"][0]; b["translatedText"]=i==0?" T0 ":"T"+std::to_string(i);b["status"]="translated";blocks.push_back(json{{"index",i},{"blocks",json::array({b})}});
  }
  auto exported=parse(translatepdf::exportDocx(src.string(),blocks.dump(),dst.string()));
  require(!exported["partial"].get<bool>(),"complete export unexpectedly partial");
  mz_zip_archive outZip{};require(mz_zip_reader_init_file(&outZip,dst.string().c_str(),0)!=0,"translated package could not be opened as ZIP");
  auto imageIndex=mz_zip_reader_locate_file(&outZip,"word/media/image1.png",nullptr,0);require(imageIndex>=0,"DOCX image was lost during export");
  size_t imageSize=0;auto* imageBytes=static_cast<char*>(mz_zip_reader_extract_to_heap(&outZip,static_cast<mz_uint>(imageIndex),&imageSize,0));
  require(imageBytes && std::string(imageBytes,imageSize)==image,"DOCX image content changed during export");mz_free(imageBytes);mz_zip_reader_end(&outZip);
  auto out=parse(translatepdf::inspectDocx(dst.string()));require(out["segmentCount"]==6,"exported DOCX did not reopen with expected paragraphs");
  auto trans=parse(translatepdf::readDocxSegment(dst.string(),2));require(trans["blocks"][0]["text"]=="T2T3","translated chunks did not join in order");
  require(parse(translatepdf::readDocxSegment(dst.string(),0))["blocks"][0]["text"]==" T0 ","leading/trailing spaces were not retained");
  require(parse(translatepdf::readDocxSegment(dst.string(),3))["blocks"][0]["text"]=="T4","empty paragraph did not receive translated text");
  require(parse(translatepdf::readDocxSegment(dst.string(),4))["blocks"][0]["text"]=="T5T6","regional indicator paragraph was not reassembled");
  auto partial=parse(translatepdf::exportDocx(src.string(),json::array().dump(),(root/"partial.docx").string()));require(partial["partial"],"missing translations should mark partial");
  require(parse(translatepdf::readDocxSegment((root/"partial.docx").string(),0))["blocks"][0]["text"]=="Hello & world","partial export changed source paragraph");
  json flat=json::array({json{{"id","unknown"},{"translatedText","unused"},{"status","translated"}}});
  require(parse(translatepdf::exportDocx(src.string(),flat.dump(),(root/"flat.docx").string()))["partial"],"flat block input was not accepted");

  auto doc=src;doc.replace_extension(".doc");fs::copy_file(src,doc);expectError(translatepdf::inspectDocx(doc.string()));
  auto docm=src;docm.replace_extension(".docm");fs::copy_file(src,docm);expectError(translatepdf::inspectDocx(docm.string()));
  auto bad=root/"bad.docx";{std::ofstream f(bad,std::ios::binary);f<<"not a ZIP";}expectError(translatepdf::inspectDocx(bad.string()));
  auto dtd=root/"dtd.docx";writeDocx(dtd,R"(<?xml version="1.0"?><!DOCTYPE w:document [<!ENTITY e "bad">]><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>)");expectError(translatepdf::inspectDocx(dtd.string()));
  auto traversal=root/"traversal.docx";writeDocx(traversal,body,"",true);expectError(translatepdf::inspectDocx(traversal.string()));
  auto duplicate=root/"duplicate.docx";writeDocx(duplicate,body,"",false,true);expectError(translatepdf::inspectDocx(duplicate.string()));
  auto embedded=root/"embedded.docx";writeDocx(embedded,body,"",false,false,true);require(!parse(translatepdf::inspectDocx(embedded.string()))["warnings"].empty(),"unsupported embedded object was not warned");
  fs::create_directory(root/"blocked-output.docx");auto renameError=json::parse(translatepdf::exportDocx(src.string(),blocks.dump(),(root/"blocked-output.docx").string()));require(renameError.value("error",std::string()).find("selected destination")!=std::string::npos,"destination conflict did not exercise atomic rename failure");
  const std::string controlsBody=R"(<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t><w:br w:type="page"/></w:r></w:p></w:body></w:document>)";
  auto controls=root/"controls.docx",unchanged=root/"controls-copy.docx",changed=root/"controls-translated.docx";
  writeDocx(controls,controlsBody);
  const auto originalControl=parse(translatepdf::readDocxSegment(controls.string(),0));
  require(originalControl["blocks"][0]["text"]=="A\tB\nC","layout page break leaked into text");
  parse(translatepdf::exportDocx(controls.string(),"[]",unchanged.string()));
  require(parse(translatepdf::readDocxSegment(unchanged.string(),0))["blocks"][0]["text"]=="A\tB\nC","partial export duplicated controls");
  auto controlBlock=originalControl["blocks"][0];controlBlock["translatedText"]="X\tY\nZ";controlBlock["status"]="translated";
  parse(translatepdf::exportDocx(controls.string(),json::array({controlBlock}).dump(),changed.string()));
  require(parse(translatepdf::readDocxSegment(changed.string(),0))["blocks"][0]["text"]=="X\tY\nZ","translated controls duplicated or reordered");
  std::string alternate=controlsBody;size_t position=0;
  while((position=alternate.find("w:",position))!=std::string::npos){alternate.replace(position,2,"q:");position+=2;}
  position=alternate.find("xmlns:w");if(position!=std::string::npos)alternate.replace(position,7,"xmlns:q");
  auto alternateSource=root/"alternate.docx",alternateOutput=root/"alternate-result.docx";writeDocx(alternateSource,alternate);
  auto alternateBlock=parse(translatepdf::readDocxSegment(alternateSource.string(),0))["blocks"][0];alternateBlock["translatedText"]="Alt\tline\nend";alternateBlock["status"]="translated";
  parse(translatepdf::exportDocx(alternateSource.string(),json::array({alternateBlock}).dump(),alternateOutput.string()));
  require(parse(translatepdf::readDocxSegment(alternateOutput.string(),0))["blocks"][0]["text"]=="Alt\tline\nend","alternate namespace prefix lost controls");
  auto deep=root/"deep.docx";
  std::string deepBody=R"(<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>)";
  for(int i=0;i<1000;++i)deepBody+="<a>";
  deepBody+="<w:p><w:r><w:t>deep</w:t></w:r></w:p>";
  for(int i=0;i<1000;++i)deepBody+="</a>";
  deepBody+="</w:body></w:document>";writeDocx(deep,deepBody);
  expectError(translatepdf::inspectDocx(deep.string()));
  runAdditionalCases(root);
  fs::remove_all(root);
  std::cout<<"DOCX core round-trip and adversarial checks passed\n";return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
