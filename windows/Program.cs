using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using NAudio.Wave;

namespace PocketSpeaker.Windows;

internal static class Protocol
{
    public const int ControlPort = 50005;
    public const int StreamPort = 50008;
    public const int ResyncPort = 50006;
    public const int UdpAudioPort = 50009;
    public const int StreamMagic = 0x50534B34;
    public const int UdpAudioMagic = 0x50535531;
    public const string Discover = "PS1|DISCOVER";
    public const string SenderPrefix = "PS1|SENDER|";
    public const string ConnectPrefix = "PS2|CONNECT|";
    public const string ResyncPrefix = "PS2|RESYNC|";
    public const string RenamePrefix = "PS2|RENAME|";
}

internal sealed class Sender : IDisposable
{
    private readonly Action<string> status;
    private readonly object gate = new();
    private readonly ConcurrentQueue<byte[]> udpQueue = new();
    private readonly AutoResetEvent udpReady = new(false);
    private int udpQueuedPackets;
    private byte[] udpPending = new byte[4096];
    private int udpPendingCount;
    private int senderTransport;
    private UdpClient? control;
    private UdpClient? udp;
    private TcpClient? tcp;
    private NetworkStream? tcpStream;
    private WasapiRecorder? capture;
    private IPEndPoint? phone;
    private int phoneAudioPort = Protocol.UdpAudioPort;
    private int phoneResyncPort = Protocol.ResyncPort;
    private bool lowLatency;
    private int sequence;
    private int resyncId;
    private bool running;
    private int captureSampleRate = 48000;
    private int captureChannels = 2;
    private int captureBits = 32;
    private WaveFormatEncoding captureEncoding = WaveFormatEncoding.IeeeFloat;
    private long packetsSent;
    private bool firstAudioReported;
    public string SourceName { get; private set; } = Environment.MachineName;

    public Sender(Action<string> status) => this.status = status;

    public void Start()
    {
        if (running) return;
        running = true;
        _ = Task.Run(ControlLoop);
        var udpThread = new Thread(UdpSendLoop)
        {
            IsBackground = true,
            Name = "PocketSpeaker-UDP",
            Priority = ThreadPriority.Highest
        };
        udpThread.Start();
        StartCapture();
        status($"Ready — waiting for phone. Source name: {SourceName}");
    }

    private void StartCapture()
    {
        capture = new WasapiRecorderBuilder()
            .WithLoopbackCapture()
            .WithEventSync()
            .WithBufferLength(10)
            .WithMmcssThreadPriority("Pro Audio")
            .Build();

        captureSampleRate = capture.WaveFormat.SampleRate;
        captureChannels = Math.Max(1, capture.WaveFormat.Channels);
        captureBits = capture.WaveFormat.BitsPerSample;
        captureEncoding = capture.WaveFormat.Encoding;

        capture.DataAvailable += (buffer, flags, devicePosition, qpcPosition) =>
        {
            try
            {
                if (phone is null || buffer.Length <= 0) return;
                byte[] raw = buffer.ToArray();
                byte[] pcm = ConvertToPcm16(raw, raw.Length, capture.WaveFormat);
                if (pcm.Length == 0) return;

                if (lowLatency) QueueUdpPcm(pcm);
                else SendTcp(pcm);

                if (!firstAudioReported)
                {
                    firstAudioReported = true;
                    int callbackMs = Math.Max(1, (pcm.Length * 1000) /
                        Math.Max(1, captureSampleRate * Math.Min(captureChannels, 2) * 2));
                    status($"Audio streaming • {captureSampleRate} Hz • {Math.Min(captureChannels, 2)} ch • " +
                           (lowLatency ? $"LOW LATENCY UDP • capture chunk {callbackMs} ms • 7 ms packets" : "STABLE TCP"));
                }
            }
            catch (Exception ex)
            {
                status("Audio send error: " + ex.Message);
            }
        };

        capture.RecordingStopped += (_, e) =>
        {
            if (e.Exception != null) status("Capture stopped: " + e.Exception.Message);
        };

        capture.StartRecording();
        status($"Ready — waiting for phone. Capturing {captureSampleRate} Hz / {captureChannels} ch • 10 ms WASAPI buffer.");
    }

