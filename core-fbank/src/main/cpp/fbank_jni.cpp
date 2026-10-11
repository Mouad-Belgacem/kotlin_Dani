// Pont JNI mince autour de knf::OnlineFbank (kaldi-native-fbank) - expose
// uniquement ce dont core-fbank a besoin (AcceptWaveform/NumFramesReady/
// GetFrame/InputFinished), pas un wrapper JNI generique.
//
// Deux configurations, selectionnees par StreamingFbank.Config cote Kotlin :
// - ZIPFORMER (config=0, defaut historique) : identique au caractere pres a
//   celle validee dans scripts/compare_fbank_parity.py (snip_edges=false,
//   window_type=povey, high_freq=-400.0, low_freq=20.0, dither=0,
//   preemph_coeff=0.97, remove_dc_offset=true, round_to_power_of_two=true,
//   num_bins=80, sample_rate=16000).
// - SEGMENTER (config=1) : reproduit transformers.audio_utils
//   ._extract_fbank_features (SeamlessM4TFeatureExtractor, utilise par
//   segmenter/final/) - snip_edges=true (center=False cote HF, pas de
//   reflect-padding), high_freq=0.0 (Nyquist=8000Hz, pas d'offset -400 comme
//   pour Zipformer), memes fenetre/preaccentuation/nb de bandes sinon.
//   Valide numeriquement contre la reference Python dans
//   scripts/compare_segmenter_fbank_parity.py (maxDiff=4.13e-4). L'appelant
//   Kotlin (core-segmenter) doit prealablement multiplier les echantillons
//   par 2^15 avant acceptWaveform() - convention "16-bit signed integers"
//   de SeamlessM4TFeatureExtractor, pas geree ici.
// Ne pas modifier l'une ou l'autre config sans revalider contre sa
// reference Python (voir FbankGoldenDeviceTest / SegmenterFbankGoldenDeviceTest
// cote Kotlin).

#include <jni.h>

#include <memory>
#include <vector>

#include "kaldi-native-fbank/csrc/online-feature.h"

namespace {

knf::FbankOptions BuildOptions(jint config) {
    knf::FbankOptions opts;
    opts.frame_opts.samp_freq = 16000;
    opts.frame_opts.dither = 0.0f;
    opts.frame_opts.window_type = "povey";
    opts.frame_opts.preemph_coeff = 0.97f;
    opts.frame_opts.remove_dc_offset = true;
    opts.frame_opts.round_to_power_of_two = true;
    opts.mel_opts.num_bins = 80;
    opts.mel_opts.is_librosa = false;
    opts.mel_opts.low_freq = 20.0f;
    if (config == 1) {
        opts.frame_opts.snip_edges = true;
        opts.mel_opts.high_freq = 0.0f;
    } else {
        opts.frame_opts.snip_edges = false;
        opts.mel_opts.high_freq = -400.0f;
    }
    return opts;
}

knf::OnlineFbank *AsFbank(jlong handle) {
    return reinterpret_cast<knf::OnlineFbank *>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeCreate(JNIEnv *, jobject, jint config) {
    auto *fbank = new knf::OnlineFbank(BuildOptions(config));
    return reinterpret_cast<jlong>(fbank);
}

JNIEXPORT void JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete AsFbank(handle);
}

JNIEXPORT void JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeAcceptWaveform(
    JNIEnv *env, jobject, jlong handle, jfloat sampleRate, jfloatArray samples) {
    jsize n = env->GetArrayLength(samples);
    std::vector<float> buffer(n);
    env->GetFloatArrayRegion(samples, 0, n, buffer.data());
    AsFbank(handle)->AcceptWaveform(sampleRate, buffer.data(), n);
}

JNIEXPORT void JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeInputFinished(JNIEnv *, jobject, jlong handle) {
    AsFbank(handle)->InputFinished();
}

JNIEXPORT jint JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeNumFramesReady(JNIEnv *, jobject, jlong handle) {
    return AsFbank(handle)->NumFramesReady();
}

JNIEXPORT jboolean JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeIsLastFrame(
    JNIEnv *, jobject, jlong handle, jint frameIndex) {
    return AsFbank(handle)->IsLastFrame(frameIndex) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeFeatureDim(JNIEnv *, jobject, jlong handle) {
    return AsFbank(handle)->Dim();
}

JNIEXPORT jfloatArray JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativeGetFrame(
    JNIEnv *env, jobject, jlong handle, jint frameIndex) {
    auto *fbank = AsFbank(handle);
    const float *frame = fbank->GetFrame(frameIndex);
    jint dim = fbank->Dim();
    jfloatArray result = env->NewFloatArray(dim);
    env->SetFloatArrayRegion(result, 0, dim, frame);
    return result;
}

JNIEXPORT void JNICALL
Java_com_tarteelwarsh_tilawa_fbank_StreamingFbank_nativePop(JNIEnv *, jobject, jlong handle, jint n) {
    AsFbank(handle)->Pop(n);
}

}  // extern "C"
