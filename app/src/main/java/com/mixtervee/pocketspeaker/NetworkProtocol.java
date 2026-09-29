package com.mixtervee.pocketspeaker;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NetworkProtocol {
    static final int CONTROL_PORT = 50005;
    static final int STREAM_PORT = 50008;
    static final int RESYNC_PORT = 50006;
    static final int STREAM_MAGIC = 0x50534B33; // "PSK3" - framed audio + out-of-band resync

    static final String DISCOVER = "PS1|DISCOVER";
    static final String SENDER_PREFIX = "PS1|SENDER|";
    static final String CONNECT_PREFIX = "PS2|CONNECT|";
    static final String RESYNC_PREFIX = "PS2|RESYNC|";

    private NetworkProtocol() {}

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (address instanceof Inet4Address && broadcast != null) {
                        result.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            InetAddress global = InetAddress.getByName("255.255.255.255");
            if (!result.contains(global)) result.add(global);
        } catch (Exception ignored) {
        }
        return result;
    }
}
