#pragma once
#include <algorithm>
#include <regex>
#include <string>
#include <vector>

namespace translatepdf {
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
inline bool preservesCriticalTokens(const std::string& source,const std::string& translated) {
 if(protectedNumbers(source)!=protectedNumbers(translated))return false;
 const std::regex codes("\\b[A-Z]+[0-9]+[A-Z0-9-]*\\b");
 for(auto i=std::sregex_iterator(source.begin(),source.end(),codes);i!=std::sregex_iterator();++i)
  if(translated.find(i->str())==std::string::npos)return false;
 const std::regex negative("\\b(not|never)\\b",std::regex::icase);
 const std::regex russianNegative("(^|[[:space:][:punct:]])(не|Не|НЕ|нельзя|Нельзя|НЕЛЬЗЯ)([[:space:][:punct:]]|$)");
 if(std::regex_search(source,negative)&&!std::regex_search(translated,russianNegative))return false;
 return true;
}
}
