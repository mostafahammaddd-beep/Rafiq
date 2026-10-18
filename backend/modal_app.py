"""
Rafiq v12.0 — Production Serverless GPU Backend for Modal.com (`modal_app.py`)
Powered by `quran-muaalem` (v0.2.2) + `obadx/muaalem-model-v3_2` (Wav2Vec2BertForMultilevelCTC)
and `quran-transcript` (Quran Phonetic Script - QPS).

Endpoints exposed via FastAPI ASGI app (`@modal.asgi_app()`):
1. WebSocket `/ws/live-recitation`:
   - Accepts streaming 16kHz 16-bit Mono PCM audio chunks + `sura_idx` and `aya_idx`.
   - Runs rolling QPS phonetization (`quran_phonetizer`) and multi-level CTC inference
     (Phonemes + 10 Sifat levels: `ghonna`, `qalqla`, `tafkheem_or_taqeeq`, `hams_or_jahr`,
     `shidda_or_rakhawa`, `itbaq`, `safeer`, `tikraar`, `tafashie`, `istitala`).
   - Performs prefix-aware Waqf/Wasl alignment and pushes real-time JSON diffs on every
     micro-pause (waqf) or word boundary.
2. POST `/correct-recitation`:
   - Accepts WAV or raw 16kHz PCM audio + `sura_idx` and `aya_idx`.
   - Returns `predicted_phonemes`, `reference_phonemes`, `uthmani_text`, and the exact
     `errors` array containing `uthmani_pos: [start_char, end_char]`, `error_type`
     (`"tajweed" | "tashkeel" | "normal"`), `speech_error_type` (`"replace" | "insert" | "delete"`),
     `expected_ph`, `predicted_ph`, `expected_len`, `predicted_len`, and Arabic `correction_tip`.
"""

from __future__ import annotations

import base64
import io
import json
import os
import struct
import tempfile
import time
import wave
from dataclasses import asdict
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

try:
    import modal
except ImportError:
    modal = None  # Allows importing helper functions in local unit tests without modal installed

MODEL_ID = "obadx/muaalem-model-v3_2"
SAMPLE_RATE = 16000

# All 10 phonetic Sifat levels output by Wav2Vec2BertForMultilevelCTC in quran-muaalem v0.2.2
SIFAT_LEVEL_NAMES: Tuple[str, ...] = (
    "ghonna",
    "qalqla",
    "tafkheem_or_taqeeq",
    "hams_or_jahr",
    "shidda_or_rakhawa",
    "itbaq",
    "safeer",
    "tikraar",
    "tafashie",
    "istitala",
)

HARAKAT_SET = set("َُؙِ۪")
MADD_SET = set("اۥۦـ")
GHUNNAH_SET = set("ں۾")
QALQALAH_SET = set("ڇ")
NON_ONSET_CHARS = HARAKAT_SET | MADD_SET | QALQALAH_SET

AYAT_KURSI_UTHMANI_WORDS: List[str] = [
    "اللَّهُ", "لَا", "إِلَٰهَ", "إِلَّا", "هُوَ", "الْحَيُّ", "الْقَيُّومُ",
    "لَا", "تَأْخُذُهُ", "سِنَةٌ", "وَلَا", "نَوْمٌ",
    "لَهُ", "مَا", "فِي", "السَّمَاوَاتِ", "وَمَا", "فِي", "الْأَرْضِ",
    "مَنْ", "ذَا", "الَّذِي", "يَشْفَعُ", "عِنْدَهُ", "إِلَّا", "بِإِذْنِهِ",
    "يَعْلَمُ", "مَا", "بَيْنَ", "أَيْدِيهِمْ", "وَمَا", "خَلْفَهُمْ",
    "وَلَا", "يُحِيطُونَ", "بِشَيْءٍ", "مِنْ", "عِلْمِهِ", "إِلَّا", "بِمَا", "شَاءَ",
    "وَسِعَ", "كُرْسِيُّهُ", "السَّمَاوَاتِ", "وَالْأَرْضَ",
    "وَلَا", "يَئُودُهُ", "حِفْظُهُمَا", "وَهُوَ", "الْعَلِيُّ", "الْعَظِيمُ",
]

AYAT_KURSI_UTHMANI_TEXT: str = " ".join(AYAT_KURSI_UTHMANI_WORDS)


def compute_word_char_spans(uthmani_text: str) -> Tuple[List[str], List[Tuple[int, int]]]:
    words = uthmani_text.split(" ")
    spans: List[Tuple[int, int]] = []
    cursor = 0
    for w in words:
        spans.append((cursor, cursor + len(w)))
        cursor += len(w) + 1
    return words, spans


def refine_uthmani_char_pos(
    uthmani_word: str,
    word_span: Tuple[int, int],
    error_type: str,
    expected_ph: str,
    predicted_ph: str,
) -> List[int]:
    """
    Pinpoints the exact character + haraka range `[start_char, end_char]` inside `uthmani_text`
    corresponding to the first phoneme/tashkeel/tajweed mismatch between `expected_ph` and `predicted_ph`.
    """
    w_start, w_end = word_span
    w_len = len(uthmani_word)
    if w_len == 0:
        return [w_start, w_end]

    # Find the exact phoneme index where expected_ph and predicted_ph diverge
    common_prefix = 0
    min_l = min(len(expected_ph), len(predicted_ph))
    while common_prefix < min_l and expected_ph[common_prefix] == predicted_ph[common_prefix]:
        common_prefix += 1

    # Walk backwards from the mismatch position in expected_ph to find the owning base consonant
    anchor_consonant = None
    is_near_end = common_prefix >= max(1, len(expected_ph) - 2)
    search_idx = min(common_prefix, len(expected_ph) - 1)
    while search_idx >= 0:
        c = expected_ph[search_idx]
        if c not in HARAKAT_SET and c not in MADD_SET and c not in GHUNNAH_SET and c not in QALQALAH_SET:
            anchor_consonant = c
            break
        search_idx -= 1

    if anchor_consonant is not None:
        search_variants = [anchor_consonant]
        if anchor_consonant == "ء":
            search_variants.extend(["أ", "إ", "ئ", "ؤ", "ء"])
        elif anchor_consonant == "ا":
            search_variants.extend(["ا", "أ", "إ", "آ", "ٰ"])
        elif anchor_consonant == "ت":
            search_variants.extend(["ة", "ت"] if is_near_end else ["ت", "ة"])
        elif anchor_consonant == "ه":
            search_variants.extend(["ه", "ة"])

        for sv in search_variants:
            idx = uthmani_word.rfind(sv) if is_near_end else uthmani_word.find(sv)
            if idx != -1:
                c_start = idx
                c_end = idx + 1
                while c_end < w_len and (
                    "\u064b" <= uthmani_word[c_end] <= "\u065f"
                    or uthmani_word[c_end] == "\u0670"
                    or "\u06d6" <= uthmani_word[c_end] <= "\u06ed"
                ):
                    c_end += 1
                return [w_start + c_start, w_start + c_end]

    if error_type == "tashkeel" and w_len >= 2:
        idx = w_len - 1
        while idx > 0 and (
            "\u064b" <= uthmani_word[idx] <= "\u065f"
            or uthmani_word[idx] == "\u0670"
        ):
            idx -= 1
        return [w_start + idx, w_end]

    return [w_start, w_end]


