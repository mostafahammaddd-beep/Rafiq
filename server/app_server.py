"""
Rafiq v5.0 — Zero-Tolerance Hybrid Quranic Recitation Auditor Server
Combines:
1. Coupled Waqf/Wasl/Ibtida QPS Dynamic Programming Aligner
2. Short-Window Context-Breaking Inference (prevents Wav2Vec2-BERT Transformer from auto-correcting verse errors via long context)
3. Goodness of Pronunciation (GOP) Token Probability Auditor (`out.phonemes.probs`) for Harakat (<0.82) & Tajweed/Letters (<0.75)
4. 10-Level Sifat Auditor (Tafkheem/Tarqeeq, Itbaq, Qalqalah / False Qalqalah)
5. Physical Acoustic-Phonetic & Formant (F1/F2) + Madd Timer + Ghunnah + Sibilance/Tafashie Auditor via Viterbi Forced Alignment
"""
import time
import numpy as np

HARAKAT = set("َُؙِ۪")
MADD_CHARS = set("اۥۦـ")
NON_ONSET_CHARS = HARAKAT | MADD_CHARS | {"ڇ"}

# Legitimate Waqf (pause) positions in Ayat al-Kursi (end of clauses / breath groups)
VALID_WAQF_WORDS = {4, 5, 6, 11, 15, 18, 25, 29, 31, 39, 43, 46, 48, 49}
NATURAL_WAQF_SET = {6, 11, 18, 25, 31, 39, 43, 46, 49}

WORDS_AR = [
    "اللَّهُ", "لَا", "إِلَٰهَ", "إِلَّا", "هُوَ", "الْحَيُّ", "الْقَيُّومُ",
    "لَا", "تَأْخُذُهُ", "سِنَةٌ", "وَلَا", "نَوْمٌ",
    "لَهُ", "مَا", "فِي", "السَّمَاوَاتِ", "وَمَا", "فِي", "الْأَرْضِ",
    "مَنْ", "ذَا", "الَّذِي", "يَشْفَعُ", "عِنْدَهُ", "إِلَّا", "بِإِذْنِهِ",
    "يَعْلَمُ", "مَا", "بَيْنَ", "أَيْدِيهِمْ", "وَمَا", "خَلْفَهُمْ",
    "وَلَا", "يُحِيطُونَ", "بِشَيْءٍ", "مِنْ", "عِلْمِهِ", "إِلَّا", "بِمَا", "شَاءَ",
    "وَسِعَ", "كُرْسِيُّهُ", "السَّمَاوَاتِ", "وَالْأَرْضَ",
    "وَلَا", "يَئُودُهُ", "حِفْظُهُمَا", "وَهُوَ", "الْعَلِيُّ", "الْعَظِيمُ"
]

WORD_ACOUSTIC_SPECS = [
    # (w, word, wasl_vowels, waqf_vowels, min_harakat, madd_type, req_ghunnah, interdental, safeer, tafashie, tafkheem, internal_sakin)
    (0,  "اللَّهُ",       ["a", "aa", "u"],        ["a", "aa"],             4.0, "TABEEI",   False, False, False, False, True,  False),
    (1,  "لَا",           ["aa"],                  ["aa"],                  2.0, "MUNFASIL", False, False, False, False, False, False),
    (2,  "إِلَٰهَ",       ["i", "aa", "a"],        ["i", "aa"],             4.0, "TABEEI",   False, False, False, False, False, False),
    (3,  "إِلَّا",        ["i", "aa"],             ["i", "aa"],             3.5, "TABEEI",   False, False, False, False, False, False),
    (4,  "هُوَ",          ["u", "a"],              ["u"],                   2.0, "NONE",     False, False, False, False, False, False),
    (5,  "الْحَيُّ",      ["a", "u"],              ["a"],                   2.8, "NONE",     False, False, False, False, False, False),
    (6,  "الْقَيُّومُ",   ["a", "uu", "u"],        ["a", "uu"],             4.5, "TABEEI",   False, False, False, False, True,  False),
    (7,  "لَا",           ["aa"],                  ["aa"],                  2.0, "TABEEI",   False, False, False, False, False, False),
    (8,  "تَأْخُذُهُ",    ["a", "u", "u", "uu"],   ["a", "u", "u"],         4.5, "TABEEI",   False, True,  False, False, True,  True),
    (9,  "سِنَةٌ",        ["i", "a", "u"],         ["i", "a"],              3.5, "NONE",     True,  False, True,  False, False, False),
    (10, "وَلَا",         ["a", "aa"],             ["a", "aa"],             3.0, "TABEEI",   False, False, False, False, False, False),
    (11, "نَوْمٌ",        ["a", "u"],              ["a"],                   3.0, "LEEN",     False, False, False, False, False, False),
    (12, "لَهُ",          ["a", "uu"],             ["a"],                   3.0, "TABEEI",   False, False, False, False, False, False),
    (13, "مَا",           ["aa"],                  ["aa"],                  2.0, "TABEEI",   False, False, False, False, False, False),
    (14, "فِي",           ["i"],                   ["ii"],                  1.2, "NONE",     False, False, False, False, False, False),
    (15, "السَّمَاوَاتِ", ["a", "aa", "aa", "i"],  ["a", "aa", "aa"],       6.0, "TABEEI",   False, False, True,  False, False, False),
    (16, "وَمَا",         ["a", "aa"],             ["a", "aa"],             3.0, "TABEEI",   False, False, False, False, False, False),
    (17, "فِي",           ["i"],                   ["ii"],                  1.2, "NONE",     False, False, False, False, False, False),
    (18, "الْأَرْضِ",     ["a", "i"],              ["a"],                   3.0, "NONE",     False, False, False, False, True,  True),
    (19, "مَنْ",          ["a"],                   ["a"],                   2.8, "NONE",     True,  False, False, False, False, False),
    (20, "ذَا",           ["a"],                   ["aa"],                  1.2, "NONE",     False, True,  False, False, False, False),
    (21, "الَّذِي",       ["a", "ii"],             ["a", "ii"],             3.5, "TABEEI",   False, True,  False, False, False, False),
    (22, "يَشْفَعُ",      ["a", "a", "u"],         ["a", "a"],              3.0, "NONE",     False, False, False, True,  False, True),
    (23, "عِنْدَهُ",      ["i", "a", "uu"],        ["i", "a"],              5.0, "MUNFASIL", True,  False, False, False, False, False),
    (24, "إِلَّا",        ["i", "aa"],             ["i", "aa"],             3.5, "TABEEI",   False, False, False, False, False, False),
    (25, "بِإِذْنِهِ",    ["i", "i", "i", "ii"],   ["i", "i", "i"],         4.5, "TABEEI",   False, True,  False, False, False, True),
    (26, "يَعْلَمُ",      ["a", "a", "u"],         ["a", "a"],              3.0, "NONE",     False, False, False, False, False, True),
    (27, "مَا",           ["aa"],                  ["aa"],                  2.0, "TABEEI",   False, False, False, False, False, False),
    (28, "بَيْنَ",        ["a", "a"],              ["a"],                   2.5, "LEEN",     False, False, False, False, False, False),
    (29, "أَيْدِيهِمْ",   ["a", "ii", "i"],        ["a", "ii", "i"],        4.5, "TABEEI",   False, False, False, False, False, True),
    (30, "وَمَا",         ["a", "aa"],             ["a", "aa"],             3.0, "TABEEI",   False, False, False, False, False, False),
    (31, "خَلْفَهُمْ",    ["a", "a", "u"],         ["a", "a", "u"],         3.2, "NONE",     False, False, False, False, True,  True),
    (32, "وَلَا",         ["a", "aa"],             ["a", "aa"],             3.0, "TABEEI",   False, False, False, False, False, False),
    (33, "يُحِيطُونَ",    ["u", "ii", "uu", "a"],  ["u", "ii", "uu"],       6.0, "TABEEI",   False, False, False, False, True,  False),
    (34, "بِشَيْءٍ",      ["i", "a", "i"],         ["i", "a"],              4.0, "NONE",     True,  False, False, True,  False, False),
    (35, "مِنْ",          ["i"],                   ["i"],                   1.5, "NONE",     False, False, False, False, False, True),
    (36, "عِلْمِهِ",      ["i", "i", "ii"],        ["i", "i"],              4.0, "MUNFASIL", False, False, False, False, False, True),
    (37, "إِلَّا",        ["i", "aa"],             ["i", "aa"],             3.5, "TABEEI",   False, False, False, False, False, False),
    (38, "بِمَا",         ["i", "aa"],             ["i", "aa"],             3.0, "TABEEI",   False, False, False, False, False, False),
    (39, "شَاءَ",         ["aa", "a"],             ["aa"],                  4.5, "MUTTASIL", False, False, False, True,  False, False),
    (40, "وَسِعَ",        ["a", "i", "a"],         ["a", "i"],              2.5, "NONE",     False, False, True,  False, False, False),
    (41, "كُرْسِيُّهُ",   ["u", "i", "u", "u"],    ["u", "i", "u"],         4.0, "NONE",     False, False, True,  False, True,  True),
    (42, "السَّمَاوَاتِ", ["a", "aa", "aa", "i"],  ["a", "aa", "aa"],       6.0, "TABEEI",   False, False, True,  False, False, False),
    (43, "وَالْأَرْضَ",   ["a", "a", "a"],         ["a", "a"],              3.5, "NONE",     False, False, False, False, True,  True),
    (44, "وَلَا",         ["a", "aa"],             ["a", "aa"],             3.0, "TABEEI",   False, False, False, False, False, False),
    (45, "يَئُودُهُ",     ["a", "uu", "u", "uu"],  ["a", "uu", "u"],        5.5, "TABEEI",   False, False, False, False, False, False),
    (46, "حِفْظُهُمَا",   ["i", "u", "u", "aa"],   ["i", "u", "u", "aa"],   5.0, "TABEEI",   False, True,  False, False, True,  True),
    (47, "وَهُوَ",        ["a", "u", "a"],         ["a", "u"],              2.5, "NONE",     False, False, False, False, False, False),
    (48, "الْعَلِيُّ",    ["a", "i", "u"],         ["a", "i"],              3.5, "NONE",     False, False, False, False, False, False),
    (49, "الْعَظِيمُ",    ["a", "ii"],             ["a", "ii"],             4.5, "AARED",    False, True,  False, False, True,  False),
]


