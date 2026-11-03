#include "surface_renderer.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

void logW(const char* what) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", what);
}

}  // namespace

SurfaceRenderer::~SurfaceRenderer() {
    setWindow(nullptr);
}

void SurfaceRenderer::setWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == window) return;
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = window;
    if (window_ != nullptr) {
        // 统一用 RGBA_8888：软件路径最省事（GLES 路径以后再说，接口不用改）
        ANativeWindow_setBuffersGeometry(window_, 0, 0, WINDOW_FORMAT_RGBA_8888);
    }
}

bool SurfaceRenderer::hasWindow() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

void SurfaceRenderer::setBufferSize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return;
    ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
}

void SurfaceRenderer::windowSize(int* width, int* height) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) {
        if (width) *width = 0;
        if (height) *height = 0;
        return;
    }
    if (width) *width = ANativeWindow_getWidth(window_);
    if (height) *height = ANativeWindow_getHeight(window_);
}

bool SurfaceRenderer::blitRgba(const uint8_t* rgba, int width, int height) {
    if (rgba == nullptr || width <= 0 || height <= 0) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        logW("ANativeWindow_lock 失败（送帧）");
        return false;
    }
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    const int windowW = buffer.width;
    const int windowH = buffer.height;
    // 居中 + 等比（FIT）：先把整屏刷黑，再把画面贴到中间。
    // 2026-11-03：清屏用 fill_n（编译器可向量化），贴图用按行 memcpy——调用方现在直接产
    // BGRA（内存字节序与 Surface 一致），不再需要逐像素交换通道。
    std::fill_n(pixels, static_cast<size_t>(buffer.stride) * windowH, 0xFF000000u);
    const int offsetX = (windowW - width) / 2;
    const int offsetY = (windowH - height) / 2;
    const int visibleX = std::max(0, -offsetX);
    const int visibleY = std::max(0, -offsetY);
    const int copyW = std::min(width - visibleX, windowW - std::max(0, offsetX));
    if (copyW > 0) {
        const size_t rowBytes = static_cast<size_t>(copyW) * 4;
        for (int y = visibleY; y < height; ++y) {
            const int dstY = offsetY + y;
            if (dstY < 0 || dstY >= windowH) continue;
            uint8_t* dstRow = reinterpret_cast<uint8_t*>(pixels + static_cast<size_t>(dstY) * buffer.stride) +
                              static_cast<size_t>(std::max(0, offsetX)) * 4;
            const uint8_t* srcRow = rgba + (static_cast<size_t>(y) * width + visibleX) * 4;
            std::memcpy(dstRow, srcRow, rowBytes);
        }
    }
    ANativeWindow_unlockAndPost(window_);
    return true;
}

bool SurfaceRenderer::clear(uint32_t argb) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        logW("ANativeWindow_lock 失败（清屏）");
        return false;
    }
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    const int count = buffer.stride * buffer.height;
    for (int i = 0; i < count; ++i) pixels[i] = argb;
    ANativeWindow_unlockAndPost(window_);
    return true;
}

bool SurfaceRenderer::drawTestPattern(int frameIndex) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        logW("ANativeWindow_lock 失败（测试图案）");
        return false;
    }
    const int width = buffer.width;
    const int height = buffer.height;
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    // 一条随时间横向移动的竖线，用来确认"帧确实在更新"（不是卡住的静态画面）
    const int barX = (frameIndex * 7) % std::max(1, width);
    for (int y = 0; y < height; ++y) {
        uint32_t* row = pixels + static_cast<size_t>(y) * buffer.stride;
        for (int x = 0; x < width; ++x) {
            if (std::abs(x - barX) < 4) {
                row[x] = 0xFFFFFFFFu;  // 白线
            } else {
                // 横向渐变（红→蓝），每行加一点绿，方便看出画面朝向
                const uint32_t r = static_cast<uint32_t>(255.0 * x / std::max(1, width));
                const uint32_t b = 255u - r;
                const uint32_t g = static_cast<uint32_t>(96.0 * y / std::max(1, height));
                row[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
            }
        }
    }
    ANativeWindow_unlockAndPost(window_);
    return true;
}

}  // namespace mediagate