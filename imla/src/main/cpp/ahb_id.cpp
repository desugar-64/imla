#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>

typedef AHardwareBuffer* (*AHardwareBuffer_fromHardwareBuffer_fn)(JNIEnv*, jobject);

static AHardwareBuffer_fromHardwareBuffer_fn gFromHardwareBuffer = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    gFromHardwareBuffer = reinterpret_cast<AHardwareBuffer_fromHardwareBuffer_fn>(
        dlsym(RTLD_DEFAULT, "AHardwareBuffer_fromHardwareBuffer"));
    return JNI_VERSION_1_6;
}

static AHardwareBuffer* ahbFromJava(JNIEnv* env, jobject hardwareBuffer) {
    if (hardwareBuffer == nullptr || gFromHardwareBuffer == nullptr) {
        return nullptr;
    }
    return gFromHardwareBuffer(env, hardwareBuffer);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_serhiiyaremych_imla_internal_render_gl_HardwareBufferId_nativePtrNative(
        JNIEnv* env,
        jclass,
        jobject hardwareBuffer) {
    AHardwareBuffer* ahb = ahbFromJava(env, hardwareBuffer);
    if (ahb == nullptr) {
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ahb));
}
