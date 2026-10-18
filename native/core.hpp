#pragma once
#include <cstdint>
#include <string>
#include <vector>
namespace translatepdf {
struct Raster { int width; int height; std::vector<uint8_t> rgba; }; // BGRA bytes, stride width * 4.
std::string inspect(const std::string& path);
std::string readPage(const std::string& path, int index);
Raster render(const std::string& path, int index, int width);
std::string loadModel(const std::string& path, int context, int threads);
std::string translate(const std::string& text, const std::string& glossary, const std::string& context);
void releaseModel();
void cancel();
std::string exportPage(const std::string& source, int index, const std::string& blocksJson, const std::string& destination, const std::string& fontPath);
std::string mergePdf(const std::string& pagesJson, const std::string& destination);
}
