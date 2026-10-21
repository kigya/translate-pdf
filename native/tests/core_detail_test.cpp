// Exercise the production parser and Unicode definitions linked from core.cpp.
#include "core_detail.hpp"
#include <algorithm>
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

}
int main() {
  try { fontCoverage(); unicodeDecisions(); groupingContracts(); std::cout << "Core font/Unicode contracts passed\n"; return 0; }
  catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
