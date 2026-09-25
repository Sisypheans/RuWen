cmake_minimum_required(VERSION 3.22.1)
project("ruwen_whisper")

# Android 15+ 16 KB 页大小设备要求：native 库的 LOAD 段须按 16 KB (16384) 边界对齐，
# 否则在 16KB 设备上 dlopen 会失败、应用启动即崩溃。此处为所有 SHARED 库统一设置对齐。
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ============================================================
#  whisper.cpp 集成
# ============================================================
# 说明：
# 构建前请先运行 scripts/setup_whisper.ps1 脚本下载 whisper.cpp 源码
# 脚本会将 whisper.cpp 源码下载到 app/src/main/cpp/whisper/ 目录下
# ============================================================

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper")

# ----------------------------------------------------------------------------
#  关键改动说明（性能修复）
# ----------------------------------------------------------------------------
# 1) 旧写法用 add_subdirectory(whisper) 生成一个静态库，再链接进唯一的
#    libruwen_whisper.so。这样整个进程只有一份 whisper 机器码，无法针对不同
#    CPU 能力提供不同的 -march 版本，也就无法启用 ARMv8.2 的 FP16 向量算术
#    与 dotprod（int8 点积）指令。
#
#    本项目 vendored 的 whisper.cpp 版本是 **v1.5.4**（见
#    whisper/CMakeLists.txt 的 project(whisper.cpp VERSION 1.5.4)），
#    该版本的 CMakeLists.txt 里：
#      - 存在 WHISPER_NO_AVX / NO_AVX2 / NO_FMA / NO_F16C（只影响 x86，
#        在 arm64 上完全无效果）；
#      - **不存在** WHISPER_ARM / WHISPER_ARM_FMA —— 旧写法里这两行是空转的，
#        从未产生任何编译选项；
#      - 不存在 GGML_CPU_ARM_ARCH 之类的新版开关（那是 2024 下半年 ggml
#        独立子项目之后的产物）。
#    所以在 v1.5.4 上，「ARM 侧优化」唯一正确的开启方式就是直接给编译单元
#    传 -march，这正是官方 examples/whisper.android 的做法（它把 ggml.c 等
#    源文件直接编进每个产物，并对 arm64 额外编译一个 -march=armv8.2-a+fp16
#    的 whisper_v8fp16_va 变体，运行时按 /proc/cpuinfo 决定加载哪个）。
#    这里沿用同一套方案。
#
# 2) FP16 向量算术（__ARM_FEATURE_FP16_VECTOR_ARITHMETIC）只有在
#    -march 带 +fp16 时才由编译器自动定义。它决定 ggml.c 里
#    ggml_vec_dot_f16() 走 float16x8 + vfmaq_f16（8 元素/指令）还是退化成
#    "vcvt 转 f32 + vfmaq_f32"（4 元素/指令）。若用户下载 FP16 模型（如已移除的
#    medium.en FP16，约 1.53GB），所有矩阵乘都走 ggml_vec_dot_f16，因此这个开关影响巨大；
#    默认档位 small.en(Q5_1) 等量化模型则主要靠下方 dotprod 加速。
#
# 3) dotprod（+dotprod）只影响 **量化** 模型（q5_0/q5_1/q8_0）的矩阵乘；
#    对 FP16 模型无效果。之所以一并打开，是为了让默认档位 small.en(Q5_1)
#    及用户可下载的 medium.en-q5_0 等量化模型直接受益。
#
# 4) 绝不使用 -march=native（交叉编译时它描述的是编译主机，会生成在目标机上
#    SIGILL 的指令）。优化变体只在运行时检测到 CPU 具备 fphp + asimddp 时才
#    被加载，否则回退到基线库，保证老设备（如骁龙 820/835 等 ARMv8.0）可用。
# ----------------------------------------------------------------------------

# 判断 whisper.cpp 源码是否完整（只需头文件 + 核心源文件）
if(EXISTS "${WHISPER_DIR}/whisper.h" AND EXISTS "${WHISPER_DIR}/ggml.c")
    set(WHISPER_SOURCE_AVAILABLE TRUE)
else()
    set(WHISPER_SOURCE_AVAILABLE FALSE)
endif()

if(NOT WHISPER_SOURCE_AVAILABLE)
    message(WARNING "whisper.cpp source not found at ${WHISPER_DIR}")
    message(WARNING "Please run scripts/setup_whisper.ps1 to download whisper.cpp")
    message(WARNING "Building stub library for compilation...")

    # 创建一个空的 stub 库，确保项目能编译通过（保持原有行为）
    add_library(whisper STATIC whisper_stub.cpp)
    target_include_directories(whisper PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

    add_library(ruwen_whisper SHARED whisper_jni.cpp)
    target_include_directories(ruwen_whisper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(ruwen_whisper whisper log android)
    target_compile_options(ruwen_whisper PRIVATE -O3)
else()
    message(STATUS "Found whisper.cpp at ${WHISPER_DIR}")

    # 与官方 examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt
    # 一致：直接把源文件编进每个产物，而不是先做成静态库。
    set(WHISPER_SOURCES
        ${WHISPER_DIR}/ggml.c
        ${WHISPER_DIR}/ggml-alloc.c
        ${WHISPER_DIR}/ggml-backend.c
        ${WHISPER_DIR}/ggml-quants.c
        ${WHISPER_DIR}/whisper.cpp
    )

    # NDK 上 libm 可能只是桩库；找到才链接，避免任何链接期风险
    find_library(MATH_LIBRARY m)

    function(build_ruwen_whisper target_name)
        add_library(${target_name} SHARED whisper_jni.cpp ${WHISPER_SOURCES})

        target_include_directories(${target_name} PRIVATE
            ${WHISPER_DIR}
            ${WHISPER_DIR}/include
        )

        # -O3：ggml 的手写 SIMD 循环高度依赖编译器优化。
        # 注意不要加 -ffast-math：ggml 里有 NaN/INF 相关的掩码逻辑（attention
        # mask 用 -INFINITY 初始化），收益很小却可能引入正确性问题。
        target_compile_options(${target_name} PRIVATE -O3)

        target_link_libraries(${target_name} log android)
        if(MATH_LIBRARY)
            target_link_libraries(${target_name} ${MATH_LIBRARY})
        endif()

        target_link_options(${target_name} PRIVATE
            -Wl,-z,max-page-size=16384
            -Wl,--exclude-libs,ALL
        )
    endfunction()

    # 基线变体：armv8-a，所有 arm64 设备都能跑
    build_ruwen_whisper("ruwen_whisper")

    # 优化变体：ARMv8.2-A + FP16 向量算术 + dotprod。
    # 仅 arm64 构建；是否使用由 Kotlin 侧读取 /proc/cpuinfo 的 fphp / asimddp
    # 字段在运行时决定（见 WhisperNativeLibrary.kt）。
    if(${ANDROID_ABI} STREQUAL "arm64-v8a")
        build_ruwen_whisper("ruwen_whisper_v8fp16_va")
        target_compile_options(ruwen_whisper_v8fp16_va PRIVATE
            -march=armv8.2-a+fp16+dotprod
        )
    endif()
endif()
