package com.jarves.mh.control

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * The concrete operations behind the control HTTP endpoints. Keeping them in a
 * dedicated object keeps [PhoneControlServer] small and lets tests exercise the
 * logic without an HTTP round trip.
 */
object PhoneControlActions {

    fun health(context: Context, port: Int): JSONObject = JSONObject()
        .put("ok", true)
        .put("app", "Mobile Harness")
        .put("port", port)
        .put("accessibility", PhoneControlService.isConnected())
        .put("api", 1)

    // ---- screen ---------------------------------------------------------

    fun uiTree(): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")
        val tree = service.uiTree() ?: return err("no window content available")
        return JSONObject().put("ok", true).put("tree", tree)
    }

    fun tap(text: String?, resourceId: String?, x: Int?, y: Int?): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")

        if (!text.isNullOrBlank() || !resourceId.isNullOrBlank()) {
            val node = service.findNode(text, resourceId)
                ?: return err("no visible element matches the lookup criteria")
            return if (service.click(node)) {
                JSONObject().put("ok", true).put("method", "node")
            } else {
                err("element found but it could not be activated")
            }
        }

        if (x == null || y == null) return err("provide x,y coordinates or text/resourceId")
        return if (service.tap(x, y)) {
            JSONObject().put("ok", true).put("method", "gesture")
        } else {
            err("gesture was rejected by the system")
        }
    }

    fun longPress(x: Int, y: Int, durationMs: Long): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")
        return if (service.longPress(x, y, durationMs)) JSONObject().put("ok", true)
        else err("gesture was rejected by the system")
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")
        return if (service.swipe(x1, y1, x2, y2, durationMs)) JSONObject().put("ok", true)
        else err("gesture was rejected by the system")
    }

    fun type(text: String): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")
        if (text.isBlank()) return err("text must not be empty")
        return if (service.typeText(text)) JSONObject().put("ok", true)
        else err("no editable field is focused on the screen")
    }

    fun key(name: String): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")
        return if (service.globalKey(name)) JSONObject().put("ok", true)
        else err("unknown or unavailable key: $name (home|back|recents|notifications|quick_settings|power_dialog|lock_screen|screenshot)")
    }

    fun screenshot(): JSONObject {
        val service = requireService() ?: return err("accessibility service is not enabled")
        val png = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.screenshotPng()
        } else {
            null
        } ?: return err("screenshot unavailable (requires Android 11+; use /ui as fallback)")
        return JSONObject()
            .put("ok", true)
            .put("mime", "image/png")
            .put("base64", PhoneControlService.encodeBase64(png))
    }

    // ---- apps -----------------------------------------------------------

    fun openApp(context: Context, packageName: String): JSONObject {
        if (packageName.isBlank()) return err("package name must not be empty")
        return runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName.trim())
                ?: return err("no launcher intent for '$packageName'; is it installed?")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            JSONObject().put("ok", true).put("launched", packageName.trim())
        }.getOrElse { err(it.message ?: "could not launch app") }
    }

    fun apps(context: Context, limit: Int = 250): JSONObject {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(launcher, 0) }
            .getOrDefault(emptyList())
        val list = JSONArray()
        resolved
            .asSequence()
            .mapNotNull { info ->
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                JSONObject().put("package", pkg).put("label", label ?: pkg)
            }
            .distinctBy { it.getString("package") }
            .sortedBy { it.optString("label").lowercase() }
            .take(limit)
            .forEach { list.put(it) }
        return JSONObject().put("ok", true).put("apps", list)
    }

    // ---- shell ----------------------------------------------------------

    /**
     * Runs a command as the app's own UID via ProcessBuilder. This is the same
     * level of privilege the app itself has: it cannot modify system settings
     * or touch other apps' private data, but it CAN run am/dumpsys/packages
     * helpers and anything else visible to the app's sandbox.
     */
    fun shell(args: List<String>, timeoutMs: Long): JSONObject {
        if (args.isEmpty()) return err("provide args, e.g. {\"args\":[\"am\",\"start\",\"-n\",\"com.android.settings/.Settings\"]}")
        return runCatching {
            val process = ProcessBuilder(args).start()
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val outPump = pump(process.inputStream, stdout)
            val errPump = pump(process.errorStream, stderr)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return err("command timed out after ${timeoutMs}ms")
            }
            // Drain the pump threads before reading the buffers, otherwise the
            // tail of the output can be lost while the process has already exited.
            outPump.join()
            errPump.join()
            JSONObject()
                .put("ok", true)
                .put("exit", process.exitValue())
                .put("stdout", String(stdout.toByteArray(), Charsets.UTF_8))
                .put("stderr", String(stderr.toByteArray(), Charsets.UTF_8))
        }.getOrElse { err(it.message ?: "shell failed") }
    }

    private fun pump(source: java.io.InputStream, target: ByteArrayOutputStream): Thread {
        val thread = Thread {
            runCatching { source.copyTo(target) }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    // ---- helpers --------------------------------------------------------

    private fun requireService() = PhoneControlService.instance

    private fun err(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    /** Convenience for the settings screen: visual echo that a tap happened. */
    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}