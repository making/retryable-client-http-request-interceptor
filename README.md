# retryable-client-http-request-interceptor

RetryableClientHttpRequestInterceptor for `RestTemplate` and `RestClient`

For Spring 6+ / Spring Boot 3+

```xml

<dependency>
	<groupId>am.ik.spring</groupId>
	<artifactId>retryable-client-http-request-interceptor</artifactId>
	<version>0.3.0</version>
</dependency>
```

For Spring 5 / Spring Boot 2

```xml

<dependency>
	<groupId>am.ik.spring</groupId>
	<artifactId>retryable-client-http-request-interceptor-spring5</artifactId>
	<version>0.3.0</version>
</dependency>
```

### How to use

```java
private final RestTemplate restTemplate;

public MyClient(RestTemplateBuilder builder){
	this.restTemplate = builder
		.rootUri("http://example.com")
		.additionalInterceptors(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2)))
		.build();
}
```

or 

```java
// Spring 6.1+ / Spring Boot 3.2+
private final RestClient restClient;

public MyClient(RestClient.Builder builder){
	this.restClient = builder
		.baseUrl("http://example.com")
		.requestInterceptor(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2)))
		.build();
}
```
* How to use exponential backoff
```java
new RetryableClientHttpRequestInterceptor(new ExponentialBackOff(100, 2))
```

* How to configure `retryableResponseStatuses` (default: 408, 425, 429, 500, 503, 504)
```java
new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2), Set.of(500, 503))
```

* How to configure whether to `retryClientTimeout` (default: true)
```java
new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2), DEFAULT_RETRYABLE_RESPONSE_STATUSES, false)
```

### License

Licensed under the Apache License, Version 2.0.
