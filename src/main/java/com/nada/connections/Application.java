package com.nada.connections;

import com.nada.connections.api.ApiHandler;
import com.nada.connections.service.PeopleService;
import com.nada.connections.store.InMemoryPeopleStore;
import com.nada.connections.store.Neo4jPeopleStore;
import com.nada.connections.store.PeopleStore;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.concurrent.ExecutionException;

// Entry point: wires the store, the service and the Vert.x HTTP handler together and starts the server.
public class Application {

    private static final int DEFAULT_PORT = 8080;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final long MAX_BODY_BYTES = 1024 * 1024;
    private static final int EXIT_STATUS_INVALID_PORT = 1;
    private static final int EXIT_STATUS_CONFIG_ERROR = 1;
    private static final String DEFAULT_NEO4J_URI = "bolt://localhost:7687";
    private static final String DEFAULT_NEO4J_USER = "neo4j";
    private static final String INVALID_PORT_MESSAGE = "Invalid port (use a number from 1 to 65535): ";

    // A started server: the Vert.x instance that owns all its threads, and the port it really listens on.
    public record RunningServer(Vertx vertx, int port) {

        public void close() {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = parsePort(args[0]);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(EXIT_STATUS_INVALID_PORT);
            }
        }
        PeopleStore store = createStore();
        RunningServer server = start(port, store);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            store.close();
        }));
        System.out.println("Server running on http://localhost:" + server.port());
    }

    // Port 0 asks the operating system for a free port, which is what the tests use.
    public static RunningServer start(int port, PeopleStore store) throws ExecutionException, InterruptedException {
        Vertx vertx = Vertx.vertx();
        PeopleService service = new PeopleService(store);
        Router router = Router.router(vertx);
        // BodyHandler reads the whole request body (at most 1 MB, otherwise Vert.x answers 413) before ApiHandler runs.
        router.route().handler(BodyHandler.create(false).setBodyLimit(MAX_BODY_BYTES));
        router.route().handler(new ApiHandler(vertx, service));
        int actualPort = vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .toCompletionStage().toCompletableFuture().get()
                .actualPort();
        return new RunningServer(vertx, actualPort);
    }

    // Neo4j is the default. STORAGE=memory switches to the in-memory store (no database needed, data lost on exit).
    private static PeopleStore createStore() {
        if ("memory".equalsIgnoreCase(System.getenv("STORAGE"))) {
            System.out.println("Using in-memory storage");
            return new InMemoryPeopleStore();
        }
        String uri = System.getenv().getOrDefault("NEO4J_URI", DEFAULT_NEO4J_URI);
        String user = System.getenv().getOrDefault("NEO4J_USER", DEFAULT_NEO4J_USER);
        String password = System.getenv("NEO4J_PASSWORD");
        if (password == null) {
            System.err.println("Set the NEO4J_PASSWORD environment variable (or set STORAGE=memory).");
            System.exit(EXIT_STATUS_CONFIG_ERROR);
        }
        try {
            Neo4jPeopleStore store = new Neo4jPeopleStore(uri, user, password);
            System.out.println("Using Neo4j at " + uri);
            return store;
        } catch (RuntimeException e) {
            System.err.println("Could not connect to Neo4j at " + uri + ": " + e.getMessage());
            System.exit(EXIT_STATUS_CONFIG_ERROR);
            throw e;
        }
    }

    private static int parsePort(String text) {
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_PORT_MESSAGE + text, e);
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(INVALID_PORT_MESSAGE + text);
        }
        return port;
    }
}
