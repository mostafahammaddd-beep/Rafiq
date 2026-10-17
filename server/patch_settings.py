"""
يضبط إعدادات محرك quran-muaalem للعمل على المعالج (CPU) وبصوت أطول من 15 ثانية.
أفضل جهد (best-effort): يطبع القيم قبل التعديل وبعده في سجل التشغيل لتتأكد بنفسك.
"""
import importlib.util
import pathlib
import re

MODULE = "quran_muaalem.engine.settings"
WANT = {
    "accelerator": "cpu",
    "dtype": "float32",
    "max_audio_seconds": "60",
    "max_batch_size": "4",
    "timeout": "170",
}


def patch_line(line, name, value):
    if not re.match(r"\s*" + name + r"\s*:", line):
        return line
    head = r"(=\s*(?:Field\(\s*(?:default\s*=\s*)?)?)"
    if re.fullmatch(r"\d+", value):
        return re.sub(head + r"\d+(?:\.\d+)?", lambda m: m.group(1) + value, line, count=1)
    return re.sub(head + r"""(['"])\w+\2""", lambda m: m.group(1) + m.group(2) + value + m.group(2), line, count=1)


def patch_source(src):
    out = []
    for line in src.splitlines(True):
        for name, value in WANT.items():
            line = patch_line(line, name, value)
        out.append(line)
    return "".join(out)


def main():
    try:
        spec = importlib.util.find_spec(MODULE)
        path = pathlib.Path(spec.origin)
        before = path.read_text(encoding="utf-8")
        after = patch_source(before)
        if after != before:
            path.write_text(after, encoding="utf-8")
        print("[patch_settings] file:", path, "| changed:", after != before)
        for line in after.splitlines():
            if any(re.match(r"\s*" + n + r"\s*:", line) for n in WANT):
                print("[patch_settings]  ", line.strip())
    except Exception as e:  # لا نُسقط الإقلاع بسبب هذا السكربت
        print("[patch_settings] تعذّر التعديل:", repr(e))


if __name__ == "__main__":
    main()
