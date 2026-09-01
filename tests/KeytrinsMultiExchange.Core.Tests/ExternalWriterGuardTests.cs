using System.Globalization;
using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class ExternalWriterGuardTests
{
    [Fact]
    public async Task Recent_foreign_entry_blocks_exclusivity()
    {
        var source = new AuditSource([Row("MU-USDT-SWAP", "OXMU123", false, DateTimeOffset.UtcNow)]);
        var result = await new ExternalWriterGuard(source).CheckAsync(default);
        Assert.False(result.IsExclusive);
        Assert.Equal("OXMU123", result.LatestForeignClientId);
    }

    [Fact]
    public async Task Own_entry_and_foreign_reduce_only_exit_do_not_block()
    {
        var source = new AuditSource([
            Row("MU-USDT-SWAP", "KXOkxEntry123", false, DateTimeOffset.UtcNow),
            Row("MU-USDT-SWAP", "PL123", true, DateTimeOffset.UtcNow)
        ]);
        var result = await new ExternalWriterGuard(source).CheckAsync(default);
        Assert.False(result.IsExclusive);
        Assert.Null(result.LatestForeignEntryAt);
        Assert.StartsWith("QUIET_WINDOW_PASS_NOT_EXCLUSIVE", result.Detail);
    }

    [Fact]
    public async Task Foreign_entry_older_than_quiet_window_does_not_block()
    {
        var source = new AuditSource([Row("MU-USDT-SWAP", "OXMU123", false,
            DateTimeOffset.UtcNow - ExternalWriterGuard.QuietWindow - TimeSpan.FromMinutes(1))]);
        Assert.False((await new ExternalWriterGuard(source).CheckAsync(default)).IsExclusive);
    }

    [Fact]
    public async Task Quiet_window_is_exclusive_only_after_environment_confirmation()
    {
        var source = new AuditSource([]);
        var result = await new ExternalWriterGuard(source, operatorConfirmedExclusiveWriter: true).CheckAsync(default);
        Assert.True(result.IsExclusive);
        Assert.StartsWith("OPERATOR_CONFIRMED_EXCLUSIVE", result.Detail);
    }

    [Fact]
    public async Task Manual_entry_without_client_id_does_not_block_runtime_ownership()
    {
        var source = new AuditSource([Row("UNI-USDT-SWAP", "", false, DateTimeOffset.UtcNow)]);

        var result = await new ExternalWriterGuard(source, operatorConfirmedExclusiveWriter: true).CheckAsync(default);

        Assert.True(result.IsExclusive);
        Assert.Null(result.LatestForeignEntryAt);
    }

    [Fact]
    public async Task Manual_entry_does_not_hide_later_foreign_api_writer()
    {
        var source = new AuditSource([
            Row("UNI-USDT-SWAP", "", false, DateTimeOffset.UtcNow.AddSeconds(-1)),
            Row("XRP-USDT-SWAP", "OTHER-BOT", false, DateTimeOffset.UtcNow)
        ]);

        var result = await new ExternalWriterGuard(source, operatorConfirmedExclusiveWriter: true).CheckAsync(default);

        Assert.False(result.IsExclusive);
        Assert.Equal("OTHER-BOT", result.LatestForeignClientId);
    }

    private static Dictionary<string, string> Row(string symbol, string clientId, bool reduceOnly, DateTimeOffset at) =>
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["instId"] = symbol, ["clOrdId"] = clientId, ["reduceOnly"] = reduceOnly ? "true" : "false",
            ["cTime"] = at.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture)
        };

    private sealed class AuditSource(IReadOnlyList<Dictionary<string, string>> rows) : IWriterAuditSource
    {
        public Task<IReadOnlyList<Dictionary<string, string>>> GetRecentOrderAuditAsync(string? symbol,
            CancellationToken cancellationToken) => Task.FromResult(rows);
    }
}
