package org.owntracks.android.testutils

import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.owntracks.android.R

fun scrollToPreferenceWithText(textResource: Int) {
    onView(withId(androidx.preference.R.id.recycler_view))
        .perform(
            RecyclerViewActions.actionOnItem<RecyclerView.ViewHolder>(
                hasDescendant(withText(textResource)),
                ViewActions.scrollTo()
            )
        )
}

fun setNotFirstStartPreferences() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putBoolean(context.getString(R.string.preferenceKeyFirstStart), false)
        .putBoolean(context.getString(R.string.preferenceKeySetupNotCompleted), false)
        .apply()
}
