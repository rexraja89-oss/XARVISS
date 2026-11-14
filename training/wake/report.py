"""The full held-out report for a set of heads averaged together, at one threshold (in logits):
   python report.py <a.pt,b.pt,...> <threshold>
   Wake clips by voice family, condition and wording; the Hindi voice kept out of training; false wakes per set."""
import sys, os, glob, numpy as np, collections
import evalmodel as E, roc
from feat import Feats
paths = sys.argv[1].split(","); thr = float(sys.argv[2])
models = [E.load_model(p) for p in paths]
def lg(fr): return np.mean([roc.logits(m, fr) for m in models], axis=0)
f = Feats(2); P = {"vctk": E.pos_feats(f, "vctk"), "kokoro": E.pos_feats(f, "kokoro")}; Ng = E.neg_feats(f)
print(f"{len(models)} heads, threshold {thr:+.2f}")
for fam in P:
    for cond, d in P[fam].items():
        by = collections.defaultdict(list)
        for key, fr in d.items(): by[key.split("-")[0]].append(E.woke(lg(fr), thr))
        main = [v for k, vs in by.items() if k in roc.MAIN for v in vs]
        print(f"  {fam:6s} {cond:8s} main {np.mean(main):.2f} | " + " ".join(f"{k}:{np.mean(v):.2f}" for k, v in sorted(by.items())))
d = dict(np.load(f"{E.T}/hi2_test.npz")); texts = dict(l.rstrip("\n").split("\t") for l in open("clips/hi2_test/manifest-hi.tsv")); by = collections.defaultdict(list)
for k, fr in d.items():
    cond, name = k.split("|"); t = texts[name]; by[(cond, "alone" if " " not in t else "in a phrase")].append(E.woke(lg(fr), thr))
print("  Hindi voice kept out of training: " + "  ".join(f"{c} {k}: {np.mean(v):.2f}" for (c, k), v in sorted(by.items())))
tot = 0; hours = 0
for n, fr in Ng.items():
    s = lg(fr); h = len(fr) * 0.08 / 3600; e = E.events(s, thr); tot += e; hours += h
    print(f"  false wakes {n:10s} {h:5.2f} h: {e}")
val = np.load(f"{E.HERE}/data/validation_set_features.npy"); e = E.events(lg(val), thr)
print(f"  false wakes held-out speech total: {tot} in {hours:.1f} h ({tot / hours:.2f}/h) | openWakeWord's validation recording: {e} in {len(val) * 0.08 / 3600:.1f} h")
