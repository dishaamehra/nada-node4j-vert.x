package com.nada.connections.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.nada.connections.model.Person;
import com.nada.connections.model.PersonId;
import com.nada.connections.service.PeopleService;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

// Turns HTTP requests into PeopleService calls, and results or exceptions into HTTP responses.
// Vert.x runs handlers on a few event-loop threads that must never block. PeopleService may block (Neo4j), so the
// real work runs on Vert.x's worker pool, and only the final write of the response happens back on the event loop.
public class ApiHandler implements Handler<RoutingContext> {

    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";

    private static final String SEGMENT_PEOPLE = "people";
    private static final String SEGMENT_KNOWS = "knows";
    private static final String SEGMENT_PATH = "path";
    private static final String SEGMENT_CONTACTS = "contacts";

    private static final String FIELD_NAME = "name";
    private static final String FIELD_A = "a";
    private static final String FIELD_B = "b";
    private static final String PARAM_FROM = "from";
    private static final String PARAM_TO = "to";

    private static final String KEY_ERROR = "error";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_HOPS = "hops";
    private static final String KEY_PATH = "path";

    private static final String ERROR_NOT_FOUND = "not found";
    private static final String ERROR_METHOD_NOT_ALLOWED = "method not allowed";
    private static final String ERROR_INTERNAL = "internal error";
    private static final String ERROR_INVALID_BODY = "request body must be a JSON object";
    private static final String MUST_BE_ID = " must be an 8-character hex id";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

    private static final int STATUS_OK = 200;
    private static final int STATUS_CREATED = 201;
    private static final int STATUS_NO_CONTENT = 204;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_INTERNAL_ERROR = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Without FAIL_ON_TRAILING_TOKENS, a body like {"name":"Asha"} garbage would be accepted.
    private static final ObjectReader JSON_READER = MAPPER.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // The parts of the HTTP request we need, copied out so the worker thread never touches Vert.x objects.
    private record Request(String method, String path, String rawQuery, byte[] body) {
    }

    // What to send back. A null body means "no content": only the status line and headers.
    private record Reply(int status, byte[] body) {
    }

    private final Vertx vertx;
    private final PeopleService service;

    public ApiHandler(Vertx vertx, PeopleService service) {
        this.vertx = vertx;
        this.service = service;
    }

    @Override
    public void handle(RoutingContext context) {
        Buffer buffer = context.body().buffer();
        Request request = new Request(
                context.request().method().name(),
                context.request().path(),
                context.request().query(),
                buffer == null ? new byte[0] : buffer.getBytes());
        // ordered = false: requests do not queue behind each other, they run in parallel on the worker pool.
        vertx.<Reply>executeBlocking(promise -> promise.complete(respond(request)), false)
                .onComplete(result -> send(context,
                        result.succeeded() ? result.result() : errorReply(STATUS_INTERNAL_ERROR, ERROR_INTERNAL)));
    }

