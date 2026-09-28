package com.mixtervee.pocketspeaker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class CaptureService extends Service {
    static final String ACTION_START = "com.mixtervee.pocketspeaker.START_CAPTURE";
    static final String ACTION_STATUS = "com.mixtervee.pocketspeaker.SENDER_STATUS";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "pocket_speaker_capture";
    private static final int NOTIFICATION_ID = 100;

    private final ArrayBlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(48);
    private volatile boolean running;
    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private DatagramSocket controlSocket;
    private Socket clientSocket;
    private volatile OutputStream clientOut;
    private Thread captureThread;
    private Thread writerThread;
    private Thread controlThread;
    private int sampleRate = 48000;
    private int channelCount = 2;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        if (running) {
            sendStatus("TV audio capture is already running.");
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification("TV audio capture active");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == 0 || resultData == null) {
            sendStatus("Could not start TV audio capture.");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjectionManager unavailable");
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection unavailable");

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    sendStatus("TV audio sharing permission ended.");
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            audioRecord = createPlaybackRecorder(mediaProjection);
            if (audioRecord == null) throw new IllegalStateException("Audio capture is not supported");

            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "PocketSpeaker:Capture");
                wakeLock.acquire();
            }

            running = true;
            audioRecord.startRecording();
            startThreads();
            sendStatus("TV audio ready — waiting for your phone.");
        } catch (Exception e) {
            sendStatus("Unable to capture TV audio: " + safeMessage(e));
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private AudioRecord createPlaybackRecorder(MediaProjection projection) {
        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        int[][] candidates = new int[][]{
                {48000, 2}, {48000, 1}, {44100, 2}, {44100, 1}
        };

        for (int[] candidate : candidates) {
            int rate = candidate[0];
            int channels = candidate[1];
            int inputMask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            int minBuffer = AudioRecord.getMinBufferSize(rate, inputMask, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) continue;

            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(inputMask)
                        .build();

                AudioRecord record = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(minBuffer * 2, 16384))
                        .setAudioPlaybackCaptureConfig(config)
                        .build();

                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    sampleRate = record.getSampleRate();
                    channelCount = channels;
                    return record;
                }
                record.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void startThreads() {
        captureThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            while (running) {
                try {
                    int read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (read > 0 && clientOut != null) {
                        byte[] copy = Arrays.copyOf(buffer, read);
                        if (!audioQueue.offer(copy)) {
                            audioQueue.poll();
                            audioQueue.offer(copy);
                        }
                    }
                } catch (Exception e) {
                    if (running) sendStatus("Audio capture stopped: " + safeMessage(e));
                    break;
                }
            }
        }, "PocketSpeaker-Capture");

        writerThread = new Thread(() -> {
            while (running) {
                try {
                    byte[] data = audioQueue.poll(500, TimeUnit.MILLISECONDS);
                    OutputStream out = clientOut;
                    if (data != null && out != null) {
                        out.write(data);
                    }
                } catch (Exception e) {
                    closeClient();
                    if (running) sendStatus("Phone disconnected — waiting for another connection.");
                }
            }
        }, "PocketSpeaker-Writer");

        controlThread = new Thread(() -> {
            try {
                controlSocket = new DatagramSocket(NetworkProtocol.CONTROL_PORT);
                controlSocket.setBroadcast(true);
                byte[] buffer = new byte[512];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    controlSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                    if (NetworkProtocol.DISCOVER.equals(msg)) {
                        String response = NetworkProtocol.SENDER_PREFIX + Build.MODEL;
                        byte[] reply = response.getBytes(StandardCharsets.UTF_8);
                        controlSocket.send(new DatagramPacket(
                                reply, reply.length, packet.getAddress(), packet.getPort()));
                    } else if (msg.startsWith(NetworkProtocol.CONNECT_PREFIX)) {
                        String portText = msg.substring(NetworkProtocol.CONNECT_PREFIX.length()).trim();
                        try {
                            int port = Integer.parseInt(portText);
                            connectClient(packet.getAddress().getHostAddress(), port);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    sendStatus("Network listener stopped: " + safeMessage(e));
                    stopSelf();
                }
            }
        }, "PocketSpeaker-Control");

        captureThread.start();
        writerThread.start();
        controlThread.start();
    }

    private synchronized void connectClient(String ip, int port) {
        closeClient();
        audioQueue.clear();
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(ip, port), 4000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(NetworkProtocol.STREAM_MAGIC);
            out.writeInt(sampleRate);
            out.writeInt(channelCount);
            out.writeInt(AudioFormat.ENCODING_PCM_16BIT);
            out.flush();
            clientSocket = socket;
            clientOut = out;
            sendStatus("Connected to phone at " + ip + ".");
        } catch (Exception e) {
            closeClient();
            sendStatus("Could not connect to phone: " + safeMessage(e));
        }
    }

    private synchronized void closeClient() {
        clientOut = null;
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (Exception ignored) {
        }
        clientSocket = null;
        audioQueue.clear();
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_capture), NotificationManager.IMPORTANCE_LOW);
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

    @Override
    public void onDestroy() {
        running = false;
        if (controlSocket != null) controlSocket.close();
        closeClient();

        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
        } catch (Exception ignored) {
        }
        audioRecord = null;

        try {
            if (mediaProjection != null) mediaProjection.stop();
        } catch (Exception ignored) {
        }
        mediaProjection = null;

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        audioQueue.clear();
        sendStatus("TV audio stopped.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
