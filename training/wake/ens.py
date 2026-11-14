"""Several heads averaged (their logits): python ens.py name=a.pt,b.pt,c.pt ...   Prints the same table as roc.py."""
import sys, os, numpy as np
import evalmodel as E, roc
from feat import Feats
f = Feats(2); P = {"vctk": E.pos_feats(f, "vctk"), "kokoro": E.pos_feats(f, "kokoro")}; Ng = E.neg_feats(f)
cache = {}
def lg(path):
    if path not in cache:
        m = E.load_model(path)
        pos = []; grp = []
        for fam in P:
            for cond in ("near", "far", "noise10", "echo"):
                for key, fr in P[fam].get(cond, {}).items():
                    if key.split("-")[0] in roc.MAIN: pos.append(roc.logits(m, fr)); grp.append(roc.group(fam, key))
        cache[path] = (pos, np.array(grp), {n: roc.logits(m, fr) for n, fr in Ng.items()})
    return cache[path]
hours = sum(len(fr) for fr in Ng.values()) * 0.08 / 3600
for arg in sys.argv[1:]:
    name, paths = arg.split("="); parts = [lg(p) for p in paths.split(",")]
    pos = [np.mean([p[0][i] for p in parts], axis=0) for i in range(len(parts[0][0]))]; grp = parts[0][1]
    neg = {n: np.mean([p[2][n] for p in parts], axis=0) for n in parts[0][2]}
    rows = []
    for t in np.arange(-6.0, 8.01, 0.25):
        w = np.array([E.woke(s, t) for s in pos]); fa = sum(E.events(s, t) for s in neg.values())
        rows.append((float(t), float(w.mean()), fa, fa / hours, {g: float(w[grp == g].mean()) for g in sorted(set(grp))}))
    rows = [r for r in rows if r[2] < 500]
    print(f"{name:10s} " + " | ".join(f"<={k} false: {r[1]:.3f} (thr {r[0]:+.2f}; " + " ".join(f"{g.replace('kokoro-', 'k-')} {v:.2f}" for g, v in r[4].items()) + ")" for k in (0, 1, 3, 6) for r in [roc.at(rows, k)]))
