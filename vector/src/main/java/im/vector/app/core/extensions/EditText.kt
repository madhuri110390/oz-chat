/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import im.vector.app.R
import im.vector.app.core.platform.SimpleTextWatcher

/*fun EditText.setupAsSearch(
        @DrawableRes searchIconRes: Int = im.vector.lib.ui.styles.R.drawable.ic_home_search,
        @DrawableRes clearIconRes: Int = im.vector.lib.ui.styles.R.drawable.ic_x_gray
) {
    addTextChangedListener(object : SimpleTextWatcher() {
        override fun afterTextChanged(s: Editable) {
            val clearIcon = if (s.isNotEmpty()) clearIconRes else 0
            setCompoundDrawablesWithIntrinsicBounds(searchIconRes, 0, clearIcon, 0)
        }
    })

    maxLines = 1
    inputType = InputType.TYPE_CLASS_TEXT
    imeOptions = EditorInfo.IME_ACTION_SEARCH
    setOnEditorActionListener { _, actionId, _ ->
        var consumed = false
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            hideKeyboard()
            consumed = true
        }
        consumed
    }

    setOnTouchListener(View.OnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_UP) {
            if (event.rawX >= (this.right - this.compoundPaddingRight)) {
                text = null
                return@OnTouchListener true
            }
        }
        return@OnTouchListener false
    })
}*/


fun TextInputEditText.setupAsSearch(
        textInputLayout: TextInputLayout,
        @DrawableRes searchIconRes: Int = R.drawable.ic_home_search,
        @DrawableRes clearIconRes: Int = R.drawable.ic_x_gray
) {
    // Configure the TextInputLayout with prefix and suffix
    textInputLayout.apply {
//        prefixText = "@"
//        suffixText = ":oz.openzippers.com"
        // Initially hide the start icon
        startIconDrawable = null
        endIconDrawable = null
        endIconMode = TextInputLayout.END_ICON_NONE
    }

    // Text watcher to handle text changes and show icons based on input
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // Show start icon when typing begins, and toggle end (clear) icon based on input
            if (s.isNullOrEmpty()) {
                textInputLayout.startIconDrawable = null
                textInputLayout.endIconMode = TextInputLayout.END_ICON_NONE
            } else {
                // Return start icon to null to follow user request "dont use icon in place of search bar"
                textInputLayout.startIconDrawable = null
                textInputLayout.endIconDrawable = ContextCompat.getDrawable(context, clearIconRes)
                textInputLayout.endIconMode = TextInputLayout.END_ICON_CUSTOM
            }
        }

        override fun afterTextChanged(s: Editable?) = Unit
    })

    // Handle clear icon click by setting an end icon listener
    textInputLayout.setEndIconOnClickListener {
        text?.clear() // Clears the text in TextInputEditText
    }

    setOnTouchListener(View.OnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_UP) {
            if (event.rawX >= (this.right - this.compoundPaddingRight)) {
                text = null
                return@OnTouchListener true
            }
        }
        return@OnTouchListener false
    })

    // Configure EditText properties for search action
    maxLines = 1
    inputType = InputType.TYPE_CLASS_TEXT
    imeOptions = EditorInfo.IME_ACTION_SEARCH

    // Handle search action on keyboard
    setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            hideKeyboard()
            true
        } else {
            false
        }
    }
}

fun EditText.setTextIfDifferent(newText: CharSequence?): Boolean {
    if (!isTextDifferent(newText, text)) {
        // Previous text is the same. No op
        return false
    }
    setText(newText)
    // Since the text changed we move the cursor to the end of the new text.
    // This allows us to fill in text programmatically with a different value,
    // but if the user is typing and the view is rebound we won't lose their cursor position.
    setSelection(newText?.length ?: 0)
    return true
}

private fun isTextDifferent(str1: CharSequence?, str2: CharSequence?): Boolean {
    if (str1 === str2) {
        return false
    }
    if (str1 == null || str2 == null) {
        return true
    }
    val length = str1.length
    if (length != str2.length) {
        return true
    }
    if (str1 is Spanned) {
        return str1 != str2
    }
    for (i in 0 until length) {
        if (str1[i] != str2[i]) {
            return true
        }
    }
    return false
}
