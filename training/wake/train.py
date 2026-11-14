"""Trains the small "Xarvis" detector head on openWakeWord features (after openWakeWord's own recipe).
   python train.py <name> [steps] [layer] [blocks] [max_neg_weight]"""
import sys, os, glob, copy, time, json, numpy as np, torch, torch.nn as nn
torch.set_num_threads(int(os.environ.get("XT", "1")))
name = sys.argv[1]; STEPS = int(sys.argv[2]) if len(sys.argv) > 2 else 30000
LAYER = int(sys.argv[3]) if len(sys.argv) > 3 else 32; BLOCKS = int(sys.argv[4]) if len(sys.argv) > 4 else 1
MAXW = float(sys.argv[5]) if len(sys.argv) > 5 else 1500
F = os.environ.get("XF") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "feats"); D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
SEED = int(os.environ.get("XSEED", "0")); rng = np.random.default_rng(SEED); torch.manual_seed(SEED)
class Net(nn.Module):
    def __init__(self, layer, blocks):
        super().__init__()
        self.layer1 = nn.Linear(16 * 96, layer); self.norm1 = nn.LayerNorm(layer)
        self.blocks = nn.ModuleList([nn.ModuleList([nn.Linear(layer, layer), nn.LayerNorm(layer)]) for _ in range(blocks)])
        self.last = nn.Linear(layer, 1)
    def logits(self, x):
        x = torch.relu(self.norm1(self.layer1(x.flatten(1))))
        for lin, norm in self.blocks: x = torch.relu(norm(lin(x)))
        return self.last(x)
    def forward(self, x): return torch.sigmoid(self.logits(x))
def cat(pattern, dtype=np.float32):
    parts = [np.load(p) for p in sorted(glob.glob(os.path.join(F, pattern)))]
    return np.concatenate(parts).astype(dtype) if parts else np.zeros((0, 16, 96), dtype)
class Lazy:
    """Several big feature files read from disk as one array (the system's file cache is shared between runs)."""
    def __init__(self, parts):
        self.parts = parts; self.off = np.cumsum([0] + [len(p) for p in parts]); self.shape = (int(self.off[-1]), 16, 96)
    def __len__(self): return int(self.off[-1])
    def __getitem__(self, idx):
        out = np.empty((len(idx), 16, 96), np.float16); k = np.searchsorted(self.off, idx, side="right") - 1
        for j in np.unique(k):
            m = k == j; out[m] = self.parts[j][idx[m] - self.off[j]]
        return out
def lazy(paths, raw=False):
    return Lazy([(np.memmap(p, dtype=np.float16, mode="r").reshape(-1, 16, 96) if raw else np.load(p, mmap_mode="r")) for p in paths])
# XPOS / XADV (e.g. "a,c") pick which positive / decoy files to use: for finding out what a set does to the result.
def pick(kind, env):
    which = os.environ.get(env)
    if not which: return cat(f"{kind}_train*.npy")
    parts = [np.load(os.path.join(F, f"{kind}_train_{w}.npy")) for w in which.split(",")]
    return np.concatenate(parts).astype(np.float32)
pos = pick("pos", "XPOS"); adv = pick("adv", "XADV")
# XREAL picks the real-speech file(s) (default: every real_train*.npy); XNOISE=0 leaves the noise windows out.
real = lazy(sorted(glob.glob(os.path.join(F, os.environ.get("XREAL", "real_train*.npy")))))
noise = lazy([] if os.environ.get("XNOISE") == "0" else sorted(glob.glob(os.path.join(F, "noise_train*.npy"))))
acav = lazy([p for p in sorted(glob.glob(f"{D}/acav/part*.bin")) if os.path.exists(p + ".done")], raw=True)
pos_val = cat("pos_val*.npy"); adv_val = cat("adv_val*.npy")
val_frames = np.load(f"{D}/validation_set_features.npy"); VAL_H = len(val_frames) * 0.08 / 3600
val_win = np.lib.stride_tricks.sliding_window_view(val_frames, (16, 96))[:, 0]
print(f"pos {pos.shape} adv {adv.shape} real {real.shape} noise {noise.shape} acav {acav.shape} | val pos {pos_val.shape} adv {adv_val.shape} fp-set {VAL_H:.1f} h", flush=True)
NB = [int(v) for v in os.environ.get("XB", "1024,320,64,160,160").split(",")]
SETS = [(acav, NB[0], 0), (real, NB[1], 0), (noise, NB[2], 0), (adv, NB[3], 0), (pos, NB[4], 1)]
SETS = [s for s in SETS if len(s[0])]
def batch():
    xs, ys = [], []
    for data, n, label in SETS:
        idx = np.sort(rng.integers(0, len(data), n)); xs.append(np.asarray(data[idx], dtype=np.float32)); ys.append(np.full(n, label, np.float32))
    return torch.from_numpy(np.concatenate(xs)), torch.from_numpy(np.concatenate(ys))
