using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class RuntimeAdmissionPolicyTests
{
    [Fact]
    public void External_positions_do_not_degrade_runtime_readiness()
    {
        Assert.Equal("ready", RuntimeAdmissionPolicy.HealthStatus("ONLINE", 0, 0, "EXCLUSIVE"));
    }

    [Theory]
    [InlineData("ERROR", 0, 0, "EXCLUSIVE")]
    [InlineData("ONLINE", 1, 0, "EXCLUSIVE")]
    [InlineData("ONLINE", 0, 1, "EXCLUSIVE")]
    [InlineData("ONLINE", 0, 0, "FOREIGN_WRITER_ACTIVE")]
    public void Genuine_execution_or_writer_failures_remain_degraded(string masterHealth,
        int unresolvedExecution, int unresolvedRisk, string writerExclusivity)
    {
        Assert.Equal("degraded", RuntimeAdmissionPolicy.HealthStatus(masterHealth, unresolvedExecution,
            unresolvedRisk, writerExclusivity));
    }
}
