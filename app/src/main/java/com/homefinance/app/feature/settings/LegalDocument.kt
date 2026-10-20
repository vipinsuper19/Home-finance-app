package com.homefinance.app.feature.settings

import androidx.annotation.StringRes
import com.homefinance.app.R

/**
 * The in-app documents Settings can open.
 *
 * There is no hosted policy page or support address for this project, so "Privacy Policy", "Terms &
 * Conditions" and "Help & Support" are real, readable screens whose text is shipped in the app rather
 * than links that would open nothing. The last line of each screen says so, so nobody is left thinking
 * an online document exists.
 *
 * The enum's [name] is what travels through the type-safe route, because a route can carry a `String`
 * and not a resource id.
 */
enum class LegalDocument(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
) {
    PRIVACY(R.string.legal_privacy_title, R.string.legal_privacy_body),
    TERMS(R.string.legal_terms_title, R.string.legal_terms_body),
    HELP(R.string.legal_help_title, R.string.legal_help_body);

    companion object {
        /** Resolves a route argument, falling back to the privacy document for an unknown value. */
        fun fromRoute(value: String?): LegalDocument =
            entries.firstOrNull { it.name == value } ?: PRIVACY
    }
}
