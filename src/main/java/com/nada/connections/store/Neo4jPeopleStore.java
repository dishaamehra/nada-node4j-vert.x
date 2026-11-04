package com.nada.connections.store;

import com.nada.connections.model.Person;
import com.nada.connections.model.PersonId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ClientException;

// Stores people as (:Person {id, name}) nodes and connections as [:KNOWS] relationships in Neo4j.
// There is no application-level lock: each call runs in its own Neo4j transaction, and Neo4j locks only the
// nodes that transaction touches. Two requests about different people therefore do not wait for each other.
public class Neo4jPeopleStore implements PeopleStore {

    private static final String CONSTRAINT_VIOLATION = "Neo.ClientError.Schema.ConstraintValidationFailed";
    private static final int MAX_ID_ATTEMPTS = 10;

    // Also creates an index on id, so looking a person up by id is fast.
    private static final String CREATE_UNIQUE_ID_CONSTRAINT =
            "CREATE CONSTRAINT person_id_unique IF NOT EXISTS FOR (p:Person) REQUIRE p.id IS UNIQUE";
    private static final String CREATE_PERSON = "CREATE (:Person {id: $id, name: $name})";
    private static final String FIND_PERSON = "MATCH (p:Person {id: $id}) RETURN p.id AS id, p.name AS name";
    // MERGE creates the relationship only if there is none yet, in either direction, so a duplicate does nothing.
    private static final String CONNECT =
            "MATCH (a:Person {id: $a}), (b:Person {id: $b}) MERGE (a)-[:KNOWS]-(b)";
    private static final String CONTACTS =
            "MATCH (:Person {id: $id})-[:KNOWS]-(c:Person) RETURN DISTINCT c.id AS id, c.name AS name";

    // A Driver is thread-safe and shared by all requests. A Session is not, so every call opens its own.
    private final Driver driver;

    public Neo4jPeopleStore(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        try {
            driver.verifyConnectivity();
            write(CREATE_UNIQUE_ID_CONSTRAINT, Map.of());
        } catch (RuntimeException e) {
            driver.close();
            throw e;
        }
    }

    @Override
    public Person addPerson(String name) {
        // The ids are only 32 random bits, so two people could in theory get the same one. The unique constraint
        // makes Neo4j refuse the second one, and we simply try again with a new id.
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            String id = PersonId.generate();
            try {
                write(CREATE_PERSON, Map.of("id", id, "name", name));
                return new Person(id, name);
            } catch (ClientException e) {
                if (!CONSTRAINT_VIOLATION.equals(e.code())) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("could not generate a unique person id");
    }

    @Override
    public Optional<Person> findPerson(String id) {
        return read(FIND_PERSON, Map.of("id", id), result ->
                result.hasNext() ? Optional.of(personFromRow(result.next())) : Optional.<Person>empty());
    }

    @Override
    public void addConnection(String a, String b) {
        write(CONNECT, Map.of("a", a, "b", b));
    }

    @Override
    public List<Person> getContacts(String id) {
        return read(CONTACTS, Map.of("id", id), result -> result.list(Neo4jPeopleStore::personFromRow));
    }

    @Override
    public Optional<List<Person>> findShortestPath(String from, String to, int maxHops) {
        if (maxHops < 1) {
            throw new IllegalArgumentException("maxHops must be at least 1");
        }
        // Cypher does not allow a parameter for the length bound, so the number is written into the text.
        // It is an int and never user input, so this cannot be abused for injection.
        String query = "MATCH (a:Person {id: $from}), (b:Person {id: $to}) "
                + "MATCH p = shortestPath((a)-[:KNOWS*.." + maxHops + "]-(b)) "
                + "RETURN [n IN nodes(p) | {id: n.id, name: n.name}] AS people";
        return read(query, Map.of("from", from, "to", to), result -> {
            if (!result.hasNext()) {
                return Optional.<List<Person>>empty();
            }
            List<Person> people = result.next().get("people").asList(Neo4jPeopleStore::personFromValue);
            return Optional.of(people);
        });
    }

    @Override
    public void close() {
        driver.close();
    }

    // The query result must be fully read inside the transaction function, which is why the mapper runs there.
    private <T> T read(String query, Map<String, Object> parameters, Function<Result, T> mapper) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> mapper.apply(tx.run(query, parameters)));
        }
    }

    private void write(String query, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(query, parameters).consume();
                return null;
            });
        }
    }

    private static Person personFromRow(Record row) {
        return new Person(row.get("id").asString(), row.get("name").asString());
    }

    // For the list of maps built by the path query.
    private static Person personFromValue(Value node) {
        return new Person(node.get("id").asString(), node.get("name").asString());
    }
}
