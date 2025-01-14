/*
 * MainActivity.kt
 *
 * Copyright (C) 2001-2020, Celestia Development Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package space.celestia.mobilecelestia

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.LayoutDirection
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.Guideline
import androidx.core.animation.addListener
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import org.json.JSONObject
import space.celestia.celestia.*
import space.celestia.mobilecelestia.browser.*
import space.celestia.mobilecelestia.celestia.CelestiaFragment
import space.celestia.mobilecelestia.common.EdgeInsets
import space.celestia.mobilecelestia.common.Poppable
import space.celestia.mobilecelestia.common.RoundedCorners
import space.celestia.mobilecelestia.common.SheetLayout
import space.celestia.mobilecelestia.control.*
import space.celestia.mobilecelestia.eventfinder.EventFinderContainerFragment
import space.celestia.mobilecelestia.eventfinder.EventFinderInputFragment
import space.celestia.mobilecelestia.eventfinder.EventFinderResultFragment
import space.celestia.mobilecelestia.favorite.*
import space.celestia.mobilecelestia.help.HelpAction
import space.celestia.mobilecelestia.help.HelpFragment
import space.celestia.mobilecelestia.info.InfoFragment
import space.celestia.mobilecelestia.info.model.*
import space.celestia.mobilecelestia.loading.LoadingFragment
import space.celestia.mobilecelestia.resource.*
import space.celestia.mobilecelestia.resource.model.*
import space.celestia.mobilecelestia.search.SearchContainerFragment
import space.celestia.mobilecelestia.search.SearchFragment
import space.celestia.mobilecelestia.search.hideKeyboard
import space.celestia.mobilecelestia.settings.*
import space.celestia.mobilecelestia.share.ShareAPIService
import space.celestia.mobilecelestia.share.URLCreationResponse
import space.celestia.mobilecelestia.share.URLResolultionResponse
import space.celestia.mobilecelestia.toolbar.ToolbarAction
import space.celestia.mobilecelestia.toolbar.ToolbarFragment
import space.celestia.mobilecelestia.travel.GoToContainerFragment
import space.celestia.mobilecelestia.travel.GoToInputFragment
import space.celestia.mobilecelestia.utils.*
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URL
import java.util.*
import javax.inject.Inject
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.system.exitProcess

@AndroidEntryPoint
class MainActivity : AppCompatActivity(R.layout.activity_main),
    ToolbarFragment.Listener,
    InfoFragment.Listener,
    SearchFragment.Listener,
    BottomControlFragment.Listener,
    BrowserCommonFragment.Listener,
    CameraControlFragment.Listener,
    HelpFragment.Listener,
    FavoriteFragment.Listener,
    FavoriteItemFragment.Listener,
    SettingsItemFragment.Listener,
    SettingsCurrentTimeFragment.Listener,
    AboutFragment.Listener,
    AppStatusReporter.Listener,
    CelestiaFragment.Listener,
    SettingsDataLocationFragment.Listener,
    SettingsCommonFragment.Listener,
    SettingsCommonFragment.DataSource,
    EventFinderInputFragment.Listener,
    EventFinderResultFragment.Listener,
    SettingsLanguageFragment.Listener,
    SettingsLanguageFragment.DataSource,
    AsyncListPagingFragment.Listener,
    DestinationDetailFragment.Listener,
    GoToInputFragment.Listener,
    ResourceItemFragment.Listener,
    SettingsRefreshRateFragment.Listener,
    CommonWebFragment.Listener {

    private val preferenceManager by lazy { PreferenceManager(this, "celestia") }
    private val settingManager by lazy { PreferenceManager(this, "celestia_setting") }
    private val legacyCelestiaParentPath by lazy { this.filesDir.absolutePath }
    private val celestiaParentPath by lazy { this.noBackupFilesDir.absolutePath }
    private val favoriteJsonFilePath by lazy { "${filesDir.absolutePath}/favorites.json" }

    @Inject
    lateinit var appCore: AppCore
    @Inject
    lateinit var renderer: Renderer
    @Inject
    lateinit var resourceAPI: ResourceAPIService
    @Inject
    lateinit var shareAPI: ShareAPIService
    @Inject
    lateinit var resourceManager: ResourceManager

    lateinit var appStatusReporter: AppStatusReporter

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppStatusInterface {
        fun getAppStatusReporter(): AppStatusReporter
    }

    private var interactionBlocked = false

    private var readyForInteraction = false
    private var scriptOrURLPath: String? = null
    private var addonToOpen: String? = null
    private var guideToOpen: String? = null

    private val defaultConfigFilePath by lazy { "$defaultDataDirectoryPath/$CELESTIA_CFG_NAME" }
    private val defaultDataDirectoryPath by lazy { "$celestiaParentPath/$CELESTIA_DATA_FOLDER_NAME" }

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var directoryChooserLauncher: ActivityResultLauncher<Intent>

    private val celestiaConfigFilePath: String
        get() {
            val custom = customConfigFilePath
            if (custom != null)
                return custom
            return defaultConfigFilePath
        }

    private val celestiaDataDirPath: String
        get() {
            val custom = customDataDirPath
            if (custom != null)
                return custom
            return defaultDataDirectoryPath
        }

    private val fontDirPath: String
        get() = "$celestiaParentPath/$CELESTIA_FONT_FOLDER_NAME"

    private var currentGoToData: GoToInputFragment.GoToData? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        val factory = EntryPointAccessors.fromApplication(this, AppStatusInterface::class.java)
        appStatusReporter = factory.getAppStatusReporter()

        val currentState = appStatusReporter.state
        val savedState = if (currentState == AppStatusReporter.State.NONE) null else savedInstanceState

        super.onCreate(savedState)

        Log.d(TAG, "Creating MainActivity")

        if (!AppCenter.isConfigured()) {
            AppCenter.start(
                application, "APPCENTER-APP-ID",
                Analytics::class.java, Crashes::class.java
            )

            Crashes.getMinidumpDirectory().thenAccept { path ->
                if (path != null) {
                    CrashHandler.setupNativeCrashesListener(path)
                }
            }
        }

        if (preferenceManager[PreferenceManager.PredefinedKey.PrivacyPolicyAccepted] != "true" && Locale.getDefault().country == Locale.CHINA.country) {
            val builder = MaterialAlertDialogBuilder(this)
            builder.setCancelable(false)
            builder.setTitle(R.string.privacy_policy_alert_title)
            builder.setMessage(R.string.privacy_policy_alert_detail)
            builder.setNeutralButton(R.string.privacy_policy_alert_show_policy_button_title) { _, _ ->
                openURL("https://celestia.mobi/privacy.html")
                finishAndRemoveTask()
                exitProcess(0)
            }
            builder.setPositiveButton(R.string.privacy_policy_alert_accept_button_title) { _, _ ->
                preferenceManager[PreferenceManager.PredefinedKey.PrivacyPolicyAccepted] = "true"
            }
            builder.setNegativeButton(R.string.privacy_policy_alert_decline_button_title) { dialog, _ ->
                dialog.cancel()
                finishAndRemoveTask()
                exitProcess(0)
            }
            builder.show()
        }

        // Handle notch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appStatusReporter.register(this)

        // Handle notch
        ViewCompat.setOnApplyWindowInsetsListener( findViewById<View>(android.R.id.content).rootView) { _, insets ->
            updateConfiguration(resources.configuration, insets)
            return@setOnApplyWindowInsetsListener insets
        }

        findViewById<View>(R.id.menu_overlay).setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                hideOverlay(true)
            }
            return@setOnTouchListener true
        }

        findViewById<View>(R.id.close_button).setOnClickListener {
            hideOverlay(true)
        }

        findViewById<View>(R.id.interaction_filter).setOnTouchListener { _, _ ->
            if (interactionBlocked) { return@setOnTouchListener true }
            // Pass through
            return@setOnTouchListener false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottom_sheet_overlay)) { _, insets ->
            // TODO: the suggested replacement for the deprecated methods does not work
            val builder = WindowInsetsCompat.Builder(insets).setSystemWindowInsets(Insets.of(0, 0, 0, insets.systemWindowInsetBottom))
            return@setOnApplyWindowInsetsListener builder.build()
        }

        if (currentState == AppStatusReporter.State.LOADING_FAILURE || currentState == AppStatusReporter.State.EXTERNAL_LOADING_FAILURE) {
            celestiaLoadingFailed()
            return
        }

        if (currentState == AppStatusReporter.State.NONE || currentState == AppStatusReporter.State.EXTERNAL_LOADING)  {
            loadExternalConfig(savedState)
        } else if (currentState == AppStatusReporter.State.LOADING) {
            // Do nothing
        } else if (currentState == AppStatusReporter.State.LOADING_SUCCESS) {
            celestiaLoadingSucceeded()
        } else if (currentState == AppStatusReporter.State.FINISHED) {
            celestiaLoadingFinished()
        }

        if (savedState != null) {
            val toolbarVisible = savedState.getBoolean(TOOLBAR_VISIBLE_TAG, false)
            val menuVisible = savedState.getBoolean(MENU_VISIBLE_TAG, false)
            val bottomSheetVisible = savedState.getBoolean(BOTTOM_SHEET_VISIBLE_TAG, false)
            currentGoToData = savedState.getSerializable(GO_TO_DATA_TAG) as? GoToInputFragment.GoToData

            findViewById<View>(R.id.toolbar_overlay).visibility = if (toolbarVisible) View.VISIBLE else View.GONE
            findViewById<View>(R.id.toolbar_container).visibility = if (toolbarVisible) View.VISIBLE else View.GONE

            findViewById<View>(R.id.menu_overlay).visibility = if (menuVisible) View.VISIBLE else View.GONE
            findViewById<View>(R.id.menu_container).visibility = if (menuVisible) View.VISIBLE else View.GONE

            findViewById<View>(R.id.bottom_sheet_overlay).visibility = if (bottomSheetVisible) View.VISIBLE else View.GONE
            findViewById<View>(R.id.bottom_sheet_card).visibility = if (bottomSheetVisible) View.VISIBLE else View.GONE
        }

        val weakSelf = WeakReference(this)
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val self = weakSelf.get() ?: return@registerForActivityResult
            val uri = it.data?.data ?: return@registerForActivityResult
            val path = RealPathUtils.getRealPath(self, uri)
            if (path == null) {
                self.showWrongPathProvided()
            } else {
                self.setConfigFilePath(path)
                self.reloadSettings()
            }
        }

        directoryChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val self = weakSelf.get() ?: return@registerForActivityResult
            val uri = it.data?.data ?: return@registerForActivityResult
            val path = RealPathUtils.getRealPath(self, DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)))
            if (path == null) {
                self.showWrongPathProvided()
            } else {
                self.setDataDirectoryPath(path)
                self.reloadSettings()
            }
        }

        val rootView = findViewById<View>(android.R.id.content).rootView
        updateConfiguration(resources.configuration, ViewCompat.getRootWindowInsets(rootView))

        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(MENU_VISIBLE_TAG, findViewById<View>(R.id.menu_container).visibility == View.VISIBLE)
        outState.putBoolean(BOTTOM_SHEET_VISIBLE_TAG, findViewById<View>(R.id.bottom_sheet_overlay).visibility == View.VISIBLE)
        outState.putBoolean(TOOLBAR_VISIBLE_TAG, findViewById<View>(R.id.toolbar_container).visibility == View.VISIBLE)
        outState.putSerializable(GO_TO_DATA_TAG, currentGoToData)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        appStatusReporter.unregister(this)

        Log.d(TAG, "Destroying MainActivity")

        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val rootView = findViewById<View>(android.R.id.content).rootView
        updateConfiguration(newConfig, ViewCompat.getRootWindowInsets(rootView))
    }

    override fun onBackPressed() {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is Poppable && frag.canPop()) {
            frag.popLast()
        } else {
            hideOverlay(true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun updateConfiguration(configuration: Configuration, windowInsets: WindowInsetsCompat?) {
        val density = resources.displayMetrics.density
        val isRTL = configuration.layoutDirection == LayoutDirection.RTL

        val safeInsets = EdgeInsets(
            EdgeInsets(windowInsets),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RoundedCorners(windowInsets) else RoundedCorners(0, 0, 0, 0),
            resources.configuration
        )
        val toolbarContainer = findViewById<View>(R.id.toolbar_overlay)
        val menuWidthGuideLine = findViewById<Guideline>(R.id.menu_width_guideline)

        val safeInsetStart = if (isRTL) safeInsets.right else safeInsets.left
        val safeInsetEnd = if (isRTL) safeInsets.left else safeInsets.right

        menuWidthGuideLine.setGuidelineEnd((220 * density).toInt() + safeInsetEnd)

        val toolbarParams = toolbarContainer.layoutParams as FrameLayout.LayoutParams
        toolbarParams.leftMargin = if (isRTL) safeInsetEnd else safeInsetStart
        toolbarParams.rightMargin = if (isRTL) safeInsetStart else safeInsetEnd
        toolbarParams.topMargin = safeInsets.top
        toolbarParams.bottomMargin = safeInsets.bottom

        val bottomSheetContainer = findViewById<SheetLayout>(R.id.bottom_sheet_overlay)
        bottomSheetContainer.edgeInsets = safeInsets
        bottomSheetContainer.postInvalidate()
    }

    private fun loadExternalConfig(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .add(R.id.loading_fragment_container, LoadingFragment.newInstance())
                .commitAllowingStateLoss()
        }
        appStatusReporter.updateState(AppStatusReporter.State.EXTERNAL_LOADING)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                copyAssetIfNeeded()
                if (!isActive) return@launch
                createAddonFolder()
                if (!isActive) return@launch
                loadConfig()
                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    loadConfigSuccess()
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    appStatusReporter.updateState(AppStatusReporter.State.EXTERNAL_LOADING_FAILURE)
                    loadConfigFailed(error)
                }
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, findViewById(R.id.main_container)).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun celestiaLoadingProgress(status: String) {}

    override fun celestiaLoadingStateChanged(newState: AppStatusReporter.State) {
        if (newState == AppStatusReporter.State.FINISHED) {
            celestiaLoadingFinished()
        } else if (newState == AppStatusReporter.State.LOADING_SUCCESS) {
            celestiaLoadingSucceeded()
        } else if (newState == AppStatusReporter.State.EXTERNAL_LOADING_FAILURE || newState == AppStatusReporter.State.LOADING_FAILURE) {
            celestiaLoadingFailed()
        }
    }

    private fun celestiaLoadingSucceeded() {
        renderer.enqueueTask {
            readSettings()

            appStatusReporter.updateState(AppStatusReporter.State.FINISHED)
        }
    }

    private fun celestiaLoadingFinished() {
        lifecycleScope.launch {
            supportFragmentManager.findFragmentById(R.id.loading_fragment_container)?.let {
                supportFragmentManager.beginTransaction().hide(it).remove(it).commitAllowingStateLoss()
            }
            findViewById<View>(R.id.loading_fragment_container).visibility = View.GONE

            resourceManager.addonDirectory = addonPaths.firstOrNull()

            readyForInteraction = true

            openURLOrScriptOrGreeting()
        }
    }

    private fun celestiaLoadingFailed() {
        appStatusReporter.updateStatus(CelestiaString("Loading Celestia failed…", ""))
        lifecycleScope.launch {
            removeCelestiaFragment()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        val rootView = findViewById<View>(android.R.id.content).rootView
        updateConfiguration(resources.configuration, ViewCompat.getRootWindowInsets(rootView))
    }

    private fun removeCelestiaFragment() {
        supportFragmentManager.findFragmentById(R.id.celestia_fragment_container)?.let {
            supportFragmentManager.beginTransaction().hide(it).remove(it).commitAllowingStateLoss()
        }
    }

    private fun copyAssetIfNeeded() {
        appStatusReporter.updateStatus(CelestiaString("Copying data…", ""))
        if (preferenceManager[PreferenceManager.PredefinedKey.DataVersion] != CURRENT_DATA_VERSION) {
            // When version name does not match, copy the asset again
            copyAssetsAndRemoveOldAssets()
        }
    }

    private fun loadConfig() {
        availableInstalledFonts = mapOf(
            "ja" to Pair(
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Regular.ttc", 0),
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Bold.ttc", 0)
            ),
            "ko" to Pair(
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Regular.ttc", 1),
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Bold.ttc", 1)
            ),
            "zh_CN" to Pair(
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Regular.ttc", 2),
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Bold.ttc", 2)
            ),
            "zh_TW" to Pair(
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Regular.ttc", 3),
                FontHelper.FontCompat("$fontDirPath/NotoSansCJK-Bold.ttc", 3)
            ),
            "ar" to Pair(
                FontHelper.FontCompat("$fontDirPath/NotoSansArabic-Regular.ttf", 0),
                FontHelper.FontCompat("$fontDirPath/NotoSansArabic-Bold.ttf", 0)
            )
        )
        defaultInstalledFont = Pair(
            FontHelper.FontCompat("$fontDirPath/NotoSans-Regular.ttf", 0),
            FontHelper.FontCompat("$fontDirPath/NotoSans-Bold.ttf", 0)
        )

        // Read custom paths here
        customConfigFilePath = preferenceManager[PreferenceManager.PredefinedKey.ConfigFilePath]
        customDataDirPath = preferenceManager[PreferenceManager.PredefinedKey.DataDirPath]
        customFrameRateOption = preferenceManager[PreferenceManager.PredefinedKey.FrameRateOption]?.toIntOrNull() ?: Renderer.FRAME_60FPS

        val localeDirectory = File("${celestiaDataDirPath}/locale")
        if (localeDirectory.exists()) {
            val languageCodes = ArrayList((localeDirectory.listFiles { file ->
                return@listFiles file.isDirectory
            } ?: arrayOf()).map { file -> file.name })
            availableLanguageCodes = languageCodes.sorted()
        }

        val languageFromSettings = preferenceManager[PreferenceManager.PredefinedKey.Language]
        if (languageFromSettings == null) {
            language = getString(R.string.celestia_language)
        } else {
            language = languageFromSettings
        }

        enableMultisample = preferenceManager[PreferenceManager.PredefinedKey.MSAA] == "true"
        enableHiDPI = preferenceManager[PreferenceManager.PredefinedKey.FullDPI] != "false" // default on
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return

        showToast(CelestiaString("Opening external file or URL…", ""), Toast.LENGTH_SHORT)
        if (uri.scheme == "content") {
            handleContentURI(uri)
        } else if (uri.scheme == "cel") {
            requestOpenURL(uri.toString())
        } else if (uri.scheme == "https") {
            handleAppLink(uri)
        } else if (uri.scheme == "celaddon") {
            if (uri.host == "item") {
                val id = uri.getQueryParameter("item") ?: return
                requestOpenAddon(id)
            }
        } else if (uri.scheme == "celguide") {
            if (uri.host == "guide") {
                val id = uri.getQueryParameter("guide") ?: return
                requestOpenGuide(id)
            }
        } else {
            // Cannot handle this URI scheme
            showAlert("Unknown URI scheme ${uri.scheme}")
        }
    }

    private fun handleAppLink(uri: Uri) {
        val path = uri.path ?: return
        when (path) {
            "/api/url" -> {
                val id = uri.getQueryParameter("id") ?: return
                lifecycleScope.launch {
                    try {
                        val result = shareAPI.resolve(path, id).commonHandler(URLResolultionResponse::class.java)
                        requestOpenURL(result.resolvedURL)
                    } catch (ignored: Throwable) {}
                }
            }
            "/resources/item" -> {
                val id = uri.getQueryParameter("item") ?: return
                requestOpenAddon(id)
            }
            "/resources/guide" -> {
                val guide = uri.getQueryParameter("guide") ?: return
                requestOpenGuide(guide)
            }
        }
    }

    private fun handleContentURI(uri: Uri) {
        // Content scheme, copy the resource to a temporary directory
        val itemName = uri.lastPathSegment
        // Check file name
        if (itemName == null) {
            showAlert("A filename needed to be present for ${uri.path}")
            return
        }
        // Check file type
        if (!itemName.endsWith(".cel") && !itemName.endsWith(".celx")) {
            showAlert("Celestia does not know how to open $itemName")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = "${cacheDir.absolutePath}/$itemName"
                if (!FileUtils.copyUri(this@MainActivity, uri, path)) {
                    throw RuntimeException("Failed to open $itemName")
                }
                withContext(Dispatchers.Main) {
                    requestRunScript(path)
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    showError(error)
                }
            }
        }
    }

    private fun requestRunScript(path: String) {
        scriptOrURLPath = path
        if (readyForInteraction)
            openURLOrScriptOrGreeting()
    }

    private fun requestOpenURL(url: String) {
        scriptOrURLPath = url
        if (readyForInteraction)
            openURLOrScriptOrGreeting()
    }

    private fun requestOpenAddon(addon: String) {
        addonToOpen = addon
        if (readyForInteraction)
            openURLOrScriptOrGreeting()
    }

    private fun requestOpenGuide(guide: String) {
        guideToOpen = guide
        if (readyForInteraction)
            openURLOrScriptOrGreeting()
    }

    private fun openCelestiaURL(uri: String) {
        val isURL = uri.startsWith("cel://")
        renderer.enqueueTask {
            if (isURL) {
                appCore.goToURL(uri)
            } else {
                appCore.runScript(uri)
            }
        }
    }

    private fun openURLOrScriptOrGreeting() {
        fun cleanup() {
            // Just clean up everything, only the first message gets presented
            scriptOrURLPath = null
            guideToOpen = null
            addonToOpen = null
        }

        if (preferenceManager[PreferenceManager.PredefinedKey.OnboardMessage] != "true") {
            preferenceManager[PreferenceManager.PredefinedKey.OnboardMessage] = "true"
            showHelp()
            cleanup()
            return
        }
        val scriptOrURL = scriptOrURLPath
        if (scriptOrURL != null) {
            val isURL = scriptOrURL.startsWith("cel://")
            showAlert(if (isURL) CelestiaString("Open URL?", "") else CelestiaString("Run Script?", "")) {
                openCelestiaURL(scriptOrURL)
            }
            cleanup()
            return
        }
        val guide = guideToOpen
        if (guide != null) {
            showBottomSheetFragment(CommonWebFragment.newInstance(buildGuideURI(guide), listOf("guide")))
            cleanup()
            return
        }
        val addon = addonToOpen
        val lang = AppCore.getLanguage()
        if (addon != null) {
            lifecycleScope.launch {
                try {
                    val result = resourceAPI.item(lang, addon).commonHandler(ResourceItem::class.java, ResourceAPI.gson)
                    showBottomSheetFragment(ResourceItemFragment.newInstance(result, lang))
                } catch (ignored: Throwable) {}
            }
            cleanup()
            return
        }

        // Check news
        lifecycleScope.launch {
            try {
                val result = resourceAPI.latest("news", lang).commonHandler(GuideItem::class.java, ResourceAPI.gson)
                if (preferenceManager[PreferenceManager.PredefinedKey.LastNewsID] == result.id) { return@launch }
                preferenceManager[PreferenceManager.PredefinedKey.LastNewsID] = result.id
                showBottomSheetFragment(CommonWebFragment.newInstance(buildGuideURI(result.id), listOf("guide")))
            } catch (ignored: Throwable) {}
        }
    }

    private fun buildGuideURI(id: String): Uri {
        val baseURL = "https://celestia.mobi/resources/guide"
        return Uri.parse(baseURL)
            .buildUpon()
            .appendQueryParameter("guide", id)
            .appendQueryParameter("lang", AppCore.getLanguage())
            .appendQueryParameter("environment", "app")
            .appendQueryParameter("theme", "dark")
            .build()
    }

    @Throws(IOException::class)
    private fun copyAssetsAndRemoveOldAssets() {
        try {
            // Remove old ones, ignore any exception thrown
            File(legacyCelestiaParentPath, CELESTIA_DATA_FOLDER_NAME).deleteRecursively()
            File(legacyCelestiaParentPath, CELESTIA_FONT_FOLDER_NAME).deleteRecursively()
            File(celestiaParentPath, CELESTIA_DATA_FOLDER_NAME).deleteRecursively()
            File(celestiaParentPath, CELESTIA_FONT_FOLDER_NAME).deleteRecursively()
        } catch (ignored: Exception) {}
        AssetUtils.copyFileOrDir(this@MainActivity, CELESTIA_DATA_FOLDER_NAME, celestiaParentPath)
        AssetUtils.copyFileOrDir(this@MainActivity, CELESTIA_FONT_FOLDER_NAME, celestiaParentPath)
        preferenceManager[PreferenceManager.PredefinedKey.DataVersion] = CURRENT_DATA_VERSION
    }

    private fun readSettings() {
        val map = readDefaultSetting()
        val bools = HashMap<String, Boolean>()
        val ints = HashMap<String, Int>()
        val doubles = HashMap<String, Double>()

        fun getDefaultInt(key: String): Int? {
            val value = map[key]
            if (value is Int) {
                return value
            }
            return null
        }

        fun getDefaultDouble(key: String): Double? {
            val value = map[key]
            if (value is Double) {
                return value
            }
            return null
        }

        fun getDefaultBool(key: String): Boolean? {
            val value = getDefaultInt(key)
            if (value != null) {
                if (value == 1) { return true }
                if (value == 0) { return false }
            }
            return null
        }

        fun getCustomInt(key: String): Int? {
            val value = settingManager[PreferenceManager.CustomKey(key)] ?: return null
            return try {
                value.toInt()
            } catch (exp: NumberFormatException) {
                null
            }
        }

        fun getCustomBool(key: String): Boolean? {
            val value = getCustomInt(key)
            if (value != null) {
                if (value == 1) { return true }
                if (value == 0) { return false }
            }
            return null
        }

        fun getCustomDouble(key: String): Double? {
            val value = settingManager[PreferenceManager.CustomKey(key)] ?: return null
            return try {
                value.toDouble()
            } catch (exp: NumberFormatException) {
                null
            }
        }

        for (key in SettingsKey.allBooleanCases) {
            val def = getDefaultBool(key.valueString)
            if (def != null) {
                bools[key.valueString] = def
            }
            val cus = getCustomBool(key.valueString)
            if (cus != null)
                bools[key.valueString] = cus
        }

        for (key in SettingsKey.allIntCases) {
            val def = getDefaultInt(key.valueString)
            if (def != null) {
                ints[key.valueString] = def
            }
            val cus = getCustomInt(key.valueString)
            if (cus != null)
                ints[key.valueString] = cus
        }

        for (key in SettingsKey.allDoubleCases) {
            val def = getDefaultDouble(key.valueString)
            if (def != null) {
                doubles[key.valueString] = def
            }
            val cus = getCustomDouble(key.valueString)
            if (cus != null)
                doubles[key.valueString] = cus
        }

        for ((key, value) in bools) {
            appCore.setBooleanValueForField(key, value)
        }

        for ((key, value) in ints) {
            appCore.setIntValueForField(key, value)
        }

        for ((key, value) in doubles) {
            appCore.setDoubleValueForField(key, value)
        }
    }

    private fun readDefaultSetting(): Map<String, Any> {
        try {
            val jsonFileContent = AssetUtils.readFileToText(this, "defaults.json")
            val json = JSONObject(jsonFileContent)
            val map = HashMap<String, Any>()
            for (key in json.keys()) {
                map[key] = json[key]
            }
            return map
        } catch (ignored: Throwable) {}
        return mapOf()
    }

    private fun createAddonFolder() {
        val addonDirs = listOf(getExternalFilesDir(CELESTIA_EXTRA_FOLDER_NAME), File(externalMediaDirs.firstOrNull(), CELESTIA_EXTRA_FOLDER_NAME)).mapNotNull { it }
        addonPaths = createDirectoriesIfNeeded(addonDirs)
        val scriptDirs = listOf(getExternalFilesDir(CELESTIA_SCRIPT_FOLDER_NAME), File(externalMediaDirs.firstOrNull(), CELESTIA_SCRIPT_FOLDER_NAME)).mapNotNull { it }
        extraScriptPaths = createDirectoriesIfNeeded(scriptDirs)
    }

    private fun createDirectoriesIfNeeded(dirs: List<File>): List<String> {
        val availablePaths = ArrayList<String>()
        for (dir in dirs) {
            try {
                if (dir.exists() || dir.mkdir()) {
                    availablePaths.add(dir.absolutePath)
                }
            } catch (ignored: Throwable) {}
        }
        return availablePaths
    }

    private fun loadConfigSuccess() {
        // Add gl fragment
        val celestiaFragment = CelestiaFragment.newInstance(
            celestiaDataDirPath,
            celestiaConfigFilePath,
            addonPaths,
            enableMultisample,
            enableHiDPI,
            customFrameRateOption,
            language
        )
        supportFragmentManager
            .beginTransaction()
            .add(R.id.celestia_fragment_container, celestiaFragment)
            .commitAllowingStateLoss()
    }

    private fun loadConfigFailed(error: Throwable) {
        Log.e(TAG, "Initialization failed, $error")
        showError(error)
    }

    private fun showToolbar() {
        showMenuFragment(ToolbarFragment.newInstance(listOf()))
    }

    override fun onToolbarActionSelected(action: ToolbarAction) {
        executeToolbarAction(action)
    }

    private fun executeToolbarAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.Search -> {
                showSearch()
            }
            ToolbarAction.Time -> {
                showTimeControl()
            }
            ToolbarAction.Script -> {
                showScriptControl()
            }
            ToolbarAction.Browse -> {
                showBrowser()
            }
            ToolbarAction.Camera -> {
                showCameraControl()
            }
            ToolbarAction.Help -> {
                showHelp()
            }
            ToolbarAction.Favorite -> {
                showFavorite()
            }
            ToolbarAction.Setting -> {
                showSettings()
            }
            ToolbarAction.Share -> {
                hideOverlay {
                    showShare()
                }
            }
            ToolbarAction.Home -> {
                renderer.enqueueTask {
                    appCore.charEnter(CelestiaAction.Home.value)
                }
            }
            ToolbarAction.Event -> {
                showEventFinder()
            }
            ToolbarAction.Exit -> {
                moveTaskToBack(true)
            }
            ToolbarAction.Addons -> {
                showInstalledAddons()
            }
            ToolbarAction.Paperplane -> {
                showGoTo()
            }
            ToolbarAction.Speedometer -> {
                showSpeedControl()
            }
            ToolbarAction.NewsArchive -> {
                val baseURL = "https://celestia.mobi/resources/guides"
                val uri = Uri.parse(baseURL).buildUpon().appendQueryParameter("type", "news").appendQueryParameter("lang", AppCore.getLanguage()).build()
                openURL(uri.toString())
            }
            ToolbarAction.Download -> {
                val baseURL = "https://celestia.mobi/resources/categories"
                val uri = Uri.parse(baseURL).buildUpon().appendQueryParameter("lang", AppCore.getLanguage()).build()
                openURL(uri.toString())
            }
        }
    }

    // Listeners...
    override fun onInfoActionSelected(action: InfoActionItem, item: Selection) {
        when (action) {
            is InfoNormalActionItem -> {
                renderer.enqueueTask {
                    appCore.simulation.selection = item
                    appCore.charEnter(action.item.value)
                }
            }
            is InfoSelectActionItem -> {
                renderer.enqueueTask {
                    appCore.simulation.selection = item
                }
            }
            is InfoWebActionItem -> {
                val url = item.webInfoURL
                if (url != null)
                    openURL(url)
            }
            is SubsystemActionItem -> {
                val entry = item.`object` ?: return
                val browserItem = BrowserItem(
                    appCore.simulation.universe.getNameForSelection(item),
                    null,
                    entry,
                    appCore.simulation.universe
                )
                showBottomSheetFragment(SubsystemBrowserFragment.newInstance(browserItem))
            }
            is AlternateSurfacesItem -> {
                val alternateSurfaces = item.body?.alternateSurfaceNames ?: return
                val current = appCore.simulation.activeObserver.displayedSurface
                var currentIndex = 0
                if (current != "") {
                    val index = alternateSurfaces.indexOf(current)
                    if (index >= 0)
                        currentIndex = index + 1
                }
                val surfaces = ArrayList<String>()
                surfaces.add(CelestiaString("Default", ""))
                surfaces.addAll(alternateSurfaces)
                showSingleSelection(CelestiaString("Alternate Surfaces", ""), surfaces, currentIndex) { index ->
                    if (index == null) return@showSingleSelection
                    if (index == 0)
                        appCore.simulation.activeObserver.displayedSurface = ""
                    else
                        appCore.simulation.activeObserver.displayedSurface = alternateSurfaces[index - 1]
                }
            }
            is MarkItem -> {
                val markers = CelestiaFragment.availableMarkers
                showSingleSelection(CelestiaString("Mark", ""), markers, -1) { newIndex ->
                    if (newIndex != null) {
                        if (newIndex >= Universe.MARKER_COUNT) {
                            appCore.simulation.universe.unmark(item)
                        } else {
                            appCore.simulation.universe.mark(item, newIndex)
                            appCore.showMarkers = true
                        }
                    }
                }
            }
            else -> {
            }
        }
    }

    override fun onInfoLinkMetaDataClicked(url: URL) {
        openURL(url.toString())
    }

    override fun onRunScript(type: String, content: String) {
        if (!supportedScriptTypes.contains(type)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(cacheDir, "${UUID.randomUUID()}.${type}")
            try {
                file.writeText(content)
                withContext(Dispatchers.Main) {
                    openCelestiaURL(file.absolutePath)
                }
            } catch (ignored: Throwable) {}
        }
    }

    override fun onExternalWebLinkClicked(url: String) {
        openURL(url)
    }

    override fun onShareURL(title: String, url: String) {
        shareURLDirect(title, url)
    }

    override fun onSearchItemSelected(text: String) {
        hideKeyboard()

        val savedGoToData = currentGoToData
        if (savedGoToData != null) {
            savedGoToData.objectName = text
            showGoTo(savedGoToData)
            return
        }

        val selection = appCore.simulation.findObject(text)
        if (selection.isEmpty) {
            showAlert(CelestiaString("Object not found", ""))
            return
        }

        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is SearchContainerFragment) {
            frag.pushSearchResult(selection)
        }
    }

    override fun onSearchItemSubmit(text: String) {
        onSearchItemSelected(text)
    }

    override fun onInstantActionSelected(item: CelestiaAction) {
        renderer.enqueueTask { appCore.charEnter(item.value) }
    }

    override fun onContinuousActionUp(item: CelestiaContinuosAction) {
        renderer.enqueueTask { appCore.keyUp(item.value) }
    }

    override fun onContinuousActionDown(item: CelestiaContinuosAction) {
        renderer.enqueueTask { appCore.keyDown(item.value) }
    }

    override fun objectExistsWithName(name: String): Boolean {
        return !appCore.simulation.findObject(name).isEmpty
    }

    override fun onGoToObject(name: String) {
        val sel = appCore.simulation.findObject(name)
        if (sel.isEmpty) {
            showAlert(CelestiaString("Object not found", ""))
            return
        }
        renderer.enqueueTask {
            appCore.simulation.selection = sel
            appCore.charEnter(CelestiaAction.GoTo.value)
        }
    }

    override fun onShareAddon(name: String, id: String) {
        val baseURL = "https://celestia.mobi/resources/item"
        val uri = Uri.parse(baseURL).buildUpon().appendQueryParameter("item", id).appendQueryParameter("lang", AppCore.getLanguage()).build()
        shareURLDirect(name, uri.toString())
    }

    private fun shareURLDirect(title: String, url: String) {
        val intent = ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setChooserTitle(title)
            .setText(url)
            .intent
        val ai = intent.resolveActivityInfo(packageManager, PackageManager.MATCH_DEFAULT_ONLY)
        if (ai != null && ai.exported)
            startActivity(intent)
        else
            showUnsupportedAction()
    }

    override fun onBottomControlHide() {
        hideOverlay(true)
    }

    override fun onBrowserItemSelected(item: BrowserUIItem) {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet) as? BrowserRootFragment ?: return
        if (!item.isLeaf) {
            frag.pushItem(item.item)
        } else {
            val obj = item.item.`object`
            if (obj != null) {
                frag.showInfo(Selection(obj))
            } else {
                showAlert(CelestiaString("Object not found", ""))
            }
        }
    }

    override fun onCameraActionClicked(action: CameraControlAction) {
        renderer.enqueueTask { appCore.simulation.reverseObserverOrientation() }
    }

    override fun onCameraActionStepperTouchDown(action: CameraControlAction) {
        renderer.enqueueTask { appCore.keyDown(action.value) }
    }

    override fun onCameraActionStepperTouchUp(action: CameraControlAction) {
        renderer.enqueueTask { appCore.keyUp(action.value) }
    }

    override fun onHelpActionSelected(action: HelpAction) {
        when (action) {
            HelpAction.RunDemo -> {
                renderer.enqueueTask { appCore.charEnter(CelestiaAction.RunDemo.value) }
            }
            HelpAction.ShowDestinations -> {
                showDestinations()
            }
        }
    }

    override fun onHelpURLSelected(url: String) {
        openURL(url)
    }

    override fun addFavoriteItem(item: MutableFavoriteBaseItem) {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is FavoriteFragment && item is FavoriteBookmarkItem) {
            val bookmark = appCore.currentBookmark
            if (bookmark == null) {
                showAlert(CelestiaString("Cannot add object", ""))
                return
            }
            frag.add(FavoriteBookmarkItem(bookmark))
        }
    }

    override fun shareFavoriteItem(item: MutableFavoriteBaseItem) {
        if (item is FavoriteBookmarkItem && item.bookmark.isLeaf) {
            shareURL(item.bookmark.url, item.bookmark.name)
        }
    }

    private fun readFavorites() {
        var favorites = arrayListOf<BookmarkNode>()
        try {
            val myType = object : TypeToken<List<BookmarkNode>>() {}.type
            val str = FileUtils.readFileToText(favoriteJsonFilePath)
            val decoded = Gson().fromJson<ArrayList<BookmarkNode>>(str, myType)
            favorites = decoded
        } catch (ignored: Throwable) { }
        updateCurrentBookmarks(favorites)
    }

    override fun saveFavorites() {
        val favorites = getCurrentBookmarks()
        try {
            val myType = object : TypeToken<List<BookmarkNode>>() {}.type
            val str = Gson().toJson(favorites, myType)
            FileUtils.writeTextToFile(str, favoriteJsonFilePath)
        } catch (ignored: Throwable) { }
    }

    override fun onFavoriteItemSelected(item: FavoriteBaseItem) {
        if (item.isLeaf) {
            if (item is FavoriteScriptItem) {
                openCelestiaURL(item.script.filename)
            } else if (item is FavoriteBookmarkItem) {
                openCelestiaURL(item.bookmark.url)
            } else if (item is FavoriteDestinationItem) {
                val destination = item.destination
                val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
                if (frag is FavoriteFragment) {
                    frag.pushFragment(DestinationDetailFragment.newInstance(destination))
                }
            }
        } else {
            val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
            if (frag is FavoriteFragment) {
                frag.pushItem(item)
            }
        }
    }

    override fun onEditGoToObject(goToData: GoToInputFragment.GoToData) {
        currentGoToData = goToData
        showBottomSheetFragment(SearchContainerFragment.newInstance())
    }

    override fun onGoToDestination(destination: Destination) {
        renderer.enqueueTask { appCore.simulation.goTo(destination) }
    }

    override fun deleteFavoriteItem(index: Int) {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is FavoriteFragment) {
            frag.remove(index)
        }
    }

    override fun renameFavoriteItem(item: MutableFavoriteBaseItem) {
        showTextInput(CelestiaString("Rename", ""), item.title) { text ->
            val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
            if (frag is FavoriteFragment) {
                frag.rename(item, text)
            }
        }
    }

    override fun onMainSettingItemSelected(item: SettingsItem) {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is SettingsFragment) {
            frag.pushMainSettingItem(item)
        }
    }

    override fun onCommonSettingSliderItemChange(field: String, value: Double) {
        applyDoubleValue(value, field, true)
    }

    override fun onCommonSettingActionItemSelected(action: Int) {
        renderer.enqueueTask { appCore.charEnter(action) }
    }

    override fun onCommonSettingUnknownAction(id: String) {
        if (id == settingUnmarkAllID)
            appCore.simulation.universe.unmarkAll()
    }

    override fun onCommonSettingSwitchStateChanged(field: String, value: Boolean, volatile: Boolean) {
        applyBooleanValue(value, field, true, volatile)
    }

    override fun onRefreshRateChanged(frameRateOption: Int) {
        preferenceManager[PreferenceManager.PredefinedKey.FrameRateOption] = frameRateOption.toString()
        customFrameRateOption = frameRateOption
        reloadSettings()
        (supportFragmentManager.findFragmentById(R.id.celestia_fragment_container) as? CelestiaFragment)?.updateFrameRateOption(frameRateOption)
    }

    private fun applyBooleanValue(value: Boolean, field: String, reloadSettings: Boolean = false, volatile: Boolean = false) {
        renderer.enqueueTask {
            appCore.setBooleanValueForField(field, value)
            lifecycleScope.launch {
                if (!volatile)
                    settingManager[PreferenceManager.CustomKey(field)] = if (value) "1" else "0"
                if (reloadSettings)
                    reloadSettings()
            }
        }
    }

    private fun applyIntValue(value: Int, field: String, reloadSettings: Boolean = false, volatile: Boolean = false) {
        renderer.enqueueTask {
            appCore.setIntValueForField(field, value)
            lifecycleScope.launch {
                if (!volatile)
                    settingManager[PreferenceManager.CustomKey(field)] = value.toString()
                if (reloadSettings)
                    reloadSettings()
            }
        }
    }

    private fun applyDoubleValue(value: Double, field: String, reloadSettings: Boolean = false, volatile: Boolean = false) {
        renderer.enqueueTask {
            appCore.setDoubleValueForField(field, value)
            lifecycleScope.launch {
                if (!volatile)
                    settingManager[PreferenceManager.CustomKey(field)] = value.toString()
                if (reloadSettings)
                    reloadSettings()
            }
        }
    }

    override fun onCommonSettingPreferenceSwitchStateChanged(
        key: PreferenceManager.PredefinedKey,
        value: Boolean
    ) {
        preferenceManager[key] = if (value) "true" else "false"
    }

    override fun onCommonSettingSelectionChanged(field: String, selected: Int) {
        applyIntValue(selected, field, true)
    }

    override fun commonSettingPreferenceSwitchState(key: PreferenceManager.PredefinedKey): Boolean? {
        return when (preferenceManager[key]) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    override fun commonSettingSliderValue(field: String): Double {
        return appCore.getDoubleValueForField(field)
    }

    override fun commonSettingSelectionValue(field: String): Int {
        return appCore.getIntValueForField(field)
    }

    override fun commonSettingSwitchState(field: String): Boolean {
        return appCore.getBooleanValueForPield(field)
    }

    override fun onCurrentTimeActionRequested(action: CurrentTimeAction) {
        when (action) {
            CurrentTimeAction.SetToCurrentTime -> {
                renderer.enqueueTask { appCore.charEnter(CelestiaAction.CurrentTime.value) }
                reloadSettings()
            }
            CurrentTimeAction.PickDate -> {
                val format = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(),"yyyyMMddHHmmss")
                showDateInput(CelestiaString("Please enter the time in \"%s\" format.", "").format(format), format) { date ->
                    if (date == null) {
                        showAlert(CelestiaString("Unrecognized time string.", ""))
                        return@showDateInput
                    }
                    renderer.enqueueTask {
                        appCore.simulation.time = date.julianDay
                        lifecycleScope.launch {
                            reloadSettings()
                        }
                    }
                }
            }
        }
    }

    override fun onAboutURLSelected(url: String) {
        openURL(url)
    }

    override fun onDataLocationNeedReset() {
        setConfigFilePath(null)
        setDataDirectoryPath(null)
        reloadSettings()
    }

    override fun onDataLocationRequested(dataType: DataType) {
        when (dataType) {
            DataType.Config -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.type = "*/*"
                intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
                if (intent.resolveActivity(packageManager) != null)
                    fileChooserLauncher.launch(intent)
                else
                    showUnsupportedAction()
            }
            DataType.DataDirectory -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
                if (intent.resolveActivity(packageManager) != null)
                    directoryChooserLauncher.launch(intent)
                else
                    showUnsupportedAction()
            }
        }
    }

    override fun onSetOverrideLanguage(language: String?) {
        preferenceManager[PreferenceManager.PredefinedKey.Language] = language
        reloadSettings()
    }

    override fun currentLanguage(): String {
        return AppCore.getLanguage()
    }

    override fun currentOverrideLanguage(): String? {
        return preferenceManager[PreferenceManager.PredefinedKey.Language]
    }

    override fun availableLanguages(): List<String> {
        return availableLanguageCodes
    }

    override fun onSearchForEvent(objectName: String, startDate: Date, endDate: Date) {
        val body = appCore.simulation.findObject(objectName).`object` as? Body
        if (body == null) {
            showAlert(CelestiaString("Object not found", ""))
            return
        }
        val finder = EclipseFinder(body)
        val alert = showLoading(CelestiaString("Calculating…", "")) {
            finder.abort()
        } ?: return
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                finder.search(
                    startDate.julianDay,
                    endDate.julianDay,
                    EclipseFinder.ECLIPSE_KIND_LUNAR or EclipseFinder.ECLIPSE_KIND_SOLAR
                )
            }
            EventFinderResultFragment.eclipses = results
            finder.close()
            if (alert.isShowing) alert.dismiss()
            val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
            if (frag is EventFinderContainerFragment) {
                frag.showResult()
            }
        }
    }

    override fun onEclipseChosen(eclipse: EclipseFinder.Eclipse) {
        renderer.enqueueTask {
            appCore.simulation.goToEclipse(eclipse)
        }
    }

    private fun setDataDirectoryPath(path: String?) {
        preferenceManager[PreferenceManager.PredefinedKey.DataDirPath] = path
        customDataDirPath = path
    }

    private fun setConfigFilePath(path: String?) {
        preferenceManager[PreferenceManager.PredefinedKey.ConfigFilePath] = path
        customConfigFilePath = path
    }

    private fun showWrongPathProvided() {
        val expectedParent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) externalMediaDirs.firstOrNull() else getExternalFilesDir(null)
        showAlert(CelestiaString("Unable to resolve path, please ensure that you have selected a path inside %s.", "").format(expectedParent?.absolutePath ?: ""))
    }

    private fun showUnsupportedAction() {
        showAlert(CelestiaString("Unsupported action.", ""))
    }

    override fun celestiaFragmentDidRequestActionMenu() {
        showToolbar()
    }

    override fun celestiaFragmentDidRequestObjectInfo() {
        renderer.enqueueTask {
            val selection = appCore.simulation.selection
            if (!selection.isEmpty) {
                lifecycleScope.launch {
                    showInfo(selection)
                }
            }
        }
    }

    override fun provideFallbackConfigFilePath(): String {
        return defaultConfigFilePath
    }

    override fun provideFallbackDataDirectoryPath(): String {
        return defaultDataDirectoryPath
    }

    override fun celestiaFragmentLoadingFromFallback() {
        lifecycleScope.launch {
            showAlert(CelestiaString("Error loading data, fallback to original configuration.", ""))
        }
    }

    private fun openURL(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val ai = intent.resolveActivityInfo(packageManager, PackageManager.MATCH_DEFAULT_ONLY)
        if (ai != null && ai.exported)
            startActivity(intent)
        else
            showUnsupportedAction()
    }

    private fun reloadSettings() {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is SettingsFragment) {
            frag.reload()
        }
    }

    private fun hideOverlay(animated: Boolean = false, completion: (() -> Unit)? = null) {
        hideMenu(animated) {
            hideToolbar(animated) {
                hideBottomSheet(animated) {
                    if (completion != null)
                        completion()
                }
            }
        }
    }

    private fun hideMenu(animated: Boolean, completion: (() -> Unit)? = null) {
        hideView(animated, R.id.menu_container, true) {
            findViewById<View>(R.id.menu_overlay).visibility = View.INVISIBLE
            val fragment = supportFragmentManager.findFragmentById(R.id.menu_container)
            if (fragment != null)
                supportFragmentManager.beginTransaction().hide(fragment).remove(fragment).commitAllowingStateLoss()
            if (completion != null)
                completion()
        }
    }

    private fun hideToolbar(animated: Boolean, completion: (() -> Unit)? = null) {
        hideView(animated, R.id.toolbar_container, false) {
            findViewById<View>(R.id.toolbar_overlay).visibility = View.INVISIBLE
            val fragment = supportFragmentManager.findFragmentById(R.id.toolbar_container)
            if (fragment != null)
                supportFragmentManager.beginTransaction().hide(fragment).remove(fragment).commitAllowingStateLoss()
            if (completion != null)
                completion()
        }
    }

    private fun hideBottomSheet(animated: Boolean, completion: (() -> Unit)? = null) {
        hideView(animated, R.id.bottom_sheet_card, false) {
            findViewById<View>(R.id.bottom_sheet_overlay).visibility = View.INVISIBLE
            val fragment = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
            if (fragment != null)
                supportFragmentManager.beginTransaction().hide(fragment).remove(fragment).commitAllowingStateLoss()
            if (completion != null)
                completion()
        }
    }

    private fun showView(animated: Boolean, viewID: Int, horizontal: Boolean, completion: () -> Unit = {}) {
        val view = findViewById<View>(viewID)
        view.visibility = View.VISIBLE

        val destination: Float = if (horizontal) {
            val ltr = resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL
            (if (ltr) view.width else -view.width).toFloat()
        } else {
            view.height.toFloat()
        }

        val executionBlock = {
            completion()
        }

        if (!animated) {
            executionBlock()
        } else {
            val showAnimator = ObjectAnimator.ofFloat(view, if (horizontal) "translationX" else "translationY", destination, 0f)

            showAnimator.duration = 200
            showAnimator.addListener(onStart = {
                interactionBlocked = true
            }, onEnd = {
                interactionBlocked = false
                executionBlock()
            }, onCancel = {
                interactionBlocked = false
                executionBlock()
            })
            showAnimator.start()
        }
    }

    private fun hideView(animated: Boolean, viewID: Int, horizontal: Boolean, completion: () -> Unit = {}) {
        val view = findViewById<View>(viewID)

        val parent = view.parent as? View ?: return

        val destination: Float = if (horizontal) {
            val ltr = resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL
            (if (ltr) (parent.width - view.left) else -(view.right)).toFloat()
        } else {
            (parent.height - view.top).toFloat()
        }

        val executionBlock = {
            view.visibility = View.INVISIBLE
            completion()
        }

        if (view.visibility != View.VISIBLE || !animated) {
            executionBlock()
        } else {
            val hideAnimator = ObjectAnimator.ofFloat(view, if (horizontal) "translationX" else "translationY", 0f, destination)
            hideAnimator.duration = 200
            hideAnimator.addListener(onStart = {
                interactionBlocked = true
            }, onEnd = {
                interactionBlocked = false
                executionBlock()
            }, onCancel = {
                interactionBlocked = false
                executionBlock()
            })
            hideAnimator.start()
        }
    }

    private fun showInfo(selection: Selection) {
        showBottomSheetFragment(InfoFragment.newInstance(selection))
    }

    private fun showSearch() {
        currentGoToData = null
        showBottomSheetFragment(SearchContainerFragment.newInstance())
    }

    private fun showBrowser() {
        showBottomSheetFragment(BrowserFragment.newInstance())
    }

    private fun showTimeControl() {
        val actions: List<CelestiaAction> = if (resources.configuration.layoutDirection == LayoutDirection.RTL) {
            listOf(
                CelestiaAction.Faster,
                CelestiaAction.PlayPause,
                CelestiaAction.Slower,
                CelestiaAction.Reverse
            )
        } else {
            listOf(
                CelestiaAction.Slower,
                CelestiaAction.PlayPause,
                CelestiaAction.Faster,
                CelestiaAction.Reverse
            )
        }
        showToolbarFragment(BottomControlFragment.newInstance(actions.map { InstantAction(it) }))
    }

    private fun showScriptControl() {
        showToolbarFragment(
            BottomControlFragment.newInstance(
                listOf(
                    CelestiaAction.PlayPause,
                    CelestiaAction.CancelScript
                ).map { InstantAction(it) }))
    }

    private fun showSpeedControl() {
        val isRTL = resources.configuration.layoutDirection == LayoutDirection.RTL
        val actions: ArrayList<BottomControlAction> = if (isRTL) arrayListOf(
            ContinuousAction(CelestiaContinuosAction.TravelFaster),
            ContinuousAction(CelestiaContinuosAction.TravelSlower),
        ) else arrayListOf(
            ContinuousAction(CelestiaContinuosAction.TravelSlower),
            ContinuousAction(CelestiaContinuosAction.TravelFaster),
        )
        actions.addAll(
            listOf(
                InstantAction(CelestiaAction.Stop),
                InstantAction(CelestiaAction.ReverseSpeed),
                GroupAction(
                    listOf(
                        GroupActionItem(CelestiaString("1 km/s", ""), CelestiaContinuosAction.F2),
                        GroupActionItem(CelestiaString("1000 km/s", ""), CelestiaContinuosAction.F3),
                        GroupActionItem(CelestiaString("c (lightspeed)", ""), CelestiaContinuosAction.F4),
                        GroupActionItem(CelestiaString("10c", ""), CelestiaContinuosAction.F5),
                        GroupActionItem(CelestiaString("1 AU/s", ""), CelestiaContinuosAction.F6),
                        GroupActionItem(CelestiaString("1 ly/s", ""), CelestiaContinuosAction.F7),
                    )
                )
            )
        )
        showToolbarFragment(
            BottomControlFragment.newInstance(actions)
        )
    }

    private fun showCameraControl() {
        showBottomSheetFragment(CameraControlContainerFragment.newInstance())
    }

    private fun showHelp() {
        showBottomSheetFragment(HelpFragment.newInstance())
    }

    private fun showFavorite() {
        readFavorites()
        val scripts = Script.getScriptsInDirectory("scripts", true)
        for (extraScriptPath in extraScriptPaths) {
            scripts.addAll(Script.getScriptsInDirectory(extraScriptPath, true))
        }
        updateCurrentScripts(scripts)
        updateCurrentDestinations(appCore.destinations)
        showBottomSheetFragment(FavoriteFragment.newInstance(FavoriteRoot()))
    }

    private fun showDestinations() {
        updateCurrentDestinations(appCore.destinations)
        showBottomSheetFragment(FavoriteFragment.newInstance(FavoriteTypeItem(FavoriteType.Destination)))
    }

    private fun showSettings() {
        showBottomSheetFragment(SettingsFragment.newInstance())
    }

    private fun showShare() {
        showOptions("", arrayOf(CelestiaString("Image", ""), CelestiaString("URL", ""))) { which ->
            when (which) {
                1 -> {
                    shareURL()
                }
                0 -> {
                    shareImage()
                }
                else -> {
                    Log.e(TAG, "Unknown selection in share $which")
                }
            }
        }
    }

    private fun shareURL() {
        renderer.enqueueTask {
            val orig = appCore.currentURL
            val name = appCore.simulation.universe.getNameForSelection(appCore.simulation.selection)
            lifecycleScope.launch {
                shareURL(orig, name)
            }
        }
    }

    private fun shareURL(url: String, name: String) {
        val encodedURL = android.util.Base64.encodeToString(url.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        showTextInput(CelestiaString("Share", ""), name) { title ->
            showToast(CelestiaString("Generating sharing link…", ""), Toast.LENGTH_SHORT)
            lifecycleScope.launch {
                try {
                    val result = shareAPI.create(title, encodedURL, versionCode.toString(), AppCore.getLanguage()).commonHandler(URLCreationResponse::class.java)
                    shareURLDirect(name, result.publicURL)
                } catch (ignored: CancellationException) {
                } catch (exception: ResultException) {
                    showShareError(reason = exception.reason)
                } catch (ignored: Throwable) {
                    showShareError()
                }
            }
        }
    }

    private fun shareImage() {
        val directory = File(cacheDir, "screenshots")
        if (!directory.exists())
            directory.mkdir()

        val file = File(directory, "${UUID.randomUUID()}.png")
        renderer.enqueueTask {
            appCore.draw()
            if (appCore.saveScreenshot(file.absolutePath, AppCore.IMAGE_TYPE_PNG)) {
                lifecycleScope.launch {
                    shareFile(file, "image/png")
                }
            } else {
                lifecycleScope.launch {
                    showToast(CelestiaString("Unable to generate image.", ""), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "space.celestia.mobilecelestia.fileprovider", file)
        val intent = ShareCompat.IntentBuilder(this)
            .setType(mimeType)
            .setStream(uri)
            .intent
        val ai = intent.resolveActivityInfo(packageManager, PackageManager.MATCH_DEFAULT_ONLY)
        if (ai != null && ai.exported)
            startActivity(intent)
        else
            showUnsupportedAction()
    }

    private fun showEventFinder() {
        showBottomSheetFragment(EventFinderContainerFragment.newInstance())
    }

    private fun showShareError(reason: String? = null) {
        showAlert(title = CelestiaString("Cannot share URL", ""), message = reason)
    }

    // Resource
    private fun showInstalledAddons() {
        showBottomSheetFragment(ResourceFragment.newInstance(AppCore.getLanguage()))
    }

    override fun onAsyncListPagingItemSelected(item: AsyncListItem) {
        val frag = supportFragmentManager.findFragmentById(R.id.bottom_sheet)
        if (frag is ResourceFragment) {
            if (item is ResourceItem) {
                frag.pushItem(item)
            }
        }
    }

    private fun showGoTo(data: GoToInputFragment.GoToData? = null) {
        val inputData = data ?: GoToInputFragment.GoToData(
            AppCore.getLocalizedString("Earth", "celestia-data"),
            0.0f,
            0.0f,
            8.0,
            GoToLocation.DistanceUnit.radii
        )
        showBottomSheetFragment(GoToContainerFragment.newInstance(inputData))
    }

    override fun onGoToObject(goToData: GoToInputFragment.GoToData) {
        val selection = appCore.simulation.findObject(goToData.objectName)
        if (selection.isEmpty) {
            showAlert(CelestiaString("Object not found", ""))
            return
        }

        val location = GoToLocation(
            selection,
            goToData.longitude,
            goToData.latitude,
            goToData.distance,
            goToData.distanceUnit
        )
        renderer.enqueueTask {
            appCore.simulation.goToLocation(location)
        }
    }

    // Utilities
    private fun showMenuFragment(fragment: Fragment) {
        val ref = WeakReference(fragment)
        hideOverlay(true) {
            ref.get()?.let {
                showMenuFragmentDirect(it)
            }
        }
    }

    private fun showMenuFragmentDirect(fragment: Fragment) {
        findViewById<View>(R.id.menu_overlay).visibility = View.VISIBLE
        supportFragmentManager
            .beginTransaction()
            .add(R.id.menu_container, fragment)
            .commitAllowingStateLoss()
        showView(true, R.id.menu_container, true)
    }

    private fun showBottomSheetFragment(fragment: Fragment) {
        val ref = WeakReference(fragment)
        hideOverlay(true) {
            ref.get()?.let {
                showBottomSheetFragmentDirect(it)
            }
        }
    }

    private fun showBottomSheetFragmentDirect(fragment: Fragment) {
        findViewById<View>(R.id.bottom_sheet_overlay).visibility = View.VISIBLE
        supportFragmentManager
            .beginTransaction()
            .add(R.id.bottom_sheet, fragment)
            .commitAllowingStateLoss()
        showView(true, R.id.bottom_sheet_card, false)
    }

    private fun showToolbarFragment(fragment: Fragment) {
        val ref = WeakReference(fragment)
        hideOverlay(true) {
            ref.get()?.let {
                showToolbarFragmentDirect(it)
            }
        }
    }

    private fun showToolbarFragmentDirect(fragment: Fragment) {
        findViewById<View>(R.id.toolbar_overlay).visibility = View.VISIBLE
        supportFragmentManager
            .beginTransaction()
            .add(R.id.toolbar_container, fragment)
            .commitAllowingStateLoss()
        showView(true, R.id.toolbar_container, false)
    }

    companion object {
        private const val CURRENT_DATA_VERSION = "31"
        // 31: 1.5.5 Localization update, data update (commit 9e7a8ee18a875ae8fc202439952256a5a2378a0b)
        // 30: 1.5.2 Localization update, data update (commit 430b955920e31c84fa433bc7aaa43938c5e04ca7)
        // 29: 1.5.0 Data update
        // 28: 1.4.5 Always remove old files
        // 27: 1.4.4 Data update
        // 26: 1.4.3 Localization update
        // 25: 1.4.3 Localization update, data update
        // 24: 1.4.2 Localization update
        // 23: 1.3.3 Localization update
        // 22: 1.3.0 Localization update
        // 21: 1.2.10 Update content to commit 2a80a7695f1dea73de20d3411bfdf8eff94155e5
        // 20: 1.2.7 Privacy Policy and Service Agreement
        // 19: 1.2.2 Shader updates
        // 18: 1.2.1 ru.po/bg.po updates
        // 17: 1.2 Beta, shader updates
        // 16: 1.1 Migrate from filesDir to noBackupFilesDir
        // 15: 1.0.5
        // 14: 1.0.4
        // 13: 1.0.3
        // 12: 1.0.2
        // 11: 1.0.1
        // 10: 1.0
        // < 10: 1.0 Beta X

        private const val CELESTIA_DATA_FOLDER_NAME = "CelestiaResources"
        private const val CELESTIA_FONT_FOLDER_NAME = "fonts"
        private const val CELESTIA_CFG_NAME = "celestia.cfg"
        private const val CELESTIA_EXTRA_FOLDER_NAME = "CelestiaResources/extras"
        private const val CELESTIA_SCRIPT_FOLDER_NAME = "CelestiaResources/scripts"

        private const val TOOLBAR_VISIBLE_TAG = "toolbar_visible"
        private const val MENU_VISIBLE_TAG = "menu_visible"
        private const val BOTTOM_SHEET_VISIBLE_TAG = "bottom_sheet_visible"
        private const val GO_TO_DATA_TAG = "go_to"

        private const val TAG = "MainActivity"

        var customDataDirPath: String? = null
        var customConfigFilePath: String? = null
        var customFrameRateOption: Int = Renderer.FRAME_60FPS
        private var language: String = "en"
        private var addonPaths: List<String> = listOf()
        private var extraScriptPaths: List<String> = listOf()
        private var enableMultisample = false
        private var enableHiDPI = false

        var availableInstalledFonts: Map<String, Pair<FontHelper.FontCompat, FontHelper.FontCompat>> = mapOf()
        var defaultInstalledFont: Pair<FontHelper.FontCompat, FontHelper.FontCompat>? = null

        private var availableLanguageCodes: List<String> = listOf()

        private val supportedScriptTypes = listOf("cel", "celx")

        init {
            System.loadLibrary("nativecrashhandler")
            System.loadLibrary("celestia")
            AppCore.setUpLocale()
        }
    }
}