def get_valid_qps(idx: int, ibtida: bool, waqf: bool, lenient: bool) -> list[str]:
    munfasil = ["اا", "ااا", "اااا", "ااااا"] if lenient else ["اااا"]
    silah_kubra_w = ["ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ"] if lenient else ["ۥۥۥۥ"]
    silah_kubra_y = ["ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ"] if lenient else ["ۦۦۦۦ"]
    muttasil_wasl = ["اااا", "ااااا", "اااااا"] if lenient else ["اااا"]
    muttasil_waqf = ["اااا", "ااااا", "اااااا"] if lenient else ["اااا"]
    aared_w = ["ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ", "ۥۥۥۥۥۥ"] if lenient else ["ۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥۥ"]
    aared_y = ["ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ", "ۦۦۦۦۦۦ"] if lenient else ["ۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦۦ"]
    aared_a = ["اا", "ااا", "اااا", "ااااا", "اااااا"] if lenient else ["اا", "اااا", "اااااا"]
    leen_w = ["و", "وو", "ووو", "وووو", "ووووو", "وووووو"] if lenient else ["و", "ووو", "ووووو"]
    leen_y = ["ي", "يي", "ييي", "يييي", "ييييي", "يييييي"] if lenient else ["ي", "ييي", "ييييي"]

    def comb(*parts):
        res = [""]
        for p in parts:
            if isinstance(p, str):
                res = [r + p for r in res]
            else:
                res = [r + x for r in res for x in p]
        return list(dict.fromkeys(res))

    hw = "ءَ" if ibtida else ""

    if idx == 0:
        return comb("ءَللَ", aared_a if waqf else "اا", "ه" if waqf else "هُ")
    elif idx == 1:
        return comb("لَ", "اا" if waqf else munfasil)
    elif idx == 2:
        return comb("ءِلَ", aared_a if waqf else "اا", "ه" if waqf else "هَ")
    elif idx in (3, 24, 37):
        return comb("ءِللَ", "اا")
    elif idx == 4:
        return comb("هُ", ["و", "ۥۥ"] if waqf else "وَ")
    elif idx == 5:
        return comb(hw, "لحَ", ["يي", "ي"] if waqf else "ييُ")
    elif idx == 6:
        return comb(hw, "لقَييُ", aared_w if waqf else "ۥۥ", "م" if waqf else "مُ")
    elif idx == 7:
        return comb("لَ", "اا")
    elif idx == 8:
        return comb("تَءخُذُ", "ه" if waqf else "هُۥۥ")
    elif idx == 9:
        if waqf:
            return ["سِنَه"]
        return ["سِنَتُوو", "سِنَتُو"]
    elif idx in (10, 32, 44):
        return comb("وَلَ", "اا")
    elif idx == 11:
        if waqf:
            return comb("نَ", leen_w, "م")
        return ["نَومُل", "نَومُلل"]
    elif idx == 12:
        return comb("لَ", "ه" if waqf else "هُۥۥ")
    elif idx in (13, 27):
        return comb("مَ", "اا")
    elif idx in (14, 17):
        return ["فِۦۦ"] if waqf else ["فِ"]
    elif idx in (15, 42):
        return comb(hw, "سسَمَ", "اا", "وَ", aared_a if waqf else "اا", "ت" if waqf else "تِ")
    elif idx in (16, 30):
        return comb("وَمَ", "اا")
    elif idx == 18:
        return comb(hw, "لءَرض" if waqf else "لءَرضِ")
    elif idx == 19:
        return ["مَن"] if waqf else ["مَںںں", "مَںں", "مَںںںں"]
    elif idx == 20:
        return ["ذَاا"] if waqf else ["ذَ"]
    elif idx == 21:
        return comb(hw, "للَذِ", "ۦۦ")
    elif idx == 22:
        return ["يَشفَع"] if waqf else ["يَشفَعُ"]
    elif idx == 23:
        if waqf:
            return ["عِںںںدَه", "عِںںدَه", "عِںںںںدَه"]
        return comb(["عِںںںدَهُ", "عِںںدَهُ", "عِںںںںدَهُ"], silah_kubra_w)
    elif idx == 25:
        return ["بِءِذنِه"] if waqf else ["بِءِذنِهِۦۦ"]
    elif idx == 26:
        return ["يَعلَم"] if waqf else ["يَعلَمُ"]
    elif idx == 28:
        return comb("بَ", leen_y, "ن") if waqf else ["بَينَ"]
    elif idx == 29:
        return ["ءَيدِۦۦهِم"]
    elif idx == 31:
        return ["خَلفَهُم"]
    elif idx == 33:
        return comb("يُحِۦۦطُ", aared_w if waqf else "ۥۥ", "ن" if waqf else "نَ")
    elif idx == 34:
        if waqf:
            return comb("بِشَ", leen_y, ["ء", "ءڇ"])
        return ["بِشَيءِممم", "بِشَيءِمم", "بِشَيءِمممم"]
    elif idx == 35:
        return ["مِن"]
    elif idx == 36:
        if waqf:
            return ["عِلمِه"]
        return comb("عِلمِهِ", silah_kubra_y)
    elif idx == 38:
        return ["بِمَاا"]
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
        return ["يَءُۥۥدُه"] if waqf else ["يَءُۥۥدُهُۥۥ"]
    elif idx == 46:
        return ["حِفظُهُمَاا"]
    elif idx == 47:
        return ["وَهُو", "وَهُۥۥ"] if waqf else ["وَهُوَ"]
    elif idx == 48:
        return comb(hw, "لعَلِ", ["يي", "ي"] if waqf else "ييُ")
    elif idx == 49:
        if waqf:
            return comb(hw, "لعَظِ", aared_y, "م")
        return comb(hw, "لعَظِۦۦمُ")
    return []


