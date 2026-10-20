#include "translation_guard.hpp"
#include <iostream>
int main(){
 struct Case{std::string source,target;bool expected;};
 const Case cases[]={
 {"Do not exceed 1.5 N.m at −11 °C.","Не превышайте 1,5 Нм при -11 °C.",true},
 {"Do not exceed 140 N.m.","Не превышайте 14 Нм.",false},
 {"Do not remove the cover.","Снимите крышку.",false},
 {"Never remove the cover.","Нельзя снимать крышку.",true},
 {"Check P0400 and B120-1.","Проверьте P0400 и B120-1.",true},
 {"Check P0400.","Проверьте P4000.",false},
 {"Read page 10 before step 2.","До шага 2 прочитайте страницу 10.",true},
 {"Keep -11 °C.","Сохраняйте 11 °C.",false},
 {"Check the cover.","Проверьте крышку.",true}};
 for(const auto& c:cases)if(translatepdf::preservesCriticalTokens(c.source,c.target)!=c.expected){std::cerr<<"Consistency guard failed: "<<c.source<<"\n";return 1;}
 struct PairCase{const char* source;const char* translated;const char* sourceLanguage;const char* targetLanguage;bool expected;};
 const PairCase pairs[]={
  {"Do not remove P0400 at 10.","Не снимайте P0400 при 10.","en","ru",true},
  {"Do not remove P0400 at 10.","Снимайте P0400 при 10.","en","ru",false},
  {"Do not remove P0400 at 10.","不要在 10 时拆除 P0400。","en","zh-Hans",true},
  {"Do not remove P0400 at 10.","Retirez P0400 à 10.","en","fr",false},
  {"Do not remove P0400 at 10.","Nicht P0400 bei 10 entfernen.","en","de",true},
  {"Do not remove P0400 at 10.","No retire P0400 a 10.","en","es",true},
  {"Do not force the cover.","Проверьте крышку без усилия.","en","ru",true},
  {"Do not force the cover.","Vérifiez le cache sans forcer.","en","fr",true},
  {"Do not force the cover.","Prüfen Sie die Abdeckung ohne Druck.","en","de",true},
  {"Do not force the cover.","Revise la cubierta sin forzar.","en","es",true},
  {"Не снимайте крышку при 10.","Do not remove the cover at 10.","ru","en",true},
  {"Проверьте панель.","Check the panel.","ru","en",true},
  {"不要拆除 P0400。","Remove P0400.","zh-Hans","en",false},
  {"不要拆除 P0400。","禁止拆除 P0400。","zh-Hans","zh-Hans",true},
  {"不要拆除 P0400。","勿拆除 P0400。","zh-Hans","zh-Hans",true},
  {"不要拆除 P0400。","不得拆除 P0400。","zh-Hans","zh-Hans",true},
  {"Ne retirez pas P0400 à 10.","No retire P0400 a 10.","fr","es",true},
  {"Vérifiez le passage.","Check the passage.","fr","en",true},
  {"Ne retirez pas le cache.","Retirez le cache.","fr","fr",false},
  {"Retirez le cache sans forcer.","Do not force the cover.","fr","en",true},
  {"Entfernen Sie P0400 nie bei 10.","No retire P0400 a 10.","de","es",true},
  {"Entfernen Sie die Abdeckung nie.","Do not remove the cover.","de","en",true},
  {"Entfernen Sie die Abdeckung nie.","Remove the cover without force.","de","en",true},
  {"No retire P0400 a 10.","Entfernen Sie P0400 bei 10.","es","de",false},
  {"No retire la cubierta.","Remove the cover without force.","es","en",true},
 };
 for(const auto& c:pairs)if(translatepdf::preservesCriticalTokens(c.source,c.translated,c.sourceLanguage,c.targetLanguage)!=c.expected){std::cerr<<"Pair consistency guard failed: "<<c.sourceLanguage<<" -> "<<c.targetLanguage<<"\n";return 1;}
 std::cout<<"9 legacy and 25 multilingual translation consistency cases passed\n";
}
