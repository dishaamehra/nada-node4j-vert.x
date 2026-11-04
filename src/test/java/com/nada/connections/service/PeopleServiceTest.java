package com.nada.connections.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nada.connections.model.Person;
import com.nada.connections.model.PersonId;
import com.nada.connections.store.InMemoryPeopleStore;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeopleServiceTest {

    // A well-formed id that no created person has (ids are random, so a clash is practically impossible).
    private static final String UNKNOWN_ID = "ffffffff";

    private PeopleService service;

    @BeforeEach
    void setUp() {
        service = new PeopleService(new InMemoryPeopleStore());
    }

    // Creates people P1, P2, ... and connects each one to the previous one: P1-P2-P3-...
    private List<Person> createChain(int length) {
        List<Person> people = new ArrayList<>();
        for (int position = 1; position <= length; position++) {
            Person person = service.createPerson("P" + position);
            if (!people.isEmpty()) {
                Person previous = people.get(people.size() - 1);
                service.connect(previous.id(), person.id());
            }
            people.add(person);
        }
        return people;
    }

    @Test
    void createPerson_assignsDifferentRandomHexIds() {
        Person first = service.createPerson("Asha");
        Person second = service.createPerson("Ravi");

        assertTrue(PersonId.isValid(first.id()));
        assertTrue(PersonId.isValid(second.id()));
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void createPerson_keepsLeadingAndTrailingSpaces() {
        Person person = service.createPerson("  Asha  ");

        assertEquals("  Asha  ", person.name());
    }

    @Test
    void createPerson_keepsHindiNameExactly() {
        Person person = service.createPerson("आशा");

        assertEquals("आशा", person.name());
    }

    @Test
    void createPerson_rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> service.createPerson("   "));
    }

    @Test
    void createPerson_rejectsEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> service.createPerson(""));
    }

    @Test
    void createPerson_rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> service.createPerson(null));
    }

    @Test
    void connect_makesBothPeopleContactsOfEachOther() {
        Person asha = service.createPerson("Asha");
        Person ravi = service.createPerson("Ravi");

        service.connect(asha.id(), ravi.id());

        assertEquals(List.of(ravi), service.getContacts(asha.id()));
        assertEquals(List.of(asha), service.getContacts(ravi.id()));
    }

    @Test
    void connect_duplicateCreatesNoDuplicateContact() {
        Person asha = service.createPerson("Asha");
        Person ravi = service.createPerson("Ravi");

        service.connect(asha.id(), ravi.id());
        service.connect(asha.id(), ravi.id());
        service.connect(ravi.id(), asha.id());

        assertEquals(List.of(ravi), service.getContacts(asha.id()));
        assertEquals(List.of(asha), service.getContacts(ravi.id()));
    }

    @Test
    void connect_rejectsConnectingPersonToThemselves() {
        Person asha = service.createPerson("Asha");

        assertThrows(IllegalArgumentException.class, () -> service.connect(asha.id(), asha.id()));
    }

    @Test
    void connect_rejectsUnknownPerson() {
        Person asha = service.createPerson("Asha");

        NoSuchElementException error =
                assertThrows(NoSuchElementException.class, () -> service.connect(asha.id(), UNKNOWN_ID));

        assertEquals("no person " + UNKNOWN_ID, error.getMessage());
    }

    @Test
    void getContacts_excludesIndirectPeople() {
        List<Person> chain = createChain(3);

        List<Person> contacts = service.getContacts(chain.get(0).id());

        assertEquals(List.of(chain.get(1)), contacts);
    }

    @Test
    void getContacts_rejectsUnknownPerson() {
        assertThrows(NoSuchElementException.class, () -> service.getContacts(UNKNOWN_ID));
    }

    @Test
    void findPath_rejectsUnknownPerson() {
        Person asha = service.createPerson("Asha");

        assertThrows(NoSuchElementException.class, () -> service.findPath(asha.id(), UNKNOWN_ID));
        assertThrows(NoSuchElementException.class, () -> service.findPath(UNKNOWN_ID, asha.id()));
    }

    @Test
    void findPath_samePersonIsZeroHopPath() {
        Person asha = service.createPerson("Asha");

        Optional<List<Person>> path = service.findPath(asha.id(), asha.id());

        assertEquals(Optional.of(List.of(asha)), path);
    }

    @Test
    void findPath_directContactIsOneHop() {
        List<Person> chain = createChain(2);

        Optional<List<Person>> path = service.findPath(chain.get(0).id(), chain.get(1).id());

        assertEquals(Optional.of(chain), path);
    }

    @Test
    void findPath_findsTwoHopPath() {
        List<Person> chain = createChain(3);

        Optional<List<Person>> path = service.findPath(chain.get(0).id(), chain.get(2).id());

        assertEquals(Optional.of(chain), path);
    }

    @Test
    void findPath_choosesShortestPathWhenLongerRouteExists() {
        Person a = service.createPerson("A");
        Person b = service.createPerson("B");
        Person c = service.createPerson("C");
        Person d = service.createPerson("D");
        Person e = service.createPerson("E");
        // Long route A-B-C-D (3 hops) and short route A-E-D (2 hops).
        service.connect(a.id(), b.id());
        service.connect(b.id(), c.id());
        service.connect(c.id(), d.id());
        service.connect(a.id(), e.id());
        service.connect(e.id(), d.id());

        Optional<List<Person>> path = service.findPath(a.id(), d.id());

        assertEquals(Optional.of(List.of(a, e, d)), path);
    }

    @Test
    void findPath_endsWhenConnectionsFormACycle() {
        Person a = service.createPerson("A");
        Person b = service.createPerson("B");
        Person c = service.createPerson("C");
        Person outsider = service.createPerson("Outsider");
        service.connect(a.id(), b.id());
        service.connect(b.id(), c.id());
        service.connect(c.id(), a.id());

        Optional<List<Person>> path = service.findPath(a.id(), outsider.id());

        assertTrue(path.isEmpty());
    }

    @Test
    void findPath_returnsEmptyWhenPeopleAreDisconnected() {
        Person asha = service.createPerson("Asha");
        Person ravi = service.createPerson("Ravi");

        Optional<List<Person>> path = service.findPath(asha.id(), ravi.id());

        assertTrue(path.isEmpty());
    }

    @Test
    void findPath_threeHopsIsWithinTheLimit() {
        List<Person> chain = createChain(4);

        Optional<List<Person>> path = service.findPath(chain.get(0).id(), chain.get(3).id());

        assertEquals(Optional.of(chain), path);
    }

    @Test
    void findPath_fourHopsIsBeyondTheLimit() {
        List<Person> chain = createChain(5);

        Optional<List<Person>> path = service.findPath(chain.get(0).id(), chain.get(4).id());

        assertTrue(path.isEmpty());
    }

    @Test
    void findPath_pathSizeIsAlwaysHopsPlusOne() {
        List<Person> chain = createChain(4);

        for (int hops = 0; hops <= 3; hops++) {
            List<Person> path = service.findPath(chain.get(0).id(), chain.get(hops).id()).get();
            assertEquals(hops + 1, path.size());
        }
    }
}
