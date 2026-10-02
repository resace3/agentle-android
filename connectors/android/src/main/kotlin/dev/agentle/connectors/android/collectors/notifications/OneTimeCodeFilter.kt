package dev.agentle.connectors.android.collectors.notifications

import java.util.Locale

/**
 * Drops notification text that looks like a one-time code at capture (red team privacy-ai-16): 4 to 8 digits (also
 * split as `123 456` or `123-456`) within [WINDOW] characters of a code word in English or any supported language,
 * or an alphanumeric code near such a word. The event is kept; only its title and text are dropped.
 */
public object OneTimeCodeFilter {
    private const val WINDOW = 60
    private val DIGITS = Regex("""(?<![\p{L}\d])(?:\d{4,8}|\d{3}[ -]\d{3,4})(?![\p{L}\d])""")

    /** Alphanumeric codes such as `G-482913`, `AB12CD` or `X7K-9Q2`: 4-10 characters mixing letters and digits. */
    private val ALPHANUMERIC = Regex(
        """(?<![\p{L}\d])(?=[A-Za-z0-9-]*\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9]{1,6}(?:-[A-Za-z0-9]{2,6})?(?![\p{L}\d])""",
    )

    private val ENGLISH = listOf("code", "otp", "verification", "verify", "passcode", "pin", "one-time", "one time", "2fa", "security")

    /** Code words per language (ISO 639 code); English always applies. */
    private val BY_LANGUAGE: Map<String, List<String>> = mapOf(
        "de" to listOf("code", "bestätigung", "bestätigungscode", "einmal", "kennwort", "pin", "tan"),
        "fr" to listOf("code", "vérification", "verification", "mot de passe", "confirmation", "pin"),
        "es" to listOf("código", "codigo", "verificación", "verificacion", "contraseña", "clave", "pin"),
        "pt" to listOf("código", "codigo", "verificação", "verificacao", "senha", "pin"),
        "it" to listOf("codice", "verifica", "password", "pin"),
        "nl" to listOf("code", "verificatie", "wachtwoord", "pincode"),
        "pl" to listOf("kod", "weryfikac", "hasło", "pin"),
        "tr" to listOf("kod", "doğrulama", "şifre"),
        "ru" to listOf("код", "пароль", "подтвержд"),
        "uk" to listOf("код", "пароль", "підтвердж"),
        "ja" to listOf("コード", "認証", "確認", "パスコード"),
        "zh" to listOf("验证码", "驗證碼", "代码", "密码", "校验码"),
        "ko" to listOf("코드", "인증", "비밀번호"),
        "ar" to listOf("رمز", "كود", "التحقق"),
        "hi" to listOf("कोड", "सत्यापन", "ओटीपी"),
    )

    private const val MIN_ALPHANUMERIC = 4
    private val ALL_WORDS: List<String> by lazy { (ENGLISH + BY_LANGUAGE.values.flatten()).distinct() }

    /** Whether [text] looks like it carries a one-time code for a device in [locale]. */
    public fun matches(text: String?, locale: Locale = Locale.getDefault()): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase(locale)
        // Every language's words apply (a Spanish code on an English device is still a code); the device language
        // only decides lowercasing.
        val words = ALL_WORDS
        val wordAt = words.flatMap { word -> Regex(Regex.escape(word)).findAll(lower).map { it.range } }
        if (wordAt.isEmpty()) return false
        val codes = DIGITS.findAll(lower).map { it.range } +
            ALPHANUMERIC.findAll(text).map { it.range }.filter { it.last - it.first + 1 >= MIN_ALPHANUMERIC }
        return codes.any { code ->
            wordAt.any { word -> word.first - code.last <= WINDOW && code.first - word.last <= WINDOW }
        }
    }
}
