package ru.archlabs.chat.http;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("serial")
final class ChatServlet extends HttpServlet {
    static final String SESSION_ATTRIBUTE = HttpChatSession.class.getName();
    static final String POLL_REPLY = ChatServlet.class.getName() + ".reply";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final ChatServer chat;
    private final long pollTimeoutMillis;
    private final Set<HttpChatSession> connections = ConcurrentHashMap.newKeySet();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    ChatServlet(ChatServer chat, Duration pollTimeout) {
        this.chat = chat;
        this.pollTimeoutMillis = pollTimeout.toMillis();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            var reply = (HttpChatSession.PollReply) request.getAttribute(POLL_REPLY);
            if (reply.status() == 200) {
                writeJson(response, 200, messages(reply.messages()));
            } else {
                response.setStatus(reply.status());
            }
            return;
        }
        try {
            route(request, response);
        } catch (BadRequest exception) {
            writeJson(response, exception.status, Map.of("error", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            writeJson(response, 400, Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            writeJson(response, 409, Map.of("error", exception.getMessage()));
        } catch (RuntimeException exception) {
            getServletContext().log("Chat request failed", exception);
            writeJson(response, 500, Map.of("error", "Internal server error"));
        }
    }

    private void route(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var path = request.getPathInfo();
        var expectedMethod = switch (path == null ? "" : path) {
            case "/connect", "/messages", "/disconnect" -> "POST";
            case "/notifications" -> request.getMethod().equals("PUT") ? "PUT" : "GET";
            case "/history", "/users" -> "GET";
            default -> throw new BadRequest(404, "Unknown path");
        };
        if (!request.getMethod().equals(expectedMethod)) {
            response.setHeader("Allow", path.equals("/notifications") ? "GET, PUT" : expectedMethod);
            throw new BadRequest(405, "Method not allowed");
        }
        if (path.equals("/connect")) {
            connect(request, response);
            return;
        }
        var httpSession = request.getSession(false);
        var connection = httpSession == null ? null
                : (HttpChatSession) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (connection == null || connection.isClosed()) {
            throw new BadRequest(401, "Connect first");
        }
        switch (path) {
            case "/messages" -> {
                connection.chat().send(text(body(request), "text"));
                response.setStatus(204);
            }
            case "/notifications" -> {
                if (request.getMethod().equals("PUT")) {
                    var enabled = body(request).get("enabled");
                    if (!(enabled instanceof Boolean value)) {
                        throw new BadRequest(400, "enabled must be boolean");
                    }
                    connection.chat().setNotificationsEnabled(value);
                    response.setStatus(204);
                } else {
                    connection.poll(request, pollTimeoutMillis);
                }
            }
            case "/history" -> writeJson(response, 200, messages(connection.chat().history()));
            case "/users" -> {
                var username = request.getParameter("username");
                if (username == null || username.isBlank()) {
                    throw new BadRequest(400, "username must not be blank");
                }
                UserState user;
                try {
                    user = chat.user(username);
                } catch (IllegalArgumentException exception) {
                    throw new BadRequest(404, "Unknown user");
                }
                writeJson(response, 200, user(user));
            }
            case "/disconnect" -> {
                httpSession.invalidate();
                response.setStatus(204);
            }
            default -> throw new BadRequest(404, "Unknown path");
        }
    }

    private void connect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var username = text(body(request), "username");
        var httpSession = request.getSession(true);
        synchronized (httpSession) {
            if (httpSession.getAttribute(SESSION_ATTRIBUTE) != null) {
                throw new BadRequest(409, "Session is already connected");
            }
            var connection = new HttpChatSession(connections::remove);
            connection.attach(chat.connect(username, connection::accept));
            connections.add(connection);
            httpSession.setAttribute(SESSION_ATTRIBUTE, connection);
        }
        writeJson(response, 201, user(chat.user(username)));
    }

    private Map<?, ?> body(HttpServletRequest request) throws IOException {
        var bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BadRequest(413, "Request body is too large");
        }
        try {
            var fields = json.readValue(bytes, Map.class);
            if (fields == null) {
                throw new BadRequest(400, "Expected JSON object");
            }
            return fields;
        } catch (IOException exception) {
            throw new BadRequest(400, "Invalid JSON");
        }
    }

    private static String text(Map<?, ?> fields, String name) {
        if (!(fields.get(name) instanceof String value) || value.isBlank()) {
            throw new BadRequest(400, name + " must be a nonblank string");
        }
        return value;
    }

    private void writeJson(HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(value));
    }

    private static List<Map<String, Object>> messages(List<Message> messages) {
        return messages.stream().map(message -> Map.<String, Object>of(
                "id", message.id(), "sender", message.sender(), "text", message.text(),
                "sentAt", message.sentAt().toString())).toList();
    }

    private static Map<String, Object> user(UserState user) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("name", user.name());
        fields.put("online", user.online());
        fields.put("lastConnected", user.lastConnected() == null ? null : user.lastConnected().toString());
        fields.put("notificationsEnabled", user.notificationsEnabled());
        return fields;
    }

    @Override
    public void destroy() {
        for (var connection : List.copyOf(connections)) {
            connection.close();
        }
        super.destroy();
    }

    @SuppressWarnings("serial")
    static final class BadRequest extends RuntimeException {
        private final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
