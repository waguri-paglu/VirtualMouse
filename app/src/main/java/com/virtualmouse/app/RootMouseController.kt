package com.virtualmouse.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Manages the root su process and streams mouse events to the native uinput daemon.
 * Uses a CachedThreadPool so concurrent tasks never deadlock.
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

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
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

    /**
     * Asynchronously starts the daemon on a background thread pool.
     */
    fun startAsync(context: Context, onResult: (Boolean, String?) -> Unit) {
        if (isRunning && writer != null) {
            notifyState(DriverState.RUNNING, "Virtual mouse active")
            onResult(true, null)
            return
        }

        if (isStarting) {
            return
        }
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

                // Kill any stale vmouse_daemon instances first
                val suProcess = Runtime.getRuntime().exec("su")
                val out = BufferedWriter(OutputStreamWriter(suProcess.outputStream))
                val inStream = suProcess.inputStream
                val inReader = BufferedReader(InputStreamReader(inStream))

                out.write("killall -9 vmouse_daemon 2>/dev/null || true\n")
                out.write("chmod 755 $TARGET_DAEMON_PATH\n")
                out.write("chmod 666 /dev/uinput 2>/dev/null || true\n")
                out.write("chmod 666 /dev/input/uinput 2>/dev/null || true\n")
                out.write("exec $TARGET_DAEMON_PATH\n")
                out.flush()

                // Poll for READY signal with a 4-second non-blocking loop
                var line: String? = null
                val startTime = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - startTime < 4000L) {
                    if (inStream.available() > 0) {
                        line = inReader.readLine()
                        if (line != null) break
                    }
                    Thread.sleep(40)
                }

                Log.i(TAG, "Daemon response: $line")

                if (line != null && line.contains("READY")) {
                    process = suProcess
                    writer = out
                    reader = inReader
                    isRunning = true
                    isStarting = false
                    // Send an initial nudge to make sure cursor is visible
                    sendCommand("M 2 2")
                    notifyState(DriverState.RUNNING, "Virtual mouse active")
                    onResult(true, null)
                } else if (line != null && line.contains("ERROR")) {
                    isStarting = false
                    val errMsg = "Kernel driver error: $line"
                    notifyState(DriverState.ERROR, errMsg)
                    onResult(false, errMsg)
                    suProcess.destroy()
                } else {
                    // Even if stdout was swallowed by su wrapper, check if process is alive!
                    var alive = true
                    try {
                        suProcess.exitValue()
                        alive = false
                    } catch (e: IllegalThreadStateException) {
                        alive = true
                    }

                    if (alive) {
                        Log.i(TAG, "Daemon process is alive; assuming READY")
                        process = suProcess
                        writer = out
                        reader = inReader
                        isRunning = true
                        isStarting = false
                        sendCommand("M 2 2")
                        notifyState(DriverState.RUNNING, "Virtual mouse active")
                        onResult(true, null)
                    } else {
                        isStarting = false
                        val msg = "Daemon exited prematurely during uinput registration."
                        notifyState(DriverState.ERROR, msg)
                        onResult(false, msg)
                        suProcess.destroy()
                    }
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
        try {
            writer?.write("Q\n")
            writer?.flush()
        } catch (ignored: Exception) {}

        try {
            writer?.close()
            reader?.close()
            process?.destroy()
        } catch (ignored: Exception) {}

        executor.execute {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "killall -9 vmouse_daemon")).waitFor()
            } catch (ignored: Exception) {}
        }

        process = null
        writer = null
        reader = null
        isRunning = false
        notifyState(DriverState.IDLE, "Virtual mouse stopped")
        Log.i(TAG, "Root virtual mouse daemon stopped.")
    }

    fun move(dx: Int, dy: Int) {
        sendCommand("M $dx $dy")
    }

    fun mouseDown(button: Int = 1) {
        sendCommand("D $button")
    }

    fun mouseUp(button: Int = 1) {
        sendCommand("U $button")
    }

    fun click(button: Int = 1) {
        sendCommand("C $button")
    }

    fun scroll(dy: Int) {
        sendCommand("W $dy")
    }

    fun scrollHorizontal(dx: Int) {
        sendCommand("H $dx")
    }

    private fun sendCommand(cmd: String) {
        if (!isRunning || writer == null) return
        try {
            writer?.write("$cmd\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing command: $cmd", e)
        }
    }

    private fun deployDaemonBinary(context: Context): String? {
        try {
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            val candidates = listOf(
                File(libDir, "libvmouse_bin.so"),
                File(libDir, "vmouse_daemon"),
                File(libDir, "libvmouse.so")
            )

            var sourceFile: File? = candidates.firstOrNull { it.exists() && it.length() > 0 }

            if (sourceFile == null) {
                val abiList = Build.SUPPORTED_ABIS
                val apkFile = File(context.packageCodePath)
                if (apkFile.exists()) {
                    ZipFile(apkFile).use { zip ->
                        for (abi in abiList) {
                            val entry = zip.getEntry("lib/$abi/libvmouse_bin.so")
                                ?: zip.getEntry("lib/$abi/libvmouse.so")
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
