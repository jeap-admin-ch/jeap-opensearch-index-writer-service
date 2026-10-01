# Metrics

The service publishes Micrometer metrics that can be scraped by Prometheus and visualised in
Grafana.

## Published metrics

| Metric                                         | Type    | Tags                         | Description                                                                                                                                                                         |
|-------------------------------------------------|---------|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.indexing`           | Timer   | `message_type`, `operation`  | Time spent processing a single indexing operation, including skipped and failed ones. The `message_type` tag contains the simple class name of the triggering Kafka message type. |
| `jeap.opensearch.indexwriter.kafka.lag`          | Timer   | `message_type`                | Time between the triggering message's creation (set by the publishing producer) and the start of its processing here — i.e. Kafka/network transit time. Omitted if the message has no creation timestamp. |
| `jeap.opensearch.indexwriter.searchitem.fetch`   | Timer   | `index_type`                   | Time spent calling the owning domain service's SearchItem Provider endpoint to retrieve the current search representation.                                                        |
| `jeap.opensearch.indexwriter.opensearch.write`   | Timer   | `index_type`, `operation`      | Time spent writing a document to OpenSearch. The `operation` tag (`upsert` or `delete`) allows breaking down write time by write kind.                                              |

## Notes

The `indexing` timer covers the full end-to-end processing time per operation, from receiving the
Kafka message to completing (or failing) the OpenSearch write. Operations that are skipped due to
an inactive feature flag or a `false` `IndexingCondition` are still recorded — use the timer's
count alongside application logs to distinguish successful writes from skipped operations.

For load/performance testing, use `kafka.lag`, `searchitem.fetch` and `opensearch.write` together
with `indexing` to break down where processing time is spent: Kafka/network transit, the remote
SearchItem fetch, and the OpenSearch write itself. The remainder of `indexing` not covered by
`searchitem.fetch` and `opensearch.write` is internal IWS overhead (validation, deserialization,
reference extraction).

## Related

- [Architecture](architecture.md)
- [Message configuration](message-configuration.md)
- [jeap-opensearch-index-writer-service](../README.md)
