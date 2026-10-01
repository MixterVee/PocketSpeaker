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

        final String finalSenderName = senderName;
        final boolean finalLowLatency = lowLatency;
        workerThread = new Thread(() -> {
            if (finalLowLatency) {
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
        long lastMeterUpdate = 0L;
        // Beta 9 keeps a modest fixed reserve instead of Beta 8's hard
        // pause/flush/rebuffer recovery. That recovery could turn one brief
        // scheduling hiccup into an audible ~100-120 ms hole of its own.
        // +35 ms is only 15 ms more than Beta 8's normal reserve.
        final int baselineCushionMs = 35;
        boolean receivedFirstAudio = false;
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
                    throw timeout;
                }

                if (!receivedFirstAudio) {
                    receivedFirstAudio = true;
                    udpAudioSocket.setSoTimeout(10000);
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

                int requested = requestedResyncId;
                if (packetResyncId < requested) {
                    continue;
                }

                if (packetResyncId > activeResyncId || requested > activeResyncId) {
                    activeResyncId = Math.max(packetResyncId, requested);
                    try {
                        if (playbackStarted && audioTrack != null) audioTrack.pause();
                        if (audioTrack != null) audioTrack.flush();
                    } catch (Exception ignored) {
                    }
                    bufferedBeforePlay = 0;
                    playbackStarted = false;
                    lastSequence = sequence - 1;
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

                if (lastSequence >= 0) {
                    int expected = lastSequence + 1;
                    if (sequence < expected) {
                        continue;
                    }
                    if (sequence > expected) {
                        packetLoss += sequence - expected;
                    }
                }
                lastSequence = sequence;

                int audioOffset = 28;
                int audioLength = datagram.getLength() - audioOffset;
                if (audioLength <= 0) continue;

                int peak = pcmPeakPercent(datagram.getData(), audioOffset, audioLength);

                int writtenOffset = 0;
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
                    if (!playbackStarted) bufferedBeforePlay += written;
                }

                int prebufferMs = senderTransport == 1 ? 45
                        : (senderTransport == 2 ? 25 : 35);
                int targetPrebufferBytes = Math.max(
                        (sampleRate * channels * 2
                                * (prebufferMs + baselineCushionMs)) / 1000,
                        audioLength * 2);

                if (!playbackStarted && bufferedBeforePlay >= targetPrebufferBytes) {
                    audioTrack.play();
                    playbackStarted = true;
                    sendStatus("Playing " + senderName + " • LOW LATENCY UDP.");
                }

                long now = System.currentTimeMillis();
                if (now - lastMeterUpdate >= 700L) {
                    int underruns = audioTrack.getUnderrunCount();

                    // Beta 9 deliberately does not pause/flush here. Keep playing
                    // through brief loss bursts and let the fixed reserve absorb them.
                    // Explicit sender resyncs still use the normal flush path above.

                    sendStatus("UDP " + transportLabel
                            + " • signal " + peak + "%"
                            + " • cushion +" + baselineCushionMs + " ms\n"
                            + "Lost " + packetLoss
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
        String message = NetworkProtocol.CONNECT_PREFIX
                + NetworkProtocol.STREAM_PORT + "|" + NetworkProtocol.RESYNC_PORT
                + "|" + NetworkProtocol.UDP_AUDIO_PORT
                + "|" + (lowLatency ? "UDP" : "TCP");
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
