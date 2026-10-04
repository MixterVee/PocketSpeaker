package com.mixtervee.pocketspeaker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
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
    private TextView audioModeText;
    private Button testToneButton;
    private Button latencyModeButton;
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
            if (isTv && testToneButton != null && intent.hasExtra("testTone")) {
                boolean active = intent.getBooleanExtra("testTone", false);
                testToneButton.setText(active
                        ? "STOP CONNECTION TONE"
                        : "START TEST CONNECTION TONE");
            }
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
        title.setText(isTv ? "Pocket Speaker — Beta 64 — TV" : "Pocket Speaker — Beta 64");
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

            Button rename = makeButton("RENAME THIS TV");
            rename.setOnClickListener(v -> showRenameDialog());
            root.addView(rename, buttonParams());

            testToneButton = makeButton("START TEST CONNECTION TONE");
            testToneButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, CaptureService.class);
                intent.setAction(CaptureService.ACTION_TOGGLE_TEST_TONE);
                startService(intent);
            });
            root.addView(testToneButton, buttonParams());

            Button stop = makeButton("STOP");
            stop.setOnClickListener(v -> {
                stopService(new Intent(this, CaptureService.class));
                statusText.setText("Stopped.");
            });
            root.addView(stop, buttonParams());
        } else {
            audioModeText = new TextView(this);
            audioModeText.setTextSize(18);
            audioModeText.setGravity(Gravity.CENTER);
            audioModeText.setPadding(0, dp(4), 0, dp(8));
            root.addView(audioModeText, matchWrap());

            latencyModeButton = makeButton("");
            updateLatencyModeUi();
            latencyModeButton.setOnClickListener(v -> {
                boolean enabled = !isLowLatencyEnabled();
                getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                        .edit().putBoolean("low_latency_mode", enabled).apply();
                updateLatencyModeUi();
                statusText.setText(enabled
                        ? "Low latency UDP selected. Tap a TV to test it."
                        : "Stable TCP selected. Tap a TV to connect.");
            });
            root.addView(latencyModeButton, buttonParams());

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
    private void showRenameDialog() {
        String current = CaptureService.getSourceName(this);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelectAllOnFocus(true);
        input.setHint(Build.MODEL);
        input.setPadding(dp(18), dp(12), dp(18), dp(12));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename this TV")
                .setMessage("This is the name that will appear on your phone.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText() == null
                            ? "" : input.getText().toString().replace("\n", " ").trim();
                    if (name.length() > 32) name = name.substring(0, 32).trim();

                    if (name.isEmpty()) {
                        getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                                .edit().remove(CaptureService.PREF_SOURCE_NAME).apply();
                        name = Build.MODEL;
                    } else {
                        getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                                .edit().putString(CaptureService.PREF_SOURCE_NAME, name).apply();
                    }

                    statusText.setText("This TV is now named “" + name + "”.");
                    dialog.dismiss();
                }));

        dialog.getWindow();
        dialog.show();
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private boolean isLowLatencyEnabled() {
        return getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                .getBoolean("low_latency_mode", true);
    }

    private void updateLatencyModeUi() {
        boolean lowLatency = isLowLatencyEnabled();

        if (audioModeText != null) {
            audioModeText.setText(lowLatency
                    ? "CURRENT MODE\nLOW LATENCY (UDP)"
                    : "CURRENT MODE\nSTABLE (TCP)");
            audioModeText.setTextColor(lowLatency
                    ? Color.rgb(120, 220, 170)
                    : Color.LTGRAY);
        }

        if (latencyModeButton != null) {
            latencyModeButton.setText(lowLatency
                    ? "SWITCH TO STABLE (TCP)"
                    : "SWITCH TO LOW LATENCY (UDP)");
        }
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
            Button existing = deviceButtons.get(ip);
            if (existing != null) {
                existing.setTag(name);
                existing.setText(sourceButtonLabel(name, ip));
                return;
            }

            statusText.setText("TV found. Tap it to listen.");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            Button button = makeButton(sourceButtonLabel(name, ip));
            button.setTag(name);
            button.setOnClickListener(v ->
                    connectToSender(String.valueOf(button.getTag()), ip));

            LinearLayout.LayoutParams connectParams =
                    new LinearLayout.LayoutParams(0, dp(62), 1f);
            connectParams.setMargins(0, dp(6), dp(6), dp(6));
            row.addView(button, connectParams);

            Button rename = makeButton("RENAME");
            rename.setTextSize(14);
            rename.setOnClickListener(v ->
                    showRemoteRenameDialog(String.valueOf(button.getTag()), ip, button));

            LinearLayout.LayoutParams renameParams =
                    new LinearLayout.LayoutParams(dp(104), dp(62));
            renameParams.setMargins(dp(6), dp(6), 0, dp(6));
            row.addView(rename, renameParams);

            deviceButtons.put(ip, button);

            String lastIp = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                    .getString("last_source_ip", "");
            if (ip.equals(lastIp)) {
                deviceList.addView(row, 0, matchWrap());
            } else {
                deviceList.addView(row, matchWrap());
            }
        });
    }

    private String sourceButtonLabel(String name, String ip) {
        String lastIp = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                .getString("last_source_ip", "");
        return (ip.equals(lastIp) ? "LAST • " : "") + name + "   •   " + ip;
    }

    private void showRemoteRenameDialog(String currentName, String ip, Button sourceButton) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(18), dp(12), dp(18), dp(12));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename source")
                .setMessage("This name is saved on the TV device.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText() == null
                            ? "" : input.getText().toString().replace("\n", " ").trim();
                    if (name.length() > 32) name = name.substring(0, 32).trim();

                    sendRenameToSource(ip, name, sourceButton);
                    dialog.dismiss();
                }));

        dialog.show();
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void sendRenameToSource(String ip, String requestedName, Button sourceButton) {
        statusText.setText("Renaming source…");

        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1200);
                String message = NetworkProtocol.RENAME_PREFIX + requestedName;
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(
                        bytes, bytes.length,
                        InetAddress.getByName(ip), NetworkProtocol.CONTROL_PORT));

                byte[] replyBuffer = new byte[512];
                DatagramPacket reply = new DatagramPacket(replyBuffer, replyBuffer.length);
                socket.receive(reply);

                String response = new String(
                        reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
                if (response.startsWith(NetworkProtocol.SENDER_PREFIX)) {
                    String savedName =
                            response.substring(NetworkProtocol.SENDER_PREFIX.length()).trim();
                    if (savedName.isEmpty()) savedName = "Android TV";

                    final String finalName = savedName;
                    runOnUiThread(() -> {
                        sourceButton.setTag(finalName);
                        sourceButton.setText(sourceButtonLabel(finalName, ip));

                        String lastIp = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                                .getString("last_source_ip", "");
                        if (ip.equals(lastIp)) {
                            getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                                    .edit().putString("last_source_name", finalName).apply();
                        }

                        statusText.setText("Source renamed to “" + finalName + "”.");
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() ->
                        statusText.setText("Rename sent. The source name should update shortly."));
            }
        }, "PocketSpeaker-Rename").start();
    }

    private void connectToSender(String name, String ip) {
        getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
                .edit()
                .putString("last_source_ip", ip)
                .putString("last_source_name", name)
                .apply();

        Intent intent = new Intent(this, ReceiverService.class);
        intent.setAction(ReceiverService.ACTION_CONNECT);
        boolean lowLatency = isLowLatencyEnabled();
        intent.putExtra(ReceiverService.EXTRA_SENDER_IP, ip);
        intent.putExtra(ReceiverService.EXTRA_SENDER_NAME, name);
        intent.putExtra(ReceiverService.EXTRA_LOW_LATENCY, lowLatency);
        startForegroundService(intent);
        statusText.setText("Connecting to " + name
                + (lowLatency ? " in low latency mode…" : " in stable mode…"));
    }
}
