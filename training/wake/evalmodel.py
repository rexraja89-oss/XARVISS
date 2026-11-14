"""Held-out check of a trained head: voices and recordings it never saw. python evalmodel.py <model.pt> [quick]"""
import sys, os, glob, numpy as np, torch, collections
sys.argv_keep = sys.argv; 
from feat import Feats
import build
torch.set_num_threads(2)
HERE = os.path.dirname(os.path.abspath(__file__)); K = os.path.join(HERE, "..", "kws", "audio"); T = os.path.join(HERE, "testfeats"); os.makedirs(T, exist_ok=True)
def load_model(path):
    import torch.nn as nn
    ck = torch.load(path, weights_only=False)
    class Net(nn.Module):
        def __init__(self, layer, blocks):
            super().__init__()
            self.layer1 = nn.Linear(16 * 96, layer); self.norm1 = nn.LayerNorm(layer)
            self.blocks = nn.ModuleList([nn.ModuleList([nn.Linear(layer, layer), nn.LayerNorm(layer)]) for _ in range(blocks)])
            self.last = nn.Linear(layer, 1)
        def forward(self, x):
            x = torch.relu(self.norm1(self.layer1(x.flatten(1))))
            for lin, norm in self.blocks: x = torch.relu(norm(lin(x)))
            return torch.sigmoid(self.last(x))
    m = Net(ck["layer"], ck["blocks"]); m.load_state_dict(ck["state"]); m.eval(); return m
@torch.no_grad()
def scores(model, frames):
    """[n, 96] embeddings -> a score for every 80 ms step (window of 16)."""
    if len(frames) < 16: return np.zeros(0, np.float32)
    w = np.lib.stride_tricks.sliding_window_view(np.asarray(frames, dtype=np.float32), (16, 96))[:, 0]
    out = [model(torch.from_numpy(np.ascontiguousarray(w[i:i + 32768]))).squeeze(1).numpy() for i in range(0, len(w), 32768)]
    return np.concatenate(out)
def fires(s, thr):
    """The phone's rule (HitRule): two of the last three steps at or over the threshold."""
    a = (s >= thr).astype(np.int32)
    if len(a) < 3: return np.zeros(len(a), bool)
    c = a.copy(); c[1:] += a[:-1]; c[2:] += a[:-2]
    return c >= 2
def woke(s, thr): return bool(fires(s, thr).any())
def events(s, thr, gap=25):
    """Separate wake-ups by the phone's rule, then quiet for 2 s."""
    n = 0; last = -10 ** 9
    for i in np.nonzero(fires(s, thr))[0]:
        if i - last > gap: n += 1
        last = i
    return n
def pos_condition(a, cond, rng):
    """a: float clip at 16 kHz -> int16 recording with the word in the middle, under a listening condition."""
    a = a / (np.sqrt(np.mean(a ** 2)) + 1e-9)
    level = {"near": -20, "far": -36, "noise10": -24, "noise5": -24, "echo": -26, "babble8": -24}[cond]
    a = a * build.db(level); r = build.rms(a)
    buf = np.concatenate([np.zeros(16000, np.float32), a, np.zeros(9600, np.float32)])
    if cond == "echo":
        rr = build.rirs(); buf = build.fftconvolve(buf, rr[int(rng.integers(len(rr)))])[:len(buf)].astype(np.float32)
    if cond.startswith("noise"):
        bg = build.crop("musan", len(buf), rng); buf = buf + bg * (r / build.rms(bg) / build.db(float(cond[5:])))
    if cond == "babble8":
        bg = build.crop("ls_test", len(buf), rng); buf = buf + bg * (r / build.rms(bg) / build.db(8))
    buf = buf + build.colored(len(buf), rng) * build.db(-65)
    return (np.clip(buf, -1, 1) * 32767).astype(np.int16)
def pos_feats(f, family="vctk"):
    """Embeddings of held-out positives (VCTK or Kokoro voices) under each condition (cached)."""
    out = {}; src = "pos" if family == "vctk" else "kpos"
    for cond in ["near", "far", "noise10", "noise5", "echo", "babble8"]:
        p = f"{T}/{family}_pos_{cond}.npz"
        if not os.path.exists(p):
            rng = np.random.default_rng(5); d = {}
            for path in sorted(glob.glob(f"{K}/{src}/*.npy")):
                key = os.path.basename(path)[:-4]
                if cond != "near" and not key.endswith("-1.0"): continue
                d[key] = f.stream(pos_condition(np.load(path), cond, rng)).astype(np.float16)
            if not d: continue
            np.savez(p, **d)
        out[cond] = dict(np.load(p))
    return out
def neg_feats(f):
    out = {}
    for name in ["ls_test", "hi_test", "inen_test", "mucs_test", "vctk_neg", "kokoro_neg"]:
        p = f"{T}/neg_{name}.npy"
        if not os.path.exists(p):
            if name in ("vctk_neg", "kokoro_neg"):
                files = sorted(glob.glob(f"{K}/{'neg' if name == 'vctk_neg' else 'kneg'}/*.npy"))
                if not files: continue
                x = np.concatenate([np.concatenate([np.load(q), np.zeros(4000, np.float32)]) for q in files])
                x = (np.clip(x / (np.abs(x).max() + 1e-9) * 0.5, -1, 1) * 32767).astype(np.int16)
            elif os.path.exists(f"{HERE}/data/pools/{name}.npy"):
                x = np.load(f"{HERE}/data/pools/{name}.npy")
            else: continue
            np.save(p, f.stream(x).astype(np.float16)); print("cached", name, flush=True)
        out[name] = np.load(p)
    return out
if __name__ == "__main__":
    f = Feats(2); P = {"vctk": pos_feats(f, "vctk"), "kokoro": pos_feats(f, "kokoro")}; Ng = neg_feats(f)
    for path in sys.argv[1:]:
        model = load_model(path); print("=====", path)
        for fam, PF in P.items():
            for cond, d in PF.items():
                by = collections.defaultdict(list)
                for key, fr in d.items(): by[key.split("-")[0]].append(scores(model, fr))
                allv = [v for vs in by.values() for v in vs]
                rec = lambda vs, t: float(np.mean([woke(v, t) for v in vs]))
                line = "  ".join(f"{k}:{rec(v, 0.5):.2f}" for k, v in sorted(by.items()))
                print(f"{fam:6s} {cond:8s} recall @0.5 {rec(allv, 0.5):.3f} (@0.35 {rec(allv, 0.35):.3f}, @0.7 {rec(allv, 0.7):.3f})  {line}")
        tot = collections.Counter(); hours = 0
        for name, fr in Ng.items():
            s_ = scores(model, fr); h = len(fr) * 0.08 / 3600; hours += h
            for t in (0.35, 0.5, 0.7): tot[t] += events(s_, t)
            print(f"false wakes {name:10s} {h:5.2f} h: " + "  ".join(f"@{t}: {events(s_, t)} ({events(s_, t) / h:.2f}/h)" for t in (0.35, 0.5, 0.7)))
        print(f"false wakes ALL        {hours:5.2f} h: " + "  ".join(f"@{t}: {tot[t]} ({tot[t] / hours:.2f}/h)" for t in (0.35, 0.5, 0.7)))
