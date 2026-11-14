"""Words and short phrases that sound like "zarvis"/"jarvis" but aren't: the hard negatives."""
import re, itertools, random, pronouncing
random.seed(7)
VOWELS = ["AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"]
TARGETS = {"jarvis": "JH AA R V AH S", "jarvis2": "JH AA R V IH S", "zarvis": "Z AA R V IH S", "zarvis2": "Z AA R V AH S"}
SAME = {"jarvis", "jarvis's", "jarvis'", "zarvis"}
def stressed(p): return re.sub("|".join(VOWELS), lambda m: m.group(0) + "[0-2]", p)
def patterns(phones, max_replace):
    out = []
    for k in range(1, max_replace + 1):
        for idx in itertools.combinations(range(len(phones)), k):
            q = [("(.){1,3}" if i in idx else stressed(p)) for i, p in enumerate(phones)]
            out.append("^" + " ".join(q) + "$")
    return out
words = set()
for name, ph in TARGETS.items():
    phones = ph.split()
    for pat in patterns(phones, len(phones) - 3):
        for w in pronouncing.search(pat):
            if w not in SAME and "'" not in w and "." not in w and len(w) > 2: words.add(w)
# Words that share the stressed "ar-v" middle or the "-vis/-vice" ending.
for pat in ["AA[0-2] R V", "ER[0-2] V AH[0-2] S$", "ER[0-2] V IH[0-2] S$", "^JH AA[0-2] R", "^Z AA[0-2]", "AA[0-2] R V AH[0-2]", "V IH[0-2] S$", "V AH[0-2] S$", "^S ER[0-2] V", "^CH AA[0-2] R", "^SH AA[0-2] R", "^D AA[0-2] R", "^G AA[0-2] R", "^Y AA[0-2] R"]:
    for w in pronouncing.search(pat):
        if w not in SAME and "'" not in w and "." not in w and 2 < len(w) < 12: words.add(w)
words = sorted(words)
CUSTOM = """service services servicing serviced harvest harvested harvesting travis marvin mavis davis nervous charles carve carved carving starve starving starved
jar jars jarring java jazz jacket jargon charge charged charger charging sharp shark harbor harvard marvel marvelous marvellous carbon garbage garden guard large march
arvin arvind harvey harness darwin larva lava saliva surface survive survives surfaces purpose purchase nervously server servers serve serves served serving servant servants
office offices notice novice crevice jarvik jarrett jasmine javier javelin gervais jervis purvis elvis davies chavez sarvis zara czar bazaar bizarre zombie zebra zero zeros zoo zest
tsar star stars start started farm farmer far father artist argue army arvo arvada marvis parvez pervez sarvesh sarvar sarwar zaheer zaid zafar zakir zarina zarif
are this hard this jar this far west our visit car is are we starve us carve us part of us charter bus arctic ice a jar of rice the jar is the car is the star is
sir yes sir thank you sir good morning okay hello hey there hey hi hey you hey john hey siri hey google alexa okay google hey jar hey charles hey marvin hey travis hey service hey harvest
customer service room service service center the service is at your service self service secret service harvest moon harvest time nervous system jar of honey
jar jar door ajar a jarful jars of java script javascript jarhead jarful czar of the czar is so far as so far it is as far as"""
custom = [w for w in re.split(r"\s+", CUSTOM.strip()) if w]
phrases = ["are this", "hard this", "jar this", "far west", "our visit", "car is", "are we", "starve us", "carve us", "part of us", "charter bus", "arctic ice", "a jar of rice",
           "the jar is", "the car is", "the star is", "yes sir", "thank you sir", "good morning", "hey there", "hey you", "hey john", "hey siri", "hey google", "okay google",
           "hey jar", "hey charles", "hey marvin", "hey travis", "hey service", "hey harvest", "customer service", "room service", "service center", "the service is",
           "at your service", "self service", "secret service", "harvest moon", "harvest time", "nervous system", "jar of honey", "door ajar", "java script", "czar of", "the czar is",
           "so far as", "so far it is", "as far as", "hey davis", "okay davis", "hello travis", "hi marvin", "hey harvey", "okay service", "hello service", "hey nervous",
           "jar vase", "jar fits", "jar bits", "jar wish", "zar wish", "char fish", "jar miss", "our wish", "harm us", "arm us", "charm us", "jar of us", "far from us", "garage is", "the art is", "start this", "star fish", "starfish"]
if __name__ == "__main__":
    print(len(words), "dictionary words;", len(custom), "custom"); print(" ".join(random.sample(words, 120)))
