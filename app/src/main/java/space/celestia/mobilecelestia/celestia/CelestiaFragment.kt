/*
 * CelestiaFragment.kt
 *
 * Copyright (C) 2001-2020, Celestia Development Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package space.celestia.mobilecelestia.celestia

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import androidx.core.view.MenuCompat
import androidx.fragment.app.Fragment
import space.celestia.mobilecelestia.MainActivity
import space.celestia.mobilecelestia.R
import space.celestia.mobilecelestia.browser.createAllBrowserItems
import space.celestia.mobilecelestia.core.*
import space.celestia.mobilecelestia.info.model.CelestiaAction
import space.celestia.mobilecelestia.utils.AppStatusReporter
import space.celestia.mobilecelestia.utils.CelestiaString
import java.util.*
import kotlin.concurrent.fixedRateTimer

class CelestiaFragment: Fragment(), SurfaceHolder.Callback, CelestiaControlView.Listener, AppStatusReporter.Listener, CelestiaAppCore.ContextMenuHandler {
    private var activity: Activity? = null

    // MARK: GL View
    private var glViewContainer: FrameLayout? = null
    private var glView: CelestiaView? = null
    private var viewInteraction: CelestiaInteraction? = null

    private var currentControlViewID = R.id.active_control_view_container

    // Parameters
    private var pathToLoad: String? = null
    private var cfgToLoad: String? = null
    private var addonToLoad: String? = null
    private var enableMultisample = false
    private var enableFullResolution = false
    private lateinit var languageOverride: String

    // MARK: Celestia
    private val core by lazy { CelestiaAppCore.shared() }
    private val renderer by lazy { CelestiaRenderer.shared() }
    private var pendingTarget: CelestiaSelection? = null
    private var browserItems: ArrayList<CelestiaBrowserItem> = arrayListOf()
    private var density: Float = 1f

    private val scaleFactor: Float
        get() = if (enableFullResolution) 1.0f else (1.0f / density)

    private val controlMargin
        get() = (4 * density).toInt()
    private val controlContainerTrailingMargin
        get() = (8 * density).toInt()

    private var zoomTimer: Timer? = null

    private var loadSuccess = false

    interface Listener {
        fun celestiaFragmentDidRequestActionMenu()
        fun celestiaFragmentDidRequestObjectInfo()
        fun provideFallbackConfigFilePath(): String
        fun provideFallbackDataDirectoryPath(): String
        fun celestiaFragmentLoadingFromFallback()
    }

    var listener: Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        density = resources.displayMetrics.density
        arguments?.let {
            pathToLoad = it.getString(ARG_DATA_DIR)
            cfgToLoad = it.getString(ARG_CFG_FILE)
            addonToLoad = it.getString(ARG_ADDON_DIR)
            enableMultisample = it.getBoolean(ARG_MULTI_SAMPLE)
            enableFullResolution = it.getBoolean(ARG_FULL_RESOLUTION)
            languageOverride = it.getString(ARG_LANG_OVERRIDE, "en")
        }

        if (savedInstanceState == null) {
            core.setRenderer(renderer)
            renderer.setEngineStartedListener {
                loadCelestia()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val reporter = AppStatusReporter.shared()
        val currentState = reporter.state
        reporter.register(this)

        val view = inflater.inflate(R.layout.fragment_celestia, container, false)
        glViewContainer = view.findViewById(R.id.celestia_gl_view)
        setupGLView()

        val activeControlView = CelestiaControlView(inflater.context, listOf(
            CelestiaToggleButton(R.drawable.control_mode_combined, CelestiaControlAction.ToggleModeToObject, CelestiaControlAction.ToggleModeToCamera),
            CelestiaPressButton(R.drawable.control_zoom_in, CelestiaControlAction.ZoomIn),
            CelestiaPressButton(R.drawable.control_zoom_out, CelestiaControlAction.ZoomOut),
            CelestiaTapButton(R.drawable.control_info, CelestiaControlAction.Info),
            CelestiaTapButton(R.drawable.control_action_menu, CelestiaControlAction.ShowMenu),
            CelestiaTapButton(R.drawable.control_hide, CelestiaControlAction.Hide)
        ))
        val inactiveControlView = CelestiaControlView(inflater.context, listOf(
            CelestiaTapButton(R.drawable.control_show, CelestiaControlAction.Show)
        ))
        val activeControlContainer = view.findViewById<FrameLayout>(R.id.active_control_view_container)
        val inactiveControlContainer = view.findViewById<FrameLayout>(R.id.inactive_control_view_container)

        activeControlContainer.addView(activeControlView)
        inactiveControlContainer.addView(inactiveControlView)

        val layoutParamsForControls = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        layoutParamsForControls.setMargins(controlMargin, controlMargin, controlMargin, controlMargin)
        activeControlView.layoutParams = layoutParamsForControls
        inactiveControlView.layoutParams = layoutParamsForControls

        activeControlView.listener = this
        inactiveControlView.listener = this

        if (currentState.value >= AppStatusReporter.State.LOADING_SUCCESS.value) {
            loadingFinished()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setOnApplyWindowInsetsListener { _, insets ->
                insets.displayCutout?.let {
                    applyCutout(it)
                }
                return@setOnApplyWindowInsetsListener insets
            }
        }

        return view
    }

    override fun onDestroyView() {
        AppStatusReporter.shared().unregister(this)
        core.setContextMenuHandler(null)

        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()

        renderer.pause()
    }

    override fun onResume() {
        super.onResume()

        renderer.resume()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (context is Listener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement CelestiaFragment.Listener")
        }
        activity = context as? Activity
    }

    override fun onDetach() {
        super.onDetach()

        listener = null
        activity = null
        zoomTimer?.cancel()
        zoomTimer = null
    }

    override fun celestiaLoadingStateChanged(newState: AppStatusReporter.State) {
        if (newState.value >= AppStatusReporter.State.LOADING_SUCCESS.value)
            loadingFinished()
    }

    override fun celestiaLoadingProgress(status: String) {}

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyCutout(cutout: DisplayCutout) {
        if (!loadSuccess) { return }

        val insets = cutout.safeInsets().scaleBy(scaleFactor)
        CelestiaView.callOnRenderThread {
            core.setSafeAreaInsets(insets)
        }

        val ltr = resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL
        val safeInsetEnd = if (ltr) cutout.safeInsetRight else cutout.safeInsetLeft

        val controlView = view?.findViewById<FrameLayout>(currentControlViewID) ?: return
        val params = controlView.layoutParams as? ConstraintLayout.LayoutParams
        if (params != null) {
            params.marginEnd = controlContainerTrailingMargin + safeInsetEnd
            controlView.layoutParams = params
        }
    }

    private fun setupGLView() {
        val activity = this.activity ?: return
        val container = glViewContainer ?: return
        val view = CelestiaView(activity, scaleFactor)

        registerForContextMenu(view)

        val interaction = CelestiaInteraction(activity)
        glView = view
        viewInteraction = interaction

        interaction.scaleFactor = scaleFactor
        interaction.density = density
        view.isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        renderer.startConditionally(activity, enableMultisample)
        container.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        view.holder?.addCallback(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun loadCelestia(): Boolean {
        val data = pathToLoad
        val cfg = cfgToLoad
        val addon = addonToLoad

        if (data == null || cfg == null) {
            AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING_FAILURE)
            return false
        }

        AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING)

        CelestiaAppCore.initGL()
        CelestiaAppCore.chdir(data)

        // Set up locale
        CelestiaAppCore.setLocaleDirectoryPath("$data/locale", languageOverride)

        val extraDirs = if (addon != null) arrayOf(addon) else null

        // Reading config, data
        if (!core.startSimulation(cfg, extraDirs, AppStatusReporter.shared())) {
            var lis = listener;
            if (lis != null) {
                // Read from fallback
                var fallbackConfigPath = lis.provideFallbackConfigFilePath();
                var fallbackDataPath = lis.provideFallbackDataDirectoryPath();
                if (fallbackConfigPath != cfg || fallbackDataPath != data) {
                    lis.celestiaFragmentLoadingFromFallback()
                    CelestiaAppCore.chdir(fallbackDataPath)
                    CelestiaAppCore.setLocaleDirectoryPath("$fallbackDataPath/locale", languageOverride)
                    if (!core.startSimulation(cfg, extraDirs, AppStatusReporter.shared())) {
                        AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING_FAILURE)
                        return false
                    }
                } else {
                    AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING_FAILURE)
                    return false
                }
            } else {
                AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING_FAILURE)
                return false
            }
        }

        // Prepare renderer
        if (!core.startRenderer()) {
            AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING_FAILURE)
            return false
        }

        // Prepare for browser items
        core.simulation.createAllBrowserItems()

        core.setDPI((96 * density * scaleFactor).toInt())
        core.setPickTolerance(10f * density * scaleFactor)

        // Use installed font
        val locale = CelestiaAppCore.getLocalizedString("LANGUAGE", "celestia")
        val preferredInstalledFont = MainActivity.availableInstalledFonts[locale] ?: MainActivity.defaultInstalledFont
        if (preferredInstalledFont != null) {
            val font = preferredInstalledFont.first
            val boldFont = preferredInstalledFont.second
            core.setFont(font.filePath, font.collectionIndex, 9)
            core.setTitleFont(boldFont.filePath, boldFont.collectionIndex, 15)
            core.setRendererFont(font.filePath, font.collectionIndex, 9, CelestiaAppCore.RENDER_FONT_STYLE_NORMAL)
            core.setRendererFont(boldFont.filePath, boldFont.collectionIndex, 15, CelestiaAppCore.RENDER_FONT_STYLE_LARGE)
        }

        // Display
        core.tick()
        core.start()

        AppStatusReporter.shared().updateState(AppStatusReporter.State.LOADING_SUCCESS)
        return true
    }

    private fun loadingFinished() {
        val interaction = viewInteraction ?: return
        val view = glView ?: return

        view.isReady = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.isContextClickable = true
        }
        interaction.isReady = true
        view.setOnTouchListener(interaction)
        view.setOnKeyListener(interaction)
        view.setOnGenericMotionListener(interaction)
        core.setContextMenuHandler(this)
        registerForContextMenu(view)
        loadSuccess = true

        Log.d(TAG, "Ready to display")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity?.runOnUiThread {
                this.view?.rootWindowInsets?.displayCutout?.let { applyCutout(it) }
            }
        }
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        val selection = pendingTarget ?: return
        if (selection.isEmpty) return

        browserItems.clear()

        fun createSubMenu(menu: Menu, browserItem: CelestiaBrowserItem) {
            val obj = browserItem.`object`
            if (obj != null) {
                menu.add(GROUP_BROWSER_ITEM_GO, browserItems.size, Menu.NONE, CelestiaString("Go", ""))
                browserItems.add(browserItem)
            }

            browserItem.children.withIndex().forEach {
                val subMenu = menu.addSubMenu(GROUP_BROWSER_ITEM, 0, Menu.NONE, browserItem.childNameAtIndex(it.index))
                createSubMenu(subMenu, it.value)
            }
            MenuCompat.setGroupDividerEnabled(menu, true)
        }

        menu.setHeaderTitle(core.simulation.universe.getNameForSelection(selection))

        CelestiaAction.allActions.withIndex().forEach {
            menu.add(GROUP_ACTION, it.index, Menu.NONE, it.value.title)
        }
        val obj = selection.`object`

        if (obj != null) {
            val browserItem = CelestiaBrowserItem(core.simulation.universe.getNameForSelection(selection), null, obj, core.simulation.universe)
            for (child in browserItem.children) {
                val subMenu = menu.addSubMenu(GROUP_BROWSER_ITEM, 0, Menu.NONE, child.name)
                createSubMenu(subMenu, child)
            }
        }

        if (obj is CelestiaBody) {
            val alternateSurfaces = obj.alternateSurfaceNames
            if (alternateSurfaces.size > 0) {
                val subMenu = menu.addSubMenu(GROUP_ALT_SURFACE_TOP, 0, Menu.NONE, CelestiaString("Alternate Surfaces", ""))
                subMenu.add(GROUP_ALT_SURFACE, 0, Menu.NONE, CelestiaString("Default", ""))
                alternateSurfaces.withIndex().forEach {
                    subMenu.add(GROUP_ALT_SURFACE, it.index + 1, Menu.NONE, it.value)
                }
            }
        }
        val markMenu = menu.addSubMenu(GROUP_MARK_TOP, 0, Menu.NONE, CelestiaString("Mark", ""))
        availableMarkers.withIndex().forEach {
            markMenu.add(GROUP_MARK, it.index, Menu.NONE, it.value)
        }
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val selection = pendingTarget ?: return true
        if (selection.isEmpty) return true
        val actions = CelestiaAction.allActions
        if (item.groupId == GROUP_ACTION) {
            if (item.itemId >= 0 && item.itemId < actions.size) {
                CelestiaView.callOnRenderThread {
                    core.simulation.selection = selection
                    core.charEnter(actions[item.itemId].value)
                }
            }
        } else if (item.groupId == GROUP_ALT_SURFACE) {
            val body = selection.body
            if (body != null) {
                CelestiaView.callOnRenderThread {
                    val alternateSurfaces = body.alternateSurfaceNames
                    if (item.itemId == 0) {
                        core.simulation.activeObserver.displayedSurface = ""
                    } else if (item.itemId >= 0 && item.itemId < alternateSurfaces.size) {
                        core.simulation.activeObserver.displayedSurface = alternateSurfaces[item.itemId - 1]
                    }
                }
            }
        } else if (item.groupId == GROUP_MARK) {
            CelestiaView.callOnRenderThread {
                if (item.itemId == availableMarkers.size) {
                    core.simulation.universe.unmark(selection)
                } else {
                    core.simulation.universe.mark(selection, item.itemId)
                    core.showMarkers = true
                }
            }
        } else if (item.groupId == GROUP_BROWSER_ITEM_GO) {
            if (item.itemId >= 0 && item.itemId < browserItems.size) {
                val ent = browserItems[item.itemId].`object`
                if (ent != null) {
                    val obj = CelestiaSelection.create(ent)
                    if (obj != null) {
                        CelestiaView.callOnRenderThread {
                            core.simulation.selection = obj
                            core.charEnter(CelestiaAction.GoTo.value)
                        }
                    }
                }
            }
        }
        if (item.subMenu == null) { // leaf clicked, do clean up
            pendingTarget = null
            browserItems.clear()
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Resize to $width x $height")
        renderer.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.setSurface(null)
    }

    // Actions
    override fun didTapAction(action: CelestiaControlAction) {
        when (action) {
            CelestiaControlAction.ShowMenu -> {
                listener?.celestiaFragmentDidRequestActionMenu()
            }
            CelestiaControlAction.Info -> {
                listener?.celestiaFragmentDidRequestObjectInfo()
            }
            CelestiaControlAction.Hide -> {
                hideCurrentControlViewToShow(R.id.inactive_control_view_container)
            }
            CelestiaControlAction.Show -> {
                hideCurrentControlViewToShow(R.id.active_control_view_container)
            }
            else -> {}
        }
    }

    private fun hideCurrentControlViewToShow(anotherView: Int) {
        val current = view?.findViewById<FrameLayout>(currentControlViewID) ?: return
        val new = view?.findViewById<FrameLayout>(anotherView) ?: return

        val ltr = resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL

        if (current == new) { return }

        var maxValue = current.width + controlContainerTrailingMargin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            maxValue += if (ltr)
                current.rootWindowInsets.displayCutout?.safeInsetRight ?: 0
            else
                current.rootWindowInsets.displayCutout?.safeInsetLeft ?: 0
        }

        // Reserve 1 dp to ensure it does not completely fall off the screen
        val hideAnimator = ObjectAnimator.ofFloat(current, "translationX", 0f, (if (ltr) 1 else -1) * (maxValue - density))
        hideAnimator.duration = 200
        hideAnimator.start()

        val finishBlock: (Animator) -> Unit = {
            val currentLayoutParams = current.layoutParams as? ConstraintLayout.LayoutParams
            val newLayoutParams = new.layoutParams as? ConstraintLayout.LayoutParams
            if (currentLayoutParams != null && newLayoutParams != null) {
                currentLayoutParams.startToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                currentLayoutParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
                currentLayoutParams.marginEnd = 0
                current.layoutParams = currentLayoutParams
                current.translationX = -1 * density

                newLayoutParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
                newLayoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID

                var insetEnd = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insetEnd = if (ltr)
                        new.rootWindowInsets.displayCutout?.safeInsetRight ?: 0
                    else
                        new.rootWindowInsets.displayCutout?.safeInsetLeft ?: 0
                }

                newLayoutParams.marginEnd = insetEnd + controlContainerTrailingMargin
                new.layoutParams = newLayoutParams
                new.translationX = 0f
            }
            currentControlViewID = anotherView
        }

        val showAnimator = ObjectAnimator.ofFloat(new, "translationX", 0f, -maxValue.toFloat())

        hideAnimator.addListener(onEnd = {
            showAnimator.duration = 200
            showAnimator.start()
        }, onCancel = finishBlock)

        showAnimator.addListener(onEnd = finishBlock, onCancel = finishBlock)
    }

    override fun didToggleToMode(action: CelestiaControlAction) {
        val viewInteraction = this.viewInteraction ?: return

        when (action) {
            CelestiaControlAction.ToggleModeToCamera -> {
                viewInteraction.setInteractionMode(CelestiaInteraction.InteractionMode.Camera)
                activity?.let {
                    Toast.makeText(it, CelestiaString("Switched to camera mode", ""), Toast.LENGTH_SHORT).show()
                }
            }
            CelestiaControlAction.ToggleModeToObject -> {
                viewInteraction.setInteractionMode(CelestiaInteraction.InteractionMode.Object)
                activity?.let {
                    Toast.makeText(it, CelestiaString("Switched to object mode", ""), Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    override fun didStartPressingAction(action: CelestiaControlAction) {
        val viewInteraction = this.viewInteraction ?: return

        when (action) {
            CelestiaControlAction.ZoomIn -> { viewInteraction.zoomMode = CelestiaInteraction.ZoomMode.In; viewInteraction.callZoom() }
            CelestiaControlAction.ZoomOut -> { viewInteraction.zoomMode = CelestiaInteraction.ZoomMode.Out; viewInteraction.callZoom() }
            else -> {}
        }

        zoomTimer?.cancel()
        zoomTimer = fixedRateTimer("zoom", false, 0, 100) {
            CelestiaView.callOnRenderThread {
                viewInteraction.callZoom()
            }
        }
    }

    override fun didEndPressingAction(action: CelestiaControlAction) {
        zoomTimer?.cancel()
        zoomTimer = null
        viewInteraction?.zoomMode = null
    }

    override fun requestContextMenu(x: Float, y: Float, selection: CelestiaSelection) {
        if (selection.isEmpty) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return // Avoid showing context menu before Android 8, since it is fullscreen

        pendingTarget = selection

        // Show context menu on main thread
        activity?.runOnUiThread {
            glView?.showContextMenu(x / scaleFactor, y / scaleFactor)
        }
    }

    companion object {
        private const val ARG_DATA_DIR = "data"
        private const val ARG_CFG_FILE = "cfg"
        private const val ARG_ADDON_DIR = "addon"
        private const val ARG_MULTI_SAMPLE = "multisample"
        private const val ARG_FULL_RESOLUTION = "fullresolution"
        private const val ARG_LANG_OVERRIDE = "lang"
        private const val GROUP_ACTION = 0
        private const val GROUP_ALT_SURFACE_TOP = 1
        private const val GROUP_ALT_SURFACE = 2
        private const val GROUP_MARK_TOP = 3
        private const val GROUP_MARK = 4
        private const val GROUP_WEB_INFO = 5
        private const val GROUP_BROWSER_ITEM_GO = 6
        private const val GROUP_BROWSER_ITEM = 7

        val availableMarkers: List<String>
            get() = listOf(
                "Diamond", "Triangle", "Square", "Filled Square",
                "Plus", "X", "Left Arrow", "Right Arrow",
                "Up Arrow", "Down Arrow", "Circle", "Disk",
                "Crosshair", "Unmark"
            ).map { CelestiaString(it, "") }

        private const val TAG = "CelestiaFragment"

        fun newInstance(data: String, cfg: String, addon: String?, enableMultisample: Boolean, enableFullResolution: Boolean, languageOverride: String) =
            CelestiaFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DATA_DIR, data)
                    putString(ARG_CFG_FILE, cfg)
                    putBoolean(ARG_MULTI_SAMPLE, enableMultisample)
                    putBoolean(ARG_FULL_RESOLUTION, enableFullResolution)
                    putString(ARG_LANG_OVERRIDE, languageOverride)
                    if (addon != null) {
                        putString(ARG_ADDON_DIR, addon)
                    }
                }
            }
    }
}

class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun scaleBy(factor: Float): Insets {
        return Insets(
            (left * factor).toInt(),
            (top * factor).toInt(),
            (right * factor).toInt(),
            (bottom * factor).toInt()
         )
    }
}

@RequiresApi(Build.VERSION_CODES.P)
fun DisplayCutout.safeInsets(): Insets {
    return Insets(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom)
}

fun CelestiaAppCore.setSafeAreaInsets(insets: Insets) {
    setSafeAreaInsets(insets.left, insets.top, insets.right, insets.bottom)
}