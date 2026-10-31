package com.virtualmouse.app

import android.content.Context
import android.os.Build
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile

/**
 * Ultra-low-latency controller for the native uinput daemon.
 * Coalesces high-frequency touch deltas atomically so the UI thread never blocks
 * and the IPC pipe never builds up a laggy event queue.
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

    // Atomic accumulators for lock-free event coalescing
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)
    private val pendingScrollY = AtomicInteger(0)
    private val pendingScrollX = AtomicInteger(0)
    private val discreteCommandQueue = ConcurrentLinkedQueue<String>()
    private val hasPendingWork = AtomicBoolean(false)

    @Volatile private var dispatcherThread: Thread? = null
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
        if (isRunning && writer != null) {
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

                val suProcess = Runtime.getRuntime().exec("su")
                // Small 512-byte buffer for low-overhead flushing
                val out = BufferedWriter(OutputStreamWriter(suProcess.outputStream), 512)
                val inStream = suProcess.inputStream
                val inReader = BufferedReader(InputStreamReader(inStream), 256)

                out.write("killall -9 vmouse_daemon 2>/dev/null || true\n")
                out.write("chmod 755 $TARGET_DAEMON_PATH\n")
                out.write("chmod 666 /dev/uinput 2>/dev/null || true\n")
                out.write("chmod 666 /dev/input/uinput 2>/dev/null || true\n")
                out.write("exec $TARGET_DAEMON_PATH\n")
                out.flush()

                var line: String? = null
                val startTime = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - startTime < 4000L) {
                    if (inStream.available() > 0) {
                        line = inReader.readLine()
                        if (line != null) break
                    }
                    Thread.sleep(25)
                }

                Log.i(TAG, "Daemon response: $line")

                val startedOk = if (line != null && line.contains("READY")) {
                    true
                } else if (line != null && line.contains("ERROR")) {
                    false
                } else {
                    // Check if process is alive
                    try {
                        suProcess.exitValue()
                        false
                    } catch (e: IllegalThreadStateException) {
                        true
                    }
                }

                if (startedOk) {
                    process = suProcess
                    writer = out
                    reader = inReader
                    isRunning = true
                    isStarting = false
                    startDispatcherThread()
                    move(2, 2)
                    notifyState(DriverState.RUNNING, "Virtual mouse active")
                    onResult(true, null)
                } else {
                    isStarting = false
                    val errMsg = line ?: "Daemon exited prematurely during uinput registration."
                    notifyState(DriverState.ERROR, errMsg)
                    onResult(false, errMsg)
                    suProcess.destroy()
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

    /**
     * Dedicated high-priority thread that drains coalesced movement deltas
     * and discrete button events without blocking Android's UI thread.
     */
    private fun startDispatcherThread() {
        dispatcherThread?.interrupt()
        pendingDx.set(0)
        pendingDy.set(0)
        pendingScrollY.set(0)
        pendingScrollX.set(0)
        discreteCommandQueue.clear()

        val thread = Thread({
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_DISPLAY)
            val sb = StringBuilder(64)

            while (isRunning && !Thread.currentThread().isInterrupted) {
                if (!hasPendingWork.compareAndSet(true, false)) {
                    LockSupport.parkNanos(50_000_000L) // Park up to 50ms until unparked
                    continue
                }

                val out = writer ?: break
                var wroteAnything = false
                sb.setLength(0)

                // 1. Coalesce all accumulated movement deltas into a single kernel frame
                val dx = pendingDx.getAndSet(0)
                val dy = pendingDy.getAndSet(0)
                if (dx != 0 || dy != 0) {
                    sb.append("M ").append(dx).append(' ').append(dy).append('\n')
                    wroteAnything = true
                }

                // 2. Coalesce vertical & horizontal scroll deltas
                val sy = pendingScrollY.getAndSet(0)
                if (sy != 0) {
                    sb.append("W ").append(sy).append('\n')
                    wroteAnything = true
                }
                val sx = pendingScrollX.getAndSet(0)
                if (sx != 0) {
                    sb.append("H ").append(sx).append('\n')
                    wroteAnything = true
                }

                // 3. Drain discrete button clicks/presses in exact order
                while (true) {
                    val cmd = discreteCommandQueue.poll() ?: break
                    sb.append(cmd).append('\n')
                    wroteAnything = true
                }

                if (wroteAnything) {
                    try {
                        out.write(sb.toString())
                        out.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Dispatcher write failed", e)
                        break
                    }
                }
            }
        }, "VMouse-Dispatcher")

        thread.isDaemon = true
        dispatcherThread = thread
        thread.start()
    }

    private fun wakeDispatcher() {
        hasPendingWork.set(true)
        dispatcherThread?.let { LockSupport.unpark(it) }
    }

    private fun notifyState(state: DriverState, message: String) {
        onStateChanged?.invoke(state, message)
    }

    @Synchronized
    fun stop() {
        isStarting = false
        isRunning = false
        dispatcherThread?.interrupt()
        dispatcherThread = null

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
        notifyState(DriverState.IDLE, "Virtual mouse stopped")
        Log.i(TAG, "Root virtual mouse daemon stopped.")
    }

    /**
     * Lock-free, non-blocking movement accumulation called from the UI thread.
     */
    fun move(dx: Int, dy: Int) {
        if (!isRunning || (dx == 0 && dy == 0)) return
        pendingDx.addAndGet(dx)
        pendingDy.addAndGet(dy)
        wakeDispatcher()
    }

    fun mouseDown(button: Int = 1) {
        if (!isRunning) return
        discreteCommandQueue.offer("D $button")
        wakeDispatcher()
    }

    fun mouseUp(button: Int = 1) {
        if (!isRunning) return
        discreteCommandQueue.offer("U $button")
        wakeDispatcher()
    }

    fun click(button: Int = 1) {
        if (!isRunning) return
        discreteCommandQueue.offer("C $button")
        wakeDispatcher()
    }

    fun scroll(dy: Int) {
        if (!isRunning || dy == 0) return
        pendingScrollY.addAndGet(dy)
        wakeDispatcher()
    }

    fun scrollHorizontal(dx: Int) {
        if (!isRunning || dx == 0) return
        pendingScrollX.addAndGet(dx)
        wakeDispatcher()
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
