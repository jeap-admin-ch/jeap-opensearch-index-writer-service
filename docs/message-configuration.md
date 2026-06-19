# Message configuration

Message-to-operation mappings are loaded at startup from `/opensearch/messages.json` on the
classpath. Each entry maps a Kafka message type and topic to one or more index operations that
are executed when a message of that type arrives.

## File structure

```json
{
  "messages": [
    {
      "messageName": "JmeDecreeDocumentCreatedEvent",
      "topicName": "jme-decree-document-created",
      "operations": [
        {
          "indexType": "DecreeDocument",
          "indexOperation": "UPSERT",
          "uri": "${jme.resource.base-uri}",
          "oauthClientId": "jme-client-id",
          "referenceProvider": "ch.admin.bit.jme.indexwriter.DecreeDocumentReferenceProvider",
          "condition": "ch.admin.bit.jme.indexwriter.DecreeDocumentActiveCondition",
          "featureFlag": "DECREE_DOCUMENT_INDEXING"
        }
      ]
    },
    {
      "messageName": "JmeDecreeDocumentDeletedEvent",
      "topicName": "jme-decree-document-deleted",
      "operations": [
        {
          "indexType": "DecreeDocument",
          "indexOperation": "DELETE",
          "uri": "${jme.resource.base-uri}",
          "referenceProvider": "ch.admin.bit.jme.indexwriter.DecreeDocumentReferenceProvider"
        }
      ]
    }
  ]
}
```

## Message fields

| Field          | Required | Description                                                                                  |
|----------------|----------|----------------------------------------------------------------------------------------------|
| `messageName`  | Yes      | Simple class name of the Avro-generated message type (e.g. `JmeDecreeDocumentCreatedEvent`). |
| `topicName`    | Yes      | Kafka topic the message is consumed from.                                                    |
| `operations`   | Yes      | One or more index operations to execute when the message arrives.                            |

## Operation fields

| Field               | Required | Description                                                                                                                                                                                               |
|---------------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `indexType`         | Yes      | Name of the target `IndexType` — must match the `name()` of a registered `IndexTypeDescriptor` bean.                                                                                                      |
| `indexOperation`    | Yes      | `UPSERT` or `DELETE` (case-insensitive).                                                                                                                                                                  |
| `uri`               | Yes      | URI of the SearchItem Provider endpoint. Spring property placeholders (`${...}`) are resolved at startup.                                                                                                 |
| `oauthClientId`     | No       | The OAuth2 client ID used to authenticate against the SearchItem Provider. Must be registered under `spring.security.oauth2.client.registration`. If omitted, the request is sent without authentication. |
| `referenceProvider` | Yes      | Fully qualified class name of the Spring bean implementing `ReferenceProvider<M>`.                                                                                                                        |
| `condition`         | No       | Fully qualified class name of the Spring bean implementing `IndexingCondition`. The operation is skipped when the condition returns `false`.                                                              |
| `featureFlag`       | No       | Name of a jEAP feature flag. The operation is skipped when the flag is inactive.                                                                                                                          |

## ReferenceProvider

The `ReferenceProvider<M>` interface extracts one or more `OriginReference` objects from a message.
The service executes the configured operation independently for each returned reference:

```java
@Component
class DecreeDocumentReferenceProvider implements ReferenceProvider<JmeDecreeDocumentCreatedEvent> {

    @Override
    public List<OriginReference> extractReference(JmeDecreeDocumentCreatedEvent event) {
        return List.of(
            OriginReference.builder()
                .id(event.getPayload().getDecreeDocumentId())
                .version(event.getPayload().getVersion())
                .build()
        );
    }
}
```

Returning multiple references from a single message is useful when one event triggers indexing of
several related business objects at once.

## IndexingCondition

An `IndexingCondition` bean gates an operation — the operation is skipped when the condition
returns `false`:

```java
@Component
class DecreeDocumentActiveCondition implements IndexingCondition<JmeDecreeDocumentCreatedEvent> {

    @Override
    public boolean shouldIndex(JmeDecreeDocumentCreatedEvent event) {
        return event.getPayload().getStatus() == Status.ACTIVE;
    }
}
```

## Feature flags

Operations can be guarded by a jEAP feature flag. When the flag is inactive the operation is
skipped entirely, without fetching the SearchItem or contacting OpenSearch. The flag name must
match a feature flag registered in the jEAP feature flag configuration.

## Multiple operations per message

A single message can trigger multiple independent operations — for example an event that requires
updating two different index types:

```json
{
  "messageName": "JmeRegistrationCompletedEvent",
  "topicName": "jme-registration-completed",
  "operations": [
    {
      "indexType": "Registration",
      "indexOperation": "UPSERT",
      "uri": "${jme.resource.base-uri}",
      "referenceProvider": "ch.admin.bit.jme.indexwriter.RegistrationReferenceProvider"
    },
    {
      "indexType": "DecreeDocument",
      "indexOperation": "UPSERT",
      "uri": "${jme.resource.base-uri}",
      "referenceProvider": "ch.admin.bit.jme.indexwriter.DecreeDocumentByRegistrationReferenceProvider"
    }
  ]
}
```

## Related

- [Getting started](getting-started.md)
- [Write operations](write-operations.md)
- [Consumer contracts](consumer-contracts.md)
- [jeap-opensearch-index-writer-service](../README.md)
