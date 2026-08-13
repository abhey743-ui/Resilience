# Exception Reference — What Each Pattern Throws, and How to Catch It at the Gateway

The whole point of this doc: know exactly which exception type to expect from which failure mode, so a
gateway-level `@RestControllerAdvice` (see `GlobalResilienceExceptionHandler.java`) can map each one to
the right HTTP status instead of everything collapsing into a generic 500.

## The full table

| Pattern / situation | Exception thrown | Notes |
|---|---|---|
| Circuit Breaker — OPEN (or HALF_OPEN, no permits left) | `io.github.resilience4j.circuitbreaker.CallNotPermittedException` | Thrown instantly, real call never attempted |
| Circuit Breaker — CLOSED | *(none specific)* | Whatever the real call throws propagates unchanged |
| Rate Limiter — over budget | `io.github.resilience4j.ratelimiter.RequestNotPermitted` | Thrown instantly, real call never attempted |
| Bulkhead — full | `io.github.resilience4j.bulkhead.BulkheadFullException` | Same class for semaphore- and thread-pool-based bulkheads |
| Time Limiter — deadline exceeded | `java.util.concurrent.TimeoutException` | Only applies to calls wrapped as `CompletableFuture` — see below |
| Retry — attempts exhausted | *(no unique exception by default)* | Rethrows whatever the **last attempt** actually threw. Only throws `io.github.resilience4j.retry.MaxRetriesExceededException` if `failAfterMaxAttempts: true` is set, which is meant for result-based (not exception-based) retries |
| **Feign — network-level failure** (connect timeout, read timeout, connection reset, DNS failure) | **`feign.RetryableException`** | See detailed explanation below — this is *not* `TimeoutException` |
| Feign — HTTP error status from the real service (4xx/5xx) | `feign.FeignException` (or a subtype, e.g. `FeignException.NotFound`) | Decoded from the actual HTTP response by Feign's `ErrorDecoder` |

## Why Feign gives `RetryableException`, not `TimeoutException`, on a timeout

This trips people up because Resilience4j's own `TimeLimiter` throws `java.util.concurrent.TimeoutException`
— so it's natural to expect the same thing from a Feign timeout. It's not what happens for a normal
**synchronous** Feign call, which is what this repo's `CustomersServiceImpl` uses throughout.

Inside Feign, `SynchronousMethodHandler.executeAndDecode()` does roughly this:

```java
try {
    response = client.execute(request, options);
} catch (IOException e) {
    throw errorExecuting(request, e);   // wraps into feign.RetryableException
}
```

Any `IOException` from the underlying HTTP client — connect timeout, read timeout, connection reset, DNS
failure, basically any network-level glitch — gets caught here and wrapped into `feign.RetryableException`
(which extends `FeignException`, which extends `RuntimeException`). So for a normal synchronous Feign
call:

- **Network glitch / timeout → `feign.RetryableException`**
- **`java.util.concurrent.TimeoutException` → only from Resilience4j's own `TimeLimiter`, which only
  applies to calls you've explicitly made asynchronous (`CompletableFuture`)**

`fetchCustomerDetails` never wraps its Feign calls in a `CompletableFuture`, so in practice you will only
ever see `RetryableException` for network problems, never `TimeoutException`, from this codebase as-is.

## Does the Circuit Breaker need special code to catch `RetryableException`?

No. `CircuitBreaker.decorateSupplier(...)` (and the `.withCircuitBreaker(...)` functional wrapper) already
wraps the call in its own internal try/catch. **Any** unchecked exception that propagates out of the real
call — including `RetryableException` — is automatically recorded as a failure by default, unless
explicitly excluded via `ignoreExceptions`/`recordExceptions`. No manual catch block needed in service
code for this to work. And it's correct that it counts: unlike `RequestNotPermitted`/`BulkheadFullException`
(self-imposed), `RetryableException` means the network call to the real service genuinely failed or timed
out — real evidence of downstream health.

## A Feign-specific gotcha worth checking once

Raw Feign has its **own** built-in retry mechanism (`feign.Retryer`), completely separate from
Resilience4j's `@Retry`/`.withRetry(...)`. If left at Feign's own default, it would retry
`RetryableException` internally, *before* Resilience4j's Retry (or the Circuit Breaker) ever sees the
failure — an invisible double-retry. Spring Cloud OpenFeign avoids this by auto-configuring a
`Retryer.NEVER_RETRY` bean by default, deliberately handing retry responsibility to Resilience4j instead.
Worth a quick project-wide search for a custom `Retryer` bean to confirm nothing overrides this default.

## Mapping all of this at the gateway

See `GlobalResilienceExceptionHandler.java` for the actual implementation. The mapping used:

| Exception | HTTP status returned to the client |
|---|---|
| `CallNotPermittedException` | 503 Service Unavailable — "try later, we know this dependency is down" |
| `RequestNotPermitted` | 429 Too Many Requests |
| `BulkheadFullException` | 503 Service Unavailable — "we're at capacity right now" |
| `TimeoutException` | 504 Gateway Timeout |
| `feign.RetryableException` | 502 Bad Gateway — network-level failure talking to the real downstream service |
| `feign.FeignException` (generic) | Pass through the real status code the downstream service returned, where reasonable |
