using System.Buffers.Binary;
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
    public string SourceName { get; private set; } = Environment.MachineName;

    public Sender(Action<string> status) => this.status = status;

    public void Start()
    {
        if (running) return;
        running = true;
        _ = Task.Run(ControlLoop);
        StartCapture();
        status($"Ready — waiting for phone. Source name: {SourceName}");
    }

    private void StartCapture()
    {
        capture = new WasapiLoopbackCapture();
        capture.DataAvailable += (_, e) =>
        {
            try
            {
                if (phone is null) return;
                SendCaptured(e.Buffer, e.BytesRecorded, capture.WaveFormat);
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
        WriteInt32(tcpStream, 48000);
        WriteInt32(tcpStream, 2);
        WriteInt32(tcpStream, 2);
        WriteInt32(tcpStream, SenderTransport());
        await tcpStream.FlushAsync();
        status($"Connected to phone at {ip} • STABLE TCP.");
    }

    private void SendCaptured(byte[] buffer, int count, WaveFormat fmt)
    {
        // Android expects signed 16-bit little-endian PCM. NAudio loopback is usually IEEE float,
        // so convert here and always transmit 48 kHz stereo for the first test build.
        var provider = new BufferedWaveProvider(fmt) { DiscardOnBufferOverflow = true };
        provider.AddSamples(buffer, 0, count);
        ISampleProvider samples = provider.ToSampleProvider();
        if (fmt.SampleRate != 48000) samples = new WdlResamplingSampleProvider(samples, 48000);
        if (samples.WaveFormat.Channels == 1) samples = new MonoToStereoSampleProvider(samples);
        if (samples.WaveFormat.Channels > 2) samples = new StereoSampleProvider(samples);

        float[] floats = new float[480 * 2];
        int n;
        while ((n = samples.Read(floats, 0, floats.Length)) > 0)
        {
            byte[] pcm = new byte[n * 2];
            for (int i = 0; i < n; i++)
            {
                short s = (short)Math.Clamp((int)(floats[i] * 32767f), short.MinValue, short.MaxValue);
                pcm[i * 2] = (byte)(s & 0xff);
                pcm[i * 2 + 1] = (byte)((s >> 8) & 0xff);
            }
            if (lowLatency) SendUdp(pcm);
            else SendTcp(pcm);
        }
    }

    private void SendUdp(byte[] pcm)
    {
        var target = phone;
        var sock = udp;
        if (target is null || sock is null) return;
        const int header = 28;
        const int payload = 960; // 5 ms at 48 kHz stereo 16-bit
        for (int off = 0; off < pcm.Length;)
        {
            int len = Math.Min(payload, pcm.Length - off);
            len -= len % 4;
            if (len <= 0) break;
            byte[] packet = new byte[header + len];
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(0, 4), Protocol.UdpAudioMagic);
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(4, 4), ++sequence);
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(8, 4), resyncId);
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(12, 4), 48000);
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(16, 4), 2);
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(20, 4), 2);
            BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(24, 4), SenderTransport());
            Buffer.BlockCopy(pcm, off, packet, header, len);
            sock.Send(packet, packet.Length, new IPEndPoint(target.Address, phoneAudioPort));
            off += len;
        }
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
        Text = "Pocket Speaker";
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
