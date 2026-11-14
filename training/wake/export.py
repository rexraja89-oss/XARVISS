"""Writes the chosen heads (averaged on the phone) as the file WakeModel.kt reads ("XWK2"), with the chosen threshold
   moved to score 0.5, plus test vectors that pin the Kotlin maths to PyTorch's.
   python export.py <a.pt,b.pt,...> <threshold logit> <xarvis.bin> <wake_vectors.bin>"""
import sys, struct, numpy as np, torch
import evalmodel as E, roc
paths, thr, out, vec = sys.argv[1].split(","), float(sys.argv[2]), sys.argv[3], sys.argv[4]
models = [E.load_model(p) for p in paths]
with open(out, "wb") as f:
    f.write(b"XWK2"); f.write(struct.pack("<iii", 16, 96, len(models)))
    for m in models:
        sd = m.state_dict()
        layers = [("layer1", "norm1")] + [(f"blocks.{i}.0", f"blocks.{i}.1") for i in range(len(m.blocks))] + [("last", None)]
        f.write(struct.pack("<i", len(layers)))
        for lin, norm in layers:
            w = sd[lin + ".weight"].numpy().astype("<f4"); b = sd[lin + ".bias"].numpy().astype("<f4").copy()
            if lin == "last": b = b - thr          # the threshold becomes logit 0 = score 0.5
            f.write(struct.pack("<iii", w.shape[1], w.shape[0], 1 if norm else 0)); f.write(w.tobytes()); f.write(b.tobytes())
            if norm: f.write(sd[norm + ".weight"].numpy().astype("<f4").tobytes()); f.write(sd[norm + ".bias"].numpy().astype("<f4").tobytes())
rng = np.random.default_rng(1)
pos, neg = np.load("feats/pos_val.npy"), np.load("feats/adv_val.npy")
x = np.concatenate([pos[rng.choice(len(pos), 10, replace=False)], neg[rng.choice(len(neg), 8, replace=False)]]).astype(np.float32)
with torch.no_grad():
    lg = np.mean([roc.logits(m, x) if False else m.last(torch.relu(m.norm1(m.layer1(torch.from_numpy(x).flatten(1))))).squeeze(1).numpy() if len(m.blocks) == 0 else None for m in models], axis=0) if False else None
    ls = []
    for m in models:
        h = torch.relu(m.norm1(m.layer1(torch.from_numpy(x).flatten(1))))
        for lin, norm in m.blocks: h = torch.relu(norm(lin(h)))
        ls.append(m.last(h).squeeze(1).numpy() - thr)
    y = (1 / (1 + np.exp(-np.mean(ls, axis=0)))).astype("<f4")
with open(vec, "wb") as f:
    f.write(struct.pack("<i", len(x)))
    for a, s in zip(x, y): f.write(a.astype("<f4").tobytes()); f.write(struct.pack("<f", float(s)))
import os; print("heads", len(models), "bytes", os.path.getsize(out), "scores", np.round(y, 3))
