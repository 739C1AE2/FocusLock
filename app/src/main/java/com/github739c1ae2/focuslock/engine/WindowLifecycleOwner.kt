package com.github739c1ae2.focuslock.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class WindowLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)
    override val viewModelStore: ViewModelStore get() = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry


    init {
        savedStateRegistryController.performRestore(null)
        lifecycle.currentState = Lifecycle.State.CREATED
    }


    fun onAttach() {
        lifecycle.currentState = Lifecycle.State.RESUMED
    }

    fun onDetach() {
        lifecycle.currentState = Lifecycle.State.CREATED
        viewModelStore.clear()
    }

    fun onScreenOff() {
        lifecycle.currentState = Lifecycle.State.STARTED
    }

    fun onScreenOn() {
        lifecycle.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
    }

    fun attachToView(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}