def edit_distance_detail(expected: str, actual: str):
    m, n = len(expected), len(actual)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    bp = [[0] * (n + 1) for _ in range(m + 1)]

    def del_cost(idx_in_exp: int, c: str) -> float:
        if idx_in_exp == 0:
            return 1.35
        if c in HARAKAT:
            return 0.85
        if c in MADD_CHARS or c in "ں۾ڇ":
            return 0.75
        return 0.95

    def ins_cost(idx_in_act: int, c: str) -> float:
        if c in HARAKAT:
            return 0.85
        if c in MADD_CHARS or c in "ں۾ڇ":
            return 0.75
        return 1.0

    def sub_cost(idx_in_exp: int, c1: str, c2: str) -> float:
        if c1 == c2:
            return 0.0
        onset_pen = 0.25 if idx_in_exp == 0 else 0.0
        if (c1 in HARAKAT) and (c2 in HARAKAT):
            return 0.85 + onset_pen
        if (c1 in HARAKAT) != (c2 in HARAKAT):
            return 1.30 + onset_pen
        return 1.0 + onset_pen

    for i in range(1, m + 1):
        dp[i][0] = dp[i - 1][0] + del_cost(i - 1, expected[i - 1])
        bp[i][0] = 1
    for j in range(1, n + 1):
        dp[0][j] = dp[0][j - 1] + ins_cost(j - 1, actual[j - 1])
        bp[0][j] = 2

    for i in range(1, m + 1):
        for j in range(1, n + 1):
            c_sub = dp[i - 1][j - 1] + sub_cost(i - 1, expected[i - 1], actual[j - 1])
            c_del = dp[i - 1][j] + del_cost(i - 1, expected[i - 1])
            c_ins = dp[i][j - 1] + ins_cost(j - 1, actual[j - 1])
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

    ops = []
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


def describe_word_qps_error(word_idx: int, expected: str, actual: str, ops: list) -> str:
    if not actual:
        return "لم تُقرأ هذه الكلمة (أو تم تخطيها أثناء التسميع)."
    msgs = []
    has_tashkeel = False
    has_tajweed = False
    has_letter = False

    harakat_names = {'َ': 'الفتحة', 'ُ': 'الضمة', 'ِ': 'الكسرة'}
    for op, ex, ac in ops:
        if ex in HARAKAT or ac in HARAKAT:
            has_tashkeel = True
            if op == "replace" and ex in HARAKAT and ac in HARAKAT:
                msgs.append(f"خطأ في التشكيل: أبدلت {harakat_names.get(ex, ex)} بـ{harakat_names.get(ac, ac)}")
            elif op == "delete" and ex in HARAKAT:
                msgs.append(f"خطأ في التشكيل: أسقطت حركة {harakat_names.get(ex, ex)}")
            elif op == "insert" and ac in HARAKAT:
                msgs.append(f"خطأ في التشكيل: زدت حركة {harakat_names.get(ac, ac)} على حرف ساكن (أو لم تُدغم التنوين)")
        elif ex in MADD_CHARS or ac in MADD_CHARS or ex in "ں۾ڇ" or ac in "ں۾ڇ":
            has_tajweed = True
            if ac == 'ڇ' and ex == '':
                msgs.append("خطأ في التجويد: قلقلة حرف غير مقلقل")
            elif ex == 'ڇ' and ac == '':
                msgs.append("خطأ في التجويد: ترك القلقلة")
            elif ex == 'ں' or ac == 'ں':
                msgs.append("خطأ في التجويد (الإخفاء الحقيقي): إظهار النون أو نقص غنة الإخفاء")
            elif ex == '۾' or ac == '۾':
                msgs.append("خطأ في التجويد (الإخفاء الشفوي / الإقلاب): نقص في الغنة")
            elif ex in MADD_CHARS or ac in MADD_CHARS:
                if op == "delete":
                    msgs.append("خطأ في التجويد (المد): قصر أو إسقاط حرف المد عن مقداره المطلوب")
                elif op == "insert":
                    msgs.append("خطأ في التجويد (المد): زيادة في مقدار المد عن الحد المعتبر")
                else:
                    msgs.append("خطأ في التجويد (المد): تغير في حرف المد أو مقداره")
        else:
            if op == "delete" and (word_idx in (9, 11, 34) or (ex in expected and expected.count(ex) >= 2)):
                has_tajweed = True
                msgs.append(f"خطأ في التجويد (الإدغام/التشديد): نقص في تشديد أو إدغام حرف «{ex}»")
            elif op == "replace" and (ex in "ں۾" or ac in "ں۾"):
                has_tajweed = True
                msgs.append("خطأ في التجويد (الإخفاء): إظهار النون الساكنة بدلاً من إخفائها بغنة")
            else:
                has_letter = True
                if op == "replace":
                    msgs.append(f"خطأ في نطق الحرف (المخرج/الصفة): نطقت «{ac}» بدلاً من «{ex}»")
                elif op == "delete":
                    msgs.append(f"خطأ في الحروف: إسقاط حرف «{ex}»")
                elif op == "insert":
                    msgs.append(f"خطأ في الحروف: زيادة حرف «{ac}»")

    unique_msgs = list(dict.fromkeys(msgs))
    header = []
    if has_letter:
        header.append("خطأ في الحرف")
    if has_tashkeel:
        header.append("خطأ في التشكيل")
    if has_tajweed:
        header.append("خطأ في التجويد")
    title = " + ".join(header) if header else "خطأ في النطق"
    details = "\n• ".join(unique_msgs[:4])
    return f"{title}:\n• {details}\n(المتوقع صوتياً: «{expected}» — المسموع: «{actual}»)"


_FORMS_CACHE = {}
def _get_forms_table(lenient_madd: bool):
    if lenient_madd in _FORMS_CACHE:
        return _FORMS_CACHE[lenient_madd]
    N = 50
    forms = {}
    min_w_len = {}
    max_w_len = {}
    for w in range(N):
        for ib in (False, True):
            for wq in (False, True):
                flist = get_valid_qps(w, ibtida=ib, waqf=wq, lenient=lenient_madd)
                forms[(w, ib, wq)] = flist
        all_lens = [len(s) for ib in (False, True) for wq in (False, True) for s in forms[(w, ib, wq)]]
        min_w_len[w] = min(all_lens)
        max_w_len[w] = max(all_lens)
    _FORMS_CACHE[lenient_madd] = (forms, min_w_len, max_w_len)
    return forms, min_w_len, max_w_len


