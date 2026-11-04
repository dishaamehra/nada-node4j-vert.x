package com.nada.connections.store;

import com.nada.connections.model.Person;
import com.nada.connections.model.PersonId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

// In-memory store used by the unit tests and when STORAGE=memory. It is not fine-grained: every method takes one lock.
// The real, concurrent store is Neo4jPeopleStore.
public class InMemoryPeopleStore implements PeopleStore {

    private final Map<String, Person> people = new LinkedHashMap<>();
    // Adjacency map: person id -> ids of direct contacts. A Set makes duplicate contacts impossible.
    private final Map<String, Set<String>> contacts = new LinkedHashMap<>();

    @Override
    public synchronized Person addPerson(String name) {
        String id = PersonId.generate();
        while (people.containsKey(id)) {
            id = PersonId.generate();
        }
        Person person = new Person(id, name);
        people.put(id, person);
        contacts.put(id, new LinkedHashSet<>());
        return person;
    }

    @Override
    public synchronized Optional<Person> findPerson(String id) {
        return Optional.ofNullable(people.get(id));
    }

    @Override
    public synchronized void addConnection(String a, String b) {
        contacts.get(a).add(b);
        contacts.get(b).add(a);
    }

    @Override
    public synchronized List<Person> getContacts(String id) {
        List<Person> result = new ArrayList<>();
        for (String contactId : contacts.get(id)) {
            result.add(people.get(contactId));
        }
        return result;
    }

    // Breadth-first search reaches people in order of hops, so the first route found to the target is a shortest one.
    @Override
    public synchronized Optional<List<Person>> findShortestPath(String from, String to, int maxHops) {
        Map<String, String> parents = new HashMap<>();
        Map<String, Integer> depths = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        parents.put(from, from);
        depths.put(from, 0);
        queue.add(from);

        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (current.equals(to)) {
                break;
            }
            // Depth cut-off: people already maxHops away are not expanded, so longer paths are never found.
            if (depths.get(current) < maxHops) {
                for (String contactId : contacts.get(current)) {
                    // The parents map is also the visited set, so cycles cannot loop forever.
                    if (!parents.containsKey(contactId)) {
                        parents.put(contactId, current);
                        depths.put(contactId, depths.get(current) + 1);
                        queue.add(contactId);
                    }
                }
            }
        }
        if (!parents.containsKey(to)) {
            return Optional.empty();
        }
        return Optional.of(buildPath(parents, from, to));
    }

    private List<Person> buildPath(Map<String, String> parents, String from, String to) {
        List<Person> path = new ArrayList<>();
        String current = to;
        // Walk from the target back to the start; the list is built backwards, so reverse it at the end.
        while (!current.equals(from)) {
            path.add(people.get(current));
            current = parents.get(current);
        }
        path.add(people.get(from));
        Collections.reverse(path);
        return path;
    }
}
