#pragma once

#include <string>

namespace translatepdf {

// DOCX package API. Paragraphs are split into deterministic <=1800-byte UTF-8
// segments at whitespace where possible; IDs include paragraph and chunk ordinals.
std::string inspectDocx(const std::string& path);
std::string readDocxSegment(const std::string& path, int index);
std::string exportDocx(const std::string& source, const std::string& segmentsJson,
                       const std::string& destination);

}
