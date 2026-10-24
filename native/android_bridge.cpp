#include "core.hpp"
#include "docx.hpp"
#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <codecvt>
#include <locale>
#include <stdexcept>
namespace {
std::string str(JNIEnv* e,jstring s){if(!s)return {};auto p=e->GetStringChars(s,nullptr);std::u16string input(reinterpret_cast<const char16_t*>(p),e->GetStringLength(s));e->ReleaseStringChars(s,p);return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(input);}
jstring result(JNIEnv* e,const std::string& s){auto input=std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.from_bytes(s);return e->NewString(reinterpret_cast<const jchar*>(input.data()),static_cast<jsize>(input.size()));}
// Batch callbacks run synchronously on the owning worker. Never retain a JNI
// local reference across requests or clear a checkpoint writer's exception.
struct LocalReference {
 JNIEnv* env;
 jobject value;
 ~LocalReference(){if(value)env->DeleteLocalRef(value);}
};
std::string batchString(JNIEnv* e,jstring value){
 if(!value)return {};
 const auto* chars=e->GetStringChars(value,nullptr);
 if(!chars)throw std::runtime_error("Java string storage is unavailable.");
 struct PinnedCharacters {
  JNIEnv* env;jstring value;const jchar* characters;
  ~PinnedCharacters(){env->ReleaseStringChars(value,characters);}
 } pinned{e,value,chars};
 const std::u16string copy(reinterpret_cast<const char16_t*>(chars),e->GetStringLength(value));
 return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(copy);
}
void batchException(JNIEnv* e,const char* message){
 if(e->ExceptionCheck())return;
 LocalReference type{e,e->FindClass("java/lang/IllegalStateException")};
 if(type.value)e->ThrowNew(static_cast<jclass>(type.value),message);
}
}
extern "C" {
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_inspect(JNIEnv*e,jobject,jstring p){return result(e,translatepdf::inspect(str(e,p)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_inspectDocx(JNIEnv*e,jobject,jstring p){return result(e,translatepdf::inspectDocx(str(e,p)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_readDocxSegment(JNIEnv*e,jobject,jstring p,jint i){return result(e,translatepdf::readDocxSegment(str(e,p),i));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_exportDocx(JNIEnv*e,jobject,jstring s,jstring b,jstring d){return result(e,translatepdf::exportDocx(str(e,s),str(e,b),str(e,d)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_readPage(JNIEnv*e,jobject,jstring p,jint i){return result(e,translatepdf::readPage(str(e,p),i));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_loadModel(JNIEnv*e,jobject,jstring p,jint c,jint t,jstring profile){return result(e,translatepdf::loadModel(str(e,p),c,t,str(e,profile)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_loadModelBatch(JNIEnv*e,jobject,jstring p,jint c,jint t,jstring profile,jint sequences){
 try{
  if(e->ExceptionCheck())return nullptr;
  const auto path=batchString(e,p),modelProfile=batchString(e,profile);
  if(e->ExceptionCheck())return nullptr;
  return result(e,translatepdf::loadModelBatch(path,c,t,modelProfile,sequences));
 }catch(const std::exception& error){batchException(e,error.what());return nullptr;}
}
JNIEXPORT void JNICALL Java_dev_kigya_translatepdf_NativeCore_setWeightRepackingAllowed(JNIEnv*,jobject,jboolean allowed){translatepdf::setWeightRepackingAllowed(allowed==JNI_TRUE);}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_translate(JNIEnv*e,jobject,jstring p,jstring g,jstring c,jstring s,jstring t){return result(e,translatepdf::translate(str(e,p),str(e,g),str(e,c),str(e,s),str(e,t)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_translateForLayout(JNIEnv*e,jobject,jstring p,jstring g,jstring c,jstring s,jstring t,jstring o){return result(e,translatepdf::translateForLayout(str(e,p),str(e,g),str(e,c),str(e,s),str(e,t),str(e,o)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_translateBatch(JNIEnv*e,jobject,jstring requests,jstring glossary,jstring context,jstring source,jstring target,jlong generation,jobject callback){
 try{
  if(e->ExceptionCheck())return nullptr;
  if(!callback){batchException(e,"A durable batch completion callback is required.");return nullptr;}
  LocalReference type{e,e->GetObjectClass(callback)};
  if(e->ExceptionCheck()||!type.value)return nullptr;
  const auto method=e->GetMethodID(static_cast<jclass>(type.value),"onCompleted","(Ljava/lang/String;)I");
  if(e->ExceptionCheck()||!method)return nullptr;
  const auto requestText=batchString(e,requests),glossaryText=batchString(e,glossary),background=batchString(e,context),sourceLanguage=batchString(e,source),targetLanguage=batchString(e,target);
  if(e->ExceptionCheck())return nullptr;
  const auto summary=translatepdf::translateBatch(requestText,glossaryText,background,sourceLanguage,targetLanguage,generation,[&](const std::string& completed){
   if(e->ExceptionCheck())return 0;
   LocalReference payload{e,result(e,completed)};
   if(e->ExceptionCheck()||!payload.value)return 0;
   const jint acknowledgement=e->CallIntMethod(callback,method,payload.value);
   return e->ExceptionCheck()?0:static_cast<int>(acknowledgement);
  });
  if(e->ExceptionCheck())return nullptr;
  return result(e,summary);
 }catch(const std::exception& error){batchException(e,error.what());return nullptr;}
}
JNIEXPORT void JNICALL Java_dev_kigya_translatepdf_NativeCore_releaseModel(JNIEnv*,jobject){translatepdf::releaseModel();}
JNIEXPORT void JNICALL Java_dev_kigya_translatepdf_NativeCore_cancel(JNIEnv*,jobject){translatepdf::cancel();}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_inferenceProgress(JNIEnv*e,jobject){return result(e,translatepdf::inferenceProgress());}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_exportPage(JNIEnv*e,jobject,jstring p,jint i,jstring b,jstring d,jstring f,jstring cjk){return result(e,translatepdf::exportPage(str(e,p),i,str(e,b),str(e,d),str(e,f),str(e,cjk)));}
JNIEXPORT jstring JNICALL Java_dev_kigya_translatepdf_NativeCore_mergePdf(JNIEnv*e,jobject,jstring p,jstring d){return result(e,translatepdf::mergePdf(str(e,p),str(e,d)));}
JNIEXPORT jobject JNICALL Java_dev_kigya_translatepdf_NativeCore_render(JNIEnv*e,jobject,jstring p,jint i,jint w){try{
 auto r=translatepdf::render(str(e,p),i,w);auto cls=e->FindClass("android/graphics/Bitmap");auto cfgcls=e->FindClass("android/graphics/Bitmap$Config");auto config=e->GetStaticObjectField(cfgcls,e->GetStaticFieldID(cfgcls,"ARGB_8888","Landroid/graphics/Bitmap$Config;"));auto create=e->GetStaticMethodID(cls,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");auto bitmap=e->CallStaticObjectMethod(cls,create,r.width,r.height,config);if(e->ExceptionCheck()||!bitmap)return nullptr;
 AndroidBitmapInfo info{};if(AndroidBitmap_getInfo(e,bitmap,&info)!=0)return nullptr;void* pixels=nullptr;if(AndroidBitmap_lockPixels(e,bitmap,&pixels)!=0)return nullptr;
 for(int y=0;y<r.height;y++){auto dst=static_cast<uint8_t*>(pixels)+y*info.stride;auto src=r.rgba.data()+y*r.width*4;for(int x=0;x<r.width;x++){dst[x*4]=src[x*4+2];dst[x*4+1]=src[x*4+1];dst[x*4+2]=src[x*4];dst[x*4+3]=src[x*4+3];}}
 AndroidBitmap_unlockPixels(e,bitmap);return bitmap;
}catch(const std::exception& ex){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),ex.what());return nullptr;}}
}
