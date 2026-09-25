// whisper_jni.cpp
// JNI 绑定层：连接 Kotlin 代码与 whisper.cpp C++ 库
//
// 功能：
// 1. 初始化/释放 whisper 上下文（支持 assets 直接流式加载，或文件路径加载）
// 2. 音频转文字（完整转录），音频数据通过 direct ByteBuffer 零拷贝传入
// 3. 进度回调
// 4. SRT 格式字幕生成
// 5. 取消（abort）正在进行的识别
//
// JNI 规范要点（本文件严格遵循）：
// - JNIEnv* 是「线程私有」的，绝不能缓存进全局；进程内唯一可缓存的是 JavaVM*。
// - jobject 局部引用只在本次 native 调用期间有效；需要跨回调持有必须 NewGlobalRef，
//   用完 DeleteGlobalRef。
// - 每次 CallXxxMethod 之后必须 ExceptionCheck，避免 pending exception 在后续
//   JNI 调用中造成未定义行为。
// - 只读的 Java 数组用 JNI_ABORT 释放，避免无意义的回写拷贝。

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <iomanip>
#include <string>

// whisper.cpp 头文件
#include "whisper.h"

#define LOG_TAG "RuWenWhisper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace {

// ============================================================================
//  全局状态
// ============================================================================

// 进程唯一，可安全缓存（JNI 官方规范允许缓存 JavaVM*）
std::atomic<JavaVM*> g_vm{nullptr};

// 进度回调所需：类（全局引用，防止类被卸载）、方法 ID、回调对象（全局引用）
std::atomic<jclass>    g_callbackClass{nullptr};
std::atomic<jmethodID> g_progressMethod{nullptr};
std::atomic<jobject>   g_callbackObj{nullptr};

// 取消标记：由 Kotlin 侧 nativeCancel() 置位
std::atomic<bool>      g_abort{false};

// 上一次打过日志的 native 进度（用于把进度日志降频到每 5% 一条，
// 否则 whisper 每个 30s 窗口就会回调一次，长音频会刷爆 logcat）
std::atomic<int>       g_lastLoggedProgress{-100};

// 单调时钟毫秒数：用于统计模型加载耗时 / 识别耗时 / 实时倍率
int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// 把本次编译实际启用的 SIMD 特性打成日志。
// 这是排查「为什么这么慢」的第一手证据：如果 fp16_va=0，说明加载的是基线库
// 或 -march 没生效，ggml 的 ggml_vec_dot_f16() 会退化成 cvt+f32 路径。
const char* buildFeatureSummary() {
    static std::string summary;
    if (summary.empty()) {
        std::ostringstream oss;
        oss << "neon="
#if defined(__ARM_NEON)
            << "1"
#else
            << "0"
#endif
            << " fma="
#if defined(__ARM_FEATURE_FMA)
            << "1"
#else
            << "0"
#endif
            << " fp16_va="
#if defined(__ARM_FEATURE_FP16_VECTOR_ARITHMETIC)
            << "1"
#else
            << "0"
#endif
            << " dotprod="
#if defined(__ARM_FEATURE_DOTPROD)
            << "1"
#else
            << "0"
#endif
            << " i8mm="
#if defined(__ARM_FEATURE_MATMUL_INT8)
            << "1"
#else
            << "0"
#endif
            ;
        summary = oss.str();
    }
    return summary.c_str();
}

// 取当前线程可用的 JNIEnv；若当前线程尚未附加到 VM（例如将来改成异步回调），
// 则临时附加，析构时自动分离。
class ScopedEnv {
public:
    ScopedEnv() {
        JavaVM* vm = g_vm.load();
        if (vm == nullptr) return;
        if (vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) != JNI_OK) {
            if (vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        }
    }

    ~ScopedEnv() {
        JavaVM* vm = g_vm.load();
        if (attached_ && vm != nullptr) {
            vm->DetachCurrentThread();
        }
    }

    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool    attached_ = false;
};

