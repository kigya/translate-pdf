#include "docx.hpp"

#include "miniz.h"
#include "miniz_zip.h"
#include "pugixml.hpp"
#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace translatepdf {
// Internal dependency boundary, shared by production and host fault tests.
namespace docxops {
decltype(&::mz_zip_reader_file_stat) fileStat = ::mz_zip_reader_file_stat;
decltype(&::mz_zip_reader_get_filename) filename = ::mz_zip_reader_get_filename;
decltype(&::mz_zip_reader_extract_to_heap) extractToHeap = ::mz_zip_reader_extract_to_heap;
decltype(&::mz_free) freeMemory = ::mz_free;
}
namespace {
using json = nlohmann::json;
namespace fs = std::filesystem;
constexpr uint64_t kMaxArchive = 128ull * 1024 * 1024;
constexpr uint64_t kMaxExpanded = 256ull * 1024 * 1024;
constexpr uint64_t kMaxXml = 8ull * 1024 * 1024;
constexpr size_t kMaxEntries = 4096;
constexpr size_t kMaxSegments = 100000;
constexpr size_t kChunkBytes = 1800;
constexpr std::string_view kWordNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

std::string failJson(std::string message) {
  return json{{"error", std::move(message)}}.dump();
}
void ensureDocxExtension(const std::string& path) {
  auto ext=fs::path(path).extension().string();
  std::transform(ext.begin(),ext.end(),ext.begin(),[](unsigned char c){return static_cast<char>(std::tolower(c));});
  if(ext==".doc")throw std::runtime_error("Legacy .doc files are not supported; save as .docx");
  if(ext==".docm")throw std::runtime_error("Macro-enabled Word documents are not supported; use .docx");
  if(!ext.empty()&&ext!=".docx")throw std::runtime_error("Only .docx Word documents are supported");
}

struct ZipReader {
  mz_zip_archive z{};
  explicit ZipReader(const std::string& path) {
    std::error_code ec;
    auto size = fs::file_size(path, ec);
    if (ec || size < 22 || size > kMaxArchive) throw std::runtime_error("DOCX archive is missing, empty, or too large");
    if (!mz_zip_reader_init_file(&z, path.c_str(), 0)) throw std::runtime_error("Invalid or encrypted ZIP package");
    if (mz_zip_reader_get_num_files(&z) > kMaxEntries) { mz_zip_reader_end(&z); throw std::runtime_error("DOCX contains too many ZIP entries"); }
  }
  ~ZipReader() { mz_zip_reader_end(&z); }
};
struct ZipWriter {
  mz_zip_archive z{};
  explicit ZipWriter(const std::string& path) {
    if (!mz_zip_writer_init_file(&z, path.c_str(), 0)) throw std::runtime_error("Cannot create translated DOCX package");
  }
  ~ZipWriter() { mz_zip_writer_end(&z); }
  void finish() {
    if (!mz_zip_writer_finalize_archive(&z)) throw std::runtime_error("Cannot finalize translated DOCX package");
  }
};
struct Entry { mz_uint index; mz_zip_archive_file_stat stat{}; std::string name; };

bool safeEntryName(std::string_view n) {
  if (n.empty() || n.front() == '/' || n.find('\\') != n.npos || n.find('\0') != n.npos) return false;
  size_t start = 0;
  for (;;) {
    auto end = n.find('/', start);
    auto part = n.substr(start, end == n.npos ? n.size() - start : end - start);
    if (part == ".." || part == ".") return false;
    if (end == n.npos) break;
    start = end + 1;
  }
  return true;
}
std::vector<Entry> listEntries(ZipReader& r) {
  std::vector<Entry> result;
  result.reserve(mz_zip_reader_get_num_files(&r.z));
  std::set<std::string> seen;
  uint64_t expanded = 0;
  for (mz_uint i = 0; i < mz_zip_reader_get_num_files(&r.z); ++i) {
    Entry e{}; e.index = i;
    if (!docxops::fileStat(&r.z, i, &e.stat)) throw std::runtime_error("Malformed DOCX ZIP directory");
    // file_stat's display name is truncated and NUL-terminated. Validate the
    // complete central-directory name before handing the package to a renderer.
    const auto nameSize = docxops::filename(&r.z, i, nullptr, 0);
    if (nameSize == 0 || nameSize > 4097) throw std::runtime_error("DOCX ZIP path exceeds the safe size limit");
    e.name.resize(nameSize);
    docxops::filename(&r.z, i, e.name.data(), nameSize);
    e.name.resize(nameSize - 1);
    if (!safeEntryName(e.name) || !seen.insert(e.name).second) throw std::runtime_error("DOCX contains an unsafe or duplicate ZIP path");
    if (e.stat.m_is_encrypted) throw std::runtime_error("Encrypted DOCX documents are not supported");
    if (!e.stat.m_is_supported) throw std::runtime_error("DOCX uses unsupported ZIP compression or encryption");
    if (e.stat.m_uncomp_size > kMaxExpanded || expanded > kMaxExpanded - e.stat.m_uncomp_size) throw std::runtime_error("DOCX expands beyond the safe size limit");
    expanded += e.stat.m_uncomp_size;
    if (!e.stat.m_is_directory && e.stat.m_uncomp_size > 64ull * 1024 * 1024) throw std::runtime_error("DOCX contains an oversized package part");
    result.push_back(std::move(e));
  }
  return result;
}
std::string extract(ZipReader& r, const Entry& e, size_t limit) {
  if (e.stat.m_uncomp_size > limit) throw std::runtime_error("DOCX XML part exceeds the safe size limit");
  size_t got = 0;
  void* p = docxops::extractToHeap(&r.z, e.index, &got, 0);
  if (!p || got != e.stat.m_uncomp_size) { if (p) docxops::freeMemory(p); throw std::runtime_error("DOCX ZIP entry failed integrity validation"); }
  std::string s(static_cast<const char*>(p), got);
  docxops::freeMemory(p);
  return s;
}
const Entry* findEntry(const std::vector<Entry>& entries, const std::string& name) {
  auto it = std::find_if(entries.begin(), entries.end(), [&](const Entry& e) { return e.name == name; });
  return it == entries.end() ? nullptr : &*it;
}
void validateXmlUtf8(std::string_view value) {
  size_t reference=0;
  while(reference<value.size()) {
    if(value.substr(reference,9)=="<![CDATA[" || value.substr(reference,4)=="<!--") {
      const bool cdata=value.substr(reference,9)=="<![CDATA[";
      const auto end=value.find(cdata?"]]>":"-->",reference+(cdata?9:4));
      if(end==std::string_view::npos)throw std::runtime_error("DOCX contains malformed XML");
      reference=end+3;continue;
    }
    if(value.substr(reference,2)!="&#"){++reference;continue;}
    // XML CharRef permits the lowercase x prefix only (W3C XML 1.0, production 66).
    size_t position=reference+2;bool hex=position<value.size()&&value[position]=='x';if(hex)++position;
    uint32_t scalar=0;size_t digits=0;
    for(;position<value.size()&&value[position]!=';';++position){
      const char c=value[position];const int digit=c>='0'&&c<='9'?c-'0':hex&&c>='a'&&c<='f'?c-'a'+10:hex&&c>='A'&&c<='F'?c-'A'+10:-1;
      if(digit<0||scalar>0x10ffffu/(hex?16u:10u))throw std::runtime_error("DOCX contains an invalid XML character reference");
      scalar=scalar*(hex?16u:10u)+static_cast<uint32_t>(digit);++digits;
    }
    if(digits==0||position==value.size()||scalar>0x10ffff||(scalar>=0xd800&&scalar<=0xdfff)||scalar==0xfffe||scalar==0xffff||
       (scalar<0x20&&scalar!=9&&scalar!=10&&scalar!=13))throw std::runtime_error("DOCX contains an invalid XML character reference");
    reference=position+1;
  }
  for(size_t i=0;i<value.size();) {
    const auto first=static_cast<unsigned char>(value[i]);
    const size_t length=first<0x80?1:(first>=0xc2&&first<=0xdf)?2:(first>=0xe0&&first<=0xef)?3:(first>=0xf0&&first<=0xf4)?4:0;
    if(length==0||i+length>value.size())throw std::runtime_error("DOCX contains malformed UTF-8 XML");
    uint32_t scalar=first&static_cast<unsigned char>(length==1?0x7f:(1u<<(7-length))-1);
    for(size_t j=1;j<length;++j){const auto next=static_cast<unsigned char>(value[i+j]);if((next&0xc0)!=0x80)throw std::runtime_error("DOCX contains malformed UTF-8 XML");scalar=(scalar<<6)|(next&0x3f);}
    if((length==3&&scalar<0x800)||(length==4&&scalar<0x10000)||scalar>0x10ffff||
       (scalar>=0xd800&&scalar<=0xdfff)||scalar==0xfffe||scalar==0xffff||(scalar<0x20&&scalar!=9&&scalar!=10&&scalar!=13))
      throw std::runtime_error("DOCX contains an invalid XML character");
    i+=length;
  }
}
pugi::xml_document parseXml(const std::string& xml) {
  validateXmlUtf8(xml);
  if (xml.find("<!DOCTYPE") != std::string::npos || xml.find("<!doctype") != std::string::npos)
    throw std::runtime_error("DOCX XML document types are not supported");
  pugi::xml_document d;
  auto result = d.load_buffer(xml.data(), xml.size(), pugi::parse_default | pugi::parse_ws_pcdata, pugi::encoding_utf8);
  if (!result) throw std::runtime_error("DOCX contains malformed or non-UTF-8 XML");
  // Bound depth before XPath and recursive WordprocessingML traversal. pugixml's
  // parser itself is iterative; do not recurse while validating untrusted input.
  auto node=d.first_child(); size_t depth=1;
  while(node) {
    if(depth>128) throw std::runtime_error("DOCX XML nesting exceeds the safe limit");
    if(node.first_child()){node=node.first_child();++depth;continue;}
    while(node && !node.next_sibling()){node=node.parent();if(depth>0)--depth;}
    if(node)node=node.next_sibling();
  }
  return d;
}
std::string localName(pugi::xml_node n) {
  std::string_view name(n.name()); auto i = name.find(':');
  return std::string(name.substr(i == name.npos ? 0 : i + 1));
}
std::string qualified(pugi::xml_node n,const char* local) {
  std::string_view name(n.name());auto colon=name.find(':');
  return colon==name.npos?std::string(local):std::string(name.substr(0,colon+1))+local;
}
std::string breakType(pugi::xml_node n) {
  for(auto a:n.attributes()) {std::string_view name(a.name());auto colon=name.find(':');if(name.substr(colon==name.npos?0:colon+1)=="type")return a.value();}
  return {};
}
bool isNode(pugi::xml_node n, const char* local) { return n.type() == pugi::node_element && localName(n) == local; }
void walk(pugi::xml_node n, const char* local, std::vector<pugi::xml_node>& out) {
  if (isNode(n, local)) out.push_back(n);
  for (auto c = n.first_child(); c; c = c.next_sibling()) walk(c, local, out);
}
void textOf(pugi::xml_node n, std::string& out) {
  if (isNode(n, "t")) { out += n.child_value(); return; }
  if (isNode(n, "tab")) { out += '\t'; return; }
  if (isNode(n, "br") || isNode(n, "cr")) { const std::string type=breakType(n); if(type.empty()||type=="textWrapping")out += '\n'; return; }
  for (auto c = n.first_child(); c; c = c.next_sibling()) if(!isNode(c,"p")) textOf(c, out);
}
std::string paragraphText(pugi::xml_node p) { std::string s; textOf(p, s); return s; }
void paragraphTextNodes(pugi::xml_node p,std::vector<pugi::xml_node>& out){
  for(auto c=p.first_child();c;c=c.next_sibling()){
    if(isNode(c,"p"))continue;
    if(isNode(c,"t")){out.push_back(c);continue;}
    paragraphTextNodes(c,out);
  }
}
std::vector<std::string> xmlParts(const std::vector<Entry>& entries) {
  std::vector<std::string> result;
  for (const auto& e : entries) {
    if (e.name == "word/document.xml" || e.name == "word/footnotes.xml" || e.name == "word/endnotes.xml" ||
        (e.name.rfind("word/header", 0) == 0 && e.name.size() > 11 && e.name.substr(e.name.size()-4) == ".xml") ||
        (e.name.rfind("word/footer", 0) == 0 && e.name.size() > 11 && e.name.substr(e.name.size()-4) == ".xml")) result.push_back(e.name);
  }
  std::stable_sort(result.begin(), result.end(), [](const auto& a, const auto& b) {
    auto rank=[](const std::string& s){ if(s=="word/document.xml") return 0; if(s.rfind("word/header",0)==0)return 1;if(s.rfind("word/footer",0)==0)return 2;if(s=="word/footnotes.xml")return 3;return 4; };
    return rank(a)==rank(b) ? a<b : rank(a)<rank(b);
  });
  return result;
}
std::vector<std::string> splitUtf8(std::string_view text) {
  std::vector<std::string> out;
  if (text.empty()) { out.emplace_back(); return out; }
  size_t pos = 0;
  while (pos < text.size()) {
    size_t end = std::min(text.size(), pos + kChunkBytes);
    while (end < text.size() && (static_cast<unsigned char>(text[end]) & 0xc0) == 0x80) --end;
    if (end < text.size()) {
      size_t preferred = text.rfind(' ', end - 1);
      size_t tab = text.rfind('\t', end - 1); if (tab != std::string_view::npos && (preferred == std::string_view::npos || tab > preferred)) preferred = tab;
      size_t nl = text.rfind('\n', end - 1); if (nl != std::string_view::npos && (preferred == std::string_view::npos || nl > preferred)) preferred = nl;
      if (preferred != std::string_view::npos && preferred > pos + kChunkBytes / 2) end = preferred + 1;
      auto decode=[&](size_t at)->uint32_t {
        auto c=static_cast<unsigned char>(text[at]);
        if(c<0x80)return c;
        size_t n=(c&0xe0)==0xc0?2:(c&0xf0)==0xe0?3:4;
        uint32_t cp=c&((1u<<(7-n))-1u);
        for(size_t i=1;i<n;i++)cp=(cp<<6)|(static_cast<unsigned char>(text[at+i])&0x3f);
        return cp;
      };
      auto previousStart=[&](size_t at){size_t s=at-1;while(s>pos&&(static_cast<unsigned char>(text[s])&0xc0)==0x80)--s;return s;};
      auto extend=[](uint32_t cp){return (cp>=0x0300&&cp<=0x036f)||(cp>=0x1ab0&&cp<=0x1aff)||(cp>=0x1dc0&&cp<=0x1dff)||(cp>=0x20d0&&cp<=0x20ff)||(cp>=0xfe00&&cp<=0xfe0f)||(cp>=0xfe20&&cp<=0xfe2f)||(cp>=0x1f3fb&&cp<=0x1f3ff)||(cp>=0xe0100&&cp<=0xe01ef);};
      auto regional=[](uint32_t cp){return cp>=0x1f1e6&&cp<=0x1f1ff;};
      while(end>pos){
        const size_t prev=previousStart(end),next=end;
        const uint32_t before=decode(prev),after=decode(next);
        bool unsafe=extend(after)||after==0x200d||before==0x200d;
        if(regional(before)&&regional(after)){
          size_t count=0,at=end;
          while(at>pos){size_t s=previousStart(at);if(!regional(decode(s)))break;count++;at=s;}
          unsafe=(count%2)==1;
        }
        if(!unsafe)break;
        end=prev;
      }
    }
    if (end <= pos) throw std::runtime_error("Cannot safely split DOCX paragraph text");
    out.emplace_back(text.substr(pos, end - pos)); pos = end;
  }
  return out;
}
struct Segment { std::string id, part, text; int paragraph = 0, chunk = 0; };
std::vector<Segment> segmentsFor(const std::string& path, ZipReader& r, const std::vector<Entry>& entries,
                                 std::unordered_map<std::string, std::string>* xmlOut = nullptr) {
  ensureDocxExtension(path);
  auto content = findEntry(entries, "[Content_Types].xml");
  auto rels = findEntry(entries, "_rels/.rels");
  auto doc = findEntry(entries, "word/document.xml");
  if (!content || !rels || !doc) throw std::runtime_error("File is not a valid Word DOCX package");
  // Preview also reads styles, themes and other XML parts. Apply the same
  // expansion/depth/DTD bounds before handing any package to a WebView parser.
  for(const auto& entry:entries) {
    const auto suffix=fs::path(entry.name).extension().string();
    if(!entry.stat.m_is_directory && (suffix==".xml" || suffix==".rels"))
      (void)parseXml(extract(r,entry,kMaxXml));
  }
  const auto contentXml = extract(r, *content, kMaxXml);
  const auto contentDoc = parseXml(contentXml);
  bool mainType = false;
  for (auto n : contentDoc.select_nodes("//*[local-name()='Override']")) {
    auto x = n.node();
    if (std::string(x.attribute("PartName").value()) == "/word/document.xml" &&
        std::string(x.attribute("ContentType").value()) == "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml") mainType = true;
    auto type = std::string(x.attribute("ContentType").value());
    if (type.find("macroEnabled") != std::string::npos) throw std::runtime_error("Macro-enabled Word documents are not supported; use .docx");
  }
  for (auto n : contentDoc.select_nodes("//*[local-name()='Default']")) {
    if (std::string(n.node().attribute("Extension").value()) == "docm") throw std::runtime_error("Macro-enabled Word documents are not supported; use .docx");
  }
  if (!mainType) throw std::runtime_error("File does not declare a valid Word DOCX document part");
  const auto rootRelXml = extract(r, *rels, kMaxXml);
  const auto rootRelDoc = parseXml(rootRelXml);
  bool officeDoc = false;
  for (auto n : rootRelDoc.select_nodes("//*[local-name()='Relationship']")) {
    auto x=n.node();
    if (std::string(x.attribute("Type").value()).find("/officeDocument") != std::string::npos && std::string(x.attribute("Target").value()) == "word/document.xml") officeDoc = true;
  }
  if (!officeDoc) throw std::runtime_error("DOCX package has no valid main document relationship");
  for (const auto& e : entries) if (e.name == "word/vbaProject.bin" || e.name.find("vbaProject") != std::string::npos) throw std::runtime_error("Macro-enabled Word documents are not supported; use .docx");

  std::vector<Segment> all;
  for (const auto& part : xmlParts(entries)) {
    // xmlParts only selects names from this same immutable entry list.
    const auto* e = findEntry(entries, part);
    auto xml = extract(r, *e, kMaxXml);
    auto parsed = parseXml(xml);
    if (part == "word/document.xml") {
      const auto root = parsed.document_element();
      const std::string rootName = root.name();
      const auto colon = rootName.find(':');
      const auto declaration = colon == std::string::npos ? std::string("xmlns") : "xmlns:" + rootName.substr(0, colon);
      if (localName(root) != "document" || std::string(root.attribute(declaration.c_str()).value()) != kWordNs)
        throw std::runtime_error("DOCX main document uses an unsupported XML namespace");
    }
    std::vector<pugi::xml_node> paragraphs; walk(parsed, "p", paragraphs);
    for (size_t pi = 0; pi < paragraphs.size(); ++pi) {
      auto pieces = splitUtf8(paragraphText(paragraphs[pi]));
      for (size_t ci = 0; ci < pieces.size(); ++ci) {
        if (all.size() >= kMaxSegments) throw std::runtime_error("DOCX has too many text segments");
        std::string id = "docx:" + part + "#p:" + std::to_string(pi) + "#chunk:" + std::to_string(ci);
        all.push_back({std::move(id), part, std::move(pieces[ci]), static_cast<int>(pi), static_cast<int>(ci)});
      }
    }
    if (xmlOut) (*xmlOut)[part] = std::move(xml);
  }
  if (all.empty()) throw std::runtime_error("DOCX contains no readable paragraphs");
  return all;
}
json warningsFor(const std::vector<Entry>& entries, ZipReader& r, const std::vector<std::string>& parts) {
  json warnings = json::array();
  for (const auto& e : entries) {
    if (e.name.size() >= 5 && e.name.compare(e.name.size()-5, 5, ".rels") == 0) {
      auto d = parseXml(extract(r,e,kMaxXml));
        for (auto n : d.select_nodes("//*[local-name()='Relationship']")) {
          std::string mode=n.node().attribute("TargetMode").value();
          std::transform(mode.begin(),mode.end(),mode.begin(),[](unsigned char c){return static_cast<char>(std::tolower(c));});
          if(mode=="external"){warnings.push_back("External relationship preserved without fetching: " + e.name);break;}
        }
    }
  }
  for (const auto& e : entries) {
    if ((e.name.find("altChunk") != std::string::npos || e.name.find("embeddings/") != std::string::npos) && !e.stat.m_is_directory)
      warnings.push_back("Unsupported embedded Word content preserved unchanged");
  }
  (void)parts;
  return warnings;
}
void setParagraphText(pugi::xml_node p, const std::string& value) {
  std::vector<pugi::xml_node> textNodes, delimiters;
  paragraphTextNodes(p,textNodes);
  const auto collect=[&](auto&& self,pugi::xml_node node)->void {
    for(auto c=node.first_child();c;c=c.next_sibling()) {
      if(isNode(c,"p"))continue;
      if(isNode(c,"tab")||isNode(c,"br")||isNode(c,"cr"))delimiters.push_back(c);
      else self(self,c);
    }
  };
  collect(collect,p);
  // Page/column breaks are layout instructions, not translatable line breaks.
  for(auto n:delimiters) {
    const std::string type=breakType(n);
    if(type.empty()||type=="textWrapping")n.parent().remove_child(n);
  }
  pugi::xml_node first;
  if(textNodes.empty())first=p.append_child(qualified(p,"r").c_str()).append_child(qualified(p,"t").c_str());
  else {first=textNodes.front();for(size_t i=1;i<textNodes.size();++i)textNodes[i].text().set("");}
  auto parent=first.parent();auto anchor=first;
  size_t start=0;bool initial=true;
  for(size_t i=0;i<=value.size();++i){
    if(i<value.size()&&value[i]!='\t'&&value[i]!='\n'&&value[i]!='\r')continue;
    auto t=initial?first:parent.insert_child_after(qualified(p,"t").c_str(),anchor);initial=false;
    t.text().set(value.substr(start,i-start).c_str());
    auto space=t.attribute("xml:space");if(!space)space=t.append_attribute("xml:space");space="preserve";
    anchor=t;
    if(i<value.size()){
      anchor=parent.insert_child_after(qualified(p,value[i]=='\t'?"tab":"br").c_str(),anchor);
      if(value[i]=='\r'&&i+1<value.size()&&value[i+1]=='\n')++i;
    }
    start=i+1;
  }
}
// Close the complete ZIP before its atomic destination rename.
void writePackage(const std::string& temp, ZipReader& r, const std::vector<Entry>& entries,
                  const std::unordered_map<std::string,std::string>& originalXml) {
  ZipWriter w(temp);
  for (const auto& e : entries) {
    if (e.stat.m_is_directory) {
      if (!mz_zip_writer_add_mem(&w.z,e.name.c_str(),nullptr,0,MZ_DEFAULT_COMPRESSION)) throw std::runtime_error("Cannot preserve DOCX package directory");
      continue;
    }
    auto changed=originalXml.find(e.name);
    if (changed != originalXml.end()) {
      if (!mz_zip_writer_add_mem(&w.z,e.name.c_str(),changed->second.data(),changed->second.size(),MZ_DEFAULT_COMPRESSION)) throw std::runtime_error("Cannot write translated Word text");
    } else {
      auto bytes=extract(r,e,64ull*1024*1024);
      if (!mz_zip_writer_add_mem(&w.z,e.name.c_str(),bytes.data(),bytes.size(),MZ_DEFAULT_COMPRESSION)) throw std::runtime_error("Cannot preserve DOCX package part");
    }
  }
  w.finish();
}
std::string tempPath(const std::string& dest) { return dest + ".folivela-tmp"; }
}

