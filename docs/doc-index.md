# Doc Index


| |                                                                                                                         |
| --- |-------------------------------------------------------------------------------------------------------------------------|
| **See the payoff** | [Value proposition](value.md) — measured A/B: 6.1x throughput, 98.7% less origin load |
| **Measure it yourself** | [Benchmark harness](../benchmark) — replay your own request log against your own node |
| **Understand it** | [Concepts](concept.md) — fragment caching, request lifecycle, ...                                            |
| **Install it** | [Install guide](install-guide.md) — library, archive, installer script, or container                                    |
| **Run it in containers** | [Docker options](docker.md) — server, console, nginx front door, or the ELK stack                            |
| **Choose a topology** | [Deployment use cases](deployment-usecases.md) — filter, standalone proxy, or multi-region                              |
| **See it work** | [Java / Spring Boot](../examples/frontcache-spring) · [PHP](../examples/frontcache-php) — each runs with one command    |
| **Tell it what to cache** | [HTTP headers](http-headers.md) — the header contract an origin drives caching with, in any language                 |
| **...from a Java app** | [JSP tags](jsp-tags.md) — `fc:component` and `fc:include`, the tag form of those headers                            |
| **Protect a node** | [Guard rules](guard-getting-started.md) - another way to reduce load on origin                               |
| **Survive a bad origin** | [Resilience command flow](resilience-command-flow.md) — how every origin call is circuit-broken, and what serves a fallback |
| **Lock it down** | [Security](security.md) — the site key, the management port, and the invalidation blast radius                  |
| **Put it behind nginx** | [Front door](../examples/front-door) — nginx on 80/443 in front of Frontcache, as containers or on a VM                 |
| **Watch it** | [Console and dashboards](console-dashboards.md) — the console UI, and getting metrics into Prometheus / Grafana / OTLP  |
| **See what it is doing** | [Log analytics](../examples/log-analytics) — pull the logs into Elasticsearch + Kibana, with four ready-made dashboards |
