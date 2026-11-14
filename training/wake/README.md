# The "Xarvis" wake word detector

Scripts that made `wake/src/main/assets/wake/xarvis.bin` (14 Nov 2026). Run on CPU; data comes from HuggingFace and GitHub.
1. `gen_psg2.py` / `gen_hi.py` / `gen_psg.py adv2`: synthetic positives and sound-alike decoys (Piper LibriTTS-R, Piper Hindi).
2. `pools.py` + `build.py`: real speech/noise pools; augmentation; openWakeWord features (`feat.py`).
3. `train.py <name> 6000 32 1 1500` with XREAL=real_ls.npy XNOISE=0 XPOS=a,h XADV=a,h2 and seeds 1-3 (+ p2): the four shipped heads.
4. `report.py a.pt,b.pt,... <threshold>` and `roc.py`: held-out results (VCTK, Kokoro, a Hindi voice never trained on, 17 h of speech).
5. `export.py p2.pt,b1.pt,b2.pt,b3.pt -1.75 xarvis.bin wake_vectors.bin`: always commit both files together (WakeModelTest).