def lr_at(step, total, warm, hold, target):
    if step < warm: return target * step / max(1, warm)
    if step < warm + hold: return target
    return 0.5 * target * (1 + np.cos(np.pi * (step - warm - hold) / max(1, total - warm - hold)))
@torch.no_grad()
def evaluate(model, thr=0.5):
    model.eval()
    rec = float((model(torch.from_numpy(pos_val)).squeeze(1) >= thr).float().mean()) if len(pos_val) else 0
    advfp = float((model(torch.from_numpy(adv_val)).squeeze(1) >= thr).float().mean()) if len(adv_val) else 0
    fp = 0
    for i in range(0, len(val_win), 65536):
        fp += int((model(torch.from_numpy(np.ascontiguousarray(val_win[i:i + 65536]))).squeeze(1) >= thr).sum())
    model.train()
    return rec, advfp, fp / VAL_H
model = Net(LAYER, BLOCKS); opt = torch.optim.Adam(model.parameters(), lr=1e-4)
history = []; best = []
def run(steps, lr, maxw, tag, val_from):
    weights = np.linspace(1, maxw, steps); t0 = time.time()
    val_steps = set(np.linspace(val_from, steps - 1, 20).astype(int).tolist())
    for step in range(steps):
        x, y = batch()
        for g in opt.param_groups: g["lr"] = lr_at(step, steps, steps // 5, steps // 3, lr)
        opt.zero_grad()
        p = model(x).squeeze(1)
        keep = ((y == 0) & (p >= 0.001)) | ((y == 1) & (p < 0.999))   # only the examples it still gets wrong-ish
        if keep.sum() == 0: continue
        p, yk = p[keep], y[keep]
        w = torch.where(yk == 1, torch.ones_like(yk), torch.full_like(yk, float(weights[step])))
        loss = nn.functional.binary_cross_entropy(p, yk, weight=w)
        loss.backward(); opt.step()
        if step % 1000 == 0: print(f"{tag} step {step} ({time.time() - t0:.0f}s)", flush=True)
        if step in val_steps:
            rec, advfp, fph = evaluate(model)
            history.append((tag, step, rec, advfp, fph)); best.append((copy.deepcopy(model.state_dict()), rec, advfp, fph))
            print(f"{tag} step {step} loss {float(loss):.4f} recall {rec:.3f} adv-fp {advfp:.4f} fp/h {fph:.2f} ({time.time() - t0:.0f}s)", flush=True)
run(STEPS, 1e-4, MAXW, "seq1", int(STEPS * 0.75))
fph1 = min(b[3] for b in best[-20:]); w2 = MAXW * 2 if fph1 > 0.2 else MAXW
run(STEPS // 10, 1e-5, w2, "seq2", 1)
fph2 = min(b[3] for b in best[-20:]); w3 = w2 * 2 if fph2 > 0.2 else w2
run(STEPS // 10, 1e-6, w3, "seq3", 1)
# Average the checkpoints that are good on every count (as openWakeWord does), else take the best single one.
# Keep the checkpoints with the fewest false wakes (within reach of the best one), and of those the ones that
# hear the name best; average them (as openWakeWord does) if that doesn't cost anything.
fps = np.array([b[3] for b in best]); limit = max(0.3, float(fps.min()) * 1.5 + 0.1)
ok = [b for b in best if b[3] <= limit]; top = max(b[1] for b in ok)
good = [b for b in ok if b[1] >= top - 0.01]
single = max(ok, key=lambda b: b[1])[0]
avg = {k: sum(g[0][k] for g in good) / len(good) for k in good[0][0]}
model.load_state_dict(avg); r_avg = evaluate(model)
model.load_state_dict(single); r_one = evaluate(model)
print(f"averaged {len(good)} checkpoints: recall {r_avg[0]:.3f} fp/h {r_avg[2]:.2f} | best single: recall {r_one[0]:.3f} fp/h {r_one[2]:.2f}", flush=True)
if r_avg[2] <= r_one[2] * 1.2 + 0.1 and r_avg[0] >= r_one[0] - 0.005: model.load_state_dict(avg)
for thr in (0.3, 0.5, 0.7, 0.9):
    rec, advfp, fph = evaluate(model, thr); print(f"FINAL thr {thr}: recall {rec:.3f} adv-fp {advfp:.4f} fp/h {fph:.2f} ", flush=True)
os.makedirs("models", exist_ok=True)
torch.save({"state": model.state_dict(), "layer": LAYER, "blocks": BLOCKS, "history": history}, f"models/{name}.pt")
print("saved", f"models/{name}.pt")
