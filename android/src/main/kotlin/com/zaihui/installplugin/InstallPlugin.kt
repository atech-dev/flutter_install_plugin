package com.zaihui.installplugin

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.lang.ref.WeakReference

class InstallPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var mResult: MethodChannel.Result? = null
    private var context: Context? = null
    private val activity get() = activityReference.get()
    private var activityReference = WeakReference<Activity>(null)
    private var installReceiver: BroadcastReceiver? = null
    private var targetPackage: String? = null
    private var isInstalling = false

    private val REQUEST_CODE_PERMISSION_OR_INSTALL = 1024

    private var apkFilePath = ""
    private var hasPermission = false

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "install_plugin")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
        channel.setMethodCallHandler(null)
        mResult = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityReference = WeakReference(binding.activity)
        binding.addActivityResultListener { requestCode, resultCode, data ->
            return@addActivityResultListener handleActivityResult(requestCode, resultCode, data)
        }
    }

    private fun registerInstallReceiver(packageName: String, version: String) {
        Log.i("InstallPlugin", "Registering install receiver for $packageName version $version")
        Log.i("InstallPlugin", "Current isInstalling flag: $isInstalling")
        unregisterInstallReceiver() // Clear previous receiver if exists
        
        targetPackage = packageName // Save target package
        
        installReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                val receivedPackage = intent?.data?.schemeSpecificPart
                
                Log.i("InstallPlugin", "Broadcast received:")
                Log.i("InstallPlugin", "Action: $action")
                Log.i("InstallPlugin", "Package: $receivedPackage")
                Log.i("InstallPlugin", "Target Package: $targetPackage")
                Log.i("InstallPlugin", "isInstalling: $isInstalling")
                
                if (!isInstalling) {
                    Log.i("InstallPlugin", "Skipping broadcast - not installing")
                    return
                }
                
                if (receivedPackage == targetPackage && receivedPackage != null) {
                    try {
                        val pm = context?.packageManager
                        val info = pm?.getPackageInfo(receivedPackage, 0)
                        Log.i("InstallPlugin", "Installed version: ${info?.versionName}, Expected: $version")
                        
                        if (info?.versionName == version) {
                            Log.i("InstallPlugin", "Installation confirmed successful")
                            isInstalling = false
                            mResult?.success(SaveResultModel(true, "Install Success").toHashMap())
                        } else {
                            Log.w("InstallPlugin", "Version mismatch after install")
                            isInstalling = false
                            mResult?.success(SaveResultModel(false, "Version mismatch").toHashMap())
                        }
                    } catch (e: Exception) {
                        Log.e("InstallPlugin", "Error verifying installation: ${e.message}")
                        isInstalling = false
                        mResult?.success(SaveResultModel(false, "Install verification failed").toHashMap())
                    } finally {
                        unregisterInstallReceiver()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_INSTALL)
            addDataScheme("package")
        }
        
        Log.i("InstallPlugin", "Registering receiver with actions: ${filter.actionsIterator().asSequence().toList()}")
        
        try {
            activity?.applicationContext?.registerReceiver(installReceiver, filter)
            Log.i("InstallPlugin", "Receiver registered successfully")
        } catch (e: Exception) {
            Log.e("InstallPlugin", "Error registering receiver: ${e.message}")
        }
    }

    private fun unregisterInstallReceiver() {
        installReceiver?.let { receiver ->
            try {
                activity?.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e("InstallPlugin", "Error unregistering receiver: ${e.message}")
            }
        }
        installReceiver = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        unregisterInstallReceiver()
        activityReference.clear()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityReference = WeakReference(binding.activity)
        binding.addActivityResultListener { requestCode, resultCode, data ->
            return@addActivityResultListener handleActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDetachedFromActivity() {
        unregisterInstallReceiver()
        activityReference.clear()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        mResult = result
        when (call.method) {
            "installApk" -> {
                val filePath = call.argument<String>("filePath")
                val packageName = call.argument<String>("packageName")
                Log.i("test","onMethodCall:$filePath,$packageName")
                installApk(filePath,packageName)
            }
            else -> result.notImplemented()
        }
    }

    private fun installApk(filePath: String?, packageName: String?) {
        Log.i("InstallPlugin", "Starting APK installation process")
        
        if (filePath.isNullOrEmpty()) {
            Log.e("InstallPlugin", "File path is null or empty")
            mResult?.success(SaveResultModel(false, "FilePath Must Not Null").toHashMap())
            return
        }
        
        apkFilePath = filePath
        val file = File(filePath)
        
        if (!file.exists()) {
            Log.e("InstallPlugin", "APK file does not exist")
            mResult?.success(SaveResultModel(false, "APK file not found").toHashMap())
            return
        }
        
        if (!file.canRead()) {
            Log.e("InstallPlugin", "Cannot read APK file")
            mResult?.success(SaveResultModel(false, "Cannot read APK file").toHashMap())
            return
        }
        
        val currentContext = context
        if (currentContext == null) {
            Log.e("InstallPlugin", "Context is null")
            mResult?.success(SaveResultModel(false, "Context is null").toHashMap())
            return
        }
        
        try {
            if (hasInstallPermission()) {
                Log.i("InstallPlugin", "Has install permission")
                hasPermission = true
                isInstalling = true  // Set installation flag

                // Get APK info
                val packageInfo = context?.packageManager?.getPackageArchiveInfo(filePath, 0)
                val apkPackageName = packageInfo?.packageName
                val version = packageInfo?.versionName
                
                if (apkPackageName != null && version != null) {
                    // Register receiver before starting installation
                    registerInstallReceiver(apkPackageName, version)
                    
                    val uri = InstallFileProvider.getUriForFile(currentContext, file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    }

                    Log.i("InstallPlugin", "Launching install intent:")
                    Log.i("InstallPlugin", "URI: $uri")
                    Log.i("InstallPlugin", "Package: ${currentContext.packageName}")
                    Log.i("InstallPlugin", "File exists: ${File(filePath).exists()}")

                    if (activity != null) {
                        activity?.startActivity(intent)  // Use startActivity instead of startActivityForResult
                        Log.i("InstallPlugin", "Installation intent launched")
                    } else {
                        isInstalling = false
                        Log.e("InstallPlugin", "Activity is null")
                        mResult?.success(SaveResultModel(false, "Activity is null").toHashMap())
                    }
                } else {
                    isInstalling = false
                    Log.e("InstallPlugin", "Invalid APK info")
                    mResult?.success(SaveResultModel(false, "Invalid APK info").toHashMap())
                }
            } else {
                Log.i("InstallPlugin", "Requesting install permission")
                hasPermission = false
                requestInstallPermission(packageName ?: currentContext.packageName)
            }
        } catch (e: Exception) {
            isInstalling = false
            Log.e("InstallPlugin", "Installation error: ${e.message}")
            mResult?.success(SaveResultModel(false, "Installation error: ${e.message}").toHashMap())
        }
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        Log.i("InstallPlugin", "handleActivityResult($requestCode,$resultCode,$data)")
        
        if (requestCode == REQUEST_CODE_PERMISSION_OR_INSTALL) {
            if (!hasPermission) {
                // Handle only permission result
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        Log.i("InstallPlugin", "Permission granted, starting installation")
                        installApk(apkFilePath, "")
                    }
                    else -> {
                        Log.e("InstallPlugin", "Permission denied")
                        mResult?.success(SaveResultModel(false, "Permission denied").toHashMap())
                    }
                }
            }
            // Ignore activity result during installation, wait for BroadcastReceiver
            return true
        }
        return false
    }

    private fun hasInstallPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context?.packageManager?.canRequestPackageInstalls() ?: false
        } else {
            return true
        }
    }

    private fun requestInstallPermission(packageName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = Uri.parse("package:$packageName")
            activity?.startActivityForResult(intent, REQUEST_CODE_PERMISSION_OR_INSTALL)
        }
    }
}

class SaveResultModel(
    private var isSuccess: Boolean,
    private var errorMessage: String? = null) {
    fun toHashMap(): HashMap<String, Any?> {
        val hashMap = HashMap<String, Any?>()
        hashMap["isSuccess"] = isSuccess
        hashMap["errorMessage"] = errorMessage
        return hashMap
    }
}

class InstallFileProvider : FileProvider() {
    companion object {
        fun getUriForFile(context: Context, file: File): Uri {
            val authority = "${context.packageName}.installFileProvider.install"
            return getUriForFile(context, authority, file)
        }
    }
}