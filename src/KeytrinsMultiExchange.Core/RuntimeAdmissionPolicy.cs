namespace KeytrinsMultiExchange.Core;

public static class RuntimeAdmissionPolicy
{
    public static string HealthStatus(string masterHealth, int unresolvedExecutionCommands,
        int unresolvedRiskActions, string writerExclusivity) =>
        masterHealth == "ERROR" || unresolvedExecutionCommands > 0 || unresolvedRiskActions > 0 ||
        writerExclusivity != "EXCLUSIVE"
            ? "degraded"
            : "ready";
}