// 缓存进度回调的方法 ID（同一个类只解析一次，并持有 class 全局引用防止卸载）
bool cacheProgressMethod(JNIEnv* env, jobject thiz) {
    if (g_progressMethod.load() != nullptr) return true;

    jclass local = env->GetObjectClass(thiz);
    if (local == nullptr) return false;

    jmethodID mid = env->GetMethodID(local, "onNativeProgress", "(I)V");
    if (mid == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(local);
        LOGE("Cannot find onNativeProgress(I)V on WhisperManager");
        return false;
    }

    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (global == nullptr) return false;

    g_callbackClass.store(global);
    g_progressMethod.store(mid);
    return true;
}

// 格式化时间戳为 SRT 格式 (HH:MM:SS,mmm)
std::string formatTimestamp(int64_t t_ms) {
    if (t_ms < 0) t_ms = 0;
    const int64_t hours   = t_ms / 3600000;
    const int64_t minutes = (t_ms % 3600000) / 60000;
    const int64_t seconds = (t_ms % 60000) / 1000;
    const int64_t millis  = t_ms % 1000;

    std::ostringstream oss;
    oss << std::setw(2) << std::setfill('0') << hours << ":"
        << std::setw(2) << std::setfill('0') << minutes << ":"
        << std::setw(2) << std::setfill('0') << seconds << ","
        << std::setw(3) << std::setfill('0') << millis;
    return oss.str();
}

// 去掉首尾空白（用于跳过 whisper 输出的空白片段）
std::string trim(const char* s) {
    if (s == nullptr) return std::string();
    std::string out(s);
    const size_t first = out.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return std::string();
    const size_t last = out.find_last_not_of(" \t\r\n");
    return out.substr(first, last - first + 1);
}

} // namespace

// ============================================================================
//  whisper.cpp 回调
// ============================================================================

// 进度回调（在调用 whisper_full 的线程上同步触发）
static void whisperProgressCallback(
    struct whisper_context* ctx,
    struct whisper_state* state,
    int progress,
    void* user_data) {
    (void)ctx;
    (void)state;
    (void)user_data;

    // 进度日志降频：每 5% 打一条，避免长音频把 logcat 刷爆
    const int last = g_lastLoggedProgress.load();
    if (progress >= last + 5 || progress < last) {
        g_lastLoggedProgress.store(progress);
        LOGI("whisper progress: %d%%", progress);
    }

    jobject obj = g_callbackObj.load();
    jmethodID mid = g_progressMethod.load();
    if (obj == nullptr || mid == nullptr) return;

    ScopedEnv scoped;
    JNIEnv* env = scoped.get();
    if (env == nullptr) return;

    env->CallVoidMethod(obj, mid, progress);
    // 必须检查并清除 Java 侧抛出的异常，否则会带着 pending exception 往下走
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// 返回 true 表示「请求中止」（whisper.cpp 语义：abort_callback 返回 true 即中止）
static bool whisperAbortCallback(void* user_data) {
    (void)user_data;
    return g_abort.load();
}

// ============================================================================
//  assets 流式加载（官方 whisper.android 示例的 whisper_init_from_asset 写法）
//  使用 AASSET_MODE_STREAMING + whisper_model_loader，模型无需先拷贝到磁盘，
//  省掉一次 1.5GB 的文件复制与 1.5GB 的永久内部存储占用。
// ============================================================================

static size_t assetRead(void* ctx, void* output, size_t read_size) {
    return AAsset_read(static_cast<AAsset*>(ctx), output, read_size);
}

static bool assetIsEof(void* ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset*>(ctx)) <= 0;
}

static void assetClose(void* ctx) {
    AAsset_close(static_cast<AAsset*>(ctx));
}

