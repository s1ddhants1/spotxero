package app.spotxero.core

object PrefKeys {
    const val PREFS_NAME = "spotify_prefs"

    const val KEY_MASTER_ENABLED = "pref_master_enabled"
    const val KEY_ENABLE_PREMIUM = "pref_enable_premium"
    const val KEY_UNCAPPED_LYRICS = "pref_uncapped_lyrics"
    const val KEY_EXTERNAL_LYRICS = "pref_external_lyrics"
    const val KEY_FORCE_EXTERNAL_LYRICS = "pref_force_external_lyrics"
    const val KEY_LYRICS_TRANSLATIONS = "pref_lyrics_translations"
    const val KEY_AI_LYRICS_TRANSLATION_ENABLED = "pref_ai_lyrics_translation_enabled"
    const val KEY_AI_TRANSLATION_PROVIDER = "pref_ai_translation_provider"
    const val KEY_AI_TRANSLATION_API_KEY = "pref_ai_translation_api_key"
    const val KEY_AI_TRANSLATION_BASE_URL = "pref_ai_translation_base_url"
    const val KEY_AI_TRANSLATION_MODEL = "pref_ai_translation_model"
    const val KEY_AI_TRANSLATION_TARGET_LANG = "pref_ai_translation_target_lang"
    const val KEY_AI_TRANSLATION_CUSTOM_PROMPT = "pref_ai_translation_custom_prompt"
    const val KEY_AI_TRANSLATION_DUAL_LINE = "pref_ai_translation_dual_line"
    const val KEY_AI_TRANSLATION_TRANSLATE_MODE = "pref_ai_translation_translate_mode"
    const val KEY_AI_TRANSLATION_DEEPL_API_KEY = "pref_ai_translation_deepl_api_key"
    const val KEY_AI_TRANSLATION_DEEPL_FORMALITY = "pref_ai_translation_deepl_formality"
    const val KEY_AI_TRANSLATION_ACTIVATION_MODE = "pref_ai_translation_activation_mode"
    const val KEY_ENABLE_HAPTIC_FEEDBACK = "pref_enable_haptic_feedback"

    const val OpenRouterDefaultBaseUrl = "https://openrouter.ai/api/v1/chat/completions"
    const val OpenRouterDefaultModel = "qwen/qwen3.7-flash"
    const val GeminiDefaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent"
    const val GeminiDefaultModel = "gemini-flash-lite-latest"
    const val LEGACY_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    const val AnthropicDefaultEndpoint = "https://api.anthropic.com/v1/messages"
    const val AnthropicDefaultModel = "claude-haiku-5-5"

    const val DEFAULT_AI_SYSTEM_PROMPT = "You are a lyrics localization engine. Return only JSON that matches the provided schema. Do not explain, think aloud, use markdown, merge lines, split lines, or add notes. Return exactly {lineCount} strings in `lines`, preserving input line order. Empty input lines must stay empty. Make translations natural for music listening while staying faithful to meaning, tense, names, punctuation, and repeated hooks. Preserve text already in the target language verbatim, including the target-language parts of mixed lines."

    val LanguageCodeToName = mapOf(
        "af" to "Afrikaans",
        "az" to "Azərbaycan",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Malaysia",
        "ca" to "Català",
        "cs" to "Čeština",
        "da" to "Dansk",
        "de" to "Deutsch",
        "et" to "Eesti",
        "en-GB" to "English (UK)",
        "en" to "English (US)",
        "es" to "Español (España)",
        "es-419" to "Español (Latinoamérica)",
        "eu" to "Euskara",
        "fil" to "Filipino",
        "fr" to "Français",
        "fr-CA" to "Français (Canada)",
        "gl" to "Galego",
        "hr" to "Hrvatski",
        "zu" to "IsiZulu",
        "is" to "Íslenska",
        "it" to "Italiano",
        "sw" to "Kiswahili",
        "lt" to "Lietuvių",
        "hu" to "Magyar",
        "nl" to "Nederlands",
        "no" to "Norsk",
        "or" to "Odia",
        "uz" to "O‘zbe",
        "pl" to "Polski",
        "pt-PT" to "Português",
        "pt" to "Português (Brasil)",
        "ro" to "Română",
        "sq" to "Shqip",
        "sk" to "Slovenčina",
        "sl" to "Slovenščina",
        "fi" to "Suomi",
        "sv" to "Svenska",
        "bo" to "Tibetan བོད་སྐད།",
        "vi" to "Tiếng Việt",
        "tr" to "Türkçe",
        "bg" to "Български",
        "ky" to "Кыргызча",
        "kk" to "Қазақ Тілі",
        "mk" to "Македонски",
        "mn" to "Монгол",
        "ru" to "Русский",
        "sr" to "Српски",
        "uk" to "Українська",
        "el" to "Ελληνικά",
        "hy" to "Հայերեն",
        "iw" to "עברית",
        "ur" to "اردو",
        "ar" to "العربية",
        "fa" to "فارسی",
        "ne" to "नेपाली",
        "mr" to "मराठी",
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "pa" to "ਪੰਜਾਬੀ",
        "gu" to "ગુજરાતી",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
        "kn" to "ಕನ್ನಡ",
        "ml" to "മലയാളം",
        "si" to "සිංහල",
        "th" to "ภาษาไทย",
        "lo" to "ລາວ",
        "my" to "ဗမာ",
        "ka" to "ქართული",
        "am" to "አማርኛ",
        "km" to "ខ្មែរ",
        "zh-CN" to "中文 (简体)",
        "zh-TW" to "中文 (繁體)",
        "zh-HK" to "中文 (香港)",
        "ja" to "日本語",
        "ko" to "한국어",
    )
    const val KEY_LYRICS_PROVIDERS_ORDER = "pref_lyrics_providers_order"
    const val KEY_LYRICS_PROVIDER_BETTERLYRICS = "pref_lyrics_provider_betterlyrics"
    const val KEY_LYRICS_PROVIDER_LRCRED = "pref_lyrics_provider_lrcred"
    const val KEY_LYRICS_PROVIDER_LRCLIB = "pref_lyrics_provider_lrclib"
    const val KEY_LYRICS_PROVIDER_KUGOU = "pref_lyrics_provider_kugou"
    const val KEY_LYRICS_PROVIDER_NETEASE = "pref_lyrics_provider_netease"
    const val KEY_LYRICS_PROVIDER_GENIUS = "pref_lyrics_provider_genius"
    const val KEY_FORCE_LOCAL_PLAYBACK = "pref_force_local_playback"
    const val KEY_CLEAN_SHARING = "pref_clean_sharing"
    const val KEY_SPOTIFY_AMOLED = "pref_spotify_amoled"
    const val KEY_SPOTIFY_MONET = "pref_spotify_monet"
    const val KEY_SPOTIFY_ROUNDY_UI = "pref_spotify_roundy_ui"
    const val KEY_HIDE_PODCASTS = "pref_hide_podcasts"
    const val KEY_HIDE_AUDIOBOOKS = "pref_hide_audiobooks"
    const val KEY_SHUFFLE_STATE = "pref_user_shuffle_state"
    const val KEY_SMART_SHUFFLE_IN_MODES = "pref_smart_shuffle_in_modes"
}
