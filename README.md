# Nada People & Connections API

A small REST API for a network of people, built on Vert.x and stored in Neo4j. You can add people, connect them to each other, list a person's direct contacts, and find the shortest chain of connections between two people (up to 3 hops).

## Prerequisites

- Java 17 or newer
- Maven 3.9 or newer
- Docker (to run Neo4j). The unit tests do not need it.

## Run the server

1. Start Neo4j (once; wait about 20 seconds until `docker logs nada-neo4j` shows `Started.`):

```
docker run -d --name nada-neo4j -p 7474:7474 -p 7687:7687 -e NEO4J_AUTH=neo4j/password123 neo4j:5
```

2. Set the password and start the server (PowerShell shown; in bash use `export NEO4J_PASSWORD=password123`):

```
$env:NEO4J_PASSWORD="password123"
mvn compile exec:java
```

The server starts on port 8080 and prints `Server running on http://localhost:8080`. To use another port, pass it as an argument: `mvn compile exec:java -Dexec.args="9090"`. Press Ctrl+C to stop it.

Settings (environment variables):

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEO4J_PASSWORD` | none, required | Neo4j password |
| `NEO4J_URI` | `bolt://localhost:7687` | Where Neo4j listens |
| `NEO4J_USER` | `neo4j` | Neo4j user |
| `STORAGE` | unset | Set to `memory` to run without Neo4j (data is lost on exit) |

The data in Neo4j survives restarts of the server. You can look at the graph in the Neo4j Browser at http://localhost:7474 with `MATCH (p:Person) RETURN p`.

## Run the tests

```
mvn test
```

The tests use an in-memory store, so Neo4j does not have to be running.

## Ids

Every person gets a random 32-bit id, written as 8 lowercase hex characters, for example `3fa9c012`. Ids are strings, not numbers, and they are not sequential.

## Endpoints

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| POST | `/people` | 201 `{"id":"3fa9c012","name":"Asha"}` | 400 invalid JSON, `name` missing, not a string, or blank |
| POST | `/knows` | 204 (no body) | 400 `a` or `b` missing or not a valid id string, or `a` equals `b`; 404 unknown person |
| GET | `/people/{id}/contacts` | 200 JSON array of people (`[]` if none) | 400 malformed id; 404 unknown person |
| GET | `/path?from=<id>&to=<id>` | 200 `{"connected":true,"hops":2,"path":[...]}` or `{"connected":false}` | 400 `from`/`to` missing or malformed; 404 unknown person |

Every error body looks like `{"error":"message"}`. An unknown path returns 404, a known path with the wrong method returns 405, and an unexpected bug returns 500 with `{"error":"internal error"}`.

Examples (bash-style quoting, for example Git Bash; replace the ids with the ones the server returns):

```
curl -X POST http://localhost:8080/people -H "Content-Type: application/json" -d '{"name":"Asha"}'
curl -X POST http://localhost:8080/people -H "Content-Type: application/json" -d '{"name":"Ravi"}'
curl -X POST http://localhost:8080/knows -H "Content-Type: application/json" -d '{"a":"3fa9c012","b":"9b1d4e77"}'
curl http://localhost:8080/people/3fa9c012/contacts
curl "http://localhost:8080/path?from=3fa9c012&to=9b1d4e77"
```

## Design notes

- **Three layers.** `PeopleStore` (an interface) holds the data and has no rules. `PeopleService` holds all the rules (validation, unknown people, self-connection) and has no HTTP or database code. `ApiHandler` only translates between HTTP and the service. `Application` wires them together and starts the server.
- **Graph in Neo4j.** A person is a `(:Person {id, name})` node (a vertex). A connection is one `[:KNOWS]` relationship, read in both directions, so it is mutual. `MERGE` makes a duplicate connection do nothing.
- **Vert.x.** `Application` starts a Vert.x HTTP server with a `Router`. Vert.x handles requests on a few event-loop threads that must never block, so `ApiHandler` copies the request data, runs the service call (which may wait for Neo4j) on Vert.x's worker pool with `executeBlocking(..., false)`, and writes the response back on the event loop. `ordered = false` lets requests run in parallel instead of queueing. The request body is limited to 1 MB.
- **Fine-grained concurrency instead of one lock.** There is no `synchronized` in the service. Each request runs in its own Neo4j transaction, and Neo4j locks only the nodes that transaction touches. Two requests about different people do not wait for each other. The Neo4j `Driver` is shared and thread-safe, and each call opens its own short-lived `Session`. The worker pool lets several of these calls run at once.
- **Random 32-bit ids.** `PersonId.generate()` makes 32 random bits with `SecureRandom` and prints them as 8 hex characters. With only 32 bits two people could in theory get the same id, so Neo4j has a uniqueness constraint on `Person.id` and the store retries with a new id if it is refused. Ids are checked against the format `[0-9a-f]{8}` at the API edge: a malformed id is 400, a well-formed id nobody has is 404.
- **Shortest path.** Neo4j's `shortestPath((a)-[:KNOWS*..3]-(b))` finds the shortest route of at most 3 hops. The in-memory store (used by the tests) does the same with a breadth-first search and a depth cut-off.
- **Errors.** The service throws standard exceptions: `IllegalArgumentException` becomes 400 and `NoSuchElementException` becomes 404.

## Limitations

- The in-memory store (tests and `STORAGE=memory`) still uses one lock and loses its data on exit.
- The order of the contacts returned by Neo4j is not guaranteed.
- There is no limit on name length.
- There is no authentication, and the Neo4j password comes from an environment variable.
- The Neo4j store has no automated test yet.

## What I would do next with more time

- Add integration tests for the Neo4j store (for example with Testcontainers).
- Limit the name length.
- Add a way to remove a person or a connection.
- Add logging and an authentication layer.

## AI tools used

Claude Sonnet (Anthropic) was used to generate the initial code from a prompt I wrote with Claude's help. I reviewed the code myself. (edit if inaccurate)
