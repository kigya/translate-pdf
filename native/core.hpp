#pragma once
#include "docx.hpp"
#include <cstdint>
#include <functional>
#include <string>
#include <vector>
namespace translatepdf {
struct Raster { int width; int height; std::vector<uint8_t> rgba; }; // BGRA bytes, stride width * 4.
std::string inspect(const std::string& path);
std::string readPage(const std::string& path, int index);
Raster render(const std::string& path, int index, int width);
std::string loadModel(const std::string& path, int context, int threads, const std::string& profile = "hy-mt2");
/** Optional Hy-MT2 worker session; weights survive solo/batch context changes. */
std::string loadModelBatch(const std::string& path, int context, int threads,
                          const std::string& profile, int maxSequences = 4);
/** Synchronous worker sink: 1=committed/continue, 2=committed/stop, 0=reject/stop. */
using BatchCompletion = std::function<int(const std::string&)>;
std::string translateBatch(const std::string& requestsJson, const std::string& glossary,
                           const std::string& background, const std::string& sourceLanguage,
                           const std::string& targetLanguage, int64_t generation,
                           const BatchCompletion& onCompleted);
/** Configure the next worker-owned load; disabling extra buffers keeps weights mapped. */
void setWeightRepackingAllowed(bool allowed);
std::string translate(const std::string& text, const std::string& glossary, const std::string& context,
                      const std::string& sourceLanguage = "en", const std::string& targetLanguage = "ru");
/** Retry fitting without mixing layout instructions into document metadata. */
std::string translateForLayout(const std::string& text, const std::string& glossary, const std::string& context,
                               const std::string& sourceLanguage, const std::string& targetLanguage,
                               const std::string& layoutOptions = "");
void releaseModel();
void cancel();
/** Lock-free inference counters; safe while loading/decoding on the worker. */
std::string inferenceProgress();
std::string exportPage(const std::string& source, int index, const std::string& blocksJson,
                       const std::string& destination, const std::string& fontPath,
                       const std::string& cjkFontPath = "");
std::string mergePdf(const std::string& pagesJson, const std::string& destination);
}
