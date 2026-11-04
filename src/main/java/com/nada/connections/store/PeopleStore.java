package com.nada.connections.store;

import com.nada.connections.model.Person;
import java.util.List;
import java.util.Optional;

// Where people and connections are kept. PeopleService holds the rules and talks only to this interface,
// so the data can live in Neo4j (real use) or in memory (fast tests).
public interface PeopleStore extends AutoCloseable {

    // Creates a person with a new random id.
    Person addPerson(String name);

    Optional<Person> findPerson(String id);

    // Makes the two people contacts of each other. The caller has already checked that both exist.
    void addConnection(String a, String b);

    List<Person> getContacts(String id);

    // Shortest route from one person to another that is at most maxHops connections long, or empty if none.
    // The caller guarantees from and to are different people that exist.
    Optional<List<Person>> findShortestPath(String from, String to, int maxHops);

    @Override
    default void close() {
    }
}
