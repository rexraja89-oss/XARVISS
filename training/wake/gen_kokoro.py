"""Held-out test voices (Kokoro, 54 speakers incl. Hindi-, Spanish-, French-accented ones): never used for training.
   Run with the kws venv: python gen_kokoro.py"""
import os, sys, time, numpy as np, sherpa_onnx
HERE = os.path.dirname(os.path.abspath(__file__)); d = f"{HERE}/tts/kokoro-multi-lang-v1_0"; K = f"{HERE}/../kws/audio"
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
    model=f"{d}/model.onnx", voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
    lexicon=f"{HERE}/tts/kokoro-us2.txt,{HERE}/tts/kokoro-gb2.txt"), num_threads=1, provider="cpu")))
def say(text, sid, speed):
    a = tts.generate(text, sid=sid, speed=speed); x = np.array(a.samples, dtype=np.float32)
    if a.sample_rate == 0 or len(x) == 0: return None
    n = int(len(x) * 16000 / a.sample_rate); return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
os.makedirs(f"{K}/kpos", exist_ok=True); os.makedirs(f"{K}/kneg", exist_ok=True); t0 = time.time()
WORDS = {"zarvis": "zarvis.", "jarvis": "jarvis.", "zarviss": "zarviss.", "jarviss": "jarviss.", "zarwis": "zarwis.", "jarwis": "jarwis.", "hey_zarvis": "hey zarvis.", "ok_jarvis": "okay jarvis.",
         "zarvis_q": "zarvis, what time is it?", "jarvis_q": "jarvis, call my wife.", "end_zarvis": "are you there, zarvis?"}
for sid in range(tts.num_speakers):
    for name, text in WORDS.items():
        for sp in ((0.9, 1.0, 1.15) if name in ("zarvis", "jarvis") else (1.0,)):
            p = f"{K}/kpos/{name}-{sid}-{sp}.npy"
            if os.path.exists(p): continue
            a = say(text, sid, sp)
            if a is not None: np.save(p, a)
    print("pos", sid, round(time.time() - t0), flush=True)
lines = [l.strip() for l in open(f"{HERE}/../kws/neg.txt") if l.strip()]
for i, text in enumerate(lines):
    for k in range(3):
        sid = (i * 5 + k * 17) % tts.num_speakers; a = say(text, sid, 1.0)
        if a is not None: np.save(f"{K}/kneg/{i}-{sid}.npy", a)
    if i % 20 == 0: print("neg", i, round(time.time() - t0), flush=True)
print("done")
