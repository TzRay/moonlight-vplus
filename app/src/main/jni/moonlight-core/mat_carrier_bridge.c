#include "mat_carrier_bridge.h"
#include "moonlight-common-c/src/MatWire.h"
#include <stdlib.h>

/* AudioStream owns authentication/reassembly and joins workers before cleanup.
 * The Java consumer copies this borrowed array before returning. */
typedef struct {
    jobject consumer;
    jbyteArray cycle;
    jmethodID offer;
    jmethodID end;
    jmethodID progress;
} MAT_PLAYBACK_BRIDGE;

static int clearException(JNIEnv* env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return 1;
}

void* MatPlaybackBridgeCreate(JNIEnv* env, jobject consumer) {
    if (!consumer) return NULL;
    MAT_PLAYBACK_BRIDGE* bridge = calloc(1, sizeof(*bridge));
    if (!bridge) return NULL;
    jclass type = (*env)->GetObjectClass(env, consumer);
    if (!type) goto fail;
    bridge->offer = (*env)->GetMethodID(env, type, "offerMatCycle", "([BIJJJJ)Z");
    if (bridge->offer) bridge->end = (*env)->GetMethodID(env, type, "endMatStream", "(J)Z");
    if (bridge->end) bridge->progress = (*env)->GetMethodID(env, type, "matPlaybackProgress", "(JJ)I");
    (*env)->DeleteLocalRef(env, type);
    if (!bridge->offer || !bridge->end || !bridge->progress) goto fail;
    bridge->consumer = (*env)->NewGlobalRef(env, consumer);
    if (!bridge->consumer) goto fail;
    jbyteArray cycle = (*env)->NewByteArray(env, (jsize)MAT_WIRE_CYCLE);
    if (!cycle) goto fail;
    bridge->cycle = (*env)->NewGlobalRef(env, cycle);
    (*env)->DeleteLocalRef(env, cycle);
    if (!bridge->cycle) goto fail;
    return bridge;
fail:
    clearException(env);
    MatPlaybackBridgeDestroy(bridge, env);
    return NULL;
}

int MatPlaybackBridgeCycle(void* context, JNIEnv* env, const unsigned char* bytes, int length,
                           uint64_t epoch, uint64_t domain, uint64_t index, uint64_t pts) {
    MAT_PLAYBACK_BRIDGE* bridge = context;
    if (!bridge || !bytes || length != MAT_WIRE_CYCLE || epoch > INT64_MAX || domain > INT64_MAX ||
        index > INT64_MAX || pts > INT64_MAX) return 0;
    (*env)->SetByteArrayRegion(env, bridge->cycle, 0, length, (const jbyte*)bytes);
    if (clearException(env)) return 0;
    jboolean accepted = (*env)->CallBooleanMethod(env, bridge->consumer, bridge->offer,
        bridge->cycle, (jint)length, (jlong)epoch, (jlong)domain, (jlong)index, (jlong)pts);
    return clearException(env) ? 0 : accepted;
}

int MatPlaybackBridgeEnd(void* context, JNIEnv* env, uint64_t cycles) {
    MAT_PLAYBACK_BRIDGE* bridge = context;
    if (!bridge || cycles > INT64_MAX) return 0;
    jboolean accepted = (*env)->CallBooleanMethod(env, bridge->consumer, bridge->end, (jlong)cycles);
    return clearException(env) ? 0 : accepted;
}

int MatPlaybackBridgeProgress(void* context, JNIEnv* env, uint64_t epoch, uint64_t domain) {
    MAT_PLAYBACK_BRIDGE* bridge = context;
    if (!bridge) return -1;
    jint progress = (*env)->CallIntMethod(env, bridge->consumer, bridge->progress, (jlong)epoch, (jlong)domain);
    return clearException(env) ? -1 : progress;
}

void MatPlaybackBridgeDestroy(void* context, JNIEnv* env) {
    MAT_PLAYBACK_BRIDGE* bridge = context;
    if (!bridge) return;
    if (bridge->consumer) (*env)->DeleteGlobalRef(env, bridge->consumer);
    if (bridge->cycle) (*env)->DeleteGlobalRef(env, bridge->cycle);
    free(bridge);
}
