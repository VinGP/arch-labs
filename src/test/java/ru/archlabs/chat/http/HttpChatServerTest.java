package ru.archlabs.chat.http;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.archlabs.chat.ChatServerImpl;
import ru.archlabs.chat.storage.H2ChatStorage;
import ru.archlabs.chat.storage.InMemoryChatStorage;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpChatServerTest {
    private static final JsonMapper JSON_CODEC = new JsonMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chatWorksOverHttp(boolean h2) throws Exception {
        var chat = new ChatServerImpl(h2
                ? new H2ChatStorage("jdbc:h2:mem:http-chat") : new InMemoryChatStorage());
        try (var server = new JettyChatServer(chat, 0, Duration.ofMillis(200), 120)) {
            server.start();
            try (var alice = client(); var bob = client()) {
                assertEquals(401, request(alice, server, "GET", "/history", "").statusCode());
                assertEquals(201, request(alice, server, "POST", "/connect", "{\"username\":\"alice\"}").statusCode());
                assertEquals(201, request(bob, server, "POST", "/connect", "{\"username\":\"bob\"}").statusCode());
                assertEquals(400, request(alice, server, "POST", "/messages", "{\"text\":false}").statusCode());
                assertEquals(400, request(alice, server, "POST", "/messages", "{\"text\":\"broken\"").statusCode());
                assertEquals(400, request(alice, server, "POST", "/messages", "{\"text\":\"broken\"} garbage").statusCode());
                assertEquals(204, request(alice, server, "POST", "/messages", "{\"text\":\"Привет \\\"мир\\\"\\n!\"}").statusCode());
                var expected = "Привет \"мир\"\n!";
                assertMessage(request(alice, server, "GET", "/notifications", ""), expected);
                assertMessage(request(bob, server, "GET", "/notifications", ""), expected);
                assertEquals(204, request(bob, server, "GET", "/notifications", "").statusCode());
                assertEquals(204, request(bob, server, "PUT", "/notifications", "{\"enabled\":false}").statusCode());
                assertEquals(204, request(alice, server, "POST", "/messages", "{\"text\":\"second\"}").statusCode());
                assertEquals(204, request(bob, server, "GET", "/notifications", "").statusCode());
                var history = request(bob, server, "GET", "/history", "");
                assertEquals(200, history.statusCode());
                var messages = JSON_CODEC.readValue(history.body(), Object[].class);
                assertEquals(2, messages.length);
                assertEquals(expected, ((Map<?, ?>) messages[0]).get("text"));
                assertEquals(204, request(bob, server, "POST", "/disconnect", "").statusCode());
                assertEquals(401, request(bob, server, "GET", "/history", "").statusCode());
                var state = request(alice, server, "GET", "/users?username=bob", "");
                assertEquals(false, JSON_CODEC.readValue(state.body(), Map.class).get("online"));
            }
        }
    }

    @org.junit.jupiter.api.Test
    void messageWakesPendingPoll() throws Exception {
        try (var server = new JettyChatServer(new ChatServerImpl(new InMemoryChatStorage()), 0,
                Duration.ofSeconds(3), 120)) {
            server.start();
            try (var alice = client(); var bob = client()) {
                request(alice, server, "POST", "/connect", "{\"username\":\"alice\"}");
                request(bob, server, "POST", "/connect", "{\"username\":\"bob\"}");
                // One poll waits; the other gets 409, whichever reaches the server first.
                var first = async(bob, server, "GET", "/notifications", "");
                var second = async(bob, server, "GET", "/notifications", "");
                var rejected = (HttpResponse<?>) CompletableFuture.anyOf(first, second).get(2, TimeUnit.SECONDS);
                assertEquals(409, rejected.statusCode());
                var pending = first.isDone() ? second : first;
                assertFalse(pending.isDone());
                assertEquals(204, request(alice, server, "POST", "/messages", "{\"text\":\"wake up\"}").statusCode());
                assertMessage(pending.get(2, TimeUnit.SECONDS), "wake up");
                var wait = async(bob, server, "GET", "/notifications", "");
                request(bob, server, "POST", "/disconnect", "");
                assertEquals(401, wait.get(2, TimeUnit.SECONDS).statusCode());
            }
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static CompletableFuture<HttpResponse<String>> async(HttpClient client, JettyChatServer server,
                                                                 String method, String path, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> request(HttpClient client, JettyChatServer server,
                                                String method, String path, String body) throws Exception {
        return async(client, server, method, path, body).get(6, TimeUnit.SECONDS);
    }

    private static void assertMessage(HttpResponse<String> response, String text) throws java.io.IOException {
        assertEquals(200, response.statusCode(), response.body());
        var messages = JSON_CODEC.readValue(response.body(), Object[].class);
        assertEquals(1, messages.length);
        var message = (Map<?, ?>) messages[0];
        assertEquals(1L, ((Number) message.get("id")).longValue());
        assertEquals("alice", message.get("sender"));
        assertEquals(text, message.get("text"));
        assertNotNull(message.get("sentAt"));
    }
}
