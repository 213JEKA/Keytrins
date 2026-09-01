using KeytrinsMultiExchange.Core;

namespace KeytrinsMultiExchange.Service;

public sealed class PositionManagementWorker(
    ILogger<PositionManagementWorker> logger,
    RuntimeSettingsStore settings,
    PositionManager manager,
    ExecutionCoordinator execution) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await execution.RecoverAsync(stoppingToken);
                await manager.RunOnceAsync(settings.Current, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception exception)
            {
                logger.LogError(exception, "Position management iteration failed");
            }

            try { await Task.Delay(TimeSpan.FromSeconds(3), stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
        }
    }
}
