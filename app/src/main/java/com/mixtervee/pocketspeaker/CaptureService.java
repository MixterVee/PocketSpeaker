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
    static final String ACTION_TOGGLE_TEST_TONE = "com.mixtervee.pocketspeaker.TOGGLE_TEST_TONE";
    static final String ACTION_STATUS = "com.mixtervee.pocketspeaker.SENDER_STATUS";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "pocket_speaker_capture";
    private static final int NOTIFICATION_ID = 100;

    private static final byte[] FLUSH_MARKER = new byte[0];
    private final ArrayBlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(10);
    private volatile boolean running;
    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private DatagramSocket controlSocket;
    private Socket clientSocket;
    private volatile DataOutputStream clientOut;
    private Thread captureThread;
    private Thread writerThread;
    private Thread controlThread;
    private int sampleRate = 48000;
    private int channelCount = 2;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean testTone;
    private double tonePhase;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_TOGGLE_TEST_TONE.equals(intent.getAction())) {
            if (!running) {
                sendStatus("Start TV Audio first, then connect the phone.");
                return START_NOT_STICKY;
            }
            testTone = !testTone;
            sendStatus(testTone
                    ? "TEST TONE ON — the phone should play a steady tone."
                    : "TEST TONE OFF — back to captured TV audio.");
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
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
                        .setBufferSizeInBytes(Math.max(minBuffer, 4096))
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
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            byte[] buffer = new byte[Math.max(960, (sampleRate / 100) * channelCount * 2)];
            long lastReadAt = 0L;

            while (running) {
                try {
                    int read;
                    long beforeRead = System.currentTimeMillis();

                    if (testTone) {
                        read = fillTestTone(buffer);
                        long frames = read / (2L * channelCount);
                        long sleepMs = Math.max(1L, (frames * 1000L) / sampleRate);
                        Thread.sleep(sleepMs);
                    } else {
                        read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    }

                    long now = System.currentTimeMillis();

                    // A decoder seek/restart can stall playback capture.  When that happens,
                    // tell the phone to throw away any PCM it still has queued.
                    if (!testTone && lastReadAt != 0L && beforeRead - lastReadAt > 140L) {
                        requestResync();
                    }
                    lastReadAt = now;

                    if (read > 0 && clientOut != null) {
                        byte[] copy = Arrays.copyOf(buffer, read);
                        if (!audioQueue.offer(copy)) {
                            // A full queue means we're becoming audibly late. Catch up once,
                            // rather than continuously dropping chunks and producing little gaps.
                            audioQueue.clear();
                            audioQueue.offer(FLUSH_MARKER);
                            audioQueue.offer(copy);
                        }
                    }
                } catch (InterruptedException ignored) {
                    if (!running) break;
                } catch (Exception e) {
                    if (running) sendStatus("Audio capture stopped: " + safeMessage(e));
                    break;
                }
            }
        }, "PocketSpeaker-Capture");

        writerThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            while (running) {
                try {
                    byte[] data = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                    DataOutputStream out = clientOut;
                    if (data != null && out != null) {
                        if (data == FLUSH_MARKER || data.length == 0) {
                            out.writeInt(-1);
                            out.flush();
                        } else {
                            out.writeInt(data.length);
                            out.write(data);
                        }
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

    private void requestResync() {
        if (clientOut == null) return;
        audioQueue.clear();
        audioQueue.offer(FLUSH_MARKER);
    }

    private int fillTestTone(byte[] buffer) {
        final double frequency = 440.0;
        final double amplitude = 0.22 * Short.MAX_VALUE;
        final double step = 2.0 * Math.PI * frequency / sampleRate;
        int bytesPerFrame = 2 * channelCount;
        int frames = buffer.length / bytesPerFrame;
        int index = 0;

        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (Math.sin(tonePhase) * amplitude);
            tonePhase += step;
            if (tonePhase >= Math.PI * 2.0) tonePhase -= Math.PI * 2.0;

            for (int channel = 0; channel < channelCount; channel++) {
                buffer[index++] = (byte) (sample & 0xff);
                buffer[index++] = (byte) ((sample >>> 8) & 0xff);
            }
        }
        return index;
    }

    private synchronized void connectClient(String ip, int port) {
        closeClient();
        audioQueue.clear();
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(16384);
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
        testTone = false;
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