def estimate_qps_harakat_lengths(
    expected_ph: str,
    predicted_ph: str,
    error_type: str,
) -> Tuple[int, int]:
    """
    Computes `expected_len` and `predicted_len` in harakat (beats) for Madd, Ghunnah,
    Shaddah, or short vowel units.
    """
    def count_units(s: str) -> int:
        if not s:
            return 0
        madd_cnt = sum(1 for c in s if c in MADD_SET)
        ghun_cnt = sum(1 for c in s if c in GHUNNAH_SET)
        har_cnt = sum(1 for c in s if c in HARAKAT_SET)
        if madd_cnt > 0:
            return max(2, madd_cnt)
        if ghun_cnt > 0:
            return max(1, ghun_cnt)
        if har_cnt > 0:
            return har_cnt
        return len(s)

    exp_l = count_units(expected_ph)
    pred_l = count_units(predicted_ph)
    if error_type == "tajweed" and exp_l == pred_l:
        if any(c in MADD_SET for c in expected_ph) and not any(c in MADD_SET for c in predicted_ph):
            exp_l, pred_l = max(2, len(expected_ph)), 1
        elif any(c in GHUNNAH_SET for c in expected_ph) and not any(c in GHUNNAH_SET for c in predicted_ph):
            exp_l, pred_l = 2, 0
    return exp_l, pred_l


def get_valid_qps_ayat_kursi(idx: int, ibtida: bool, waqf: bool, lenient: bool = True) -> List[str]:
    """
    Generates all valid Quran Phonetic Script (QPS) realizations for word `idx` (0..49)
    of Ayat al-Kursi under both Wasl (`waqf=False`) and Waqf (`waqf=True`) and Ibtida (`ibtida=True`).
    """
    munfasil = ["اا", "ااا", "اااا", "ااااا"] if lenient else ["اااا"]
    silah_kubra_w = ["ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ"] if lenient else ["ۥۥۥۥ"]
    silah_kubra_y = ["ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ"] if lenient else ["ۦۦۦۦ"]
    muttasil_wasl = ["اااا", "ااااا", "اااااا"] if lenient else ["اااا"]
    muttasil_waqf = ["اااا", "ااااا", "اااااا"] if lenient else ["اااا"]
    aared_w = ["ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ", "ۥۥۥۥۥۥ"]
    aared_y = ["ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ", "ۦۦۦۦۦۦ"]
    aared_a = ["اا", "ااا", "اااا", "ااااا", "اااااا"]
    leen_w = ["و", "وو", "ووو", "وووو", "ووووو", "وووووو"]
    leen_y = ["ي", "يي", "ييي", "يييي", "ييييي", "يييييي"]

    def comb(*parts: Any) -> List[str]:
        res = [""]
        for p in parts:
            if isinstance(p, str):
                res = [r + p for r in res]
            else:
                res = [r + x for r in res for x in p]
        return list(dict.fromkeys(res))

    tabeei_a = ["اا", "ااا"] if lenient else ["اا"]
    tabeei_w = ["ۥۥ", "ۥۥۥ"] if lenient else ["ۥۥ"]
    tabeei_y = ["ۦۦ", "ۦۦۦ"] if lenient else ["ۦۦ"]
    hw = "ءَ" if ibtida else ""

    if idx == 0:
        return comb("ءَللَ", aared_a if waqf else tabeei_a, "ه" if waqf else "هُ")
    elif idx == 1:
        return comb("لَ", tabeei_a if waqf else munfasil)
    elif idx == 2:
        return comb("ءِلَ", aared_a if waqf else tabeei_a, "ه" if waqf else "هَ")
    elif idx in (3, 24, 37):
        return comb("ءِللَ", tabeei_a)
    elif idx == 4:
        return comb("هُ", ["و", "ۥۥ"] if waqf else "وَ")
    elif idx == 5:
        return comb(hw, "لحَ", ["يي", "ي"] if waqf else "ييُ")
    elif idx == 6:
        return comb(hw, "لقَييُ", aared_w if waqf else tabeei_w, "م" if waqf else "مُ")
    elif idx == 7:
        return comb("لَ", tabeei_a)
    elif idx == 8:
        return comb("تَءخُذُ", "ه" if waqf else comb("هُ", tabeei_w))
    elif idx == 9:
        return ["سِنَه"] if waqf else ["سِنَتُوو", "سِنَتُو"]
    elif idx in (10, 32, 44):
        return comb("وَلَ", tabeei_a)
    elif idx == 11:
        return comb("نَ", leen_w, "م") if waqf else ["نَومُل", "نَومُلل"]
    elif idx == 12:
        return comb("لَ", "ه" if waqf else comb("هُ", tabeei_w))
    elif idx in (13, 27):
        return comb("مَ", tabeei_a)
    elif idx in (14, 17):
        return comb("فِ", tabeei_y) if waqf else ["فِ"]
    elif idx in (15, 42):
        return comb(hw, "سسَمَ", tabeei_a, "وَ", aared_a if waqf else tabeei_a, "ت" if waqf else "تِ")
    elif idx in (16, 30):
        return comb("وَمَ", tabeei_a)
    elif idx == 18:
        return comb(hw, "لءَرض" if waqf else "لءَرضِ")
    elif idx == 19:
        return ["مَن"] if waqf else ["مَںںں", "مَںں", "مَںںںں"]
    elif idx == 20:
        return comb("ذَ", tabeei_a) if waqf else ["ذَ"]
    elif idx == 21:
        return comb(hw, "للَذِ", tabeei_y)
    elif idx == 22:
        return ["يَشفَع"] if waqf else ["يَشفَعُ"]
    elif idx == 23:
        if waqf:
            return ["عِںںںدَه", "عِںںدَه", "عِںںںںدَه"]
        return comb(["عِںںںدَهُ", "عِںںدَهُ", "عِںںںںدَهُ"], silah_kubra_w)
    elif idx == 25:
        return ["بِءِذنِه"] if waqf else comb("بِءِذنِهِ", tabeei_y)
    elif idx == 26:
        return ["يَعلَم"] if waqf else ["يَعلَمُ"]
    elif idx == 28:
        return comb("بَ", leen_y, "ن") if waqf else ["بَينَ"]
    elif idx == 29:
        return comb("ءَيدِ", tabeei_y, "هِم")
    elif idx == 31:
        return ["خَلفَهُم"]
    elif idx == 33:
        return comb("يُحِ", tabeei_y, "طُ", aared_w if waqf else tabeei_w, "ن" if waqf else "نَ")
    elif idx == 34:
        if waqf:
            return comb("بِشَ", leen_y, ["ء", "ءڇ"])
        return ["بِشَيءِممم", "بِشَيءِمم", "بِشَيءِمممم"]
    elif idx == 35:
        return ["مِن"]
    elif idx == 36:
        return ["عِلمِه"] if waqf else comb("عِلمِهِ", silah_kubra_y)
    elif idx == 38:
        return comb("بِمَ", tabeei_a)
    elif idx == 39:
        if waqf:
            return comb("شَ", muttasil_waqf, ["ء", "ءڇ"])
        return comb("شَ", muttasil_wasl, "ءَ")
    elif idx == 40:
        return ["وَسِع"] if waqf else ["وَسِعَ"]
    elif idx == 41:
        return ["كُرسِييُه"] if waqf else ["كُرسِييُهُ"]
    elif idx == 43:
        return ["وَلءَرض"] if waqf else ["وَلءَرضَ"]
    elif idx == 45:
        return comb("يَءُ", tabeei_w, "دُه") if waqf else comb("يَءُ", tabeei_w, "دُهُ", tabeei_w)
    elif idx == 46:
        return comb("حِفظُهُمَ", tabeei_a)
    elif idx == 47:
        return ["وَهُو", "وَهُۥۥ"] if waqf else ["وَهُوَ"]
    elif idx == 48:
        return comb(hw, "لعَلِ", ["يي", "ي"] if waqf else "ييُ")
    elif idx == 49:
        if waqf:
            return comb(hw, "لعَظِ", aared_y, "م")
        return comb(hw, "لعَظِ", tabeei_y, "مُ")
    return []


