package com.s17labs.opencodelauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.s17labs.opencodelauncher.MainActivity
import com.s17labs.opencodelauncher.runtime.GuestSetup
import com.s17labs.opencodelauncher.runtime.ProotRunner
import com.s17labs.opencodelauncher.runtime.ProotSetup
import com.s17labs.opencodelauncher.runtime.RuntimeBootstrap
import com.s17labs.opencodelauncher.storage.ProjectMount
import com.s17labs.opencodelauncher.storage.ProjectStore
import com.s17labs.opencodelauncher.storage.StorageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 3: owns the `opencode web` guest process lifecycle.
 *
 * - Starts `opencode web --hostname 127.0.0.1 --port 4096` via proot exec
 *   (fixed port per plan decision; stdout is still parsed for the real URL
 *   in case the server reports a different one).
 * - Persistent notification with a Stop action; tap returns to the app.
 * - Restarts on unexpected death with backoff; gives up after 5 rapid deaths
 *   (<15s uptime each) and reports Failed instead of hot-looping.
 * - Never auto-restarts after user-initiated stop.
 * - Publishes state to [OpenCodeStatus] (same process) for the Compose UI.
 */
class OpenCodeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "opencode_launcher_status"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.s17labs.opencodelauncher.action.START"
        const val ACTION_STOP = "com.s17labs.opencodelauncher.action.STOP"
        const val PORT = 4096
        const val HOST = "127.0.0.1"

        private val URL_RE = Regex("""https?://[^\s'"]+""")

        fun startIntent(ctx: Context): Intent =
            Intent(ctx, OpenCodeForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, OpenCodeForegroundService::class.java).setAction(ACTION_STOP)

        /** Prefer loopback URLs from server output; fall back to any URL line. */
        fun pickUrl(line: String): String? {
            val all = URL_RE.findAll(line)
                .map { it.value.trimEnd('.', ',', ')', ';') }
                .toList()
            return all.firstOrNull { it.contains("127.0.0.1") || it.contains("localhost") }
                ?: all.firstOrNull()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supervisor: Job? = null

    @Volatile private var userStopped = false
    @Volatile private var proc: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        rotateLogIfHuge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(userInitiated = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (supervisor?.isActive == true) return START_STICKY
                userStopped = false
                startForeground(NOTIF_ID, buildNotification("Starting OpenCode…"))
                OpenCodeStatus.set(OpenCodeStatus.Value.Starting)
                supervisor = scope.launch { supervise() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            proc?.destroyForcibly()
        } catch (_: Exception) {}
        supervisor?.cancel()
        super.onDestroy()
    }

    private fun shutdown(userInitiated: Boolean) {
        if (userInitiated) userStopped = true
        supervisor?.cancel()
        supervisor = null
        try {
            proc?.destroyForcibly()
        } catch (_: Exception) {}
        proc = null
        if (userInitiated) OpenCodeStatus.set(OpenCodeStatus.Value.Stopped)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private suspend fun supervise() {
        var quickDeaths = 0
        while (scope.isActive && !userStopped) {
            val startedAt = SystemClock.elapsedRealtime()
            val exit = startOnce()
            if (!scope.isActive || userStopped) break
            if (exit == null) {
                // Fatal (missing prereqs / spawn failure already reported) — stop, don't loop.
                shutdown(userInitiated = false)
                break
            }
            val uptime = SystemClock.elapsedRealtime() - startedAt
            if (uptime < 15_000) quickDeaths++ else quickDeaths = 0
            appendServiceLog("opencode web exited ($exit) after ${uptime / 1000}s")
            if (quickDeaths >= 5) {
                val msg = "opencode web keeps dying — see Settings log, then Retry from Home"
                OpenCodeStatus.set(OpenCodeStatus.Value.Failed(msg))
                updateNotification("OpenCode died repeatedly")
                shutdown(userInitiated = false)
                break
            }
            OpenCodeStatus.set(OpenCodeStatus.Value.Starting)
            updateNotification("Restarting OpenCode…")
            delay(3000)
        }
    }

    /**
     * One supervised run. Returns the exit code, or null for fatal errors
     * (status already set to Failed — supervisor must not retry).
     */
    private suspend fun startOnce(): Int? {
        if (!RuntimeBootstrap.isBootstrapDone(filesDir)) {
            OpenCodeStatus.set(OpenCodeStatus.Value.Failed("Run Test bootstrap first (Setup tab)"))
            return null
        }
        if (!GuestSetup.isDone(filesDir)) {
            OpenCodeStatus.set(OpenCodeStatus.Value.Failed("Run Install Node + OpenCode first (Setup tab)"))
            return null
        }
        val resolved = ProotSetup.resolve(this) { appendServiceLog(it) }.getOrElse {
            OpenCodeStatus.set(OpenCodeStatus.Value.Failed("proot setup failed: ${it.message}"))
            return null
        }
        val rootfs = RuntimeBootstrap.rootfsDir(filesDir)
        // Phase 5b: work inside the user's project folder when one is picked —
        // bind-mount the shared-storage path into the guest at /project so
        // opencode edits real files other apps can also see. No project yet
        // means guest home, exactly like Phase 3. A stale pick is a hard
        // failure with a re-pick hint, never a silent wrong directory.
        val mount = ProjectMount.resolve(
            ProjectStore.get(this),
            StorageAccess.hasFullAccess(this)
        )
        if (mount is ProjectMount.Mount.Invalid) {
            OpenCodeStatus.set(OpenCodeStatus.Value.Failed(mount.reason))
            return null
        }
        val binds = if (mount is ProjectMount.Mount.Bound) {
            try {
                File(rootfs, ProjectMount.GUEST_PATH.trimStart('/')).mkdirs()
            } catch (_: Exception) {}
            ProjectMount.bindsFor(mount)
        } else {
            emptyMap()
        }
        val workdir = ProjectMount.guestWorkdir(mount)
        val guestSh =
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "export HOME=/root; mkdir -p /root; cd $workdir; " +
                "exec opencode web --hostname $HOST --port $PORT"
        val cmd = ProotRunner.buildCommand(
            resolved.proot, rootfs,
            binds = binds,
            guestCmd = listOf("/bin/sh", "-c", guestSh)
        )
        appendServiceLog(
            when (mount) {
                is ProjectMount.Mount.Bound ->
                    "$ opencode web --hostname $HOST --port $PORT " +
                        "(project ${mount.hostDir.absolutePath} → ${ProjectMount.GUEST_PATH})"
                else -> "$ opencode web --hostname $HOST --port $PORT (no project — guest home)"
            }
        )
        val pb = ProcessBuilder(cmd)
        pb.environment().remove("LD_PRELOAD")
        pb.environment().putAll(resolved.env)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            OpenCodeStatus.set(OpenCodeStatus.Value.Failed("Could not start proot: ${e.message}"))
            return null
        }
        proc = p

        val foundUrl = AtomicReference<String?>(null)
        val recent = ArrayDeque<String>()
        fun pump(stream: java.io.InputStream, tag: String) {
            try {
                stream.bufferedReader().forEachLine { line ->
                    synchronized(recent) {
                        recent.addLast(line.take(300))
                        while (recent.size > 60) recent.removeFirst()
                    }
                    appendServiceLog("[$tag] $line")
                    if (tag == "out" && foundUrl.get() == null) {
                        pickUrl(line)?.let { foundUrl.compareAndSet(null, it) }
                    }
                }
            } catch (_: Exception) {}
        }
        val tOut = Thread({ pump(p.inputStream, "out") }, "opencode-stdout").apply { isDaemon = true; start() }
        val tErr = Thread({ pump(p.errorStream, "err") }, "opencode-stderr").apply { isDaemon = true; start() }

        try {
            // Wait for the URL (or death) — up to 45s, then assume the fixed URL if alive.
            val deadline = SystemClock.elapsedRealtime() + 45_000
            var url: String? = null
            while (p.isAlive && !userStopped && scope.isActive) {
                url = foundUrl.get()
                if (url != null) break
                if (SystemClock.elapsedRealtime() > deadline) break
                delay(250)
            }
            if (!p.isAlive) {
                val tail = synchronized(recent) { recent.takeLast(15).joinToString("\n") }
                appendServiceLog("process died during startup:\n$tail")
                tOut.join(2000); tErr.join(2000)
                proc = null
                try {
                    return p.exitValue()
                } catch (_: Exception) {
                    return 1
                }
            }
            if (userStopped || !scope.isActive) {
                tOut.join(1000); tErr.join(1000)
                return 0
            }
            val bound = url ?: "http://$HOST:$PORT"
            if (url == null) appendServiceLog("no URL in output after 45s — assuming $bound")
            OpenCodeStatus.set(OpenCodeStatus.Value.Running(bound))
            updateNotification(bound)
            appendServiceLog("running at $bound")
            try {
                p.waitFor()
            } catch (_: Exception) {
                return 0
            }
            tOut.join(2000); tErr.join(2000)
            proc = null
            return try {
                p.exitValue()
            } catch (_: Exception) {
                0
            }
        } finally {
            if (proc === p) proc = null
        }
    }

    private fun serviceLogFile(): File = File(filesDir, "opencode-service.log")

    private fun appendServiceLog(line: String) {
        try {
            serviceLogFile().appendText(line.take(500) + "\n")
        } catch (_: Exception) {}
    }

    private fun rotateLogIfHuge() {
        try {
            val f = serviceLogFile()
            if (f.exists() && f.length() > 512 * 1024) {
                val lines = f.readLines().takeLast(300)
                f.writeText(lines.joinToString("\n"))
            }
        } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "OpenCode status",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OpenCode Launcher")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
