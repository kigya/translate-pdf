#include "source_layout.hpp"
#include "core_detail.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <utility>

namespace translatepdf::detail {
bool sameBaselineSentenceInline(double before,double symbol,double after,double beforeSize,double symbolSize,double afterSize){
 for(double value:{before,symbol,after,beforeSize,symbolSize,afterSize})if(!std::isfinite(value))return false;
 for(double size:{beforeSize,symbolSize,afterSize})if(size<=0)return false;
 const double tolerance=std::min({beforeSize,symbolSize,afterSize})*.15;
 return std::abs(before-symbol)<=tolerance&&std::abs(after-symbol)<=tolerance&&std::abs(before-after)<=tolerance;
}
bool preferSourceFlow(const SourceTextFit& fixed,const SourceTextFit& flow,bool eligible){
 if(!flow.fits)return false;
 if(!fixed.fits)return true;
 if(!eligible)return false;
 constexpr double epsilon=1e-9;
 if(flow.fontSize-fixed.fontSize>.2+epsilon)return true;
 return fixed.widthScale<1.-epsilon&&flow.widthScale>=1.-epsilon&&flow.fontSize>=fixed.fontSize-.2-epsilon;
}
SourceTextFit::SourceTextFit():fits(false),fontSize(0),widthScale(1){}
namespace {
bool finiteBox(const SourceBox& b){
 return std::isfinite(b.left)&&std::isfinite(b.top)&&std::isfinite(b.right)&&std::isfinite(b.bottom)&&b.right>b.left&&b.bottom>b.top;
}
bool overlap(double a,double b,double c,double d){return std::min(b,d)>std::max(a,c)+0.1;}
bool intersects(const SourceBox& a,const SourceBox& b){return overlap(a.left,a.right,b.left,b.right)&&overlap(a.top,a.bottom,b.top,b.bottom);}
}
std::vector<SourceRule> occludeSourceRules(const std::vector<SourceRule>& rules,const SourceBox& mask){
 if(!finiteBox(mask))throw std::invalid_argument("Invalid source occlusion rectangle");
 std::vector<SourceRule> result;
 for(const auto& rule:rules){
  if(std::abs(rule.y0-rule.y1)<.3&&rule.y0>=mask.top&&rule.y0<=mask.bottom){
   const double left=std::min(rule.x0,rule.x1),right=std::max(rule.x0,rule.x1);
   if(right<=mask.left||left>=mask.right){result.push_back(rule);continue;}
   if(left<mask.left)result.push_back({left,rule.y0,mask.left,rule.y0});
   if(right>mask.right)result.push_back({mask.right,rule.y0,right,rule.y0});
  }else if(std::abs(rule.x0-rule.x1)<.3&&rule.x0>=mask.left&&rule.x0<=mask.right){
   const double top=std::min(rule.y0,rule.y1),bottom=std::max(rule.y0,rule.y1);
   if(bottom<=mask.top||top>=mask.bottom){result.push_back(rule);continue;}
   if(top<mask.top)result.push_back({rule.x0,top,rule.x0,mask.top});
   if(bottom>mask.bottom)result.push_back({rule.x0,mask.bottom,rule.x0,bottom});
  }else result.push_back(rule);
 }
 return result;
}
std::vector<SourceContainer> sourceContainers(double width,double height,
 const std::vector<SourceLayoutUnit>& units,const std::vector<SourceBox>& artwork,
 const std::vector<SourceRule>& rules){
 if(!std::isfinite(width)||!std::isfinite(height)||width<=0||height<=0)throw std::invalid_argument("Invalid source page dimensions");
 double rightMargin=width*.035,bottomMargin=height*.035;
 std::vector<std::pair<double,double>> intervals;
 for(const auto& u:units){
  if(!finiteBox(u.bounds)||!std::isfinite(u.fontSize)||u.fontSize<=0||u.id.empty())throw std::invalid_argument("Invalid source layout unit");
  if(u.bounds.left<0||u.bounds.top<0||u.bounds.right>width+.1||u.bounds.bottom>height+.1)throw std::invalid_argument("Source layout unit is outside its page");
  rightMargin=std::min(rightMargin,width-u.bounds.right);bottomMargin=std::min(bottomMargin,height-u.bounds.bottom);
  // Ignore running heads and spanning titles when finding persistent gutters.
  if(u.bounds.top>=height*.08&&u.bounds.bottom<=height*.97&&u.bounds.right-u.bounds.left<width*.49)
   intervals.emplace_back(u.bounds.left,u.bounds.right);
 }
 std::sort(intervals.begin(),intervals.end());std::vector<std::pair<double,double>> occupied;
 for(const auto& v:intervals){if(occupied.empty()||v.first>occupied.back().second+1)occupied.push_back(v);else occupied.back().second=std::max(occupied.back().second,v.second);}
 std::vector<double> gutters;
 for(size_t i=1;i<occupied.size();++i)if(occupied[i].first-occupied[i-1].second>=std::max(6.,width*.01))gutters.push_back((occupied[i].first+occupied[i-1].second)*.5);
 std::vector<SourceContainer> result;result.reserve(units.size());
 for(size_t index=0;index<units.size();++index){const auto& u=units[index];const auto& b=u.bounds;
  SourceContainer c{u.id,{b.left,b.top,width-rightMargin,height-bottomMargin},{}};
  for(double gutter:gutters)if(gutter>=b.right+.1)c.bounds.right=std::min(c.bounds.right,gutter-std::min(1.5,(gutter-b.right)*.5));
  for(const auto& rule:rules){
   if(!std::isfinite(rule.x0)||!std::isfinite(rule.y0)||!std::isfinite(rule.x1)||!std::isfinite(rule.y1))throw std::invalid_argument("Invalid source rule");
   const double x=std::min(rule.x0,rule.x1),y=std::min(rule.y0,rule.y1);
   if(std::abs(rule.x0-rule.x1)<.3&&overlap(b.top,b.bottom,std::min(rule.y0,rule.y1),std::max(rule.y0,rule.y1))){
    if(x>b.left+.8&&x<b.right-.8)c.error="source_cell_split_required";
    else if(x>=b.right-.1)c.bounds.right=std::min(c.bounds.right,std::max(b.right,x-std::min(1.,std::max(0.,x-b.right)*.5)));
   }
   if(std::abs(rule.y0-rule.y1)<.3&&y>=b.bottom-.1&&overlap(b.left,b.right,std::min(rule.x0,rule.x1),std::max(rule.x0,rule.x1)))c.bounds.bottom=std::min(c.bounds.bottom,std::max(b.bottom,y-std::min(1.,std::max(0.,y-b.bottom)*.5)));
  }
  // Original peer positions jointly bound every allocation: a preceding unit
  // cannot consume the whitespace reserved for a later source paragraph/cell.
  for(size_t peer=0;peer<units.size();++peer){if(peer==index)continue;const auto& p=units[peer].bounds;
   if(p.left>=b.right+.5&&overlap(b.top,b.bottom,p.top,p.bottom))c.bounds.right=std::min(c.bounds.right,p.left-std::min(1.5,(p.left-b.right)*.5));
   if(overlap(c.bounds.left,c.bounds.right,p.left,p.right)){
    if(p.top>=b.bottom+.1)c.bounds.bottom=std::min(c.bounds.bottom,p.top-std::min(1.5,(p.top-b.bottom)*.5));
    else if(p.top>b.top+.1&&p.bottom>b.bottom+.1)c.bounds.bottom=std::min(c.bounds.bottom,b.bottom);
   }
  }
  for(const auto& art:artwork){if(!finiteBox(art))throw std::invalid_argument("Invalid source artwork bounds");
   if(intersects(b,art))continue; // Existing labels may lie inside a diagram.
   if(art.left>=b.right+.1&&overlap(b.top,b.bottom,art.top,art.bottom))c.bounds.right=std::min(c.bounds.right,art.left-std::min(1.5,(art.left-b.right)*.5));
   if(art.top>=b.bottom+.1&&overlap(c.bounds.left,c.bounds.right,art.left,art.right))c.bounds.bottom=std::min(c.bounds.bottom,art.top-std::min(1.5,(art.top-b.bottom)*.5));
  }
  // Initial edges enclose every validated unit, and each clamp keeps at least
  // its source edge (or half of the positive gap). No allocation can cut into
  // its own source bounds; crossing a divider is reported above explicitly.
  result.push_back(std::move(c));
 }
 return result;
}
namespace {
struct Atom {std::string text;bool space;bool hardBreak=false;};
std::vector<Atom> sourceAtoms(const std::string& text){
 const auto value=wide(text);std::vector<Atom> result;std::u16string current;bool spaced=false;
 const auto flush=[&]{if(!current.empty()){result.push_back({utf8(current),spaced});current.clear();spaced=false;}};
 for(size_t at=0;at<value.size();){const size_t begin=at;const auto cp=codepointAt(value,at);const auto unit=value.substr(begin,at-begin);
  if(cp==' '||cp=='\t'||cp=='\r'||cp=='\n'||cp==0xa0){flush();if(cp=='\n')result.push_back({{},false,true});spaced=true;continue;}
  if(cjk(cp)){flush();result.push_back({utf8(unit),false});continue;}
  if(closingPunctuation(cp)&&current.empty()&&!result.empty()&&!result.back().hardBreak){result.back().text+=utf8(unit);continue;}
  if(combining(cp)&&current.empty()&&!result.empty()&&!result.back().hardBreak){result.back().text+=utf8(unit);continue;}
  current+=unit;
 }
 flush();return result;
}
struct Slot {double left,right,baseline;};
std::vector<Slot> freeSourceSlots(const SourceContainer& c,const std::vector<SourceAnchor>& anchors,
 double baseline,double firstLeft,double size,double lastBaseline,double lastRight,bool bounded,
 const std::vector<SourceBox>& foreignGlyphInk){
 const double leading=size*1.15;std::vector<double> baselines;
 if(bounded){
  if(lastBaseline<baseline-.3)return {};
  if(lastBaseline-baseline<leading*.8)baselines.push_back(lastBaseline);
  else{
   baselines.push_back(baseline);
   for(double y=baseline+leading;y<=lastBaseline-leading*.8;y+=leading)baselines.push_back(y);
   baselines.push_back(lastBaseline);
  }
 }else for(double y=baseline;y<=c.bounds.bottom+.05;y+=leading)baselines.push_back(y);
 std::vector<Slot> result;
 for(size_t row=0;row<baselines.size();++row){const double y=baselines[row];
  std::vector<std::pair<double,double>> spans{{row==0?firstLeft:c.bounds.left,bounded&&row+1==baselines.size()?lastRight:c.bounds.right}};
  for(const auto& anchor:anchors){if(!overlap(y-size*.85,y+size*.25,anchor.bounds.top,anchor.bounds.bottom))continue;
   std::vector<std::pair<double,double>> available;
   for(const auto& span:spans){
    if(span.second<=anchor.bounds.left-1||span.first>=anchor.bounds.right+1)available.push_back(span);
    else {if(span.first<anchor.bounds.left-1)available.emplace_back(span.first,anchor.bounds.left-1);if(span.second>anchor.bounds.right+1)available.emplace_back(anchor.bounds.right+1,span.second);}
   }
   spans=std::move(available);
  }
  for(const auto& obstacle:foreignGlyphInk){if(!overlap(y-size*.85,y+size*.25,obstacle.top,obstacle.bottom))continue;
   std::vector<std::pair<double,double>> available;
   for(const auto& span:spans){
    if(span.second<=obstacle.left-1||span.first>=obstacle.right+1)available.push_back(span);
    else {if(span.first<obstacle.left-1)available.emplace_back(span.first,obstacle.left-1);if(span.second>obstacle.right+1)available.emplace_back(obstacle.right+1,span.second);}
   }
   spans=std::move(available);
  }
  for(const auto& span:spans)if(span.second>span.first+.1)result.push_back({span.first,span.second,y});
 }
 return result;
}
bool flowSourceAtoms(const std::vector<Atom>& atoms,const std::vector<Slot>& slots,double size,double scale,
 const std::function<double(const std::string&,double)>& measure,std::vector<SourceTextPlacement>& output){
 size_t at=0;
 for(const auto& slot:slots){if(at==atoms.size())return true;std::string line;
  while(at<atoms.size()){
   if(atoms[at].hardBreak){++at;break;}
   const auto trial=line+(line.empty()||!atoms[at].space?"":" ")+atoms[at].text;
   const double width=measure(trial,size)*scale;
   if(!std::isfinite(width)||width<0)throw std::invalid_argument("Invalid target font advance");
   if(width>slot.right-slot.left+.01)break;
   line=trial;++at;
  }
  if(!line.empty())output.push_back({std::move(line),slot.left,slot.baseline,size,scale});
 }
 return at==atoms.size();
}
}
SourceTextFit fitSourceChunks(const SourceContainer& c,const std::vector<std::string>& chunks,
 const std::vector<SourceAnchor>& anchors,double originalFontSize,double firstBaseline,
 const std::function<double(const std::string&,double)>& measure,
 const std::function<SourceBox(const std::string&,double)>& inkBounds,bool allowWidthScaling,
 const std::vector<SourceBox>& foreignGlyphInk){
 if(!finiteBox(c.bounds)||!std::isfinite(originalFontSize)||originalFontSize<=0||!std::isfinite(firstBaseline)||!measure||chunks.size()!=anchors.size()+1)throw std::invalid_argument("Invalid source text fitting request");
 if(!c.error.empty())return {};
 for(const auto& anchor:anchors)if(!finiteBox(anchor.bounds)||!std::isfinite(anchor.baseline))throw std::invalid_argument("Invalid source glyph anchor");
 for(const auto& obstacle:foreignGlyphInk)if(!finiteBox(obstacle))throw std::invalid_argument("Invalid foreign source glyph bounds");
 std::vector<std::vector<Atom>> parts;for(const auto& chunk:chunks)parts.push_back(sourceAtoms(chunk));
 const double minimum=std::min(originalFontSize,std::max(6.,originalFontSize*.8));
 for(double requested=originalFontSize;;requested=std::max(minimum,requested-.2)){
  for(double scale:{1.,.95,.90,.85,.80}){if(!allowWidthScaling&&scale!=1.)continue;
   const auto attempt=[&](const std::vector<SourceBox>& slotObstacles){
    SourceTextFit fit;fit.fontSize=requested;fit.widthScale=scale;bool valid=true,foreignCollision=false;
    double baseline=firstBaseline,left=c.bounds.left;
    for(size_t part=0;part<parts.size();++part){const bool bounded=part<anchors.size();
     const auto slots=freeSourceSlots(c,anchors,baseline,left,requested,bounded?anchors[part].baseline:0,bounded?anchors[part].bounds.left-1:0,bounded,slotObstacles);
     if(!flowSourceAtoms(parts[part],slots,requested,scale,measure,fit.placements)){valid=false;break;}
     if(bounded){baseline=anchors[part].baseline;left=anchors[part].bounds.right+1;}
    }
    if(valid&&inkBounds)for(const auto& p:fit.placements){const auto ink=inkBounds(p.text,p.fontSize);
     if(!finiteBox(ink))throw std::invalid_argument("Invalid target glyph ink bounds");
     const SourceBox actual{p.left+ink.left*p.widthScale,p.baseline+ink.top,p.left+ink.right*p.widthScale,p.baseline+ink.bottom};
     if(actual.left<c.bounds.left-.4||actual.right>c.bounds.right+.4||actual.top<c.bounds.top-.8||actual.bottom>c.bounds.bottom+.4){valid=false;break;}
     for(const auto& anchor:anchors)if(intersects(actual,anchor.bounds)){valid=false;break;}
     for(const auto& obstacle:foreignGlyphInk)if(intersects(actual,obstacle)){foreignCollision=true;valid=false;break;}
     if(!valid)break;
    }
    fit.fits=valid;
    return std::pair<SourceTextFit,bool>{std::move(fit),foreignCollision};
   };
   // Preserve an already-safe source placement based on measured glyph ink.
   // Coarse font boxes are useful for carving space only after they detect an
   // actual collision; they must not move a title that already fits.
   if(foreignGlyphInk.empty()||!inkBounds){auto [fit,unused]=attempt(foreignGlyphInk);(void)unused;if(fit.fits)return fit;}
   else{auto [fit,foreignCollision]=attempt({});if(fit.fits)return fit;
    if(foreignCollision){auto [carvedFit,unused]=attempt(foreignGlyphInk);(void)unused;if(carvedFit.fits)return carvedFit;}
   }
  }
  if(requested<=minimum+.001)break;
 }
 return {};
}
}