def edit_distance_ops(expected: str, actual: str) -> Tuple[float, List[Tuple[str, str, str]]]:
    m, n = len(expected), len(actual)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    bp = [[0] * (n + 1) for _ in range(m + 1)]

    def del_cost(i_exp: int, c: str) -> float:
        if i_exp == 0:
            return 1.30
        if c in HARAKAT_SET:
            return 0.85
        if c in MADD_SET or c in GHUNNAH_SET or c in QALQALAH_SET:
            return 0.75
        return 0.95

    def ins_cost(c: str) -> float:
        if c in HARAKAT_SET:
            return 0.85
        if c in MADD_SET or c in GHUNNAH_SET or c in QALQALAH_SET:
            return 0.75
        return 1.0

    def sub_cost(i_exp: int, c1: str, c2: str) -> float:
        if c1 == c2:
            return 0.0
        pen = 0.20 if i_exp == 0 else 0.0
        if (c1 in HARAKAT_SET) and (c2 in HARAKAT_SET):
            return 0.85 + pen
        if (c1 in HARAKAT_SET) != (c2 in HARAKAT_SET):
            return 1.25 + pen
        return 1.0 + pen

    for i in range(1, m + 1):
        dp[i][0] = dp[i - 1][0] + del_cost(i - 1, expected[i - 1])
        bp[i][0] = 1
    for j in range(1, n + 1):
        dp[0][j] = dp[0][j - 1] + ins_cost(actual[j - 1])
        bp[0][j] = 2

    for i in range(1, m + 1):
        for j in range(1, n + 1):
            c_sub = dp[i - 1][j - 1] + sub_cost(i - 1, expected[i - 1], actual[j - 1])
            c_del = dp[i - 1][j] + del_cost(i - 1, expected[i - 1])
            c_ins = dp[i][j - 1] + ins_cost(actual[j - 1])
            best = c_sub
            b = 0
            if c_del < best:
                best = c_del
                b = 1
            if c_ins < best:
                best = c_ins
                b = 2
            dp[i][j] = best
            bp[i][j] = b

    ops: List[Tuple[str, str, str]] = []
    i, j = m, n
    while i > 0 or j > 0:
        b = bp[i][j]
        if i > 0 and j > 0 and b == 0:
            if expected[i - 1] != actual[j - 1]:
                ops.append(("replace", expected[i - 1], actual[j - 1]))
            i -= 1
            j -= 1
        elif i > 0 and (j == 0 or b == 1):
            ops.append(("delete", expected[i - 1], ""))
            i -= 1
        else:
            ops.append(("insert", "", actual[j - 1]))
            j -= 1
    ops.reverse()
    return dp[m][n], ops


def classify_qps_ops(
    word_idx: int,
    uthmani_word: str,
    expected_ph: str,
    predicted_ph: str,
    ops: List[Tuple[str, str, str]],
) -> Tuple[str, str, int, int, str]:
    """
    Classifies a word's QPS discrepancy into:
    - `error_type`: `"tajweed" | "tashkeel" | "normal"`
    - `speech_error_type`: `"replace" | "insert" | "delete"`
    - `expected_len`: int (in harakat)
    - `predicted_len`: int (in harakat)
    - `correction_tip`: concise Arabic diagnostic tip
    """
    if not predicted_ph:
        return (
            "normal",
            "delete",
            max(2, len(expected_ph)),
            0,
            f"لم تُسمع كلمة «{uthmani_word}» في التلاوة؛ احرص على قراءتها كاملةً دون تخطٍّ.",
        )

    harakat_names = {"َ": "الفتحة", "ُ": "الضمة", "ِ": "الكسرة"}
    has_tashkeel = False
    has_tajweed = False
    has_normal = False
    speech_op = ops[0][0] if ops else "replace"
    tips: List[str] = []

    for op, ex, ac in ops:
        if ex in HARAKAT_SET or ac in HARAKAT_SET:
            has_tashkeel = True
            if op == "replace" and ex in HARAKAT_SET and ac in HARAKAT_SET:
                tips.append(
                    f"خطأ في التشكيل في «{uthmani_word}»: أبدلتَ {harakat_names.get(ex, ex)} بـ{harakat_names.get(ac, ac)}."
                )
            elif op == "delete" and ex in HARAKAT_SET:
                tips.append(
                    f"خطأ في التشكيل في «{uthmani_word}»: أسقطتَ حركة {harakat_names.get(ex, ex)} في غير موضع وقف."
                )
            elif op == "insert" and ac in HARAKAT_SET:
                tips.append(
                    f"خطأ في التشكيل في «{uthmani_word}»: حرّكتَ الحرف الساكن بـ{harakat_names.get(ac, ac)} وحقه السكون."
                )
        elif ex in MADD_SET or ac in MADD_SET or ex in GHUNNAH_SET or ac in GHUNNAH_SET or ex in QALQALAH_SET or ac in QALQALAH_SET:
            has_tajweed = True
            if ac == "ڇ" and ex == "":
                tips.append(f"خطأ في التجويد في «{uthmani_word}»: قلقلتَ حرفاً ساكناً غير مقلقل.")
            elif ex == "ڇ" and ac == "":
                tips.append(f"خطأ في التجويد في «{uthmani_word}»: تركتَ القلقلة الواجبة في الحرف الساكن.")
            elif ex == "ں" or ac == "ں":
                tips.append(f"خطأ في التجويد (الإخفاء الحقيقي) في «{uthmani_word}»: نقص في غنة الإخفاء (مقدارها حركتان).")
            elif ex == "۾" or ac == "۾":
                tips.append(f"خطأ في التجويد (الغنة الشفوية) في «{uthmani_word}»: نقص في الغنة الواجبة.")
            elif ex in MADD_SET or ac in MADD_SET:
                if op == "delete":
                    tips.append(f"خطأ في التجويد (المد) في «{uthmani_word}»: قصر أو إسقاط حرف المد عن مقداره المطلوب.")
                elif op == "insert":
                    tips.append(f"خطأ في التجويد (المد) في «{uthmani_word}»: إشباع الحركة القصيرة وتوليد حرف مد زائد.")
                else:
                    tips.append(f"خطأ في التجويد (المد) في «{uthmani_word}»: تغيّر في حرف المد أو مقداره.")
        else:
            if op == "delete" and (word_idx in (9, 11, 34) or (ex and expected_ph.count(ex) >= 2)):
                has_tajweed = True
                tips.append(f"خطأ في التجويد (الإدغام/التشديد) في «{uthmani_word}»: ترك تشديد أو إدغام حرف «{ex}».")
            else:
                has_normal = True
                if op == "replace":
                    tips.append(f"خطأ في مخرج الحرف في «{uthmani_word}»: نطقتَ «{ac}» بدلاً من «{ex}».")
                elif op == "delete":
                    tips.append(f"خطأ في الحروف في «{uthmani_word}»: إسقاط حرف «{ex}».")
                elif op == "insert":
                    tips.append(f"خطأ في الحروف في «{uthmani_word}»: زيادة حرف «{ac}».")

    if has_normal:
        err_type = "normal"
    elif has_tashkeel:
        err_type = "tashkeel"
    elif has_tajweed:
        err_type = "tajweed"
    else:
        err_type = "normal"

    exp_len, pred_len = estimate_qps_harakat_lengths(expected_ph, predicted_ph, err_type)
    unique_tips = list(dict.fromkeys(tips))
    tip_str = " ".join(unique_tips[:2]) if unique_tips else f"راجع نطق «{uthmani_word}» (المتوقع: {expected_ph}، المسموع: {predicted_ph})."
    return err_type, speech_op, exp_len, pred_len, tip_str


