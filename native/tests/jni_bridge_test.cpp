#include "android/bitmap.h"
#include "core.hpp"
#include "../vendor/miniz.h"
#include "../vendor/miniz_zip.h"
#include <jni.h>

#include <cstdarg>
#include <codecvt>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <locale>
#include <stdexcept>
#include <string>
#include <vector>

extern "C" {
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_inspect(JNIEnv*, jobject, jstring);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_inspectDocx(JNIEnv*, jobject, jstring);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_readDocxSegment(JNIEnv*, jobject, jstring, jint);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_exportDocx(JNIEnv*, jobject, jstring, jstring, jstring);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_readPage(JNIEnv*, jobject, jstring, jint);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_loadModel(JNIEnv*, jobject, jstring, jint, jint, jstring);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_translate(JNIEnv*, jobject, jstring, jstring, jstring, jstring, jstring);
void JNICALL Java_dev_kigya_translatepdf_NativeCore_releaseModel(JNIEnv*, jobject);
void JNICALL Java_dev_kigya_translatepdf_NativeCore_cancel(JNIEnv*, jobject);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_exportPage(JNIEnv*, jobject, jstring, jint, jstring, jstring, jstring, jstring);
jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_mergePdf(JNIEnv*, jobject, jstring, jstring);
jobject JNICALL Java_dev_kigya_translatepdf_NativeCore_render(JNIEnv*, jobject, jstring, jint, jint);
}

namespace {
struct FakeString final : _jstring { std::vector<jchar> value; };
struct FakeBitmap final : _jobject {
  AndroidBitmapInfo info{};
  std::vector<uint8_t> pixels;
};
struct JniHarness {
  JNINativeInterface_ table{};
  JNIEnv env{};
  bool exception = false;
  bool nullBitmap = false;
  bool throwFromBitmapFactory = false;
  int bitmapInfoStatus = 0;
  int bitmapLockStatus = 0;
  int bitmapUnlockStatus = 0;
  std::string thrownMessage;
  FakeBitmap bitmap;

  JniHarness() {
    table.FindClass = findClass;
    table.ThrowNew = throwNew;
    table.ExceptionCheck = exceptionCheck;
    table.GetStaticMethodID = staticMethod;
    table.CallStaticObjectMethodV = callStaticObject;
    table.GetStaticFieldID = staticField;
    table.GetStaticObjectField = staticObjectField;
    table.GetStringLength = stringLength;
    table.GetStringChars = stringChars;
    table.ReleaseStringChars = releaseStringChars;
    table.NewString = newString;
    env.functions = &table;
    active = this;
  }
  static JniHarness* active;

  static jclass JNICALL findClass(JNIEnv*, const char*) { return reinterpret_cast<jclass>(1); }
  static jint JNICALL throwNew(JNIEnv*, jclass, const char* message) {
    active->exception = true;
    active->thrownMessage = message ? message : "";
    return JNI_OK;
  }
  static jboolean JNICALL exceptionCheck(JNIEnv*) { return active->exception ? JNI_TRUE : JNI_FALSE; }
  static jmethodID JNICALL staticMethod(JNIEnv*, jclass, const char*, const char*) { return reinterpret_cast<jmethodID>(2); }
  static jfieldID JNICALL staticField(JNIEnv*, jclass, const char*, const char*) { return reinterpret_cast<jfieldID>(3); }
  static jobject JNICALL staticObjectField(JNIEnv*, jclass, jfieldID) { return reinterpret_cast<jobject>(4); }
  static jobject JNICALL callStaticObject(JNIEnv*, jclass, jmethodID, va_list args) {
    const jint width = va_arg(args, jint);
    const jint height = va_arg(args, jint);
    (void)va_arg(args, jobject);
    if (active->throwFromBitmapFactory) {
      active->exception = true;
      active->thrownMessage = "synthetic Bitmap.createBitmap exception";
      return nullptr;
    }
    if (active->nullBitmap) return nullptr;
    auto& bitmap = active->bitmap;
    bitmap.info = {static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                   static_cast<uint32_t>(width * 4 + 8), 1, 0};
    bitmap.pixels.assign(static_cast<size_t>(bitmap.info.stride) * bitmap.info.height, 0);
    return reinterpret_cast<jobject>(&bitmap);
  }
  static jsize JNICALL stringLength(JNIEnv*, jstring value) {
    return static_cast<jsize>(reinterpret_cast<FakeString*>(value)->value.size());
  }
  static const jchar* JNICALL stringChars(JNIEnv*, jstring value, jboolean* copied) {
    if (copied) *copied = JNI_TRUE;
    return reinterpret_cast<FakeString*>(value)->value.data();
  }
  static void JNICALL releaseStringChars(JNIEnv*, jstring, const jchar*) {}
  static jstring JNICALL newString(JNIEnv*, const jchar* value, jsize length) {
    auto* result = new FakeString;
    result->value.assign(value, value + length);
    return reinterpret_cast<jstring>(result);
  }

