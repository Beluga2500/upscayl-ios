"""Convertit les modèles ncnn d'Upscayl (mêmes poids que sur PC) en Core ML.

Entrée  : $UPSCAYL_MODELS (dossier .param/.bin), défaut build/upscayl/resources/models
Sortie  : build/models/<nom>.mlpackage  (entrée image RGB, sortie x4)
Tailles de tuile : 64/96/128/192/256 (formes énumérées) ; repli sur 128 fixe si la conversion échoue.
"""
import math
import os
import sys
import traceback

import coremltools as ct
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(__file__))
from ncnn_loader import load_ncnn  # noqa: E402

MODELS_DIR = os.environ.get("UPSCAYL_MODELS", "build/upscayl/resources/models")
OUT = "build/models"
TILES = [64, 96, 128, 192, 256]
DEFAULT_TILE = 128
NAMES = [
    "upscayl-standard-4x",
    "upscayl-lite-4x",
    "high-fidelity-4x",
    "digital-art-4x",
    "remacri-4x",
    "ultramix-balanced-4x",
    "ultrasharp-4x",
]


# ---------------- Architectures ----------------
class ResidualDenseBlock(nn.Module):
    def __init__(self, nf=64, gc=32):
        super().__init__()
        self.conv1 = nn.Conv2d(nf, gc, 3, 1, 1)
        self.conv2 = nn.Conv2d(nf + gc, gc, 3, 1, 1)
        self.conv3 = nn.Conv2d(nf + 2 * gc, gc, 3, 1, 1)
        self.conv4 = nn.Conv2d(nf + 3 * gc, gc, 3, 1, 1)
        self.conv5 = nn.Conv2d(nf + 4 * gc, nf, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, inplace=False)

    def forward(self, x):
        x1 = self.lrelu(self.conv1(x))
        x2 = self.lrelu(self.conv2(torch.cat((x, x1), 1)))
        x3 = self.lrelu(self.conv3(torch.cat((x, x1, x2), 1)))
        x4 = self.lrelu(self.conv4(torch.cat((x, x1, x2, x3), 1)))
        x5 = self.conv5(torch.cat((x, x1, x2, x3, x4), 1))
        return x5 * 0.2 + x


class RRDB(nn.Module):
    def __init__(self, nf, gc=32):
        super().__init__()
        self.rdb1 = ResidualDenseBlock(nf, gc)
        self.rdb2 = ResidualDenseBlock(nf, gc)
        self.rdb3 = ResidualDenseBlock(nf, gc)

    def forward(self, x):
        return self.rdb3(self.rdb2(self.rdb1(x))) * 0.2 + x


class RRDBNet(nn.Module):
    def __init__(self, nf=64, nb=23, gc=32):
        super().__init__()
        self.conv_first = nn.Conv2d(3, nf, 3, 1, 1)
        self.body = nn.Sequential(*[RRDB(nf, gc) for _ in range(nb)])
        self.conv_body = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up1 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up2 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_hr = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_last = nn.Conv2d(nf, 3, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, inplace=False)

    def forward(self, x):
        feat = self.conv_first(x)
        feat = feat + self.conv_body(self.body(feat))
        feat = self.lrelu(self.conv_up1(F.interpolate(feat, scale_factor=2, mode="nearest")))
        feat = self.lrelu(self.conv_up2(F.interpolate(feat, scale_factor=2, mode="nearest")))
        return self.conv_last(self.lrelu(self.conv_hr(feat)))


