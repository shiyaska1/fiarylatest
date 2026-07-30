package com.billing.pos.ui.billing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

/** Thin wrapper so screens can write `flow.collectAsStateSafe()`. */
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(): State<T> = collectAsState()
