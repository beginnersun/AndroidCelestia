/*
 * SettingsFragment.kt
 *
 * Copyright (C) 2001-2020, Celestia Development Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package space.celestia.mobilecelestia.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import space.celestia.mobilecelestia.R
import space.celestia.mobilecelestia.celestia.CelestiaView
import space.celestia.mobilecelestia.common.NavigationFragment
import space.celestia.celestia.AppCore
import space.celestia.celestia.Renderer
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : NavigationFragment() {
    @Inject
    lateinit var appCore: AppCore
    @Inject
    lateinit var renderer: Renderer

    override fun createInitialFragment(savedInstanceState: Bundle?): SubFragment {
        return SettingsItemFragment.newInstance()
    }

    fun pushMainSettingItem(item: SettingsItem) {
        when (item) {
            is SettingsCommonItem -> {
                pushFragment(SettingsCommonFragment.newInstance(item))
            }
            is SettingsCurrentTimeItem -> {
                pushFragment(SettingsCurrentTimeFragment.newInstance())
            }
            is SettingsRenderInfoItem -> {
                renderer.enqueueTask {
                    val renderInfo = appCore.renderInfo
                    lifecycleScope.launch {
                        pushFragment(SimpleTextFragment.newInstance(item.name, renderInfo))
                    }
                }
            }
            is SettingsRefreshRateItem -> {
                pushFragment(SettingsRefreshRateFragment.newInstance())
            }
            is SettingsAboutItem -> {
                pushFragment(AboutFragment.newInstance())
            }
            is SettingsDataLocationItem -> {
                pushFragment(SettingsDataLocationFragment.newInstance())
            }
            is SettingsLanguageItem -> {
                pushFragment(SettingsLanguageFragment.newInstance())
            }
            else -> {
                throw RuntimeException("SettingsFragment cannot handle item $item")
            }
        }
    }

    fun reload() {
        val frag = childFragmentManager.findFragmentById(R.id.fragment_container)
        if (frag is SettingsBaseFragment)
            frag.reload()
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}
