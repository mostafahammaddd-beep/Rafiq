"""
Rafiq v6.0 — Coupled Waqf/Wasl/Ibtida QPS Word-Level Aligner & Diagnostic Engine.
"""
import time
import numpy as np

HARAKAT = set("َُؙِ۪")
MADD_CHARS = set("اۥۦـ")
NON_ONSET_CHARS = HARAKAT | MADD_CHARS | {"ڇ"}

VALID_WAQF_WORDS = {4, 5, 6, 11, 15, 18, 25, 29, 31, 39, 43, 46, 48, 49}

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


def segment_audio_at_waqf(wave, sr=16000, max_seg_sec=12.0):
    min_seg_samples = int(sr * 1.2)
    max_seg_samples = int(sr * max_seg_sec)
    if wave.size <= int(sr * 10.0):
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
    min_silence_frames = 32
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
        if seg.size <= max_seg_samples:
            final_segments.append(seg)
        else:
            mid = seg.size // 2
            final_segments.append(seg[:mid])
            final_segments.append(seg[mid:])
    return final_segments if final_segments else [wave]


def audit_sifat_on_alignment(word_results, pred_sifat, ref_sifat, confidence_threshold=0.86):
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


def create_fastapi_app():
    import os, tempfile
    from dataclasses import asdict
    from typing import Optional
    from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
    from fastapi.responses import JSONResponse

    app = FastAPI(title="Rafiq Precision Quranic Auditor Server", version="6.0.0")
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
        return {"status": "ok", "engine": "rafiq-v6"}

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
        segments = segment_audio_at_waqf(wave, sr=16000)
        pred_ph_parts = []
        pred_sifat_all = []
        for seg in segments:
            outs = muaalem([seg], [full_ref_out], sampling_rate=16000)
            out = outs[0]
            pred_ph_parts.append(out.phonemes.text)
            if getattr(out, "sifat", None):
                pred_sifat_all.extend(out.sifat)
        predicted_phonemes = "".join(pred_ph_parts)
        if not predicted_phonemes:
            raise HTTPException(status_code=404, detail="لم يُتعرَّف على أي صوت واضح في التسجيل")
        if sura == 2 and aya == 255:
            word_results = align_qps_to_ayat_kursi(predicted_phonemes, lenient_madd=lenient)
            if word_results is None:
                raise HTTPException(status_code=404, detail="تعذّرت مطابقة التلاوة مع آية الكرسي")
            audit_sifat_on_alignment(word_results, pred_sifat_all, getattr(full_ref_out, "sifat", []))
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