static struct whisper_context* whisperInitFromAsset(
    JNIEnv* env,
    jobject assetManager,
    const char* assetPath) {
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    if (manager == nullptr) {
        LOGE("AAssetManager_fromJava failed");
        return nullptr;
    }

    AAsset* asset = AAssetManager_open(manager, assetPath, AASSET_MODE_STREAMING);
    if (asset == nullptr) {
        LOGE("Failed to open asset '%s'", assetPath);
        return nullptr;
    }

    whisper_model_loader loader = {};
    loader.context = asset;
    loader.read    = &assetRead;
    loader.eof     = &assetIsEof;
    loader.close   = &assetClose;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    return whisper_init_with_params(&loader, cparams);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm.store(vm);
    // 关键可观测性：这个 .so 编译时到底开了哪些 SIMD 特性。
    // 若 fp16_va=0，说明用的是基线库（或 -march 未生效），FP16 模型会明显变慢。
    LOGI("whisper JNI loaded. build features: %s", buildFeatureSummary());
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
//  初始化 / 释放
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeInitFromAsset(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jstring assetPath) {

    const char* path = env->GetStringUTFChars(assetPath, nullptr);
    if (path == nullptr) return 0;
    LOGI("Initializing whisper model from asset: %s", path);

    const int64_t t0 = nowMs();
    struct whisper_context* ctx = whisperInitFromAsset(env, assetManager, path);
    const int64_t loadMs = nowMs() - t0;
    env->ReleaseStringUTFChars(assetPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from asset");
        return 0;
    }

    cacheProgressMethod(env, thiz);
    LOGI("Whisper initialized from asset successfully in %lld ms", (long long)loadMs);
    LOGI("System info: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeInitFromFile(
    JNIEnv* env,
    jobject thiz,
    jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;
    LOGI("Initializing whisper model from file: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    const int64_t t0 = nowMs();
    struct whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    const int64_t loadMs = nowMs() - t0;
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from file");
        return 0;
    }

    cacheProgressMethod(env, thiz);
    LOGI("Whisper initialized from file successfully in %lld ms", (long long)loadMs);
    LOGI("System info: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeRelease(
    JNIEnv* env,
    jobject thiz,
    jlong nativePointer) {

    (void)env;
    (void)thiz;

    if (nativePointer == 0) return;

    struct whisper_context* ctx = reinterpret_cast<struct whisper_context*>(nativePointer);
    whisper_free(ctx);
    LOGI("Whisper context released");
}

JNIEXPORT void JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeCancel(
    JNIEnv* env,
    jobject thiz) {
    (void)env;
    (void)thiz;
    g_abort.store(true);
    LOGI("Cancel requested");
}

// ---------------------------------------------------------------------------
//  识别
// ---------------------------------------------------------------------------

/**
 * @param samples 必须是 allocateDirect 出来的 ByteBuffer（16kHz 单声道 float PCM），
 *                通过 GetDirectBufferAddress 直接拿到地址，避免大数组跨 JNI 拷贝。
 */
JNIEXPORT jstring JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeTranscribe(
    JNIEnv* env,
    jobject thiz,
    jlong nativePointer,
    jobject samples,
    jint nSamples,
    jstring language,
    jint nThreads,
    jboolean translate,
    jboolean wordTimestamps) {

    if (nativePointer == 0) {
        LOGE("Native pointer is null");
        return env->NewStringUTF("");
    }

    if (samples == nullptr || nSamples <= 0) {
        LOGE("Invalid audio buffer");
        return env->NewStringUTF("");
    }

    // direct buffer：零拷贝取得 native 地址
    void* raw = env->GetDirectBufferAddress(samples);
    if (raw == nullptr) {
        LOGE("samples is not a direct ByteBuffer");
        return env->NewStringUTF("");
    }
    const float* audioData = static_cast<const float*>(raw);

    struct whisper_context* ctx = reinterpret_cast<struct whisper_context*>(nativePointer);

    const char* lang = env->GetStringUTFChars(language, nullptr);
    const double audioSec = (double)nSamples / 16000.0;
    LOGI("Transcribing %d samples (%.1f s audio), language=%s, threads=%d",
         nSamples, audioSec, lang, nThreads);

    // 回调对象必须用全局引用持有（局部引用在回调期间不保证有效）
    jobject callbackGlobal = env->NewGlobalRef(thiz);
    g_callbackObj.store(callbackGlobal);
    g_abort.store(false);
    g_lastLoggedProgress.store(-100);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    params.print_progress   = false;
    params.print_special    = false;
    params.print_timestamps = false;
    params.print_realtime   = false;
    params.translate        = (translate == JNI_TRUE);
    params.language         = lang;
    params.n_threads        = nThreads;
    params.n_max_text_ctx   = 16384;
    params.offset_ms        = 0;
    params.duration_ms      = 0;
    params.token_timestamps = (wordTimestamps == JNI_TRUE);
    params.thold_pt         = 0.01f;
    params.max_len          = 0;      // 0 = unlimited
    params.split_on_word    = true;
    params.suppress_blank   = true;
    params.suppress_non_speech_tokens = true;
    params.temperature      = 0.0f;

    // 进度 + 中止回调
    params.new_segment_callback          = nullptr;
    params.new_segment_callback_user_data = nullptr;
    params.progress_callback             = &whisperProgressCallback;
    params.progress_callback_user_data   = nullptr;
    params.abort_callback                = &whisperAbortCallback;
    params.abort_callback_user_data      = nullptr;

    const int64_t t0 = nowMs();
    const int result = whisper_full(ctx, params, audioData, nSamples);
    const int64_t elapsedMs = nowMs() - t0;

    env->ReleaseStringUTFChars(language, lang);

    // 实时倍率（xRT）：处理速度 / 音频时长。1.0x 表示「1 秒音频用 1 秒算完」。
    // 这是判断「慢是慢在模型档位还是慢在编译/线程配置」的核心指标。
    const double elapsedSec = (double)elapsedMs / 1000.0;
    const double xrt = elapsedSec > 0.0 ? audioSec / elapsedSec : 0.0;
    LOGI("Transcribe finished in %.1f s (audio %.1f s) => xRT=%.3f, threads=%d, rc=%d",
         elapsedSec, audioSec, xrt, nThreads, result);

    // 生成 token 总数：把「慢」拆成「token 太多」还是「每个 token 太慢」。
    // whisper.cpp 每个 30s 窗口的解码有硬上限 n_text_ctx/2-4 = 448/2-4 = 220
    // （见 whisper.cpp 的解码循环 `for (int i = 0, n_max = whisper_n_text_ctx(ctx)/2 - 4; ...)`），
    // 所以 28.9s 干净人声正常只有 ~100 个 token；若打出来贴近 220，说明解码端在病理性拖长，
    // 若只有 ~100，则 242s 全部要由「每个 token 太慢」来解释 —— 那就是计算路径本身的问题。
    {
        int nTokens = 0;
        const int nSeg = whisper_full_n_segments(ctx);
        for (int i = 0; i < nSeg; i++) {
            nTokens += whisper_full_n_tokens(ctx, i);
        }
        LOGI("Decoded %d tokens in %d segment(s) => %.1f ms/token (per-window cap 220)",
             nTokens, nSeg, nTokens > 0 ? (double)elapsedMs / (double)nTokens : 0.0);
    }

    // 补一发 100%：whisper.cpp 的 progress_callback 只在 seek 循环的**开头**触发
    // （`progress_cur = 100*(seek - seek_start)/(seek_end - seek_start)`），
    // 所以最后一个窗口的整段解码期间都不会再有进度上报，且循环退出时也永远不会自然
    // 到达 100%。UI 因此会长时间停在一个中间值（28.9s 音频实测停在 96%，
    // 窗口更多的长音频会停在 50% 之类），看起来像「卡住」，其实一直在跑。
    //
    // 注意：必须放在下面 DeleteGlobalRef(callbackGlobal) **之前** —— 一旦全局引用被
    // 删除并把 g_callbackObj 置空，这里拿到的就是 nullptr，100% 永远不会送到 UI。
    if (result == 0 && !g_abort.load()) {
        jobject obj = g_callbackObj.load();
        jmethodID mid = g_progressMethod.load();
        if (obj != nullptr && mid != nullptr) {
            ScopedEnv scoped;
            JNIEnv* cbEnv = scoped.get();
            if (cbEnv != nullptr) {
                cbEnv->CallVoidMethod(obj, mid, 100);
                if (cbEnv->ExceptionCheck()) {
                    cbEnv->ExceptionDescribe();
                    cbEnv->ExceptionClear();
                }
            }
        }
    }

    if (g_callbackObj.load() == callbackGlobal) {
        g_callbackObj.store(nullptr);
    }
    env->DeleteGlobalRef(callbackGlobal);

    const bool aborted = g_abort.load();
    if (aborted) {
        LOGW("Transcription cancelled");
        return env->NewStringUTF("");
    }

    if (result != 0) {
        LOGE("Whisper transcription failed with code %d", result);
        return env->NewStringUTF("");
    }

    // 生成 SRT 字幕
    const int nSegments = whisper_full_n_segments(ctx);
    LOGI("Transcription complete: %d segments", nSegments);

    std::ostringstream srt;
    int subtitleIndex = 1;

    for (int i = 0; i < nSegments; i++) {
        const std::string text = trim(whisper_full_get_segment_text(ctx, i));
        // 跳过空片段（whisper 常输出纯空白/纯标片段落）
        if (text.empty()) continue;

        // whisper_full_get_segment_t0/t1 单位为 厘秒(10ms)，*10 转毫秒
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        if (t1 <= t0) continue;

        srt << subtitleIndex << "\n";
        srt << formatTimestamp(t0) << " --> " << formatTimestamp(t1) << "\n";
        srt << text << "\n\n";
        subtitleIndex++;
    }

    LOGI("Generated %d subtitle entries", subtitleIndex - 1);
    return env->NewStringUTF(srt.str().c_str());
}

// ---------------------------------------------------------------------------
//  调试信息
// ---------------------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeGetSystemInfo(
    JNIEnv* env,
    jobject thiz,
    jlong nativePointer) {

    (void)thiz;
    (void)nativePointer;

    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "unavailable");
}

// ---------------------------------------------------------------------------
//  性能自检：ggml 矩阵乘基准
// ---------------------------------------------------------------------------

// 直接量出「这台机器上的 ggml 到底能跑多少 GFLOPS」。
//
// 存在的理由：small.en-q5_1 + 6 线程在骁龙 8s Gen 4 上实测 28.9s 音频要 242.3s
// （xRT=0.119）。扣掉 220 token 的解码上限后，编码器约 340 GFLOP 摊到 242s，
// 折合只有 ~1.5 GFLOP/s —— 比 2017 年的 Pixel 2（约 10 GFLOP/s）还慢好几倍。
// 纯读代码已经无法定位，必须用实测把问题空间劈成两半：
//   · Q5_1 基准有几十 GFLOPS → ggml 内核与多线程都正常，问题在模型档位 / 内存 / 调用层；
//   · Q5_1 基准只有个位数    → 计算内核或多线程本身退化，问题在构建 / 运行时。
//
// 这里复用 whisper.cpp 自带的 whisper_bench_ggml_mul_mat_str()（whisper.h 公开 API，
// 已经编进 .so），不自己拼 ggml 图 —— 本机没有 NDK 无法编译验证，手写图出错代价太大。
JNIEXPORT jstring JNICALL
Java_com_ruwen_audioplayer_whisper_WhisperManager_nativeBenchmark(
    JNIEnv* env,
    jobject thiz,
    jint nThreads) {

    (void)thiz;

    const int64_t t0 = nowMs();
    const char* raw = whisper_bench_ggml_mul_mat_str((int)nThreads);
    const int64_t benchMs = nowMs() - t0;

    const std::string result = (raw != nullptr) ? std::string(raw)
                                                : std::string("benchmark unavailable");

    // 基准输出有几十行，整段丢进 logcat 会被截断，这里逐行打印
    int lines = 0;
    std::istringstream iss(result);
    std::string line;
    while (std::getline(iss, line)) {
        if (!line.empty()) {
            LOGI("BENCH t=%d | %s", (int)nThreads, line.c_str());
            lines++;
        }
    }
    LOGI("BENCH threads=%d done in %lld ms (%d lines)",
         (int)nThreads, (long long)benchMs, lines);

    return env->NewStringUTF(result.c_str());
}

} // extern "C"
