package com.eazybytes.gatewayserver;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * See GATEWAY-RESILIENCE.md in this same folder for the full breakdown: dependencies, a
 * bean-by-bean and route-by-route walkthrough, the request-flow diagram, and — importantly —
 * why there is deliberately no Retry filter anywhere in this class (retry belongs one layer
 * down, inside whichever downstream service owns the specific call).
 *
 * Three routes, each getting the same resilience treatment via the shared defaultCustomizer
 * bean below: a CircuitBreaker (stop calling a route that's clearly unhealthy) and a
 * TimeLimiter (don't let one slow call hang forever), both funneling into the same
 * fallbackUri when they trip.
 */
@SpringBootApplication
public class GatewayserverApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayserverApplication.class, args);
    }

    @Bean
    public RouteLocator eazyBankRouteConfig(RouteLocatorBuilder routeLocatorBuilder) {
        return routeLocatorBuilder.routes()
                .route(p -> p
                        .path("/eazybank/accounts/**")
                        .filters(f -> f.rewritePath("/eazybank/accounts/(?<segment>.*)", "/${segment}")
                                .addResponseHeader("X-Response-Time", LocalDateTime.now().toString())
                                .circuitBreaker(config -> config.setName("account")
                                        .setFallbackUri("forward:/contactSupport")
                                )
                        )
                        .uri("lb://ACCOUNTS"))
                .route(p -> p
                        .path("/eazybank/loans/**")
                        .filters(f -> f.rewritePath("/eazybank/loans/(?<segment>.*)", "/${segment}")
                                .circuitBreaker(config -> config.setName("loan")
                                        .setFallbackUri("forward:/contactSupport"))
                                .addResponseHeader("X-Response-Time", LocalDateTime.now().toString()))
                        .uri("lb://LOANS"))
                .route(p -> p
                        .path("/eazybank/cards/**")
                        .filters(f -> f.rewritePath("/eazybank/cards/(?<segment>.*)", "/${segment}")
                                .circuitBreaker(config -> config.setName("card")
                                        .setFallbackUri("forward:/contactSupport"))
                                .addResponseHeader("X-Response-Time", LocalDateTime.now().toString()))
                        .uri("lb://CARDS"))
                .build();
    }

    /**
     * Shared default config for every named CircuitBreaker created above ("account", "loan",
     * "card") since none of them registers a more specific per-name override.
     *
     * The TimeLimiter timeout here is set to 30s — deliberately LONGER than the Netty
     * response-timeout configured in application.yml (28s). This is a fix from an earlier
     * version of this bean, where both were set to the same 30s value: with equal durations,
     * it becomes a race which one actually fires, making the exact failure mode
     * non-deterministic from one request to the next. Staggering them means Netty's own
     * response-timeout is always the one that fires deterministically for a slow real network
     * call, and this TimeLimiter becomes a pure backstop for anything that hangs somewhere
     * else in the reactive chain. See GATEWAY-RESILIENCE.md for the full reasoning.
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(30))
                        .build())
                .build());
    }
}
