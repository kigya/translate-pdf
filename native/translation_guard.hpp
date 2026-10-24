#pragma once
#include <algorithm>
#include <cstdint>
#include <optional>
#include <regex>
#include <string>
#include <vector>

namespace translatepdf {
// Decode only the two boundaries introduced by our Gemma prompt. Literal
// source markup remains data; arbitrary XML/HTML is never interpreted here.
inline std::optional<std::string> decodeSourceBoundaryOutput(const std::string& source,const std::string& output,bool protocol){
 if(output.find_first_not_of(" \t\r\n")==std::string::npos)return std::nullopt;
 const std::string opening="<source_segment>",closing="</source_segment>";
 const auto count=[](const std::string& value,const std::string& token){size_t result=0;for(size_t at=0;(at=value.find(token,at))!=std::string::npos;at+=token.size())++result;return result;};
 if(source.find(opening)!=std::string::npos||source.find(closing)!=std::string::npos){
  if(count(source,opening)!=count(output,opening)||count(source,closing)!=count(output,closing))return std::nullopt;
  return output;
 }
 if(output.find(opening)==std::string::npos&&output.find(closing)==std::string::npos)return output;
 if(!protocol)return std::nullopt;
 std::string value=output;
 const auto trim=[](std::string& text){const auto first=text.find_first_not_of(" \t\r\n");if(first==std::string::npos){text.clear();return;}const auto last=text.find_last_not_of(" \t\r\n");text=text.substr(first,last-first+1);};
 trim(value);
 if(value.compare(0,opening.size(),opening)==0)value.erase(0,opening.size());
 if(value.size()>=closing.size()&&value.compare(value.size()-closing.size(),closing.size(),closing)==0)value.resize(value.size()-closing.size());
 trim(value);
 if(value.empty()||value.find(opening)!=std::string::npos||value.find(closing)!=std::string::npos)return std::nullopt;
 return value;
}
// Conservative consistency check, not semantic translation validation. When a
// critical token changes the caller retains the source and reports partial.
inline std::vector<std::string> protectedNumbers(std::string value) {
 const std::string minus="−";
 for(auto pos=value.find(minus);pos!=std::string::npos;pos=value.find(minus,pos+1))value.replace(pos,minus.size(),"-");
 std::vector<std::string> values;
 const std::regex pattern("-?[0-9]+(?:[.,][0-9]+)?");
 for(auto i=std::sregex_iterator(value.begin(),value.end(),pattern);i!=std::sregex_iterator();++i){
  auto token=i->str();std::replace(token.begin(),token.end(),',','.');values.push_back(token);
 }
 std::sort(values.begin(),values.end());return values;
}
inline bool preservesNumbersAndCodes(const std::string& source,const std::string& translated) {
 if(protectedNumbers(source)!=protectedNumbers(translated))return false;
 const std::regex codes("\\b[A-Z]+[0-9]+[A-Z0-9-]*\\b");
 for(auto i=std::sregex_iterator(source.begin(),source.end(),codes);i!=std::sregex_iterator();++i)
  if(translated.find(i->str())==std::string::npos)return false;
 return true;
}
inline bool containsAny(const std::string& text, const std::vector<std::string>& terms) {
 for (const auto& term : terms) if (text.find(term) != std::string::npos) return true;
 return false;
}

inline bool wordByte(unsigned char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }
struct Utf8Point { uint32_t value; size_t length; bool valid; };
inline Utf8Point utf8Point(const std::string& text,size_t pos) {
 if(pos>=text.size())return {0,0,false};
 const auto first=static_cast<unsigned char>(text[pos]);
 if(first<0x80)return {first,1,true};
 size_t length=first>=0xc2&&first<=0xdf?2:first>=0xe0&&first<=0xef?3:first>=0xf0&&first<=0xf4?4:1;
 if(length==1||pos+length>text.size())return {first,1,false};
 uint32_t value=first&((1U<<(7-length))-1U);
 for(size_t i=1;i<length;++i){const auto byte=static_cast<unsigned char>(text[pos+i]);if((byte&0xc0)!=0x80)return {first,1,false};value=(value<<6)|(byte&0x3f);}
 if((length==3&&value<0x800)||(length==4&&value<0x10000)||(value>=0xd800&&value<=0xdfff)||value>0x10ffff)return {first,1,false};
 return {value,length,true};
}
inline bool unicodeWordPoint(uint32_t cp) {
 if(cp<0x80)return wordByte(static_cast<unsigned char>(cp));
 // Explicit Unicode separators common in translated prose: spaces, dashes,
 // quotation marks, ellipsis, and CJK quote/comma/full-stop marks.
 if(cp==0x00a0||cp==0x00ab||cp==0x00bb||cp==0x00b7||
    (cp>=0x2000&&cp<=0x200a)||cp==0x202f||cp==0x205f||cp==0x3000||
    (cp>=0x2010&&cp<=0x2015)||(cp>=0x2018&&cp<=0x201f)||cp==0x2026||
    cp==0x3001||cp==0x3002||(cp>=0x3008&&cp<=0x301f)||cp==0x3030)return false;
 // Unlisted Unicode characters remain word-like; a new script or format
 // character cannot split a word and accidentally erase a protected token.
 return true;
}
inline bool unicodeWordBefore(const std::string& text,size_t pos) {
 if(pos==0)return false;
 size_t start=pos-1;while(start>0&&(static_cast<unsigned char>(text[start])&0xc0)==0x80)--start;
 const auto point=utf8Point(text,start);
 if(!point.valid||start+point.length!=pos)return true;
 return unicodeWordPoint(point.value);
}
inline bool unicodeWordAfter(const std::string& text,size_t pos) {
 if(pos>=text.size())return false;
 const auto point=utf8Point(text,pos);
 return !point.valid||unicodeWordPoint(point.value);
}
inline bool containsToken(const std::string& text, const std::string& token) {
 for (size_t pos = text.find(token); pos != std::string::npos; pos = text.find(token, pos + 1)) {
  const size_t end = pos + token.size();
  const bool left = !unicodeWordBefore(text,pos);
  const bool right = !unicodeWordAfter(text,end);
  if (left && right) return true;
 }
 return false;
}
inline bool containsAnyToken(const std::string& text, const std::vector<std::string>& terms) {
 for (const auto& term : terms) if (containsToken(text, term)) return true;
 return false;
}
inline std::string asciiLower(std::string text) {
 for(auto& c:text)if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');
 return text;
}
inline bool precededByPartLabel(const std::string& lower,size_t pos) {
 size_t end=pos;while(end>0&&(lower[end-1]==' '||lower[end-1]=='\t'))--end;
 size_t start=end;while(start>0&&wordByte(static_cast<unsigned char>(lower[start-1])))--start;
 return end-start==4&&lower.compare(start,4,"part")==0;
}
inline bool containsEnglishNo(const std::string& text) {
 const auto lower=asciiLower(text);
 for(size_t pos=lower.find("no");pos!=std::string::npos;pos=lower.find("no",pos+1)){
  if((pos>0&&unicodeWordBefore(lower,pos))||unicodeWordAfter(lower,pos+2))continue;
  size_t next=pos+2;
  if(next<lower.size()&&lower[next]=='.'){
   ++next;while(next<lower.size()&&(lower[next]==' '||lower[next]=='\t'))++next;
   if(next<lower.size()&&lower[next]>='0'&&lower[next]<='9'&&precededByPartLabel(lower,pos))continue;
  }
  return true;
 }
 return false;
}
inline bool containsFrenchNegationContraction(const std::string& text) {
 for(size_t pos=text.find_first_of("nN");pos!=std::string::npos;pos=text.find_first_of("nN",pos+1)){
  if(pos>0&&unicodeWordBefore(text,pos))continue;
  size_t next=pos+1;
  if(next<text.size()&&text[next]=='\'')return true;
  if(next+2<text.size()&&static_cast<unsigned char>(text[next])==0xe2&&static_cast<unsigned char>(text[next+1])==0x80&&static_cast<unsigned char>(text[next+2])==0x99)return true;
 }
 return false;
}

inline bool hasNegation(const std::string& text, const std::string& language) {
 if (language == "en") return containsEnglishNo(text)||containsAnyToken(asciiLower(text), {"not", "never", "neither", "nor", "without", "cannot", "don't", "doesn't", "didn't", "can't", "won't"});
 if (language == "ru") return containsAnyToken(text, {"не", "Не", "НЕ", "никогда", "Никогда", "НИКОГДА", "нельзя", "Нельзя", "НЕЛЬЗЯ", "нет", "Нет", "НЕТ", "без", "Без", "БЕЗ"});
 if (language == "zh-Hans") return containsAny(text, {"不", "没", "没有", "别", "未", "无", "禁止", "勿", "不得"});
 if (language == "fr") return containsAnyToken(asciiLower(text), {"ne", "pas", "jamais", "aucun", "aucune", "rien", "personne", "sans"}) || containsFrenchNegationContraction(text);
 if (language == "de") return containsAnyToken(asciiLower(text), {"nicht", "nie", "kein", "keine", "keinen", "keinem", "keiner", "ohne"});
 if (language == "es") return containsAnyToken(asciiLower(text), {"no", "nunca", "ningún", "ninguna", "ninguno", "jamás", "sin"});
 return false;
}

// This only catches obvious loss of a negating token; it does not establish
// translation meaning or grammatical correctness.
inline bool preservesCriticalTokens(const std::string& source, const std::string& translated,
                                    const std::string& sourceLanguage, const std::string& targetLanguage) {
 if (!preservesNumbersAndCodes(source, translated)) return false;
 return !hasNegation(source, sourceLanguage) || hasNegation(translated, targetLanguage);
}
inline bool preservesCriticalTokens(const std::string& source,const std::string& translated) {
 return preservesCriticalTokens(source,translated,"en","ru");
}
}
