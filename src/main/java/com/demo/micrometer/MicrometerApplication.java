package com.demo.micrometer;

import java.time.Duration;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.publisher.Flux;

@SpringBootApplication
@EnableScheduling
public class MicrometerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MicrometerApplication.class, args);
    }

    private BeerService beerService;

    public MicrometerApplication(BeerService beerService) {
        this.beerService = beerService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void orderBeers() {
        Flux.interval(Duration.ofSeconds(2))
                .map(MicrometerApplication::toOrder)
                .doOnEach(o -> beerService.orderBeer(o.get()))
                .subscribe();
    }

    private static Order toOrder(Long l) {
        Long amount = l % 5;
        String type = l % 2 == 0 ? "ale" : "light";
        return new Order(amount.intValue(), type);
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

}

