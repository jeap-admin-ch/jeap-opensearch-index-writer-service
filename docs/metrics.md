# Metrics

The service publishes Micrometer metrics that can be scraped by Prometheus and visualised in
Grafana.

## Published metrics

| Metric                                  | Type    | Tags            | Description                                                                                                                                                                         |
|-----------------------------------------|---------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.indexing`  | Timer   | `message_type`  | Time spent processing a single indexing operation, including skipped and failed ones. The `message_type` tag contains the simple class name of the triggering Kafka message type.   |

## Notes

The `indexing` timer covers the full end-to-end processing time per operation, from receiving the
Kafka message to completing (or failing) the OpenSearch write. Operations that are skipped due to
an inactive feature flag or a `false` `IndexingCondition` are still recorded — use the timer's
count alongside application logs to distinguish successful writes from skipped operations.

## Related

- [Architecture](architecture.md)
- [Message configuration](message-configuration.md)
- [jeap-opensearch-index-writer-service](../README.md)
