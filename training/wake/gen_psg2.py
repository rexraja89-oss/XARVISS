"""Positives, round 2: more ways of saying the name (Indian r and v, British, short/long vowels) and the name inside
   a sentence — "Xarvis, what time is it" is cut just after the name, so the detector fires before the rest.
   python gen_psg2.py <n> <seed> <outdir>"""
import sys, os, json, random, wave, time, unicodedata
import numpy as np, torch, torchaudio
torch.set_num_threads(2)
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "psg-fork"))
import generate_samples as G
from piper_train.vits import commons
from espeak_phonemizer import Phonemizer
n, seed, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
random.seed(seed); np.random.seed(seed); torch.manual_seed(seed)
os.makedirs(out, exist_ok=True)
model_path = os.path.join(HERE, "psg-fork", "models", "en-us-libritts-high.pt")
model = torch.load(model_path, weights_only=False); model.eval()
config = json.load(open(model_path + ".json")); M = config["phoneme_id_map"]; NS = config["num_speakers"]
ph = Phonemizer(config["espeak"]["voice"])
resampler = torchaudio.transforms.Resample(22050, 16000, lowpass_filter_width=64, rolloff=0.9475937167399596, resampling_method="sinc_interp_kaiser", beta=14.769656459379492)
# Round 1 also had v->w, r-less and "ur" variants and cut in-sentence clips up to 0.26 s after the name: the detector
# then woke on "jar of pickles" and "guitar was" (3 false wakes an hour). SAFE=1 (round 2) keeps the variants that
# stay clear of everyday words; the Indian v and r come from the real Hindi voices (gen_hi.py) instead.
SAFE = os.environ.get("SAFE") == "1"
BODY = (["ˈɑːɹvɪs"] * 8 + ["ˈɑːɹvəs"] * 3 + ["ˈɑːɾvɪs"] * 3 + ["ˈɑɹvɪs"] * 2 + ["ˈɑːɹviːs", "ˈɑːɹvɪz"]) if SAFE else (
        ["ˈɑːɹvɪs"] * 6 + ["ˈɑːɹvəs"] * 2 + ["ˈɑːɾvɪs"] * 3 + ["ˈɑːɹwɪs"] * 3 + ["ˈɑːɾwɪs"] * 2 + ["ˈɑːvɪs"] * 2 + ["ˈɑɹvɪs"] * 2 +
        ["ˈɑːɹviːs", "ˈɑːɹvɪz", "ˈʌɹvɪs", "ˈɑːɹʋɪs", "ˈɑːɾʋɪs", "ˈɑːɹvɪs"])
CUT = 0.10 if SAFE else 0.26
def name(): return random.choice(["z"] * 11 + ["dʒ"] * 9) + random.choice(BODY)
PRE = [ph.phonemize(t).strip() for t in ["hey", "hey", "hey", "okay", "okay", "hello", "hi", "oh", "yes", "listen", "excuse me", "good morning", "wake up", "hey there"]]
AFTER = [ph.phonemize(t).strip() for t in """what time is it|call my wife|what's the weather today|are you there|wake up|status report|turn on the flashlight|send a message|open whatsapp
how is my other phone|good morning|can you hear me|play some music|what's the battery level|remind me to call him at six|listen to me|stop|tell me the news|where is my phone
I need your help|what's on my calendar|read my messages|is my phone charging|set an alarm for five|who is calling|what do you think|how are you today|take a note|turn it off
are you awake|do you copy|I have a question|what happened|any updates|check my email|find my phone|lock the phone|guard my phone|update everything|what can you do
tell me a joke|how far is the airport|translate this|start the night shift|what did I miss|give me a report|run a diagnostic|talk to me|say something|one more thing
kya haal hai|suno|kya time hua hai|meri baat suno|phone kahan hai|battery kitni hai|kaise ho|ek kaam karo|bolo|zara sunna""".replace("\n", "|").split("|")]
BEFORE = [ph.phonemize(t).strip() for t in """are you there|thank you|good morning|good night|listen|wake up|well done|come on|not now|one second|hello there|excuse me|good job|thanks
tell me|help me|answer me|that's all|very good|where are you|can you hear me|I said|okay then|right|so|and|but|please|shukriya|suno|arey""".replace("\n", "|").split("|")]
def ids_of(s):
    out = []
    for c in unicodedata.normalize("NFD", s):
        if c in M: out.extend(M[c]); out.extend(M["_"])
    return out
