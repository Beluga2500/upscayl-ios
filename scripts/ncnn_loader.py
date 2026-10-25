"""Lecture des modèles ncnn (.param/.bin) d'Upscayl avec numpy uniquement."""
import struct

import numpy as np


def parse_param(path):
    lines = open(path).read().strip().split("\n")
    assert lines[0].strip() == "7767517", "format .param inattendu"
    layers = []
    for line in lines[2:]:
        t = line.split()
        typ, name, nin, nout = t[0], t[1], int(t[2]), int(t[3])
        params = {}
        for kv in t[4 + nin + nout:]:
            k, _, v = kv.partition("=")
            params[int(k)] = v
        layers.append((typ, name, params))
    return layers


class _Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def raw(self, n):
        a = np.frombuffer(self.d, dtype="<f4", count=n, offset=self.p).copy()
        self.p += 4 * n
        return a

    def tagged(self, n):
        tag = struct.unpack_from("<I", self.d, self.p)[0]
        self.p += 4
        if tag == 0x01306B47:  # float16
            a = np.frombuffer(self.d, dtype="<f2", count=n, offset=self.p).astype(np.float32)
            self.p += 2 * n
            if (2 * n) % 4:
                self.p += 4 - (2 * n) % 4
            return a
        if tag in (0x0002C056, 0):  # float32
            return self.raw(n)
        raise ValueError(f"tag de poids inconnu : {tag:#x}")


def load_ncnn(prefix):
    """Renvoie (convs, prelus) : convs = [(poids[out,in,k,k], biais)], prelus = [pentes]."""
    layers = parse_param(prefix + ".param")
    data = open(prefix + ".bin", "rb").read()
    r = _Reader(data)
    convs, prelus = [], []
    for typ, _name, p in layers:
        if typ == "Convolution":
            out = int(p[0])
            k = int(p.get(1, 1))
            wsz = int(p[6])
            w = r.tagged(wsz)
            b = r.raw(out) if int(p.get(5, 0)) else np.zeros(out, np.float32)
            convs.append((w.reshape(out, wsz // (out * k * k), k, k), b))
        elif typ == "PReLU":
            prelus.append(r.raw(int(p[0])))
        elif typ in ("MemoryData", "Scale", "BatchNorm", "InnerProduct", "Deconvolution"):
            raise ValueError("couche non gérée : " + typ)
    assert r.p == len(data), f"fichier .bin non consommé entièrement ({r.p}/{len(data)})"
    return convs, prelus


def expected_rrdb_shapes(nb, nf=64, gc=32):
    s = [(nf, 3)]
    for _ in range(nb):
        for _ in range(3):
            s += [(gc, nf), (gc, nf + gc), (gc, nf + 2 * gc), (gc, nf + 3 * gc), (nf, nf + 4 * gc)]
    s += [(nf, nf)] * 4 + [(3, nf)]
    return s
