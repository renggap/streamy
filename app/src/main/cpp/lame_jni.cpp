// JNI bridge for LAME MP3 encoder.
// channel-aware: mono uses lame_encode_buffer (L==R); stereo uses
// lame_encode_buffer_interleaved (PCM is L,R,L,R,...) — avoids the
// duplicated-channel bug of passing the same buffer to lame_encode_buffer.
#include <jni.h>
#include <lame.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeInit(
        JNIEnv* /*env*/, jobject /*this*/, jint sampleRate, jint channels, jint bitrateKbps) {
    lame_t g = lame_init();
    if (g == nullptr) return 0;
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_out_samplerate(g, sampleRate);
    lame_set_brate(g, bitrateKbps);
    lame_set_quality(g, 5);
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return reinterpret_cast<jlong>(g);
}

/**
 * LAME can internally adjust the OUTPUT sample rate during lame_init_params (notably halving
 * it at low bitrates). The app's bitrate math and the MP3 direct-sink recording assume the
 * output equals the configured input rate, so the wrapper exposes the actual value back to
 * Kotlin ([Mp3Encoder.outSampleRateHz]) where it is pinned against the configured rate.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeOutSampleRate(JNIEnv* /*env*/, jobject /*this*/, jlong ptr) {
    lame_t g = reinterpret_cast<lame_t>(ptr);
    if (!g) return 0;
    return lame_get_out_samplerate(g);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeEncode(
        JNIEnv* env, jobject /*this*/, jlong ptr, jshortArray pcm, jint n, jint channels,
        jbyteArray out) {
    lame_t g = reinterpret_cast<lame_t>(ptr);
    if (!g) return -1;
    jshort* in = env->GetShortArrayElements(pcm, nullptr);
    if (in == nullptr) return -1;
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    if (o == nullptr) { env->ReleaseShortArrayElements(pcm, in, JNI_ABORT); return -1; }
    int outSize = env->GetArrayLength(out);
    int written;
    if (channels == 1) {
        written = lame_encode_buffer(g, in, in, n,
                                     reinterpret_cast<unsigned char*>(o), outSize);
    } else {
        // interleaved stereo: n is total shorts (frames*2); per-channel samples = n/2
        written = lame_encode_buffer_interleaved(g, in, n / 2,
                                                 reinterpret_cast<unsigned char*>(o), outSize);
    }
    env->ReleaseShortArrayElements(pcm, in, JNI_ABORT);
    env->ReleaseByteArrayElements(out, o, 0);
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeFlush(
        JNIEnv* env, jobject /*this*/, jlong ptr, jbyteArray out) {
    lame_t g = reinterpret_cast<lame_t>(ptr);
    if (!g) return -1;
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    if (o == nullptr) return -1;
    int outSize = env->GetArrayLength(out);
    int w = lame_encode_flush(g, reinterpret_cast<unsigned char*>(o), outSize);
    env->ReleaseByteArrayElements(out, o, 0);
    return w;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeClose(
        JNIEnv* /*env*/, jobject /*this*/, jlong ptr) {
    if (ptr) lame_close(reinterpret_cast<lame_t>(ptr));
}
