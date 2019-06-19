# Defining custom metrics in a Spring Boot application using Micrometer

A few months ago my friend and colleague, Attila wrote a [great post](https://blog.autsoft.hu/monitoring-microservices/) on the monitoring of Spring microservices using Micrometer, Prometheus, Grafana and Kubernetes.
Now, it is time to have a closer look at Micrometer and its' integration into Spring Boot and the way one should export custom metrics using these technologies.

Spring Boot 2.0 brought a ton of new features into our favorite Java framework.
One of these new features, amongst many, is the integration of Micrometer into Spring Boot Actuator.
Micrometer is a dimensional metrics and monitoring facade to help developers integrate their application metrics to various monitoring systems while keeping the appliaction indepedendent from the actual monitoring implementation.
As the landing page of the project states, it's like SLF4J but for metrics.

## Micrometer 101

Before we dive into the specifics of defining custom metrics using Micrometer, let's spend a few moments on this definition.
First of all, we said that Micrometer is a facade.
What it really means is that using the library, you as a developer can use a single interface (or facade) to ship your metrics into a wide variety of monitoring systems.
You may think that it's not that big of a deal.
Well, it is.
There are a ton of solutions for monitoring applications, and each of them has different approaches to satisfy your monitoring needs.
These differences can be as subtle as the naming conventions they use, or some might differ even on the fundamental approach on how they collect their data.
Here, at AutSoft we use Prometheus which polls the applications for new data, as opposed to e.g. DataDog which relies on a push model.
Micrometer can bridge all of these differences for you so you can use a unified interface for all of these solutions.

Next, we said that Micrometer follows a dimensional approach, which means that you can tag your metrics with an arbitrary number of tags.
For example, if you have a metric that counts the HTTP requests in your application, you can annotate it with the URI the requests are hitting.
Once Prometheus collects these metrics, you can see the aggregate number of requests but also, you can drill down and examine the number of requests for a specific URI.

## Micrometer and Spring

With the new version of Spring Boot Actuator, the Spring team decided to use Micrometer to report the framework's built-n metrics using Micrometer.
(Which is not a surprise since they were the ones developing the library in the first place...)

To examine these metrics in an existing Spring Boot 2 application you don't really need to work a lot.
It is as easy as importing the Spring Boot Actuator and Micrometer dependencies and do some configuration.
First, add the following lines to your `pom.xml`'s `dependencies` section:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>

<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
``` 

Then, paste the following configuration in the `application.properties` (or `application.yml`) file to expose the Prometheus scraping endpoint:
```properties
management.endpoints.web.exposure.include=prometheus
```

Now, if you start your application and open the `http://localhost:8080/actuator/prometheus` endpoint, you will see a ton of metrics already exported by Actuator.
If you take a peek at the first few lines, you can already see Micrometer's and Prometheus' dimensional approach in action:
```
# HELP logback_events_total Number of error level events that made it to the logs
# TYPE logback_events_total counter
logback_events_total{level="warn",} 0.0
logback_events_total{level="debug",} 0.0
logback_events_total{level="error",} 0.0
logback_events_total{level="trace",} 0.0
logback_events_total{level="info",} 7.0
```

The metric's name is `logback_events_total` but there is a tag (or dimension) called `level` to help you drill down, and examine exactly how many events happened on each logging level.

## Defining your custom metrics

Now, let's define and export our own custom metrics using Micrometer and Spring Boot.
To do so, we will use our favorite example, the `BeerService` which we will monitor thoroughly.
(You should always keep an eye on your beers, shouldn't you?)

First of all, we need to get a hold of our ApplicationContext's `MeterRegistry` instance:

```java
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BeerService {

    private MeterRegistry meterRegistry;

    public BeerService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
}
```
The `MeterRegistry` is responsible for collecting and managing your application's meters.

### Defining a Counter

The most basic type of metrics is the Counter.
The Counter is used to report a single number that represents a count. 

In our example, we will report the number of Orders coming into our `BeerService`:
```java
private void initOrderCounters() {
    lightOrderCounter = this.meterRegistry.counter("beer.orders", "type", "light"); // 1 - create a counter
    aleOrderCounter = Counter.builder("beer.orders")    // 2 - create a counter using the fluent API
            .tag("type", "ale")
            .description("The number of orders ever placed for Ale beers")
            .register(meterRegistry);
}

void orderBeer(Order order) {
    orders.add(order);

    if ("light".equals(order.type)) {
        lightOrderCounter.increment(1.0);  // 3 - increment the counter
    } else if ("ale".equals(order.type)) {
        aleOrderCounter.increment();
    }
}
```

Let's see what's happening here:
1. We can create a counter using the `meterRegistry`. The created metric will be registered to the registry automatically.
2. A more concise way to create a metric is using the fluent builder API.
3. The counter can be incremented by one or any positive number.

The only thing left to do is to actually order some beers.
Paste the following lines into your Application class and start the application:
```java
@SpringBootApplication
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
        double amount = l % 5;
        String type = l % 2 == 0 ? "ale" : "light";
        return new Order(amount, type);
    }

}
``` 

Now, if you open the `http://localhost:8080/actuator/prometheus` URL in your browser, somewhere along the lines you should find our new metrics:
```
# HELP beer_orders_total  
# TYPE beer_orders_total counter
beer_orders_total{type="light",} 5.0
beer_orders_total{type="ale",} 6.0
```

Congratulations, we've just defined our first Counter metric using Micrometer!

### Defining a Gauge

Next up, let's take a look at Gauges.
A Gauge also represents a single numerical value but there are a few significant differences.
First, a Counter stores a monotonically increasing value while a Gauge's value can be decremented as well.
Second, a Gauge's value only changes when observed, we don't increment it manually, like we did in our previous example.
Instead, we provide a function to get the current value of the Gauge if needed.
This behavior also implies that any events happening between two observations are lost.  

Usually, it is recommended to use Gauges for values that have upper limits and it is not recommended to use Gauges for metrics that are representable by Counters. 

In our example, we will monitor the size of the `order` list using a Gauge.
(Even though our current "business logic" doesn't implement an upper limit for this list, let's assume that we have a maximum number of orders we can handle, any more than that will be dropped.)

Extend your `BeerService`'s constructor as follows:
```java
public BeerService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    initOrderCounters();
    Gauge.builder("beer.ordersInQueue", orders, Collection::size)
            .description("Number of unserved orders")
            .register(meterRegistry);
}
```

Again, if you restart your application and check the `http://localhost:8080/actuator/prometheus` URL in your browser, you should find the `beer.ordersInQueue` metric.

### Defining a Timer

A Timer serves two functions, it measures the time of certain events (typically method executions) and counts these events at the same time.
If you've ever monitored a web application, most likely to wanted to check the response times of your server.
This use case is the most typical one to Timers.

Timers have a ton of useful functionality but let's just focus on measuring the execution time of a given method.
In Spring, one can use `micrometer-core`'s `@Timed` annotation after configuring the `TimedAspect` Aspect provided by Micrometer.
Put the following lines of code in your Application class (or in any `@Configuration` class): 
```java
@Bean
public TimedAspect timedAspect(MeterRegistry registry) {
    return new TimedAspect(registry);
}
```
(Make sure to import the `spring-boot-starter-aop` maven depency.)

Then, create a method in the `BeerService` to serve the first order in the `orders` list:
```java
@Scheduled(fixedRate = 5000)
@Timed(description = "Time spent serving orders")
public void serveFirstOrder() throws InterruptedException {
    if (!orders.isEmpty()) {
        Order order = orders.remove(0);
        Thread.sleep(1000L * order.amount);
    }
}
```
(To make the `@Scheduled` annotations work, you need to annotate the Application or any other Configuration class with `@EnableScheduling`.)

Now, if you start the application, you'll find the following metrics exported to Prometheus:
```
# HELP method_timed_seconds Time spent serving orders
# TYPE method_timed_seconds summary
method_timed_seconds_count{class="com.demo.micrometer.BeerService",exception="none",method="serveFirstOrder",} 8.0
method_timed_seconds_sum{class="com.demo.micrometer.BeerService",exception="none",method="serveFirstOrder",} 11.041940917
# HELP method_timed_seconds_max Time spent serving orders
# TYPE method_timed_seconds_max gauge
method_timed_seconds_max{class="com.demo.micrometer.BeerService",exception="none",method="serveFirstOrder",} 4.003256321
```

You can see that our application served 8 orders in 11.04 seconds with a maximum of 4 seconds per Order.

Timers usually do not report the measured times until the execution of the event isn't finished.
If you have long tasks that you'd like to measure while they are still running, use the `@Timed` annotation like this:
```java
@Timed(description = "Time spent serving orders", longTask = true)
```

## Summary & where to go next

In this post, we got to know the basics of Micrometer, it's integration to Spring Boot Actuator, and to make things a bit more exciting, we've defined our own metrics in a Spring Boot application.

Now, you know the basics of Micrometer but there is still a lot to explore.
I recommend you to check out the [Concepts page of the Micrometer documentation](http://micrometer.io/docs/concepts) and/or watch Jon Schneider's [presentation](https://www.youtube.com/watch?v=HIUoeLYWo7o&t=2272s) on Micrometer at SpringOne Platform.

In this post, we haven't covered the visualization of the metrics defined.
For these purposes we use [Prometheus](https://prometheus.io/) with [Grafana](https://grafana.com/) as they fit our use cases perfectly.  

The source code for this guide can be found on our [GitHub page](https://github.com/AutSoft/micrometer-demo).

If you liked this post or have any questions, please don't hesitate to leave a comment below.

## Sources

* [Micrometer: Spring Boot 2's new application metrics collector](https://spring.io/blog/2018/03/16/micrometer-spring-boot-2-s-new-application-metrics-collector)
* [Spring Boot Reference Guide - Metrics](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#production-ready-metrics)
* [Micrometer Docs - Concepts](https://micrometer.io/docs/concepts)
* [Introducing Micrometer Application Metrics - Jon Schneider](https://www.youtube.com/watch?v=HIUoeLYWo7o&t=2272s)
