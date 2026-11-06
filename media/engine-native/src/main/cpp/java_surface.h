#pragma once

#include <jni.h>

namespace mediagate {

/**
 * 更新 MediaCodec 硬解用的 Java Surface 全局引用（null 表示解绑）。
 *
 * 软件渲染走 ANativeWindow；硬解要的是 android.view.Surface（FFmpeg 的
 * mediacodec 解码器用 Java Surface 接管输出）。两个句柄同源，各自保存。
 * 旧全局引用故意不 Delete：解码线程可能仍持有它，换 Surface 时宁可变慢一点也不要 UAF。
 */
void updateJavaSurface(JNIEnv* env, jobject surface);

/** 当前 Java Surface 全局引用（可能为空；调用方不要 DeleteGlobalRef）。 */
jobject javaSurface();

}  // namespace mediagate
