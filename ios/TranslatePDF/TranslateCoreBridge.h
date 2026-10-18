#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN
@interface TranslateCoreBridge : NSObject
+ (NSString *)inspect:(NSString *)path;
+ (NSString *)readPage:(NSString *)path index:(NSInteger)index;
+ (nullable NSData *)renderPNG:(NSString *)path index:(NSInteger)index width:(NSInteger)width;
+ (NSString *)loadModel:(NSString *)path context:(NSInteger)context threads:(NSInteger)threads;
+ (NSString *)translate:(NSString *)text glossary:(NSString *)glossary context:(NSString *)context;
+ (void)releaseModel;
+ (void)cancel;
+ (NSString *)exportPage:(NSString *)source index:(NSInteger)index blocksJSON:(NSString *)blocksJSON destination:(NSString *)destination fontPath:(NSString *)fontPath;
+ (NSString *)mergePDF:(NSString *)pagesJSON destination:(NSString *)destination;
@end
NS_ASSUME_NONNULL_END