def align_qps_to_ayat_kursi(pred_ph: str, lenient_madd: bool = True, allowed_waqf: set = None):
    if allowed_waqf is None:
        allowed_waqf = VALID_WAQF_WORDS
    N = 50
    L = len(pred_ph)
    INF = 1e9
    forms, min_w_len, max_w_len = _get_forms_table(lenient_madd)

    valid_boundary = [True] * (L + 1)
    for p in range(1, L):
        if pred_ph[p] in NON_ONSET_CHARS:
            valid_boundary[p] = False

    dp = [[ [INF, INF] for _ in range(L + 1) ] for _ in range(N + 1)]
    back = [[ [None, None] for _ in range(L + 1) ] for _ in range(N + 1)]
    dp[0][0][1] = 0.0

    ed_cache = {}
    def fast_ed(form: str, sub: str) -> float:
        if form == sub:
            return 0.0
        key = (form, sub)
        if key in ed_cache:
            return ed_cache[key]
        cost, _ = edit_distance_detail(form, sub)
        ed_cache[key] = cost
        return cost

    for w in range(N):
        best_at_w = min(dp[w][p][q] for p in range(L + 1) for q in (0, 1))
        if best_at_w >= INF:
            break
        beam_cutoff = best_at_w + 6.0

        min_span = max(1, min_w_len[w] - 2)
        max_span = max_w_len[w] + 2
        can_waqf = (w in allowed_waqf)
        wq_options = (False, True) if can_waqf else (False,)

        for pos in range(L + 1):
            if not valid_boundary[pos]:
                continue
            for prev_wq in (0, 1):
                cur_cost = dp[w][pos][prev_wq]
                if cur_cost > beam_cutoff:
                    continue
                ib = (prev_wq == 1)

                for wq in wq_options:
                    wq_idx = 1 if wq else 0
                    flist = forms[(w, ib, wq)]
                    tie_bias = 0.0 if (w == N - 1 and wq) or (w < N - 1 and not wq) else 0.01
                    matched_exact = False
                    for f in flist:
                        Lf = len(f)
                        np_exact = pos + Lf
                        if np_exact <= L and valid_boundary[np_exact] and pred_ph[pos:np_exact] == f:
                            matched_exact = True
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
                        sub = pred_ph[pos:next_pos]
                        if matched_exact and sub in flist:
                            continue
                        best_f_cost = INF
                        best_f = flist[0]
                        for f in canonical_forms:
                            if abs(len(f) - len(sub)) * 0.75 >= best_f_cost:
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
                skip_cost = len(skip_ref) * 0.85 + 0.5
                if cur_cost + skip_cost < dp[w + 1][pos][prev_wq]:
                    dp[w + 1][pos][prev_wq] = cur_cost + skip_cost
                    back[w + 1][pos][prev_wq] = (pos, prev_wq, False, skip_ref, "")

    best_end_wq = 1 if dp[N][L][1] <= dp[N][L][0] else 0
    if dp[N][L][best_end_wq] >= INF:
        return None

    results = [None] * N
    cur_pos = L
    cur_wq = best_end_wq
    for w in range(N - 1, -1, -1):
        prev_pos, prev_wq, wq, best_f, sub = back[w + 1][cur_pos][cur_wq]
        cost, ops = edit_distance_detail(best_f, sub)
        ok = (cost == 0.0)
        note = "" if ok else describe_word_qps_error(w, best_f, sub, ops)
        results[w] = {
            "w": w,
            "word": WORDS_AR[w],
            "ok": ok,
            "waqf": wq,
            "expected_ph": best_f,
            "heard_ph": sub,
            "cost": round(cost, 3),
            "note": note,
        }
        cur_pos = prev_pos
        cur_wq = prev_wq
    return results


def preprocess_audio(wave, sr=16000):
    if wave.size == 0:
        return wave
    wave = wave.astype(np.float32)
    wave = wave - np.mean(wave)
    frame_len = int(sr * 0.025)
    hop_len = int(sr * 0.010)
    if wave.size > frame_len * 4:
        n_frames = 1 + (wave.size - frame_len) // hop_len
        rms = np.array([
            np.sqrt(np.mean(wave[i * hop_len : i * hop_len + frame_len] ** 2) + 1e-12)
            for i in range(n_frames)
        ])
        peak_rms = float(np.percentile(rms, 95)) if n_frames > 0 else 0.0
        if peak_rms > 1e-4:
            active = np.where(rms > max(peak_rms * 0.06, 1e-4))[0]
            if active.size > 0:
                margin_samples = int(sr * 0.18)
                start_sample = max(0, int(active[0]) * hop_len - margin_samples)
                end_sample = min(wave.size, int(active[-1] + 1) * hop_len + margin_samples)
                wave = wave[start_sample:end_sample]
    max_abs = float(np.max(np.abs(wave))) if wave.size > 0 else 0.0
    if max_abs > 1e-4:
        wave = (wave / max_abs) * 0.92
    return wave


