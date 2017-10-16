package com.android.launcher3.nowcompanion

import android.content.*
import android.graphics.Point
import android.os.*
import android.util.Log
import android.view.Window
import android.view.WindowManager
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities


class FlickNowClient(private val launcher: Launcher) : ILauncherClient {
    private val serviceIntent = getServiceIntent()
    private var callbacks = ProxyCallbacks()
    private var proxy: ILauncherClientProxy? = null
    private var destroyed = false
    private val serviceConnectionOptions = 3
    private var serviceConnected = false
    private var activityState: Int = 0
    private var serviceStatus: Int = 0
    private var state: Int = 0
    private var windowAttrs: WindowManager.LayoutParams? = null
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reconnect()
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        filter.addDataScheme("package")
        filter.addDataSchemeSpecificPart(PROXY_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        launcher.registerReceiver(updateReceiver, filter)

        connectProxy()
    }

    private fun connectProxy() {
        sProxyConnection = ProxyServiceConnection(PROXY_PACKAGE)
        connectSafely(launcher.applicationContext, sProxyConnection!!)
    }

    private fun connectSafely(context: Context, conn: ServiceConnection, flags: Int = 0): Boolean {
        try {
            return context.bindService(serviceIntent, conn, flags or Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e("DrawerOverlayClient", "Unable to connect to overlay service")
            return false
        }
    }

    fun reconnect() {
        if (sProxyConnection != null) {
            state = proxy?.reconnect() ?: 0
            if (state == 0) {
                launcher.runOnUiThread { notifyStatusChanged(0) }
            }
        } else {
            connectProxy()
        }
    }

    override fun onStart() {
        if (!destroyed && Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            activityState = activityState or 1
            if (windowAttrs != null) {
                try {
                    proxy?.setActivityState(activityState)
                } catch (e: RemoteException) {
                }

            }
        }
    }

    override fun onStop() {
        if (!destroyed && Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            activityState = activityState and -2
            if (windowAttrs != null) {
                try {
                    proxy?.setActivityState(activityState)
                } catch (e: RemoteException) {
                }

            }
        }
    }

    override fun onPause() {
        if (destroyed || !Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            return
        }
        activityState = activityState and -3
        if (windowAttrs != null) {
            try {
                proxy?.onPause()
            } catch (ignored: RemoteException) {
            }

        }
    }

    override fun onResume() {
        if (destroyed || !Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            return
        }
        activityState = activityState or 2
        reconnect()
        if (windowAttrs != null) {
            try {
                proxy?.onResume()
            } catch (ignored: RemoteException) {
            }

        }
    }

    override fun onDestroy() {
        if(Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            try {
                removeClient(!launcher.isChangingConfigurations)
            }catch(ex: Exception){
                Log.e("FlickNowClient", "onDestroy()");
            }
        }
    }

    override fun onAttachedToWindow() {
        if (!destroyed && Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            setWindowAttrs(launcher.window.attributes)
        }
    }

    override fun onDetachedFromWindow() {
        if (!destroyed && Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            setWindowAttrs(null)
        }
    }

    override fun remove() {
        removeClient(true)
    }

    override fun openOverlay(animate: Boolean) {
        ifConnected { proxy!!.openOverlay(if (animate) 1 else 0) }
    }

    override fun hideOverlay(animate: Boolean) {
        ifConnected { proxy!!.closeOverlay(if (animate) 1 else 0) }
    }

    override fun startMove() {
        ifConnected { proxy!!.startScroll() }
    }

    override fun endMove() {
        ifConnected { proxy!!.endScroll() }
    }

    override fun updateMove(progress: Float) {
        ifConnected { proxy!!.onScroll(progress) }
    }

    private fun setWindowAttrs(windowAttrs: WindowManager.LayoutParams?) {
        this.windowAttrs = windowAttrs
        if (this.windowAttrs != null) {
            applyWindowToken()
        } else if (proxy != null) {
            try {
                proxy!!.windowDetached(launcher.isChangingConfigurations)
            } catch (ignored: RemoteException) {
            }
        }
    }

    private fun applyWindowToken() {
        ifConnected {
            callbacks.setClient(this)
            if (version >= 3) {
                val bundle = Bundle()
                bundle.putParcelable("layout_params", windowAttrs)
                bundle.putParcelable("configuration", launcher.resources.configuration)
                bundle.putInt("client_options", serviceConnectionOptions)
                proxy!!.windowAttached2(bundle)
            } else {
                proxy!!.windowAttached(WindowLayoutParams(windowAttrs!!), serviceConnectionOptions)
            }
            if (version >= 4) {
                proxy!!.setActivityState(activityState)
            } else if (activityState and 2 == 0) {
                proxy!!.onPause()
            } else {
                proxy!!.onResume()
            }
        }
    }

    private fun removeClient(removeAppConnection: Boolean) {
        destroyed = true
        launcher.unregisterReceiver(updateReceiver)

        if (removeAppConnection && sProxyConnection != null) {
            launcher.applicationContext.unbindService(sProxyConnection!!)
            sProxyConnection = null
        }
    }

    private fun notifyStatusChanged(status: Int) {
        if (serviceStatus == status) {
            return
        }

        serviceStatus = status
    }

    private inline fun ifConnected(body: () -> Unit) {
        if (!isConnected || !Utilities.isAllowGoogleNowFeedPrefEnabled(launcher)) {
            return
        }
        try {
            body()
        } catch (ignored: RemoteException) {

        }
    }

    override val isConnected: Boolean
        get() = serviceConnected

    private inner class ProxyCallbacks : ILauncherClientProxyCallback.Stub(), Handler.Callback {
        private var mClient: FlickNowClient? = null
        private val mUIHandler: Handler
        private var mWindow: Window? = null
        private var mWindowHidden: Boolean = false
        private var mWindowManager: WindowManager? = null
        private var mWindowShift: Int = 0

        init {
            mWindowHidden = false
            mUIHandler = Handler(Looper.getMainLooper(), this)
        }

        private fun hideActivityNonUI(isHidden: Boolean) {
            if (mWindowHidden != isHidden) {
                mWindowHidden = isHidden
            }
        }

        fun clear() {
            mClient = null
            mWindowManager = null
            mWindow = null
        }

        override fun handleMessage(msg: Message): Boolean {
            if (mClient == null) {
                return true
            }

            when (msg.what) {
                3 -> {
                    val attrs = mWindow!!.attributes
                    if (msg.obj as Boolean) {
                        attrs.x = mWindowShift
                        attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    }
                    mWindowManager!!.updateViewLayout(mWindow!!.decorView, attrs)
                    return true
                }
                4 -> {
                    mClient!!.notifyStatusChanged(msg.arg1)
                    return true
                }
                else -> return false
            }
        }

        @Throws(RemoteException::class)
        override fun overlayScrollChanged(progress: Float) {
            mUIHandler.removeMessages(2)
            Message.obtain(mUIHandler, 2, progress).sendToTarget()

            if (progress > 0) {
                hideActivityNonUI(false)
            }

            launcher.runOnUiThread { launcher.workspace.onOverlayScrollChanged(progress) }
        }

        override fun overlayStatusChanged(status: Int) {
            Message.obtain(mUIHandler, 4, status, 0).sendToTarget()
        }

        fun setClient(client: FlickNowClient) {
            mClient = client
            mWindowManager = client.launcher.windowManager

            val p = Point()
            mWindowManager!!.defaultDisplay.getRealSize(p)
            mWindowShift = Math.max(p.x, p.y)

            mWindow = client.launcher.window
        }

        override fun onServiceConnected() {
            state = 1
            serviceConnected = true
            if (windowAttrs != null) {
                applyWindowToken()
            }
        }

        override fun onServiceDisconnected() {
            state = 0
            serviceConnected = false
            notifyStatusChanged(0)
        }
    }

    internal inner class ProxyServiceConnection(val packageName: String) : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "connected to proxy service")
            proxy = ILauncherClientProxy.Stub.asInterface(service)
            version = proxy!!.init(callbacks)
            reconnect()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "disconnected from proxy service")
            serviceConnected = false
            if (name.packageName == packageName) {
                sProxyConnection = null
            }
        }
    }

    companion object {
        const val PROXY_PACKAGE = "com.android.launcher3.nowcompanion"
        const val PROXY_SERVICE = ".LauncherClientProxyService"
        const val TAG = "FlickNowClient"

        private var sProxyConnection: ProxyServiceConnection? = null

        private var version = -1

        fun getServiceIntent(): Intent = Intent().apply {
            component = ComponentName(PROXY_PACKAGE, "$PROXY_PACKAGE$PROXY_SERVICE")
            `package` = PROXY_PACKAGE
        }
    }
}
