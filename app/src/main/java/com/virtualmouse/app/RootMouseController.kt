package com.virtualmouse.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Manages the root uinput + EVIOCGRAB kernel daemon and syncs screen config,
 * floating dock button coordinates, and Trackpad/Touch/Hide states.
 */
object RootMouseController {
    private const val TAG = "RootMouseController"
    private const val TARGET_DAEMON_PATH = "/data/local/tmp/vmouse_daemon"

    private const val CMD_MOVE = 1
    private const val CMD_DOWN = 2
    private const val CMD_UP = 3
    private const val CMD_CLICK = 4
    private const val CMD_SCROLL_V = 5
    private const val CMD_SCROLL_H = 6
    private const val CMD_PING = 7
    private const val CMD_SET_GRAB = 8
    private const val CMD_SET_CONFIG = 9
    private const val CMD_SET_DOCK = 10
    private const val CMD_QUIT = 99

    init {
        try {
            System.loadLibrary("vmouse")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load libvmouse.so", e)
        }
    }

    @JvmStatic
    external fun nativeSendPacket(cmd: Int, arg1: Int, arg2: Int, arg3: Int, arg4: Int): Boolean

    enum class DriverState {
        IDLE,
        CHECKING_ROOT,
        DEPLOYING,
        CONNECTING,
        RUNNING,
        ERROR
    }

    @Volatile private var isRunning = false
    @Volatile private var isStarting = false
    @Volatile private var callbackSocket: DatagramSocket? = null
    private val executor = Executors.newCachedThreadPool()

    var onStateChanged: ((DriverState, String) -> Unit)? = null
    var onDaemonEvent: ((Int) -> Unit)? = null

    fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su -c id")
            val exitCode = p.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    fun isAttached(): Boolean = isRunning

