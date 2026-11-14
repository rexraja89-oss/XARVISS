"""openWakeWord features: 16 kHz int16 audio -> mel -> 96-d speech embeddings (one every 80 ms)."""
import os, numpy as np, onnxruntime as ort
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "openWakeWord", "openwakeword", "resources", "models")
class Feats:
    def __init__(self, threads=2):
        so = ort.SessionOptions(); so.intra_op_num_threads = threads; so.inter_op_num_threads = 1
        self.mel = ort.InferenceSession(os.path.join(R, "melspectrogram.onnx"), so, providers=["CPUExecutionProvider"])
        self.emb = ort.InferenceSession(os.path.join(R, "embedding_model.onnx"), so, providers=["CPUExecutionProvider"])
        self.mel_in = self.mel.get_inputs()[0].name; self.emb_in = self.emb.get_inputs()[0].name
    def melspec(self, x):
        """[B, T] int16-range audio -> [B, frames, 32]"""
        out = self.mel.run(None, {self.mel_in: np.asarray(x, dtype=np.float32)})[0]
        return out.reshape(out.shape[0], -1, 32) / 10.0 + 2.0
    def embed_mel(self, mel, bs=512):
        """[B, frames, 32] -> [B, n, 96]: a 76-frame window every 8 frames"""
        B, F, _ = mel.shape
        starts = list(range(0, F - 76 + 1, 8))
        w = np.stack([mel[:, s:s + 76, :] for s in starts], axis=1).reshape(-1, 76, 32, 1).astype(np.float32)
        outs = [self.emb.run(None, {self.emb_in: w[i:i + bs]})[0].reshape(-1, 96) for i in range(0, len(w), bs)]
        return np.concatenate(outs).reshape(B, len(starts), 96)
    def clips(self, x, bs=64):
        """[N, T] int16 clips (same length) -> [N, n, 96]"""
        return np.concatenate([self.embed_mel(self.melspec(x[i:i + bs])) for i in range(0, len(x), bs)])
    def stream(self, x, chunk=16000 * 30):
        """One long int16 recording -> [n, 96] embeddings, one per 80 ms (as the phone computes them live)."""
        out = []; step = chunk - (chunk % 1280)
        for i in range(0, max(1, len(x) - 12400), step):
            seg = x[i:i + step + 12400 - 1280 + 160 * 3]  # overlap so windows line up across segments
            if len(seg) < 12400 + 480: break
            m = self.melspec(seg[None, :])
            out.append(self.embed_mel(m)[0])
        return np.concatenate(out) if out else np.zeros((0, 96), np.float32)
if __name__ == "__main__":
    import time
    f = Feats(4)
    for i in f.mel.get_inputs() + f.mel.get_outputs() + f.emb.get_inputs() + f.emb.get_outputs(): print(i.name, i.shape)
    x = (np.random.randn(8, 32000) * 3000).astype(np.int16)
    m = f.melspec(x); print("mel", m.shape)
    t = time.time(); e = f.clips(np.repeat(x, 32, 0)); print("emb", e.shape, "x realtime", 256 * 2 / (time.time() - t))
    one = f.melspec(x[:1]); print(np.abs(one - m[:1]).max())
    s = f.stream(np.concatenate(list(x))); print("stream", s.shape)
