"""Real speech and noise as long 16 kHz int16 recordings (one .npy per pool): python pools.py <pool> ..."""
import sys, os, io, glob, tarfile, numpy as np, soundfile as sf, pyarrow.parquet as pq
from scipy.signal import resample_poly
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
def to16k(a, sr):
    if a.ndim > 1: a = a.mean(axis=1)
    if sr != 16000:
        from math import gcd
        g = gcd(sr, 16000); a = resample_poly(a, 16000 // g, sr // g)
    return np.clip(a * 32767, -32767, 32767).astype(np.int16)
def from_parquet(paths, max_hours=None):
    for p in paths:
        f = pq.ParquetFile(p)
        for b in f.iter_batches(batch_size=64, columns=["audio"]):
            for row in b.column("audio").to_pylist():
                try:
                    a, sr = sf.read(io.BytesIO(row["bytes"]), dtype="float32")
                    yield to16k(a, sr)
                except Exception as e:
                    print("skip", str(e)[:60])
def from_tar(path):
    with tarfile.open(path) as t:
        for m in t:
            if m.name.endswith(".wav"):
                a, sr = sf.read(io.BytesIO(t.extractfile(m).read()), dtype="float32"); yield to16k(a, sr)
def from_wavs(paths):
    for p in paths:
        try:
            a, sr = sf.read(p, dtype="float32"); yield to16k(a, sr)
        except Exception as e: print("skip", p, str(e)[:60])
POOLS = {
    "ls_val": lambda: from_parquet([f"{D}/ls/validation.clean.parquet"]),
    "ls_test": lambda: from_parquet([f"{D}/ls/test.clean.parquet"]),
    "hi_dev": lambda: from_tar(f"{D}/fleurs/hi_dev.tar.gz"),
    "hi_test": lambda: from_tar(f"{D}/fleurs/hi_test.tar.gz"),
    "hi_train": lambda: from_tar(f"{D}/fleurs/hi_train.tar.gz"),
    "inen_train": lambda: from_parquet([f"{D}/inen/0.parquet", f"{D}/inen/1.parquet"]),
    "inen_test": lambda: from_parquet([f"{D}/inen/5.parquet", f"{D}/inen/6.parquet"]),
    "mucs_train": lambda: from_parquet([f"{D}/mucs/train0.parquet"]),
    "mucs_test": lambda: from_parquet([f"{D}/mucs/test0.parquet", f"{D}/mucs/test1.parquet"]),
    "musan": lambda: from_wavs(sorted(glob.glob(f"{D}/musan/*.wav"))),
    "esc50": lambda: from_parquet(sorted(glob.glob(f"{D}/esc50/*.parquet"))),
}
if __name__ == "__main__":
    os.makedirs(f"{D}/pools", exist_ok=True)
    for name in sys.argv[1:]:
        out = f"{D}/pools/{name}.npy"
        if os.path.exists(out): print(name, "exists"); continue
        parts = []; n = 0; gap = np.zeros(4000, np.int16)
        for a in POOLS[name]():
            parts.append(a); parts.append(gap); n += 1
        x = np.concatenate(parts); np.save(out, x)
        print(name, n, "clips", round(len(x) / 16000 / 3600, 2), "hours", flush=True)
