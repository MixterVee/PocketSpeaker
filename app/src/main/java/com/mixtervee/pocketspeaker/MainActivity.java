package com.mixtervee.pocketspeaker;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 1001;
    private static final int REQUEST_CAPTURE = 1002;

    private boolean isTv;
    private TextView statusText;
    private LinearLayout deviceList;
    private volatile boolean discovering;
    private DatagramSocket discoverySocket;
    private Thread discoveryThread;
    private final Map<String, Button> deviceButtons = new LinkedHashMap<>();

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null && statusText != null) statusText.setText(status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        isTv = detectTv();
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(CaptureService.ACTION_STATUS);
        filter.addAction(ReceiverService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        if (!isTv) startDiscovery();
    }

    @Override
    protected void onStop() {
        if (!isTv) stopDiscovery();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    private boolean detectTv() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        boolean tvMode = uiModeManager != null &&
                uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        return tvMode || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 16, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(isTv ? 36 : 24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(isTv ? "Pocket Speaker — TV" : "Pocket Speaker");
        title.setTextColor(Color.WHITE);
        title.setTextSize(isTv ? 32 : 28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(isTv
                ? "Send this TV's audio to your phone over the local network."
                : "Choose an Android TV device and use this phone as its speaker.");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(isTv ? 18 : 16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(12), 0, dp(18));
        root.addView(subtitle, matchWrap());

        statusText = new TextView(this);
        statusText.setText(isTv ? "Ready." : "Looking for TVs…");
        statusText.setTextColor(Color.rgb(120, 220, 170));
        statusText.setTextSize(isTv ? 20 : 17);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(8), dp(8), dp(8), dp(20));
        root.addView(statusText, matchWrap());

        if (isTv) {
            Button start = makeButton("START TV AUDIO");
            start.setOnClickListener(v -> beginTvCapture());
            root.addView(start, buttonParams());

            Button stop = makeButton("STOP");
            stop.setOnClickListener(v -> {
                stopService(new Intent(this, CaptureService.class));
                statusText.setText("Stopped.");
            });
            root.addView(stop, buttonParams());
        } else {
            deviceList = new LinearLayout(this);
            deviceList.setOrientation(LinearLayout.VERTICAL);
            root.addView(deviceList, matchWrap());

            Button stop = makeButton("STOP LISTENING");
            stop.setOnClickListener(v -> {
                Intent intent = new Intent(this, ReceiverService.class);
                intent.setAction(ReceiverService.ACTION_STOP);
                startService(intent);
                statusText.setText("Stopped. Choose a TV to connect again.");
            });
            root.addView(stop, buttonParams());
        }

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(isTv ? 72 : 62));
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(isTv ? 20 : 17);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void beginTvCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            statusText.setText("Media projection is not available on this device.");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestProjection();
        } else if (requestCode == REQUEST_AUDIO) {
            statusText.setText("Audio permission is required.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            statusText.setText("TV audio sharing was not started.");
            return;
        }

        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_START);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        statusText.setText("Starting TV audio capture…");
    }

    private void startDiscovery() {
        if (discovering) return;
        discovering = true;
        discoveryThread = new Thread(() -> {
            try {
                discoverySocket = new DatagramSocket();
                discoverySocket.setBroadcast(true);
                discoverySocket.setSoTimeout(450);
                byte[] discovery = NetworkProtocol.DISCOVER.getBytes(StandardCharsets.UTF_8);

                while (discovering) {
                    for (InetAddress address : NetworkProtocol.broadcastAddresses()) {
                        try {
                            discoverySocket.send(new DatagramPacket(
                                    discovery, discovery.length, address, NetworkProtocol.CONTROL_PORT));
                        } catch (Exception ignored) {
                        }
                    }

                    long until = System.currentTimeMillis() + 1200;
                    while (discovering && System.currentTimeMillis() < until) {
                        try {
                            byte[] buffer = new byte[512];
                            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                            discoverySocket.receive(packet);
                            String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                            if (msg.startsWith(NetworkProtocol.SENDER_PREFIX)) {
                                String name = msg.substring(NetworkProtocol.SENDER_PREFIX.length()).trim();
                                if (name.isEmpty()) name = "Android TV";
                                String ip = packet.getAddress().getHostAddress();
                                addDiscoveredDevice(name, ip);
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                        } catch (Exception ignored) {
                        }
                    }

                    try {
                        Thread.sleep(650);
                    } catch (InterruptedException ignored) {
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Could not scan the local network."));
            } finally {
                if (discoverySocket != null) discoverySocket.close();
                discoverySocket = null;
            }
        }, "PocketSpeaker-Discovery");
        discoveryThread.start();
    }

    private void stopDiscovery() {
        discovering = false;
        if (discoverySocket != null) discoverySocket.close();
        if (discoveryThread != null) discoveryThread.interrupt();
        discoveryThread = null;
    }

    private void addDiscoveredDevice(String name, String ip) {
        runOnUiThread(() -> {
            if (deviceButtons.containsKey(ip)) return;
            statusText.setText("TV found. Tap it to listen.");
            Button button = makeButton(name + "   •   " + ip);
            button.setOnClickListener(v -> connectToSender(name, ip));
            deviceButtons.put(ip, button);
            deviceList.addView(button, buttonParams());
        });
    }

    private void connectToSender(String name, String ip) {
        Intent intent = new Intent(this, ReceiverService.class);
        intent.setAction(ReceiverService.ACTION_CONNECT);
        intent.putExtra(ReceiverService.EXTRA_SENDER_IP, ip);
        intent.putExtra(ReceiverService.EXTRA_SENDER_NAME, name);
        startForegroundService(intent);
        statusText.setText("Connecting to " + name + "…");
    }
}
