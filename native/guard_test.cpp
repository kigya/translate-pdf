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
 std::cout<<"9 translation consistency cases passed\n";
}
