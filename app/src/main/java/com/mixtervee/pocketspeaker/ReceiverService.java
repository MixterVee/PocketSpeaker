package com.mixtervee.pocketspeaker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.DataInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class ReceiverService extends Service {
    static final String ACTION_CONNECT = "com.mixtervee.pocketspeaker.CONNECT";
    static final String ACTION_STOP = "com.mixtervee.pocketspeaker.STOP_RECEIVER";
    static final String ACTION_STATUS = "com.mixtervee.pocketspeaker.RECEIVER_STATUS";
    static final String EXTRA_SENDER_IP = "senderIp";
    static final String EXTRA_SENDER_NAME = "senderName";
    static final String EXTRA_LOW_LATENCY = "lowLatency";

    private static final String CHANNEL_ID = "pocket_speaker_receiver";
    private static final int NOTIFICATION_ID = 101;

    private volatile boolean running;
    private Thread workerThread;
    private ServerSocket serverSocket;
    private Socket streamSocket;
    private DatagramSocket udpAudioSocket;
    private AudioTrack audioTrack;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private DatagramSocket resyncSocket;
    private Thread resyncThread;
    private volatile int requestedResyncId;
    private volatile int lastMarkerResyncId;
    private volatile int sessionGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_CONNECT.equals(intent.getAction())) return START_NOT_STICKY;

        String senderIp = intent.getStringExtra(EXTRA_SENDER_IP);
        String senderName = intent.getStringExtra(EXTRA_SENDER_NAME);
        boolean lowLatency = intent.getBooleanExtra(EXTRA_LOW_LATENCY, false);
        if (senderIp == null || senderIp.trim().isEmpty()) {
            sendStatus("No TV address was supplied.");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (senderName == null || senderName.trim().isEmpty()) senderName = "Android TV";

        Notification notification = buildNotification("Listening to " + senderName);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        int session = ++sessionGeneration;
        stopWorkerOnly();
        running = true;
        requestedResyncId = 0;
        lastMarkerResyncId = 0;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "PocketSpeaker:Receiver");
            wakeLock.acquire();
        }

        // Beta 12: low-latency UDP depends on the phone receiving packets on time.
        // A CPU wake lock alone does not stop Wi-Fi power saving from briefly batching
        // traffic. Android's low-latency Wi-Fi lock asks the radio/driver to favor
        // interactive delivery while this foreground receiver is active.
        if (lowLatency) {
            try {
                WifiManager wifiManager =
                        (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                            "PocketSpeaker:LowLatency");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            } catch (Exception ignored) {
                // Keep streaming even on devices that do not honor this lock.
                wifiLock = null;
            }
        }

        final String finalSenderName = senderName;
        final boolean finalLowLatency = lowLatency;
        workerThread = new Thread(() -> {
            if (finalLowLatency) {
                // Apply audio priority before the first UDP receive, not only after
                // AudioTrack creation, so the whole receive/write loop gets priority.
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                runLowLatencyReceiver(senderIp, finalSenderName, session);
            } else {
                runReceiver(senderIp, finalSenderName, session);
            }
        }, "PocketSpeaker-Receiver");
        workerThread.start();
        return START_NOT_STICKY;
    }

    private void runReceiver(String senderIp, String senderName, int session) {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(NetworkProtocol.STREAM_PORT));
            serverSocket.setSoTimeout(10000);
            startResyncListener();

            sendConnectRequest(senderIp, false);
            sendStatus("Waiting for " + senderName + " • STABLE TCP…");

            streamSocket = serverSocket.accept();
            streamSocket.setTcpNoDelay(true);
            streamSocket.setReceiveBufferSize(16384);

            DataInputStream in = new DataInputStream(streamSocket.getInputStream());
            int magic = in.readInt();
            int sampleRate = in.readInt();
            int channels = in.readInt();
            int encoding = in.readInt();
            int senderTransport = in.readInt();

            if (magic != NetworkProtocol.STREAM_MAGIC) {
                throw new IllegalStateException("Unexpected audio stream");
            }
            if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                throw new IllegalStateException("Unsupported audio format");
            }
            if (channels != 1 && channels != 2) {
                throw new IllegalStateException("Unsupported channel count");
            }

            int channelMask = channels == 2
                    ? AudioFormat.CHANNEL_OUT_STEREO
                    : AudioFormat.CHANNEL_OUT_MONO;
            int minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IllegalStateException("Audio output unavailable");

            final boolean senderWifi = senderTransport == 1;
            final boolean senderEthernet = senderTransport == 2;
            final int targetPrebufferMs = senderWifi ? 220 : (senderEthernet ? 160 : 200);
            final int audioTrackBufferMs = senderWifi ? 420 : (senderEthernet ? 320 : 380);
            final String transportLabel = senderWifi ? "Wi-Fi" : (senderEthernet ? "Ethernet" : "Network");

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build();

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(
                            minBuffer * 4,
                            (sampleRate * channels * 2 * audioTrackBufferMs) / 1000))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Could not initialize phone speaker");
            }

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            final int targetPrebufferBytes =
                    Math.max((sampleRate * channels * 2 * targetPrebufferMs) / 1000, 4096);
            byte[] buffer = new byte[8192];
            int bufferedBeforePlay = 0;
            boolean playbackStarted = false;
            boolean discardUntilMarker = false;
            int activeResyncId = 0;
            long lastMeterUpdate = 0L;

            while (running) {
                int packetLength = in.readInt();

                // The UDP notice normally arrives before stale TCP audio. Flush immediately
                // and discard old framed packets until the matching TCP marker catches up.
                int requested = requestedResyncId;
                if (requested > activeResyncId) {
                    activeResyncId = requested;
                    discardUntilMarker = true;
                    try {
                        if (playbackStarted) audioTrack.pause();
                        audioTrack.flush();
                    } catch (Exception ignored) {
                    }
                    bufferedBeforePlay = 0;
                    playbackStarted = false;
                }

                if (packetLength == -1) {
                    int markerId = in.readInt();
                    lastMarkerResyncId = Math.max(lastMarkerResyncId, markerId);

                    // If the UDP notice was lost, the in-band marker still performs the flush.
                    if (markerId > activeResyncId) {
                        activeResyncId = markerId;
                        try {
                            if (playbackStarted) audioTrack.pause();
                            audioTrack.flush();
                        } catch (Exception ignored) {
                        }
                        bufferedBeforePlay = 0;
                        playbackStarted = false;
                    }

                    if (markerId >= activeResyncId) {
                        discardUntilMarker = false;
                    }
                    continue;
                }

                if (packetLength <= 0 || packetLength > 65536) {
                    throw new IllegalStateException("Bad audio packet length");
                }

                if (buffer.length < packetLength) buffer = new byte[packetLength];
                in.readFully(buffer, 0, packetLength);

                if (discardUntilMarker) {
                    continue;
                }

                int peak = pcmPeakPercent(buffer, packetLength);
                long now = System.currentTimeMillis();
                if (now - lastMeterUpdate >= 700L) {
                    int underruns = audioTrack.getUnderrunCount();
                    sendStatus("Playing " + senderName + " audio • " + transportLabel
                            + " • signal " + peak
                            + "% • underruns " + underruns
                            + " • resyncs " + activeResyncId);
                    lastMeterUpdate = now;
                }

                int offset = 0;
                while (running && offset < packetLength) {
                    int written = audioTrack.write(
                            buffer, offset, packetLength - offset, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IllegalStateException("Phone audio output failed");
                    offset += written;
                    if (!playbackStarted) bufferedBeforePlay += written;
                }

                if (!playbackStarted && bufferedBeforePlay >= targetPrebufferBytes) {
                    audioTrack.play();
                    playbackStarted = true;
                    sendStatus("Playing " + senderName + " audio on this phone.");
                }
            }

            if (running && session == sessionGeneration) sendStatus(senderName + " disconnected.");
        } catch (java.net.SocketTimeoutException e) {
            if (running && session == sessionGeneration) sendStatus("The TV did not connect. Make sure TV Audio is started there.");
        } catch (Exception e) {
            if (running && session == sessionGeneration) sendStatus("Connection stopped: " + safeMessage(e));
        } finally {
            if (session == sessionGeneration) stopSelf();
        }
    }

    private void runLowLatencyReceiver(String senderIp, String senderName, int session) {
        int packetLoss = 0;
        int lastSequence = -1;
        int activeResyncId = 0;
        int bufferedBeforePlay = 0;
        boolean playbackStarted = false;
        boolean waitingForFreshEpoch = false;
        long lastMeterUpdate = 0L;
        // Beta 22: restore Beta 20's proven AudioTrack capacity and UDP behavior.
        // Request Android's explicit low-latency output flag in addition to PERFORMANCE_MODE_LOW_LATENCY.
        final int fallbackCushionMs = 50;
        // Beta 53: build a slightly deeper normal reserve so short Wi-Fi/Android
        // scheduling stalls are absorbed before AudioTrack can run dry. The watchdog
        // below adds only a tiny bridge if the queue still approaches the danger zone.
        final int adaptiveCushionMs = 28;
        final int fallbackStartupGuardMs = 35;
        final int adaptiveStartupGuardMs = 0;
        final int predictiveGuardThresholdMs = 34;
        // Beta 62: each sender UDP packet carries about 5 ms of PCM. A 4 ms guard
        // every 10 ms watchdog cycle still let the queue drain during an 80-150 ms
        // scheduling stall. Bridge roughly the full watchdog interval instead, and
        // allow enough retained-audio guards to cover the hard gaps seen in Beta 61.
        final int predictiveGuardMs = 10;
        final int maxPredictiveGuardsPerGap = 18;
        int cleanStartupPackets = 0;
        int selectedCushionMs = fallbackCushionMs;
        int selectedStartupGuardMs = fallbackStartupGuardMs;
        long totalFramesWritten = 0L;
        int lastObservedUnderruns = 0;
        int recoveredPackets = 0;
        int softRecoveries = 0;
        int proactiveGuards = 0;
        int seamPatches = 0;
        int catchupEvents = 0;
        int healthyPacketsSinceCatchup = 0;
        long guardDebtFrames = 0L;
        int guardsThisGap = 0;
        boolean recoveryRefill = false;
        final int recoveryRefillMs = 28;
        byte[] previousAudioPacket = null;
        int previousAudioLength = 0;
        boolean receivedFirstAudio = false;
        long lastAudioPacketAtMs = 0L;
        int activeSampleRate = 0;
        int activeChannels = 0;
        String transportLabel = "Network";

        try {
            udpAudioSocket = new DatagramSocket(NetworkProtocol.UDP_AUDIO_PORT);
            // Keep the network receive queue large enough to absorb brief Android
            // scheduling stalls while AudioTrack is busy. The old 16 KB socket buffer
            // only held a small fraction of a second of PCM and could overflow quickly.
            udpAudioSocket.setReceiveBufferSize(262144);
            // Before the first audio packet, use a short timeout so a lost CONNECT
            // datagram is retried automatically instead of requiring another tap.
            udpAudioSocket.setSoTimeout(700);
            startResyncListener();

            sendConnectRequest(senderIp, true);
            sendStatus("Waiting for " + senderName + " • LOW LATENCY UDP…");

            byte[] packetBuffer = new byte[2048];

            while (running) {
                DatagramPacket datagram = new DatagramPacket(packetBuffer, packetBuffer.length);
                try {
                    udpAudioSocket.receive(datagram);
                } catch (java.net.SocketTimeoutException timeout) {
                    if (!receivedFirstAudio) {
                        // CONNECT is UDP too, so it can disappear. Keep asking until the
                        // sender actually starts delivering audio; one tap should be enough.
                        sendConnectRequest(senderIp, true);
                        sendStatus("Connecting to " + senderName + " • retrying automatically…");
                        continue;
                    }

                    long timeoutNow = System.currentTimeMillis();
                    long silentForMs = lastAudioPacketAtMs > 0L
                            ? timeoutNow - lastAudioPacketAtMs : Long.MAX_VALUE;

                    // Beta 55 watchdog: a 10 ms receive timeout is not a disconnect.
                    // It is an opportunity to inspect the real AudioTrack reserve while
                    // no packet is arriving. If the reserve is already near starvation,
                    // intervene sooner, while the reserve is still recoverable, with several
                    // tiny faded tails. Continuity is the priority for Beta 57: short
                    // scheduling stalls should be concealed before Android records an underrun.
                    if (silentForMs < 1500L) {
                        if (playbackStarted
                                && audioTrack != null
                                && previousAudioPacket != null
                                && previousAudioLength > 0
                                && activeSampleRate > 0
                                && activeChannels > 0
                                && guardsThisGap < maxPredictiveGuardsPerGap) {
                            long playedFrames =
                                    audioTrack.getPlaybackHeadPosition() & 0xffffffffL;
                            long queuedFrames =
                                    Math.max(0L, totalFramesWritten - playedFrames);
                            int queuedMs = (int) Math.min(
                                    9999L,
                                    (queuedFrames * 1000L)
                                            / Math.max(1, activeSampleRate));

                            if (queuedMs <= predictiveGuardThresholdMs) {
                                int guardedBytes = writePredictiveGuard(
                                        audioTrack,
                                        previousAudioPacket,
                                        previousAudioLength,
                                        activeSampleRate,
                                        activeChannels,
                                        predictiveGuardMs,
                                        guardsThisGap);
                                int guardFrames =
                                        guardedBytes / Math.max(2, activeChannels * 2);
                                totalFramesWritten += guardFrames;
                                guardDebtFrames += guardFrames;
                                proactiveGuards++;
                                guardsThisGap++;
                            }
                        }
                        continue;
                    }

                    // A genuine long stream silence still uses the proven fresh-epoch
                    // reconnect path.
                    try {
                        if (playbackStarted && audioTrack != null) audioTrack.pause();
                        if (audioTrack != null) audioTrack.flush();
                    } catch (Exception ignored) {
                    }
                    playbackStarted = false;
                    bufferedBeforePlay = 0;
                    totalFramesWritten = 0L;
                    guardDebtFrames = 0L;
                    healthyPacketsSinceCatchup = 0;
                    previousAudioPacket = null;
                    previousAudioLength = 0;
                    lastSequence = -1;
                    waitingForFreshEpoch = true;
                    cleanStartupPackets = 6;
                    selectedCushionMs = adaptiveCushionMs;
                    selectedStartupGuardMs = adaptiveStartupGuardMs;
                    sendRecoveryConnectRequest(senderIp);
                    sendStatus("UDP stream stalled • reconnecting automatically…");
                    continue;
                }

                lastAudioPacketAtMs = System.currentTimeMillis();
                int guardsBeforePacket = guardsThisGap;
                guardsThisGap = 0;

                if (!receivedFirstAudio) {
                    receivedFirstAudio = true;
                    // Normal packets are ~7 ms apart. A 10 ms timeout gives the watchdog
                    // a chance to act during a real scheduling/network hiccup without
                    // firing between healthy packets.
                    udpAudioSocket.setSoTimeout(10);
                }

                if (datagram.getLength() <= 28) continue;

                ByteBuffer packet = ByteBuffer.wrap(
                                datagram.getData(), 0, datagram.getLength())
                        .order(ByteOrder.BIG_ENDIAN);

                int magic = packet.getInt();
                if (magic != NetworkProtocol.UDP_AUDIO_MAGIC) continue;

                int sequence = packet.getInt();
                int packetResyncId = packet.getInt();
                int sampleRate = packet.getInt();
                int channels = packet.getInt();
                int encoding = packet.getInt();
                int senderTransport = packet.getInt();

                if (encoding != AudioFormat.ENCODING_PCM_16BIT) continue;
                if (channels != 1 && channels != 2) continue;

                activeSampleRate = sampleRate;
                activeChannels = channels;

                int requested = requestedResyncId;
                if (packetResyncId < requested) {
                    continue;
                }

                // Beta 47 recovery epochs: after an underrun/reconnect request, keep
                // discarding packets from the old epoch until Windows confirms the
                // freshly re-armed stream with a higher epoch. Once an epoch advances,
                // any late datagrams from earlier epochs remain permanently stale.
                if (packetResyncId < activeResyncId) {
                    continue;
                }
                if (waitingForFreshEpoch && packetResyncId <= activeResyncId) {
                    continue;
                }

                if (packetResyncId > activeResyncId || requested > activeResyncId) {
                    activeResyncId = Math.max(packetResyncId, requested);
                    waitingForFreshEpoch = false;
                    try {
                        if (playbackStarted && audioTrack != null) audioTrack.pause();
                        if (audioTrack != null) audioTrack.flush();
                    } catch (Exception ignored) {
                    }
                    bufferedBeforePlay = 0;
                    playbackStarted = false;
                    lastSequence = sequence - 1;
                    previousAudioPacket = null;
                    previousAudioLength = 0;
                    // Recovery should return to the same tight target that was working
                    // before the underrun, not establish a new delayed equilibrium.
                    cleanStartupPackets = 6;
                    selectedCushionMs = adaptiveCushionMs;
                    selectedStartupGuardMs = adaptiveStartupGuardMs;
                    totalFramesWritten = 0L;
                    guardDebtFrames = 0L;
                    healthyPacketsSinceCatchup = 0;
                }

                if (audioTrack == null) {
                    int channelMask = channels == 2
                            ? AudioFormat.CHANNEL_OUT_STEREO
                            : AudioFormat.CHANNEL_OUT_MONO;
                    int minBuffer = AudioTrack.getMinBufferSize(
                            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuffer <= 0) {
                        throw new IllegalStateException("Audio output unavailable");
                    }

                    final boolean senderWifi = senderTransport == 1;
                    final boolean senderEthernet = senderTransport == 2;
                    // Capacity is deliberately larger than the normal latency target.
                    // A larger AudioTrack does not itself add delay; it leaves room for
                    // the fixed receive reserve and brief Android scheduling jitter.
                    final int audioTrackBufferMs =
                            senderWifi ? 180 : (senderEthernet ? 150 : 165);
                    transportLabel = senderWifi
                            ? "Wi-Fi" : (senderEthernet ? "Ethernet" : "Network");

                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                            .build();
                    AudioFormat format = new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build();

                    audioTrack = new AudioTrack.Builder()
                            .setAudioAttributes(attributes)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(Math.max(
                                    minBuffer,
                                    (sampleRate * channels * 2 * audioTrackBufferMs) / 1000))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            .build();

                    if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        throw new IllegalStateException("Could not initialize phone speaker");
                    }

                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                }

                int missingPackets = 0;
                if (lastSequence >= 0) {
                    int expected = lastSequence + 1;
                    if (sequence < expected) {
                        continue;
                    }
                    if (sequence > expected) {
                        missingPackets = sequence - expected;
                        packetLoss += missingPackets;
                    }
                }
                lastSequence = sequence;

                if (!playbackStarted) {
                    if (missingPackets == 0) {
                        cleanStartupPackets++;
                        if (cleanStartupPackets >= 6) {
                            selectedCushionMs = adaptiveCushionMs;
                            selectedStartupGuardMs = adaptiveStartupGuardMs;
                        }
                    } else {
                        cleanStartupPackets = 0;
                        selectedCushionMs = fallbackCushionMs;
                        selectedStartupGuardMs = fallbackStartupGuardMs;
                    }
                }

                int audioOffset = 28;
                int audioLength = datagram.getLength() - audioOffset;
                if (audioLength <= 0) continue;

                int peak = pcmPeakPercent(datagram.getData(), audioOffset, audioLength);

                // Beta 11 short-gap recovery: if UDP packets went missing but the
                // AudioTrack reserve has not yet starved, preserve that missing time
                // with a gently fading copy of the last good PCM packet. Cap recovery
                // at roughly 60 ms so a long outage never turns into a long repeated
                // sound. If AudioTrack already underrun, do not add delayed audio.
                int currentUnderruns = audioTrack.getUnderrunCount();
                boolean alreadyStarved =
                        playbackStarted && currentUnderruns > lastObservedUnderruns;

                // Beta 55 continuity recovery: do NOT pause, flush, or drain when the
                // AudioTrack counter increments. Those hard operations created the audible
                // hiccup in Beta 54. Keep the stream running and smooth the handoff from the
                // last good PCM into the newly arrived live packet instead.
                if (alreadyStarved) {
                    softRecoveries++;
                    lastObservedUnderruns =
                            Math.max(lastObservedUnderruns, currentUnderruns);
                }
                lastObservedUnderruns = Math.max(lastObservedUnderruns, currentUnderruns);

                int liveBytesConsumed = 0;
                if (playbackStarted
                        && previousAudioPacket != null
                        && previousAudioLength > 0
                        && (alreadyStarved || guardsBeforePacket > 0)) {
                    int crossfadeMs = alreadyStarved ? 14 : 9;
                    // Beta 58: keep Beta 57's guard envelope and latency settings, but make the
                    // return to live audio more gradual. A slightly longer return crossfade
                    // hides the recovery seam without adding any permanent buffer or cushion.
                    int returnStartGainPercent = guardsBeforePacket > 0
                            ? Math.max(65, 100 - (guardsBeforePacket * 7))
                            : 100;
                    liveBytesConsumed = writeReturnCrossfade(
                            audioTrack,
                            previousAudioPacket,
                            previousAudioLength,
                            datagram.getData(),
                            audioOffset,
                            audioLength,
                            sampleRate,
                            channels,
                            returnStartGainPercent,
                            crossfadeMs);
                    totalFramesWritten +=
                            liveBytesConsumed / Math.max(2, channels * 2);
                    if (liveBytesConsumed > 0) {
                        seamPatches++;
                    }
                    healthyPacketsSinceCatchup = 0;
                } else if (playbackStarted
                        && missingPackets == 0
                        && guardDebtFrames > 0L
                        && previousAudioPacket != null
                        && previousAudioLength > 0) {
                    // Beta 56: predictive guards buy continuity by inserting a few
                    // milliseconds of synthetic audio. Track that added time as debt and
                    // pay it back gradually on healthy packets instead of letting latency
                    // accumulate forever. Each repayment skips only 1-2 ms and crossfades
                    // into the later point of the live packet to hide the micro jump.
                    healthyPacketsSinceCatchup++;
                    long guardDebtMs =
                            (guardDebtFrames * 1000L) / Math.max(1, sampleRate);
                    int catchupInterval = guardDebtMs >= 80L ? 2 : 3;
                    int catchupSkipMs = guardDebtMs >= 80L ? 2 : 1;

                    if (guardDebtMs >= 6L
                            && healthyPacketsSinceCatchup >= catchupInterval) {
                        int frameBytes = Math.max(2, channels * 2);
                        int bytesPerMs = Math.max(
                                frameBytes, (sampleRate * frameBytes) / 1000);
                        int requestedSkipBytes = bytesPerMs * catchupSkipMs;
                        requestedSkipBytes -= requestedSkipBytes % frameBytes;

                        int crossfadeMs = 2;
                        int maxCrossfadeBytes = bytesPerMs * crossfadeMs;
                        maxCrossfadeBytes -= maxCrossfadeBytes % frameBytes;
                        int maxSkipBytes = Math.max(
                                0, audioLength - maxCrossfadeBytes - frameBytes);
                        int skipBytes = Math.min(requestedSkipBytes, maxSkipBytes);
                        skipBytes -= skipBytes % frameBytes;

                        if (skipBytes > 0) {
                            int crossfadeBytes = writeCatchupCrossfade(
                                    audioTrack,
                                    previousAudioPacket,
                                    previousAudioLength,
                                    datagram.getData(),
                                    audioOffset + skipBytes,
                                    audioLength - skipBytes,
                                    sampleRate,
                                    channels,
                                    crossfadeMs);
                            liveBytesConsumed = skipBytes + crossfadeBytes;
                            totalFramesWritten +=
                                    crossfadeBytes / frameBytes;
                            long skippedFrames = skipBytes / frameBytes;
                            guardDebtFrames = Math.max(
                                    0L, guardDebtFrames - skippedFrames);
                            catchupEvents++;
                            healthyPacketsSinceCatchup = 0;
                        }
                    }
                } else {
                    healthyPacketsSinceCatchup = 0;
                }

                if (playbackStarted
                        && missingPackets > 0
                        && !alreadyStarved
                        && previousAudioPacket != null
                        && previousAudioLength > 0) {
                    int bytesPerMs = Math.max(1, (sampleRate * channels * 2) / 1000);
                    int previousPacketMs = Math.max(1, previousAudioLength / bytesPerMs);
                    int maxRecoveryPackets = Math.max(1, 60 / previousPacketMs);
                    int packetsToRecover = Math.min(missingPackets, maxRecoveryPackets);
                    byte[] concealed = new byte[previousAudioLength];

                    for (int recoveryIndex = 0;
                         recoveryIndex < packetsToRecover && running;
                         recoveryIndex++) {
                        // Start close to full level and fade each repeated packet.
                        int gainPercent = Math.max(20, 90 - (recoveryIndex * 15));
                        int usable = previousAudioLength - (previousAudioLength % 2);
                        for (int i = 0; i < usable; i += 2) {
                            int sample = (short) ((previousAudioPacket[i] & 0xff)
                                    | (previousAudioPacket[i + 1] << 8));
                            int scaled = (sample * gainPercent) / 100;
                            concealed[i] = (byte) (scaled & 0xff);
                            concealed[i + 1] = (byte) ((scaled >> 8) & 0xff);
                        }
                        if (usable < previousAudioLength) {
                            concealed[previousAudioLength - 1] =
                                    previousAudioPacket[previousAudioLength - 1];
                        }

                        int concealOffset = 0;
                        while (running && concealOffset < previousAudioLength) {
                            int written = audioTrack.write(
                                    concealed,
                                    concealOffset,
                                    previousAudioLength - concealOffset,
                                    AudioTrack.WRITE_BLOCKING);
                            if (written < 0) {
                                throw new IllegalStateException("Phone audio output failed");
                            }
                            concealOffset += written;
                            totalFramesWritten += written / Math.max(2, channels * 2);
                        }
                    }
                    recoveredPackets += packetsToRecover;
                }

                int writtenOffset = liveBytesConsumed;
                while (running && writtenOffset < audioLength) {
                    int written = audioTrack.write(
                            datagram.getData(),
                            audioOffset + writtenOffset,
                            audioLength - writtenOffset,
                            AudioTrack.WRITE_BLOCKING);
                    if (written < 0) {
                        throw new IllegalStateException("Phone audio output failed");
                    }
                    writtenOffset += written;
                    totalFramesWritten += written / Math.max(2, channels * 2);
                    if (!playbackStarted) bufferedBeforePlay += written;
                }

                if (previousAudioPacket == null
                        || previousAudioPacket.length < audioLength) {
                    previousAudioPacket = new byte[audioLength];
                }
                System.arraycopy(
                        datagram.getData(),
                        audioOffset,
                        previousAudioPacket,
                        0,
                        audioLength);
                previousAudioLength = audioLength;

                int prebufferMs = senderTransport == 1 ? 45
                        : (senderTransport == 2 ? 25 : 35);
                int startupGuardMs = activeResyncId == 0
                        ? selectedStartupGuardMs : 0;
                int targetPrebufferMs = recoveryRefill
                        ? recoveryRefillMs
                        : prebufferMs + selectedCushionMs + startupGuardMs;
                int targetPrebufferBytes = Math.max(
                        (sampleRate * channels * 2 * targetPrebufferMs) / 1000,
                        audioLength * 2);

                if (!playbackStarted && bufferedBeforePlay >= targetPrebufferBytes) {
                    audioTrack.play();
                    playbackStarted = true;
                    recoveryRefill = false;
                    sendStatus("Playing " + senderName + " • LOW LATENCY UDP"
                            + " • target " + targetPrebufferMs + " ms.");
                }

                long now = System.currentTimeMillis();
                if (now - lastMeterUpdate >= 700L) {
                    int underruns = audioTrack.getUnderrunCount();
                    lastObservedUnderruns = Math.max(lastObservedUnderruns, underruns);

                    // No pause/flush recovery here. The fixed reserve handles scheduler
                    // jitter; Beta 11 only conceals bounded UDP sequence gaps that are
                    // caught before the AudioTrack itself starves.

                    long playedFrames = playbackStarted
                            ? (audioTrack.getPlaybackHeadPosition() & 0xffffffffL) : 0L;
                    long queuedFrames = Math.max(0L, totalFramesWritten - playedFrames);
                    int queuedMs = (int) Math.min(
                            9999L, (queuedFrames * 1000L) / Math.max(1, sampleRate));

                    sendStatus("UDP " + transportLabel
                            + " • signal " + peak + "%"
                            + " • cushion +" + selectedCushionMs + " ms"
                            + " • queue ~" + queuedMs + " ms\n"
                            + "Lost " + packetLoss
                            + " • Recovered " + recoveredPackets
                            + " • Guard " + proactiveGuards
                            + " • Patch " + seamPatches
                            + " • Catch " + catchupEvents
                            + " • Debt "
                            + ((guardDebtFrames * 1000L) / Math.max(1, sampleRate))
                            + "ms"
                            + " • Soft " + softRecoveries
                            + " • Underruns " + underruns
                            + " • Resyncs " + activeResyncId);
                    lastMeterUpdate = now;
                }
            }

            if (running && session == sessionGeneration) {
                sendStatus(senderName + " disconnected.");
            }
        } catch (java.net.SocketTimeoutException e) {
            if (running && session == sessionGeneration) {
                sendStatus("No low latency audio arrived. Try STABLE TCP mode.");
            }
        } catch (Exception e) {
            if (running && session == sessionGeneration) {
                sendStatus("Low latency connection stopped: " + safeMessage(e));
            }
        } finally {
            if (session == sessionGeneration) stopSelf();
        }
    }

    private int writePredictiveGuard(
            AudioTrack track,
            byte[] previousAudioPacket,
            int previousAudioLength,
            int sampleRate,
            int channels,
            int guardMs,
            int guardIndex) {
        int frameBytes = Math.max(2, channels * 2);
        int bytesPerMs = Math.max(frameBytes, (sampleRate * frameBytes) / 1000);
        int sourceBytes = previousAudioLength - (previousAudioLength % frameBytes);
        if (sourceBytes <= 0) return 0;

        // Beta 62 retained-audio bridge: sender packets are only about 5 ms long,
        // so a guard longer than one packet must synthesize continuity rather than
        // stop at previousAudioLength. Reflect (ping-pong) the last good packet by
        // whole audio frames: last -> first -> last. The endpoints therefore meet
        // without the hard end-to-start discontinuity of a simple loop.
        int guardBytes = bytesPerMs * Math.max(1, guardMs);
        guardBytes -= guardBytes % frameBytes;
        if (guardBytes <= 0) return 0;

        byte[] guard = new byte[guardBytes];
        int sourceFrames = Math.max(1, sourceBytes / frameBytes);
        int guardFrames = Math.max(1, guardBytes / frameBytes);
        int reflectionPeriod = sourceFrames <= 1 ? 1 : (sourceFrames - 1) * 2;

        // Continue the same gentle envelope across successive watchdog guards.
        // Never fade toward silence: the purpose of this bridge is specifically to
        // keep AudioTrack fed until live PCM returns.
        int startGainPercent = Math.max(65, 100 - (Math.max(0, guardIndex) * 7));
        int endGainPercent = Math.max(65, startGainPercent - 7);

        for (int frame = 0; frame < guardFrames; frame++) {
            int reflected = reflectionPeriod <= 1 ? 0 : frame % reflectionPeriod;
            int sourceFrame;
            if (sourceFrames <= 1) {
                sourceFrame = 0;
            } else if (reflected <= sourceFrames - 1) {
                sourceFrame = (sourceFrames - 1) - reflected;
            } else {
                sourceFrame = reflected - (sourceFrames - 1);
            }

            int gainPercent = startGainPercent
                    - (((startGainPercent - endGainPercent) * frame)
                    / Math.max(1, guardFrames - 1));
            int sourceFrameOffset = sourceFrame * frameBytes;
            int guardFrameOffset = frame * frameBytes;

            for (int channel = 0; channel < channels; channel++) {
                int source = sourceFrameOffset + (channel * 2);
                int target = guardFrameOffset + (channel * 2);
                int sample = (short) ((previousAudioPacket[source] & 0xff)
                        | (previousAudioPacket[source + 1] << 8));
                int scaled = (sample * gainPercent) / 100;
                guard[target] = (byte) (scaled & 0xff);
                guard[target + 1] = (byte) ((scaled >> 8) & 0xff);
            }
        }

        int writtenTotal = 0;
        while (running && writtenTotal < guardBytes) {
            int written = track.write(
                    guard,
                    writtenTotal,
                    guardBytes - writtenTotal,
                    AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("Phone audio output failed");
            }
            writtenTotal += written;
        }
        return writtenTotal;
    }

    private int writeCatchupCrossfade(
            AudioTrack track,
            byte[] previousAudioPacket,
            int previousAudioLength,
            byte[] livePacket,
            int liveOffset,
            int liveLength,
            int sampleRate,
            int channels,
            int crossfadeMs) {
        int frameBytes = Math.max(2, channels * 2);
        int bytesPerMs = Math.max(frameBytes, (sampleRate * frameBytes) / 1000);
        int crossfadeBytes = Math.min(
                Math.min(previousAudioLength, liveLength),
                bytesPerMs * Math.max(1, crossfadeMs));
        crossfadeBytes -= crossfadeBytes % frameBytes;
        if (crossfadeBytes <= 0) return 0;

        byte[] blended = new byte[crossfadeBytes];
        int previousStart = previousAudioLength - crossfadeBytes;
        int frameCount = Math.max(1, crossfadeBytes / frameBytes);

        for (int frame = 0; frame < frameCount; frame++) {
            int livePercent = frameCount <= 1
                    ? 100
                    : (frame * 100) / (frameCount - 1);
            int previousPercent = 100 - livePercent;
            int frameOffset = frame * frameBytes;

            for (int channel = 0; channel < channels; channel++) {
                int sampleOffset = frameOffset + (channel * 2);
                int previousIndex = previousStart + sampleOffset;
                int liveIndex = liveOffset + sampleOffset;

                int previousSample = (short) ((previousAudioPacket[previousIndex] & 0xff)
                        | (previousAudioPacket[previousIndex + 1] << 8));
                int liveSample = (short) ((livePacket[liveIndex] & 0xff)
                        | (livePacket[liveIndex + 1] << 8));

                int mixed = ((previousSample * previousPercent)
                        + (liveSample * livePercent)) / 100;
                blended[sampleOffset] = (byte) (mixed & 0xff);
                blended[sampleOffset + 1] = (byte) ((mixed >> 8) & 0xff);
            }
        }

        int writtenTotal = 0;
        while (running && writtenTotal < crossfadeBytes) {
            int written = track.write(
                    blended,
                    writtenTotal,
                    crossfadeBytes - writtenTotal,
                    AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("Phone audio output failed");
            }
            writtenTotal += written;
        }
        return writtenTotal;
    }

    private int writeReturnCrossfade(
            AudioTrack track,
            byte[] previousAudioPacket,
            int previousAudioLength,
            byte[] livePacket,
            int liveOffset,
            int liveLength,
            int sampleRate,
            int channels,
            int previousGainPercent,
            int crossfadeMs) {
        int frameBytes = Math.max(2, channels * 2);
        int bytesPerMs = Math.max(frameBytes, (sampleRate * frameBytes) / 1000);
        int crossfadeBytes = Math.min(
                Math.min(previousAudioLength, liveLength),
                bytesPerMs * Math.max(1, crossfadeMs));
        crossfadeBytes -= crossfadeBytes % frameBytes;
        if (crossfadeBytes <= 0) return 0;

        byte[] blended = new byte[crossfadeBytes];
        int previousStart = previousAudioLength - crossfadeBytes;
        int frameCount = Math.max(1, crossfadeBytes / frameBytes);
        int clampedPreviousGain = Math.max(0, Math.min(100, previousGainPercent));

        for (int frame = 0; frame < frameCount; frame++) {
            double progress = frameCount <= 1
                    ? 1.0
                    : (double) frame / (double) (frameCount - 1);
            // Smoothstep avoids the abrupt slope changes of Beta 56's linear blend.
            double smoothProgress = progress * progress * (3.0 - (2.0 * progress));
            int livePercent = (int) Math.round(smoothProgress * 100.0);
            int previousPercent = 100 - livePercent;
            int frameOffset = frame * frameBytes;

            for (int channel = 0; channel < channels; channel++) {
                int sampleOffset = frameOffset + (channel * 2);
                int previousIndex = previousStart + sampleOffset;
                int liveIndex = liveOffset + sampleOffset;

                int previousSample = (short) ((previousAudioPacket[previousIndex] & 0xff)
                        | (previousAudioPacket[previousIndex + 1] << 8));
                int liveSample = (short) ((livePacket[liveIndex] & 0xff)
                        | (livePacket[liveIndex + 1] << 8));
                int scaledPreviousSample =
                        (previousSample * clampedPreviousGain) / 100;

                int mixed = ((scaledPreviousSample * previousPercent)
                        + (liveSample * livePercent)) / 100;
                blended[sampleOffset] = (byte) (mixed & 0xff);
                blended[sampleOffset + 1] = (byte) ((mixed >> 8) & 0xff);
            }
        }

        int writtenTotal = 0;
        while (running && writtenTotal < crossfadeBytes) {
            int written = track.write(
                    blended,
                    writtenTotal,
                    crossfadeBytes - writtenTotal,
                    AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("Phone audio output failed");
            }
            writtenTotal += written;
        }
        return writtenTotal;
    }

    private int writeSoftRecoveryBridge(
            AudioTrack track,
            byte[] previousAudioPacket,
            int previousAudioLength,
            int sampleRate,
            int channels) {
        int frameBytes = Math.max(2, channels * 2);
        int bytesPerMs = Math.max(frameBytes, (sampleRate * frameBytes) / 1000);

        // One sender packet is about 7 ms. An 8 ms bridge is long enough to cover
        // draining the stale socket backlog and waiting for the next live datagram,
        // without turning recovery itself into noticeable extra latency.
        int bridgeBytes = Math.min(previousAudioLength, bytesPerMs * 8);
        bridgeBytes -= bridgeBytes % frameBytes;
        if (bridgeBytes <= 0) return 0;

        byte[] bridge = new byte[bridgeBytes];
        int sourceStart = previousAudioLength - bridgeBytes;
        int sampleCount = Math.max(1, bridgeBytes / 2);

        for (int i = 0; i < bridgeBytes; i += 2) {
            int source = sourceStart + i;
            int sample = (short) ((previousAudioPacket[source] & 0xff)
                    | (previousAudioPacket[source + 1] << 8));

            // Fade the repeated tail from 90% to 25%. That makes the tiny stretch
            // much less click-prone than repeating a full-level PCM edge.
            int sampleIndex = i / 2;
            int gainPercent = 90
                    - ((65 * sampleIndex) / Math.max(1, sampleCount - 1));
            int scaled = (sample * gainPercent) / 100;
            bridge[i] = (byte) (scaled & 0xff);
            bridge[i + 1] = (byte) ((scaled >> 8) & 0xff);
        }

        int writtenTotal = 0;
        while (running && writtenTotal < bridgeBytes) {
            int written = track.write(
                    bridge,
                    writtenTotal,
                    bridgeBytes - writtenTotal,
                    AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException("Phone audio output failed");
            }
            writtenTotal += written;
        }
        return writtenTotal;
    }

    private int drainUdpBacklog(DatagramSocket socket) {
        if (socket == null || socket.isClosed()) return 0;

        int originalTimeout = 1500;
        int drained = 0;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(1);

            byte[] stale = new byte[2048];
            while (running && drained < 128) {
                try {
                    DatagramPacket packet = new DatagramPacket(stale, stale.length);
                    socket.receive(packet);
                    drained++;
                } catch (java.net.SocketTimeoutException caughtUp) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // If a platform refuses the temporary timeout, the next normal receive
            // still resumes the stream; never turn soft recovery into a fatal error.
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {
            }
        }
        return drained;
    }

    private int pcmPeakPercent(byte[] data, int length) {
        return pcmPeakPercent(data, 0, length);
    }

    private int pcmPeakPercent(byte[] data, int offset, int length) {
        int peak = 0;
        int usable = length - (length % 2);
        for (int i = 0; i < usable; i += 2) {
            int index = offset + i;
            int lo = data[index] & 0xff;
            int hi = data[index + 1];
            short sample = (short) ((hi << 8) | lo);
            int value = Math.abs((int) sample);
            if (value > peak) peak = value;
        }
        return Math.min(100, Math.round((peak / 32767f) * 100f));
    }

    private void startResyncListener() throws Exception {
        resyncSocket = new DatagramSocket(NetworkProtocol.RESYNC_PORT);
        resyncThread = new Thread(() -> {
            byte[] buffer = new byte[128];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    resyncSocket.receive(packet);
                    String message = new String(
                            packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (message.startsWith(NetworkProtocol.RESYNC_PREFIX)) {
                        int id = Integer.parseInt(
                                message.substring(NetworkProtocol.RESYNC_PREFIX.length()).trim());
                        if (id > lastMarkerResyncId && id > requestedResyncId) {
                            requestedResyncId = id;
                        }
                    }
                } catch (Exception e) {
                    if (running && resyncSocket != null && !resyncSocket.isClosed()) {
                        // Keep TCP fallback alive even if the UDP listener has a transient error.
                    }
                }
            }
        }, "PocketSpeaker-Resync");
        resyncThread.start();
    }

    private void sendConnectRequest(String senderIp, boolean lowLatency) throws Exception {
        sendConnectRequest(senderIp, lowLatency, false);
    }

    private void sendRecoveryConnectRequest(String senderIp) throws Exception {
        sendConnectRequest(senderIp, true, true);
    }

    private void sendConnectRequest(
            String senderIp, boolean lowLatency, boolean recovery) throws Exception {
        String message = NetworkProtocol.CONNECT_PREFIX
                + NetworkProtocol.STREAM_PORT + "|" + NetworkProtocol.RESYNC_PORT
                + "|" + NetworkProtocol.UDP_AUDIO_PORT
                + "|" + (lowLatency ? "UDP" : "TCP")
                + (lowLatency && recovery ? "|RECOVER" : "");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        InetAddress sender = InetAddress.getByName(senderIp);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(
                    bytes, bytes.length, sender, NetworkProtocol.CONTROL_PORT));
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_receiver), NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Pocket Speaker")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void sendStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private synchronized void stopWorkerOnly() {
        running = false;
        if (resyncSocket != null) resyncSocket.close();
        resyncSocket = null;
        if (resyncThread != null) resyncThread.interrupt();
        resyncThread = null;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        try {
            if (streamSocket != null) streamSocket.close();
        } catch (Exception ignored) {
        }
        if (udpAudioSocket != null) udpAudioSocket.close();
        udpAudioSocket = null;
        serverSocket = null;
        streamSocket = null;

        try {
            if (audioTrack != null) {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.release();
            }
        } catch (Exception ignored) {
        }
        audioTrack = null;

        if (workerThread != null) workerThread.interrupt();
        workerThread = null;

        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        sessionGeneration++;
        stopWorkerOnly();
        sendStatus("Phone speaker stopped.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