std::string inspectDocx(const std::string& path) {
  try {
    ensureDocxExtension(path);
    ZipReader r(path); auto entries=listEntries(r);
    auto segments=segmentsFor(path,r,entries);
    auto warnings=warningsFor(entries,r,xmlParts(entries));
    return json{{"format","docx"},{"pageCount",segments.size()},{"segmentCount",segments.size()},{"warnings",warnings}}.dump();
  } catch (const std::exception& e) { return failJson(e.what()); }
}

std::string readDocxSegment(const std::string& path, int index) {
  try {
    ensureDocxExtension(path);
    if (index < 0) throw std::runtime_error("DOCX segment index is out of range");
    ZipReader r(path); auto entries=listEntries(r); auto segments=segmentsFor(path,r,entries);
    if (static_cast<size_t>(index) >= segments.size()) throw std::runtime_error("DOCX segment index is out of range");
    const auto& s=segments[static_cast<size_t>(index)];
    json block={{"id",s.id},{"text",s.text},{"sourceText",s.text},{"left",24.0},{"top",24.0},{"right",588.0},{"bottom",44.0},{"fontSize",11.0},{"source","native"},{"objectIndex",nullptr}};
    return json{{"index",index},{"width",612},{"height",792},{"blocks",json::array({block})},{"warnings",warningsFor(entries,r,xmlParts(entries))}}.dump();
  } catch (const std::exception& e) { return failJson(e.what()); }
}

