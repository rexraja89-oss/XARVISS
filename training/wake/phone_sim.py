"""The phone's detector, step for step (a Python mirror of wake/.../WakeDetector.kt + HitRule, on the same TensorFlow
   Lite models the app ships), to check that what the phone computes live matches what the detector was trained and
   tested on.  python phone_sim.py <model.pt> [n_clips]"""
import sys, os, glob, collections, numpy as np, torch
from ai_edge_litert.interpreter import Interpreter
import evalmodel as E
A = "/home/user/xarviss/wake/src/main/assets/wake"
class PhoneDetector:
    CHUNK, WINDOW, MEL_FRAMES, FEATURES = 1280, 1280 + 160 * 3, 76, 16
    def __init__(self, model):
        self.model = model
        self.mel = Interpreter(model_path=f"{A}/melspectrogram.tflite"); self.mel.resize_tensor_input(0, [1, self.WINDOW]); self.mel.allocate_tensors()
        self.emb = Interpreter(model_path=f"{A}/embedding_model.tflite"); self.emb.allocate_tensors()
        self.mi, self.mo = self.mel.get_input_details()[0]["index"], self.mel.get_output_details()[0]["index"]
        self.ei, self.eo = self.emb.get_input_details()[0]["index"], self.emb.get_output_details()[0]["index"]
        self.raw = np.zeros(self.WINDOW, np.float32); self.filled = 0
        self.melf = collections.deque([np.ones(32, np.float32)] * self.MEL_FRAMES, maxlen=self.MEL_FRAMES)
        self.feats = collections.deque(maxlen=self.FEATURES)
        rnd = np.random.default_rng(7)
        for _ in range(self.FEATURES + 12): self.step(rnd.integers(-20, 21, self.CHUNK).astype(np.int16))
        self.quiet_mel = [m.copy() for m in self.melf]; self.quiet_feats = list(self.feats)[-self.FEATURES:]
        self.reset()
    def reset(self):
        self.raw[:] = 0; self.filled = 0
        self.melf = collections.deque(self.quiet_mel, maxlen=self.MEL_FRAMES); self.feats = collections.deque(self.quiet_feats, maxlen=self.FEATURES)
    def step(self, chunk):
        self.raw[:-self.CHUNK] = self.raw[self.CHUNK:]; self.raw[-self.CHUNK:] = chunk.astype(np.float32)
        self.filled = min(self.WINDOW, self.filled + self.CHUNK)
        if self.filled < self.WINDOW: return False
        self.mel.set_tensor(self.mi, self.raw[None, :]); self.mel.invoke()
        for fr in self.mel.get_tensor(self.mo).reshape(-1, 32): self.melf.append(fr / 10.0 + 2.0)
        self.emb.set_tensor(self.ei, np.stack(self.melf)[None, :, :, None].astype(np.float32)); self.emb.invoke()
        self.feats.append(self.emb.get_tensor(self.eo).reshape(96).copy())
        return True
    def process(self, chunk):
        if not self.step(chunk) or len(self.feats) < self.FEATURES: return 0.0
        with torch.no_grad(): return float(self.model(torch.from_numpy(np.stack(self.feats)[None].astype(np.float32)))[0, 0])
def run(det, audio):
    det.reset(); out = []
    for i in range(0, len(audio) - det.CHUNK + 1, det.CHUNK): out.append(det.process(audio[i:i + det.CHUNK]))
    return np.array(out, np.float32)
if __name__ == "__main__":
    import build
    from feat import Feats
    model = E.load_model(sys.argv[1]); n = int(sys.argv[2]) if len(sys.argv) > 2 else 150
    det = PhoneDetector(model); f = Feats(1); rng = np.random.default_rng(3)
    files = sorted(glob.glob(f"{E.K}/pos/*-1.0.npy")); files = [files[i] for i in rng.choice(len(files), n, replace=False)]
    agree = 0; phone_hits = 0; batch_hits = 0; diffs = []
    for p in files:
        a = E.pos_condition(np.load(p), "near" if rng.random() < 0.5 else "noise10", rng)
        a = np.concatenate([(rng.integers(-15, 16, 16000 * 2)).astype(np.int16), a])   # two quiet seconds first, as after the gate opens
        sp = run(det, a); sb = E.scores(model, f.stream(a))
        hp, hb = E.woke(sp, 0.5), E.woke(sb, 0.5); phone_hits += hp; batch_hits += hb; agree += (hp == hb); diffs.append(abs(float(sp.max()) - float(sb.max())))
    print(f"{n} clips: the phone's live path wakes on {phone_hits}, the training/test path on {batch_hits}; same answer on {agree} ({agree / n:.1%}); peak score differs by {np.mean(diffs):.3f} on average (max {np.max(diffs):.3f})")
    neg = np.load(f"{E.HERE}/data/pools/ls_test.npy", mmap_mode="r"); seg = np.asarray(neg[:16000 * 600]); sp = run(det, seg); sb = E.scores(model, f.stream(seg))
    print(f"10 min of real speech: phone path false wakes {E.events(sp, 0.5)}, test path {E.events(sb, 0.5)}; highest score {sp.max():.3f} vs {sb.max():.3f}")
