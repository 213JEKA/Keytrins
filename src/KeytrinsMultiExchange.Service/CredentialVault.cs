using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using KeytrinsMultiExchange.Core;

namespace KeytrinsMultiExchange.Service;

public sealed class CredentialVault
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Keytrins.MultiExchange.Credentials.v1");
    private readonly object _gate = new();
    private readonly string _vaultPath;
    private readonly string? _keyPath;
    private Dictionary<ExchangeId, ExchangeCredentials> _stored = new();

    public CredentialVault(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        _vaultPath = Path.Combine(dataDirectory, "credentials.vault");
        _keyPath = OperatingSystem.IsWindows() ? null : Environment.GetEnvironmentVariable("KEYTRINS_SECRET_KEY_FILE");
        if (!OperatingSystem.IsWindows() && (string.IsNullOrWhiteSpace(_keyPath) || !File.Exists(_keyPath)))
            throw new InvalidOperationException("Linux credential vault requires KEYTRINS_SECRET_KEY_FILE");
        Load();
    }

    public ExchangeCredentials Get(ExchangeId exchange)
    {
        lock (_gate)
        {
            if (_stored.TryGetValue(exchange, out var stored) && stored.IsPresent) return stored;
        }
        return FromEnvironment(exchange);
    }

    public IReadOnlyDictionary<string, bool> Status() => Enum.GetValues<ExchangeId>()
        .ToDictionary(x => x.ToString(), x => Get(x).IsPresent, StringComparer.OrdinalIgnoreCase);

    public void Set(ExchangeId exchange, string apiKey, string apiSecret, string passphrase)
    {
        apiKey = apiKey.Trim(); apiSecret = apiSecret.Trim(); passphrase = passphrase.Trim();
        if (apiKey.Length < 4 || apiSecret.Length < 4) throw new ArgumentException("API key and secret are required");
        lock (_gate) { _stored[exchange] = new(apiKey, apiSecret, passphrase); SaveLocked(); }
    }

    public void Clear(ExchangeId exchange)
    {
        lock (_gate) { _stored.Remove(exchange); SaveLocked(); }
    }

    private void Load()
    {
        if (!File.Exists(_vaultPath)) return;
        var encoded = File.ReadAllBytes(_vaultPath);
        if (encoded.Length < 2) throw new CryptographicException("Credential vault is invalid");
        byte[] plaintext;
        if (encoded[0] == 1 && OperatingSystem.IsWindows())
            plaintext = ProtectedData.Unprotect(encoded[1..], Entropy, DataProtectionScope.LocalMachine);
        else if (encoded[0] == 2 && !OperatingSystem.IsWindows())
        {
            if (encoded.Length < 30) throw new CryptographicException("Credential vault is invalid");
            var nonce = encoded[1..13]; var tag = encoded[13..29]; var ciphertext = encoded[29..];
            plaintext = new byte[ciphertext.Length];
            using var aes = new AesGcm(ReadLinuxKey(), 16); aes.Decrypt(nonce, ciphertext, tag, plaintext, Entropy);
        }
        else throw new PlatformNotSupportedException("Credential vault belongs to another operating system");
        _stored = JsonSerializer.Deserialize<Dictionary<ExchangeId, ExchangeCredentials>>(plaintext) ?? new();
        CryptographicOperations.ZeroMemory(plaintext);
    }

    private void SaveLocked()
    {
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(_stored);
        byte[] encoded;
        if (OperatingSystem.IsWindows())
        {
            var protectedBytes = ProtectedData.Protect(plaintext, Entropy, DataProtectionScope.LocalMachine);
            encoded = new byte[protectedBytes.Length + 1]; encoded[0] = 1; protectedBytes.CopyTo(encoded, 1);
        }
        else
        {
            var nonce = RandomNumberGenerator.GetBytes(12); var tag = new byte[16]; var ciphertext = new byte[plaintext.Length];
            using var aes = new AesGcm(ReadLinuxKey(), 16); aes.Encrypt(nonce, plaintext, ciphertext, tag, Entropy);
            encoded = new byte[29 + ciphertext.Length]; encoded[0] = 2; nonce.CopyTo(encoded, 1); tag.CopyTo(encoded, 13); ciphertext.CopyTo(encoded, 29);
        }
        CryptographicOperations.ZeroMemory(plaintext);
        var temporary = _vaultPath + ".tmp"; File.WriteAllBytes(temporary, encoded); File.Move(temporary, _vaultPath, true);
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(_vaultPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
    }

    private byte[] ReadLinuxKey()
    {
        var key = File.ReadAllBytes(_keyPath!);
        if (key.Length != 32) throw new CryptographicException("Credential vault key must be 32 bytes");
        return key;
    }

    private static ExchangeCredentials FromEnvironment(ExchangeId exchange)
    {
        var prefix = exchange switch
        {
            ExchangeId.KuCoinFutures => "KUCOIN", ExchangeId.MexcFutures => "MEXC",
            ExchangeId.GateFutures => "GATE", ExchangeId.CoinExFutures => "COINEX",
            _ => exchange.ToString().ToUpperInvariant()
        };
        var apiKey = Environment.GetEnvironmentVariable($"KEYTRINS_{prefix}_API_KEY");
        var apiSecret = Environment.GetEnvironmentVariable($"KEYTRINS_{prefix}_API_SECRET");
        if (exchange == ExchangeId.Bybit)
        {
            apiKey ??= Environment.GetEnvironmentVariable("LR_BYBIT_API_KEY");
            apiSecret ??= Environment.GetEnvironmentVariable("LR_BYBIT_API_SECRET");
        }
        return new(apiKey ?? string.Empty, apiSecret ?? string.Empty,
            Environment.GetEnvironmentVariable($"KEYTRINS_{prefix}_PASSPHRASE") ?? string.Empty);
    }
}
