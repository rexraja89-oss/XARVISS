"""Augments clips (level, echo, noise, speech before the word) and saves openWakeWord features.
   python build.py words <clip_dir> <out.npy> <rounds> <nproc>      -- short clips ending at the end of a 2 s window
   python build.py stream <pool,pool> <out.npy> <stride> <variants> -- sliding windows over long real recordings"""
import sys, os, glob, wave, numpy as np, time
from multiprocessing import Pool
from scipy.signal import fftconvolve, butter, sosfilt, resample
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
N = 32000
SPEECH_POOLS = ["ls_val", "hi_dev", "hi_train", "inen_train", "mucs_train"]
NOISE_POOLS = ["musan", "esc50"]
_cache = {}
def pool(name):
    if name not in _cache: _cache[name] = np.load(f"{D}/pools/{name}.npy", mmap_mode="r")
    return _cache[name]
def have(names): return [n for n in names if os.path.exists(f"{D}/pools/{n}.npy")]
def rirs():
    if "rirs" not in _cache:
        out = []
        for p in sorted(glob.glob(f"{D}/rirs/*.wav")):
            import soundfile as sf
            a, sr = sf.read(p, dtype="float32")
            if a.ndim > 1: a = a[:, 0]
            a = a / (np.abs(a).max() + 1e-9); k = int(np.argmax(np.abs(a)))
            out.append(a[max(0, k - 16):k + 8000])  # start at the direct sound, keep half a second of tail
        _cache["rirs"] = out
    return _cache["rirs"]
def load(p):
    with wave.open(p) as w: return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
def crop(name, n, rng):
    x = pool(name); i = int(rng.integers(0, len(x) - n - 1)); return np.asarray(x[i:i + n], dtype=np.float32) / 32768
def rms(x): return float(np.sqrt(np.mean(x ** 2)) + 1e-9)
def db(v): return 10 ** (v / 20)
def colored(n, rng):
    w = rng.standard_normal(n).astype(np.float32)
    if rng.random() < 0.5: w = np.cumsum(w) ; w = w - np.linspace(w[0], w[-1], n); w = w.astype(np.float32)
    return w / (np.abs(w).max() + 1e-9)
def degrade(mix, speech_rms, rng, noise_p=0.8):
    """Background sound, mic hiss, band limits and clipping on a float buffer."""
    n = len(mix)
    if rng.random() < noise_p:
        names = have(NOISE_POOLS) + (have(SPEECH_POOLS) if rng.random() < 0.2 else [])
        bg = crop(names[int(rng.integers(len(names)))], n, rng)
        snr = rng.uniform(-3, 25)
        mix = mix + bg * (speech_rms / rms(bg) / db(snr))
    if rng.random() < 0.5: mix = mix + colored(n, rng) * db(rng.uniform(-75, -48))
    if rng.random() < 0.3:
        lo = rng.uniform(80, 350) if rng.random() < 0.7 else None; hi = rng.uniform(2800, 7000) if rng.random() < 0.7 else None
        if lo: mix = sosfilt(butter(2, lo, "hp", fs=16000, output="sos"), mix)
        if hi: mix = sosfilt(butter(4, hi, "lp", fs=16000, output="sos"), mix)
    if rng.random() < 0.03: mix = np.tanh(mix * rng.uniform(3, 10)) / rng.uniform(1, 3)
    peak = np.abs(mix).max()
    if peak > 0.99: mix = mix * (0.99 / peak)
    return (mix * 32767).astype(np.int16)
