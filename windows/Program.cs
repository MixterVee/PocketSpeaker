using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

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
    private UdpClient? control;
    private UdpClient? udp;
    private TcpClient? tcp;
    private NetworkStream? tcpStream;
    private WasapiLoopbackCapture? capture;
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
        _ = Task.Run(UdpSendLoop);
        StartCapture();
        status($"Ready — waiting for phone. Source name: {SourceName}");
    }

    private void StartCapture()
    {
        capture = new WasapiLoopbackCapture();
        captureSampleRate = capture.WaveFormat.SampleRate;
        captureChannels = Math.Max(1, capture.WaveFormat.Channels);
        captureBits = capture.WaveFormat.BitsPerSample;
        captureEncoding = capture.WaveFormat.Encoding;

        capture.DataAvailable += (_, e) =>
        {
            try
            {
                if (phone is null || e.BytesRecorded <= 0) return;
                byte[] pcm = ConvertToPcm16(e.Buffer, e.BytesRecorded, capture.WaveFormat);
                if (pcm.Length == 0) return;

                if (lowLatency) QueueUdpPcm(pcm);
                else SendTcp(pcm);

                if (!firstAudioReported)
                {
                    firstAudioReported = true;
                    int callbackMs = Math.Max(1, (pcm.Length * 1000) /
                        Math.Max(1, captureSampleRate * Math.Min(captureChannels, 2) * 2));
                    status($"Audio streaming • {captureSampleRate} Hz • {Math.Min(captureChannels, 2)} ch • " +
                           (lowLatency ? $"LOW LATENCY UDP • WASAPI chunk {callbackMs} ms" : "STABLE TCP"));
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
        status($"Ready — waiting for phone. Capturing {captureSampleRate} Hz / {captureChannels} ch.");
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
                lowLatency = parts.Length > 3 && parts[3].Equals("UDP", StringComparison.OrdinalIgnoreCase);
                phone = new IPEndPoint(r.RemoteEndPoint.Address, lowLatency ? phoneAudioPort : streamPort);
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
                    udp?.Dispose();
                    udp = new UdpClient();
                    status($"Connected to phone at {r.RemoteEndPoint.Address} • LOW LATENCY UDP.");
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
        WriteInt32(tcpStream, SenderTransport());
        await tcpStream.FlushAsync();
        status($"Connected to phone at {ip} • STABLE TCP.");
    }

    private void QueueUdpPcm(byte[] pcm)
    {
        int channels = Math.Min(captureChannels, 2);
        int bytesPerFrame = Math.Max(2, channels * 2);
        int payload = Math.Max(bytesPerFrame, (captureSampleRate * bytesPerFrame) / 200); // ~5 ms
        payload -= payload % bytesPerFrame;
        if (payload <= 0) return;

        for (int off = 0; off < pcm.Length;)
        {
            int len = Math.Min(payload, pcm.Length - off);
            len -= len % bytesPerFrame;
            if (len <= 0) break;

            byte[] chunk = new byte[len];
            Buffer.BlockCopy(pcm, off, chunk, 0, len);
            udpQueue.Enqueue(chunk);
            Interlocked.Increment(ref udpQueuedPackets);
            off += len;
        }

        // Never let Windows build a large hidden delay. If scheduling ever falls behind
        // by more than about 150 ms, jump forward to roughly 60 ms of fresh audio.
        if (Volatile.Read(ref udpQueuedPackets) > 30)
        {
            while (Volatile.Read(ref udpQueuedPackets) > 12 && udpQueue.TryDequeue(out _))
                Interlocked.Decrement(ref udpQueuedPackets);
        }

        udpReady.Set();
    }

    private async Task UdpSendLoop()
    {
        long frequency = Stopwatch.Frequency;
        long packetTicks = Math.Max(1, frequency / 200); // 5 ms
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

            long now = Stopwatch.GetTimestamp();
            if (next == 0 || now - next > frequency / 10)
                next = now;

            while (running)
            {
                now = Stopwatch.GetTimestamp();
                long remain = next - now;
                if (remain <= 0) break;

                double ms = remain * 1000.0 / frequency;
                if (ms > 2.0)
                    Thread.Sleep(1);
                else
                    Thread.SpinWait(100);
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
            await Task.Yield();
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
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(24, 4), SenderTransport());
        Buffer.BlockCopy(pcm, 0, packet, 28, pcm.Length);
        try
        {
            sock.Send(packet, packet.Length, new IPEndPoint(target.Address, phoneAudioPort));
        }
        catch (ObjectDisposedException)
        {
            // Reconnect can replace the UDP socket while the sender thread is in flight.
            // Drop only this stale packet; preserve historical pacing behavior.
            return;
        }
        packetsSent++;
    }

    private void ClearUdpQueue()
    {
        while (udpQueue.TryDequeue(out _)) { }
        Interlocked.Exchange(ref udpQueuedPackets, 0);
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

    private static int SenderTransport()
    {
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet) return 2;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211) return 1;
            }
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
        Text = "Pocket Speaker — Beta5 USB W2";
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

internal sealed class StereoSampleProvider : ISampleProvider
{
    private readonly ISampleProvider source;
    public StereoSampleProvider(ISampleProvider source) => this.source = source;
    public WaveFormat WaveFormat => WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, 2);

    public int Read(float[] buffer, int offset, int count)
    {
        int srcChannels = source.WaveFormat.Channels;
        float[] temp = new float[(count / 2) * srcChannels];
        int read = source.Read(temp, 0, temp.Length);
        int frames = read / srcChannels;
        for (int f = 0; f < frames; f++)
        {
            buffer[offset + f * 2] = temp[f * srcChannels];
            buffer[offset + f * 2 + 1] = temp[f * srcChannels + Math.Min(1, srcChannels - 1)];
        }
        return frames * 2;
    }
}
