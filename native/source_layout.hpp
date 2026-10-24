#pragma once
#include <string>
#include <vector>
#include <functional>

namespace translatepdf::detail {
struct SourceBox { double left,top,right,bottom; };
struct SourceRule { double x0,y0,x1,y1; };
struct SourceLayoutUnit { std::string id;SourceBox bounds;double fontSize; };
struct SourceContainer { std::string id;SourceBox bounds;std::string error; };
// Remove portions hidden by a later, verified opaque rectangle. Invisible
// printer/column rules must not split text printed over that rectangle.
std::vector<SourceRule> occludeSourceRules(const std::vector<SourceRule>&,const SourceBox&);

// All coordinates are in the original visible page, measured from its top left.
// Artwork never moves. The planner allocates only whitespace around source text.
std::vector<SourceContainer> sourceContainers(double width,double height,
 const std::vector<SourceLayoutUnit>& units,const std::vector<SourceBox>& artwork,
 const std::vector<SourceRule>& rules);
struct SourceAnchor { SourceBox bounds;double baseline; };
struct SourceTextPlacement { std::string text;double left,baseline,fontSize,widthScale; };
struct SourceTextFit { bool fits;std::vector<SourceTextPlacement> placements;double fontSize,widthScale;SourceTextFit(); };
// Each chunk lies before/between/after the fixed source anchors. Glyphs remain
// at their original matrices; only linguistic text occupies the free line slots.
SourceTextFit fitSourceChunks(const SourceContainer&,const std::vector<std::string>& chunks,
 const std::vector<SourceAnchor>& anchors,double originalFontSize,double firstBaseline,
 const std::function<double(const std::string&,double)>& measure,
 const std::function<SourceBox(const std::string&,double)>& inkBounds={},bool allowWidthScaling=true,
 const std::vector<SourceBox>& foreignGlyphInk={});
bool sameBaselineSentenceInline(double precedingBaseline,double symbolBaseline,double followingBaseline,
 double precedingFontSize,double symbolFontSize,double followingFontSize);
bool preferSourceFlow(const SourceTextFit& fixed,const SourceTextFit& flow,bool sentenceInlineEligible);
}
