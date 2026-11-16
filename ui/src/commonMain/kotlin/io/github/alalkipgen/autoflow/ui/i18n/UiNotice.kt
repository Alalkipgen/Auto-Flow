package io.github.alalkipgen.autoflow.ui.i18n

/** Keep an action result as a token + data, not a translation captured before a locale switch. */
data class UiNotice(val key: UiText, val arguments: Map<String, Any?> = emptyMap()) {
    constructor(key: UiText, vararg arguments: Pair<String, Any?>) : this(key, arguments.toMap())
    fun text(language: UiLanguage): String = key.text(language, *arguments.map { (key, value) ->
        key to if (value is UiNotice) value.text(language) else value
    }.toTypedArray())
}
