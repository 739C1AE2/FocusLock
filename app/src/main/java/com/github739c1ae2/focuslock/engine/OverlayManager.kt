package com.github739c1ae2.focuslock.engine

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github739c1ae2.focuslock.ui.screen.overlay.LockOverlayScreen
import com.github739c1ae2.focuslock.ui.screen.overlay.OverlayViewModel
import com.github739c1ae2.focuslock.ui.screen.overlay.OverlayViewModelEntryPoint
import com.github739c1ae2.focuslock.ui.theme.FocusLockTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch


const val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

class OverlayManager(private val context: Context, private val lockEngine: LockEngine) {

    companion object {
        private const val TAG = "LockOverlayWindow"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lifecycleOwner: WindowLifecycleOwner? = null
    private var composeView: ComposeView? = null

    private val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        OverlayViewModelEntryPoint::class.java
    )
    private val assistedFactory = entryPoint.getOverlayViewModelFactory()

    private val viewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OverlayViewModel::class.java)) {
                return assistedFactory.create(lockEngine) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private enum class OverlayWindowState {
        NOT_CREATED,
        SHOWN,
        HIDDEN
    }

    private var overlayState = OverlayWindowState.NOT_CREATED
    private val layoutParams = WindowManager.LayoutParams().apply {
        type = OVERLAY_WINDOW_TYPE
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            // 只关注主屏幕 (Display.DEFAULT_DISPLAY)
            if (displayId == Display.DEFAULT_DISPLAY) {
                val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                when (defaultDisplay?.state) {
                    Display.STATE_OFF -> {
                        Log.d(TAG, "屏幕已关闭")
                        lifecycleOwner?.onScreenOff()
                        hideWindow()
                    }

                    Display.STATE_ON -> {
                        Log.d(TAG, "屏幕已打开")
                        lifecycleOwner?.onScreenOn()
                        updateWindowFlags(lockEngine.engineState.value)
                    }

                    else -> {}
                }
            }
        }
    }

    private var isListenerRegistered = false

    init {
        lockEngine.engineScope.launch(Dispatchers.Main) {
            lockEngine.engineState
                .distinctUntilChangedBy {
                    when (it) {
                        is EngineState.Idle -> it::class
                        is EngineState.InSession -> it.sessionState::class
                    }
                }
                .collect { state ->
                    updateWindowFlags(state)
                }
        }
    }

    private fun updateWindowFlags(state: EngineState) {
        if (state == EngineState.Idle) {
            if (isListenerRegistered) {
                displayManager.unregisterDisplayListener(displayListener)
                isListenerRegistered = false
            }
            removeWindow()
        } else if (state is EngineState.InSession) {

            if (!isListenerRegistered) {
                displayManager.registerDisplayListener(displayListener, null)
                isListenerRegistered = true
            }

            when (state.sessionState) {
                is SessionState.Warning -> {
                    layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                    layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                    layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    layoutParams.alpha = 0.8f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutParams.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                    showWindow()
                }

                is SessionState.Locked -> {
                    // 锁机状态：全屏且拦截所有触摸事件
                    layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
                    layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
                    layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    layoutParams.alpha = 1.0f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutParams.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    showWindow()
                }

                SessionState.Allowed, SessionState.Paused -> {
                    hideWindow()
                }
            }
        }
    }

    private fun createWindow() {
        if (overlayState != OverlayWindowState.NOT_CREATED) {
            return
        }
        Log.d(TAG, "创建覆盖窗口")
        composeView = ComposeView(context)
        lifecycleOwner = WindowLifecycleOwner().apply {
            attachToView(composeView!!)
            onAttach()
        }
        composeView!!.setContent {
            FocusLockTheme {
                LockOverlayScreen(
                    viewModel = viewModel(factory = viewModelFactory)
                )
            }
        }
        overlayState = OverlayWindowState.HIDDEN
    }

    private fun showWindow() {
        when (overlayState) {
            OverlayWindowState.NOT_CREATED -> {
                createWindow()
                requireNotNull(lifecycleOwner).onAttach()
                windowManager.addView(composeView, layoutParams)
            }

            OverlayWindowState.HIDDEN -> {
                requireNotNull(lifecycleOwner).onAttach()
                windowManager.addView(composeView, layoutParams)
            }

            OverlayWindowState.SHOWN -> {
                windowManager.updateViewLayout(composeView, layoutParams)
            }
        }
        overlayState = OverlayWindowState.SHOWN
        Log.d(TAG, "显示覆盖窗口")
    }

    private fun removeWindow() {
        if (overlayState == OverlayWindowState.NOT_CREATED) {
            return
        }
        Log.d(TAG, "移除覆盖窗口")
        requireNotNull(lifecycleOwner).onDestroy()
        if (overlayState != OverlayWindowState.HIDDEN) {
            windowManager.removeView(composeView)
        }
        lifecycleOwner = null
        composeView = null
        overlayState = OverlayWindowState.NOT_CREATED
    }

    private fun hideWindow() {
        if (overlayState != OverlayWindowState.SHOWN) {
            return
        }
        Log.d(TAG, "隐藏覆盖窗口")
        requireNotNull(lifecycleOwner).onDetach()
        windowManager.removeView(composeView)
        overlayState = OverlayWindowState.HIDDEN
    }

    fun destroy() {
        Log.d(TAG, "销毁覆盖窗口")
        if (isListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            isListenerRegistered = false
        }
        removeWindow()
    }
}