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
import java.nio.charset.StandardCharsets;

public class ReceiverService extends Service {
    static final String ACTION_CONNECT = "com.mixtervee.pocketspeaker.CONNECT";
    static final String ACTION_STOP = "com.mixtervee.pocketspeaker.STOP_RECEIVER";
    static final String ACTION_STATUS = "com.mixtervee.pocketspeaker.RECEIVER_STATUS";
    static final String EXTRA_SENDER_IP = "senderIp";
    static final String EXTRA_SENDER_NAME = "senderName";

    private static final String CHANNEL_ID = "pocket_speaker_receiver";
    private static final int NOTIFICATION_ID = 101;

    private volatile boolean running;
    private Thread workerThread;
    private ServerSocket serverSocket;
    private Socket streamSocket;
    private AudioTrack audioTrack;
    private PowerManager.WakeLock wakeLock;
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

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "PocketSpeaker:Receiver");
            wakeLock.acquire();
        }

        final String finalSenderName = senderName;
        workerThread = new Thread(() -> runReceiver(senderIp, finalSenderName, session),
                "PocketSpeaker-Receiver");
        workerThread.start();
        return START_NOT_STICKY;
    }

    private void runReceiver(String senderIp, String senderName, int session) {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(NetworkProtocol.STREAM_PORT));
            serverSocket.setSoTimeout(10000);

            sendConnectRequest(senderIp);
            sendStatus("Waiting for " + senderName + "…");

            streamSocket = serverSocket.accept();
            streamSocket.setTcpNoDelay(true);
            streamSocket.setReceiveBufferSize(16384);

            DataInputStream in = new DataInputStream(streamSocket.getInputStream());
            int magic = in.readInt();
            int sampleRate = in.readInt();
            int channels = in.readInt();
            int encoding = in.readInt();

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
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Could not initialize phone speaker");
            }

            audioTrack.play();
            sendStatus("Playing " + senderName + " audio on this phone.");

            byte[] buffer = new byte[4096];
            long lastMeterUpdate = 0L;
            while (running) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read > 0) {
                    int peak = pcmPeakPercent(buffer, read);
                    long now = System.currentTimeMillis();
                    if (now - lastMeterUpdate >= 700L) {
                        sendStatus("Playing " + senderName + " audio • signal " + peak + "%");
                        lastMeterUpdate = now;
                    }

                    int offset = 0;
                    while (running && offset < read) {
                        int written = audioTrack.write(
                                buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING);
                        if (written < 0) throw new IllegalStateException("Phone audio output failed");
                        offset += written;
                    }
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

    private int pcmPeakPercent(byte[] data, int length) {
        int peak = 0;
        int usable = length - (length % 2);
        for (int i = 0; i < usable; i += 2) {
            int lo = data[i] & 0xff;
            int hi = data[i + 1];
            short sample = (short) ((hi << 8) | lo);
            int value = Math.abs((int) sample);
            if (value > peak) peak = value;
        }
        return Math.min(100, Math.round((peak / 32767f) * 100f));
    }

    private void sendConnectRequest(String senderIp) throws Exception {
        String message = NetworkProtocol.CONNECT_PREFIX + NetworkProtocol.STREAM_PORT;
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
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        try {
            if (streamSocket != null) streamSocket.close();
        } catch (Exception ignored) {
        }
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
