// Exercise the production parser and Unicode definitions linked from core.cpp.
#include "core_detail.hpp"
#include "source_layout.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <iostream>

namespace {
void expect(bool value, const char* message) {
  if (!value) throw std::runtime_error(message);
}
void put16(std::vector<unsigned char>& bytes, size_t at, uint16_t value) {
  bytes.at(at) = value >> 8;
  bytes.at(at + 1) = value & 255;
}
void put32(std::vector<unsigned char>& bytes, size_t at, uint32_t value) {
  put16(bytes, at, value >> 16);
  put16(bytes, at + 2, value & 65535);
}
std::vector<unsigned char> cmap(uint16_t format, uint16_t platform, uint16_t encoding) {
  std::vector<unsigned char> bytes(112);
  put16(bytes, 4, 1); // one sfnt table
  std::copy_n("cmap", 4, bytes.begin() + 12);
  put32(bytes, 20, 28);
  put16(bytes, 30, 1); // one encoding record
  put16(bytes, 32, platform);
  put16(bytes, 34, encoding);
  put32(bytes, 36, 12);
  put16(bytes, 40, format);
  return bytes;
}
void fontCoverage() {
  using translatepdf::detail::FontCoverage;
  for (size_t length : {size_t(0), size_t(11), size_t(12), size_t(27)}) {
    std::vector<unsigned char> bytes(length);
    if (length >= 12) put16(bytes, 4, 1);
    expect(!FontCoverage(bytes).has(65), "truncated sfnt has no glyph");
  }
  auto bytes = cmap(12, 3, 10);
  put32(bytes, 52, 2); // two format12 groups
  put32(bytes, 56, 32); put32(bytes, 60, 34); put32(bytes, 64, 1);
  put32(bytes, 68, 80); put32(bytes, 72, 81); put32(bytes, 76, 0);
  FontCoverage twelve(bytes);
  for (uint32_t value : {32, 33, 34, 81}) expect(twelve.has(value), "format12 range glyph");
  for (uint32_t value : {0, 31, 35, 79, 80, 82, 0x10ffff}) expect(!twelve.has(value), "format12 miss or glyph0");
  auto truncated = bytes; truncated.resize(65);
  expect(!FontCoverage(truncated).has(80), "format12 truncated group");
  auto unicode = bytes; put16(unicode, 32, 0);
  expect(FontCoverage(unicode).has(33), "Unicode platform format12");
  auto nonUnicode = bytes; put16(nonUnicode, 32, 1);
  expect(!FontCoverage(nonUnicode).has(33), "non-Unicode format12 platform ignored");
  auto ignored = bytes; put16(ignored, 34, 1);
  expect(!FontCoverage(ignored).has(33), "unsupported platform encoding");

  for (auto pair : {std::pair<uint16_t,uint16_t>{0, 0}, {3, 1}, {3, 0}}) {
    auto four = cmap(4, pair.first, pair.second);
    put16(four, 46, 2); // one segment
    put16(four, 54, 66); put16(four, 58, 65); put16(four, 60, 1);
    expect(FontCoverage(four).has(65), "format4 delta mapping");
    expect(!FontCoverage(four).has(64) && !FontCoverage(four).has(67), "format4 outside segment");
    expect(!FontCoverage(four).has(0x10000), "format4 cannot map supplementary codepoint");
    put16(four, 60, 65535 - 64);
    expect(!FontCoverage(four).has(65), "format4 delta wraps to missing glyph0");
    put16(four, 60, 1); put16(four, 62, 2); put16(four, 64, 5);
    expect(FontCoverage(four).has(65), "format4 glyph array");
    put16(four, 64, 0);
    expect(!FontCoverage(four).has(65), "format4 missing glyph array entry");
    put16(four, 64, 65535);
    expect(!FontCoverage(four).has(65), "format4 glyph delta wraps to zero");
    put16(four, 62, 500);
    expect(!FontCoverage(four).has(65), "format4 out of bounds glyph array");
  }
  for(auto pair : {std::pair<uint16_t,uint16_t>{1, 0}, {3, 7}}){
    auto unsupported=cmap(4,pair.first,pair.second);
    expect(!FontCoverage(unsupported).has(65),"unsupported format4 platform encoding");
  }
  auto malformed = bytes;
  std::copy_n("head", 4, malformed.begin() + 12);
  expect(!FontCoverage(malformed).has(33), "unrelated table is ignored");
  malformed = bytes; put32(malformed, 20, 500);
  expect(!FontCoverage(malformed).has(33), "out of bounds cmap");
  malformed = bytes; malformed.resize(35);
  expect(!FontCoverage(malformed).has(33), "truncated encoding record");
  malformed = bytes; put32(malformed, 36, 500);
  expect(!FontCoverage(malformed).has(33), "out of bounds subtable");
  malformed = bytes; put16(malformed, 40, 6);
  expect(!FontCoverage(malformed).has(33), "unsupported cmap format");

  // A real cmap may offer both BMP and supplementary tables. Prefer format12.
  auto both = bytes;
  put16(both, 30, 2);
  put16(both, 32, 3); put16(both, 34, 1); put32(both, 36, 64);
  put16(both, 40, 3); put16(both, 42, 10); put32(both, 44, 20);
  put16(both, 48, 12); put32(both, 60, 1);
  put32(both, 64, 0x10000); put32(both, 68, 0x10000); put32(both, 72, 5);
  put16(both, 92, 4);
  expect(FontCoverage(both).has(0x10000), "format12 preferred over format4");
}
void unicodeDecisions() {
  using namespace translatepdf::detail;
  std::vector<unsigned char> bytes{0x12, 0x34, 0x56, 0x78};
  expect(be16(bytes, 0) == 0x1234 && be16(bytes, 3) == 0, "big endian16 bounds");
  expect(be32(bytes, 0) == 0x12345678 && be32(bytes, 1) == 0, "big endian32 bounds");
  const std::string source = "café 中文 😀";
  expect(utf8(wide(source)) == source, "UTF8 UTF16 round trip");
  for (const auto& value : {std::u16string(u"A"), std::u16string(u"😀"), std::u16string(1, 0xd800), std::u16string({0xd800, u'A'}), std::u16string({0xd800, 0xe000}), std::u16string(1, 0xdc00)}) {
    size_t index = 0;
    auto codepoint = codepointAt(value, index);
    expect(codepoint == (value.size() == 2 && value[1] == 0xde00 ? 0x1f600 : value[0]), "surrogate decoding");
  }
  for (auto range : {std::pair<uint32_t,uint32_t>{0x0300,0x036f}, {0x1ab0,0x1aff}, {0x1dc0,0x1dff}, {0x20d0,0x20ff}, {0xfe20,0xfe2f}}) {
    expect(combining(range.first) && combining(range.second), "combining boundary");
    expect(!combining(range.first-1) && !combining(range.second+1), "non combining neighbor");
  }
  for (auto range : {std::pair<uint32_t,uint32_t>{0x2e80,0x9fff}, {0xf900,0xfaff}, {0x20000,0x3134f}, {0x3040,0x30ff}, {0xac00,0xd7af}}) {
    expect(cjk(range.first) && cjk(range.second), "CJK boundary");
  }
  for (uint32_t value : {0, 0x2e7f, 0xa000, 0xf8ff, 0xfb00, 0x1ffff, 0x31350, 0xabff, 0xd7b0}) expect(!cjk(value), "non CJK neighbor");
  for (uint32_t value : std::vector<uint32_t>{'(', '[', '{', 0x2018, 0x201c, 0x3008, 0x300a, 0x300c, 0x300e, 0x3010, 0x3014, 0x3016, 0x3018, 0x301a}) expect(openingPunctuation(value), "opening punctuation");
  for (uint32_t value : std::vector<uint32_t>{')', ']', '}', ',', '.', ':', ';', '!', '?', 0x2019, 0x201d, 0x3001, 0x3002, 0x3009, 0x300b, 0x300d, 0x300f, 0x3011, 0x3015, 0x3017, 0x3019, 0x301b}) expect(closingPunctuation(value), "closing punctuation");
  expect(!openingPunctuation('A') && !closingPunctuation('A'), "letter is not punctuation");
  for(const auto& text:{std::string("1."),std::string("0 148."),std::string(">"),std::string(" 12.5% / (42)\n"),std::string("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")})expect(languageInvariantAscii(text),"ASCII numbers and punctuation are language invariant");
  for(const auto& text:{std::string(""),std::string(" \t\r\n"),std::string("5W-30"),std::string("42 mm"),std::string("ON"),std::string("Нет"),std::string("中文"),std::string("©"),std::string("\x01")})expect(!languageInvariantAscii(text),"blank, linguistic, non-ASCII and control text cannot bypass translation");
}
void groupingContracts(){
 using namespace translatepdf::detail;
 using nlohmann::json;
 auto run=[](std::string text,double baseline=0,double left=0,double right=50,double top=0,double bottom=10,double size=10,bool horizontal=true,int face=1){
  static int index=0;int id=++index;
  return TextRun{json{{"text",text},{"sourceText",text},{"left",left},{"right",right},{"top",top},{"bottom",bottom},{"fontSize",size},{"objectIndices",json::array({id})}},baseline,reinterpret_cast<FPDF_FONT>(face),horizontal};
 };
 auto check=[](std::vector<TextRun> input,size_t count,const char* reason){size_t expected=0;for(const auto& r:input)expected+=r.block["objectIndices"].size();auto output=groupText(std::move(input));expect(output.size()==count,reason);size_t actual=0;for(const auto& b:output)actual+=b["objectIndices"].size();expect(actual==expected,"grouping retains every original object exactly once");return output;};
 check({},0,"empty page");
 check({run("A",0,0,10),run("B",0,14,24)},1,"word fragments join");
 for(auto texts:{std::pair<std::string,std::string>{"A ","B"},{"A"," B"},{"A\t","B"},{"A","\tB"},{"A-","B"},{"A−","B"},{"A","B"}}){check({run(texts.first,0,0,10),run(texts.second,0,12,22)},1,"spacing/sign fragment joins");}
 check({run("A",0,0,20),run("B",0,0,21),run("C",0,24,30)},2,"nearest fragment wins");
 check({run("A",0,0,21),run("B",0,0,20),run("C",0,24,30)},2,"farther candidate does not overwrite nearest");
 check({run(std::string(1800,'a'),0,0,10),run("B",0,12,22)},2,"line byte limit prevents join");
 check({run("A",0,0,10,0,10,10,false),run("B",0,12,22)},2,"non-horizontal row remains independent");
 check({run("A",0,0,10),run("B",0,12,22,0,10,10,false)},2,"non-horizontal fragment remains independent");
 check({run("A",0,0,50),run("B",12,0,50,12,22)},1,"adjacent lines form paragraph");
 check({run("Pressing Q may also arm the",0,0,125,0,10,11.955,true,1),run("theft-deterrent system.",14,0,125,13,23,8.966,true,2)},1,"unfinished sentence continues across inline font and size change");
 check({run("3. Turn the ignition on before",0,0,120,0,10,8.966,true,1),run("driving.",11,15.3,45,11,20,8.966,true,1)},1,"unfinished numbered instruction continues across a hanging indent");
 check({run("Slow Flash : Alarm system is",0,0,120,0,10,8.966,true,1),run("armed.",11,0,30,11,20,8.966,true,2)},1,"unfinished same-style alarm label continues across separate font handles");
 {
  auto lock=run("R : Fuse Block Cover Lock",0,0,120,0,11,11.955,true,1);
  lock.block["modelText"]="[[FVSYMCR]] : Fuse Block Cover Lock";
  auto location=run("Location",14,0,45,14,21,8.966,true,2);
  auto fuses=run("+ : Fuses",28,0,90,28,39,11.955,true,1);
  fuses.block["modelText"]="[[FVSYMCV]]: Fuses";
  auto restraints=run("Restraints",41,0,72,41,48,8.966,true,2);
  auto installed=run("Q : Keep Fuse Block Covers",55,0,125,55,66,11.955,true,1);
  installed.block["modelText"]="[[FVSYMDC]]: Keep Fuse Block Covers";
  auto properly=run("Properly Installed",68,0,105,68,75,8.966,true,2);
  const auto output=check({properly,installed,restraints,fuses,location,lock},3,"pictogram labels retain separate list entries while title-case continuations join");
  expect(output[0]["text"]=="R : Fuse Block Cover Lock Location","fuse cover location completes its own pictogram label");
  expect(output[0]["modelText"]=="[[FVSYMCR]] : Fuse Block Cover Lock Location","label modelText keeps the pictogram token and continuation");
  expect(output[1]["text"]=="+ : Fuses Restraints","ISOFIX continuation does not join the preceding Fuses pictogram label");
  expect(output[1]["modelText"]=="[[FVSYMCV]]: Fuses Restraints","second label continuation remains attached to its marker");
  expect(output[2]["text"]=="Q : Keep Fuse Block Covers Properly Installed","title-case continuation completes the cover label");
 }
 check({run("[[FVSYMCR]]: Fuse Block Cover",0,0,100,0,10,10,true,1),run("[[FVSYMCV]]: Fuses",11,0,70,11,21,10,true,1)},2,"a following pictogram marker always starts a separate entry");
 check({run("Vehicle Symbol Chart",0,0,120,0,12,12,true,1),run("Location",14,0,50,14,22,8,true,2)},2,"title-case continuation is limited to pictogram labels");
 check({run("Battery Replacement",0,0,120,0,15,15,true,1),run("Replace the battery if needed.",17,0,140,17,27,9,true,2)},2,"heading does not absorb a sentence-case body paragraph");
 {
  const auto output=check({run("Display. See ",10,0,50,1,10,9,true,1),run("Driver Information",10,53,125,1,10,9,true,2),run("Center (DIC).",20,0,80,11,20,9,true,2)},1,"a wrapped capitalized reference retains its final inline font across a mixed-style source line");
  expect(output[0]["text"]=="Display. See Driver Information Center (DIC).","the complete reference becomes one translation unit");
 }
 check({run("The doors are locked.",0,0,100,0,10,9,true,1),run("and the alarm is armed.",11,0,100,11,21,9,true,2)},2,"sentence-final line is not joined to a lowercase next line");
 check({run("Is the door locked?",0,0,100,0,10,9,true,1),run("and the alarm is armed.",11,0,100,11,21,9,true,2)},2,"question-final line is not joined to a lowercase next line");
 check({run("The doors are locked!",0,0,100,0,10,9,true,1),run("and the alarm is armed.",11,0,100,11,21,9,true,2)},2,"exclamation-final line is not joined to a lowercase next line");
 check({run("Remote Keyless Entry ........ 8",0,0,120,0,10,9,true,1),run("vehicle personalization",11,0,100,11,21,9,true,2)},2,"dotted contents leader is not joined to body text");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("onto the next line...",11,0,100,11,21,9,true,2)},2,"dotted leader on continuation line remains independent");
 check({run("Door Locks . . . . . 16",0,0,120,0,10,9,true,1),run("Power Door Locks . . . . . 19",11,0,120,11,21,9,true,1),run("Liftgate . . . . . 21",22,0,120,22,32,9,true,1)},3,"same-font contents entries keep individual labels and page references despite spaced dot leaders");
 check({run("Remote Keyless Entry (RKE)",0,0,120,0,10,9,true,1),run("System . . . . . 8",11,0,120,11,21,9,true,1),run("Door Locks . . . . . 16",22,0,120,22,32,9,true,1)},2,"a wrapped contents label joins its own reference line and stops before the next entry");
 {
  const auto output=check({run("Automatic Dimming Rearview",0,0,120,0,10,9,true,1),run("Mirror . . . . . 30",11,0,120,11,21,9,true,2)},1,"a contents reference can finish a wrapped label across a same-size font change");
  expect(output[0]["sourceText"]=="Automatic Dimming Rearview Mirror . . . . . 30","decorative leader removal never rewrites original source text");
  expect(output[0]["modelText"]=="Automatic Dimming Rearview Mirror","the model receives only the linguistic label while the source retains its page reference");
 }
 {
  const auto heading=run("10 Keys, Doors, and Windows",27,18,191,19,29,11,true,1);
  const auto body=run("from outside the vehicle using the",43,18,165,35,44,9,true,2);
  const std::vector<SourceRule> rules={{18,31,500,31}};
  expect(groupText({heading,body},&rules).size()==2,"a running-head rule prevents lowercase body text joining the heading");
  const std::vector<SourceRule> unrelated={{220,31,500,31},{18,80,500,80}};
  expect(groupText({heading,body},&unrelated).size()==1,"rules outside the shared text column or gap do not split a real continuation");
  const auto left=run("Metric",10,0,30,0,10),right=run("US",10,34,50,0,10);
  const std::vector<SourceRule> divider={{32,-5,32,20}};
  expect(groupText({left,right},&divider).size()==2,"adjacent table cells separated by an original grid line remain independent");
 }
 {
  auto icon=run("Q",10,0,7,0,12,12,true,3);icon.block["linguisticFontSize"]=0.;icon.block["symbols"]=json::array({{{"token","[[FVSYMA]]"}}});icon.block["modelText"]="[[FVSYMA]]";
  auto label=run(": Keep the cover",12,10,100,3,12,9,true,1);label.block["linguisticFontSize"]=9.;
  auto continuation=run("properly installed.",23,0,100,14,23,9,true,1);continuation.block["linguisticFontSize"]=9.;
  const auto output=check({continuation,label,icon},1,"a larger source pictogram does not change the body font or break its wrapped label");
  expect(output[0]["fontSize"]==9.,"body typography excludes the retained pictogram font size");
 }
 {
  auto otherColumn=run("Other column",10,200,280,1,10,9,true,1);
  auto label=run("Wiper/Washer ",12,39,143,3,12,9,true,1);
  auto icon=run("0 ",12,145,150,3,12,9,true,3);icon.block["symbols"]=json::array({{{"token","[[FVSYMB]]"}}});icon.block["linguisticFontSize"]=0.;icon.block["modelText"]="[[FVSYMB]]";
  auto reference=run("94.",12,154,165,3,12,9,true,1);
  const auto output=check({otherColumn,label,icon,reference},2,"a reference pictogram joins its closest printed row rather than an earlier nearby row in another column");
  expect(output[1]["text"]=="Wiper/Washer 0 94.","the reference and label retain one semantic unit");
 }
 {
  auto neighboringColumn=run("The system turns the high-beam",173.931335,41.327,170.992,167.521,175.805,8.966,true,1);
  auto press=run("press",176.373642,206.113,227.288,171.622,178.140,8.966,true,1);
  auto icon=run("b",176.371506,230.430,249.343,166.114,177.890,11.955,true,3);
  icon.block["symbols"]=json::array({{{"token","[[FVSYMCM]]"}}});
  icon.block["modelText"]="[[FVSYMCM]]";
  auto continuation=run("on the turn signal lever",176.371506,252.598,344.816,169.961,178.245,8.966,true,2);
  const auto output=check({neighboringColumn,icon,continuation,press},2,"an inline control glyph joins the nearest linguistic baseline, not an earlier row from another column");
  expect(output[1]["objectIndices"].size()==3,"the press, original glyph, and continuation share one source-owned unit");
  expect(output[1]["sourceText"]=="press b on the turn signal lever","the merged source preserves original reading order around the glyph");
  expect(output[1]["modelText"]=="press [[FVSYMCM]] on the turn signal lever","translation input preserves the symbol token inline");
 }
 {
  auto top=run("Top text",10,0,40,4,12,8.966,true,1);
  auto standalone=run("b",20,100,110,14,26,11.955,true,3);
  standalone.block["symbols"]=json::array({{{"token","[[FVSYMCM]]"}}});
  standalone.block["modelText"]="[[FVSYMCM]]";
  auto bottom=run("Bottom text",30,200,250,24,32,8.966,true,2);
  const auto output=check({bottom,standalone,top},3,"an unanchored standalone pictogram row is restored to baseline order after linguistic rows are built");
  expect(output[0]["text"]=="Top text"&&output[1]["modelText"]=="[[FVSYMCM]]"&&output[2]["text"]=="Bottom text",
         "standalone pictogram stays between the surrounding source rows without losing its token");
 }
 check({run("1. Pull the door handle.",0,0,120,0,10,9,true,1),run("2. Remove the cover.",11,0,120,11,21,9,true,1)},2,"adjacent numbered instructions cannot merge into one model unit");
 check({run("1. Pull the door handle and",0,0,120,0,10,9,true,1),run("remove the cover.",11,0,120,11,21,9,true,1)},1,"a wrapped numbered instruction retains its own continuation");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("\xe4\xb8\xad\xe6\x96\x87",11,0,100,11,21,9,true,2)},2,"non-Latin initial is not mistaken for lowercase English continuation");
 check({run("Pressing Q may also arm the",0,0,75,0,10,9,true,1),run("theft-deterrent system.",11,130,190,11,21,9,true,2)},2,"lowercase continuation in a separate column stays independent");
 check({run("This instruction continues",0,0,5,0,10,9,true,1),run("without a shared column",11,8,65,11,21,9,true,2)},2,"nearby but non-overlapping columns remain independent");
 check({run("This instruction continues",0,0,120,0,10,10,true,1),run("with a different font size",11,0,130,11,21,5,true,2)},2,"unbounded font-size changes do not trigger continuation");
 check({run("This instruction continues",0,0,120,0,10,9,false,1),run("on the next line",11,0,100,11,21,9,true,2)},2,"non-horizontal prior run remains independent");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("on the next line",11,0,100,11,21,9,false,2)},2,"non-horizontal next run remains independent");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("too soon",5,0,80,5,15,9,true,2)},2,"too-close baselines do not trigger continuation");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("too far away",20,0,80,20,30,9,true,2)},2,"distant baselines do not trigger continuation");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("with overlapping boxes",11,0,80,8,20,9,true,2)},2,"large negative line gap does not trigger continuation");
 check({run("This instruction continues",0,0,120,0,10,9,true,1),run("after a large box gap",11,0,80,20,30,9,true,2)},2,"large line gap does not trigger continuation");
 check({run("   ",0,0,120,0,10,9,true,1),run("onto the next line",11,0,100,11,21,9,true,2)},2,"blank prior line is not treated as unfinished prose");
 check({run("A",0,0,50),run("B",12,0,50,12,22,10,false)},2,"non-horizontal paragraph line independent");
 check({run("A",0,0,50),run("B",12,0,50,12,22,10,true,2)},2,"different font remains independent");
 check({run("A",0,0,50),run("B",12,0,50,12,22,15)},2,"different font size independent");
 check({run("A",0,0,50),run("B",12,30,80,12,22)},2,"column remains independent");
 check({run("A",0,0,50),run("B",6,0,50,6,16)},2,"tight baselines remain independent");
 check({run("A",0,0,50),run("B",18,0,50,18,28)},2,"wide baselines remain independent");
 check({run("A",0,0,50,0,30),run("B",12,0,50,12,22)},2,"overlapping text boxes remain independent");
 check({run("A",0,0,50,0,1),run("B",12,0,50,12,22)},2,"large box gap remains independent");
 check({run(std::string(1799,'a'),0,0,50),run("B",12,0,50,12,22)},2,"paragraph byte limit prevents join");
 auto a=run("A",0,0,50),b=run("B",6,0,50,6,16),c=run("C",12,0,50,12,21),d=run("D",20,0,50,20,30);
 check({a,b,c,d},2,"nearest paragraph retains both candidates");
 // Geometry combinations preserve source-object ownership even when the same
 // baseline contains overlaps, columns, rotations and variable font scales.
 for(int axis=0;axis<6;++axis)for(int value=-2;value<=6;++value){
  auto first=run("left",0,0,50),second=run("right",12,0,50,12,22);
  if(axis==0)second.baseline=value*4;
  if(axis==1)second.block["left"]=value*10;
  if(axis==2)second.block["top"]=value*5;
  if(axis==3)second.block["bottom"]=value*5;
  if(axis==4)second.block["fontSize"]=value+8;
  if(axis==5)second.horizontal=value>0;
  auto output=groupText({second,first});size_t actual=0;for(auto& r:output)actual+=r["objectIndices"].size();expect(actual==2,"geometry preserves source objects");
 }
}

