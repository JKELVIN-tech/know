package com.knowapp.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Counts changes made on this phone (sales, stock edits, transfers) so every open tab can refresh itself
object DataVersion {
    private val counter = MutableStateFlow(0)
    val flow: StateFlow<Int> = counter

    fun bump() {
        counter.value = counter.value + 1
    }
}