    fun startAsync(context: Context, onResult: (Boolean, String?) -> Unit) {
        if (isRunning && nativeSendPacket(CMD_PING, 0, 0, 0, 0)) {
            notifyState(DriverState.RUNNING, "Virtual mouse active")
            onResult(true, null)
            return
        }

        if (isStarting) return
        isStarting = true

        executor.execute {
            try {
                notifyState(DriverState.CHECKING_ROOT, "Verifying superuser access...")
                if (!isRootAvailable()) {
                    isStarting = false
                    notifyState(DriverState.ERROR, "Root permission not granted")
                    onResult(false, "Root access was denied. Please grant root permission in Magisk/KernelSU/APatch.")
                    return@execute
                }

                notifyState(DriverState.DEPLOYING, "Deploying uinput native driver...")
                val deployedPath = deployDaemonBinary(context)
                if (deployedPath == null) {
                    isStarting = false
                    notifyState(DriverState.ERROR, "Failed to deploy driver executable")
                    onResult(false, "Could not extract or deploy driver binary.")
                    return@execute
                }

                notifyState(DriverState.CONNECTING, "Registering hardware pointer & EVIOCGRAB...")

                val launchCmd = "killall -9 vmouse_daemon 2>/dev/null || true; " +
                        "chmod 755 $TARGET_DAEMON_PATH; " +
                        "chmod 666 /dev/uinput /dev/input/uinput 2>/dev/null || true; " +
                        "nohup $TARGET_DAEMON_PATH >/dev/null 2>&1 &\nexit\n"

                val suProcess = Runtime.getRuntime().exec("su")
                suProcess.outputStream.write(launchCmd.toByteArray())
                suProcess.outputStream.flush()
                suProcess.waitFor()

                var connected = false
                val startTime = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - startTime < 3500L) {
                    if (nativeSendPacket(CMD_PING, 0, 0, 0, 0)) {
                        connected = true
                        break
                    }
                    Thread.sleep(25)
                }

                if (connected) {
                    isRunning = true
                    isStarting = false
                    startCallbackListener()
                    notifyState(DriverState.RUNNING, "Virtual mouse active")
                    onResult(true, null)
                } else {
                    isStarting = false
                    val errMsg = "Daemon socket did not respond within timeout."
                    notifyState(DriverState.ERROR, errMsg)
                    onResult(false, errMsg)
                }
            } catch (e: Exception) {
                isStarting = false
                Log.e(TAG, "Error starting uinput daemon", e)
                notifyState(DriverState.ERROR, e.message ?: "Launch failed")
                onResult(false, e.localizedMessage ?: "Unknown launch error")
                stop()
            }
        }
    }

    private fun startCallbackListener() {
        try {
            callbackSocket?.close()
        } catch (ignored: Exception) {}

        executor.execute {
            try {
                val sock = DatagramSocket(29877, InetAddress.getByName("127.0.0.1"))
                callbackSocket = sock
                val buf = ByteArray(4)
                val pkt = DatagramPacket(buf, buf.size)
                while (isRunning && !sock.isClosed) {
                    sock.receive(pkt)
                    if (pkt.length == 4) {
                        val eventCode = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
                        onDaemonEvent?.invoke(eventCode)
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun notifyState(state: DriverState, message: String) {
        onStateChanged?.invoke(state, message)
    }

    @Synchronized
    fun stop() {
        isStarting = false
        isRunning = false
        try {
            nativeSendPacket(CMD_SET_GRAB, 0, 0, 0, 0)
            nativeSendPacket(CMD_QUIT, 0, 0, 0, 0)
        } catch (ignored: Throwable) {}

        try {
            callbackSocket?.close()
            callbackSocket = null
        } catch (ignored: Exception) {}

        executor.execute {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "killall -9 vmouse_daemon")).waitFor()
            } catch (ignored: Exception) {}
        }

        notifyState(DriverState.IDLE, "Virtual mouse stopped")
        Log.i(TAG, "Root virtual mouse daemon stopped.")
    }

    fun setTouchGrab(enable: Boolean) {
        if (!isRunning) return
        nativeSendPacket(CMD_SET_GRAB, if (enable) 1 else 0, 0, 0, 0)
    }

    fun updateConfig(
        sens: Float,
        scrollSens: Float,
        screenW: Int,
        screenH: Int,
        rotation: Int,
        threeFingerTap: Boolean = true
    ) {
        if (!isRunning) return
        val s1 = (sens * 100f).toInt()
        val s2 = (scrollSens * 100f).toInt()
        val tfFlag = if (threeFingerTap) 1 else 0
        val packedHAndFlags = (screenH and 0xFFFF) or ((rotation and 0x3) shl 16) or (tfFlag shl 20)
        nativeSendPacket(CMD_SET_CONFIG, s1, s2, screenW, packedHAndFlags)
    }

    fun updateDockBounds(left: Int, top: Int, right: Int, bottom: Int) {
        if (!isRunning) return
        nativeSendPacket(CMD_SET_DOCK, left, top, right, bottom)
    }

    fun click(button: Int = 1) {
        if (!isRunning) return
        nativeSendPacket(CMD_CLICK, button, 0, 0, 0)
    }

    fun mouseDown(button: Int = 1) {
        if (!isRunning) return
        nativeSendPacket(CMD_DOWN, button, 0, 0, 0)
    }

    fun mouseUp(button: Int = 1) {
        if (!isRunning) return
        nativeSendPacket(CMD_UP, button, 0, 0, 0)
    }

    private fun deployDaemonBinary(context: Context): String? {
        try {
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            val candidates = listOf(
                File(libDir, "libvmouse_bin.so"),
                File(libDir, "vmouse_daemon")
            )

            var sourceFile: File? = candidates.firstOrNull { it.exists() && it.length() > 0 }

            if (sourceFile == null) {
                val abiList = Build.SUPPORTED_ABIS
                val apkFile = File(context.packageCodePath)
                if (apkFile.exists()) {
                    ZipFile(apkFile).use { zip ->
                        for (abi in abiList) {
                            val entry = zip.getEntry("lib/$abi/libvmouse_bin.so")
                                ?: zip.getEntry("lib/$abi/vmouse_daemon")
                            if (entry != null) {
                                val tempFile = File(context.cacheDir, "vmouse_bin_$abi")
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(tempFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                sourceFile = tempFile
                                break
                            }
                        }
                    }
                }
            }

            if (sourceFile != null && sourceFile!!.exists()) {
                val sourcePath = sourceFile!!.absolutePath
                val suCmd = "cp '$sourcePath' '$TARGET_DAEMON_PATH' && chmod 755 '$TARGET_DAEMON_PATH'\nexit\n"
                val p = Runtime.getRuntime().exec("su")
                p.outputStream.write(suCmd.toByteArray())
                p.outputStream.flush()
                p.waitFor()
                return TARGET_DAEMON_PATH
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy daemon binary", e)
        }
        return null
    }
}
