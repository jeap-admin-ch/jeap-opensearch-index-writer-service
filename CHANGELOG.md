# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.16.1] - 2026-06-11

### Fixed

- Fix bug of deploying index type maven artifacts on trunk

## [0.16.0] - 2026-06-09

### Changed

- Add support for reading multiple index type versions

## [0.15.0] - 2026-06-08

### Changed

- Add major version to mapping file and validate in index type registry maven plugin 

## [0.14.0] - 2026-06-08

### Changed

- Create new standard API to retrieve SearchItems to be indexed by the index writer service

## [0.13.0] - 2026-06-03

### Changed

- refactor the attribute reference in Origin as Map<String, String> instead of JsonNode

## [0.12.0] - 2026-06-03

### Changed

- the oauth2 client id can now be configured in each operation (instead of being global for all operations)

## [0.11.0] - 2026-05-20

### Changed

- update parent to 33.9.0

## [0.9.0] - 2026-05-18

### Changed

- added jeap-opensearch-client-starter

## [0.8.0] - 2026-05-07

### Changed

- implement OpenSearch integration

## [0.7.0] - 2026-04-29

### Changed

- fully qualified name for configurable beans

## [0.6.0] - 2026-04-29

### Changed

- Read and validate the configuration, then process messages without indexing them yet.

## [0.5.0] - 2026-04-22

### Changed

- add index-type and index-type-registry-maven-plugin

## [0.4.0] - 2026-04-16

### Changed

- Update parent from 33.2.0 to 33.3.0

## [0.3.0] - 2026-04-13

### Changed

- Remove swagger log, because we don't use it.

## [0.2.0] - 2026-04-13

### Changed

- Update parent from 33.1.1 to 33.2.0

## [0.1.0] - 2026-04-09

### Changed

- Update parent from 33.0.0 to 33.1.1

## [0.0.1] - 2026-04-09

### Changed

- First pre version, skeleton created.
