package dev.addblocker.adshield.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.addblocker.adshield.MainActivity
import dev.addblocker.adshield.R
import dev.addblocker.adshield.data.Prefs
import dev.addblocker.adshield.data.QueryLog
import dev.addblocker.adshield.data.RulesRepository
import dev.addblocker.adshield.data.StatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The filtering tunnel.
 *
 * Only the virtual resolver address ([TUN_DNS]) is routed into the tunnel, so
 * normal traffic keeps using the phone's own connection at full speed and the
 * kernel never has to forward packets for us. Every packet that arrives is a DNS
 * query: answer it locally when the name is on a list, otherwise relay it to the
 * configured upstream resolver over a protected socket.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpn"

        const val ACTION_START = "dev.addblocker.adshield.action.START"
        const val ACTION_STOP = "dev.addblocker.adshield.action.STOP"
        const val ACTION_RELOAD = "dev.addblocker.adshield.action.RELOAD"

        private const val CHANNEL_ID = "adshield_status"
        private const val NOTIFICATION_ID = 1001

        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_DNS = "10.111.222.2"
        private const val TUN_PREFIX = 24
        private const val WORKER_COUNT = 4

        /**
         * Resolvers that apps sometimes hard-code. Routing these into the tunnel
         * means their DNS still gets filtered instead of sneaking past us.
         */
        private val HARDCODED_RESOLVERS = listOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "94.140.14.14", "94.140.15.15",
            "76.76.2.0", "76.76.10.0"
        )

        fun start(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** Re-read settings/lists without dropping the tunnel. */
        fun reload(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_RELOAD)
            runCatching { context.startService(intent) }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunnelInput: FileInputStream? = null
    private var tunnelOutput: FileOutputStream? = null
    private var tunnelThread: Thread? = null
    private var workers: ThreadPoolExecutor? = null

    private val writeLock = Any()
    private val upstreamLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    private var upstream: DnsUpstream? = null
    private val cache = DnsCache()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                startForegroundNotification()
                upstream = null
                cache.clear()
                RulesRepository.rebuild()
                if (tun == null) startVpn()
                updateNotification()
                return START_STICKY
            }

            else -> {
                startForegroundNotification()
                if (tun == null) startVpn() else updateNotification()
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by the system")
        stopVpn()
    }

    override fun onDestroy() {
        VpnStatus.setRunning(false)
        notificationJob?.cancel()
        notificationJob = null
        closeTunnel()
        workers?.shutdownNow()
        workers = null
        StatsRepository.flush()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startVpn() {
        val descriptor = buildTunnel()
        if (descriptor == null) {
            VpnStatus.setError("VPN permission was denied, so protection cannot start.")
            VpnStatus.setRunning(false)
            stopSelf()
            return
        }

        tun = descriptor
        tunnelInput = FileInputStream(descriptor.fileDescriptor)
        tunnelOutput = FileOutputStream(descriptor.fileDescriptor)
        cache.clear()
        upstream = null
        ensureWorkers()

        VpnStatus.setError(null)
        VpnStatus.setRunning(true)

        tunnelThread = Thread({ readLoop() }, "adshield-tunnel").apply {
            isDaemon = true
            start()
        }

        RulesRepository.rebuild()
        StatsRepository.flush()
        startNotificationTicker()
        updateNotification()
    }

    private fun buildTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addDnsServer(TUN_DNS)
            .addRoute(TUN_DNS, 32)
            .setBlocking(true)

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                10,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingFlags()
            )
        )

        if (Prefs.interceptHardcodedDns) {
            for (address in HARDCODED_RESOLVERS) {
                runCatching { builder.addRoute(address, 32) }
            }
        }

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }
    }

    private fun stopVpn() {
        VpnStatus.setRunning(false)
        notificationJob?.cancel()
        notificationJob = null

        workers?.shutdownNow()
        workers = null

        closeTunnel()

        upstream = null
        cache.clear()
        StatsRepository.flush()

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun closeTunnel() {
        val descriptor = tun
        tun = null
        runCatching { tunnelInput?.close() }
        runCatching { tunnelOutput?.close() }
        tunnelInput = null
        tunnelOutput = null
        runCatching { descriptor?.close() }
        runCatching { tunnelThread?.interrupt() }
        tunnelThread = null
    }

    // --------------------------------------------------------------- packet pump

    private fun readLoop() {
        val input = tunnelInput ?: return
        val buffer = ByteArray(4096)
        try {
            while (VpnStatus.running.value) {
                val read = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    break
                }
                if (read <= 0) break

                val packet = buffer.copyOf(read)
                val executor = workers ?: break
                try {
                    executor.execute { handlePacket(packet) }
                } catch (e: Exception) {
                    // Queue is full: drop it, the client will retry.
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tunnel read loop stopped", e)
        } finally {
            if (VpnStatus.running.value) {
                VpnStatus.setError("Protection stopped unexpectedly. Tap protect to restart.")
                stopVpn()
            }
        }
    }

    private fun ensureWorkers() {
        val current = workers
        if (current != null && !current.isShutdown) return

        val counter = AtomicInteger(1)
        workers = ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(512),
            { runnable ->
                Thread(runnable, "adshield-dns-" + counter.getAndIncrement()).apply { isDaemon = true }
            },
            ThreadPoolExecutor.DiscardPolicy()
        )
    }

    private fun handlePacket(packet: ByteArray) {
        try {
            handlePacketUnsafe(packet)
        } catch (e: Exception) {
            // A malformed or unexpected packet must never take a worker down.
            Log.w(TAG, "Dropped a DNS query that could not be handled", e)
        }
    }

    private fun handlePacketUnsafe(packet: ByteArray) {
        val datagram = IpPacket.parseUdp(packet) ?: return
        if (datagram.dstPort != 53) return

        val query = Dns.parseQuery(datagram.payload) ?: return
        val name = query.name.lowercase()
        val type = Dns.typeLabel(query.qType)

        val blocked = RulesRepository.matcher.isBlocked(name)
        if (blocked) {
            StatsRepository.record(true)
            QueryLog.add(name, true, type)
            send(datagram, Dns.buildBlockedResponse(query, Prefs.blockResponse))
            return
        }

        val answer = lookup(query)
        StatsRepository.record(false)
        QueryLog.add(name, false, type)

        if (answer != null) {
            send(datagram, answer)
        } else {
            send(datagram, Dns.buildErrorResponse(query))
        }
    }

    private fun lookup(query: DnsQuery): ByteArray? {
        cache.get(query.name, query.qType)?.let { cached ->
            return Dns.withTransactionId(cached, query.id)
        }

        val resolver = resolver()
        val response = resolver.resolve(query.raw) ?: return null
        // A single tunnel packet cannot carry more than MTU minus IP/UDP headers.
        if (response.size > 1400) return null
        if (Dns.rcode(response) == Dns.RCODE_NOERROR) {
            val ttl = Dns.minTtl(response).coerceIn(30, 600)
            cache.put(query.name, query.qType, response, ttl)
        }
        return response
    }

    private fun resolver(): DnsUpstream {
        upstream?.let { return it }
        synchronized(upstreamLock) {
            upstream?.let { return it }
            val created = DnsUpstream(resolveUpstreamAddress(), ::protectSocket)
            upstream = created
            return created
        }
    }

    private fun resolveUpstreamAddress(): InetAddress {
        val choice = Prefs.upstream
        for (candidate in listOf(choice.primary, choice.secondary, "1.1.1.1")) {
            val address = runCatching { InetAddress.getByName(candidate) }.getOrNull()
            if (address != null) return address
        }
        throw IllegalStateException("No upstream DNS server reachable")
    }

    private fun protectSocket(socket: DatagramSocket): Boolean = try {
        protect(socket)
    } catch (e: Exception) {
        false
    }

    private fun send(request: UdpDatagram, response: ByteArray) {
        val output = tunnelOutput ?: return
        val packet = IpPacket.buildUdp(
            srcAddress = request.dstAddress,
            srcPort = request.dstPort,
            dstAddress = request.srcAddress,
            dstPort = request.srcPort,
            payload = response
        )
        try {
            synchronized(writeLock) { output.write(packet) }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write DNS reply to the tunnel", e)
        }
    }

    // ------------------------------------------------------------- notification

    private fun startForegroundNotification() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startNotificationTicker() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            while (isActive) {
                delay(5_000)
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        if (!VpnStatus.running.value) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags()
        )
        val stop = PendingIntent.getService(
            this,
            12,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            pendingFlags()
        )

        val total = StatsRepository.queries.value
        val blocked = StatsRepository.blocked.value
        val text = if (total > 0) {
            getString(R.string.notification_stats, blocked, total)
        } else {
            getString(R.string.notification_starting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_status_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
