# Oak Chain Connector

AEM 6.5 LTS author can mount Oak Chain content read-only at `/oak-chain`, next to `/content`,
through Apache Oak's own composite node store. This repository is that integration: one OSGi
bundle and an opt-in tool in the same jar (`preflight`, `enable`, `verify`, `status`,
`disable`), applied with one restart. No Oak bundle is changed.

- Operators: [AEM 6.5 LTS Integration](https://oakchain.net/guide/aem-integration)
- Developers: [`mount/README.md`](mount/README.md)

```bash
mvn install    # builds mount/ (JDK 17+, Maven 3.9+)
JAR=$(ls mount/target/oak-chain-connector.mount-*.jar)
java -jar $JAR preflight /opt/aem/author --validator http://validator.example.internal:8090
```

## Scope

| | |
|---|---|
| Supported | AEM 6.5 LTS author on TarMK, Java 17 or 21. Demonstrated on SP3 (Apache Oak 2.4.0). |
| Not supported | AEM as a Cloud Service, AEM 6.5 (non-LTS), MongoMK. Publish instances are untested. |
| Read-only | AEM reads chain content. Writes go through the validator API or the Oak Chain SDK. |
| Support boundary | Adobe does not support a composite node store on AEM 6.5 LTS with TarMK; opting in accepts that, opting out restores the original configuration byte for byte. |

## History

- Tag `legacy/http-persistence-connector`: the earlier connector (`core`, `ui.apps`,
  `ui.config`, `all`) with an HTTP `SegmentNodeStorePersistence` and wallet write services,
  built for AEM 6.5 classic on Oak 1.x. It needed the Oak fork's `HttpSegmentStoreSync` and
  does not resolve on AEM 6.5 LTS. Superseded by `mount/`.
- Branch `archive/2026-03-oak-bootstrap-wip`: the March 2026 composite-bootstrap attempt on
  top of it, kept for history.