class SRVGGNetCompact(nn.Module):
    def __init__(self, nf=64, nc=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        body = [nn.Conv2d(3, nf, 3, 1, 1), nn.PReLU(num_parameters=nf)]
        for _ in range(nc):
            body += [nn.Conv2d(nf, nf, 3, 1, 1), nn.PReLU(num_parameters=nf)]
        body.append(nn.Conv2d(nf, 3 * upscale * upscale, 3, 1, 1))
        self.body = nn.ModuleList(body)
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        return out + F.interpolate(x, scale_factor=self.upscale, mode="nearest")


class Wrapper(nn.Module):
    """Entrée 0..1 (ImageType scale=1/255) -> sortie 0..255."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, x):
        return torch.clamp(self.net(x), 0.0, 1.0) * 255.0


# ---------------- Chargement des poids ncnn ----------------
def _set(module, w, b):
    assert tuple(module.weight.shape) == tuple(w.shape), (tuple(module.weight.shape), w.shape)
    module.weight.data = torch.from_numpy(np.ascontiguousarray(w))
    module.bias.data = torch.from_numpy(np.ascontiguousarray(b))


def build_from_ncnn(prefix):
    convs, prelus = load_ncnn(prefix)
    if prelus:  # SRVGGNetCompact
        nc = len(prelus) - 1
        net = SRVGGNetCompact(nc=nc)
        mods = [m for m in net.body if isinstance(m, nn.Conv2d)]
        pre = [m for m in net.body if isinstance(m, nn.PReLU)]
        assert len(mods) == len(convs) and len(pre) == len(prelus)
        for m, (w, b) in zip(mods, convs):
            _set(m, w, b)
        for m, s in zip(pre, prelus):
            m.weight.data = torch.from_numpy(s.copy())
        return net.eval()

    nb = (len(convs) - 6) // 15
    assert 15 * nb + 6 == len(convs), f"nombre de convolutions inattendu : {len(convs)}"
    net = RRDBNet(nb=nb)
    mods = [net.conv_first]
    for blk in net.body:
        for rdb in (blk.rdb1, blk.rdb2, blk.rdb3):
            mods += [rdb.conv1, rdb.conv2, rdb.conv3, rdb.conv4, rdb.conv5]
    mods += [net.conv_body, net.conv_up1, net.conv_up2, net.conv_hr, net.conv_last]
    for m, (w, b) in zip(mods, convs):
        _set(m, w, b)
    return net.eval()


# ---------------- Contrôles de cohérence ----------------
def test_image(n):
    yy, xx = torch.meshgrid(torch.linspace(0, 1, n), torch.linspace(0, 1, n), indexing="ij")
    img = torch.stack([
        0.5 + 0.3 * torch.sin(6 * xx + 3 * yy),
        0.5 + 0.3 * torch.cos(5 * yy - 2 * xx),
        0.2 + 0.6 * xx * yy + 0.1 * torch.sin(9 * yy),
    ]).unsqueeze(0)
    return img.clamp(0, 1)


def psnr(a, b):
    mse = float(np.mean((a - b) ** 2))
    return 10 * math.log10(1.0 / max(mse, 1e-12))


def check_torch(net):
    img = test_image(48)
    with torch.no_grad():
        out = net(img).clamp(0, 1)
    ref = F.interpolate(img, scale_factor=4, mode="bicubic", align_corners=False).clamp(0, 1)
    p = psnr(out.numpy(), ref.numpy())
    print(f"  cohérence PyTorch : PSNR vs bicubique = {p:.1f} dB")
    assert p > 20, "les poids ne produisent pas une image cohérente"


def to_coreml(net):
    wrapper = Wrapper(net).eval()
    example = torch.rand(1, 3, DEFAULT_TILE, DEFAULT_TILE)
    with torch.no_grad():
        traced = torch.jit.trace(wrapper, example)

    def convert(shape):
        return ct.convert(
            traced,
            inputs=[ct.ImageType(name="input", shape=shape, scale=1 / 255.0,
                                 color_layout=ct.colorlayout.RGB)],
            outputs=[ct.ImageType(name="output", color_layout=ct.colorlayout.RGB)],
            convert_to="mlprogram",
            minimum_deployment_target=ct.target.iOS16,
            compute_precision=ct.precision.FLOAT16,
        )

    try:
        shapes = ct.EnumeratedShapes(
            shapes=[(1, 3, t, t) for t in TILES], default=(1, 3, DEFAULT_TILE, DEFAULT_TILE))
        return convert(shapes), True
    except Exception as e:  # repli : tuile fixe
        print("  formes énumérées impossibles, repli sur 128 fixe :", repr(e)[:200])
        return convert((1, 3, DEFAULT_TILE, DEFAULT_TILE)), False


def check_coreml(ml, enumerated):
    if sys.platform != "darwin":
        return
    from PIL import Image

    for t in ([128, 192] if enumerated else [128]):
        arr = (test_image(t)[0].permute(1, 2, 0).numpy() * 255).astype(np.uint8)
        res = ml.predict({"input": Image.fromarray(arr)})
        out = res["output"]
        assert out.size == (4 * t, 4 * t), out.size
        o = np.asarray(out.convert("RGB")).astype(np.float32) / 255.0
        ref = np.asarray(Image.fromarray(arr).resize((4 * t, 4 * t), Image.BICUBIC)).astype(np.float32) / 255.0
        p = psnr(o, ref)
        print(f"  cohérence Core ML ({t} px) : PSNR = {p:.1f} dB")
        assert np.isfinite(o).all() and p > 18, "sortie Core ML incohérente"


def main():
    os.makedirs(OUT, exist_ok=True)
    ok, failed = [], []
    for name in NAMES:
        print(f"== {name}")
        try:
            net = build_from_ncnn(os.path.join(MODELS_DIR, name))
            check_torch(net)
            ml, enumerated = to_coreml(net)
            check_coreml(ml, enumerated)
            ml.save(os.path.join(OUT, name + ".mlpackage"))
            ok.append(name)
            print("  OK", "(tuiles variables)" if enumerated else "(tuile 128 fixe)")
        except Exception:
            traceback.print_exc()
            failed.append(name)
    print("\nRésumé : OK =", ok, "| ÉCHEC =", failed)
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
