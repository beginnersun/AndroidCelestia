/*
 * SettingsSliderFragment.kt
 *
 * Copyright (C) 2001-2020, the Celestia Development Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package space.celestia.mobilecelestia.settings

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import space.celestia.mobilecelestia.R

class SettingsSliderFragment : SettingsBaseFragment() {
    private var item: SettingsSliderItem? = null
    private var listener: Listener? = null

    override val title: String
        get() = item!!.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            item = it.getSerializable(ARG_ITEM) as SettingsSliderItem
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings_slider_list, container, false)
        (view as? RecyclerView)?.let {
            it.layoutManager = LinearLayoutManager(context)
            it.adapter = SettingsSliderRecyclerViewAdapter(item!!, listener)
        }
        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is Listener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement SettingsSliderFragment.Listener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    interface Listener {
        fun onSliderSettingItemChange(field: String, value: Double)
    }

    companion object {
        const val ARG_ITEM = "item"

        @JvmStatic
        fun newInstance(item: SettingsSliderItem) =
            SettingsSliderFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_ITEM, item)
                }
            }
    }
}