## Frontcache = [includes + fragment cache] x edge side


- Licensing: https://www.eternita.co/frontcache.html

#### Get started

|                       |                                                                                                                |
|-----------------------|----------------------------------------------------------------------------------------------------------------|
| **See it**            | [Interactive walkthrough](docs/demo/) — 7 schematic scenes, 5:30, drive it yourself                  |
| **See the payoff**    | [Value proposition](docs/value.md) — measured A/B on 100k real requests                             |
| **Understand it**     | [Concepts](docs/concept.md) — fragment caching, request lifecycle, ...                              |
| **Install it**        | [Install guide](docs/install-guide.md) — library, archive, installer script, or container                      |
| **Download it**       | [Downloads](docs/downloads.md) — direct links to every artifact, 2.9.0 and 2.8.0                               |
| **Choose a topology** | [Deployment use cases](docs/deployment-usecases.md) — filter, standalone proxy, or multi-region                |
| **See it work**       | [Java / Spring Boot](examples/frontcache-spring) · [PHP](examples/frontcache-php) — each runs with one command |
| **See what changed**  | [Release notes](docs/release-notes/) — 2.7.0, 2.8.0, 2.9.0                                                     |
| **Read more**         | [Documentation index](docs/doc-index.md)         |

Quickest look, if you have Docker:

```sh
docker run -d --name frontcache -p 8080:9080 \
  -e ORIGIN_HOST=your-origin.example.com \
  pavlikovskiy/frontcache-server:2.9.0
```


#### Features

* Reduce server response time for dynamic pages 

* Reduce backend load dozen times! 
   
* Increase application's resilience

* Can be used with web apps written in any language


![Alt](docs/images/how-it-works-details.png "Frontcache overview")