void fixedSourceContainers(){
 using namespace translatepdf::detail;
 {
  const std::vector<SourceRule> rules={{0,20,100,20},{50,0,50,100},{0,150,100,150},{0,0,100,100}};
  const auto visible=occludeSourceRules(rules,{25,10,75,30});
  expect(visible.size()==6,"an opaque footer splits only the covered portions of prior horizontal and vertical rules");
  expect(visible[0].x1==25&&visible[1].x0==75,"horizontal rule remains visible on both sides of the mask");
  expect(visible[2].y1==10&&visible[3].y0==30,"vertical rule remains visible above and below the mask");
  expect(occludeSourceRules({{30,15,70,15}},{25,10,75,30}).empty(),"a fully covered divider cannot split a footer caption");
  bool rejected=false;try{occludeSourceRules({}, {0,0,0,10});}catch(const std::invalid_argument&){rejected=true;}expect(rejected,"invalid opaque masks are rejected");
 }
 const std::vector<SourceLayoutUnit> units={
  {"left-upper",{18.44,94.4,141.5,105.7},11.95},
  {"left-caption",{18.5,112.,164.,139.},8.97},
  {"left-lower",{18.5,269.8,165.8,282.},8.97},
  {"middle",{183.5,36.,329.,89.},8.97},
  {"right",{348.,36.,494.,110.},8.97}
 };
 const auto containers=sourceContainers(539.005,361.629,units,{{18.444,145.063,165.788,264.062}},
  {{174.349,33.,174.349,342.},{338.758,33.,338.758,342.}});
 expect(containers.size()==units.size(),"source planner retains every semantic identity");
 expect(containers[0].bounds.left==18.44&&containers[0].bounds.top==94.4,"paragraph origin remains in the original column");
 expect(containers[0].bounds.right<174.349&&containers[0].bounds.right>141.5,"paragraph may use column whitespace without crossing its original divider");
 expect(containers[0].bounds.bottom<112.,"preceding heading cannot consume the following paragraph");
 expect(containers[1].bounds.bottom<145.063&&containers[1].bounds.bottom>=139.,"upper text stops before the unchanged source photo");
 expect(containers[2].bounds.top>264.062&&containers[2].bounds.right<174.349,"lower text remains below the original photo and in its column");
 expect(containers[3].bounds.left==183.5&&containers[3].bounds.right<338.758,"middle column stays separate");
 for(const auto& c:containers)expect(c.error.empty(),"valid original column geometry has a usable container");
 const auto cells=sourceContainers(300,200,{{"row",{20,40,270,55},10}}, {},{{140,30,140,100}});
 expect(cells[0].error=="source_cell_split_required","a source text object crossing cells must split before inference, not be squeezed across the grid");
 const auto ruled=sourceContainers(300,200,{{"cell",{20,40,130,55},10}}, {},{{10,70,280,70}});
 expect(ruled[0].bounds.bottom<70.,"table row rules bound available vertical space");
 const auto closeRule=sourceContainers(300,200,{{"header",{20,19,190,29.16},11}}, {},{{18,29.76,280,29.76}});
 expect(closeRule[0].error.empty()&&closeRule[0].bounds.bottom>=29.16&&closeRule[0].bounds.bottom<29.76,"a running head beside its original rule retains its existing ink area");
 const auto peers=sourceContainers(300,200,{{"a",{10,40,90,50},10},{"b",{105,40,180,50},10}}, {},{});
 expect(peers[0].bounds.right<105.,"borderless adjacent columns cannot overlap");
 {
  const auto stacked=sourceContainers(104,100,
   {{"heading",{10,10,80,20},10},{"paragraph",{10,19.5,100,60},10}}, {},{});
  expect(stacked[0].bounds.bottom==20,
   "a following paragraph whose actual source bounds overlap the heading cannot donate later rows to its replacement");
  const auto otherColumn=sourceContainers(180,100,
   {{"heading",{10,10,80,20},10},{"column-peer",{95,19.5,160,60},10}}, {},{});
  const auto containedPeer=sourceContainers(180,100,
   {{"heading",{10,10,80,20},10},{"peer-ending-above",{10,15,80,19},10}}, {},{});
  expect(otherColumn[0].bounds.bottom>20&&containedPeer[0].bounds.bottom>20,
   "overlapping peer bounds do not clamp across columns or when the peer ends within the current unit");
  const auto measure=[](const std::string& text,double size){return static_cast<double>(text.size())*size*.5;};
  const auto ink=[](const std::string& text,double size){return SourceBox{0,-size*.6,static_cast<double>(text.size())*size*.5,size*.1};};
  const std::vector<SourceBox> icon{{46,12,60,19}};
  const auto longHeading=fitSourceChunks(stacked[0],{"A considerably extended heading label"},{},10,17.5,measure,ink,true,icon);
  const auto shortHeading=fitSourceChunks(stacked[0],{"Mode control"},{},10,17.5,measure,ink,true,icon);
  const auto broad=fitSourceChunks({"heading-wide",{10,10,100.36,100},{}},{"A considerably extended heading label"},{},10,17.5,measure,ink,true,icon);
  expect(!longHeading.fits&&shortHeading.fits&&broad.fits,
   "a long heading cannot wrap into a following source paragraph, while concise text and legacy available whitespace remain usable");
 }
 auto rejects=[](double width,double height,std::vector<SourceLayoutUnit> u,std::vector<SourceBox> art={},std::vector<SourceRule> rules={}){
  try{sourceContainers(width,height,u,art,rules);return false;}catch(const std::invalid_argument&){return true;}
 };
 expect(rejects(0,200,{}),"invalid page dimensions rejected");
 expect(rejects(300,200,{{"bad",{10,10,9,20},10}}),"reversed source rectangles rejected");
 expect(rejects(300,200,{{"bad",{-1,10,90,20},10}}),"off-page source rectangles rejected");
 expect(rejects(300,200,{{"bad",{10,10,90,20},0}}),"invalid original font size rejected");
 expect(rejects(300,200,{{"ok",{10,10,90,20},10}},{{10,20,10,30}}),"invalid artwork rectangle rejected");
 expect(sourceContainers(300,200,{}, {},{}).empty(),"blank original page remains blank");
}

