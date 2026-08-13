package com.eazybytes.accounts.service.impl;

import com.eazybytes.accounts.dto.AccountsDto;
import com.eazybytes.accounts.dto.CardsDto;
import com.eazybytes.accounts.dto.CustomerDetailsDto;
import com.eazybytes.accounts.dto.LoansDto;
import com.eazybytes.accounts.entity.Accounts;
import com.eazybytes.accounts.entity.Customer;
import com.eazybytes.accounts.exception.ResourceNotFoundException;
import com.eazybytes.accounts.mapper.AccountsMapper;
import com.eazybytes.accounts.mapper.CustomerMapper;
import com.eazybytes.accounts.repository.AccountsRepository;
import com.eazybytes.accounts.repository.CustomerRepository;
import com.eazybytes.accounts.service.ICustomersService;
import com.eazybytes.accounts.service.client.CardsFeignClient;
import com.eazybytes.accounts.service.client.LoansFeignClient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * See CustomersServiceImpl.md in this same folder for the full line-by-line explanation of
 * everything happening here: why the decorator order is what it is, why the loans call and
 * the cards call are treated differently, and what changed from the original draft of this
 * class and why.
 *
 * Quick summary of the resilience design:
 *  - Loans call  = MANDATORY. A genuine downstream failure propagates up to the gateway.
 *                  A self-imposed throttling rejection (our own rate limiter/bulkhead) degrades
 *                  gracefully instead, returning the customer + account data we already have.
 *  - Cards call  = OPTIONAL. Any failure at all degrades silently via .withFallback(...).
 */
@Service
public class CustomersServiceImpl implements ICustomersService {

    private static final Logger log = LoggerFactory.getLogger(CustomersServiceImpl.class);

    private final AccountsRepository accountsRepository;
    private final CustomerRepository customerRepository;
    private final CardsFeignClient cardsFeignClient;
    private final LoansFeignClient loansFeignClient;

    private final CircuitBreaker cardsCircuitBreaker;
    private final CircuitBreaker loanCircuitBreaker;
    private final Retry loansRetry;
    // Intentionally not applied to the cards call below — see CustomersServiceImpl.md
    // ("why cardsRetry exists but isn't used") for the reasoning.
    private final Retry cardsRetry;
    private final Bulkhead loansBulkhead;
    private final RateLimiter loanRateLimiter;
    private final RateLimiter cardRateLimiter;

    public CustomersServiceImpl(AccountsRepository accountsRepository,
                                 CustomerRepository customerRepository,
                                 CardsFeignClient cardsFeignClient,
                                 LoansFeignClient loansFeignClient,
                                 CircuitBreakerRegistry circuitBreakerRegistry,
                                 RetryRegistry retryRegistry,
                                 RateLimiterRegistry rateLimiterRegistry,
                                 BulkheadRegistry bulkheadRegistry) {

        this.accountsRepository = accountsRepository;
        this.customerRepository = customerRepository;
        this.cardsFeignClient = cardsFeignClient;
        this.loansFeignClient = loansFeignClient;

        this.loansRetry = retryRegistry.retry("loansRetry");
        this.cardsRetry = retryRegistry.retry("cardsRetry");
        this.cardsCircuitBreaker = circuitBreakerRegistry.circuitBreaker("cardsBreaker");
        this.loanCircuitBreaker = circuitBreakerRegistry.circuitBreaker("loansBreaker");
        this.loansBulkhead = bulkheadRegistry.bulkhead("loansBulkHead");

        // Resolved ONCE here and reused for every request — this is the fix for the original
        // bug where a new RateLimiter instance (keyed by mobile number) was created on every
        // single call, which both defeated the shared throttling budget and leaked memory.
        // See docs/03-rate-limiter.md for the full explanation.
        this.loanRateLimiter = rateLimiterRegistry.rateLimiter("loanRateLimiter");
        this.cardRateLimiter = rateLimiterRegistry.rateLimiter("cardRateLimiter");
    }

