package com.chaoswares.chaosplay.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AppLanguage resolution: the "System default" option and the rule that an
 * untranslated device language falls back to English.
 */
class AppLanguageTest {

    @Test
    fun `system default is the first option and has an empty tag`() {
        assertThat(AppLanguage.entries.first()).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.SYSTEM.tag).isEmpty()
        assertThat(AppLanguage.SYSTEM.nativeName).isEqualTo("System default")
    }

    @Test
    fun `every non system language has a tag and a native name`() {
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { language ->
            assertThat(language.tag).isNotEmpty()
            assertThat(language.nativeName).isNotEmpty()
        }
    }

    @Test
    fun `language tags are unique`() {
        val tags = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.tag }
        assertThat(tags.toSet()).hasSize(tags.size)
    }

    @Test
    fun `from tag resolves known languages`() {
        assertThat(AppLanguage.fromTag("en")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.fromTag("ta")).isEqualTo(AppLanguage.TAMIL)
        assertThat(AppLanguage.fromTag("hi")).isEqualTo(AppLanguage.HINDI)
    }

    @Test
    fun `from tag is case insensitive`() {
        assertThat(AppLanguage.fromTag("EN")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.fromTag("Ta")).isEqualTo(AppLanguage.TAMIL)
    }

    @Test
    fun `from tag falls back to system for unknown tags`() {
        assertThat(AppLanguage.fromTag("zz")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTag("")).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    fun `an explicit saved language always wins`() {
        assertThat(AppLanguage.resolve(AppLanguage.TAMIL, "en"))
            .isEqualTo(AppLanguage.TAMIL)
        assertThat(AppLanguage.resolve(AppLanguage.HINDI, "ta"))
            .isEqualTo(AppLanguage.HINDI)
    }

    @Test
    fun `system default uses the device language when it is translated`() {
        assertThat(AppLanguage.resolve(AppLanguage.SYSTEM, "ta"))
            .isEqualTo(AppLanguage.TAMIL)
        assertThat(AppLanguage.resolve(AppLanguage.SYSTEM, "hi"))
            .isEqualTo(AppLanguage.HINDI)
        assertThat(AppLanguage.resolve(AppLanguage.SYSTEM, "en"))
            .isEqualTo(AppLanguage.ENGLISH)
    }

    @Test
    fun `system default falls back to english for an untranslated device language`() {
        assertThat(AppLanguage.resolve(AppLanguage.SYSTEM, "es"))
            .isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.resolve(AppLanguage.SYSTEM, "zz"))
            .isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.resolve(AppLanguage.SYSTEM, ""))
            .isEqualTo(AppLanguage.ENGLISH)
    }

    @Test
    fun `translated set matches the languages the spec ships first`() {
        assertThat(AppLanguage.TRANSLATED)
            .containsExactly(AppLanguage.ENGLISH, AppLanguage.HINDI, AppLanguage.TAMIL)
    }

    @Test
    fun `every translated language is a real enum option`() {
        AppLanguage.TRANSLATED.forEach { assertThat(AppLanguage.entries).contains(it) }
    }
}