    // Runs on a worker thread. Maps exceptions to status codes, so it never throws.
    private Reply respond(Request request) {
        try {
            return route(request);
        } catch (IllegalArgumentException e) {
            return errorReply(STATUS_BAD_REQUEST, e.getMessage());
        } catch (NoSuchElementException e) {
            return errorReply(STATUS_NOT_FOUND, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            return errorReply(STATUS_INTERNAL_ERROR, ERROR_INTERNAL);
        }
    }

    private Reply route(Request request) {
        String[] segments = request.path().split("/");

        if (isTopLevelPath(segments, SEGMENT_PEOPLE)) {
            return isMethod(request, METHOD_POST) ? handleCreatePerson(request) : methodNotAllowed();
        } else if (isTopLevelPath(segments, SEGMENT_KNOWS)) {
            return isMethod(request, METHOD_POST) ? handleConnect(request) : methodNotAllowed();
        } else if (isTopLevelPath(segments, SEGMENT_PATH)) {
            return isMethod(request, METHOD_GET) ? handlePath(request) : methodNotAllowed();
        } else if (isContactsPath(segments)) {
            return isMethod(request, METHOD_GET) ? handleContacts(segments[2]) : methodNotAllowed();
        }
        return errorReply(STATUS_NOT_FOUND, ERROR_NOT_FOUND);
    }

    // A path such as "/people" splits into ["", "people"].
    private boolean isTopLevelPath(String[] segments, String name) {
        return segments.length == 2 && segments[1].equals(name);
    }

    // "/people/3fa9c012/contacts" splits into ["", "people", "3fa9c012", "contacts"].
    private boolean isContactsPath(String[] segments) {
        return segments.length == 4
                && segments[1].equals(SEGMENT_PEOPLE)
                && segments[3].equals(SEGMENT_CONTACTS);
    }

    private boolean isMethod(Request request, String expectedMethod) {
        return request.method().equals(expectedMethod);
    }

    private Reply methodNotAllowed() {
        return errorReply(STATUS_METHOD_NOT_ALLOWED, ERROR_METHOD_NOT_ALLOWED);
    }

    private Reply handleCreatePerson(Request request) {
        JsonNode body = readJsonObject(request.body());
        JsonNode name = body.get(FIELD_NAME);
        if (name == null || !name.isTextual()) {
            throw new IllegalArgumentException(FIELD_NAME + " must be a string");
        }
        Person person = service.createPerson(name.textValue());
        return jsonReply(STATUS_CREATED, person);
    }

    private Reply handleConnect(Request request) {
        JsonNode body = readJsonObject(request.body());
        String a = readIdField(body, FIELD_A);
        String b = readIdField(body, FIELD_B);
        service.connect(a, b);
        return new Reply(STATUS_NO_CONTENT, null);
    }

    private Reply handleContacts(String idText) {
        String id = parseId(idText, "id");
        List<Person> contacts = service.getContacts(id);
        return jsonReply(STATUS_OK, contacts);
    }

    private Reply handlePath(Request request) {
        Map<String, String> query = parseQuery(request.rawQuery());
        String from = parseId(query.get(PARAM_FROM), PARAM_FROM);
        String to = parseId(query.get(PARAM_TO), PARAM_TO);
        Optional<List<Person>> path = service.findPath(from, to);
        return jsonReply(STATUS_OK, buildPathResponse(path));
    }

    // LinkedHashMap keeps the keys in the order we add them: connected, hops, path.
    private Map<String, Object> buildPathResponse(Optional<List<Person>> path) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (path.isEmpty()) {
            response.put(KEY_CONNECTED, false);
            return response;
        }
        List<Person> people = path.get();
        response.put(KEY_CONNECTED, true);
        response.put(KEY_HOPS, people.size() - 1);
        response.put(KEY_PATH, people);
        return response;
    }

    private JsonNode readJsonObject(byte[] bodyBytes) {
        try {
            JsonNode node = JSON_READER.readValue(bodyBytes);
            if (!node.isObject()) {
                throw new IllegalArgumentException(ERROR_INVALID_BODY);
            }
            return node;
        } catch (IOException e) {
            // Reading from a byte array can only fail because the JSON is invalid or empty.
            throw new IllegalArgumentException(ERROR_INVALID_BODY, e);
        }
    }

    // The id must be a JSON string, so numbers, null, booleans and objects are rejected.
    private String readIdField(JsonNode body, String fieldName) {
        JsonNode value = body.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(fieldName + MUST_BE_ID);
        }
        return parseId(value.textValue(), fieldName);
    }

    // A malformed id is a client mistake (400). A well-formed id nobody has is a missing person (404, from the service).
    private String parseId(String text, String fieldName) {
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!PersonId.isValid(text)) {
            throw new IllegalArgumentException(fieldName + MUST_BE_ID);
        }
        return text;
    }

    // Splits "from=3fa9c012&to=9b1d4e77" by hand and decodes each part as UTF-8.
    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            String[] keyAndValue = pair.split("=", 2);
            String key = URLDecoder.decode(keyAndValue[0], StandardCharsets.UTF_8);
            String value = keyAndValue.length == 2
                    ? URLDecoder.decode(keyAndValue[1], StandardCharsets.UTF_8)
                    : "";
            parameters.put(key, value);
        }
        return parameters;
    }

    private Reply jsonReply(int status, Object body) {
        try {
            return new Reply(status, MAPPER.writeValueAsBytes(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Reply errorReply(int status, String message) {
        return jsonReply(status, Map.of(KEY_ERROR, message));
    }

    // Runs on the event loop.
    private void send(RoutingContext context, Reply reply) {
        HttpServerResponse response = context.response().setStatusCode(reply.status());
        if (reply.body() == null) {
            response.end();
        } else {
            response.putHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON).end(Buffer.buffer(reply.body()));
        }
    }
}
