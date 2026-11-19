package dev.singscale.app.service

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object LocalResolver : LocalDNSTransport {

    internal const val MAX_CONCURRENT_LOCAL_DNS = 16
    private const val RCODE_SERVFAIL = 2
    private const val RCODE_NXDOMAIN = 3
    private const val DNS_QUERY_TIMEOUT_SEC = 4L
    private const val DNS_SLOT_ACQUIRE_MS = 1200L

    /**
     * Bounds concurrent blocked Go->Java JNI DNS threads so cellular blackouts / weak-signal bursts
     * can never trigger unbounded Go M pthread creation (which previously spawned 950+ OS threads).
     */
    private val inflightPermits = Semaphore(MAX_CONCURRENT_LOCAL_DNS)

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = DefaultNetworkMonitor.resolvePhysicalNetwork()
        if (network == null) {
            ctx.errnoCode(OsConstants.ENETUNREACH)
            return
        }
        val acquired = try {
            inflightPermits.tryAcquire(DNS_SLOT_ACQUIRE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!acquired) {
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }
        try {
            exchangeInternal(ctx, network, message)
        } finally {
            inflightPermits.release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exchangeInternal(ctx: ExchangeContext, network: android.net.Network, message: ByteArray) {

        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val signal = CancellationSignal()

        ctx.onCancel {
            signal.cancel()
            if (completed.compareAndSet(false, true)) {
                ctx.errnoCode(OsConstants.ECANCELED)
                latch.countDown()
            }
        }

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                if (completed.compareAndSet(false, true)) {
                    if (rcode == 0) {
                        ctx.rawSuccess(answer)
                    } else {
                        ctx.errorCode(rcode)
                    }
                    latch.countDown()
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (completed.compareAndSet(false, true)) {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        ctx.errnoCode(cause.errno)
                    } else {
                        ctx.errorCode(RCODE_SERVFAIL)
                    }
                    latch.countDown()
                }
            }
        }

        try {
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
        } catch (_: Throwable) {
            if (completed.compareAndSet(false, true)) {
                ctx.errorCode(RCODE_SERVFAIL)
                latch.countDown()
            }
            return
        }

        if (!latch.await(DNS_QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            signal.cancel()
            if (completed.compareAndSet(false, true)) {
                ctx.errnoCode(OsConstants.ETIMEDOUT)
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, networkType: String, domain: String) {
        val network = DefaultNetworkMonitor.resolvePhysicalNetwork()
        if (network == null) {
            ctx.errnoCode(OsConstants.ENETUNREACH)
            return
        }
        val acquired = try {
            inflightPermits.tryAcquire(DNS_SLOT_ACQUIRE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!acquired) {
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }
        try {
            lookupInternal(ctx, network, networkType, domain)
        } finally {
            inflightPermits.release()
        }
    }

    private fun lookupInternal(
        ctx: ExchangeContext,
        network: android.net.Network,
        networkType: String,
        domain: String
    ) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val completed = AtomicBoolean(false)
            val latch = CountDownLatch(1)
            val signal = CancellationSignal()

            ctx.onCancel {
                signal.cancel()
                if (completed.compareAndSet(false, true)) {
                    ctx.errnoCode(OsConstants.ECANCELED)
                    latch.countDown()
                }
            }

            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    if (completed.compareAndSet(false, true)) {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                        latch.countDown()
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (completed.compareAndSet(false, true)) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            ctx.errnoCode(cause.errno)
                        } else {
                            ctx.errorCode(RCODE_SERVFAIL)
                        }
                        latch.countDown()
                    }
                }
            }

            val qType = when {
                networkType.endsWith("4") -> DnsResolver.TYPE_A
                networkType.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }

            try {
                if (qType != null) {
                    DnsResolver.getInstance().query(
                        network,
                        domain,
                        qType,
                        DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(),
                        signal,
                        callback
                    )
                } else {
                    DnsResolver.getInstance().query(
                        network,
                        domain,
                        DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(),
                        signal,
                        callback
                    )
                }
            } catch (_: Throwable) {
                if (completed.compareAndSet(false, true)) {
                    ctx.errorCode(RCODE_SERVFAIL)
                    latch.countDown()
                }
                return
            }

            if (!latch.await(DNS_QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                signal.cancel()
                if (completed.compareAndSet(false, true)) {
                    ctx.errnoCode(OsConstants.ETIMEDOUT)
                }
            }
        } else {
            try {
                val answer = network.getAllByName(domain)
                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
            } catch (_: UnknownHostException) {
                ctx.errorCode(RCODE_NXDOMAIN)
            } catch (_: Throwable) {
                ctx.errorCode(RCODE_SERVFAIL)
            }
        }
    }
}
