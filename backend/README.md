# Rafiq Serverless GPU Backend (`quran-muaalem` v0.2.2 + `quran-transcript` QPS)

This directory contains the production-ready Modal.com Serverless GPU backend (`modal_app.py`) powered by:
- **`quran-muaalem` (`v0.2.2`)** with **`obadx/muaalem-model-v3_2`** (`Wav2Vec2BertForMultilevelCTC`, `device="cuda"`, `dtype=torch.bfloat16`)
- **`quran-transcript`** (`Aya`, `MoshafAttributes(rewaya="hafs", madd_monfasel_len=4, madd_mottasel_len=4, madd_mottasel_waqf=4, madd_aared_len=4)`, `quran_phonetizer`, `explain_error`)

## Quick Deploy to Modal.com (`T4` GPU)

```bash
pip install modal
modal setup
modal deploy backend/modal_app.py
```

Once deployed, Modal prints your endpoint URL (e.g. `https://<your-username>--rafiq-quran-muaalem-backend-fastapi-asgi-app.modal.run`). Paste that URL once via the **🌐 Server URL** button in the top bar of the Android app (or set `DEFAULT_BACKEND_BASE_URL` in `RecitationConfig.kt`).

## Exposed Endpoints

1. **`WebSocket /ws/live-recitation?sura_idx=2&aya_idx=255`**
   - Accepts streaming `16kHz` 16-bit Mono PCM audio chunks as the user recites.
   - Runs rolling QPS phonetization (`quran_phonetizer`) and multi-level CTC inference (Phonemes + 10 Sifat levels: `ghonna`, `qalqla`, `tafkheem_or_taqeeq`, `hams_or_jahr`, `shidda_or_rakhawa`, `itbaq`, `safeer`, `tikraar`, `tafashie`, `istitala`).
   - Pushes real-time JSON diffs (`predicted_phonemes`, `reference_phonemes`, `completed_words`, `uthmani_char_progress`, and `errors` with exact `uthmani_pos: [start_char, end_char]`) immediately on every micro-pause (`waqf`) or word boundary.
2. **`POST /correct-recitation`**
   - Accepts `file` (WAV or 16kHz PCM audio) + `sura_idx` and `aya_idx`.
   - Returns `predicted_phonemes`, `reference_phonemes`, `uthmani_text`, and `errors` containing `uthmani_pos: [start_char, end_char]`, `error_type` (`"tajweed" | "tashkeel" | "normal"`), `speech_error_type` (`"replace" | "insert" | "delete"`), `expected_ph`, `predicted_ph`, `expected_len`, `predicted_len`, and Arabic `correction_tip`.
