// whisper_stub.cpp
// 当 whisper.cpp 源码不存在时，提供一个空的 stub 实现，确保项目能编译通过
// 运行时调用会返回错误，提示用户下载模型和源码

#include <string>

// 提供 whisper.h 中核心 API 的空实现
extern "C" {

struct whisper_context;
struct whisper_full_params;

typedef int whisper_token;
typedef int whisper_state;

const char* whisper_print_system_info(void) {
    return "whisper_stub: whisper.cpp not compiled in";
}

const char* whisper_print_timings(struct whisper_context* ctx) {
    return "stub";
}

void whisper_print_usage(int argc, char** argv, const whisper_full_params& params) {
    (void)argc; (void)argv; (void)params;
}

struct whisper_context* whisper_init_from_file(const char* path_model) {
    (void)path_model;
    return nullptr;
}

struct whisper_context* whisper_init_from_buffer(const char* buffer, size_t buffer_size) {
    (void)buffer; (void)buffer_size;
    return nullptr;
}

void whisper_free(struct whisper_context* ctx) {
    (void)ctx;
}

int whisper_pcm_to_mel(
    struct whisper_context* ctx,
    const float* samples,
    int n_samples,
    int n_threads) {
    (void)ctx; (void)samples; (void)n_samples; (void)n_threads;
    return -1;
}

int whisper_set_mel(
    struct whisper_context* ctx,
    const float* data,
    int n_len,
    int n_mel) {
    (void)ctx; (void)data; (void)n_len; (void)n_mel;
    return -1;
}

int whisper_encode(
    struct whisper_context* ctx,
    int offset,
    int n_threads) {
    (void)ctx; (void)offset; (void)n_threads;
    return -1;
}

int whisper_decode(
    struct whisper_context* ctx,
    const whisper_token* tokens,
    int n_tokens,
    int n_past,
    int n_threads) {
    (void)ctx; (void)tokens; (void)n_tokens; (void)n_past; (void)n_threads;
    return -1;
}

int whisper_sample_best(struct whisper_context* ctx) {
    (void)ctx;
    return 0;
}

whisper_token whisper_sample_timestamp(
    struct whisper_context* ctx,
    int i_token) {
    (void)ctx; (void)i_token;
    return 0;
}

int whisper_lang_id(const char* lang) {
    (void)lang;
    return -1;
}

const char* whisper_lang_str(int lang_id) {
    (void)lang_id;
    return "";
}

int whisper_full(
    struct whisper_context* ctx,
    const whisper_full_params& params,
    const float* samples,
    int n_samples) {
    (void)ctx; (void)params; (void)samples; (void)n_samples;
    return -1;
}

int whisper_full_parallel(
    struct whisper_context* ctx,
    const whisper_full_params& params,
    const float* samples,
    int n_samples,
    int n_processors) {
    (void)ctx; (void)params; (void)samples; (void)n_samples; (void)n_processors;
    return -1;
}

int whisper_full_n_segments(struct whisper_context* ctx) {
    (void)ctx;
    return 0;
}

const char* whisper_full_get_segment_text(struct whisper_context* ctx, int i_segment) {
    (void)ctx; (void)i_segment;
    return "";
}

int whisper_full_n_tokens(struct whisper_context* ctx, int i_segment) {
    (void)ctx; (void)i_segment;
    return 0;
}

const char* whisper_full_get_token_text(
    struct whisper_context* ctx,
    int i_segment,
    int i_token) {
    (void)ctx; (void)i_segment; (void)i_token;
    return "";
}

whisper_token whisper_full_get_token_id(
    struct whisper_context* ctx,
    int i_segment,
    int i_token) {
    (void)ctx; (void)i_segment; (void)i_token;
    return 0;
}

float whisper_full_get_token_p(
    struct whisper_context* ctx,
    int i_segment,
    int i_token) {
    (void)ctx; (void)i_segment; (void)i_token;
    return 0.0f;
}

int64_t whisper_full_get_segment_t0(struct whisper_context* ctx, int i_segment) {
    (void)ctx; (void)i_segment;
    return 0;
}

int64_t whisper_full_get_segment_t1(struct whisper_context* ctx, int i_segment) {
    (void)ctx; (void)i_segment;
    return 0;
}

bool whisper_full_get_segment_speaker_turn_next(
    struct whisper_context* ctx,
    int i_segment) {
    (void)ctx; (void)i_segment;
    return false;
}

int whisper_full_get_segment_head(struct whisper_context* ctx, int i_segment) {
    (void)ctx; (void)i_segment;
    return 0;
}

} // extern "C"
