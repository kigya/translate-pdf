#pragma once
// Internal contracts, shared by production and host tests. Definitions remain
// in core.cpp so tests exercise the instrumented production object exactly once.
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include "fpdfview.h"
#include <nlohmann/json.hpp>
namespace translatepdf::detail {
uint16_t be16(const std::vector<unsigned char>&, size_t);
uint32_t be32(const std::vector<unsigned char>&, size_t);
struct FontCoverage {
 const std::vector<unsigned char>& data;
 size_t subtable=0;
 uint16_t format=0;
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
struct GlyphUnit { std::u16string text; FPDF_FONT font; uint32_t codepoint; bool space; };
struct TextSegment { std::u16string text; FPDF_FONT font; };
using WrappedLine = std::vector<TextSegment>;
float glyphWidth(const GlyphUnit&,float);
std::vector<WrappedLine> lines(FPDF_FONT,FPDF_FONT,const FontCoverage&,const FontCoverage*,const std::string&,float,float);
struct TextRun {
 nlohmann::json block;
 double baseline;
 FPDF_FONT font;
 bool horizontal;
};
nlohmann::json groupText(std::vector<TextRun>);

}
