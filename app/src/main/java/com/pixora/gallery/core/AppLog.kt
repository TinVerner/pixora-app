package com.pixora.gallery.core

import android.util.Log

object AppLog {
    private const val TAG = "Pixora"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
