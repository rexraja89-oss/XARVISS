"""How well a model hears the name said the Hindi way (three Piper Hindi voices: zaːrʋɪs / ɟaːrʋɪs), near and noisy.
   python hitest.py <model.pt>:<logit threshold> ..."""
import sys, os, glob, wave, numpy as np, collections
import evalmodel as E, roc, build
from feat import Feats
SRC = "clips/hi2_test"   # a Hindi voice (rohan) kept out of training
cache = f"{E.T}/hi2_test.npz"; texts = dict(l.rstrip("\n").split("\t") for l in open(f"{SRC}/manifest-hi.tsv"))
if not os.path.exists(cache):
    f = Feats(2); rng = np.random.default_rng(9); d = {}
    files = sorted(glob.glob(f"{SRC}/*.wav"))
    for p in files:
        with wave.open(p) as w: a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
        for cond in ("near", "noise10"):
            d[f"{cond}|{os.path.basename(p)}"] = f.stream(E.pos_condition(a, cond, rng)).astype(np.float16)
    np.savez(cache, **d)
d = dict(np.load(cache))
for arg in sys.argv[1:]:
    path, thr = arg.split(":"); thr = float(thr); model = E.load_model(path); by = collections.defaultdict(list)
    for k, fr in d.items():
        cond, name = k.split("|"); t = texts[name]; kind = "alone" if " " not in t else "in a phrase"
        by[(cond, "z" if "ज़" in t else "j", kind)].append(E.woke(roc.logits(model, fr), thr))
    allv = [v for vs in by.values() for v in vs]
    print(f"{os.path.basename(path):10s} thr {thr:+.1f}: all {np.mean(allv):.2f} | " + "  ".join(f"{c} {z}-{k}: {np.mean(v):.2f}" for (c, z, k), v in sorted(by.items())))
