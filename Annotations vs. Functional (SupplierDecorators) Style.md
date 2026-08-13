# Annotations vs. Functional (Supplier/Decorators) Style

Resilience4j gives you two completely different ways to apply the same patterns. Neither is "the correct
one" universally — they fit different situations.

## Annotation style

```java
@Service
public class LoansService {

    private final LoansFeignClient loansFeignClient;

    @CircuitBreaker(name = "loansBreaker", fallbackMethod = "loanFallback")
    @Retry(name = "loansRetry")
    @RateLimiter(name = "loanRateLimiter")
    public LoansDto getLoans(String mobileNumber) {
        return loansFeignClient.fetchLoanDetails(mobileNumber);
    }

    private LoansDto loanFallback(String mobileNumber, Throwable ex) {
        return LoansDto.empty();
    }
}
```

How it actually works: Spring AOP wraps a proxy around the whole method. It's declarative — you say
*what* patterns apply, and Spring handles the *how*.

**Strengths**
- Very readable — the resilience behavior is visible right on the method signature.
- Almost no boilerplate.
- Config lives entirely in `application.yml`, which non-Java-experts (SRE/ops) can often tune without
  touching code.

**Limitations**
- The annotation wraps the **entire method**, not a specific line. If a method makes two different
  downstream calls, one annotation can't treat them independently — see the worked example below.
- Decorator order is fixed by Spring AOP's default (see `docs/06`), only overridable via global
  properties that are easy to forget about.
- Fallback method signatures have to match exactly (same return type, exception param appended) — this
  is a common source of silent misconfiguration (Spring just won't find/call the fallback if the
  signature doesn't match, and you don't always get a loud error).

## Functional / Supplier style (what `CustomersServiceImpl.java` in this repo uses)

```java
ResponseEntity<LoansDto> result = Decorators.ofSupplier(
        () -> loansFeignClient.fetchLoanDetails(correlationId, mobileNumber))
    .withCircuitBreaker(loanCircuitBreaker)
    .withBulkhead(bulkhead)
    .withRateLimiter(loanRateLimiter)
    .withRetry(loansRetry)
    .get();
```

How it actually works: you build up a chain of decorators around a `Supplier<T>` (a "give me this value
when asked" lambda) explicitly, in code, and call `.get()` to actually execute it.

**Strengths**
- You choose exactly which patterns wrap exactly which call — down to individual lines inside a method.
- You control decorator order directly and explicitly (see `docs/06`).
- Works cleanly for **aggregator methods** — one method fanning out to several downstream services, each
  needing different resilience treatment and different fallback behavior. This describes
  `fetchCustomerDetails` exactly: DB read (no resilience needed, it's local), a **mandatory** Loans call
  (must propagate certain failures), and an **optional** Cards call (should degrade silently).

**Limitations**
- More verbose — the resilience logic lives inline in business logic code instead of being pushed to the
  edges via annotations.
- No compile-time help matching config instance names (`"loansBreaker"`) to actual `application.yml`
  entries — a typo just silently falls back to Resilience4j's default config for that name.

## The concrete case for why annotations don't fit `fetchCustomerDetails`

```java
// If this were a single annotated method...
@CircuitBreaker(name = "customerDetailsBreaker", fallbackMethod = "fallback")
public CustomerDetailsDto fetchCustomerDetails(String mobileNumber) {
    // reads DB
    // calls Loans   ← mandatory, should propagate specific failures
    // calls Cards   ← optional, should degrade to null and continue
    ...
}
```

One breaker, one fallback method, for *two* completely different downstream dependencies with
*opposite* failure-handling requirements. A Cards outage would trip the same breaker as a Loans outage,
and both would be routed to the same fallback — you'd lose the "Loans is mandatory, Cards is optional"
distinction entirely. This is exactly why the functional style, with two separately-scoped
`CircuitBreaker` instances (`loansBreaker`, `cardsBreaker`), is the correct professional choice here —
not a workaround, the actual right tool for a fan-out method.

## Rule of thumb
- **One method, one downstream dependency** → annotations. Simpler, more readable, no real downside.
- **One method, several downstream dependencies with different resilience needs** → functional/Supplier
  composition, scoped per call.