    private static byte[] ConvertToPcm16(byte[] input, int count, WaveFormat fmt)
    {
        int sourceChannels = Math.Max(1, fmt.Channels);
        int outputChannels = Math.Min(sourceChannels, 2);
        int bytesPerSample = Math.Max(1, fmt.BitsPerSample / 8);
        int bytesPerFrame = bytesPerSample * sourceChannels;
        if (bytesPerFrame <= 0) return Array.Empty<byte>();

        int frames = count / bytesPerFrame;
        if (frames <= 0) return Array.Empty<byte>();

        byte[] output = new byte[frames * outputChannels * 2];
        int outIndex = 0;

        bool float32 = fmt.BitsPerSample == 32 &&
                       (fmt.Encoding == WaveFormatEncoding.IeeeFloat ||
                        fmt.Encoding == WaveFormatEncoding.Extensible);

        for (int frame = 0; frame < frames; frame++)
        {
            int frameBase = frame * bytesPerFrame;
            for (int ch = 0; ch < outputChannels; ch++)
            {
                int p = frameBase + ch * bytesPerSample;
                short sample;

                if (float32)
                {
                    float v = BitConverter.ToSingle(input, p);
                    if (float.IsNaN(v) || float.IsInfinity(v)) v = 0;
                    v = Math.Clamp(v, -1f, 1f);
                    sample = (short)Math.Clamp((int)(v * 32767f), short.MinValue, short.MaxValue);
                }
                else if (fmt.BitsPerSample == 16)
                {
                    sample = (short)(input[p] | (input[p + 1] << 8));
                }
                else if (fmt.BitsPerSample == 24)
                {
                    int v = input[p] | (input[p + 1] << 8) | (input[p + 2] << 16);
                    if ((v & 0x800000) != 0) v |= unchecked((int)0xff000000);
                    sample = (short)(v >> 8);
                }
                else if (fmt.BitsPerSample == 32)
                {
                    int v = BitConverter.ToInt32(input, p);
                    sample = (short)(v >> 16);
                }
                else
                {
                    sample = 0;
                }

                output[outIndex++] = (byte)(sample & 0xff);
                output[outIndex++] = (byte)((sample >> 8) & 0xff);
            }
        }
        return output;
    }

    private async Task ControlLoop()
    {
        control = new UdpClient(new IPEndPoint(IPAddress.Any, Protocol.ControlPort));
        control.EnableBroadcast = true;
        while (running)
        {
            UdpReceiveResult r;
            try { r = await control.ReceiveAsync(); }
            catch { if (!running) break; else continue; }

            string msg = Encoding.UTF8.GetString(r.Buffer);
            if (msg == Protocol.Discover)
            {
                byte[] reply = Encoding.UTF8.GetBytes(Protocol.SenderPrefix + SourceName);
                await control.SendAsync(reply, reply.Length, r.RemoteEndPoint);
            }
            else if (msg.StartsWith(Protocol.RenamePrefix, StringComparison.Ordinal))
            {
                string name = msg[Protocol.RenamePrefix.Length..].Replace("\n", " ").Trim();
                if (name.Length > 32) name = name[..32].Trim();
                SourceName = string.IsNullOrWhiteSpace(name) ? Environment.MachineName : name;
                byte[] reply = Encoding.UTF8.GetBytes(Protocol.SenderPrefix + SourceName);
                await control.SendAsync(reply, reply.Length, r.RemoteEndPoint);
                status($"Renamed to “{SourceName}”.");
            }
            else if (msg.StartsWith(Protocol.ConnectPrefix, StringComparison.Ordinal))
            {
                string payload = msg[Protocol.ConnectPrefix.Length..].Trim();
                string[] parts = payload.Split('|');
                int streamPort = parts.Length > 0 && int.TryParse(parts[0], out var sp) ? sp : Protocol.StreamPort;
                phoneResyncPort = parts.Length > 1 && int.TryParse(parts[1], out var rp) ? rp : Protocol.ResyncPort;
                phoneAudioPort = parts.Length > 2 && int.TryParse(parts[2], out var ap) ? ap : Protocol.UdpAudioPort;
                bool requestedLowLatency =
                    parts.Length > 3 && parts[3].Equals("UDP", StringComparison.OrdinalIgnoreCase);
                bool sameUdpClient = requestedLowLatency
                    && lowLatency
                    && udp != null
                    && phone != null
                    && phone.Address.Equals(r.RemoteEndPoint.Address);

                lowLatency = requestedLowLatency;
                phone = new IPEndPoint(r.RemoteEndPoint.Address, lowLatency ? phoneAudioPort : streamPort);
                senderTransport = DetermineSenderTransport(r.RemoteEndPoint.Address);
                sequence = 0;
                resyncId = 0;
                firstAudioReported = false;
                packetsSent = 0;
                ClearUdpQueue();

                if (lowLatency)
                {
                    tcp?.Dispose();
                    tcp = null;
                    tcpStream = null;

                    // Repeated taps from the phone are harmless now. Keep a healthy UDP
                    // socket instead of closing/reopening it in the middle of streaming.
                    if (!sameUdpClient)
                    {
                        udp?.Dispose();
                        udp = new UdpClient();
                        udp.Client.SendBufferSize = 131072;
                    }

                    status($"Connected to phone at {r.RemoteEndPoint.Address} • LOW LATENCY UDP.");
                    udpReady.Set();
                }
                else
                {
                    udp?.Dispose();
                    udp = null;
                    await ConnectTcp(r.RemoteEndPoint.Address, streamPort);
                }
            }
        }
    }

