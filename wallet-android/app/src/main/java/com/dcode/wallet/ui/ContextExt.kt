package com.dcode.wallet.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
