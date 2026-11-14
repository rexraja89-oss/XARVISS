"""Synthetic clips with the LibriTTS-R generator (904 speakers, mixed in pairs): python gen_psg.py <pos|adv> <n> <seed> <outdir>"""
import sys, os, json, random, wave, time, unicodedata
import numpy as np, torch, torchaudio
torch.set_num_threads(2)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "psg-fork"))
import generate_samples as G
from espeak_phonemizer import Phonemizer
kind, n, seed, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
random.seed(seed); np.random.seed(seed); torch.manual_seed(seed)
os.makedirs(out, exist_ok=True)
POS = (["zarvis"] * 8 + ["jarvis"] * 6 + ["zarvus"] * 2 + ["jarvus"] * 2 + ["zarviz"] + ["hey zarvis"] * 3 + ["hey jarvis"] * 3 +
       ["okay zarvis", "okay jarvis", "hello zarvis", "hi jarvis", "hey zarvus", "hey jarvus"])
if kind == "pos":
    def text(): return random.choice(POS)
elif kind == "adv2":
    # Round 2's near misses: what round 1 woke on ("jar of pickles", "guitar was", "czar of", "observe", "archive"),
    # the same family built up systematically, and the things Rex says after the name on their own.
    AR = "jar czar car bar star far tar char guitar bazaar cigar radar are scar par mar jars cars stars bars".split()
    NEXT = ("of was with we will is and of his of this of us fits visits vase vest vista visit vicious wish whistle west wizard "
            "of pickles of honey of rice of water wash was out was here was not is on is off this that these a bit of the").split(" ")
    NEXT2 = ["of pickles", "of honey", "of his", "of this", "of us", "was out", "was here", "was not", "is on", "is off", "of the", "wash it", "with us", "we are", "will be", "and this"]
    WORDS = ("observe observed observes archive archived archives charges charged charger officer officers office service services harvest harvests "
             "nervous travis marvin mavis davis jervis purvis elvis novice notice crevice surface survive servant deserve reserve conserve "
             "starve starves carve carves carving marvel marvelous harbor harvard jargon jasmine java javelin jacket garbage garden charles "
             "artist army armor arvind harvey darwin larva sarvesh zara zero zebra zombie bizarre bazaar jarring starving parvis jarvik "
             "this is his is it was it is with this because of course service is the service our service").split()
    AFTER = ("what time is it|call my wife|what's the weather today|are you there|wake up|status report|turn on the flashlight|send a message|open whatsapp|"
             "good morning|can you hear me|play some music|what's the battery level|listen to me|stop|tell me the news|where is my phone|I need your help|"
             "read my messages|is my phone charging|set an alarm for five|how are you today|take a note|any updates|check my email|find my phone|"
             "kya haal hai|suno|kya time hua hai|meri baat suno|kaise ho|bolo").split("|")
    PRE = ["hey", "okay", "hello", "hi", "oh", "yes", "listen", "excuse me", "good morning", "wake up"]
    def text():
        r = random.random()
        if r < 0.30: return random.choice(AR) + " " + random.choice(NEXT2 + NEXT)
        if r < 0.42: return random.choice(["the ", "a ", "my ", "that ", "this ", ""]) + random.choice(AR) + " " + random.choice(NEXT2) + " " + random.choice(WORDS)
        if r < 0.60: return random.choice(WORDS)
        if r < 0.70: return random.choice(PRE) + " " + random.choice(WORDS + AR)
        if r < 0.82: return random.choice(WORDS + AR) + ", " + random.choice(AFTER)
        if r < 0.92: return random.choice(AFTER)
        return random.choice(PRE) + ", " + random.choice(AFTER)
else:
    import advtexts as A
    sentences = [l.strip() for l in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "kws", "neg.txt")) if l.strip()]
    def text():
        r = random.random()
        if r < 0.30: return random.choice(A.words)
        if r < 0.50: return random.choice(A.custom)
        if r < 0.65: return random.choice(A.phrases)
        if r < 0.80: return " ".join(random.sample(A.words + A.custom, random.randint(2, 3)))
        if r < 0.90: return random.choice(["hey ", "okay ", "hello ", "hi "]) + random.choice(A.words + A.custom)
        return random.choice(sentences)
model_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "psg-fork", "models", "en-us-libritts-high.pt")
model = torch.load(model_path, weights_only=False); model.eval()
config = json.load(open(model_path + ".json"))
phonemizer = Phonemizer(config["espeak"]["voice"])
resampler = torchaudio.transforms.Resample(22050, 16000, lowpass_filter_width=64, rolloff=0.9475937167399596, resampling_method="sinc_interp_kaiser", beta=14.769656459379492)
NS = config["num_speakers"]
manifest = open(os.path.join(out, f"manifest-{seed}.tsv"), "a")
done = 0; t0 = time.time(); B = 24 if kind == "pos" else (12 if kind == "adv2" else 8)
while done < n:
    texts = sorted([text() for _ in range(B)], key=len)
    ids = [G.get_phonemes(phonemizer, config, t, False) for t in texts]
    m = max(len(i) for i in ids); ids = [i + [1] * (m - len(i)) for i in ids]
    s1 = torch.LongTensor([random.randrange(NS) for _ in range(B)]); s2 = torch.LongTensor([random.randrange(NS) for _ in range(B)])
    with torch.no_grad():
        audio = G.generate_audio(model, s1, s2, ids, random.choice([0.0, 0.25, 0.5, 0.75]), random.uniform(0.6, 1.0), random.uniform(0.7, 1.0), random.uniform(0.7, 1.35), None)
        audio = resampler(audio.cpu()).numpy()
    for k in range(audio.shape[0]):
        a = audio[k].flatten()
        a = (a * (32767.0 / max(0.01, float(np.max(np.abs(a)))))).clip(-32767, 32767).astype(np.int16)
        a = G.remove_silence(a)
        name = f"{kind}-{seed}-{done}.wav"
        with wave.open(os.path.join(out, name), "wb") as w:
            w.setframerate(16000); w.setsampwidth(2); w.setnchannels(1); w.writeframes(a.tobytes())
        manifest.write(f"{name}\t{texts[k]}\n"); done += 1
    if (done // B) % 40 == 0: manifest.flush(); print(done, n, round(time.time() - t0), flush=True)
manifest.close(); print("done", done, round(time.time() - t0), flush=True)
