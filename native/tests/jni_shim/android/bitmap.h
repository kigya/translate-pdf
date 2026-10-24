#pragma once

// Minimal host-test declaration of the Android NDK bitmap boundary used by
// android_bridge.cpp. Production Android builds continue to use NDK headers.
#include <jni.h>
#include <stdint.h>

typedef struct AndroidBitmapInfo {
  uint32_t width;
  uint32_t height;
  uint32_t stride;
  int32_t format;
  uint32_t flags;
} AndroidBitmapInfo;

int AndroidBitmap_getInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info);
int AndroidBitmap_lockPixels(JNIEnv* env, jobject bitmap, void** pixels);
int AndroidBitmap_unlockPixels(JNIEnv* env, jobject bitmap);