def serialize_sifat_list(sifat_objs: Any) -> List[Dict[str, Any]]:
    """
    Extracts all 10 Sifat levels (`ghonna`, `qalqla`, `tafkheem_or_taqeeq`, `hams_or_jahr`,
    `shidda_or_rakhawa`, `itbaq`, `safeer`, `tikraar`, `tafashie`, `istitala`) from `out.sifat`.
    """
    if not sifat_objs:
        return []
    serialized: List[Dict[str, Any]] = []
    for s in sifat_objs:
        grp = getattr(s, "phonemes_group", None) or (s.get("phonemes_group") if isinstance(s, dict) else "")
        entry: Dict[str, Any] = {"phonemes_group": grp or ""}
        for level in SIFAT_LEVEL_NAMES:
            val = getattr(s, level, None) if not isinstance(s, dict) else s.get(level)
            if val is not None:
                text = getattr(val, "text", None) or (val.get("text") if isinstance(val, dict) else None)
                prob = getattr(val, "prob", None) or (val.get("prob") if isinstance(val, dict) else 0.0)
                entry[level] = {"text": text, "prob": round(float(prob or 0.0), 4)}
            else:
                entry[level] = None
        serialized.append(entry)
    return serialized


def audit_10_sifat_levels_for_word(
    word_idx: int,
    uthmani_word: str,
    word_span: Tuple[int, int],
    expected_ph: str,
    predicted_ph: str,
    sifat_slice: List[Dict[str, Any]],
    confidence_threshold: float = 0.85,
) -> Optional[Dict[str, Any]]:
    """
    Audits the 10 Sifat levels (`ghonna`, `qalqla`, `tafkheem_or_taqeeq`, `hams_or_jahr`,
    `shidda_or_rakhawa`, `itbaq`, `safeer`, `tikraar`, `tafashie`, `istitala`) on a word
    whose phoneme letters matched, catching subtle Tajweed/Sifat mistakes.
    """
    if not sifat_slice:
        return None

    TAFKHEEM_LETTERS = set("صضطظقخغ")
    TARQEEQ_LETTERS = set("تذسشعحفك")
    ITBAQ_LETTERS = set("صضطظ")
    SAFEER_LETTERS = set("صزس")
    QALQALAH_LETTERS = set("قطبجد")

    for s_obj in sifat_slice:
        grp = s_obj.get("phonemes_group", "")
        if not grp:
            continue
        base_c = grp[0]

        # 1. Tafkheem / Tarqeeq
        tt = s_obj.get("tafkheem_or_taqeeq")
        if tt and float(tt.get("prob", 0.0)) >= confidence_threshold:
            tt_text = tt.get("text")
            if base_c in TAFKHEEM_LETTERS and tt_text == "moraqaq":
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "replace",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 1,
                    "correction_tip": f"خطأ في صفات الحروف (التفخيم): رقّقتَ حرف الاستعلاء «{base_c}» في «{uthmani_word}» وحقه التفخيم.",
                }
            if base_c in TARQEEQ_LETTERS and tt_text == "mofakham":
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "replace",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 1,
                    "correction_tip": f"خطأ في صفات الحروف (الترقيق): فخّمتَ الحرف المرقق «{base_c}» في «{uthmani_word}» وحقه الترقيق.",
                }

        # 2. Itbaq
        itb = s_obj.get("itbaq")
        if itb and base_c in ITBAQ_LETTERS and float(itb.get("prob", 0.0)) >= confidence_threshold:
            if itb.get("text") == "monfateh":
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "replace",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 1,
                    "correction_tip": f"خطأ في صفة الإطباق: أضعفتَ إطباق اللسان في حرف «{base_c}» من «{uthmani_word}».",
                }

        # 3. Qalqalah
        qlq = s_obj.get("qalqla")
        if qlq and float(qlq.get("prob", 0.0)) >= confidence_threshold:
            if qlq.get("text") == "moqalqal" and base_c not in QALQALAH_LETTERS:
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "insert",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 2,
                    "correction_tip": f"خطأ في التجويد (القلقلة): قلقلتَ الحرف الساكن «{base_c}» في «{uthmani_word}» وليس من حروف (قطب جد).",
                }

        # 4. Safeer
        saf = s_obj.get("safeer")
        if saf and base_c in SAFEER_LETTERS and float(saf.get("prob", 0.0)) >= confidence_threshold:
            if saf.get("text") == "no_safeer":
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "replace",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 1,
                    "correction_tip": f"خطأ في صفة الصفير: ضعف صفير حرف «{base_c}» في «{uthmani_word}».",
                }

        # 5. Tafashie (ش)
        taf = s_obj.get("tafashie")
        if taf and base_c == "ش" and float(taf.get("prob", 0.0)) >= confidence_threshold:
            if taf.get("text") == "not_motafashie":
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "replace",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 1,
                    "correction_tip": f"خطأ في صفة التفشي: أضعفتَ تفشّي الشين في «{uthmani_word}».",
                }

        # 6. Istitala (ض)
        ist = s_obj.get("istitala")
        if ist and base_c == "ض" and len(grp) == 1 and float(ist.get("prob", 0.0)) >= confidence_threshold:
            if ist.get("text") == "not_mostateel":
                pos = refine_uthmani_char_pos(uthmani_word, word_span, "tajweed", base_c, base_c)
                return {
                    "uthmani_pos": pos,
                    "word_index": word_idx,
                    "uthmani_word": uthmani_word,
                    "error_type": "tajweed",
                    "speech_error_type": "replace",
                    "expected_ph": expected_ph,
                    "predicted_ph": predicted_ph,
                    "expected_len": 1,
                    "predicted_len": 1,
                    "correction_tip": f"خطأ في صفة الاستطالة: أضعفتَ استطالة الضاد الساكنة في «{uthmani_word}».",
                }

    return None


