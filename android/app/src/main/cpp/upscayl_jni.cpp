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

static bool prepare_gpu(bool want) {
    if (!want) return false;
    if (!g_gpu_instance) {
        ncnn::create_gpu_instance();
        g_gpu_instance = true;
    }
    return ncnn::get_gpu_count() > 0;
}

static ncnn::Net* make_net(bool gpu) {
    ncnn::Net* n = new ncnn::Net();
    n->opt.use_vulkan_compute = gpu;
    n->opt.num_threads = std::max(1, ncnn::get_big_cpu_count());
    if (gpu) n->set_vulkan_device(0);
    return n;
}

static std::string to_std(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Modèle fourni avec l'app (dossier assets/models)
extern "C" JNIEXPORT jint JNICALL
Java_fork_upscayl_android_Native_load(JNIEnv* env, jobject, jobject assetManager,
                                      jstring name, jboolean useGpu) {
    std::string model = to_std(env, name);
    bool gpu = prepare_gpu(useGpu == JNI_TRUE);

    std::string key = "asset:" + model + (gpu ? ":gpu" : ":cpu");
    if (g_net && key == g_key) return gpu ? 1 : 0;

    delete g_net;
    g_net = nullptr;
    g_key.clear();

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    g_net = make_net(gpu);

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

// Modèle personnalisé importé par l'utilisateur (fichiers .param / .bin)
extern "C" JNIEXPORT jint JNICALL
Java_fork_upscayl_android_Native_loadFile(JNIEnv* env, jobject, jstring paramPath,
                                          jstring binPath, jboolean useGpu) {
    std::string param = to_std(env, paramPath);
    std::string bin = to_std(env, binPath);
    bool gpu = prepare_gpu(useGpu == JNI_TRUE);

    std::string key = "file:" + param + (gpu ? ":gpu" : ":cpu");
    if (g_net && key == g_key) return gpu ? 1 : 0;

    delete g_net;
    g_net = nullptr;
    g_key.clear();

    g_net = make_net(gpu);
    if (g_net->load_param(param.c_str()) != 0 || g_net->load_model(bin.c_str()) != 0) {
        LOGE("Chargement du modèle personnalisé impossible : %s", param.c_str());
        delete g_net;
        g_net = nullptr;
        return -1;
    }
    g_key = key;
    return gpu ? 1 : 0;
}

// (x, y) dans l'image transformée (tw x th) -> coordonnées dans l'image d'origine (largeur w).
// Transformation directe : miroir horizontal (si flip) puis k rotations de 90° dans le sens horaire.
static inline void map_back(int x, int y, int tw, int th, int k, bool flip, int w, int& sx, int& sy) {
    int cx = x, cy = y, cw = tw, ch = th;
    for (int i = 0; i < k; i++) {
        int px = cy;
        int py = cw - 1 - cx;
        cx = px;
        cy = py;
        int tmp = cw;
        cw = ch;
        ch = tmp;
    }
    if (flip) cx = w - 1 - cx;
    sx = cx;
    sy = cy;
}

static ncnn::Mat transform_mat(const ncnn::Mat& m, int t) {
    const int k = t & 3;
    const bool flip = (t & 4) != 0;
    const int w = m.w, h = m.h;
    const int tw = (k & 1) ? h : w;
    const int th = (k & 1) ? w : h;
    ncnn::Mat out;
    out.create(tw, th, m.c);
    for (int c = 0; c < m.c; c++) {
        const float* s = m.channel(c);
        float* d = out.channel(c);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int sx, sy;
                map_back(x, y, tw, th, k, flip, w, sx, sy);
                d[y * tw + x] = s[sy * w + sx];
            }
        }
    }
    return out;
}

static inline unsigned char to_u8(float v) {
    return (unsigned char)std::min(255.f, std::max(0.f, v + 0.5f));
}