def segment_audio_at_waqf(wave, sr=16000, max_seg_sec=5.5):
    """
    Short-Window Context-Breaking Waqf Segmentation:
    Splits long recitations at natural pauses (>=280ms) AND bounds each window to <=5.5s
    at local RMS minima so the Wav2Vec2-BERT Transformer cannot use global verse context
    to auto-correct Tashkeel or Tajweed errors.
    """
    min_seg_samples = int(sr * 1.1)
    max_seg_samples = int(sr * max_seg_sec)
    if wave.size <= int(sr * 4.5):
        return [wave]
    frame_len = int(sr * 0.025)
    hop_len = int(sr * 0.010)
    n_frames = 1 + (wave.size - frame_len) // hop_len
    rms = np.array([
        np.sqrt(np.mean(wave[i * hop_len : i * hop_len + frame_len] ** 2) + 1e-12)
        for i in range(n_frames)
    ])
    med_rms = float(np.percentile(rms, 65))
    thresh = max(med_rms * 0.12, 0.008)
    is_silent = rms < thresh
    min_silence_frames = 28
    cut_samples = []
    run_start = None
    for idx, s in enumerate(is_silent):
        if s:
            if run_start is None:
                run_start = idx
        else:
            if run_start is not None and (idx - run_start) >= min_silence_frames:
                mid_frame = (run_start + idx) // 2
                cut_samples.append(mid_frame * hop_len)
            run_start = None
    segments = []
    prev = 0
    for c in cut_samples:
        if c - prev >= min_seg_samples:
            segments.append(wave[prev:c])
            prev = c
    if wave.size - prev > int(sr * 0.3):
        if segments and (wave.size - prev) < min_seg_samples:
            segments[-1] = np.concatenate([segments[-1], wave[prev:]])
        else:
            segments.append(wave[prev:])
    final_segments = []
    for seg in segments:
        cur = seg
        while cur.size > max_seg_samples:
            # Find lowest energy dip between 2.8s and max_seg_sec
            s_lo = int(sr * 2.8)
            s_hi = min(cur.size - int(sr * 1.0), max_seg_samples)
            if s_hi <= s_lo:
                mid = cur.size // 2
                final_segments.append(cur[:mid])
                cur = cur[mid:]
            else:
                f_lo = s_lo // hop_len
                f_hi = max(f_lo + 1, s_hi // hop_len)
                seg_rms = [
                    float(np.mean(cur[f * hop_len : f * hop_len + frame_len] ** 2))
                    for f in range(f_lo, min(f_hi, (cur.size - frame_len) // hop_len))
                ]
                if seg_rms:
                    best_f = f_lo + int(np.argmin(seg_rms))
                    cut_pt = best_f * hop_len
                else:
                    cut_pt = (s_lo + s_hi) // 2
                final_segments.append(cur[:cut_pt])
                cur = cur[cut_pt:]
        if cur.size > 0:
            final_segments.append(cur)
    return final_segments if final_segments else [wave]


def audit_phoneme_gop_probabilities(
    word_results: list[dict],
    pred_ph_probs: list[float],
    harakat_gop_thresh: float = 0.82,
    letter_gop_thresh: float = 0.75,
):
    """
    Goodness of Pronunciation (GOP) Token Probability Auditor:
    When Wav2Vec2-BERT uses verse context to force greedy argmax into outputting the expected
    phoneme despite an acoustic mismatch (e.g. saying سِنَةً instead of سِنَةٌ), the token's
    posterior probability drops significantly. This function catches low-confidence tokens.
    """
    if not pred_ph_probs:
        return
    harakat_names = {'َ': 'الفتحة', 'ُ': 'الضمة', 'ِ': 'الكسرة'}
    p_cursor = 0
    n_probs = len(pred_ph_probs)
    for wr in word_results:
        heard = wr.get("heard_ph", "")
        hlen = len(heard)
        p0, p1 = p_cursor, p_cursor + hlen
        p_cursor = p1
        if hlen == 0 or p0 >= n_probs:
            continue
        sub_probs = pred_ph_probs[p0 : min(p1, n_probs)]
        gop_msgs = []
        for idx_c, (ch, pr) in enumerate(zip(heard, sub_probs)):
            prob_val = float(pr)
            if ch in HARAKAT and prob_val < harakat_gop_thresh:
                h_name = harakat_names.get(ch, ch)
                gop_msgs.append(
                    f"اشتباه قوي في التشكيل (ثقة صوتية منخفضة {prob_val * 100:.0f}%): حركة «{h_name}» في «{wr['word']}» لم تُنطق بوضوح أو أُبدلت بحركة أخرى"
                )
            elif (ch in MADD_CHARS or ch in "ں۾ڇ") and prob_val < letter_gop_thresh:
                gop_msgs.append(
                    f"ضعف في تطبيق حكم التجويد (ثقة صوتية {prob_val * 100:.0f}%) في «{wr['word']}»"
                )
            elif ch not in HARAKAT and prob_val < letter_gop_thresh:
                gop_msgs.append(
                    f"ضعف أو انحراف في مخرج حرف «{ch}» (ثقة صوتية {prob_val * 100:.0f}%) في «{wr['word']}»"
                )
        if gop_msgs:
            unique_gop = list(dict.fromkeys(gop_msgs))
            wr["ok"] = False
            gop_block = "تحقق صوتي احتمالي (GOP):\n• " + "\n• ".join(unique_gop[:3])
            wr["note"] = (wr["note"] + "\n\n" + gop_block).strip() if wr["note"] else gop_block


def audit_sifat_on_alignment(word_results, pred_sifat, ref_sifat, confidence_threshold=0.72):
    if not pred_sifat:
        return
    pos_cursor = 0
    sifa_spans = []
    for s in pred_sifat:
        grp = getattr(s, "phonemes_group", "") or (s.get("phonemes_group", "") if isinstance(s, dict) else "")
        end_c = pos_cursor + len(grp)
        sifa_spans.append((pos_cursor, end_c, s))
        pos_cursor = end_c
    CRITICAL_TAFKHEEM_LETTERS = set("صضطظقخغ")
    CRITICAL_MORAQAQ_LETTERS = set("تذسشعحفك")
    QALQALAH_LETTERS = set("قطبجد")
    p_cursor = 0
    for wr in word_results:
        heard_len = len(wr.get("heard_ph", ""))
        p0, p1 = p_cursor, p_cursor + heard_len
        p_cursor = p1
        if p1 <= p0:
            continue
        sifa_msgs = []
        for s_start, s_end, s_obj in sifa_spans:
            if s_end <= p0 or s_start >= p1:
                continue
            grp = getattr(s_obj, "phonemes_group", "") or (s_obj.get("phonemes_group", "") if isinstance(s_obj, dict) else "")
            if not grp:
                continue
            base_char = grp[0]
            tt = getattr(s_obj, "tafkheem_or_taqeeq", None) if not isinstance(s_obj, dict) else s_obj.get("tafkheem_or_taqeeq")
            if tt is not None:
                tt_text = getattr(tt, "text", None) or (tt.get("text") if isinstance(tt, dict) else None)
                tt_prob = float(getattr(tt, "prob", 0.0) or (tt.get("prob", 0.0) if isinstance(tt, dict) else 0.0))
                if tt_prob >= confidence_threshold:
                    if base_char in CRITICAL_TAFKHEEM_LETTERS and tt_text == "moraqaq":
                        sifa_msgs.append(f"خطأ في صفة الحرف (التفخيم): رُقِّق حرف الاستعلاء «{base_char}» وحقه التفخيم")
                    elif base_char in CRITICAL_MORAQAQ_LETTERS and tt_text == "mofakham":
                        sifa_msgs.append(f"خطأ في صفة الحرف (الترقيق): فُخِّم الحرف المرقق «{base_char}» وحقه الترقيق")
                    elif wr["w"] == 0 and base_char == "ل" and "لل" in grp and tt_text == "moraqaq":
                        sifa_msgs.append("خطأ في التجويد: ترقيق لام لفظ الجلالة «اللَّهُ» بعد الفتح وحقها التفخيم")
            itb = getattr(s_obj, "itbaq", None) if not isinstance(s_obj, dict) else s_obj.get("itbaq")
            if itb is not None and base_char in "صضطظ":
                itb_text = getattr(itb, "text", None) or (itb.get("text") if isinstance(itb, dict) else None)
                itb_prob = float(getattr(itb, "prob", 0.0) or (itb.get("prob", 0.0) if isinstance(itb, dict) else 0.0))
                if itb_prob >= confidence_threshold and itb_text == "monfateh":
                    sifa_msgs.append(f"خطأ في صفة الحرف (الإطباق): ضعف إطباق اللسان في حرف «{base_char}»")
            qlq = getattr(s_obj, "qalqla", None) if not isinstance(s_obj, dict) else s_obj.get("qalqla")
            if qlq is not None:
                qlq_text = getattr(qlq, "text", None) or (qlq.get("text") if isinstance(qlq, dict) else None)
                qlq_prob = float(getattr(qlq, "prob", 0.0) or (qlq.get("prob", 0.0) if isinstance(qlq, dict) else 0.0))
                if qlq_prob >= confidence_threshold:
                    if qlq_text == "moqalqal" and base_char not in QALQALAH_LETTERS:
                        sifa_msgs.append(f"خطأ في التجويد (القلقلة): قلقلة الحرف الساكن «{base_char}» في «{wr['word']}» وليس من حروف (قطب جد)")
        if sifa_msgs:
            unique_sifa = list(dict.fromkeys(sifa_msgs))
            wr["ok"] = False
            sifa_block = "خطأ في صفات الحروف والتجويد:\n• " + "\n• ".join(unique_sifa)
            wr["note"] = (wr["note"] + "\n\n" + sifa_block).strip() if wr["note"] else sifa_block


def extract_frame_features(wave: np.ndarray, sr: int = 16000) -> dict[str, np.ndarray]:
    frame_len = int(sr * 0.025)
    hop_len = int(sr * 0.010)
    if wave.size < frame_len:
        wave = np.pad(wave, (0, frame_len - wave.size))
    n_frames = 1 + (wave.size - frame_len) // hop_len
    window = np.hamming(frame_len).astype(np.float32)
    freqs = np.fft.rfftfreq(512, d=1.0 / sr)

    rms = np.zeros(n_frames, dtype=np.float32)
    zcr = np.zeros(n_frames, dtype=np.float32)
    e_f1_closed = np.zeros(n_frames, dtype=np.float32)
    e_f1_open = np.zeros(n_frames, dtype=np.float32)
    e_f2_damma = np.zeros(n_frames, dtype=np.float32)
    e_f2_fatha = np.zeros(n_frames, dtype=np.float32)
    e_f2_kasra = np.zeros(n_frames, dtype=np.float32)
    e_tafashie = np.zeros(n_frames, dtype=np.float32)
    e_sibilant = np.zeros(n_frames, dtype=np.float32)

    idx_f1_closed = (freqs >= 240) & (freqs <= 460)
    idx_f1_open = (freqs >= 580) & (freqs <= 860)
    idx_f2_damma = (freqs >= 780) & (freqs <= 1150)
    idx_f2_fatha = (freqs >= 1200) & (freqs <= 1700)
    idx_f2_kasra = (freqs >= 1450) & (freqs <= 2700)
    idx_tafashie = (freqs >= 2000) & (freqs <= 3800)
    idx_sibilant = (freqs >= 4200) & (freqs <= 7600)

    for i in range(n_frames):
        seg = wave[i * hop_len : i * hop_len + frame_len]
        rms[i] = np.sqrt(np.mean(seg ** 2) + 1e-12)
        zcr[i] = np.mean(np.abs(np.diff(np.signbit(seg))))

        raw_spec = np.abs(np.fft.rfft(seg * window, n=512)) ** 2
        raw_tot = np.sum(raw_spec) + 1e-12
        e_f1_closed[i] = np.sum(raw_spec[idx_f1_closed]) / raw_tot
        e_f1_open[i] = np.sum(raw_spec[idx_f1_open]) / raw_tot

        pre = np.append(seg[0], seg[1:] - 0.95 * seg[:-1]) * window
        pre_spec = np.abs(np.fft.rfft(pre, n=512)) ** 2
        pre_tot = np.sum(pre_spec) + 1e-12
        e_f2_damma[i] = np.sum(pre_spec[idx_f2_damma]) / pre_tot
        e_f2_fatha[i] = np.sum(pre_spec[idx_f2_fatha]) / pre_tot
        e_f2_kasra[i] = np.sum(pre_spec[idx_f2_kasra]) / pre_tot
        e_tafashie[i] = np.sum(pre_spec[idx_tafashie]) / pre_tot
        e_sibilant[i] = np.sum(pre_spec[idx_sibilant]) / pre_tot

    return {
        "rms": rms,
        "zcr": zcr,
        "e_f1_closed": e_f1_closed,
        "e_f1_open": e_f1_open,
        "e_f2_damma": e_f2_damma,
        "e_f2_fatha": e_f2_fatha,
        "e_f2_kasra": e_f2_kasra,
        "e_tafashie": e_tafashie,
        "e_sibilant": e_sibilant,
    }


def classify_vowel_segment(feats: dict[str, np.ndarray], f_start: int, f_end: int) -> str:
    if f_end <= f_start:
        return "a"
    f1_open = float(np.mean(feats["e_f1_open"][f_start:f_end]))
    f1_closed = float(np.mean(feats["e_f1_closed"][f_start:f_end]))
    f2_damma = float(np.mean(feats["e_f2_damma"][f_start:f_end]))
    f2_kasra = float(np.mean(feats["e_f2_kasra"][f_start:f_end]))

    if f1_open > f1_closed * 1.15:
        return "a"
    if f2_kasra > f2_damma * 1.10:
        return "i"
    return "u"


def compute_word_expected_weights(is_waqf_map: set[int]) -> np.ndarray:
    weights = np.zeros(50, dtype=np.float32)
    for w in range(50):
        _, _, wasl_v, waqf_v, _, madd_type, req_ghunnah, interdental, safeer, tafashie, _, _ = WORD_ACOUSTIC_SPECS[w]
        wq = w in is_waqf_map
        vowels = waqf_v if wq else wasl_v
        dur_ms = 0.0
        if safeer:
            dur_ms += 75.0
        elif tafashie:
            dur_ms += 85.0
        elif interdental:
            dur_ms += 70.0
        for v in vowels:
            if len(v) >= 2:
                if madd_type == "MUTTASIL":
                    dur_ms += 580.0
                elif madd_type == "MUNFASIL" and not wq:
                    dur_ms += 420.0
                else:
                    dur_ms += 280.0
            else:
                dur_ms += 140.0
        if req_ghunnah and not wq:
            dur_ms += 240.0
        weights[w] = dur_ms / 100.0
    return weights


def forced_align_phrase_words(
    feats: dict[str, np.ndarray],
    w_start: int,
    w_end: int,
    f_start: int,
    f_end: int,
    exp_weights: np.ndarray,
) -> list[tuple[int, int]]:
    rms = feats["rms"]
    n_words = w_end - w_start + 1
    if n_words <= 1:
        return [(f_start, f_end)]

    phrase_len = f_end - f_start
    sub_w = exp_weights[w_start : w_end + 1]
    total_sub_w = float(np.sum(sub_w))
    exp_lens = [max(4.0, phrase_len * (float(sw) / total_sub_w)) for sw in sub_w]
    exp_cum = np.cumsum([0.0] + exp_lens)

    INF = 1e9
    dp = np.full((n_words + 1, phrase_len + 1), INF, dtype=np.float32)
    back = np.zeros((n_words + 1, phrase_len + 1), dtype=np.int32)
    dp[0, 0] = 0.0

    max_rms = float(np.max(rms[f_start:f_end])) + 1e-6
    norm_rms = rms[f_start:f_end] / max_rms

    for k in range(1, n_words + 1):
        exp_pos = exp_cum[k]
        exp_len = exp_lens[k - 1]
        band = max(18, int(phrase_len * 0.22))
        f_lo = phrase_len if k == n_words else max(k * 4, int(round(exp_pos)) - band)
        f_hi = phrase_len if k == n_words else min(phrase_len - (n_words - k) * 4, int(round(exp_pos)) + band)

        prev_exp_pos = exp_cum[k - 1]
        p_lo = 0 if k == 1 else max((k - 1) * 4, int(round(prev_exp_pos)) - band)
        p_hi = 0 if k == 1 else min(phrase_len, int(round(prev_exp_pos)) + band)

        for f in range(f_lo, f_hi + 1):
            dip_cost = float(norm_rms[min(f, phrase_len - 1)]) * 10.0 if k < n_words else 0.0
            best_c = INF
            best_p = p_lo
            for p in range(p_lo, min(f - 3, p_hi + 1)):
                if dp[k - 1, p] >= INF:
                    continue
                actual_len = float(f - p)
                dur_cost = ((actual_len - exp_len) / max(6.0, exp_len * 0.35)) ** 2
                c = dp[k - 1, p] + dur_cost + dip_cost
                if c < best_c:
                    best_c = c
                    best_p = p
            dp[k, f] = best_c
            back[k, f] = best_p

    spans = [None] * n_words
    cur_f = phrase_len
    for k in range(n_words, 0, -1):
        prev_f = int(back[k, cur_f])
        spans[k - 1] = (f_start + prev_f, f_start + cur_f)
        cur_f = prev_f
    return spans


def segment_full_recitation_into_50_words(
    wave: np.ndarray,
    sr: int = 16000,
) -> tuple[list[np.ndarray], list[bool], float]:
    feats = extract_frame_features(wave, sr=sr)
    rms = feats["rms"]
    n_frames = len(rms)
    hop_samples = int(sr * 0.010)
    if n_frames < 50:
        return [np.zeros(400, dtype=np.float32) for _ in range(50)], [False] * 50, 105.0

    peak_rms = float(np.percentile(rms, 95))
    silence_thresh = max(peak_rms * 0.05, 1e-4)
    is_silent = rms < silence_thresh

    pauses = []
    run_start = None
    for i, s in enumerate(is_silent):
        if s:
            if run_start is None:
                run_start = i
        else:
            if run_start is not None:
                dur = i - run_start
                if run_start > 5 and dur >= 24:
                    pauses.append((run_start, i, dur))
                run_start = None

    exp_weights = compute_word_expected_weights(NATURAL_WAQF_SET)
    cum_weights = np.cumsum(exp_weights)
    total_weight = float(cum_weights[-1])

    active_frames = np.where(~is_silent)[0]
    if active_frames.size < 20:
        return [np.zeros(400, dtype=np.float32) for _ in range(50)], [False] * 50, 105.0
    first_frame = max(0, int(active_frames[0]))
    last_frame = min(n_frames, int(active_frames[-1]) + 1)

    pause_frames_total = sum(p[2] for p in pauses if p[0] >= first_frame and p[1] <= last_frame)
    total_speech_frames = max(50, (last_frame - first_frame) - pause_frames_total)

    phrase_spans = []
    cur_w = 0
    cur_f = first_frame
    spoken_before = 0

    for p_start, p_end, p_dur in pauses:
        if p_start <= cur_f + 10 or p_end >= last_frame - 10:
            continue
        spoken_in_phrase = p_start - cur_f
        spoken_before += spoken_in_phrase
        target_cum_w = (spoken_before / float(total_speech_frames)) * total_weight
        best_w = cur_w
        best_dist = 1e9
        for cand_w in range(cur_w, 49):
            dist = abs(float(cum_weights[cand_w]) - target_cum_w)
            if cand_w not in VALID_WAQF_WORDS:
                dist += 8.0
            if dist < best_dist:
                best_dist = dist
                best_w = cand_w
        phrase_spans.append((cur_w, best_w, cur_f, p_start))
        cur_w = best_w + 1
        cur_f = p_end
        if cur_w >= 50:
            break

    if cur_w < 50:
        phrase_spans.append((cur_w, 49, cur_f, last_frame))

    word_waves = [np.zeros(400, dtype=np.float32) for _ in range(50)]
    is_waqf_list = [False] * 50

    for w_start, w_end, f_start, f_end in phrase_spans:
        is_waqf_list[w_end] = True
        w_intervals = forced_align_phrase_words(feats, w_start, w_end, f_start, f_end, exp_weights)
        for idx_offset, (wf0, wf1) in enumerate(w_intervals):
            w_idx = w_start + idx_offset
            s0 = max(0, wf0 * hop_samples)
            s1 = min(wave.size, wf1 * hop_samples)
            word_waves[w_idx] = wave[s0:s1]

    ref_2beat_words = [7, 13, 27]
    unit_ms_list = []
    for rw in ref_2beat_words:
        ww = word_waves[rw]
        if ww.size > 400:
            wf = extract_frame_features(ww, sr=sr)
            pk = float(np.max(wf["rms"]))
            vf = int(np.sum((wf["rms"] > pk * 0.18) & (wf["zcr"] < 0.28)))
            if vf >= 4:
                unit_ms_list.append((vf * 10.0) / 2.0)
    calibrated_harakah_ms = float(np.median(unit_ms_list)) if unit_ms_list else 105.0
    calibrated_harakah_ms = max(75.0, min(160.0, calibrated_harakah_ms))

    return word_waves, is_waqf_list, calibrated_harakah_ms


def audit_single_word_acoustics(
    word_idx: int,
    word_wave: np.ndarray,
    is_waqf: bool,
    harakah_ms: float = 105.0,
    lenient_madd: bool = True,
    sr: int = 16000,
) -> list[str]:
    spec = WORD_ACOUSTIC_SPECS[word_idx]
    _, w_text, wasl_v, waqf_v, min_h, madd_type, req_ghunnah, interdental, safeer, tafashie, tafkheem, _ = spec
    exp_vowels = waqf_v if is_waqf else wasl_v
    errors = []

    feats = extract_frame_features(word_wave, sr=sr)
    n_frames = len(feats["rms"])
    if n_frames < 5:
        return ["لم يُلتقط صوت واضح لهذه الكلمة."]

    peak_rms = float(np.max(feats["rms"]))
    voiced_mask = (feats["rms"] > peak_rms * 0.18) & (feats["zcr"] < 0.28)
    voiced_frames = int(np.sum(voiced_mask))
    voiced_ms = voiced_frames * 10.0

    if madd_type == "MUTTASIL":
        req_ms = harakah_ms * (3.2 if lenient_madd else 3.6)
        if voiced_ms < req_ms:
            errors.append(f"خطأ في التجويد (المد الواجب المتصل): قصر مد «{w_text}» ({voiced_ms:.0f}ms) عن مقداره الواجب (٤–٥ حركات)")
    elif madd_type == "MUNFASIL" and not is_waqf:
        req_ms = harakah_ms * (1.7 if lenient_madd else 3.2)
        if voiced_ms < req_ms:
            errors.append(f"خطأ في التجويد (المد المنفصل / الصلة الكبرى): قصر المد في «{w_text}» عن الحد المعتبر")
    elif madd_type == "TABEEI" and not is_waqf:
        min_word_ms = harakah_ms * (min_h * 0.58)
        if voiced_ms < min_word_ms:
            errors.append(f"خطأ في التجويد (المد الطبيعي): إسقاط أو قصر حرف المد في «{w_text}»")

    if req_ghunnah and not is_waqf:
        nasal_frames = int(np.sum((feats["rms"] > peak_rms * 0.12) & (feats["e_f1_closed"] > 0.35) & (feats["e_f1_open"] < 0.20)))
        nasal_ms = nasal_frames * 10.0
        if nasal_ms < harakah_ms * 1.15:
            errors.append(f"خطأ في التجويد (الغنة / الإخفاء / الإدغام): نقص زمن الغنة أو تركها في «{w_text}»")

    if interdental and not safeer:
        max_sib = float(np.max(feats["e_sibilant"]))
        if max_sib > 0.38:
            letter = "الظاء" if ("ظ" in w_text) else "الذال"
            errors.append(f"خطأ في مخرج الحرف: نطقت {letter} بحرف صفير (زاي/سين) في «{w_text}» — احرص على إخراج طرف اللسان")

    if tafashie and not safeer:
        max_sib = float(np.max(feats["e_sibilant"]))
        max_taf = float(np.max(feats["e_tafashie"]))
        if max_sib > 0.38 and max_sib > max_taf * 1.45:
            errors.append(f"خطأ في مخرج الحرف وصفته: إبدال الشين سيناً (ضعف التفشي وزيادة الصفير) في «{w_text}»")

    active_idx = np.where(voiced_mask)[0]
    if active_idx.size >= 6 and len(exp_vowels) >= 1:
        weights = []
        for v in exp_vowels:
            if len(v) >= 2:
                weights.append(4.0 if madd_type == "MUTTASIL" else (3.0 if (madd_type == "MUNFASIL" and not is_waqf) else 2.0))
            else:
                weights.append(1.0)
        gh_w = 1.6 if (req_ghunnah and not is_waqf) else 0.0
        vowel_sum = sum(weights)
        total_w = vowel_sum + gh_w
        n_act = active_idx.size
        v_end_idx = max(2, min(n_act, int(round(n_act * (vowel_sum / total_w)))))
        first_end = max(2, min(v_end_idx, int(round(n_act * (weights[0] / total_w)))))
        last_start = max(0, min(v_end_idx - 2, int(round(n_act * ((vowel_sum - weights[-1]) / total_w)))))
        v_names = {"a": "الفتحة", "u": "الضمة", "i": "الكسرة"}
        first_base = exp_vowels[0][0]
        pred_first = classify_vowel_segment(feats, int(active_idx[0]), int(active_idx[first_end - 1]) + 1)
        if pred_first != first_base:
            errors.append(
                f"خطأ في التشكيل (أول الكلمة): نطقت «{v_names[pred_first]}» بدلاً من «{v_names[first_base]}» في أول «{w_text}»"
            )
        if len(exp_vowels) >= 2:
            last_base = exp_vowels[-1][0]
            pred_last = classify_vowel_segment(feats, int(active_idx[last_start]), int(active_idx[v_end_idx - 1]) + 1)
            if pred_last != last_base:
                errors.append(
                    f"خطأ في التشكيل (آخر الكلمة / الإعراب): نطقت «{v_names[pred_last]}» بدلاً من «{v_names[last_base]}» في آخر «{w_text}»"
                )

    if tafkheem and active_idx.size >= 4:
        min_kasra_ratio = float(np.min(feats["e_f2_kasra"][active_idx] / (feats["e_f2_damma"][active_idx] + 1e-5)))
        if min_kasra_ratio > 1.85:
            errors.append(f"خطأ في صفات الحروف (التفخيم): ترقيق حرف مفخم في «{w_text}»")

    return errors


def create_fastapi_app():
    import os, tempfile
    from dataclasses import asdict
    from typing import Optional
    from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
    from fastapi.responses import JSONResponse

    app = FastAPI(title="Rafiq Precision Quranic Auditor Server", version="5.0.0")
    state = {"muaalem": None}

    def get_model():
        if state["muaalem"] is not None:
            return state["muaalem"]
        import torch
        from quran_muaalem import Muaalem
        accel = os.environ.get("ACCELERATOR", "cuda" if torch.cuda.is_available() else "cpu")
        if accel == "cuda" and not torch.cuda.is_available():
            accel = "cpu"
        dtype_str = os.environ.get("DTYPE", "bfloat16" if accel == "cuda" else "float32")
        dtype_map = {"float32": torch.float32, "float16": torch.float16, "bfloat16": torch.bfloat16}
        dtype = dtype_map.get(dtype_str, torch.float32)
        model_id = os.environ.get("MODEL_NAME_OR_PATH", "obadx/muaalem-model-v3_2")
        state["muaalem"] = Muaalem(model_name_or_path=model_id, device=accel, dtype=dtype)
        return state["muaalem"]

    @app.get("/health")
    async def health():
        return {"status": "ok", "engine": "rafiq-v5-hybrid-acoustic-gop"}

    @app.post("/correct-recitation")
    async def correct_recitation(
        file: UploadFile = File(...),
        error_ratio: float = Form(0.2),
        sura_idx: int = Form(2),
        aya_idx: int = Form(255),
        lenient_madd: bool = Form(True),
        q_sura_idx: Optional[int] = Query(None, alias="sura_idx"),
        q_aya_idx: Optional[int] = Query(None, alias="aya_idx"),
        q_lenient: Optional[bool] = Query(None, alias="lenient_madd"),
    ):
        from librosa.core import load
        from quran_transcript import Aya, MoshafAttributes, explain_error, quran_phonetizer
        sura = q_sura_idx if q_sura_idx is not None else sura_idx
        aya = q_aya_idx if q_aya_idx is not None else aya_idx
        lenient = q_lenient if q_lenient is not None else lenient_madd
        raw_bytes = await file.read()
        if not raw_bytes:
            raise HTTPException(status_code=400, detail="ملف الصوت فارغ")
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp.write(raw_bytes)
            tmp_path = tmp.name
        try:
            wave, _ = load(tmp_path, sr=16000, mono=True)
        finally:
            try:
                os.remove(tmp_path)
            except OSError:
                pass
        wave = preprocess_audio(wave, sr=16000)
        if wave.size < 16000:
            raise HTTPException(status_code=400, detail="التسجيل قصير جداً")
        muaalem = get_model()
        uthmani_ref = Aya(sura, aya).get().uthmani
        moshaf = MoshafAttributes(rewaya="hafs", madd_monfasel_len=4, madd_mottasel_len=4, madd_mottasel_waqf=4, madd_aared_len=4)
        full_ref_out = quran_phonetizer(uthmani_ref, moshaf, remove_spaces=True)
        segments = segment_audio_at_waqf(wave, sr=16000, max_seg_sec=5.5)
        pred_ph_parts = []
        pred_ph_probs = []
        pred_sifat_all = []
        for seg in segments:
            outs = muaalem([seg], [full_ref_out], sampling_rate=16000)
            out = outs[0]
            txt = out.phonemes.text
            pred_ph_parts.append(txt)
            raw_probs = getattr(out.phonemes, "probs", None)
            if raw_probs is not None:
                p_list = [float(x) for x in raw_probs]
                if len(p_list) >= len(txt):
                    pred_ph_probs.extend(p_list[:len(txt)])
                else:
                    pred_ph_probs.extend(p_list + [1.0] * (len(txt) - len(p_list)))
            else:
                pred_ph_probs.extend([1.0] * len(txt))
            if getattr(out, "sifat", None):
                pred_sifat_all.extend(out.sifat)
        predicted_phonemes = "".join(pred_ph_parts)
        if not predicted_phonemes:
            raise HTTPException(status_code=404, detail="لم يُتعرَّف على أي صوت واضح في التسجيل")
        if sura == 2 and aya == 255:
            word_results = align_qps_to_ayat_kursi(predicted_phonemes, lenient_madd=lenient)
            if word_results is None:
                raise HTTPException(status_code=404, detail="تعذّرت مطابقة التلاوة مع آية الكرسي")
            # Layer 2A: 10-Level Sifat & Qalqalah Verification
            audit_sifat_on_alignment(word_results, pred_sifat_all, getattr(full_ref_out, "sifat", []))
            # Layer 2B: Goodness of Pronunciation (GOP) Token Probability Verification
            audit_phoneme_gop_probabilities(word_results, pred_ph_probs)
            # Layer 2C: Physical Acoustic-Phonetic & Formant (F1/F2) Verification
            word_waves, is_waqf_list, cal_h = segment_full_recitation_into_50_words(wave, sr=16000)
            for w_idx, wr in enumerate(word_results):
                ac_errs = audit_single_word_acoustics(
                    w_idx,
                    word_waves[w_idx],
                    is_waqf=wr.get("waqf", is_waqf_list[w_idx]),
                    harakah_ms=cal_h,
                    lenient_madd=lenient,
                    sr=16000,
                )
                if ac_errs:
                    wr["ok"] = False
                    ac_block = "فحص صوتي فيزيائي (Formants F1/F2 & Madd & Tajweed):\n• " + "\n• ".join(ac_errs)
                    wr["note"] = (wr["note"] + "\n\n" + ac_block).strip() if wr["note"] else ac_block

            uth_words = uthmani_ref.split(" ")
            w_char_starts = []
            c_pos = 0
            for uw in uth_words:
                w_char_starts.append((c_pos, c_pos + len(uw)))
                c_pos += len(uw) + 1
            compat_errors = []
            for wr in word_results:
                if not wr["ok"]:
                    w_idx = wr["w"]
                    cs, ce = w_char_starts[w_idx] if w_idx < len(w_char_starts) else (0, 1)
                    compat_errors.append({
                        "uthmani_pos": [cs, ce],
                        "error_type": "tajweed" if "التجويد" in wr["note"] else ("tashkeel" if "التشكيل" in wr["note"] else "normal"),
                        "speech_error_type": "replace",
                        "expected_ph": wr["expected_ph"],
                        "predicted_ph": wr["heard_ph"],
                        "preditected_ph": wr["heard_ph"],
                        "note": wr["note"],
                    })
            return JSONResponse({
                "start": {"sura_idx": sura, "aya_idx": aya, "uthmani_word_idx": 0, "uthmani_char_idx": 0, "phonemes_idx": 0},
                "end": {"sura_idx": sura, "aya_idx": aya, "uthmani_word_idx": len(uth_words) - 1, "uthmani_char_idx": len(uth_words[-1]), "phonemes_idx": len(full_ref_out.phonemes)},
                "uthmani_text": uthmani_ref,
                "predicted_phonemes": predicted_phonemes,
                "reference_phonemes": full_ref_out.phonemes,
                "words_audit": word_results,
                "errors": compat_errors,
            })
        raw_errs = explain_error(uthmani_text=uthmani_ref, ref_ph_text=full_ref_out.phonemes, predicted_ph_text=predicted_phonemes, mappings=full_ref_out.mappings)
        serialized_errs = [asdict(e) if hasattr(e, "__dataclass_fields__") else e for e in raw_errs]
        return JSONResponse({
            "start": {"sura_idx": sura, "aya_idx": aya, "uthmani_word_idx": 0, "uthmani_char_idx": 0, "phonemes_idx": 0},
            "end": {"sura_idx": sura, "aya_idx": aya, "uthmani_word_idx": uthmani_ref.count(" "), "uthmani_char_idx": len(uthmani_ref), "phonemes_idx": len(full_ref_out.phonemes)},
            "uthmani_text": uthmani_ref,
            "predicted_phonemes": predicted_phonemes,
            "reference_phonemes": full_ref_out.phonemes,
            "errors": serialized_errs,
        })
    return app

try:
    app = create_fastapi_app()
except Exception:
    app = None