def make():
    r = random.random(); nm = name()
    if r < 0.28: return "posi", nm, None
    if r < (0.53 if SAFE else 0.47): return "posp", random.choice(PRE) + " " + nm, None
    if r < (0.80 if SAFE else 0.85):
        head = (random.choice(PRE) + " " if random.random() < 0.25 else "") + nm
        return "posc", head + random.choice([", ", ", ", " ", ". "]) + random.choice(AFTER), head
    return "posf", random.choice(BEFORE) + random.choice([", ", " "]) + nm, None
@torch.no_grad()
def synth(ids, s1, s2, slerp_w, ns, nsw, ls):
    x = torch.LongTensor(ids); xl = torch.LongTensor([len(i) for i in ids])
    x, m_p_orig, logs_p_orig, x_mask = model.enc_p(x, xl)
    g = G.slerp(model.emb_g(s1), model.emb_g(s2), slerp_w).unsqueeze(-1)
    logw = model.dp(x, x_mask, g=g, reverse=True, noise_scale=nsw) if model.use_sdp else model.dp(x, x_mask, g=g)
    w_ceil = torch.ceil(torch.exp(logw) * x_mask * ls)
    y_lengths = torch.clamp_min(torch.sum(w_ceil, [1, 2]), 1).long()
    y_mask = torch.unsqueeze(commons.sequence_mask(y_lengths, y_lengths.max()), 1).type_as(x_mask)
    attn = commons.generate_path(w_ceil, torch.unsqueeze(x_mask, 2) * torch.unsqueeze(y_mask, -1))
    m_p = torch.matmul(attn.squeeze(1), m_p_orig.transpose(1, 2)).transpose(1, 2)
    logs_p = torch.matmul(attn.squeeze(1), logs_p_orig.transpose(1, 2)).transpose(1, 2)
    z = model.flow(m_p + torch.randn_like(m_p) * torch.exp(logs_p) * ns, y_mask, g=g, reverse=True)
    return model.dec(z * y_mask, g=g), w_ceil.squeeze(1)
def trim(a):
    """Drops the quiet before and after the speech (keeps 20-30 ms)."""
    e = np.abs(a); thr = max(e.max() * 0.03, 1e-4); idx = np.nonzero(e > thr)[0]
    if len(idx) == 0: return a
    return a[max(0, idx[0] - 320):min(len(a), idx[-1] + 480)]
manifest = open(os.path.join(out, f"manifest2-{seed}.tsv"), "a")
done = 0; t0 = time.time(); B = 16; batches = 0
while done < n:
    items = sorted([make() for _ in range(B)], key=lambda it: len(it[1]))
    ids = [M["^"] + ids_of(it[1]) + M["$"] for it in items]
    heads = [(len(M["^"]) + len(ids_of(it[2]))) if it[2] else None for it in items]
    real = [len(i) for i in ids]   # before padding: what comes after it in the audio is junk
    m = max(len(i) for i in ids); ids = [i + [1] * (m - len(i)) for i in ids]
    s1 = torch.LongTensor([random.randrange(NS) for _ in range(B)]); s2 = torch.LongTensor([random.randrange(NS) for _ in range(B)])
    audio, dur = synth(ids, s1, s2, random.choice([0.0, 0.25, 0.5, 0.75]), random.uniform(0.6, 1.0), random.uniform(0.7, 1.0), random.uniform(0.72, 1.35))
    audio = resampler(audio.squeeze(1).cpu()).numpy(); dur = dur.cpu().numpy()
    for k, (kind, text, head) in enumerate(items):
        a = audio[k][:int(dur[k][:real[k]].sum() * 256 * 16000 / 22050)]
        if heads[k] is not None:
            end = int(dur[k][:heads[k]].sum() * 256 * 16000 / 22050) + int(random.uniform(0.0, CUT) * 16000)
            a = a[:max(4000, min(len(a), end))]
            e = np.abs(a); idx = np.nonzero(e > max(e.max() * 0.03, 1e-4))[0]
            if len(idx): a = a[max(0, idx[0] - 320):]
        else:
            a = trim(a)
        a = (a * (32767.0 / max(0.01, float(np.max(np.abs(a)))))).clip(-32767, 32767).astype(np.int16)
        fname = f"{kind}-{seed}-{done}.wav"
        with wave.open(os.path.join(out, fname), "wb") as w:
            w.setframerate(16000); w.setsampwidth(2); w.setnchannels(1); w.writeframes(a.tobytes())
        manifest.write(f"{fname}\t{text}\n"); done += 1
    batches += 1
    if batches % 30 == 0: manifest.flush(); print(done, n, round(time.time() - t0), flush=True)
manifest.close(); print("done", done, round(time.time() - t0), flush=True)