void fixedGlyphTextFit(){
 using namespace translatepdf::detail;
 const auto measure=[](const std::string& text,double size){return wide(text).size()*size*.5;};
 const SourceContainer c{"paragraph",{10,10,110,65},{}};
 const SourceAnchor icon{{60,29,70,40},38};
 const auto fit=fitSourceChunks(c,{"first preceding words and text",": following words below the symbol"},{icon},10,18,measure);
 expect(fit.fits,"text before and after a fixed inline icon uses the original paragraph whitespace");
 bool earlier=false,sameLine=false,laterFullWidth=false;
 for(const auto& p:fit.placements){
  const double right=p.left+measure(p.text,p.fontSize)*p.widthScale;
  expect(p.left>=10&&right<=110.01,"fixed source text stays in its original column");
  if(p.baseline<29)earlier=true;
  if(std::abs(p.baseline-38)<.01){sameLine=true;expect(right<=59.01||p.left>=71,"original inline pictogram is never covered by replacement text");}
  if(p.baseline>43&&p.left==10)laterFullWidth=true;
 }
 expect(earlier&&sameLine&&laterFullWidth,"prefix uses earlier rows and suffix regains the full column below the fixed glyph");
 const auto unfit=fitSourceChunks({"tiny",{10,10,20,18},{}},{"long-unbreakable-content"},{},10,17,measure);
 expect(!unfit.fits&&unfit.placements.empty(),"unfit text is rejected without truncation or unreadable shrinking");
 const auto blocked=fitSourceChunks({"blocked",{10,10,110,65},"source_cell_split_required"},{"text"},{},10,18,measure);
 expect(!blocked.fits,"a source paragraph crossing table cells cannot be certified by text fitting");
 const auto small=fitSourceChunks({"small",{0,0,100,20},{}},{"text"},{},5,5,measure);
 expect(small.fits&&small.fontSize==5,"an originally small source label is not enlarged beyond its source size");
 const auto exactInk=[](const std::string& value,double size){return SourceBox{0,-size*.7,value.size()*size*.5,size*.12};};
 const auto closeHeader=fitSourceChunks({"header",{10,19,150,28.4},{}},{"heading"},{},11,26.87,measure,exactInk);
 expect(closeHeader.fits,"actual target ink can fit beside a running-head rule even when a generic descender estimate cannot");
 const auto descendingInk=[](const std::string& value,double size){return SourceBox{0,-size*.7,value.size()*size*.5,size*.9};};
 expect(!fitSourceChunks({"header",{10,19,150,28.4},{}},{"heading"},{},11,26.87,measure,descendingInk).fits,"actual descending glyph ink is checked through all readable font sizes");
 const auto cjkFit=fitSourceChunks({"cjk",{0,0,20,40},{}},{"中文，文字。"},{},10,9,measure);
 expect(cjkFit.fits,"CJK fits at character boundaries while retaining closing punctuation");
 bool rejected=false;try{fitSourceChunks(c,{"missing anchor chunk"},{icon},10,18,measure);}catch(const std::invalid_argument&){rejected=true;}
 expect(rejected,"symbol chunks must exactly match the source anchor inventory");
}