  static jint JNICALL bitmapInfo(JNIEnv*, jobject bitmap, AndroidBitmapInfo* info) {
    if (active->bitmapInfoStatus != 0) return active->bitmapInfoStatus;
    *info = reinterpret_cast<FakeBitmap*>(bitmap)->info;
    return 0;
  }
  static jint JNICALL bitmapLock(JNIEnv*, jobject bitmap, void** pixels) {
    if (active->bitmapLockStatus != 0) return active->bitmapLockStatus;
    *pixels = reinterpret_cast<FakeBitmap*>(bitmap)->pixels.data();
    return 0;
  }
  static jint JNICALL bitmapUnlock(JNIEnv*, jobject) { return active->bitmapUnlockStatus; }
};
JniHarness* JniHarness::active = nullptr;

FakeString* javaString(const std::u16string& input) {
  auto* result = new FakeString;
  result->value.assign(input.begin(), input.end());
  return result;
}
jstring javaString(const std::string& input) {
  const auto wide = std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(input);
  return reinterpret_cast<jstring>(javaString(wide));
}
std::string nativeString(jstring value) {
  if (!value) return {};
  const auto* string = reinterpret_cast<FakeString*>(value);
  return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(
      reinterpret_cast<const char16_t*>(string->value.data()),
      reinterpret_cast<const char16_t*>(string->value.data() + string->value.size()));
}
void check(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}
bool failed(const std::string& json) { return json.find("\"error\"") != std::string::npos; }
std::filesystem::path fixturePath() {
  const auto unique = std::chrono::steady_clock::now().time_since_epoch().count();
  const auto filename = "translatepdf-jni-中文-café-" + std::to_string(unique) + ".pdf";
  const auto path = std::filesystem::temp_directory_path() / std::filesystem::u8path(filename);
  const std::string stream = "BT /F1 12 Tf 10 40 Td (JNI fixture caf\351) Tj ET\n"
                            "1 0 0 rg 10 5 20 10 re f\n"
                            "0 0 1 rg 40 5 20 10 re f\n";
  std::vector<std::string> objects{
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 120 80] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
    "<< /Length " + std::to_string(stream.size()) + " >>\nstream\n" + stream + "endstream",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
  };
  std::string pdf = "%PDF-1.4\n%\xE2\xE3\xCF\xD3\n";
  std::vector<size_t> offsets{0};
  for (size_t i = 0; i < objects.size(); ++i) {
    offsets.push_back(pdf.size());
    pdf += std::to_string(i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n";
  }
  const size_t xref = pdf.size();
  pdf += "xref\n0 6\n0000000000 65535 f \n";
  for (size_t i = 1; i < offsets.size(); ++i) {
    char entry[32];
    std::snprintf(entry, sizeof(entry), "%010zu 00000 n \n", offsets[i]);
    pdf += entry;
  }
  pdf += "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + std::to_string(xref) + "\n%%EOF\n";
  std::ofstream output(path, std::ios::binary);
  output.write(pdf.data(), static_cast<std::streamsize>(pdf.size()));
  output.close();
  return path;
}
std::filesystem::path docxFixturePath() {
  const auto unique = std::chrono::steady_clock::now().time_since_epoch().count();
  const auto path = std::filesystem::temp_directory_path() /
      std::filesystem::u8path("translatepdf-jni-" + std::to_string(unique) + ".docx");
  mz_zip_archive archive{};
  check(mz_zip_writer_init_file(&archive, path.string().c_str(), 0) != 0,
        "DOCX JNI fixture ZIP should initialize");
  const std::vector<std::pair<const char*, const char*>> entries{
      {"[Content_Types].xml", "<Types><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"},
      {"_rels/.rels", "<Relationships><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"},
      {"word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>JNI DOCX source</w:t></w:r></w:p></w:body></w:document>"},
  };
  for (const auto& [name, value] : entries) {
    check(mz_zip_writer_add_mem(&archive, name, value, std::strlen(value), MZ_DEFAULT_COMPRESSION) != 0,
          "DOCX JNI fixture part should be written");
  }
  check(mz_zip_writer_finalize_archive(&archive) != 0, "DOCX JNI fixture should finalize");
  mz_zip_writer_end(&archive);
  return path;
}
}

