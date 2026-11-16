package com.readvocab.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.readvocab.app.AppContainer
import com.readvocab.app.ReadVocabApp

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as ReadVocabApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

fun formatDue(due: Long, now: Long = System.currentTimeMillis()): String {
    val diff = due - now
    if (diff <= 0) return "待复习"
    val minutes = diff / 60_000
    return when {
        minutes < 60 -> "${minutes} 分钟后"
        minutes < 60 * 24 -> "${minutes / 60} 小时后"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天后"
        else -> "${minutes / (60 * 24 * 30)} 个月后"
    }
}

/** Sealed state for a one-shot async request (AI calls, imports). */
sealed interface Async<out T> {
    data object Idle : Async<Nothing>
    data object Loading : Async<Nothing>
    data class Done<T>(val value: T) : Async<T>
    data class Failed(val message: String) : Async<Nothing>
}
