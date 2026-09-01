using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class RuntimeSettingsStoreTests
{
    [Fact]
    public void Persists_net_loss_limit_but_never_restores_live_admission_from_client_file()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"keytrins-settings-{Guid.NewGuid():N}");
        try
        {
            var original = new RuntimeSettingsStore(new RuntimeOptions
            {
                TradingEnabled = true,
                OkxExclusiveWriterConfirmed = true
            }, directory);
            original.Update(3m, 45, 5, 1000m, 0.25m, 1.75m);
            original.SetExchangeMode(ExchangeId.Okx, ExchangeMode.Active);

            var restored = new RuntimeSettingsStore(new RuntimeOptions
            {
                TradingEnabled = false,
                OkxExclusiveWriterConfirmed = false
            }, directory).Current;

            Assert.Equal(1.75m, restored.MaxNetLossUsdt);
            Assert.False(restored.TradingEnabled);
            Assert.False(restored.OkxExclusiveWriterConfirmed);
            Assert.Equal(ExchangeMode.Active, restored.Exchanges[ExchangeId.Okx.ToString()]);
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
    }

    [Theory]
    [InlineData("0.04")]
    [InlineData("100.01")]
    public void Rejects_out_of_range_net_loss_limit(string value)
    {
        var directory = Path.Combine(Path.GetTempPath(), $"keytrins-settings-{Guid.NewGuid():N}");
        try
        {
            var store = new RuntimeSettingsStore(new RuntimeOptions(), directory);
            Assert.Throws<ArgumentOutOfRangeException>(() => store.Update(3m, 45, 5, 1000m, 0.25m,
                decimal.Parse(value, System.Globalization.CultureInfo.InvariantCulture)));
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
    }
}
