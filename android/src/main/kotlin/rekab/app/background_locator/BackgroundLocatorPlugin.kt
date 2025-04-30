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
        private const val TAG = "BackgroundLocatorPlugin"
        private var channel: MethodChannel? = null

        private fun sendResultWithDelay(
            ctx: Context,
            result: MethodChannel.Result?,
            ok: Boolean,
            delayMs: Long
        ) {
            Handler(ctx.mainLooper).postDelayed({ result?.success(ok) }, delayMs)
        }

        @SuppressLint("MissingPermission")
        private fun registerLocator(
            ctx: Context,
            args: Map<Any,Any>,
            result: MethodChannel.Result?
        ) {
            if (IsolateHolderService.isServiceRunning) {
                result?.success(true)
                return
            }
            (args[Keys.ARG_CALLBACK] as? Long)?.let {
                PreferencesManager.setCallbackHandle(ctx, Keys.CALLBACK_HANDLE_KEY, it)
            }
            (args[Keys.ARG_NOTIFICATION_CALLBACK] as? Long)?.let {
                PreferencesManager.setCallbackHandle(ctx, Keys.NOTIFICATION_CALLBACK_HANDLE_KEY, it)
            }
            (args[Keys.ARG_INIT_CALLBACK] as? Long)?.let { handle ->
                InitPluggable().apply {
                    setCallback(ctx, handle)
                    (args[Keys.ARG_INIT_DATA_CALLBACK] as? Map<Any,Any>)
                        ?.let { data -> setInitData(ctx, data) }
                }
            }
            (args[Keys.ARG_DISPOSE_CALLBACK] as? Long)?.let {
                DisposePluggable().setCallback(ctx, it)
            }
            val settings = args[Keys.ARG_SETTINGS] as? Map<Any,Any> ?: emptyMap()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                  == PackageManager.PERMISSION_DENIED) {
                result?.error("PERMISSION", "ACCESS_FINE_LOCATION required", null)
                return
            }
            startIsolateService(ctx, settings)
            sendResultWithDelay(ctx, result, true, 1000)
        }

        private fun startIsolateService(
            ctx: Context,
            settings: Map<Any,Any>
        ) {
            val intent = Intent(ctx, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_START
                putExtra(Keys.SETTINGS_INTERVAL,
                         settings[Keys.SETTINGS_INTERVAL] as? Int ?: 0)
                // repeat for other settings, using `as? T ?: default`
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        private fun stopIsolateService(ctx: Context) {
            Intent(ctx, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_SHUTDOWN
            }.also { ContextCompat.startForegroundService(ctx, it) }
        }

        private fun initializeService(ctx: Context, args: Map<Any,Any>) {
            val handle = args[Keys.ARG_CALLBACK_DISPATCHER] as? Long ?: return
            PreferencesManager.setCallbackHandle(ctx,
                 Keys.CALLBACK_DISPATCHER_HANDLE_KEY, handle)
        }

        private fun unRegisterPlugin(
            ctx: Context,
            result: MethodChannel.Result?
        ) {
            if (!IsolateHolderService.isServiceRunning) {
                result?.success(true)
                return
            }
            stopIsolateService(ctx)
            sendResultWithDelay(ctx, result, true, 1000)
        }

        private fun isServiceRunning(result: MethodChannel.Result?) {
            result?.success(IsolateHolderService.isServiceRunning)
        }

        private fun updateNotificationText(
            ctx: Context,
            args: Map<Any,Any>
        ) {
            Intent(ctx, IsolateHolderService::class.java).apply {
                action = IsolateHolderService.ACTION_UPDATE_NOTIFICATION
                (args[Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE] as? String)
                  ?.let { putExtra(Keys.SETTINGS_ANDROID_NOTIFICATION_TITLE, it) }
            }.also { ContextCompat.startForegroundService(ctx, it) }
        }

        @JvmStatic
        fun registerAfterBoot(ctx: Context) {
            val raw = PreferencesManager.getSettings(ctx)
            val args: Map<Any,Any> = raw ?: emptyMap()
            initializeService(ctx, args)
            val settings = args[Keys.ARG_SETTINGS] as? Map<Any,Any> ?: emptyMap()
            startIsolateService(ctx, settings)
        }
    }

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val raw = call.arguments<Map<Any,Any>>()
        val args = raw ?: emptyMap()
        when (call.method) {
            Keys.METHOD_PLUGIN_INITIALIZE_SERVICE -> {
                PreferencesManager.saveCallbackDispatcher(context!!, args)
                initializeService(context!!, args)
                result.success(true)
            }
            Keys.METHOD_PLUGIN_REGISTER_LOCATION_UPDATE -> {
                PreferencesManager.saveSettings(context!!, args)
                registerLocator(context!!, args, result)
            }
            Keys.METHOD_PLUGIN_UN_REGISTER_LOCATION_UPDATE ->
                unRegisterPlugin(context!!, result)
            Keys.METHOD_PLUGIN_IS_REGISTER_LOCATION_UPDATE,
            Keys.METHOD_PLUGIN_IS_SERVICE_RUNNING -> isServiceRunning(result)
            Keys.METHOD_PLUGIN_UPDATE_NOTIFICATION -> {
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
        val cb = PreferencesManager
          .getCallbackHandle(activity!!,
            Keys.NOTIFICATION_CALLBACK_HANDLE_KEY)
          ?: return false
        MethodChannel(
          IsolateHolderService.backgroundEngine!!
            .dartExecutor.binaryMessenger!!,
          Keys.BACKGROUND_CHANNEL_ID
        ).invokeMethod(Keys.BCM_NOTIFICATION_CLICK, cb)
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