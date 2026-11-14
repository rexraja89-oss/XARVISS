"""Which test sentences wake a model (the VCTK decoy set: 121 sentences x 8 voices). python fpwords.py <model.pt>"""
import sys, glob, os, numpy as np, collections
import evalmodel as E
from feat import Feats
f = Feats(2); model = E.load_model(sys.argv[1])
lines = [l.strip() for l in open(f"{E.HERE}/../kws/neg.txt") if l.strip()]
cache = f"{E.T}/vctk_neg_clips.npz"
if not os.path.exists(cache):
    d = {}
    for p in sorted(glob.glob(f"{E.K}/neg/*.npy")):
        a = np.load(p); a = (np.clip(a / (np.abs(a).max() + 1e-9) * 0.5, -1, 1) * 32767).astype(np.int16)
        d[os.path.basename(p)[:-4]] = f.stream(np.concatenate([np.zeros(16000, np.int16), a, np.zeros(8000, np.int16)])).astype(np.float16)
    np.savez(cache, **d)
d = dict(np.load(cache)); hits = collections.Counter(); n = collections.Counter()
for k, fr in d.items():
    i = int(k.split("-")[0]); n[i] += 1
    if E.woke(E.scores(model, fr), 0.5): hits[i] += 1
print("sentences that wake it:", sum(1 for i in hits), "of", len(n), "| clips:", sum(hits.values()), "of", sum(n.values()))
for i, c in hits.most_common(40): print(f"  {c}/{n[i]}  {lines[i]}")
