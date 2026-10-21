"""Télécharge les poids Real-ESRGAN officiels et les convertit en Core ML.
Sortie : build/models/<nom>.mlpackage  (entrée 128x128 -> sortie 512x512, image RGB)
"""
import os
import sys
import urllib.request

import coremltools as ct
import torch
import torch.nn as nn
import torch.nn.functional as F

TILE = 128
OUT = "build/models"
WEIGHTS = "build/weights"


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
        out = self.rdb3(self.rdb2(self.rdb1(x)))
        return out * 0.2 + x


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


MODELS = {
    "RealESRGAN_x4plus": (
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth",
        lambda: RRDBNet(nb=23),
    ),
    "RealESRGAN_x4plus_anime_6B": (
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth",
        lambda: RRDBNet(nb=6),
    ),
    "realesr-general-x4v3": (
        "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth",
        lambda: SRVGGNetCompact(),
    ),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(WEIGHTS, exist_ok=True)
    for name, (url, factory) in MODELS.items():
        pth = os.path.join(WEIGHTS, name + ".pth")
        if not os.path.exists(pth):
            print(f"Téléchargement {name}…")
            urllib.request.urlretrieve(url, pth)

        ckpt = torch.load(pth, map_location="cpu")
        state = ckpt.get("params_ema") or ckpt.get("params") or ckpt
        net = factory()
        net.load_state_dict(state, strict=True)
        net.eval()

        model = Wrapper(net).eval()
        example = torch.rand(1, 3, TILE, TILE)
        with torch.no_grad():
            traced = torch.jit.trace(model, example)

        mlmodel = ct.convert(
            traced,
            inputs=[ct.ImageType(name="input", shape=(1, 3, TILE, TILE),
                                 scale=1 / 255.0, color_layout=ct.colorlayout.RGB)],
            outputs=[ct.ImageType(name="output", color_layout=ct.colorlayout.RGB)],
            convert_to="mlprogram",
            minimum_deployment_target=ct.target.iOS16,
            compute_precision=ct.precision.FLOAT16,
        )
        dest = os.path.join(OUT, name + ".mlpackage")
        mlmodel.save(dest)
        print("OK", dest)

        # Test rapide (macOS uniquement)
        if sys.platform == "darwin":
            from PIL import Image
            res = mlmodel.predict({"input": Image.new("RGB", (TILE, TILE), (128, 128, 128))})
            assert res["output"].size == (TILE * 4, TILE * 4), res["output"].size
            print("  test prédiction OK")


if __name__ == "__main__":
    main()
