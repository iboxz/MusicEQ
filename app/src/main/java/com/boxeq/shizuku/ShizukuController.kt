package com.boxeq.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuAvailability { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, GRANTED }

/** Tracks whether Shizuku is installed, running and authorised for BoxEQ. Uses only the public Shizuku API. */
class ShizukuController(private val context: Context) {
    private val _availability = MutableStateFlow(ShizukuAvailability.NOT_INSTALLED)
    val availability: StateFlow<ShizukuAvailability> = _availability.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        _availability.value = compute()
    }

    fun requestPermission() {
        if (compute() == ShizukuAvailability.NEEDS_PERMISSION) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }

    fun launchShizukuApp(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun compute(): ShizukuAvailability = runCatching {
        when {
            Shizuku.pingBinder() && !Shizuku.isPreV11() ->
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShizukuAvailability.GRANTED
                else ShizukuAvailability.NEEDS_PERMISSION
            isInstalled() -> ShizukuAvailability.NOT_RUNNING
            else -> ShizukuAvailability.NOT_INSTALLED
        }
    }.getOrDefault(ShizukuAvailability.NOT_RUNNING)

    private fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        private const val REQUEST_CODE = 4242
    }
}
