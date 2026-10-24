// Link the production DOCX parser against controlled ZIP boundary
// failures. Real ZIP parsing/allocations run except at the selected failing call.
#include "miniz.h"
#include "miniz_zip.h"
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string>
#include <stdexcept>
#include <nlohmann/json.hpp>
#include "docx.hpp"

namespace translatepdf::docxops {
extern decltype(&::mz_zip_reader_file_stat) fileStat;
extern decltype(&::mz_zip_reader_get_filename) filename;
extern decltype(&::mz_zip_reader_extract_to_heap) extractToHeap;
extern decltype(&::mz_free) freeMemory;
}

enum class Failure { None, Stat, Filename, Extract, ExtractLength };
static Failure failure = Failure::None;
static void* outstanding = nullptr;
static mz_bool checkedStat(mz_zip_archive* zip, mz_uint index, mz_zip_archive_file_stat* stat) {
  return failure == Failure::Stat ? MZ_FALSE : mz_zip_reader_file_stat(zip, index, stat);
}
static mz_uint checkedFilename(mz_zip_archive* zip, mz_uint index, char* name, mz_uint size) {
  return failure == Failure::Filename ? 0 : mz_zip_reader_get_filename(zip, index, name, size);
}
static void* checkedExtract(mz_zip_archive* zip, mz_uint index, size_t* size, mz_uint flags) {
  if (failure == Failure::Extract) return nullptr;
  void* result = mz_zip_reader_extract_to_heap(zip, index, size, flags);
  if (failure == Failure::ExtractLength && result) { --*size; outstanding = result; }
  return result;
}
static void checkedFree(void* memory) {
  if (outstanding == memory) outstanding = nullptr;
  mz_free(memory);
}
static void check(bool okay, const std::string& message) { if (!okay) throw std::runtime_error(message); }
int main() {
  namespace fs = std::filesystem;
  const auto directory = fs::temp_directory_path()/"folivela-docx-faults";
  fs::create_directories(directory);
  const auto source = directory/"source.docx";
  mz_zip_archive zip{};
  check(mz_zip_writer_init_file(&zip,source.string().c_str(),0),"fixture open");
  const std::pair<const char*,std::string> parts[] = {
    {"[Content_Types].xml", "<Types><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"},
    {"_rels/.rels", "<Relationships><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"},
    {"word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Retain source</w:t></w:r></w:p></w:body></w:document>"},
  };
  for (const auto& part : parts) check(mz_zip_writer_add_mem(&zip,part.first,part.second.data(),part.second.size(),MZ_DEFAULT_COMPRESSION),"fixture member");
  check(mz_zip_writer_finalize_archive(&zip),"fixture finalize");mz_zip_writer_end(&zip);
  try {
    translatepdf::docxops::fileStat = checkedStat;
    translatepdf::docxops::filename = checkedFilename;
    translatepdf::docxops::extractToHeap = checkedExtract;
    translatepdf::docxops::freeMemory = checkedFree;
    for (const auto& entry : {std::pair{Failure::Stat,"Malformed DOCX ZIP directory"},
                             std::pair{Failure::Filename,"ZIP path exceeds"},
                             std::pair{Failure::Extract,"integrity validation"},
                             std::pair{Failure::ExtractLength,"integrity validation"}}) {
      failure=entry.first;
      const auto result=nlohmann::json::parse(translatepdf::inspectDocx(source.string()));
      check(result.contains("error") && result["error"].get<std::string>().find(entry.second)!=std::string::npos,"ZIP boundary failure was not rejected");
      check(outstanding==nullptr,"rejected extraction buffer was not freed");
    }
    failure=Failure::None;
    translatepdf::docxops::fileStat = mz_zip_reader_file_stat;
    translatepdf::docxops::filename = mz_zip_reader_get_filename;
    translatepdf::docxops::extractToHeap = mz_zip_reader_extract_to_heap;
    translatepdf::docxops::freeMemory = mz_free;
    check(!nlohmann::json::parse(translatepdf::inspectDocx(source.string())).contains("error"),"same original does not reopen after failure");
    fs::remove_all(directory);
    std::cout<<"DOCX ZIP boundary failure cleanup passed\n";
    return 0;
  } catch (const std::exception& e) { fs::remove_all(directory);std::cerr<<e.what()<<'\n';return 1; }
}
