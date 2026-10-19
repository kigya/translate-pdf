#import "TranslateCoreBridge.h"
#include "../../native/core.hpp"

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
+ (NSData *)renderPNG:(NSString *)path index:(NSInteger)index width:(NSInteger)width {
  translatepdf::Raster raster = translatepdf::render(path.UTF8String ?: "", (int)index, (int)width);
  if (raster.width <= 0 || raster.height <= 0 || raster.rgba.size() < (size_t)raster.width * (size_t)raster.height * 4) return nil;
  CGDataProviderRef provider = CGDataProviderCreateWithData(NULL, raster.rgba.data(), raster.rgba.size(), NULL);
  if (!provider) return nil;
  CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
  CGBitmapInfo bitmapInfo = kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst;
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
+ (NSString *)translate:(NSString *)text glossary:(NSString *)glossary context:(NSString *)context {
  return TPString(translatepdf::translate(text.UTF8String ?: "", glossary.UTF8String ?: "", context.UTF8String ?: ""));
}
+ (void)releaseModel { translatepdf::releaseModel(); }
+ (void)cancel { translatepdf::cancel(); }
+ (NSString *)exportPage:(NSString *)source index:(NSInteger)index blocksJSON:(NSString *)blocksJSON destination:(NSString *)destination fontPath:(NSString *)fontPath {
  return TPString(translatepdf::exportPage(source.UTF8String ?: "", (int)index, blocksJSON.UTF8String ?: "[]", destination.UTF8String ?: "", fontPath.UTF8String ?: ""));
}
+ (NSString *)mergePDF:(NSString *)pagesJSON destination:(NSString *)destination {
  return TPString(translatepdf::mergePdf(pagesJSON.UTF8String ?: "[]", destination.UTF8String ?: ""));
}
@end
