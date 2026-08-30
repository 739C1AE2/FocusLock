package com.github739c1ae2.focuslock.ui.navigation.scene

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneDecoratorStrategyScope
import com.github739c1ae2.focuslock.ui.navigation.Navigator


@Composable
fun rememberBackInterceptionSceneDecoratorStrategy(navigator: Navigator): BackInterceptionSceneDecoratorStrategy {
    return remember(navigator) {
        BackInterceptionSceneDecoratorStrategy(navigator)
    }
}

data class BackInterceptionScene(
    private val scene: Scene<NavKey>,
    private val navigator: Navigator
) : Scene<NavKey> {
    override val key = scene.key
    override val entries = scene.entries
    override val previousEntries = scene.previousEntries

    override val content = @Composable {
        scene.content()
        val isTopScene = entries.size + previousEntries.size == navigator.state.backStack.size
        val count = entries.size
        val enabled = if (isTopScene) {
            val isDirty by remember(navigator.state.dirtyKeys) {
                derivedStateOf {
                    navigator.state.isTopRoutesDirty(count)
                }
            }
            isDirty
        } else {
            false
        }
        BackHandler(enabled) {
            navigator.safeGoBackMultiple(count)
        }
    }

}

class BackInterceptionSceneDecoratorStrategy(private val navigator: Navigator) :
    SceneDecoratorStrategy<NavKey> {
    override fun SceneDecoratorStrategyScope<NavKey>.decorateScene(
        scene: Scene<NavKey>
    ): Scene<NavKey> {
        return BackInterceptionScene(scene, navigator)
    }

}