    @Override
    public CustomerDetailsDto fetchCustomerDetails(String mobileNumber, String correlationId) {

        // 1. Database queries — local, no network resilience needed here.
        Customer customer = customerRepository.findByMobileNumber(mobileNumber).orElseThrow(
                () -> new ResourceNotFoundException("Customer", "mobileNumber", mobileNumber)
        );
        Accounts accounts = accountsRepository.findByCustomerId(customer.getCustomerId()).orElseThrow(
                () -> new ResourceNotFoundException("Account", "customerId", customer.getCustomerId().toString())
        );

        // 2. Map the data we already have for certain. This must survive even if the
        //    downstream calls below fail — never discard it, always build on top of it.
        CustomerDetailsDto customerDetailsDto = CustomerMapper.mapToCustomerDetailsDto(customer, new CustomerDetailsDto());
        customerDetailsDto.setAccountsDto(AccountsMapper.mapToAccountsDto(accounts, new AccountsDto()));

        // 3. Mandatory Loans call.
        //    Decorator order (innermost -> outermost): CircuitBreaker, Bulkhead, RateLimiter, Retry.
        //    This keeps RequestNotPermitted / BulkheadFullException OUT of the CircuitBreaker's
        //    failure count entirely, since it now sits closer to the real call than they do.
        //    Full reasoning: docs/06-decorator-order-and-composition.md
        try {
            ResponseEntity<LoansDto> loansDtoResponseEntity = Decorators.ofSupplier(
                            () -> loansFeignClient.fetchLoanDetails(correlationId, mobileNumber))
                    .withCircuitBreaker(loanCircuitBreaker)
                    .withBulkhead(loansBulkhead)
                    .withRateLimiter(loanRateLimiter)
                    .withRetry(loansRetry)
                    .get();

            if (loansDtoResponseEntity != null && loansDtoResponseEntity.getBody() != null) {
                customerDetailsDto.setLoansDto(loansDtoResponseEntity.getBody());
            }

        } catch (RequestNotPermitted | BulkheadFullException selfThrottled) {
            // Not a real Loans-service failure — this is OUR OWN throttling rejecting the call
            // before it was even attempted. We already have valid customer + account data, so
            // return that rather than discarding it. Loans data is simply omitted this time.
            log.warn("Loans call self-throttled for correlationId={} (mobileNumber={}): {}",
                    correlationId, mobileNumber, selfThrottled.getClass().getSimpleName());
            return customerDetailsDto;

        } catch (RuntimeException genuineFailure) {
            // Breaker OPEN (CallNotPermittedException), or all retries exhausted against a real
            // network/service failure (feign.RetryableException, FeignException, etc).
            // Loans is mandatory, so this propagates up to the gateway, where
            // GlobalResilienceExceptionHandler maps it to a proper status code.
            // See docs/08-exception-reference-and-gateway-handling.md
            log.error("Loans call failed for correlationId={} (mobileNumber={}): {}",
                    correlationId, mobileNumber, genuineFailure.toString());
            throw genuineFailure;
        }

        // 4. Optional Cards call — allowed to degrade silently on ANY failure.
        //    withFallback is added LAST so it wraps everything inside it and can catch any
        //    exception from the whole chain, including CircuitBreaker/RateLimiter rejections.
        ResponseEntity<CardsDto> cardsDtoResponseEntity = Decorators.ofSupplier(
                        () -> cardsFeignClient.fetchCardDetails(correlationId, mobileNumber))
                .withCircuitBreaker(cardsCircuitBreaker)
                .withRateLimiter(cardRateLimiter)
                .withFallback(cardsFailure -> {
                    log.warn("Cards call degraded for correlationId={} (mobileNumber={}): {}",
                            correlationId, mobileNumber, cardsFailure.toString());
                    return ResponseEntity.ok((CardsDto) null);
                })
                .get();

        // 5. Populate final DTO safely.
        if (cardsDtoResponseEntity != null && cardsDtoResponseEntity.getBody() != null) {
            customerDetailsDto.setCardsDto(cardsDtoResponseEntity.getBody());
        }

        return customerDetailsDto;
    }
}
