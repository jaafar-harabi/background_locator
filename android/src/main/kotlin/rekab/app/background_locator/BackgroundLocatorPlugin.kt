package rekab.app.background_locator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import rekab.app.background_locator.pluggables.DisposePluggable
import rekab.app.background_locator.pluggables.InitPluggable

class BackgroundLocatorPlugin
    : MethodChannel.MethodCallHandler,
      FlutterPlugin,
      PluginRegistry.NewIntentListener,
      ActivityAware {

    private var context: Context? = null
    private var activity: Activity? = null

    companion object {
        @JvmStatic
        private var channel: MethodChannel? = null

        private const val TAG = "BackgroundLocatorPlugin"

        @JvmStatic
        private fun sendResultWithDelay(
            context: Context,
            result: MethodChannel.Result?,
            value: Boolean,
            delay: Long
        ) {
            Handler(context.mainLooper).postDelayed({
                result?.success(value)
            }, delay)
        }

        @SuppressLint("MissingPermission")
        @JvmStatic
        private fun registerLocator(
            context: Context,
            args: Map<Any, Any>,
            result: MethodChannel.Result?
        ) {
            if (IsolateHolderService.isServiceRunning) {
                Log.d(TAG, "Locator service is already running")
                result?.success(true)
                return
            }

            Log.d(TAG, "Start locator with ${PreferencesManager.getLocationClient(context)} client")

            // Required callbacks
            val callbackHandle = args[Keys.ARG_CALLBACK] as Long
            PreferencesManager.setCallbackHandle(context, Keys.CALLBACK_HANDLE_KEY, callbackHandle)

            (args[Keys.ARG_NOTIFICATION_CALLBACK] as? Long)?.let {
                PreferencesManager.setCallbackHandle(context, Keys.NOTIFICATION_CALLBACK_HANDLE_KEY, it)
            }

            (args[Keys.ARG_INIT_CALLBACK] as? Long)?.let { initCallbackHandle ->
                val initPluggable = InitPluggable()
                initPluggable.setCallback(context, initCallbackHandle)
                (args[Keys.ARG_INIT_DATA_CALLBACK] as? Map<Any, Any>)?.let { initData ->
                    initPluggable.setInitData(context, initData)
                }
            }

            (args[Keys.ARG_DISPOSE_CALLBACK] as? Long)?.let {
                val disposePluggable = DisposePluggable()
                disposePluggable.setCallback(context, it)
            }

            val settings = args[Keys.ARG_SETTINGS] as? Map<Any, Any> ?: emptyMap()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED
            ) {
                result?.error(
                    "'registerLocator' requires the ACCESS_FINE_LOCATION permission.",
                    null,
                    null
                )
                return
            }

            startIsolateService(context, settings)
            sendResultWithDelay(context, result, true, 1000)
        }

        @JvmStatic
        private fun startIsolateService(
            context: Context,
            settings: Map<Any, Any>
        ) {
            val intent = Intent(context, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_START
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_CHANNEL_NAME] as? String ?: ""
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] as? String ?: ""
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_MSG,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG] as? String ?: ""
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG] as? String ?: ""
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_ICON,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_ICON] as? String ?: ""
                )
                putExtra(
                    Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR,
                    settings[Keys.SETTINGS_ANDROID_NOTIFICATION_ICON_COLOR] as? Long ?: 0L
                )
                putExtra(
                    Keys.SETTINGS_INTERVAL,
                    settings[Keys.SETTINGS_INTERVAL] as? Int ?: 0
                )
                putExtra(
                    Keys.SETTINGS_ACCURACY,
                    settings[Keys.SETTINGS_ACCURACY] as? Int ?: 0
                )
                putExtra(
                    Keys.SETTINGS_DISTANCE_FILTER,
                    settings[Keys.SETTINGS_DISTANCE_FILTER] as? Double ?: 0.0
                )

                (settings[Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME] as? Int)?.let {
                    putExtra(Keys.SETTINGS_ANDROID_WAKE_LOCK_TIME, it)
                }

                if (PreferencesManager.getCallbackHandle(context, Keys.INIT_CALLBACK_HANDLE_KEY) != null) {
                    putExtra(Keys.SETTINGS_INIT_PLUGGABLE, true)
                }
                if (PreferencesManager.getCallbackHandle(context, Keys.DISPOSE_CALLBACK_HANDLE_KEY) != null) {
                    putExtra(Keys.SETTINGS_DISPOSABLE_PLUGGABLE, true)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        private fun stopIsolateService(context: Context) {
            Intent(context, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_SHUTDOWN
            }.also {
                ContextCompat.startForegroundService(context, it)
            }
        }

        @JvmStatic
        private fun initializeService(
            context: Context,
            args: Map<Any, Any>
        ) {
            val callbackHandle = args[Keys.ARG_CALLBACK_DISPATCHER] as Long
            setCallbackDispatcherHandle(context, callbackHandle)
        }

        @JvmStatic
        private fun unRegisterPlugin(
            context: Context,
            result: MethodChannel.Result?
        ) {
            if (!IsolateHolderService.isServiceRunning) {
                Log.d(TAG, "Locator service is not running, nothing to stop")
                result?.success(true)
                return
            }
            stopIsolateService(context)
            sendResultWithDelay(context, result, true, 1000)
        }

        @JvmStatic
        private fun isServiceRunning(result: MethodChannel.Result?) {
            result?.success(IsolateHolderService.isServiceRunning)
        }

        @JvmStatic
        private fun updateNotificationText(
            context: Context,
            args: Map<Any, Any>
        ) {
            Intent(context, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_UPDATE_NOTIFICATION
                if (args.containsKey(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE)) {
                    putExtra(
                        Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE,
                        args[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] as? String ?: ""
                    )
                }
                if (args.containsKey(Keys.SETTINGS_ANDROID_NOTIFICATION_MSG)) {
                    putExtra(
                        Keys.SETTINGS_ANDROID_NOTIFICATION_MSG,
                        args[Keys.SETTINGS_ANDROID_NOTIFICATION_MSG] as? String ?: ""
                    )
                }
                if (args.containsKey(Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG)) {
                    putExtra(
                        Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG,
                        args[Keys.SETTINGS_ANDROID_NOTIFICATION_BIG_MSG] as? String ?: ""
                    )
                }
            }.also {
                ContextCompat.startForegroundService(context, it)
            }
        }

        @JvmStatic
        private fun setCallbackDispatcherHandle(
            context: Context,
            handle: Long
        ) {
            context.getSharedPreferences(Keys.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .edit()
                .putLong(Keys.CALLBACK_DISPATCHER_HANDLE_KEY, handle)
                .apply()
        }

        @JvmStatic
        fun registerAfterBoot(context: Context) {
            val raw = PreferencesManager.getSettings(context)
            val args: Map<Any, Any> = raw ?: emptyMap()
            initializeService(context, args)
            val settings = args[Keys.ARG_SETTINGS] as? Map<Any, Any> ?: emptyMap()
            startIsolateService(context, settings)
        }
    }

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        when (call.method) {
            Keys.METHOD_PLUGIN_INITIALIZE_SERVICE -> {
                val raw = call.arguments<Map<Any, Any>>()
                val args = raw ?: emptyMap()
                PreferencesManager.saveCallbackDispatcher(context!!, args)
                initializeService(context!!, args)
                result.success(true)
            }
            Keys.METHOD_PLUGIN_REGISTER_LOCATION_UPDATE -> {
                val raw = call.arguments<Map<Any, Any>>()
                val args = raw ?: emptyMap()
                PreferencesManager.saveSettings(context!!, args)
                registerLocator(context!!, args, result)
            }
            Keys.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE -> {
                unRegisterPlugin(context!!, result)
            }
            Keys.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE,
            Keys.METHOD_PLUGIN_IS_SERVICE_RUNNING -> {
                isServiceRunning(result)
            }
            Keys.METHOD_PLUGIN_UPDATE_NOTIFICATION -> {
                if (!IsolateHolderService.isServiceRunning) return
                val raw = call.arguments<Map<Any, Any>>()
                val args = raw ?: emptyMap()
                updateNotificationText(context!!, args)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger!!, Keys.CHANNEL_ID)
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onNewIntent(intent: Intent): Boolean {
        if (intent.action != Keys.NOTIFICATION_ACTION) return false
        val callback = PreferencesManager.getCallbackHandle(
            activity!!,
            Keys.NOTIFICATION_CALLBACK_HANDLE_KEY
        )
        if (callback != null && IsolateHolderService.backgroundEngine != null) {
            val channel = MethodChannel(
                IsolateHolderService.backgroundEngine!!.dartExecutor.binaryMessenger!!,
                Keys.BACKGROUND_CHANNEL_ID
            )
            Handler(activity!!.mainLooper).post {
                channel.invokeMethod(
                    Keys.BCM_NOTIFICATION_CLICK,
                    hashMapOf(Keys.ARG_NOTIFICATION_CALLBACK to callback)
                )
            }
        }
        return true
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}
    override fun onDetachedFromActivityForConfigChanges() {}
}