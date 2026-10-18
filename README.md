# رَفِيق — Rafiq (Full-Stack `quran-muaalem` v0.2.2 + `quran-transcript` QPS Architecture)

بنية متكاملة (Full-Stack Production Architecture) لتصحيح التلاوة القرآنية (الحروف + التشكيل + أحكام التجويد والصفات العشر) بالاعتماد الكامل على المحرك الصوتي القرآني **`quran-muaalem` (`v0.2.2`)** ونموذج **`obadx/muaalem-model-v3_2` (`Wav2Vec2BertForMultilevelCTC`)** ومكتبة **`quran-transcript` (QPS)**.

## هيكل المشروع (`backend/` + `android/`)

### 1. `backend/` — الخادم السحابي على معالج رسومي (`Modal.com Serverless GPU Backend`)
- **`backend/modal_app.py`**:
  - يبني بيئة Modal على معالج **`T4` GPU (`gpu="T4"`)** مع تثبيت:
    `quran-muaalem[engine]>=0.2.2`, `quran-transcript`, `librosa`, `torch`, `fastapi[standard]`, `websockets`, `numba>=0.61.2`, و`ffmpeg`.
  - يحمّل مسبقاً ويهيّئ:
    - `Muaalem(model_name_or_path="obadx/muaalem-model-v3_2", device="cuda", dtype=torch.bfloat16)`
    - `MoshafAttributes(rewaya="hafs", madd_monfasel_len=4, madd_mottasel_len=4, madd_mottasel_waqf=4, madd_aared_len=4)`
  - يوفّر نقطتي اتصال عبر `@modal.asgi_app()`:
    1. **`WebSocket /ws/live-recitation`**: يستقبل البث الصوتي المباشر (`16kHz Mono PCM`) مع `sura_idx` و`aya_idx`، ويشغّل `quran_phonetizer` والاستدلال متعدد المستويات (الحروف الصوتية + الصفات العشر: `ghonna`, `qalqla`, `tafkheem_or_taqeeq`, `hams_or_jahr`, `shidda_or_rakhawa`, `itbaq`, `safeer`, `tikraar`, `tafashie`, `istitala`)، ويدفع الفروق الفورية (`JSON diffs`) عند كل وقفة قصيرة (`waqf`) أو حد كلمة.
    2. **`POST /correct-recitation`**: يستقبل ملف الصوت (`WAV/PCM`) مع `sura_idx` و`aya_idx` ويعيد `predicted_phonemes`, `reference_phonemes`, `uthmani_text`, ومصفوفة `errors` المتضمنة `uthmani_pos: [start_char, end_char]`, `error_type` (`"tajweed" | "tashkeel" | "normal"`), `speech_error_type` (`"replace" | "insert" | "delete"`), `expected_ph`, `predicted_ph`, `expected_len`, `predicted_len`, و`correction_tip`.

### 2. `android/` — تطبيق أندرويد اللحظي بدون مفاتيح API (`Zero-Config Real-Time Android App`)
- **`RecitationConfig.kt`**: يحتوي على `BACKEND_BASE_URL` مهيأ مسبقاً مع إمكانية لصق رابط `.modal.run` مرة واحدة عبر زر **(🌐 Server URL)** في الشريط العلوي بدون أي مفاتيح API.
- **`LiveAudioStreamer.kt`**: يلتقط الصوت بتردد `16kHz Mono PCM` عبر نسخة وحيدة من `AudioRecord` (دون أي تعارض مع `SpeechRecognizer`)، ويبث الصوت حياً عبر WebSocket مع تفعيل التقييم التلقائي عند كل وقفة قصيرة أو انتقال بين الكلمات دون أي شاشات انتظار.
- **`UthmaniPosHighlighter.kt`**: يلوّن الحرف والحركة الدقيقة داخل الكلمة العثمانية بالاعتماد على `uthmani_pos: [start_char, end_char]` و`error_type`:
  - **الأخضر (`#4ADE80`)**: نطق وصفات وتشكيل صحيح.
  - **الأصفر (`#FACC15`)**: `error_type == "tashkeel"` (خطأ في الحركة أو التنوين).
  - **البرتقالي (`#FF922B`)**: `error_type == "tajweed"` أو خطأ في الصفات ومقادير المدود والغنة (`expected_len` مقابل `predicted_len` بالحركات).
  - **الأحمر (`#EF4444`)**: `error_type == "normal"` (إبدال أو حذف أو زيادة حرف).
- **`MainActivity.kt`**: يعرض الآية العثمانية بالتلوين الحرفي اللحظي مع شريط المقارنة الصوتية (`QPS`) وبطاقات الأخطاء التفاعلية في أسفل الشاشة طوال التلاوة.