    private async Task ConnectTcp(IPAddress ip, int port)
    {
        tcp?.Dispose();
        tcp = new TcpClient { NoDelay = true, SendBufferSize = 16384 };
        await tcp.ConnectAsync(ip, port);
        tcpStream = tcp.GetStream();
        phone = new IPEndPoint(ip, port);
        WriteInt32(tcpStream, Protocol.StreamMagic);
        WriteInt32(tcpStream, captureSampleRate);
        WriteInt32(tcpStream, Math.Min(captureChannels, 2));
        WriteInt32(tcpStream, 2);
        WriteInt32(tcpStream, senderTransport);
        await tcpStream.FlushAsync();
        status($"Connected to phone at {ip} • STABLE TCP.");
    }

    private void QueueUdpPcm(byte[] pcm)
    {
        int channels = Math.Min(captureChannels, 2);
        int bytesPerFrame = Math.Max(2, channels * 2);
        // Use ~7 ms packets now that pacing is fixed. This lowers packet rate by
        // roughly 30% versus 5 ms packets while still keeping each datagram safely
        // below the phone receiver's packet buffer.
        int desiredPayload = Math.Max(bytesPerFrame,
            (captureSampleRate * bytesPerFrame * 7) / 1000); // target about 7 ms
        int payload = Math.Min(1400, desiredPayload);
        payload -= payload % bytesPerFrame;
        if (payload <= 0) return;

        int needed = udpPendingCount + pcm.Length;
        if (udpPending.Length < needed)
            Array.Resize(ref udpPending, Math.Max(needed, udpPending.Length * 2));

        Buffer.BlockCopy(pcm, 0, udpPending, udpPendingCount, pcm.Length);
        udpPendingCount += pcm.Length;

        int offset = 0;
        while (udpPendingCount - offset >= payload)
        {
            byte[] chunk = new byte[payload];
            Buffer.BlockCopy(udpPending, offset, chunk, 0, payload);
            udpQueue.Enqueue(chunk);
            Interlocked.Increment(ref udpQueuedPackets);
            offset += payload;
        }

        if (offset > 0)
        {
            int remaining = udpPendingCount - offset;
            if (remaining > 0)
                Buffer.BlockCopy(udpPending, offset, udpPending, 0, remaining);
            udpPendingCount = remaining;
        }

        // Beta 16 starts from Beta 14's proven sender behavior. Beta 15's tighter
        // 8 -> 4 packet stale-audio trim sounded worse in real-world testing, so
        // keep the safer ~85 ms ceiling and trim only after a genuine scheduling
        // stall, back to roughly ~40 ms.
        if (Volatile.Read(ref udpQueuedPackets) > 12)
        {
            while (Volatile.Read(ref udpQueuedPackets) > 6 && udpQueue.TryDequeue(out _))
                Interlocked.Decrement(ref udpQueuedPackets);
        }

        udpReady.Set();
    }