def word_clip(x, rng, jitter=0.3):
    """One 2 s training example: the word ends 0-[jitter] s before the end of the window."""
    if rng.random() < 0.6:
        f = rng.uniform(0.88, 1.12); x = resample(x, max(800, int(len(x) / f))).astype(np.float32)
    end = N - int(rng.uniform(0, jitter) * 16000)
    x = x[-end:]; start = end - len(x)
    x = x * (db(rng.uniform(-38, -14)) / rms(x))
    buf = np.zeros(N, np.float32); buf[start:end] = x
    sp = have(SPEECH_POOLS)
    if sp and start > 4000 and rng.random() < 0.35:   # someone (or Rex) talking just before the word
        gap = int(rng.uniform(0.05, 0.5) * 16000); stop = start - gap
        if stop > 3200:
            pre = crop(sp[int(rng.integers(len(sp)))], min(stop, int(rng.uniform(0.4, 1.6) * 16000)), rng)
            pre = pre * (rms(x) * db(rng.uniform(-8, 3)) / rms(pre)); buf[stop - len(pre):stop] += pre
    if rng.random() < 0.5:
        r = rirs(); buf = fftconvolve(buf, r[int(rng.integers(len(r)))])[:N].astype(np.float32)
        buf = buf * (rms(x) / (rms(buf[start:end]) + 1e-9))
    return degrade(buf, rms(x), rng)
def _words(args):
    files, seed, rounds = args
    from feat import Feats
    f = Feats(1); rng = np.random.default_rng(seed); out = []
    for r in range(rounds):
        for i in range(0, len(files), 64):
            # "posc" clips were cut inside a sentence, just after the name: they already carry what follows it.
            batch = np.stack([word_clip(load(p), rng, 0.05 if os.path.basename(p).startswith("posc") else 0.3) for p in files[i:i + 64]])
            out.append(f.clips(batch).astype(np.float32))
    return np.concatenate(out)
def _stream(args):
    name, lo, hi, seed, stride, variant = args
    from feat import Feats
    f = Feats(1); rng = np.random.default_rng(seed); out = []
    x = pool(name)
    for i in range(lo, hi, 16000 * 30):
        seg = np.asarray(x[i:i + 16000 * 30 + 12400], dtype=np.float32) / 32768
        if len(seg) < 16000 * 3: continue
        level = rms(seg)
        if variant > 0:
            seg = seg * (db(rng.uniform(-36, -14)) / level); level = rms(seg)
            if rng.random() < 0.4:
                r = rirs(); seg = fftconvolve(seg, r[int(rng.integers(len(r)))])[:len(seg)].astype(np.float32)
            seg16 = degrade(seg, level, rng, noise_p=0.6 if name not in NOISE_POOLS else 0.0)
        else:
            seg16 = (np.clip(seg, -1, 1) * 32767).astype(np.int16)
        e = f.stream(seg16)
        off = int(rng.integers(0, stride))
        w = [e[k:k + 16] for k in range(off, len(e) - 16, stride)]
        if w: out.append(np.stack(w).astype(np.float16))
    return np.concatenate(out) if out else np.zeros((0, 16, 96), np.float16)
if __name__ == "__main__":
    t0 = time.time(); mode = sys.argv[1]
    if mode == "words":
        files = sorted(glob.glob(os.path.join(sys.argv[2], "*.wav"))); rounds = int(sys.argv[4]); nproc = int(sys.argv[5])
        shards = [(files[k::nproc], 1000 + k + 17 * rounds, rounds) for k in range(nproc)]
        with Pool(nproc) as p: parts = p.map(_words, shards)
        x = np.concatenate(parts); np.save(sys.argv[3], x); print(sys.argv[3], x.shape, round(time.time() - t0), "s")
    else:
        names = sys.argv[2].split(","); stride = int(sys.argv[4]); variants = int(sys.argv[5]); nproc = int(os.environ.get("XP", "4")); jobs = []
        for name in names:
            L = len(pool(name)); step = L // nproc
            for v in range(variants):
                for k in range(nproc): jobs.append((name, k * step, min(L, (k + 1) * step), 500 + 31 * k + 7 * v + len(name), stride, v))
        with Pool(nproc) as p: parts = p.map(_stream, jobs)
        x = np.concatenate(parts); np.save(sys.argv[3], x); print(sys.argv[3], x.shape, round(time.time() - t0), "s")
