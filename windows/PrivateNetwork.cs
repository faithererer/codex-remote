using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace CodexRemote;

sealed record NetworkEndpoint(IPAddress Address, string Adapter, bool Tailscale)
{
    public string Url => $"https://{Address}:48761";
    public string Label => $"{(Tailscale ? "Tailscale" : "局域网")} · {Address} · {Adapter}";
}

static class PrivateNetwork
{
    static byte[] Bytes(IPAddress? address) => address == null ? [] : (address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address).GetAddressBytes();
    public static bool IsTailscale(IPAddress? address)
    {
        var bytes = Bytes(address);
        return bytes.Length == 4 && bytes[0] == 100 && bytes[1] is >= 64 and < 128;
    }
    public static bool IsLan(IPAddress? address)
    {
        var bytes = Bytes(address);
        return bytes.Length == 4 && (bytes[0] == 10 || bytes[0] == 172 && bytes[1] is >= 16 and <= 31 || bytes[0] == 192 && bytes[1] == 168);
    }
    public static bool IsListener(IPAddress address) => address.AddressFamily == AddressFamily.InterNetwork && (IsLan(address) || IsTailscale(address));
    public static bool Allows(IPAddress? remote, IPAddress? local) => IsLan(local) ? IsLan(remote) : IsTailscale(local) && IsTailscale(remote);
    public static NetworkEndpoint[] Endpoints()
    {
        var result = new List<NetworkEndpoint>();
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up))
        {
            bool tailscale = adapter.Name.Contains("tailscale", StringComparison.OrdinalIgnoreCase) || adapter.Description.Contains("tailscale", StringComparison.OrdinalIgnoreCase);
            if (!tailscale && adapter.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.FastEthernetT)) continue;
            foreach (var entry in adapter.GetIPProperties().UnicastAddresses)
                if (entry.Address.AddressFamily == AddressFamily.InterNetwork && (tailscale ? IsTailscale(entry.Address) : IsLan(entry.Address)))
                    result.Add(new(entry.Address, adapter.Name, tailscale));
        }
        return result.DistinctBy(e => e.Address).OrderBy(e => e.Tailscale).ThenBy(e => e.Adapter).ThenBy(e => e.Address.ToString()).ToArray();
    }
}
