"""The name and other words in three Hindi voices (how a Hindi speaker says them): python gen_hi.py <pos|neg> <n> <outdir>
   """
import sys, os, random, wave, time, numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
kind, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
random.seed(3 if kind == "pos" else 4); os.makedirs(out, exist_ok=True)
NAMES = ["ज़ार्विस"] * 5 + ["जार्विस"] * 5 + ["ज़ारविस", "जारविस", "ज़ार्विज़", "ज़ारविश"]
PRE = ["हे", "हे", "ओके", "हेलो", "सुनो", "अरे", "हाय", "ओ", "गुड मॉर्निंग", "उठो"]
BEFORE = ["सुनो,", "कहाँ हो", "धन्यवाद", "शुक्रिया", "गुड मॉर्निंग", "उठो", "बताओ", "थैंक यू", "ठीक है", "सुन रहे हो", "एक मिनट"]
NEG_WORDS = """सर्विस जारी ज़रा ज़रूरी हज़ार बाज़ार सर जी सर चार्ज जवाब ज़िंदगी जरूर सर्वे सर्वर नर्वस ज़ाहिर जाहिर ज़ाहिद यार आर्मी हार्वेस्ट ट्रैविस मार्विन पार्वती जॉर्ज चार्ल्स
आवाज़ दरवाज़ा जावेद ज़ाकिर जॉब जर्नी ज़मीन जहाज़ चारपाई सार्वजनिक सर्वेश सरवर परवेज़ ज़रीना ज़फ़र कार्ड गार्ड मार्च पार्क शार्क हार्ड स्टार्ट आर्ट कारवां जारविक दारविन मेविस डेविस
ज़ारा ज़ार जार विश सरविस सर्विसेज़ सर्विस सेंटर कस्टमर सर्विस रूम सर्विस चार बीस ज़रा सुनो जी हाँ नहीं ठीक है अच्छा शुक्रिया धन्यवाद नमस्ते चलो आओ जाओ रुको""".split()
NEG_SENT = """आज मौसम बहुत अच्छा है|मुझे कल सुबह जल्दी उठना है|यह मशीन ठीक से काम नहीं कर रही है|कृपया दरवाज़ा बंद कर दीजिए|सर्विस सेंटर कहाँ है|गाड़ी की सर्विस करानी है|कस्टमर सर्विस को फ़ोन करो
खाना तैयार हो गया है|बच्चे स्कूल गए हैं|मीटिंग चार बजे है|फ़ोन चार्ज पर लगा दो|ज़रा इधर आना|यह बहुत ज़रूरी काम है|काम जारी रखो|बाज़ार से सब्ज़ी ले आना|एक हज़ार रुपये दे दो
सर, फ़ाइल तैयार है|जी सर, अभी करता हूँ|आप कैसे हैं|मैं ठीक हूँ, धन्यवाद|कल छुट्टी है|पानी की टंकी भर गई है|जनरेटर चालू कर दो|प्रेशर चेक करो|यह रिपोर्ट आज भेजनी है|ट्रेन लेट है
मुझे चाय चाहिए|टीवी की आवाज़ कम करो|लाइट बंद कर दो|कितने बजे हैं|घर कब आओगे|सब ठीक है ना|हाँ बिलकुल|नहीं अभी नहीं|थोड़ी देर बाद|चलो चलते हैं|यार यह क्या हो रहा है|जावेद भाई कहाँ हैं""".replace("\n", "|").split("|")
def text():
    if kind == "pos":
        r = random.random(); nm = random.choice(NAMES)
        if r < 0.45: return nm
        if r < 0.75: return random.choice(PRE) + " " + nm
        return random.choice(BEFORE) + " " + nm
    r = random.random()
    if r < 0.45: return random.choice(NEG_WORDS)
    if r < 0.65: return " ".join(random.sample(NEG_WORDS, 2))
    if r < 0.75: return random.choice(PRE) + " " + random.choice(NEG_WORDS)
    return random.choice(NEG_SENT)
import json, unicodedata, onnxruntime as ort
from espeak_phonemizer import Phonemizer
class Piper:
    """A Piper voice run directly: espeak phonemes -> ids -> the voice's ONNX model."""
    def __init__(self, voice):
        d = f"{HERE}/tts/vits-piper-hi_IN-{voice}-medium"
        self.cfg = json.load(open(f"{d}/hi_IN-{voice}-medium.onnx.json")); self.map = self.cfg["phoneme_id_map"]
        so = ort.SessionOptions(); so.intra_op_num_threads = 1
        self.sess = ort.InferenceSession(f"{d}/hi_IN-{voice}-medium.onnx", so, providers=["CPUExecutionProvider"])
        self.ph = Phonemizer(self.cfg["espeak"]["voice"]); self.sr = self.cfg["audio"]["sample_rate"]
    def say(self, text, ns, ls, nsw):
        ids = list(self.map["^"])
        for c in unicodedata.normalize("NFD", self.ph.phonemize(text, keep_clause_breakers=True)):
            if c in self.map: ids += self.map[c] + self.map["_"]
        ids += self.map["$"]
        x = np.array([ids], dtype=np.int64)
        out = self.sess.run(None, {"input": x, "input_lengths": np.array([x.shape[1]], dtype=np.int64), "scales": np.array([ns, ls, nsw], dtype=np.float32)})[0]
        return out.squeeze()
engines = [Piper(v) for v in ("pratham", "priyamvada", "rohan")]
t0 = time.time(); manifest = open(f"{out}/manifest-hi.tsv", "w")
for i in range(n):
    t = text(); e = random.choice(engines)
    x = e.say(t, random.uniform(0.45, 1.0), random.uniform(0.75, 1.3), random.uniform(0.6, 1.0)).astype(np.float32); sr = e.sr
    k = int(len(x) * 16000 / sr); x = np.interp(np.linspace(0, len(x) - 1, k), np.arange(len(x)), x)
    en = np.abs(x); idx = np.nonzero(en > max(en.max() * 0.03, 1e-4))[0]
    if len(idx): x = x[max(0, idx[0] - 320):min(len(x), idx[-1] + 480)]
    x = (x / max(0.01, np.abs(x).max()) * 32767).astype(np.int16)
    name = f"hi{kind}-{i}.wav"
    with wave.open(f"{out}/{name}", "wb") as w: w.setframerate(16000); w.setsampwidth(2); w.setnchannels(1); w.writeframes(x.tobytes())
    manifest.write(f"{name}\t{t}\n")
    if i % 500 == 0: print(i, n, round(time.time() - t0), flush=True)
manifest.close(); print("done", n, round(time.time() - t0))
