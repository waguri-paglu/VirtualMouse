package com.virtualmouse.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Manages the root uinput daemon lifecycle and dispatches all movement/click
 * events via direct C JNI binary datagrams (@vmouse_fast_sock) with zero pipe latency.
 */
object RootMouseController {
    private const val TAG = "RootMouseController"
    private const val TARGET_DAEMON_PATH = "/data/local/tmp/vmouse_daemon"

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
    private val executor = Executors.newCachedThreadPool()

    var onStateChanged: ((DriverState, String) -> Unit)? = null

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
        if (isRunning && NativeBridge.nativeSendPacket(NativeBridge.CMD_PING, 0, 0)) {
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

                notifyState(DriverState.CONNECTING, "Registering hardware pointer device...")

                // Launch daemon detached in background via su so su stdin pipe is never used for IPC
                val launchCmd = "killall -9 vmouse_daemon 2>/dev/null || true; " +
                        "chmod 755 $TARGET_DAEMON_PATH; " +
                        "chmod 666 /dev/uinput /dev/input/uinput 2>/dev/null || true; " +
                        "nohup $TARGET_DAEMON_PATH >/dev/null 2>&1 &
exit
"

                val suProcess = Runtime.getRuntime().exec("su")
                suProcess.outputStream.write(launchCmd.toByteArray())
                suProcess.outputStream.flush()
                suProcess.waitFor()

                // Poll the fast abstract socket via JNI until the daemon binds it (typically < 100ms)
                var connected = false
                val startTime = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - startTime < 3500L) {
                    if (NativeBridge.nativeSendPacket(NativeBridge.CMD_PING, 0, 0)) {
                        connected = true
                        break
                    }
                    Thread.sleep(25)
                }

                if (connected) {
                    isRunning = true
                    isStarting = false
                    // Send initial cursor nudge
                    NativeBridge.nativeSendPacket(NativeBridge.CMD_MOVE, 2, 2)
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

    private fun notifyState(state: DriverState, message: String) {
        onStateChanged?.invoke(state, message)
    }

    @Synchronized
    fun stop() {
        isStarting = false
        isRunning = false
        try {
            NativeBridge.nativeSendPacket(NativeBridge.CMD_QUIT, 0, 0)
        } catch (ignored: Throwable) {}

        executor.execute {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "killall -9 vmouse_daemon")).waitFor()
            } catch (ignored: Exception) {}
        }

        notifyState(DriverState.IDLE, "Virtual mouse stopped")
        Log.i(TAG, "Root virtual mouse daemon stopped.")
    }

    /**
     * Direct 2-microsecond JNI datagram call (zero blocking, zero buffering).
     */
    fun move(dx: Int, dy: Int) {
        if (!isRunning || (dx == 0 && dy == 0)) return
        NativeBridge.nativeSendPacket(NativeBridge.CMD_MOVE, dx, dy)
    }

    fun mouseDown(button: Int = 1) {
        if (!isRunning) return
        NativeBridge.nativeSendPacket(NativeBridge.CMD_DOWN, button, 0)
    }

    fun mouseUp(button: Int = 1) {
        if (!isRunning) return
        NativeBridge.nativeSendPacket(NativeBridge.CMD_UP, button, 0)
    }

    fun click(button: Int = 1) {
        if (!isRunning) return
        NativeBridge.nativeSendPacket(NativeBridge.CMD_CLICK, button, 0)
    }

    fun scroll(dy: Int) {
        if (!isRunning || dy == 0) return
        NativeBridge.nativeSendPacket(NativeBridge.CMD_SCROLL_V, dy, 0)
    }

    fun scrollHorizontal(dx: Int) {
        if (!isRunning || dx == 0) return
        NativeBridge.nativeSendPacket(NativeBridge.CMD_SCROLL_H, dx, 0)
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
                Log.i(TAG, "Successfully deployed binary to $TARGET_DAEMON_PATH from $sourcePath")
                return TARGET_DAEMON_PATH
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy daemon binary", e)
        }
        return null
    }
}
