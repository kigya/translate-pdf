#import "TranslateCoreBridge.h"
#include "../../native/core.hpp"
#include <exception>

static NSString *TPString(const std::string &value) {
  return [[NSString alloc] initWithBytes:value.data() length:value.size() encoding:NSUTF8StringEncoding] ?: @"{}";
}

@implementation TranslateCoreBridge
+ (NSString *)inspect:(NSString *)path {
  return TPString(translatepdf::inspect(path.UTF8String ?: ""));
}
+ (NSString *)readPage:(NSString *)path index:(NSInteger)index {
  return TPString(translatepdf::readPage(path.UTF8String ?: "", (int)index));
}
+ (NSString *)inspectDocx:(NSString *)path {
  return TPString(translatepdf::inspectDocx(path.UTF8String ?: ""));
}
+ (NSString *)readDocxSegment:(NSString *)path index:(NSInteger)index {
  return TPString(translatepdf::readDocxSegment(path.UTF8String ?: "", (int)index));
}
+ (NSString *)exportDocx:(NSString *)source segmentsJSON:(NSString *)segmentsJSON destination:(NSString *)destination {
  return TPString(translatepdf::exportDocx(source.UTF8String ?: "", segmentsJSON.UTF8String ?: "[]", destination.UTF8String ?: ""));
}
+ (NSData *)renderPNG:(NSString *)path index:(NSInteger)index width:(NSInteger)width {
  translatepdf::Raster raster;
  // The Swift API is nullable: invalid/missing PDF pages are preview failures,
  // and C++ exceptions must not unwind through Objective-C/Swift callers.
  try {
    raster = translatepdf::render(path.UTF8String ?: "", (int)index, (int)width);
  } catch (const std::exception &) {
    return nil;
  }
  if (raster.width <= 0 || raster.height <= 0 || raster.rgba.size() < (size_t)raster.width * (size_t)raster.height * 4) return nil;
  CGDataProviderRef provider = CGDataProviderCreateWithData(NULL, raster.rgba.data(), raster.rgba.size(), NULL);
  if (!provider) return nil;
  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
  CGBitmapInfo bitmapInfo = (CGBitmapInfo)kCGBitmapByteOrder32Little | (CGBitmapInfo)kCGImageAlphaPremultipliedFirst;
  CGImageRef image = CGImageCreate((size_t)raster.width, (size_t)raster.height, 8, 32, (size_t)raster.width * 4, colorSpace, bitmapInfo, provider, NULL, false, kCGRenderingIntentDefault);
  UIImage *uiImage = image ? [UIImage imageWithCGImage:image] : nil;
  NSData *png = uiImage ? UIImagePNGRepresentation(uiImage) : nil;
  if (image) CGImageRelease(image);
  CGColorSpaceRelease(colorSpace);
  CGDataProviderRelease(provider);
  return png;
}
+ (NSString *)loadModel:(NSString *)path context:(NSInteger)context threads:(NSInteger)threads profile:(NSString *)profile {
  return TPString(translatepdf::loadModel(path.UTF8String ?: "", (int)context, (int)threads, profile.UTF8String ?: ""));
}
+ (NSString *)translate:(NSString *)text glossary:(NSString *)glossary context:(NSString *)context sourceLanguage:(NSString *)sourceLanguage targetLanguage:(NSString *)targetLanguage {
  return TPString(translatepdf::translate(text.UTF8String ?: "", glossary.UTF8String ?: "", context.UTF8String ?: "", sourceLanguage.UTF8String ?: "en", targetLanguage.UTF8String ?: "ru"));
}
+ (void)releaseModel { translatepdf::releaseModel(); }
+ (void)cancel { translatepdf::cancel(); }
+ (NSString *)inferenceProgress { return TPString(translatepdf::inferenceProgress()); }
+ (NSString *)exportPage:(NSString *)source index:(NSInteger)index blocksJSON:(NSString *)blocksJSON destination:(NSString *)destination fontPath:(NSString *)fontPath cjkFontPath:(NSString *)cjkFontPath {
  return TPString(translatepdf::exportPage(source.UTF8String ?: "", (int)index, blocksJSON.UTF8String ?: "[]", destination.UTF8String ?: "", fontPath.UTF8String ?: "", cjkFontPath.UTF8String ?: ""));
}
+ (NSString *)mergePDF:(NSString *)pagesJSON destination:(NSString *)destination {
  return TPString(translatepdf::mergePdf(pagesJSON.UTF8String ?: "[]", destination.UTF8String ?: ""));
}
@end
