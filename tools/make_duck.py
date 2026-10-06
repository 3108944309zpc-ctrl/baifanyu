"""合成小黄鸭叫声，写进 res/raw。

橡皮鸭发声器的声音特征：
  · 很短（0.2 秒左右），起音极快、衰减也快
  · 音高先快速上冲再回落，落在 1~2kHz 这个刺耳的区间
  · 有簧片嗡鸣（约 30Hz 的幅度调制）和一点气流噪声
  · 谐波丰富，听起来"扁"而"吱"

三个版本只在基频和调制上略有差别，连点时不会完全重复。

用法：python tools/make_duck.py
输出：app/src/main/res/raw/duck1.wav ~ duck3.wav
"""
import os
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "raw")
SR = 22050


def squeak(base=880.0, peak=1.95, dur=0.22, reed=32.0, seed=0):
    rng = np.random.default_rng(seed)
    n = int(SR * dur)
    t = np.linspace(0, dur, n, endpoint=False)

    # 音高轮廓：起音后 ~40ms 冲到最高，再回落
    f = base * (1.0 + (peak - 1.0) * np.exp(-((t - 0.040) / 0.045) ** 2))
    phase = 2 * np.pi * np.cumsum(f) / SR

    sig = np.sin(phase)
    sig += 0.55 * np.sin(2 * phase)
    sig += 0.30 * np.sin(3 * phase)
    sig += 0.16 * np.sin(4 * phase)
    sig += 0.05 * rng.standard_normal(n)          # 气流噪声

    sig *= 1.0 + 0.22 * np.sin(2 * np.pi * reed * t)   # 簧片嗡鸣

    # 包络：3ms 起音，快速衰减
    sig *= (1 - np.exp(-t / 0.0035)) * np.exp(-t / 0.052)

    tail = int(SR * 0.02)                          # 收尾淡出，避免咔哒
    sig[-tail:] *= np.linspace(1, 0, tail)

    return sig / max(1e-9, np.max(np.abs(sig))) * 0.86


VARIANTS = [(860.0, 1.95, 30.0, 1), (960.0, 1.85, 34.0, 2), (770.0, 2.05, 27.0, 3)]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for i, (base, peak, reed, seed) in enumerate(VARIANTS, 1):
        y = squeak(base, peak, reed=reed, seed=seed)
        p = os.path.join(OUT, f"duck{i}.wav")
        with wave.open(p, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((y * 32767).astype(np.int16).tobytes())
        print(f"duck{i}.wav  {len(y) / SR * 1000:.0f} ms  {os.path.getsize(p) / 1024:.0f} KB  基频 {base:.0f}Hz")
