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

inline bool wordByte(unsigned char c) { return c >= 0x80 || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }
inline bool containsToken(const std::string& text, const std::string& token) {
 for (size_t pos = text.find(token); pos != std::string::npos; pos = text.find(token, pos + 1)) {
  const bool left = pos == 0 || !wordByte(static_cast<unsigned char>(text[pos - 1]));
  const size_t end = pos + token.size();
  const bool right = end == text.size() || !wordByte(static_cast<unsigned char>(text[end]));
  if (left && right) return true;
 }
 return false;
}
inline bool containsAnyToken(const std::string& text, const std::vector<std::string>& terms) {
 for (const auto& term : terms) if (containsToken(text, term)) return true;
 return false;
}

inline bool hasNegation(const std::string& text, const std::string& language) {
 if (language == "en") return std::regex_search(text, std::regex("\\b(not|never|no|neither|nor|without|cannot)\\b", std::regex::icase)) || std::regex_search(text, std::regex("\\b(?:don't|doesn't|didn't|can't|won't)\\b", std::regex::icase));
 if (language == "ru") return containsAnyToken(text, {"не", "Не", "НЕ", "никогда", "Никогда", "НИКОГДА", "нельзя", "Нельзя", "НЕЛЬЗЯ", "нет", "Нет", "НЕТ", "без", "Без", "БЕЗ"});
 if (language == "zh-Hans") return containsAny(text, {"不", "没", "没有", "别", "未", "无", "禁止", "勿", "不得"});
 if (language == "fr") return containsAnyToken(text, {"ne", "Ne", "NE", "pas", "Pas", "PAS", "jamais", "Jamais", "JAMAIS", "aucun", "aucune", "rien", "personne", "sans", "Sans", "SANS"}) || std::regex_search(text, std::regex("\\bn['’]", std::regex::icase));
 if (language == "de") return std::regex_search(text, std::regex("\\b(nicht|nie|kein|keine|keinen|keinem|keiner|ohne)\\b", std::regex::icase));
 if (language == "es") return std::regex_search(text, std::regex("\\b(no|nunca|ning[uú]n|ninguna|ninguno|jam[aá]s|sin)\\b", std::regex::icase));
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
