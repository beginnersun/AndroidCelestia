package space.celestia.mobilecelestia.common

import android.content.Context
import android.util.TypedValue
import space.celestia.mobilecelestia.R

fun Context.getSecondaryColor(): Int {
    val value = TypedValue()
    theme.resolveAttribute(R.attr.colorSecondary, value, true)
    return value.data
}