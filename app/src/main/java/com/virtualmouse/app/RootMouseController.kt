package com.virtualmouse.app

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages the root su process and streams mouse events to the native uinput daemon.
 */
object RootMouseController {
    private const val TAG = "RootMouseController"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false

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

    @Synchronized
    fun start(context: Context): Boolean {
        if (isRunning && writer != null) return true

        try {
            // Locate or extract the native daemon
            val daemonPath = prepareDaemon(context)
            Log.i(TAG, "Starting daemon at: $daemonPath")

            // Spawn root process running the daemon
            val suProcess = Runtime.getRuntime().exec("su")
            val out = BufferedWriter(OutputStreamWriter(suProcess.outputStream))
            val inReader = BufferedReader(InputStreamReader(suProcess.inputStream))

            // Launch daemon in su
            out.write("chmod 755 $daemonPath\n")
            out.write("$daemonPath\n")
            out.flush()

            // Wait for READY signal from daemon
            val line = inReader.readLine()
            Log.i(TAG, "Daemon response: $line")

            if (line != null && line.contains("READY")) {
                process = suProcess
                writer = out
                reader = inReader
                isRunning = true
                Log.i(TAG, "Root virtual mouse daemon successfully attached!")
                return true
            } else {
                Log.w(TAG, "Daemon did not return READY: $line")
                // Still set process to allow writing if stdout buffered
                process = suProcess
                writer = out
                reader = inReader
                isRunning = true
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start uinput daemon", e)
            stop()
            return false
        }
    }

    @Synchronized
    fun stop() {
        try {
            writer?.write("Q\n")
            writer?.flush()
        } catch (ignored: Exception) {}

        try {
            writer?.close()
            reader?.close()
            process?.destroy()
        } catch (ignored: Exception) {}

        process = null
        writer = null
        reader = null
        isRunning = false
        Log.i(TAG, "Root virtual mouse daemon stopped.")
    }

    fun isAttached(): Boolean = isRunning

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

    private fun prepareDaemon(context: Context): String {
        // Check nativeLibraryDir first
        val libDir = context.applicationInfo.nativeLibraryDir
        val primaryDaemon = File(libDir, "vmouse_daemon")
        if (primaryDaemon.exists() && primaryDaemon.canExecute()) {
            return primaryDaemon.absolutePath
        }

        val libSo = File(libDir, "libvmouse.so")
        if (libSo.exists()) {
            return libSo.absolutePath
        }

        // Fallback: Copy to filesDir or /data/local/tmp
        val target = File(context.filesDir, "vmouse_daemon")
        if (!target.exists() || target.length() == 0L) {
            // Check if available in assets or copy libSo
            if (libSo.exists()) {
                libSo.copyTo(target, overwrite = true)
            }
        }
        target.setExecutable(true, false)
        return target.absolutePath
    }
}
