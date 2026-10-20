#include "core.hpp"
#include <iostream>
#include <sstream>
#include <nlohmann/json.hpp>
#include <fstream>
#include <thread>
#include <chrono>
int main(int argc,char**argv){
 if(argc<3)return 2;std::string op=argv[1];
 if(op=="inspect")std::cout<<translatepdf::inspect(argv[2]);
 else if(op=="model-configuration"){
  auto first=nlohmann::json::parse(translatepdf::loadModel(argv[2],1024,1));
  auto second=nlohmann::json::parse(translatepdf::loadModel(argv[2],2048,2));
  auto cached=nlohmann::json::parse(translatepdf::loadModel(argv[2],2048,2));
  std::cout<<nlohmann::json{{"first",first},{"second",second},{"cached",cached}};translatepdf::releaseModel();
 }
 else if(op=="page")std::cout<<translatepdf::readPage(argv[2],argc>3?std::stoi(argv[3]):0);
 // Usage: translate-profile MODEL PROFILE SOURCE TARGET TEXT [GLOSSARY]
 else if(op=="translate-profile"&&argc>6){
  auto loaded=nlohmann::json::parse(translatepdf::loadModel(argv[2],2048,2,argv[3]));
  if(loaded.contains("error")){std::cout<<loaded<<"\n";return 1;}
  std::cout<<translatepdf::translate(argv[6],argc>7?argv[7]:"","",argv[4],argv[5]);translatepdf::releaseModel();
 }
 // One model load per profile; input is a JSON array or JSONL file, or '-' for stdin.
 // Each case has sourceLanguage, targetLanguage, text, and optional id/glossary/context.
 else if(op=="translate-batch"&&argc>4){
  using json=nlohmann::json;auto loaded=json::parse(translatepdf::loadModel(argv[2],2048,4,argv[3]));
  if(loaded.contains("error")){std::cout<<loaded<<"\n";return 1;}
  std::string input;if(std::string(argv[4])=="-")input.assign(std::istreambuf_iterator<char>(std::cin),{});else{std::ifstream file(argv[4]);if(!file){translatepdf::releaseModel();std::cout<<json{{"error","Cannot read batch input"}}<<"\n";return 1;}input.assign(std::istreambuf_iterator<char>(file),{});}
  json cases;try{cases=json::parse(input);if(cases.is_object())cases=json::array({cases});}catch(...){cases=json::array();std::istringstream lines(input);std::string line;while(std::getline(lines,line))if(!line.empty())cases.push_back(json::parse(line));}
  if(!cases.is_array()){translatepdf::releaseModel();std::cout<<json{{"error","Batch input must be a JSON array or JSONL"}}<<"\n";return 1;}
  for(const auto& item:cases){json out;try{out=json::parse(translatepdf::translate(item.at("text"),item.value("glossary",std::string{}),item.value("context",std::string{}),item.at("sourceLanguage"),item.at("targetLanguage")));}catch(const std::exception& error){out=json{{"error",error.what()}};}json result={{"sourceLanguage",item.value("sourceLanguage",std::string{})},{"targetLanguage",item.value("targetLanguage",std::string{})},{"result",out}};if(item.contains("id"))result["id"]=item["id"];std::cout<<result.dump()<<std::endl;}
  translatepdf::releaseModel();
 }
 else if(op=="translate"){std::cerr<<translatepdf::loadModel(argv[2],2048,4)<<"\n";std::cout<<translatepdf::translate(argc>3?argv[3]:"Do not remove the head restraint.","head restraint translates to подголовник","");translatepdf::releaseModel();}
 else if(op=="cancel-translate"){
  std::cerr<<translatepdf::loadModel(argv[2],2048,4)<<"\n";
  std::thread cancellation([]{std::this_thread::sleep_for(std::chrono::milliseconds(100));translatepdf::cancel();});
  std::string text;for(int i=0;i<20;i++)text+="Read the entire document before performing the described maintenance procedure. ";
  std::cout<<translatepdf::translate(text,"","");cancellation.join();translatepdf::releaseModel();
 }
 // Usage: export INPUT PAGE BLOCKS_JSON OUTPUT LATIN_FONT [CJK_FONT]
 else if(op=="export"&&argc>6)std::cout<<translatepdf::exportPage(argv[2],std::stoi(argv[3]),argv[4],argv[5],argv[6],argc>7?argv[7]:"");
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
