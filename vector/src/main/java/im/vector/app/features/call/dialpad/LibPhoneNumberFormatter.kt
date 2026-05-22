/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.call.dialpad

import android.text.Editable
import android.text.TextWatcher
import com.google.i18n.phonenumbers.PhoneNumberUtil

class LibPhoneNumberFormatter(private val regionCode: String) : TextWatcher {
    private val formatter = PhoneNumberUtil.getInstance().getAsYouTypeFormatter(regionCode)
    private var isFormatting = false
    private var previous = ""

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        if (isFormatting || s == null) return

        val digits = s.toString().filter { it.isDigit() || it == '+' }

        isFormatting = true
        formatter.clear()

        val formatted = buildString {
            for (char in digits) {
                append(formatter.inputDigit(char))
            }
        }

        if (s.toString() != formatted) {
            s.replace(0, s.length, formatted)
        }

        isFormatting = false
    }
}