int AndroidBitmap_getInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info) {
  return JniHarness::active->bitmapInfo(env, bitmap, info);
}
int AndroidBitmap_lockPixels(JNIEnv* env, jobject bitmap, void** pixels) {
  return JniHarness::active->bitmapLock(env, bitmap, pixels);
}
int AndroidBitmap_unlockPixels(JNIEnv* env, jobject bitmap) {
  return JniHarness::active->bitmapUnlock(env, bitmap);
}

int main() {
  try {
    JniHarness harness;
    const auto pdfPath = fixturePath();
    const auto path = javaString(pdfPath.string());
    const auto docxPath = docxFixturePath();
    const auto docxJavaPath = javaString(docxPath.string());
    check(nativeString(Java_dev_kigya_translatepdf_NativeCore_inspectDocx(&harness.env, nullptr, docxJavaPath)).find("\"format\":\"docx\"") != std::string::npos,
          "inspectDocx should return DOCX metadata through JNI");
    const auto docxSegment = nativeString(Java_dev_kigya_translatepdf_NativeCore_readDocxSegment(&harness.env, nullptr, docxJavaPath, 0));
    check(docxSegment.find("JNI DOCX source") != std::string::npos,
          "readDocxSegment should preserve segment text through JNI");
    const auto docxOutput = docxPath.parent_path() / (docxPath.stem().string() + "-translated.docx");
    const auto translatedSegments = javaString("[{\"id\":\"docx:word/document.xml#p:0#chunk:0\",\"translatedText\":\"JNI DOCX translation\",\"status\":\"translated\"}]");
    const auto exportedDocx = nativeString(Java_dev_kigya_translatepdf_NativeCore_exportDocx(&harness.env, nullptr, docxJavaPath, translatedSegments, javaString(docxOutput.string())));
    check(exportedDocx.find("\"path\"") != std::string::npos &&
          nativeString(Java_dev_kigya_translatepdf_NativeCore_readDocxSegment(&harness.env, nullptr, javaString(docxOutput.string()), 0)).find("JNI DOCX translation") != std::string::npos,
          "exportDocx should write and reopen translated DOCX text through JNI");
    check(nativeString(Java_dev_kigya_translatepdf_NativeCore_inspect(&harness.env, nullptr, path)).find("pageCount\":1") != std::string::npos,
          "inspect should parse a valid Unicode path and report one page");
    const auto extracted = nativeString(Java_dev_kigya_translatepdf_NativeCore_readPage(&harness.env, nullptr, path, 0));
    check(extracted.find("JNI fixture café") != std::string::npos,
          "readPage should preserve WinAnsi café through UTF-16 JNI result conversion");
    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_readPage(&harness.env, nullptr, path, -1))),
          "readPage should return structured failure for an invalid index");
    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_inspect(&harness.env, nullptr, nullptr))),
          "null JNI strings should map to empty native strings and return a failure");
    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_loadModel(&harness.env, nullptr, nullptr, 0, 0, nullptr))),
          "loadModel should reject missing path/profile without crashing");
    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_translate(&harness.env, nullptr, javaString("中文 — café"), nullptr, nullptr, nullptr, nullptr))),
          "translate should return a controlled unloaded-model failure with nullable inputs");
    Java_dev_kigya_translatepdf_NativeCore_releaseModel(&harness.env, nullptr);
    Java_dev_kigya_translatepdf_NativeCore_cancel(&harness.env, nullptr);

    const auto rendered = translatepdf::render(pdfPath.string(), 0, 40);
    harness.nullBitmap = true;
    check(Java_dev_kigya_translatepdf_NativeCore_render(&harness.env, nullptr, path, 0, 40) == nullptr,
          "render must handle Bitmap factory returning null");
    harness.nullBitmap = false;
    harness.throwFromBitmapFactory = true;
    check(Java_dev_kigya_translatepdf_NativeCore_render(&harness.env, nullptr, path, 0, 40) == nullptr && harness.exception,
          "render must preserve a pending Java exception from Bitmap factory");
    harness.throwFromBitmapFactory = false;
    harness.exception = false;
    harness.bitmapInfoStatus = -1;
    check(Java_dev_kigya_translatepdf_NativeCore_render(&harness.env, nullptr, path, 0, 40) == nullptr,
          "render must return null when AndroidBitmap_getInfo fails");
    harness.bitmapInfoStatus = 0;
    harness.bitmapLockStatus = -1;
    check(Java_dev_kigya_translatepdf_NativeCore_render(&harness.env, nullptr, path, 0, 40) == nullptr,
          "render must return null when AndroidBitmap_lockPixels fails");
    harness.bitmapLockStatus = 0;
    auto bitmap = Java_dev_kigya_translatepdf_NativeCore_render(&harness.env, nullptr, path, 0, 40);
    check(bitmap != nullptr, "render should create a bitmap for a real PDF");
    const auto& pixels = harness.bitmap.pixels;
    check(harness.bitmap.info.width == static_cast<uint32_t>(rendered.width) &&
          harness.bitmap.info.height == static_cast<uint32_t>(rendered.height), "render must preserve dimensions");
    bool foundOpaque = false;
    bool foundColor = false;
    for (int y = 0; y < rendered.height; ++y) {
      const auto* src = rendered.rgba.data() + static_cast<size_t>(y) * rendered.width * 4;
      const auto* dst = pixels.data() + static_cast<size_t>(y) * harness.bitmap.info.stride;
      for (int x = 0; x < rendered.width; ++x) {
        check(dst[x * 4] == src[x * 4 + 2] && dst[x * 4 + 1] == src[x * 4 + 1] &&
              dst[x * 4 + 2] == src[x * 4] && dst[x * 4 + 3] == src[x * 4 + 3],
              "render must convert RGBA source pixels into Android BGRA8888 respecting row stride");
        foundOpaque |= dst[x * 4 + 3] != 0;
        foundColor |= src[x * 4] != src[x * 4 + 2];
      }
      for (uint32_t pad = harness.bitmap.info.width * 4; pad < harness.bitmap.info.stride; ++pad)
        check(dst[pad] == 0, "render must not overwrite row padding");
    }
    check(foundOpaque, "PDF renderer should produce visible pixels");
    check(foundColor, "red/blue PDF shapes must make channel swapping observable");

    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_exportPage(&harness.env, nullptr, path, 0,
              javaString("[]"), javaString("/tmp/jni-test.pdf"), nullptr, nullptr))),
          "exportPage should return a structured failure when its required font is unavailable");
    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_mergePdf(&harness.env, nullptr,
              javaString("[]"), javaString("/tmp/jni-merge.pdf")))),
          "mergePdf should reject an empty input list safely");
    check(failed(nativeString(Java_dev_kigya_translatepdf_NativeCore_mergePdf(&harness.env, nullptr,
              javaString("not json"), javaString("/tmp/jni-merge.pdf")))),
          "mergePdf should reject malformed JSON safely");

    harness.exception = false;
    check(Java_dev_kigya_translatepdf_NativeCore_render(&harness.env, nullptr, javaString("/missing/jni-host.pdf"), 0, 32) == nullptr &&
          harness.exception && !harness.thrownMessage.empty(),
          "render should translate native PDF errors into IllegalStateException");
    std::filesystem::remove(pdfPath);
    std::filesystem::remove(docxPath);
    std::filesystem::remove(docxOutput);
    std::cout << "JNI entrypoints, Unicode/null boundaries, PDFium rendering, pixel conversion, and bitmap failures passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
