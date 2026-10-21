// Moteur Upscayl pour Android : mêmes modèles ncnn que la version PC, via ncnn (Vulkan ou CPU).
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <vector>

#include "cpu.h"
#include "gpu.h"
#include "net.h"

#define TAG "UpscaylNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net* g_net = nullptr;
static std::string g_key;
static bool g_gpu_instance = false;

static const int PREPADDING = 10;

extern "C" JNIEXPORT jint JNICALL
Java_fork_upscayl_android_Native_load(JNIEnv* env, jobject, jobject assetManager,
                                      jstring name, jboolean useGpu) {
    const char* cname = env->GetStringUTFChars(name, nullptr);
    std::string model(cname);
    env->ReleaseStringUTFChars(name, cname);

    bool gpu = useGpu == JNI_TRUE;
    if (gpu) {
        if (!g_gpu_instance) {
            ncnn::create_gpu_instance();
            g_gpu_instance = true;
        }
        if (ncnn::get_gpu_count() <= 0) gpu = false;
    }

    std::string key = model + (gpu ? ":gpu" : ":cpu");
    if (g_net && key == g_key) return gpu ? 1 : 0;

    delete g_net;
    g_net = nullptr;
    g_key.clear();

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = gpu;
    g_net->opt.num_threads = std::max(1, ncnn::get_big_cpu_count());
    if (gpu) g_net->set_vulkan_device(0);

    std::string param = "models/" + model + ".param";
    std::string bin = "models/" + model + ".bin";
    if (g_net->load_param(mgr, param.c_str()) != 0 || g_net->load_model(mgr, bin.c_str()) != 0) {
        LOGE("Chargement du modèle impossible : %s", model.c_str());
        delete g_net;
        g_net = nullptr;
        return -1;
    }
    g_key = key;
    return gpu ? 1 : 0;
}

// Retour : 0 ok, -1 pas de modèle, -2 erreur d'inférence (GPU), -3 sortie invalide,
//          -4 annulé, -5 bitmap invalide
extern "C" JNIEXPORT jint JNICALL
Java_fork_upscayl_android_Native_process(JNIEnv* env, jobject, jobject inBmp, jobject outBmp,
                                         jint scale, jint tile, jobject callback) {
    if (!g_net) return -1;

    AndroidBitmapInfo ii, oi;
    if (AndroidBitmap_getInfo(env, inBmp, &ii) != 0 || AndroidBitmap_getInfo(env, outBmp, &oi) != 0)
        return -5;
    if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return -5;
    if ((int)oi.width != (int)ii.width * scale || (int)oi.height != (int)ii.height * scale)
        return -5;

    const int W = (int)ii.width, H = (int)ii.height;

    std::vector<unsigned char> rgb((size_t)W * H * 3);
    {
        void* ip = nullptr;
        if (AndroidBitmap_lockPixels(env, inBmp, &ip) != 0) return -5;
        for (int y = 0; y < H; y++) {
            const unsigned char* row = (const unsigned char*)ip + (size_t)y * ii.stride;
            unsigned char* d = &rgb[(size_t)y * W * 3];
            for (int x = 0; x < W; x++) {
                d[0] = row[x * 4 + 0];
                d[1] = row[x * 4 + 1];
                d[2] = row[x * 4 + 2];
                d += 3;
            }
        }
        AndroidBitmap_unlockPixels(env, inBmp);
    }

    void* op = nullptr;
    if (AndroidBitmap_lockPixels(env, outBmp, &op) != 0) return -5;

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID cbMid = env->GetMethodID(cbCls, "onProgress", "(F)Z");

    const float norm_in[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    const float norm_out[3] = {255.f, 255.f, 255.f};

    const int tilesX = (W + tile - 1) / tile;
    const int tilesY = (H + tile - 1) / tile;
    const int total = tilesX * tilesY;
    int done = 0;
    int result = 0;

    for (int yi = 0; yi < tilesY && result == 0; yi++) {
        for (int xi = 0; xi < tilesX; xi++) {
            const int x0 = xi * tile, y0 = yi * tile;
            const int x1 = std::min(x0 + tile, W), y1 = std::min(y0 + tile, H);
            const int xp0 = std::max(x0 - PREPADDING, 0), yp0 = std::max(y0 - PREPADDING, 0);
            const int xp1 = std::min(x1 + PREPADDING, W), yp1 = std::min(y1 + PREPADDING, H);

            ncnn::Mat in = ncnn::Mat::from_pixels_roi(rgb.data(), ncnn::Mat::PIXEL_RGB, W, H,
                                                      xp0, yp0, xp1 - xp0, yp1 - yp0);
            in.substract_mean_normalize(0, norm_in);

            ncnn::Mat out;
            {
                ncnn::Extractor ex = g_net->create_extractor();
                ex.input("data", in);
                if (ex.extract("output", out) != 0) {
                    result = -2;
                    break;
                }
            }
            out.substract_mean_normalize(0, norm_out);

            if (out.w != (xp1 - xp0) * scale || out.h != (yp1 - yp0) * scale) {
                result = -3;
                break;
            }

            const int ox = (x0 - xp0) * scale, oy = (y0 - yp0) * scale;
            const int cw = (x1 - x0) * scale, ch = (y1 - y0) * scale;
            const float* pr = out.channel(0);
            const float* pg = out.channel(1);
            const float* pb = out.channel(2);

            for (int yy = 0; yy < ch; yy++) {
                const float* rr = pr + (size_t)(oy + yy) * out.w + ox;
                const float* gg = pg + (size_t)(oy + yy) * out.w + ox;
                const float* bb = pb + (size_t)(oy + yy) * out.w + ox;
                unsigned char* d = (unsigned char*)op + (size_t)(y0 * scale + yy) * oi.stride +
                                   (size_t)x0 * scale * 4;
                for (int xx = 0; xx < cw; xx++) {
                    d[0] = (unsigned char)std::min(255.f, std::max(0.f, rr[xx] + 0.5f));
                    d[1] = (unsigned char)std::min(255.f, std::max(0.f, gg[xx] + 0.5f));
                    d[2] = (unsigned char)std::min(255.f, std::max(0.f, bb[xx] + 0.5f));
                    d[3] = 255;
                    d += 4;
                }
            }

            done++;
            jboolean cont = env->CallBooleanMethod(callback, cbMid, (jfloat)done / (jfloat)total);
            if (env->ExceptionCheck() || cont == JNI_FALSE) {
                env->ExceptionClear();
                result = -4;
                break;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    return result;
}