// Retour : 0 ok, -1 pas de modèle, -2 erreur d'inférence (GPU), -3 sortie invalide,
//          -4 annulé, -5 bitmap invalide
extern "C" JNIEXPORT jint JNICALL
Java_fork_upscayl_android_Native_process(JNIEnv* env, jobject, jobject inBmp, jobject outBmp,
                                         jint scale, jint tile, jboolean ttaFlag, jobject callback) {
    if (!g_net) return -1;
    const bool tta = ttaFlag == JNI_TRUE;
    const int nT = tta ? 8 : 1;

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

    // Renvoie false si l'utilisateur annule.
    auto report = [&](float frac) -> bool {
        jboolean cont = env->CallBooleanMethod(callback, cbMid, (jfloat)(((float)done + frac) / (float)total));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return cont != JNI_FALSE;
    };

    for (int yi = 0; yi < tilesY && result == 0; yi++) {
        for (int xi = 0; xi < tilesX; xi++) {
            const int x0 = xi * tile, y0 = yi * tile;
            const int x1 = std::min(x0 + tile, W), y1 = std::min(y0 + tile, H);
            const int xp0 = std::max(x0 - PREPADDING, 0), yp0 = std::max(y0 - PREPADDING, 0);
            const int xp1 = std::min(x1 + PREPADDING, W), yp1 = std::min(y1 + PREPADDING, H);

            ncnn::Mat in = ncnn::Mat::from_pixels_roi(rgb.data(), ncnn::Mat::PIXEL_RGB, W, H,
                                                      xp0, yp0, xp1 - xp0, yp1 - yp0);
            in.substract_mean_normalize(0, norm_in);

            const int ox = (x0 - xp0) * scale, oy = (y0 - yp0) * scale;
            const int cw = (x1 - x0) * scale, ch = (y1 - y0) * scale;
            const int ow0 = (xp1 - xp0) * scale, oh0 = (yp1 - yp0) * scale;

            if (!tta) {
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
                if (out.w != ow0 || out.h != oh0) {
                    result = -3;
                    break;
                }
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
                        d[0] = to_u8(rr[xx]);
                        d[1] = to_u8(gg[xx]);
                        d[2] = to_u8(bb[xx]);
                        d[3] = 255;
                        d += 4;
                    }
                }
            } else {
                // TTA : 8 transformations (rotations + miroir), moyenne des résultats
                std::vector<float> acc((size_t)3 * ow0 * oh0, 0.f);
                for (int t = 0; t < nT; t++) {
                    ncnn::Mat tin = transform_mat(in, t);
                    ncnn::Mat o;
                    {
                        ncnn::Extractor ex = g_net->create_extractor();
                        ex.input("data", tin);
                        if (ex.extract("output", o) != 0) {
                            result = -2;
                            break;
                        }
                    }
                    o.substract_mean_normalize(0, norm_out);
                    if (o.w != tin.w * scale || o.h != tin.h * scale) {
                        result = -3;
                        break;
                    }
                    const int k = t & 3;
                    const bool flip = (t & 4) != 0;
                    for (int c = 0; c < 3; c++) {
                        const float* s = o.channel(c);
                        float* a = &acc[(size_t)c * ow0 * oh0];
                        for (int y = 0; y < o.h; y++) {
                            for (int x = 0; x < o.w; x++) {
                                int sx, sy;
                                map_back(x, y, o.w, o.h, k, flip, ow0, sx, sy);
                                a[(size_t)sy * ow0 + sx] += s[(size_t)y * o.w + x];
                            }
                        }
                    }
                    if (!report((float)(t + 1) / (float)nT)) {
                        result = -4;
                        break;
                    }
                }
                if (result != 0) break;

                const float inv = 1.f / (float)nT;
                const float* pr = &acc[0];
                const float* pg = &acc[(size_t)ow0 * oh0];
                const float* pb = &acc[(size_t)2 * ow0 * oh0];
                for (int yy = 0; yy < ch; yy++) {
                    const size_t base = (size_t)(oy + yy) * ow0 + ox;
                    unsigned char* d = (unsigned char*)op + (size_t)(y0 * scale + yy) * oi.stride +
                                       (size_t)x0 * scale * 4;
                    for (int xx = 0; xx < cw; xx++) {
                        d[0] = to_u8(pr[base + xx] * inv);
                        d[1] = to_u8(pg[base + xx] * inv);
                        d[2] = to_u8(pb[base + xx] * inv);
                        d[3] = 255;
                        d += 4;
                    }
                }
            }

            done++;
            if (!report(0.f)) {
                result = -4;
                break;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    return result;
}