def align_and_diagnose_ayat_kursi(
    predicted_phonemes: str,
    sifat_serialized: List[Dict[str, Any]],
    is_live_partial: bool = False,
) -> Dict[str, Any]:
    """
    Coupled Waqf/Wasl/Ibtida QPS Aligner for Ayat al-Kursi (2:255).
    - Supports both full-verse evaluation (`is_live_partial=False`) and live streaming
      prefix evaluation (`is_live_partial=True`) where the reciter has only spoken the first
      `k` words so far (preventing unreached words from being flagged as missing).
    - Returns `uthmani_text`, `predicted_phonemes`, `reference_phonemes`, `completed_words`,
      `words_status`, and the exact `errors` list with `uthmani_pos: [start_char, end_char]`.
    """
    words, word_spans = compute_word_char_spans(AYAT_KURSI_UTHMANI_TEXT)
    N = len(words)
    L = len(predicted_phonemes)
    INF = 1e9

    if L == 0:
        return {
            "uthmani_text": AYAT_KURSI_UTHMANI_TEXT,
            "predicted_phonemes": "",
            "reference_phonemes": "",
            "completed_words": 0,
            "uthmani_char_progress": 0,
            "errors": [],
            "words_audit": [],
        }

    forms: Dict[Tuple[int, bool, bool], List[str]] = {}
    min_w_len: Dict[int, int] = {}
    max_w_len: Dict[int, int] = {}
    for w in range(N):
        for ib in (False, True):
            for wq in (False, True):
                flist = get_valid_qps_ayat_kursi(w, ibtida=ib, waqf=wq, lenient=True)
                forms[(w, ib, wq)] = flist
        all_lens = [len(s) for ib in (False, True) for wq in (False, True) for s in forms[(w, ib, wq)]]
        min_w_len[w] = min(all_lens)
        max_w_len[w] = max(all_lens)

    valid_boundary = [True] * (L + 1)
    for p in range(1, L):
        if predicted_phonemes[p] in NON_ONSET_CHARS:
            valid_boundary[p] = False

    dp = [[[INF, INF] for _ in range(L + 1)] for _ in range(N + 1)]
    back: List[List[List[Optional[Tuple[int, int, bool, str, str]]]]] = [
        [[None, None] for _ in range(L + 1)] for _ in range(N + 1)
    ]
    dp[0][0][1] = 0.0

    ed_cache: Dict[Tuple[str, str], float] = {}

    def fast_ed(form: str, sub: str) -> float:
        if form == sub:
            return 0.0
        key = (form, sub)
        if key not in ed_cache:
            ed_cache[key] = edit_distance_ops(form, sub)[0]
        return ed_cache[key]

    for w in range(N):
        best_at_w = min(dp[w][p][q] for p in range(L + 1) for q in (0, 1))
        if best_at_w >= INF:
            break
        beam_cutoff = best_at_w + 8.5
        min_span = max(1, min_w_len[w] - 2)
        max_span = max_w_len[w] + 3

        for pos in range(L + 1):
            if not valid_boundary[pos]:
                continue
            for prev_wq in (0, 1):
                cur_cost = dp[w][pos][prev_wq]
                if cur_cost > beam_cutoff:
                    continue
                ib = prev_wq == 1

                for wq in (False, True):
                    wq_idx = 1 if wq else 0
                    flist = forms[(w, ib, wq)]
                    tie_bias = 0.0 if (wq and pos + min_w_len[w] >= L - 1) or (not wq) else 0.02

                    for f in flist:
                        lf = len(f)
                        np_exact = pos + lf
                        if np_exact <= L and valid_boundary[np_exact] and predicted_phonemes[pos:np_exact] == f:
                            total = cur_cost + tie_bias
                            if total < dp[w + 1][np_exact][wq_idx]:
                                dp[w + 1][np_exact][wq_idx] = total
                                back[w + 1][np_exact][wq_idx] = (pos, prev_wq, wq, f, f)

                    lo = min(L + 1, pos + min_span)
                    hi = min(L, pos + max_span)
                    canonical_forms = [flist[0]] if len(flist) == 1 else [flist[0], flist[-1]]
                    for next_pos in range(lo, hi + 1):
                        if not valid_boundary[next_pos]:
                            continue
                        sub = predicted_phonemes[pos:next_pos]
                        best_f_cost = INF
                        best_f = flist[0]
                        for f in canonical_forms:
                            if abs(len(f) - len(sub)) * 0.70 >= best_f_cost:
                                continue
                            c = fast_ed(f, sub)
                            if c < best_f_cost:
                                best_f_cost = c
                                best_f = f
                        total = cur_cost + best_f_cost + tie_bias
                        if total < dp[w + 1][next_pos][wq_idx]:
                            dp[w + 1][next_pos][wq_idx] = total
                            back[w + 1][next_pos][wq_idx] = (pos, prev_wq, wq, best_f, sub)

                skip_ref = forms[(w, ib, False)][0]
                skip_cost = len(skip_ref) * 0.88 + 0.65
                if cur_cost + skip_cost < dp[w + 1][pos][prev_wq]:
                    dp[w + 1][pos][prev_wq] = cur_cost + skip_cost
                    back[w + 1][pos][prev_wq] = (pos, prev_wq, False, skip_ref, "")

    # Determine how many words were spoken (either full N or best prefix 1..N when streaming live)
    if is_live_partial:
        best_w = 1
        best_wq = 1
        best_score = INF
        for cand_w in range(1, N + 1):
            for q in (0, 1):
                c = dp[cand_w][L][q]
                if c < best_score:
                    best_score = c
                    best_w = cand_w
                    best_wq = q
        target_w_count = best_w
        end_wq = best_wq
    else:
        # Even on POST /correct-recitation, if user recited a partial segment (e.g., 1st sentence),
        # check if a prefix matches significantly better than forcing 40 unrecited skipped words
        best_prefix_w = N
        best_prefix_wq = 1 if dp[N][L][1] <= dp[N][L][0] else 0
        best_prefix_cost = dp[N][L][best_prefix_wq]
        for cand_w in range(1, N + 1):
            for q in (0, 1):
                # Small penalty for unrecited tail only if comparable
                c = dp[cand_w][L][q] + (N - cand_w) * 0.12
                if c < best_prefix_cost:
                    best_prefix_cost = c
                    best_prefix_w = cand_w
                    best_prefix_wq = q
        target_w_count = best_prefix_w
        end_wq = best_prefix_wq

    if dp[target_w_count][L][end_wq] >= INF:
        return {
            "uthmani_text": AYAT_KURSI_UTHMANI_TEXT,
            "predicted_phonemes": predicted_phonemes,
            "reference_phonemes": "",
            "completed_words": 0,
            "uthmani_char_progress": 0,
            "errors": [],
            "words_audit": [],
        }

    # Backtrack over 0..target_w_count-1
    word_entries: List[Optional[Dict[str, Any]]] = [None] * target_w_count
    cur_pos = L
    cur_wq = end_wq
    for w in range(target_w_count - 1, -1, -1):
        step = back[w + 1][cur_pos][cur_wq]
        if step is None:
            break
        prev_pos, prev_wq, wq, best_f, sub = step
        word_entries[w] = {
            "w": w,
            "word": words[w],
            "waqf": wq,
            "expected_ph": best_f,
            "predicted_ph": sub,
            "ph_span": (prev_pos, cur_pos),
        }
        cur_pos = prev_pos
        cur_wq = prev_wq

    # Map Sifat spans to phoneme character offsets
    sifat_spans: List[Tuple[int, int, Dict[str, Any]]] = []
    s_cursor = 0
    for s_obj in sifat_serialized:
        grp = s_obj.get("phonemes_group", "")
        s_end = s_cursor + len(grp)
        sifat_spans.append((s_cursor, s_end, s_obj))
        s_cursor = s_end

    errors_out: List[Dict[str, Any]] = []
    words_audit_out: List[Dict[str, Any]] = []
    ref_ph_parts: List[str] = []

    for w in range(target_w_count):
        entry = word_entries[w]
        if entry is None:
            continue
        exp_ph = entry["expected_ph"]
        pred_ph = entry["predicted_ph"]
        p0, p1 = entry["ph_span"]
        ref_ph_parts.append(exp_ph)
        w_span = word_spans[w]

        cost, ops = edit_distance_ops(exp_ph, pred_ph)
        if cost > 0.0:
            err_type, speech_op, exp_len, pred_len, tip = classify_qps_ops(
                w, words[w], exp_ph, pred_ph, ops
            )
            u_pos = refine_uthmani_char_pos(words[w], w_span, err_type, exp_ph, pred_ph)
            err_obj = {
                "uthmani_pos": u_pos,
                "word_index": w,
                "uthmani_word": words[w],
                "error_type": err_type,
                "speech_error_type": speech_op,
                "expected_ph": exp_ph,
                "predicted_ph": pred_ph,
                "expected_len": exp_len,
                "predicted_len": pred_len,
                "correction_tip": tip,
            }
            errors_out.append(err_obj)
            words_audit_out.append({
                "w": w,
                "word": words[w],
                "ok": False,
                "error_type": err_type,
                "uthmani_pos": u_pos,
                "expected_ph": exp_ph,
                "predicted_ph": pred_ph,
                "expected_len": exp_len,
                "predicted_len": pred_len,
                "correction_tip": tip,
            })
        else:
            # Phoneme characters matched -> audit the 10 Sifat levels for subtle Tajweed errors
            word_sifat = [
                s_obj for (s0, s1, s_obj) in sifat_spans if s1 > p0 and s0 < p1
            ]
            sifa_err = audit_10_sifat_levels_for_word(
                w, words[w], w_span, exp_ph, pred_ph, word_sifat
            )
            if sifa_err is not None:
                errors_out.append(sifa_err)
                words_audit_out.append({
                    "w": w,
                    "word": words[w],
                    "ok": False,
                    "error_type": "tajweed",
                    "uthmani_pos": sifa_err["uthmani_pos"],
                    "expected_ph": exp_ph,
                    "predicted_ph": pred_ph,
                    "expected_len": sifa_err["expected_len"],
                    "predicted_len": sifa_err["predicted_len"],
                    "correction_tip": sifa_err["correction_tip"],
                })
            else:
                words_audit_out.append({
                    "w": w,
                    "word": words[w],
                    "ok": True,
                    "error_type": None,
                    "uthmani_pos": [w_span[0], w_span[1]],
                    "expected_ph": exp_ph,
                    "predicted_ph": pred_ph,
                    "expected_len": len(exp_ph),
                    "predicted_len": len(pred_ph),
                    "correction_tip": "",
                })

    char_progress = word_spans[target_w_count - 1][1] if target_w_count > 0 else 0
    return {
        "uthmani_text": AYAT_KURSI_UTHMANI_TEXT,
        "predicted_phonemes": predicted_phonemes,
        "reference_phonemes": "".join(ref_ph_parts),
        "completed_words": target_w_count,
        "uthmani_char_progress": char_progress,
        "errors": errors_out,
        "words_audit": words_audit_out,
    }


