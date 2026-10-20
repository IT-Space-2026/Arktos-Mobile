@file:JvmName("Injector")

package com.arktosbrowser.mobile.di

import com.arktosbrowser.mobile.BrowserApp
import android.content.Context
import androidx.fragment.app.Fragment

/**
 * The [AppComponent] attached to the application [Context].
 */
val Context.injector: AppComponent
    get() = (applicationContext as BrowserApp).applicationComponent

/**
 * The [AppComponent] attached to the context, note that the fragment must be attached.
 */
val Fragment.injector: AppComponent
    get() = (requireContext().applicationContext as BrowserApp).applicationComponent

/**
 * The [AppComponent] attached to the context, note that the fragment must be attached.
 */
@Suppress("DeprecatedCallableAddReplaceWith")
@Deprecated("Consumers should switch to support.v4.app.Fragment")
val android.app.Fragment.injector: AppComponent
    get() = (checkNotNull(activity) { "Fragment not attached" }.applicationContext as BrowserApp)
        .applicationComponent
