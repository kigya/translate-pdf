#pragma once
// Internal contracts, shared by production and host tests. Definitions remain
// in core.cpp so tests exercise the instrumented production object exactly once.
#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <vector>
#include "fpdfview.h"
#include <nlohmann/json.hpp>
namespace translatepdf::detail {
uint16_t be16(const std::vector<unsigned char>&, size_t);
uint32_t be32(const std::vector<unsigned char>&, size_t);
struct FontCoverage {
 const std::vector<unsigned char>& data;
 size_t subtable;
 uint16_t format;
 explicit FontCoverage(const std::vector<unsigned char>&);
 bool has(uint32_t) const;
};
std::u16string wide(const std::string&);
std::string utf8(const std::u16string&);
uint32_t codepointAt(const std::u16string&, size_t&);
bool combining(uint32_t);
bool cjk(uint32_t);
bool openingPunctuation(uint32_t);
bool closingPunctuation(uint32_t);
bool languageInvariantAscii(const std::string&);
struct InlineGlyph { std::string token; float widthEm; float heightEm; InlineGlyph(std::string,float width=1.f,float height=1.f); };
struct GlyphUnit { std::u16string text; FPDF_FONT font; uint32_t codepoint; bool space; float widthEm; float heightEm; std::string symbolToken; GlyphUnit(std::u16string,FPDF_FONT,uint32_t,bool,float width=0.f,float height=0.f,std::string symbol={}); };
struct TextSegment { std::u16string text; FPDF_FONT font; std::string symbolToken; float widthEm; float heightEm; TextSegment(std::u16string,FPDF_FONT,std::string symbol={},float width=0.f,float height=0.f); };
using WrappedLine = std::vector<TextSegment>;
float glyphWidth(const GlyphUnit&,float);
std::vector<WrappedLine> lines(FPDF_FONT,FPDF_FONT,const FontCoverage&,const FontCoverage*,const std::string&,float,float,bool allowTokenBreak=false,const std::map<uint32_t,InlineGlyph>* inlineGlyphs=nullptr);
struct TextRun {
 nlohmann::json block;
 double baseline;
 FPDF_FONT font;
 bool horizontal;
 FPDF_FONT tailFont;
 TextRun(nlohmann::json,double,FPDF_FONT,bool,FPDF_FONT tail=nullptr);
};
struct SourceRule;
nlohmann::json groupText(std::vector<TextRun>,const std::vector<SourceRule>* sourceRules=nullptr);

}
