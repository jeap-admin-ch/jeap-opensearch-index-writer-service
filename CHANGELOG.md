# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.3.0] - 2026-08-28

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.2.0 → 40.4.0 (minor)

## [5.2.0] - 2026-08-26

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.1.0 → 40.2.0 (minor)

## [5.1.0] - 2026-08-24

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.0.0 → 40.1.0 (minor)

## [5.0.0] - 2026-08-21

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 39.5.0 → 40.0.0 (major)

### Changed
- Install the Avro class whitelist in `KafkaIndexWriterMessageListenerTest`, required by Avro 1.12.2 in tests without a Spring context

## [4.1.0] - 2026-08-19

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 39.0.1 → 39.5.0 (minor)

## [4.0.0] - 2026-08-16

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 38.6.0 → 39.0.1 (major)

## [3.8.0] - 2026-08-13

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 38.1.0 → 38.6.0 (minor)

## [3.7.1] - 2026-08-09

### Dependencies
- **org.apache.httpcomponents.client5:httpclient5**: 5.6.3 → 5.6.4 (patch)

## [3.7.0] - 2026-08-05

### Dependencies
- Updated dependencies

## [3.6.1] - 2026-08-02

### Dependencies
- **org.apache.httpcomponents.client5:httpclient5**: 5.6.2 → 5.6.3 (patch)

## [3.6.0] - 2026-07-31

### Fixed
- `jeap.opensearch.indexwriter.connection.url` is no longer passed unchanged to the AWS transport, which expects a host name and prefixes it with `https://` itself. A configured URL including its scheme therefore resulted in `UnknownHostException: https` on startup when `signing-region` was set, even though the documented configuration contained the scheme.

### Changed
- The connection URL is validated when the configuration properties are initialized. A missing URL, an unsupported scheme, credentials, a path, a query or a fragment now fail the startup with an error naming the property. `http` is rejected when `signing-region` is set, as AWS SigV4 signed requests are always sent over `https`.
- A connection URL without a scheme is interpreted as `https` instead of resulting in an unencrypted connection. Configure `http://` explicitly for a cluster that is reachable without TLS, i.e. a local or test instance.

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 38.0.0 → 38.0.1 (patch)

## [3.5.0] - 2026-07-29

### Fixed
- A failure to deserialize the search item data into the index type's `dataClass()` is wrapped in an `IndexingException` again. Jackson 3 reports a shape mismatch as `JacksonException`, a `RuntimeException`, whereas Jackson 2 wrapped conversion failures in `IllegalArgumentException` — so the `catch` clause no longer matched and the raw Jackson exception escaped without the index type and data class context.

## [3.4.0] - 2026-07-26

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 37.4.0 → 37.6.0 (minor)

## [3.3.0] - 2026-07-23

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 37.2.0 → 37.4.0 (minor)

## [3.2.0] - 2026-07-22

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 37.0.0 → 37.2.0 (minor)

## [3.1.0] - 2026-07-21

### Changed
- Use the standalone WireMock Spring Boot integration without exposed Jetty dependencies.

## [3.0.0] - 2026-07-21

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.10.0 → 37.0.0 (major)

## [2.6.0] - 2026-07-18

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.9.0 → 36.10.0 (minor)

## [2.5.0] - 2026-07-15

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.7.0 → 36.9.0 (minor)

## [2.4.0] - 2026-07-13

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.3.1 → 36.7.0 (minor)

## [2.3.0] - 2026-07-08

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.2.0 → 36.3.1 (minor)

## [2.2.0] - 2026-07-06

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.1.0 → 36.2.0 (minor)

## [2.1.0] - 2026-06-30

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 36.0.0 → 36.1.0 (minor)

## [2.0.0] - 2026-06-30

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 35.11.1 → 36.0.0 (major)
- **org.apache.httpcomponents.client5:httpclient5**: 5.6.1 → 5.6.2 (patch)

## [1.3.0] - 2026-06-29

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 35.11.0 → 35.11.1 (patch)
- **org.apache.httpcomponents.client5:httpclient5**: 5.4.4 → 5.6.1 (minor)

## [1.2.0] - 2026-06-23

### Changed

- Update parent from 35.8.0 to 35.11.0

## [1.1.0] - 2026-06-18

### Changed

- service now creates index templates
- update parent from 35.7.3 to 35.8.0

## [1.0.0] - 2026-06-17

### Changed

- initial official release
