package com.nada.connections.service;

import com.nada.connections.model.Person;
import com.nada.connections.store.PeopleStore;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

// All application rules live here and there is no HTTP code and no storage code.
// There is no lock in this class: thread safety is the store's job (Neo4j transactions), so requests that
// touch different people can run at the same time.
public class PeopleService {

    public static final int MAX_HOPS = 3;

    private static final String BLANK_NAME_MESSAGE = "name must not be blank";
    private static final String SELF_CONNECTION_MESSAGE = "a person cannot be connected to themselves";
    private static final String UNKNOWN_PERSON_PREFIX = "no person ";

    private final PeopleStore store;

    public PeopleService(PeopleStore store) {
        this.store = store;
    }

    public Person createPerson(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(BLANK_NAME_MESSAGE);
        }
        return store.addPerson(name);
    }

    public void connect(String a, String b) {
        if (a.equals(b)) {
            throw new IllegalArgumentException(SELF_CONNECTION_MESSAGE);
        }
        requirePerson(a);
        requirePerson(b);
        store.addConnection(a, b);
    }

    public List<Person> getContacts(String id) {
        requirePerson(id);
        return store.getContacts(id);
    }

    public Optional<List<Person>> findPath(String from, String to) {
        Person start = requirePerson(from);
        requirePerson(to);
        if (from.equals(to)) {
            return Optional.of(List.of(start));
        }
        return store.findShortestPath(from, to, MAX_HOPS);
    }

    private Person requirePerson(String id) {
        return store.findPerson(id).orElseThrow(() -> new NoSuchElementException(UNKNOWN_PERSON_PREFIX + id));
    }
}