std::string exportDocx(const std::string& source, const std::string& segmentsJson, const std::string& destination) {
  try {
    ensureDocxExtension(source);
    ZipReader r(source); auto entries=listEntries(r);
    std::unordered_map<std::string,std::string> originalXml;
    auto segments=segmentsFor(source,r,entries,&originalXml);
    json input=json::parse(segmentsJson);
    if (!input.is_array()) throw std::runtime_error("DOCX translation segments must be a JSON array");
    std::unordered_map<std::string,std::pair<std::string,std::string>> translations;
    auto acceptBlock=[&](const json& item){
      if (!item.is_object() || !item.contains("id") || !item["id"].is_string()) return;
      const auto id=item["id"].get<std::string>();
      if (!item.contains("translatedText") || !item["translatedText"].is_string()) return;
      auto status=item.value("status", std::string("translated"));
      translations[id]={item["translatedText"].get<std::string>(),status};
    };
    for (const auto& item : input) {
      if(item.is_object()&&item.contains("blocks")&&item["blocks"].is_array())for(const auto& block:item["blocks"])acceptBlock(block);
      else acceptBlock(item);
    }
    bool partial=false; json warnings=warningsFor(entries,r,xmlParts(entries));
    std::map<std::string,std::map<int,std::vector<const Segment*>>> byParagraph;
    for (const auto& s : segments) byParagraph[s.part][s.paragraph].push_back(&s);
    for (const auto& [part, paragraphs] : byParagraph) {
      auto parsed=parseXml(originalXml.at(part)); std::vector<pugi::xml_node> nodes; walk(parsed,"p",nodes);
      for (const auto& [pi,chunks] : paragraphs) {
        std::string combined; bool paragraphPartial=false;
        for (const auto* s : chunks) {
          auto it=translations.find(s->id);
          if (it != translations.end() && it->second.second == "translated") combined += it->second.first;
          else { combined += s->text; if(s->text.find_first_not_of(" \t\r\n")!=std::string::npos)paragraphPartial=true; }
        }
        if (paragraphPartial) { partial=true; }
        auto original=paragraphText(nodes.at(static_cast<size_t>(pi)));
        if (combined != original) {
          std::vector<pugi::xml_node> runs; paragraphTextNodes(nodes.at(static_cast<size_t>(pi)),runs);
          if (runs.size() > 1) warnings.push_back("Inline formatting within a translated paragraph may shift: " + part + "#p:" + std::to_string(pi));
        }
        if (combined != original) setParagraphText(nodes.at(static_cast<size_t>(pi)),combined);
      }
      std::ostringstream out; parsed.save(out,"",pugi::format_default,pugi::encoding_utf8);
      originalXml[part]=out.str();
    }
    if (partial) warnings.push_back("Some text segments were not translated and remain in the original language");
    if (destination.empty()) throw std::runtime_error("DOCX output path is empty");
    const std::string temp=tempPath(destination);
    std::error_code ec; fs::remove(temp,ec);
    writePackage(temp,r,entries,originalXml);
    fs::rename(temp,destination,ec);
    if (ec) { fs::remove(temp); throw std::runtime_error("Cannot finalize translated DOCX at the selected destination"); }
    return json{{"path",destination},{"partial",partial},{"warnings",warnings}}.dump();
  } catch (const std::exception& e) { return failJson(e.what()); }
}
}
