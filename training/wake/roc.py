"""Recall against false wakes across thresholds (in logit space, so saturated scores still separate), on the held-out
   sets, by the phone's rule. python roc.py <model.pt> ..."""
import sys, os, numpy as np, torch, collections
import evalmodel as E
from feat import Feats
MAIN = {"zarvis", "jarvis", "hey_zarvis", "ok_jarvis", "end_zarvis", "zarviss", "jarviss"}
def logits(model, frames):
    if len(frames) < 16: return np.zeros(0, np.float32)
    w = np.lib.stride_tricks.sliding_window_view(np.asarray(frames, dtype=np.float32), (16, 96))[:, 0]
    out = []
    with torch.no_grad():
        for i in range(0, len(w), 32768):
            x = torch.from_numpy(np.ascontiguousarray(w[i:i + 32768]))
            h = torch.relu(model.norm1(model.layer1(x.flatten(1))))
            for lin, norm in model.blocks: h = torch.relu(norm(lin(h)))
            out.append(model.last(h).squeeze(1).numpy())
    return np.concatenate(out)
def group(fam, key):
    if fam == "vctk": return "vctk"
    sid = int(key.split("-")[1])
    return "kokoro-en" if sid < 28 else ("kokoro-hindi" if 32 <= sid <= 35 else "kokoro-other")
def curve(model, P, Ng, conds=("near", "far", "noise10", "echo")):
    pos = []; grp = []
    for fam in P:
        for cond in conds:
            for key, fr in P[fam].get(cond, {}).items():
                if key.split("-")[0] in MAIN: pos.append(logits(model, fr)); grp.append(group(fam, key))
    neg = {n: logits(model, fr) for n, fr in Ng.items()}; hours = sum(len(fr) for fr in Ng.values()) * 0.08 / 3600
    rows = []; grp = np.array(grp)
    for t in np.arange(-3.0, 10.5, 0.5):
        w = np.array([E.woke(s, t) for s in pos]); fa = sum(E.events(s, t) for s in neg.values())
        rows.append((float(t), float(w.mean()), fa, fa / hours, {g: float(w[grp == g].mean()) for g in sorted(set(grp))}))
    return rows, len(pos), hours
def at(rows, max_fa):
    """The most sensitive threshold with at most [max_fa] false wakes."""
    ok = [r for r in rows if r[2] <= max_fa]
    return ok[0] if ok else rows[-1]
if __name__ == "__main__":
    f = Feats(2); P = {"vctk": E.pos_feats(f, "vctk"), "kokoro": E.pos_feats(f, "kokoro")}; Ng = E.neg_feats(f)
    for path in sys.argv[1:]:
        rows, n, hours = curve(E.load_model(path), P, Ng)
        line = " | ".join(f"<={k} false: recall {r[1]:.3f} (thr {r[0]:+.1f}; " + " ".join(f"{g.replace('kokoro-', 'k-')} {v:.2f}" for g, v in r[4].items()) + ")" for k in (0, 1, 3, 6) for r in [at(rows, k)])
        print(f"{os.path.basename(path):12s} {line}")