    private void UdpSendLoop()
    {
        long frequency = Stopwatch.Frequency;
        long next = 0;

        while (running)
        {
            if (!lowLatency || phone is null || udp is null)
            {
                ClearUdpQueue();
                udpReady.WaitOne(20);
                next = 0;
                continue;
            }

            if (!udpQueue.TryDequeue(out byte[]? chunk))
            {
                udpReady.WaitOne(10);
                next = 0;
                continue;
            }
            Interlocked.Decrement(ref udpQueuedPackets);

            int channels = Math.Min(captureChannels, 2);
            int bytesPerSecond = Math.Max(1, captureSampleRate * channels * 2);
            long packetTicks = Math.Max(1,
                (long)(frequency * (chunk.Length / (double)bytesPerSecond)));

            long now = Stopwatch.GetTimestamp();
            if (next == 0)
                next = now;

            // Pace against an absolute deadline. The previous build scheduled the
            // next packet from "now" after every send; tiny scheduler/send overhead
            // accumulated on every packet and starved the phone continuously.
            // If Windows is genuinely late by more than two packet periods, reset
            // once instead of blasting a catch-up burst.
            if (now - next > packetTicks * 2)
                next = now;

            while (running)
            {
                now = Stopwatch.GetTimestamp();
                long remain = next - now;
                if (remain <= 0) break;

                double ms = remain * 1000.0 / frequency;
                if (ms > 2.0)
                    Thread.Sleep(1);
                else if (ms > 0.4)
                    Thread.Yield();
                else
                    Thread.SpinWait(80);
            }

            try
            {
                SendUdpPacket(chunk);
            }
            catch (Exception ex)
            {
                status("UDP send error: " + ex.Message);
            }

            next += packetTicks;
        }
    }

    private void SendUdpPacket(byte[] pcm)
    {
        var target = phone;
        var sock = udp;
        if (target is null || sock is null || pcm.Length == 0) return;

        int channels = Math.Min(captureChannels, 2);
        byte[] packet = new byte[28 + pcm.Length];
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(0, 4), Protocol.UdpAudioMagic);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(4, 4), ++sequence);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(8, 4), resyncId);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(12, 4), captureSampleRate);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(16, 4), channels);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(20, 4), 2);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(24, 4), senderTransport);
        Buffer.BlockCopy(pcm, 0, packet, 28, pcm.Length);
        sock.Send(packet, packet.Length, new IPEndPoint(target.Address, phoneAudioPort));
        packetsSent++;
    }

    private void ClearUdpQueue()
    {
        while (udpQueue.TryDequeue(out _)) { }
        Interlocked.Exchange(ref udpQueuedPackets, 0);
        udpPendingCount = 0;
    }

    private void SendTcp(byte[] pcm)
    {
        lock (gate)
        {
            if (tcpStream is null) return;
            WriteInt32(tcpStream, pcm.Length);
            tcpStream.Write(pcm, 0, pcm.Length);
        }
    }

    private static void WriteInt32(Stream s, int value)
    {
        Span<byte> b = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(b, value);
        s.Write(b);
    }

    private static int DetermineSenderTransport(IPAddress remoteAddress)
    {
        try
        {
            IPAddress? localAddress = null;
            using (var probe = new UdpClient(remoteAddress.AddressFamily))
            {
                probe.Connect(remoteAddress, Protocol.ControlPort);
                localAddress = (probe.Client.LocalEndPoint as IPEndPoint)?.Address;
            }

            if (localAddress != null)
            {
                foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != OperationalStatus.Up) continue;
                    bool ownsAddress = nic.GetIPProperties().UnicastAddresses
                        .Any(u => u.Address.Equals(localAddress));
                    if (!ownsAddress) continue;

                    if (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211) return 1;
                    if (nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet) return 2;
                }
            }

            // Conservative fallback: prefer Wi-Fi if an active Wi-Fi interface exists.
            // This gives the phone the slightly larger Wi-Fi cushion instead of misclassifying
            // VPN/virtual Ethernet adapters as the real path.
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                if (nic.OperationalStatus == OperationalStatus.Up &&
                    nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
                    return 1;

            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                if (nic.OperationalStatus == OperationalStatus.Up &&
                    nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet)
                    return 2;
        }
        catch { }
        return 0;
    }

    public void Dispose()
    {
        running = false;
        udpReady.Set();
        ClearUdpQueue();
        try { capture?.StopRecording(); } catch { }
        capture?.Dispose();
        control?.Dispose();
        udp?.Dispose();
        tcpStream?.Dispose();
        tcp?.Dispose();
    }
}

internal sealed class MainForm : Form
{
    private readonly Label status = new() { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 11), Padding = new Padding(20) };
    private readonly Sender sender;

    public MainForm()
    {
        Text = "Pocket Speaker — Beta 31";
        Width = 520;
        Height = 260;
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(status);
        sender = new Sender(s => BeginInvoke(() => status.Text = s));
        Shown += (_, _) => sender.Start();
        FormClosed += (_, _) => sender.Dispose();
    }
}

internal static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

