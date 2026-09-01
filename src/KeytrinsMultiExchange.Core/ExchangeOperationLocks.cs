using System.Collections.Concurrent;

namespace KeytrinsMultiExchange.Core;

public sealed class ExchangeOperationLocks
{
    private readonly ConcurrentDictionary<ExchangeId, SemaphoreSlim> _locks = new();

    public SemaphoreSlim For(ExchangeId exchange) =>
        _locks.GetOrAdd(exchange, static _ => new SemaphoreSlim(1, 1));
}
