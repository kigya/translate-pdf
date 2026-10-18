#include "core.hpp"
#include <iostream>
#include <nlohmann/json.hpp>
#include <fstream>
#include <thread>
#include <chrono>
int main(int argc,char**argv){
 if(argc<3)return 2;std::string op=argv[1];
 if(op=="inspect")std::cout<<translatepdf::inspect(argv[2]);
 else if(op=="page")std::cout<<translatepdf::readPage(argv[2],argc>3?std::stoi(argv[3]):0);
 else if(op=="translate"){std::cerr<<translatepdf::loadModel(argv[2],2048,4)<<"\n";std::cout<<translatepdf::translate(argc>3?argv[3]:"Do not remove the head restraint.","head restraint translates to подголовник","");translatepdf::releaseModel();}
 else if(op=="cancel-translate"){
  std::cerr<<translatepdf::loadModel(argv[2],2048,4)<<"\n";
  std::thread cancellation([]{std::this_thread::sleep_for(std::chrono::milliseconds(100));translatepdf::cancel();});
  std::string text;for(int i=0;i<20;i++)text+="Read the entire document before performing the described maintenance procedure. ";
  std::cout<<translatepdf::translate(text,"","");cancellation.join();translatepdf::releaseModel();
 }
 else if(op=="export"&&argc>6)std::cout<<translatepdf::exportPage(argv[2],std::stoi(argv[3]),argv[4],argv[5],argv[6]);
 else if(op=="merge"&&argc>3)std::cout<<translatepdf::mergePdf(argv[2],argv[3]);
 else if(op=="render"){try{auto raster=translatepdf::render(argv[2],argc>3?std::stoi(argv[3]):0,argc>4?std::stoi(argv[4]):200);std::cout<<nlohmann::json{{"width",raster.width},{"height",raster.height},{"bytes",raster.rgba.size()}};}catch(const std::exception&error){std::cout<<nlohmann::json{{"error",error.what()}};}}
 else if(op=="bench"&&argc>3){
  using json=nlohmann::json;auto load=json::parse(translatepdf::loadModel(argv[2],2048,4));if(load.contains("error")){std::cout<<load;return 1;}
  json corpus;std::ifstream(argv[3])>>corpus;
  for(const auto& sample:corpus.at("cases")){auto out=json::parse(translatepdf::translate(sample.at("source"),"",""));out["id"]=sample.at("id");std::cout<<out.dump()<<std::endl;}
  translatepdf::releaseModel();
 }
 else if(op=="pipeline"&&argc>6){
  using json=nlohmann::json;auto load=json::parse(translatepdf::loadModel(argv[2],2048,4));if(load.contains("error")){std::cout<<load;return 1;}
  auto page=json::parse(translatepdf::readPage(argv[3],std::stoi(argv[4])));if(page.contains("error")){std::cout<<page;return 1;}
  for(auto&block:page["blocks"]){auto out=json::parse(translatepdf::translate(block["text"],"head restraint translates to подголовник\nwheel nuts translates to колёсные гайки\ndipstick translates to масляный щуп", "Chevrolet Equinox owner manual"));if(out.contains("error")){block["status"]="partial";block["error"]=out["error"];block["translatedText"]=nullptr;}else{block["translatedText"]=out["text"];block["status"]="translated";}std::cerr<<block["id"]<<"\n";}
  std::ofstream(std::string(argv[5])+".blocks.json")<<page.dump(2);
  std::cout<<translatepdf::exportPage(argv[3],std::stoi(argv[4]),page["blocks"].dump(),argv[5],argv[6]);translatepdf::releaseModel();
 }
 else return 2;std::cout<<"\n";
}
