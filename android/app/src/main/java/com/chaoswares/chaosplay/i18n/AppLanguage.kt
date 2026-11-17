package com.chaoswares.chaosplay.i18n

/**
 * App interface language, spec section 5:
 * "Language selector in Settings", start with English/Hindi/Tamil,
 * "Apply without reinstalling", "System default" option.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", "System default"),
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी"),
    TAMIL("ta", "தமிழ்"),
    TELUGU("te", "తెలుగు"),
    MALAYALAM("ml", "മലയാളം"),
    KANNADA("kn", "ಕನ್ನಡ"),
    BENGALI("bn", "বাংলা"),
    MARATHI("mr", "मराठी"),
    SPANISH("es", "Español");

    companion object {
        /** Languages actually shipped with translated strings right now. */
        val TRANSLATED: Set<AppLanguage> = setOf(ENGLISH, HINDI, TAMIL)

        fun fromTag(tag: String): AppLanguage =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: SYSTEM

        /**
         * Resolves the language to actually use, given the saved preference
         * and the device locale. SYSTEM falls back to English when the
         * device language is not translated yet.
         */
        fun resolve(saved: AppLanguage, deviceLanguageTag: String): AppLanguage {
            if (saved != SYSTEM) return saved
            val device = fromTag(deviceLanguageTag)
            return if (device in TRANSLATED) device else ENGLISH
        }
    }
}