void sourceLayoutExceptionalContracts(){
 using namespace translatepdf::detail;
 {
  const SourceBox mask{30,10,70,30};
  const std::vector<SourceRule> outside={{0,20,20,20},{80,20,100,20},{40,0,40,5},{40,35,40,50},{5,5,15,15},{0,5,20,5},{10,15,10,25},{75,15,75,25}};
  const auto kept=occludeSourceRules(outside,mask);
  expect(kept.size()==outside.size(),"rules outside an opaque rectangle and diagonal rules remain unchanged");
  for(size_t i=0;i<outside.size();++i)
   expect(kept[i].x0==outside[i].x0&&kept[i].y0==outside[i].y0&&kept[i].x1==outside[i].x1&&kept[i].y1==outside[i].y1,
          "uncovered source rules preserve their original coordinates and order");
  const double nan=std::numeric_limits<double>::quiet_NaN();
  const double inf=std::numeric_limits<double>::infinity();
  for(const SourceBox bad:std::vector<SourceBox>{{nan,0,1,1},{0,nan,1,1},{0,0,nan,1},{0,0,1,nan},{0,0,0,1},{0,0,1,0}}){
   bool rejected=false;try{occludeSourceRules(outside,bad);}catch(const std::invalid_argument&){rejected=true;}
   expect(rejected,"opaque rectangle rejects each non-finite or zero-area edge");
  }
  bool infinityRejected=false;try{occludeSourceRules(outside,{0,0,inf,1});}catch(const std::invalid_argument&){infinityRejected=true;}
  expect(infinityRejected,"opaque rectangle rejects infinite coordinates");
  const auto topOnly=occludeSourceRules({{40,15,40,50}},mask);
  expect(topOnly.size()==1&&topOnly[0].y0==30&&topOnly[0].y1==50,"vertical clipping retains the segment below a mask when the rule begins inside it");
  const auto bottomOnly=occludeSourceRules({{40,0,40,25}},mask);
  expect(bottomOnly.size()==1&&bottomOnly[0].y0==0&&bottomOnly[0].y1==10,"vertical clipping retains the segment above a mask when the rule ends inside it");
 }
 {
  const double nan=std::numeric_limits<double>::quiet_NaN();
  const double inf=std::numeric_limits<double>::infinity();
  auto throws=[](auto fn){try{fn();return false;}catch(const std::invalid_argument&){return true;}};
  const std::vector<SourceLayoutUnit> valid={{"paragraph",{10,10,80,20},9}};
  expect(throws([&]{sourceContainers(nan,100,valid,{},{});}),"non-finite page width is rejected");
  expect(throws([&]{sourceContainers(100,inf,valid,{},{});}),"infinite page height is rejected");
  expect(throws([&]{sourceContainers(100,0,valid,{},{});}),"zero page height is rejected");
  expect(throws([&]{sourceContainers(100,100,{{"",{10,10,80,20},9}},{},{});}),"source units require a stable identity");
  expect(throws([&]{sourceContainers(100,100,{{"paragraph",{10,10,80,20},nan}},{},{});}),"non-finite source font size is rejected");
  expect(throws([&]{sourceContainers(100,100,{{"paragraph",{10,10,80,20},inf}},{},{});}),"infinite source font size is rejected");
  expect(throws([&]{sourceContainers(100,100,{{"paragraph",{nan,10,80,20},9}},{},{});}),"non-finite source rectangle coordinates are rejected");
  for(const SourceRule rule:std::vector<SourceRule>{{nan,0,1,1},{0,nan,1,1},{0,0,nan,1},{0,0,1,inf}})
   expect(throws([&]{sourceContainers(100,100,valid,{}, {rule});}),"non-finite source rule coordinates are rejected");
  expect(throws([&]{sourceContainers(100,100,{{"above-page",{10,-1,80,20},9}},{},{});}),"source text above the page is rejected");
  expect(throws([&]{sourceContainers(100,100,{{"beyond-right",{10,10,101,20},9}},{},{});}),"source text beyond the page width is rejected");
  expect(throws([&]{sourceContainers(100,100,{{"below-page",{10,10,80,101},9}},{},{});}),"source text below the page is rejected");
  const auto toleratedEdge=sourceContainers(100,100,{{"rounded-edge",{10,10,100.05,100.05},9}},{},{});
  expect(toleratedEdge[0].error.empty()&&toleratedEdge[0].bounds.right>=100.05&&toleratedEdge[0].bounds.bottom>=100.05,
         "the documented 0.1-point source-page tolerance does not shrink a unit into a false container conflict");
  const auto ruleBeforeUnitBottom=sourceContainers(100,100,{{"caption",{10,10,40,20},9}},{},{{0,15,50,15}});
  expect(ruleBeforeUnitBottom.size()==1&&ruleBeforeUnitBottom[0].bounds.bottom>=20,
         "a horizontal rule inside a source unit cannot move its text container above the unit");
  for(int offset=0;offset<16;++offset){
   const double x=10.+offset,y=20.+offset;
   const std::vector<SourceLayoutUnit> propertyUnits={
    {"base",{x,y,x+30,y+8},9},
    {"right-peer",{x+32,y,x+50,y+8},9},
    {"lower-peer",{x,y+11,x+30,y+20},9}
   };
   const auto planned=sourceContainers(240,200,propertyUnits,
    {{x+32,y,x+38,y+8},{x,y+22,x+28,y+26}},
    {{x+31,y+1,x+31,y+7},{x,y+21,x+30,y+21}});
   expect(planned.size()==propertyUnits.size(),"planner returns one container for every valid property-case source unit");
   for(size_t index=0;index<propertyUnits.size();++index){
    expect(planned[index].bounds.right>=propertyUnits[index].bounds.right-.001,
           "gutter, peer, artwork and divider clamps never cut into a valid unit's right edge");
    expect(planned[index].bounds.bottom>=propertyUnits[index].bounds.bottom-.001,
           "gutter, peer, artwork and divider clamps never cut into a valid unit's bottom edge");
   }
  }
  const auto artBound=sourceContainers(200,100,{{"caption",{10,10,80,20},9}},{{82,10,120,30}},{});
  expect(artBound[0].bounds.right<82&&artBound[0].bounds.right>=80,"non-overlapping artwork to the right limits usable text width");
  const auto artOnOtherRow=sourceContainers(200,100,{{"caption",{10,10,80,20},9}},{{82,40,120,60}},{});
  expect(artOnOtherRow[0].bounds.right>80,"artwork on another row cannot constrain horizontal text allocation");
  const auto labelInDiagram=sourceContainers(200,100,{{"diagram-label",{30,20,80,30},9}},{{20,10,100,50}},{});
  expect(labelInDiagram[0].bounds.right>80&&labelInDiagram[0].bounds.bottom>30,"an existing label inside a diagram retains its original ink container");
  const auto noPersistentGutter=sourceContainers(500,400,{{"row-a",{10,100,100,110},9},{"row-b",{105,200,200,210},9}}, {},{});
  expect(noPersistentGutter[0].bounds.right>200,"a sub-threshold one-off gap does not invent a persistent column boundary");
  const auto persistentGutter=sourceContainers(500,400,{{"row-a",{10,100,100,110},9},{"row-b",{106,200,200,210},9}}, {},{});
  expect(persistentGutter[0].bounds.right<105,"a persistent gutter bounds text even when rows do not vertically overlap");
  const auto spanIgnored=sourceContainers(500,400,{{"running-head",{10,5,490,15},9},{"left",{10,100,100,110},9},{"right",{300,100,390,110},9}}, {},{});
  expect(spanIgnored[1].bounds.right<300,"a spanning running head does not erase the lower two-column gutter");
  const auto titleFilters=sourceContainers(500,400,{{"wide-title",{10,100,255,110},9},{"footer",{10,390,100,399},9},{"lower-left",{10,200,100,210},9},{"lower-right",{300,200,390,210},9}}, {},{});
  expect(titleFilters[2].bounds.right<300,"a wide in-page title and below-margin footer are excluded from persistent column discovery");
 }
 {
  using Fit=SourceTextFit;
  const SourceContainer container{"three-columns",{10,10,160,60},{}};
  const std::vector<SourceAnchor> anchors={{{40,10,48,20},18},{{90,10,98,20},38}};
  const auto measure=[](const std::string& text,double size){return static_cast<double>(text.size())*size*.5;};
  const auto fit=fitSourceChunks(container,{"pre","mid","post"},anchors,10,0,measure);
  expect(fit.fits&&fit.placements.size()==3,"each translated chunk flows before, between, and after multiple fixed original icons");
  expect(fit.placements[0].text=="pre"&&fit.placements[1].text=="mid"&&fit.placements[2].text=="post","multiple fixed icons retain ordered text chunks");
  for(const auto& p:fit.placements){
   const double right=p.left+measure(p.text,p.fontSize)*p.widthScale;
   for(const auto& anchor:anchors){
    const SourceBox ink{p.left,p.baseline-p.fontSize*.85,right,p.baseline+p.fontSize*.25};
    const bool overlaps=std::min(ink.right,anchor.bounds.right)>std::max(ink.left,anchor.bounds.left)+.1&&
      std::min(ink.bottom,anchor.bounds.bottom)>std::max(ink.top,anchor.bounds.top)+.1;
    expect(!overlaps,"wrapped text never occupies either fixed icon rectangle");
   }
  }
  const std::vector<SourceAnchor> closeOverlappingAnchors={{{40,35,60,45},45},{{50,35,70,45},45},{{30,35,50,45},45}};
  const auto overlapFit=fitSourceChunks({"overlapping-source-glyphs",{10,10,160,80},{}},{"ok","","","ok"},closeOverlappingAnchors,10,10,measure);
  expect(overlapFit.fits,"text flow remains valid with nearby overlapping source glyph bounds from mixed object order");
  for(const auto& placement:overlapFit.placements)
   for(const auto& anchor:closeOverlappingAnchors){
    const SourceBox ink{placement.left,placement.baseline-placement.fontSize*.85,
     placement.left+measure(placement.text,placement.fontSize)*placement.widthScale,
     placement.baseline+placement.fontSize*.25};
    const bool overlaps=std::min(ink.right,anchor.bounds.right)>std::max(ink.left,anchor.bounds.left)+.1&&
     std::min(ink.bottom,anchor.bounds.bottom)>std::max(ink.top,anchor.bounds.top)+.1;
    expect(!overlaps,"overlapping source glyph bounds remain uncovered by translated text");
   }
  const auto newline=fitSourceChunks({"hard-lines",{10,10,100,60},{}},{"alpha\nbeta\xc2\xa0gamma"},{},10,20,measure);
  expect(newline.fits&&newline.placements.size()==2&&newline.placements[0].text=="alpha"&&newline.placements[1].text=="beta gamma",
         "explicit newlines make hard rows while non-breaking spaces normalize between words");
  const auto multiRow=fitSourceChunks({"multi-row-prefix",{0,0,120,80},{}},{"Words wrap around a fixed original icon using several untouched paragraph rows.",""},{{{60,60,70,70},60}},10,0,measure);
  expect(multiRow.fits&&multiRow.placements.size()>=3,"long prefix text occupies multiple source rows before an inline fixed icon");
  const auto whitespace=fitSourceChunks({"separators",{10,10,100,60},{}},{"alpha\tbeta\rgamma"},{},10,20,measure);
  expect(whitespace.fits&&whitespace.placements.size()==1&&whitespace.placements[0].text=="alpha beta gamma","tabs and carriage returns remain word separators");
  const auto punctuation=fitSourceChunks({"cjk-punctuation",{10,10,100,60},{}},{"中文，文字。"},{},10,20,measure);
  expect(punctuation.fits&&punctuation.placements.size()==1&&punctuation.placements[0].text=="中文，文字。","CJK closing punctuation stays attached to its preceding characters");
  const auto asciiClosing=fitSourceChunks({"ascii-closing",{10,10,100,60},{}},{"\xe4\xb8\xad)"},{},10,20,measure);
  expect(asciiClosing.fits&&asciiClosing.placements.size()==1&&asciiClosing.placements[0].text=="\xe4\xb8\xad)","ASCII closing punctuation after a CJK atom stays attached to that atom");
  const auto leadingClosing=fitSourceChunks({"leading-closing",{10,10,100,60},{}},{") begins a new paragraph"},{},10,20,measure);
  expect(leadingClosing.fits&&!leadingClosing.placements.empty()&&leadingClosing.placements[0].text.front()==')',"closing punctuation without a preceding atom remains in its original text flow");
  const auto punctuationAfterBreak=fitSourceChunks({"punctuation-after-break",{10,10,100,60},{}},{"word\n, next"},{},10,20,measure);
  expect(punctuationAfterBreak.fits&&punctuationAfterBreak.placements.size()==2&&punctuationAfterBreak.placements[1].text==", next","closing punctuation after a hard line break begins the following row");
  const auto detachedMark=fitSourceChunks({"detached-mark",{10,10,100,60},{}},{"中\xcc\x81文"},{},10,20,measure);
  expect(detachedMark.fits&&detachedMark.placements.size()==1&&detachedMark.placements[0].text=="中\xcc\x81文","a combining mark following a flushed CJK atom remains attached to that atom");
  const auto combiningAfterLetter=fitSourceChunks({"combining-letter",{10,10,100,60},{}},{"e\xcc\x81"},{},10,20,measure);
  expect(combiningAfterLetter.fits&&combiningAfterLetter.placements[0].text=="e\xcc\x81","a combining mark in the same grapheme remains intact");
  const std::string acute="\xcc\x81";
  const auto leadingCombining=fitSourceChunks({"leading-combining",{10,10,100,60},{}},{acute+"a"},{},10,20,measure);
  expect(leadingCombining.fits&&leadingCombining.placements[0].text==acute+"a","a leading combining mark is preserved without attaching to unrelated text");
  const auto combiningAfterBreak=fitSourceChunks({"combining-after-break",{10,10,100,60},{}},{"a\n"+acute+"b"},{},10,20,measure);
  expect(combiningAfterBreak.fits&&combiningAfterBreak.placements.size()==2&&combiningAfterBreak.placements[1].text==acute+"b","a combining mark after a hard break remains with the next row rather than crossing it");
  const auto earlierAnchor=fitSourceChunks(container,{"text",""},{{{40,10,48,20},5}},10,20,measure);
  expect(!earlierAnchor.fits,"a fixed icon baseline preceding its text start cannot produce an invalid flow slot");
  auto throws=[](auto fn){try{fn();return false;}catch(const std::invalid_argument&){return true;}};
  expect(throws([&]{fitSourceChunks(container,{"text"},{},10,20,{});}),"text fitting requires a real font measurement callback");
  expect(throws([&]{fitSourceChunks(container,{"left","right"},{},10,20,measure);}),"text and fixed-icon chunk counts must agree");
  expect(throws([&]{fitSourceChunks(container,{"text"},{},std::numeric_limits<double>::quiet_NaN(),20,measure);}),"text fitting rejects a non-finite original font size");
  expect(throws([&]{fitSourceChunks(container,{"text"},{},0,20,measure);}),"text fitting rejects non-positive source font size");
  expect(throws([&]{fitSourceChunks(container,{"text"},{},10,std::numeric_limits<double>::infinity(),measure);}),"text fitting rejects a non-finite first baseline");
  expect(throws([&]{fitSourceChunks({"bad-bounds",{10,10,10,20},{}},{"text"},{},10,20,measure);}),"text fitting rejects an invalid source container");
  expect(throws([&]{fitSourceChunks(container,{"before","after"},{{{40,10,40,20},18}},10,0,measure);}),"fixed source anchors must have positive-area bounds");
  expect(throws([&]{fitSourceChunks(container,{"before","after"},{{{40,10,48,20},std::numeric_limits<double>::quiet_NaN()}},10,0,measure);}),"fixed source anchors require a finite baseline");
 }
 {
  const SourceContainer c{"ink-check",{10,10,110,65},{}};
  const auto measure=[](const std::string&,double){return 3.;};
  auto ink=[](double left,double top,double right,double bottom){return [=](const std::string&,double){return SourceBox{left,top,right,bottom};};};
  expect(fitSourceChunks(c,{"x"},{},10,20,measure,ink(0,-8,5,2)).fits,"actual glyph ink inside all four container edges is accepted");
  const std::vector<SourceBox> badInk={{-2,-8,5,2},{0,-8,140,2},{0,-11,5,2},{0,-8,5,46}};
  const char* overflowReasons[]={"actual left ink overflow is rejected","actual right ink overflow is rejected","actual top ink overflow is rejected","actual bottom ink overflow is rejected"};
  for(size_t index=0;index<badInk.size();++index)
   expect(!fitSourceChunks(c,{"x"},{},10,20,measure,[bounds=badInk[index]](const std::string&,double){return bounds;}).fits,overflowReasons[index]);
  bool badInkRejected=false;try{fitSourceChunks(c,{"x"},{},10,20,measure,ink(0,0,0,1));}catch(const std::invalid_argument&){badInkRejected=true;}
  expect(badInkRejected,"invalid target ink measurements are rejected instead of treated as readable fit");
  const std::vector<SourceAnchor> anchor={{{15,10,25,30},20}};
  expect(!fitSourceChunks(c,{"x",""},anchor,10,20,measure,ink(0,-8,7,2)).fits,
         "measured glyph ink cannot overlap a fixed source anchor even when advances fit");
  const std::vector<SourceAnchor> distantAnchor={{{60,29,70,40},38}};
  const auto separatedInk=fitSourceChunks(c,{"prefix","suffix"},distantAnchor,10,18,measure,ink(0,-8,5,2));
  expect(separatedInk.fits,"actual target ink that stays clear of an inline source anchor is accepted");
  const auto clippedSliver=fitSourceChunks({"narrow-anchor-prefix",{10,10,110,65},{}},{"x",""},{{{11.05,15,20,25},20}},10,20,measure);
  expect(!clippedSliver.fits,"a source glyph leaving only a sub-tenth-point prefix slot cannot certify unreadable text placement");
  const auto scaled=fitSourceChunks({"scale",{10,10,38,28},{}},{"abcdefgh"},{},10,20,
      [](const std::string& text,double size){return text.size()*size*.5;});
  expect(scaled.fits&&scaled.fontSize<=10&&scaled.widthScale<1,"readable width scaling is used only after preserving source font cap");
  const auto noScale=fitSourceChunks({"scale",{10,10,38,28},{}},{"abcdefgh"},{},10,20,
      [](const std::string& text,double size){return text.size()*size*.5;},{},false);
  expect(!noScale.fits,"disallowed horizontal scaling cannot certify an overwide label");
  bool nonfiniteAdvanceRejected=false;try{fitSourceChunks(c,{"x"},{},10,20,[](const std::string&,double){return std::numeric_limits<double>::infinity();});}catch(const std::invalid_argument&){nonfiniteAdvanceRejected=true;}
  expect(nonfiniteAdvanceRejected,"non-finite target advances are rejected");
  bool negativeAdvanceRejected=false;try{fitSourceChunks(c,{"x"},{},10,20,[](const std::string&,double){return -1.;});}catch(const std::invalid_argument&){negativeAdvanceRejected=true;}
  expect(negativeAdvanceRejected,"negative target advances are rejected");
 }
}

}
void sourceFlowReadabilityContracts(){
 using namespace translatepdf::detail;
 const auto candidate=[](bool fits,double size,double scale){SourceTextFit f;f.fits=fits;f.fontSize=size;f.widthScale=scale;return f;};
 const auto fixed=candidate(true,9,1);
 expect(preferSourceFlow(fixed,candidate(true,9.21,1),true),"a readable font gain can justify verified inline glyph movement");
 expect(!preferSourceFlow(fixed,candidate(true,9.2,1),true),"one fitter step or a tie preserves fixed source glyph positions");
 expect(!preferSourceFlow(fixed,candidate(false,0,1),true),"a failed flow candidate never replaces fixed text");
 expect(preferSourceFlow(candidate(false,0,1),candidate(true,9,1),false),"previously verified fallback survives the narrower preference gate");
 expect(!preferSourceFlow(candidate(true,9,.8),candidate(true,9.5,1),false),"multiline or protected symbols cannot prefer flow");
 expect(preferSourceFlow(candidate(true,9,.95),candidate(true,8.9,1),true),"removing compression with comparable font size improves readable prose");
 expect(!preferSourceFlow(candidate(true,12,.95),candidate(true,9,1),true),"removing compression cannot justify a large font reduction");
 expect(!preferSourceFlow(candidate(true,9,.95),candidate(true,9,.95),true),"compressed flow has no typography advantage");
 expect(sameBaselineSentenceInline(12,12.1,12.05,9,12,9),"inline controls share the actual neighboring text baseline");
 expect(!sameBaselineSentenceInline(10,12,12,9,12,9),"preceding line belongs to a different baseline");
 expect(!sameBaselineSentenceInline(12,12,14,9,12,9),"following line belongs to a different baseline");
 expect(!sameBaselineSentenceInline(11,12,13,9,12,9),"opposite baseline offsets cannot drift into the same row");
 for(int position=0;position<6;++position){double values[]={12,12.1,12.05,9,12,9};values[position]=std::numeric_limits<double>::quiet_NaN();expect(!sameBaselineSentenceInline(values[0],values[1],values[2],values[3],values[4],values[5]),"every actual baseline/font size must be finite");}
 for(int position=3;position<6;++position){double values[]={12,12.1,12.05,9,12,9};values[position]=0;expect(!sameBaselineSentenceInline(values[0],values[1],values[2],values[3],values[4],values[5]),"every neighboring source font size must be positive");}
}
void foreignSourceGlyphObstacleContracts(){
 using namespace translatepdf::detail;
 const auto measure=[](const std::string& text,double size){return static_cast<double>(text.size())*size*.1;};
 const auto ink=[](const std::string& text,double size){return SourceBox{0,-size*.8,static_cast<double>(text.size())*size*.1,size*.2};};
 const SourceContainer box{"foreign-glyph-obstacle",{0,0,13,32.8},{}};
 const auto freeFit=fitSourceChunks(box,{"abcdefgh"},{},10,20,measure,ink);
 const std::vector<SourceBox> foreign{{6.25,12,12,22}};
 const auto protectedFit=fitSourceChunks(box,{"abcdefgh"},{},10,20,measure,ink,true,foreign);
 expect(freeFit.fits&&freeFit.fontSize==10,"unobstructed source text keeps its requested source font size");
 expect(protectedFit.fits&&protectedFit.fontSize<freeFit.fontSize&&protectedFit.fontSize>=8,
  "measured glyph collision triggers a readable fitting adjustment rather than an unchecked source placement");
 for(const auto& placement:protectedFit.placements){const auto bounds=ink(placement.text,placement.fontSize);
  const SourceBox actual{placement.left+bounds.left*placement.widthScale,placement.baseline+bounds.top,
   placement.left+bounds.right*placement.widthScale,placement.baseline+bounds.bottom};
  for(const auto& obstacle:foreign)expect(
   std::min(actual.right,obstacle.right)<=std::max(actual.left,obstacle.left)+.1||
   std::min(actual.bottom,obstacle.bottom)<=std::max(actual.top,obstacle.top)+.1,
   "the adjusted target ink respects the source glyph collision tolerance");
 }
 const std::vector<SourceBox> fullyBlocking{{0,0,13,32.8}};
 expect(!fitSourceChunks(box,{"abcdefgh"},{},10,20,measure,ink,true,fullyBlocking).fits,
  "the planner rejects the page when every readable candidate overlaps a foreign glyph");
 const auto outside=fitSourceChunks(box,{"abcdefgh"},{},10,20,measure,{},true,
  {{30,12,40,22},{-10,12,-5,22}});
 expect(outside.fits&&outside.fontSize==10&&outside.placements.size()==1&&
  outside.placements.front().left==0&&outside.placements.front().baseline==20&&
  outside.placements.front().text=="abcdefgh",
  "advance-only callers retain the original line when foreign glyphs lie wholly outside both horizontal edges");
 bool invalidRejected=false;
 try{fitSourceChunks(box,{"abcdefgh"},{},10,20,measure,ink,true,{{4,4,4,8}});}catch(const std::invalid_argument&){invalidRejected=true;}
 expect(invalidRejected,"foreign source glyph obstacles require finite positive-area bounds");
 const SourceContainer narrowTitle{"measured-title",{10,0,100,18},{}};
 const auto titleMeasure=[](const std::string& text,double){return static_cast<double>(text.size())*3.;};
 const auto titleInk=[](const std::string& text,double){return SourceBox{0,-5,static_cast<double>(text.size())*3.,1};};
 const auto titleWithoutObstacle=fitSourceChunks(narrowTitle,{"Title"},{},10,10,titleMeasure,titleInk);
 const auto titleWithCoarseOverlap=fitSourceChunks(narrowTitle,{"Title"},{},10,10,titleMeasure,titleInk,true,{{20,11.5,40,12.2}});
 expect(titleWithoutObstacle.fits&&titleWithCoarseOverlap.fits&&
        titleWithoutObstacle.placements.front().left==titleWithCoarseOverlap.placements.front().left&&
        titleWithoutObstacle.placements.front().baseline==titleWithCoarseOverlap.placements.front().baseline,
        "a coarse row box cannot move a title when its measured ink clears the foreign glyph");
 const std::vector<SourceBox> fullRowObstacle{{0,12,13,21}};
 const auto rerouted=fitSourceChunks(box,{"abcdefgh"},{},10,20,measure,ink,true,fullRowObstacle);
 expect(rerouted.fits&&rerouted.fontSize<10&&rerouted.fontSize>=8&&rerouted.placements.front().baseline>20,
        "a real foreign glyph collision reroutes text and reduces font only as far as the source container requires");
}
int main() {
  try { fontCoverage(); unicodeDecisions(); groupingContracts(); fixedSourceContainers(); fixedGlyphTextFit(); sourceLayoutExceptionalContracts(); sourceFlowReadabilityContracts(); foreignSourceGlyphObstacleContracts(); std::cout << "Core font/Unicode contracts passed\n"; return 0; }
  catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
