package com.vitalink.app.util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
object NurseAdviceRequests {
    private val pending = MutableStateFlow<Pair<Long, String>?>(null)
    val request = pending.asStateFlow()
    fun open(text: String) { pending.value = System.nanoTime() to text }
    fun consumed() { pending.value = null }
}
