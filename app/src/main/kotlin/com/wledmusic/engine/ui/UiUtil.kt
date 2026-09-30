package com.wledmusic.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Значение StateFlow с учётом жизненного цикла. */
@Composable
fun <T> StateFlow<T>.collectAsStateValue(): T {
    val value by collectAsStateWithLifecycle()
    return value
}