def decode_audio_bytes_to_float32(raw_bytes: bytes, target_sr: int = SAMPLE_RATE) -> np.ndarray:
    """
    Decodes either a RIFF WAV payload or raw 16-bit Mono PCM bytes into a normalized
    16kHz float32 numpy waveform.
    """
    if not raw_bytes:
        return np.zeros(0, dtype=np.float32)

    if len(raw_bytes) >= 44 and raw_bytes[:4] == b"RIFF" and raw_bytes[8:12] == b"WAVE":
        try:
            with wave.open(io.BytesIO(raw_bytes), "rb") as wf:
                sr = wf.getframerate()
                n_ch = wf.getnchannels()
                sw = wf.getsampwidth()
                frames = wf.readframes(wf.getnframes())
                if sw == 2:
                    arr = np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0
                else:
                    arr = np.frombuffer(frames, dtype=np.uint8).astype(np.float32)
                    arr = (arr - 128.0) / 128.0
                if n_ch > 1:
                    arr = arr.reshape(-1, n_ch).mean(axis=1)
                if sr != target_sr and arr.size > 0:
                    import librosa
                    arr = librosa.resample(arr, orig_sr=sr, target_sr=target_sr)
                return arr.astype(np.float32)
        except Exception:
            pass

    # Fallback: treat as raw 16kHz 16-bit little-endian Mono PCM
    even_len = (len(raw_bytes) // 2) * 2
    if even_len == 0:
        return np.zeros(0, dtype=np.float32)
    arr = np.frombuffer(raw_bytes[:even_len], dtype="<i2").astype(np.float32) / 32768.0
    return arr


def preprocess_waveform(wave_arr: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    if wave_arr.size == 0:
        return wave_arr
    wave_arr = wave_arr.astype(np.float32)
    wave_arr = wave_arr - float(np.mean(wave_arr))
    peak = float(np.max(np.abs(wave_arr)))
    if peak > 1e-4:
        wave_arr = (wave_arr / peak) * 0.92
    return wave_arr


def evaluate_generic_aya_with_transcript(
    sura_idx: int,
    aya_idx: int,
    uthmani_ref: str,
    ref_ph_text: str,
    predicted_ph_text: str,
    mappings: Any,
) -> List[Dict[str, Any]]:
    """
    Converts `quran_transcript.explain_error` outputs for any Surah/Ayah into the standardized
    error schema (`uthmani_pos`, `error_type`, `speech_error_type`, `expected_ph`, `predicted_ph`,
    `expected_len`, `predicted_len`, `correction_tip`).
    """
    from quran_transcript import explain_error

    raw_errs = explain_error(
        uthmani_text=uthmani_ref,
        ref_ph_text=ref_ph_text,
        predicted_ph_text=predicted_ph_text,
        mappings=mappings,
    )
    words, word_spans = compute_word_char_spans(uthmani_ref)
    formatted_errors: List[Dict[str, Any]] = []

    for item in raw_errs:
        d = asdict(item) if hasattr(item, "__dataclass_fields__") else (item if isinstance(item, dict) else {})
        u_pos = list(d.get("uthmani_pos", [0, min(1, len(uthmani_ref))]))
        if len(u_pos) < 2:
            u_pos = [0, min(1, len(uthmani_ref))]
        start_c, end_c = int(u_pos[0]), int(u_pos[1])

        w_idx = 0
        for idx, (ws, we) in enumerate(word_spans):
            if start_c < we and end_c > ws:
                w_idx = idx
                break
        u_word = words[w_idx] if w_idx < len(words) else ""

        exp_ph = str(d.get("expected_ph", "") or "")
        pred_ph = str(d.get("predicted_ph", d.get("preditected_ph", "")) or "")
        _, ops = edit_distance_ops(exp_ph, pred_ph)
        err_type, speech_op, exp_len, pred_len, tip = classify_qps_ops(
            w_idx, u_word, exp_ph, pred_ph, ops
        )
        raw_err_type = str(d.get("error_type", "")).lower()
        if raw_err_type in ("tajweed", "tashkeel", "normal"):
            err_type = raw_err_type
        raw_speech_op = str(d.get("speech_error_type", "")).lower()
        if raw_speech_op in ("replace", "insert", "delete"):
            speech_op = raw_speech_op

        formatted_errors.append({
            "uthmani_pos": [start_c, end_c],
            "word_index": w_idx,
            "uthmani_word": u_word,
            "error_type": err_type,
            "speech_error_type": speech_op,
            "expected_ph": exp_ph,
            "predicted_ph": pred_ph,
            "expected_len": exp_len,
            "predicted_len": pred_len,
            "correction_tip": tip,
        })
    return formatted_errors


# ==============================================================================
# Modal.com Serverless GPU Image & ASGI App Configuration (`gpu="T4"`)
# ==============================================================================
if modal is not None:
    app = modal.App("rafiq-quran-muaalem-backend")

    def download_muaalem_weights() -> None:
        import torch
        from quran_muaalem import Muaalem
        from quran_transcript import Aya, MoshafAttributes, quran_phonetizer

        device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.bfloat16 if device == "cuda" else torch.float32
        Muaalem(model_name_or_path=MODEL_ID, device=device, dtype=dtype)
        moshaf = MoshafAttributes(
            rewaya="hafs",
            madd_monfasel_len=4,
            madd_mottasel_len=4,
            madd_mottasel_waqf=4,
            madd_aared_len=4,
        )
        uth = Aya(2, 255).get().uthmani
        quran_phonetizer(uth, moshaf, remove_spaces=True)

    muaalem_image = (
        modal.Image.debian_slim(python_version="3.11")
        .apt_install("ffmpeg", "libsndfile1")
            .pip_install(
            "quran-muaalem>=0.2.2",
            "transformers>=4.45.0,<5.0.0",
            "accelerate",
            "quran-transcript",
            "librosa",
            "torch",
            "fastapi[standard]",
            "websockets",
            "numba>=0.61.2",
            "soundfile",
            "numpy",
        )
        .run_function(download_muaalem_weights)
    )
else:
    app = None
    muaalem_image = None


class MuaalemEngineRuntime:
    """
    Singleton GPU runtime holding the pre-initialized `Muaalem` (`obadx/muaalem-model-v3_2`)
    and `MoshafAttributes` (`rewaya="hafs"`, `madd_*=4`).
    """

    def __init__(self) -> None:
        self.muaalem = None
        self.moshaf = None
        self._aya_cache: Dict[Tuple[int, int], Tuple[str, Any]] = {}

    def ensure_loaded(self) -> None:
        if self.muaalem is not None and self.moshaf is not None:
            return
        import torch
        from quran_muaalem import Muaalem
        from quran_transcript import MoshafAttributes

        device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.bfloat16 if device == "cuda" else torch.float32
        self.muaalem = Muaalem(
            model_name_or_path=MODEL_ID,
            device=device,
            dtype=dtype,
        )
        self.moshaf = MoshafAttributes(
            rewaya="hafs",
            madd_monfasel_len=4,
            madd_mottasel_len=4,
            madd_mottasel_waqf=4,
            madd_aared_len=4,
        )

    def get_reference(self, sura_idx: int, aya_idx: int) -> Tuple[str, Any]:
        self.ensure_loaded()
        key = (sura_idx, aya_idx)
        if key not in self._aya_cache:
            from quran_transcript import Aya, quran_phonetizer

            uthmani_ref = Aya(sura_idx, aya_idx).get().uthmani
            phonetized_ref = quran_phonetizer(uthmani_ref, self.moshaf, remove_spaces=True)
            self._aya_cache[key] = (uthmani_ref, phonetized_ref)
        return self._aya_cache[key]

    def run_inference(
        self,
        wave_arr: np.ndarray,
        sura_idx: int = 2,
        aya_idx: int = 255,
        is_live_partial: bool = False,
    ) -> Dict[str, Any]:
        self.ensure_loaded()
        uthmani_ref, full_ref_out = self.get_reference(sura_idx, aya_idx)
        clean_wave = preprocess_waveform(wave_arr, SAMPLE_RATE)
        if clean_wave.size < int(SAMPLE_RATE * 0.25):
            return {
                "uthmani_text": uthmani_ref,
                "predicted_phonemes": "",
                "reference_phonemes": full_ref_out.phonemes,
                "completed_words": 0,
                "uthmani_char_progress": 0,
                "sifat": [],
                "errors": [],
                "words_audit": [],
            }

        outs = self.muaalem([clean_wave], [full_ref_out], sampling_rate=SAMPLE_RATE)
        out = outs[0]
        predicted_ph = out.phonemes.text if hasattr(out, "phonemes") else ""
        sifat_list = serialize_sifat_list(getattr(out, "sifat", None))

        if sura_idx == 2 and aya_idx == 255:
            diag = align_and_diagnose_ayat_kursi(
                predicted_phonemes=predicted_ph,
                sifat_serialized=sifat_list,
                is_live_partial=is_live_partial,
            )
            diag["sifat"] = sifat_list
            if not diag["reference_phonemes"]:
                diag["reference_phonemes"] = full_ref_out.phonemes
            return diag

        errs = evaluate_generic_aya_with_transcript(
            sura_idx=sura_idx,
            aya_idx=aya_idx,
            uthmani_ref=uthmani_ref,
            ref_ph_text=full_ref_out.phonemes,
            predicted_ph_text=predicted_ph,
            mappings=full_ref_out.mappings,
        )
        return {
            "uthmani_text": uthmani_ref,
            "predicted_phonemes": predicted_ph,
            "reference_phonemes": full_ref_out.phonemes,
            "completed_words": uthmani_ref.count(" ") + 1,
            "uthmani_char_progress": len(uthmani_ref),
            "sifat": sifat_list,
            "errors": errs,
            "words_audit": [],
        }


_RUNTIME = MuaalemEngineRuntime()


def build_fastapi_app():
    from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile, WebSocket, WebSocketDisconnect
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.responses import JSONResponse

    api = FastAPI(
        title="Rafiq Quran-Muaalem v0.2.2 Serverless GPU Backend",
        version="12.0.0",
    )
    api.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @api.on_event("startup")
    async def startup_load_model() -> None:
        try:
            _RUNTIME.ensure_loaded()
        except Exception:
            pass

    @api.get("/health")
    async def health() -> Dict[str, Any]:
        return {
            "status": "ok",
            "engine": "quran-muaalem-v0.2.2",
            "model": MODEL_ID,
            "sifat_levels": list(SIFAT_LEVEL_NAMES),
        }

    @api.post("/correct-recitation")
    async def correct_recitation(
        file: UploadFile = File(...),
        sura_idx: int = Form(2),
        aya_idx: int = Form(255),
        is_partial: bool = Form(False),
        q_sura_idx: Optional[int] = Query(None, alias="sura_idx"),
        q_aya_idx: Optional[int] = Query(None, alias="aya_idx"),
        q_is_partial: Optional[bool] = Query(None, alias="is_partial"),
    ) -> JSONResponse:
        sura = q_sura_idx if q_sura_idx is not None else sura_idx
        aya = q_aya_idx if q_aya_idx is not None else aya_idx
        partial = q_is_partial if q_is_partial is not None else is_partial

        raw_bytes = await file.read()
        if not raw_bytes:
            raise HTTPException(status_code=400, detail="ملف الصوت المرسل فارغ")

        wave_arr = decode_audio_bytes_to_float32(raw_bytes, SAMPLE_RATE)
        if wave_arr.size < int(SAMPLE_RATE * 0.25):
            raise HTTPException(status_code=400, detail="التسجيل الصوتي قصير جداً")

        result = _RUNTIME.run_inference(
            wave_arr=wave_arr,
            sura_idx=sura,
            aya_idx=aya,
            is_live_partial=partial,
        )
        return JSONResponse(result)

    @api.websocket("/ws/live-recitation")
    async def ws_live_recitation(websocket: WebSocket) -> None:
        """
        Real-Time Waqf-Segmented Streaming WebSocket Endpoint:
        - Accepts query params `?sura_idx=2&aya_idx=255` or JSON config messages.
        - Receives streaming 16kHz 16-bit Mono PCM chunks (binary frames or base64 JSON frames).
        - Automatically detects micro-pauses (waqf) or word boundaries (or explicit `eval` triggers)
          and runs rolling `quran_phonetizer` + `Muaalem` multi-level CTC inference (Phonemes + 10 Sifat),
          pushing real-time JSON diffs back to the Android client immediately.
        """
        await websocket.accept()
        sura_idx = int(websocket.query_params.get("sura_idx", "2"))
        aya_idx = int(websocket.query_params.get("aya_idx", "255"))

        rolling_pcm = bytearray()
        samples_since_last_eval = 0
        silence_samples_run = 0
        voiced_samples_seen = 0
        seq_id = 0

        try:
            while True:
                msg = await websocket.receive()
                if msg.get("type") == "websocket.disconnect":
                    break

                trigger_now = False
                is_final_flush = False

                if msg.get("bytes") is not None:
                    chunk_bytes: bytes = msg["bytes"]
                    rolling_pcm.extend(chunk_bytes)
                    n_new_samples = len(chunk_bytes) // 2
                    samples_since_last_eval += n_new_samples

                    if n_new_samples > 0:
                        arr = np.frombuffer(chunk_bytes[: n_new_samples * 2], dtype="<i2").astype(np.float32) / 32768.0
                        rms = float(np.sqrt(np.mean(arr * arr) + 1e-12))
                        if rms >= 0.008:
                            voiced_samples_seen += n_new_samples
                            silence_samples_run = 0
                        else:
                            silence_samples_run += n_new_samples

                    # Auto-trigger evaluation on micro-pause (waqf >= 260ms after speech)
                    # or rolling word boundary (~0.95s of new audio)
                    if voiced_samples_seen >= int(SAMPLE_RATE * 0.35):
                        if (silence_samples_run >= int(SAMPLE_RATE * 0.26) and samples_since_last_eval >= int(SAMPLE_RATE * 0.25)) or (
                            samples_since_last_eval >= int(SAMPLE_RATE * 0.95)
                        ):
                            trigger_now = True

                elif msg.get("text") is not None:
                    try:
                        payload = json.loads(msg["text"])
                    except Exception:
                        continue
                    mtype = payload.get("type", "")
                    if mtype == "config":
                        sura_idx = int(payload.get("sura_idx", sura_idx))
                        aya_idx = int(payload.get("aya_idx", aya_idx))
                        if payload.get("reset", False):
                            rolling_pcm.clear()
                            samples_since_last_eval = 0
                            silence_samples_run = 0
                            voiced_samples_seen = 0
                        continue
                    elif mtype == "reset":
                        rolling_pcm.clear()
                        samples_since_last_eval = 0
                        silence_samples_run = 0
                        voiced_samples_seen = 0
                        continue
                    elif mtype == "audio_chunk":
                        b64 = payload.get("pcm_b64") or payload.get("audio_b64") or ""
                        if b64:
                            chunk_bytes = base64.b64decode(b64)
                            rolling_pcm.extend(chunk_bytes)
                            samples_since_last_eval += len(chunk_bytes) // 2
                        trigger_now = bool(payload.get("trigger_eval", False))
                    elif mtype in ("eval", "waqf", "flush", "stop"):
                        trigger_now = True
                        is_final_flush = mtype in ("flush", "stop")

                if trigger_now and len(rolling_pcm) >= int(SAMPLE_RATE * 0.35) * 2:
                    samples_since_last_eval = 0
                    seq_id += 1
                    wave_arr = decode_audio_bytes_to_float32(bytes(rolling_pcm), SAMPLE_RATE)
                    diff = _RUNTIME.run_inference(
                        wave_arr=wave_arr,
                        sura_idx=sura_idx,
                        aya_idx=aya_idx,
                        is_live_partial=not is_final_flush,
                    )
                    diff["type"] = "live_diff"
                    diff["seq"] = seq_id
                    diff["is_final"] = is_final_flush
                    await websocket.send_text(json.dumps(diff, ensure_ascii=False))

        except WebSocketDisconnect:
            pass
        except Exception as exc:
            try:
                await websocket.send_text(
                    json.dumps({"type": "error", "message": str(exc)}, ensure_ascii=False)
                )
            except Exception:
                pass

    return api


if modal is not None and app is not None and muaalem_image is not None:
    @app.function(
        image=muaalem_image,
        scaledown_window=300,
        timeout=600,
    )
    @modal.asgi_app()
    def fastapi_asgi_app():
        _RUNTIME.ensure_loaded()
        return build_fastapi_app()
