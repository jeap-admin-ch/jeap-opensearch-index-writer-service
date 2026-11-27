# Consumer contracts

The service enforces jEAP messaging consumer contracts at startup. Each Kafka message type
configured in `messages.json` must have a matching consumer contract registered for the application.

## Generating contracts

Consumer contracts are generated at compile time by placing the
`@JeapMessageConsumerContractsByTemplates` annotation on any class in the service instance module.
The annotation processor reads `messages.json` and generates one consumer contract per configured
message type:

```java
@JeapMessageConsumerContractsByTemplates(
    appName = "my-opensearch-index-writer"
)
@SpringBootApplication
public class MyOpenSearchIndexWriterApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyOpenSearchIndexWriterApplication.class, args);
    }
}
```

The annotation is processed by `jeap-messaging-contract-annotation-processor`, which is pulled in
transitively through the `jeap-opensearch-index-writer-service-instance` dependency.

## Annotation attributes

| Attribute  | Description                                                                                                                                                                          |
|------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `appName`  | Logical application name used for the consumer contracts. Defaults to `spring.application.name` from `application.yml` when not set. Must match `spring.application.name` exactly.   |

## Startup validation

On startup, the service verifies that a consumer contract exists for every message type listed in
`messages.json`. If the annotation is absent, or if the resolved `appName` does not match
`spring.application.name`, the service refuses to start with a `NoContractException`.

## Maintenance command contracts

With [maintenance jobs](maintenance-jobs.md) enabled, the service sends itself one command per task: an
`UpsertSearchItemCommand` on `jeap.opensearch.indexwriter.maintenance.upsert-command-topic` and a `DeleteSearchItemCommand`
on `jeap.opensearch.indexwriter.maintenance.delete-command-topic`. As these topics are specific to the service instance,
the instance declares the producer and the consumer contract for both, e.g. next to the template annotation:

```java
@JeapMessageConsumerContractsByTemplates(appName = "my-opensearch-index-writer")
@JeapMessageProducerContract(value = UpsertSearchItemCommand.TypeRef.class, topic = "my-system-upsert-search-item-command")
@JeapMessageConsumerContract(value = UpsertSearchItemCommand.TypeRef.class, topic = "my-system-upsert-search-item-command")
@JeapMessageProducerContract(value = DeleteSearchItemCommand.TypeRef.class, topic = "my-system-delete-search-item-command")
@JeapMessageConsumerContract(value = DeleteSearchItemCommand.TypeRef.class, topic = "my-system-delete-search-item-command")
@SpringBootApplication
public class MyOpenSearchIndexWriterApplication {
}
```

The commands come from `ch.admin.bit.jeap.messagetype.jeap:upsert-search-item-command` and
`ch.admin.bit.jeap.messagetype.jeap:delete-search-item-command`, transitive dependencies of the service. With
maintenance enabled, startup fails if any of these contracts is missing.

## Related

- [Getting started](getting-started.md)
- [Message configuration](message-configuration.md)
- [jeap-opensearch-index-writer-service](../README.md)
