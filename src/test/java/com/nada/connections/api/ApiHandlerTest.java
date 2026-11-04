package com.nada.connections.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nada.connections.Application;
import com.nada.connections.model.PersonId;
import com.nada.connections.store.InMemoryPeopleStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    // A well-formed id that no created person has (ids are random, so a clash is practically impossible).
    private static final String UNKNOWN_ID = "ffffffff";

    private Application.RunningServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = Application.start(0, new InMemoryPeopleStore());
        baseUrl = "http://localhost:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    // ---- helpers ----

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    private String createPerson(String name) throws Exception {
        HttpResponse<String> response = post("/people", MAPPER.writeValueAsString(Map.of("name", name)));
        return json(response).get("id").asText();
    }

    private HttpResponse<String> connect(String a, String b) throws Exception {
        return post("/knows", String.format("{\"a\":\"%s\",\"b\":\"%s\"}", a, b));
    }

    private HttpResponse<String> getPath(String from, String to) throws Exception {
        return get("/path?from=" + from + "&to=" + to);
    }

    // Creates people and connects each one to the previous one; returns their ids in order.
    private String[] createChain(int length) throws Exception {
        String[] ids = new String[length];
        for (int position = 0; position < length; position++) {
            ids[position] = createPerson("Person " + (position + 1));
            if (position > 0) {
                connect(ids[position - 1], ids[position]);
            }
        }
        return ids;
    }

    private List<String> contactIds(String personId) throws Exception {
        HttpResponse<String> response = get("/people/" + personId + "/contacts");
        assertEquals(200, response.statusCode());
        List<String> ids = new ArrayList<>();
        for (JsonNode contact : json(response)) {
            ids.add(contact.get("id").asText());
        }
        return ids;
    }

    private List<String> pathIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode person : body.get("path")) {
            ids.add(person.get("id").asText());
        }
        return ids;
    }

    private void assertErrorResponse(HttpResponse<String> response, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.statusCode());
        assertTrue(json(response).get("error").isTextual());
    }

    private void assertErrorMessage(HttpResponse<String> response, int expectedStatus, String expectedMessage)
            throws Exception {
        assertEquals(expectedStatus, response.statusCode());
        assertEquals(expectedMessage, json(response).get("error").asText());
    }

    // ---- POST /people ----

    @Test
    void createPerson_returns201WithIdAndExactName() throws Exception {
        HttpResponse<String> response = post("/people", "{\"name\":\"Asha\"}");

        assertEquals(201, response.statusCode());
        assertTrue(PersonId.isValid(json(response).get("id").asText()));
        assertEquals("Asha", json(response).get("name").asText());
    }

    @Test
    void createPerson_roundTripsHindiNameExactly() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("name", "आशा"));

        HttpResponse<String> response = post("/people", body);

        assertEquals(201, response.statusCode());
        assertEquals("आशा", json(response).get("name").asText());
    }

    @Test
    void createPerson_rejectsInvalidJson() throws Exception {
        assertErrorResponse(post("/people", "{not json"), 400);
    }

    @Test
    void createPerson_rejectsEmptyBody() throws Exception {
        assertErrorResponse(post("/people", ""), 400);
    }

    @Test
    void createPerson_rejectsBodyThatIsNotAnObject() throws Exception {
        assertErrorResponse(post("/people", "[1,2]"), 400);
    }

    @Test
    void createPerson_rejectsContentAfterTheJson() throws Exception {
        assertErrorResponse(post("/people", "{\"name\":\"Asha\"} extra"), 400);
    }

    @Test
    void createPerson_rejectsMissingName() throws Exception {
        assertErrorResponse(post("/people", "{}"), 400);
    }

    @Test
    void createPerson_rejectsNumberAsName() throws Exception {
        assertErrorResponse(post("/people", "{\"name\":123}"), 400);
    }

    @Test
    void createPerson_rejectsNullAsName() throws Exception {
        assertErrorResponse(post("/people", "{\"name\":null}"), 400);
    }

    @Test
    void createPerson_rejectsBooleanAsName() throws Exception {
        assertErrorResponse(post("/people", "{\"name\":true}"), 400);
    }

    @Test
    void createPerson_rejectsEmptyName() throws Exception {
        assertErrorResponse(post("/people", "{\"name\":\"\"}"), 400);
    }

    @Test
    void createPerson_rejectsBlankName() throws Exception {
        assertErrorResponse(post("/people", "{\"name\":\"   \"}"), 400);
    }

    // ---- POST /knows ----

    @Test
    void connect_returns204WithEmptyBody() throws Exception {
        String asha = createPerson("Asha");
        String ravi = createPerson("Ravi");

        HttpResponse<String> response = connect(asha, ravi);

        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void connect_204ResponseHasNoContentType() throws Exception {
        String asha = createPerson("Asha");
        String ravi = createPerson("Ravi");

        HttpResponse<String> response = connect(asha, ravi);

        assertTrue(response.headers().firstValue("Content-Type").isEmpty());
    }

    @Test
    void connect_duplicateConnectionReturns204() throws Exception {
        String asha = createPerson("Asha");
        String ravi = createPerson("Ravi");
        connect(asha, ravi);

        HttpResponse<String> response = connect(asha, ravi);

        assertEquals(204, response.statusCode());
    }

    @Test
    void connect_rejectsSamePersonTwice() throws Exception {
        String asha = createPerson("Asha");

        assertErrorResponse(connect(asha, asha), 400);
    }

    @Test
    void connect_rejectsMissingId() throws Exception {
        createPerson("Asha");

        assertErrorResponse(post("/knows", "{\"a\":\"1a2b3c4d\"}"), 400);
    }

    @Test
    void connect_rejectsNumberAsId() throws Exception {
        createPerson("Asha");
        createPerson("Ravi");

        assertErrorResponse(post("/knows", "{\"a\":1,\"b\":2}"), 400);
    }

    @Test
    void connect_rejectsMalformedId() throws Exception {
        createPerson("Asha");
        createPerson("Ravi");

        assertErrorResponse(post("/knows", "{\"a\":\"xyz\",\"b\":\"1a2b3c4d\"}"), 400);
    }

    @Test
    void connect_unknownPersonReturns404WithMessage() throws Exception {
        String asha = createPerson("Asha");

        assertErrorMessage(connect(asha, UNKNOWN_ID), 404, "no person " + UNKNOWN_ID);
    }

    // ---- GET /people/{id}/contacts ----

    @Test
    void contacts_areVisibleFromBothSides() throws Exception {
        String asha = createPerson("Asha");
        String ravi = createPerson("Ravi");
        connect(asha, ravi);

        assertEquals(List.of(ravi), contactIds(asha));
        assertEquals(List.of(asha), contactIds(ravi));
    }

    @Test
    void contacts_returnsEmptyArrayWhenPersonHasNone() throws Exception {
        String asha = createPerson("Asha");

        HttpResponse<String> response = get("/people/" + asha + "/contacts");

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }

    @Test
    void contacts_excludeIndirectConnections() throws Exception {
        String[] chain = createChain(3);

        assertEquals(List.of(chain[1]), contactIds(chain[0]));
    }

    @Test
    void contacts_rejectsMalformedId() throws Exception {
        assertErrorResponse(get("/people/abc/contacts"), 400);
    }

    @Test
    void contacts_unknownIdReturns404() throws Exception {
        assertErrorMessage(get("/people/" + UNKNOWN_ID + "/contacts"), 404, "no person " + UNKNOWN_ID);
    }

    // ---- GET /path ----

    @Test
    void path_returnsTwoHopRoute() throws Exception {
        String[] chain = createChain(3);

        HttpResponse<String> response = getPath(chain[0], chain[2]);

        assertEquals(200, response.statusCode());
        JsonNode body = json(response);
        assertTrue(body.get("connected").asBoolean());
        assertEquals(2, body.get("hops").asInt());
        assertEquals(List.of(chain[0], chain[1], chain[2]), pathIds(body));
    }

    @Test
    void path_keysAreInTheDocumentedOrder() throws Exception {
        String[] chain = createChain(2);

        HttpResponse<String> response = getPath(chain[0], chain[1]);

        assertTrue(response.body().startsWith("{\"connected\":true,\"hops\":1,\"path\":["));
    }

    @Test
    void path_samePersonIsZeroHops() throws Exception {
        String asha = createPerson("Asha");

        JsonNode body = json(getPath(asha, asha));

        assertTrue(body.get("connected").asBoolean());
        assertEquals(0, body.get("hops").asInt());
        assertEquals(List.of(asha), pathIds(body));
    }

    @Test
    void path_threeHopChainIsConnected() throws Exception {
        String[] chain = createChain(4);

        JsonNode body = json(getPath(chain[0], chain[3]));

        assertTrue(body.get("connected").asBoolean());
        assertEquals(3, body.get("hops").asInt());
    }

    @Test
    void path_fourHopChainIsNotConnected() throws Exception {
        String[] chain = createChain(5);

        HttpResponse<String> response = getPath(chain[0], chain[4]);

        assertEquals(200, response.statusCode());
        JsonNode body = json(response);
        assertFalse(body.get("connected").asBoolean());
        assertFalse(body.has("hops"));
    }

    @Test
    void path_rejectsMissingFrom() throws Exception {
        assertErrorResponse(get("/path?to=1a2b3c4d"), 400);
    }

    @Test
    void path_rejectsMissingTo() throws Exception {
        assertErrorResponse(get("/path?from=1a2b3c4d"), 400);
    }

    @Test
    void path_rejectsMalformedFrom() throws Exception {
        assertErrorResponse(get("/path?from=abc&to=1a2b3c4d"), 400);
    }

    @Test
    void path_rejectsMalformedTo() throws Exception {
        assertErrorResponse(get("/path?from=1a2b3c4d&to=xyz"), 400);
    }

    @Test
    void path_unknownPersonReturns404() throws Exception {
        String asha = createPerson("Asha");

        assertErrorMessage(getPath(asha, UNKNOWN_ID), 404, "no person " + UNKNOWN_ID);
    }

    // ---- routing and response format ----

    @Test
    void unknownRoute_returns404() throws Exception {
        assertErrorMessage(get("/nothing"), 404, "not found");
    }

    @Test
    void knownPathWithWrongMethod_returns405WhenGettingPeople() throws Exception {
        assertErrorMessage(get("/people"), 405, "method not allowed");
    }

    @Test
    void knownPathWithWrongMethod_returns405WhenPostingPath() throws Exception {
        assertErrorMessage(post("/path", "{}"), 405, "method not allowed");
    }

    @Test
    void successResponse_hasUtf8JsonContentType() throws Exception {
        HttpResponse<String> response = post("/people", "{\"name\":\"Asha\"}");

        assertEquals(JSON_CONTENT_TYPE, response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void errorResponse_hasUtf8JsonContentType() throws Exception {
        HttpResponse<String> response = get("/nothing");

        assertEquals(JSON_CONTENT_TYPE, response.headers().firstValue("Content-Type").orElse(""));
    }
}
