package ru.archlabs.chat;

import io.prometheus.metrics.core.metrics.Counter;
import ru.archlabs.chat.storage.ChatStorage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class ChatServerImpl implements ChatServer {
    private final ChatStorage storage;
    private static final Counter REQUESTS_METRIC = Counter.builder()
            .name("chat_requests_total")
            .help("Successfully processed user requests")
            .register();
    private static final Counter NOTIFICATIONS_METRIC = Counter.builder()
            .name("chat_notifications_total")
            .help("Messages successfully delivered to user callbacks")
            .register();
    private final Map<String, ActiveSession> activeSessions = new LinkedHashMap<>();

    public ChatServerImpl(ChatStorage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    @Override
    public ChatSession connect(String username, Consumer<Message> onMessage) {
        requireText(username, "Username");
        Objects.requireNonNull(onMessage, "onMessage");
        synchronized (this) {
            if (activeSessions.containsKey(username)) {
                throw new IllegalStateException("User is already connected: " + username);
            }
            storage.connect(username, Instant.now());
            var session = new Session(username);
            activeSessions.put(username, new ActiveSession(session, onMessage));
            REQUESTS_METRIC.inc();
            return session;
        }
    }

    @Override
    public UserState user(String username) {
        requireText(username, "Username");
        synchronized (this) {
            var user = storage
                    .user(username)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
            REQUESTS_METRIC.inc();
            return user;
        }
    }

    private void send(Session session, String text) {
        requireText(text, "Message");
        Message message;
        List<Consumer<Message>> recipients;
        synchronized (this) {
            requireActive(session);
            message = storage.append(session.username, text, Instant.now());
            recipients =
                    storage.onlineUsersWithNotificationsEnabled().stream()
                            .map(UserState::name)
                            .map(activeSessions::get)
                            .filter(Objects::nonNull)
                            .map(ActiveSession::onMessage)
                            .toList();
            REQUESTS_METRIC.inc();
        }
        for (var recipient : recipients) {
            try {
                recipient.accept(message);
                NOTIFICATIONS_METRIC.inc();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void setNotificationsEnabled(Session session, boolean enabled) {
        synchronized (this) {
            requireActive(session);
            storage.setNotificationsEnabled(session.username, enabled);
            REQUESTS_METRIC.inc();
        }
    }

    private List<Message> history(Session session) {
        synchronized (this) {
            requireActive(session);
            var user = storage.user(session.username).orElseThrow();
            if (user.notificationsEnabled()) {
                throw new IllegalStateException("Disable notifications before requesting history");
            }
            var messages = storage.history();
            REQUESTS_METRIC.inc();
            return messages;
        }
    }

    private void disconnect(Session session) {
        synchronized (this) {
            requireActive(session);
            storage.disconnect(session.username, Instant.now());
            activeSessions.remove(session.username);
            REQUESTS_METRIC.inc();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            for (var username : List.copyOf(activeSessions.keySet())) {
                storage.disconnect(username, Instant.now());
            }
            activeSessions.clear();
            storage.close();
        }
    }

    private void requireActive(Session session) {
        var active = activeSessions.get(session.username);
        if (active == null || active.session() != session) {
            throw new IllegalStateException("Session is not active");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private final class Session implements ChatSession {
        private final String username;
        private boolean closed;

        private Session(String username) {
            this.username = username;
        }

        @Override
        public void send(String text) {
            requireOpen();
            ChatServerImpl.this.send(this, text);
        }

        @Override
        public void setNotificationsEnabled(boolean enabled) {
            requireOpen();
            ChatServerImpl.this.setNotificationsEnabled(this, enabled);
        }

        @Override
        public List<Message> history() {
            requireOpen();
            return ChatServerImpl.this.history(this);
        }

        @Override
        public void close() {
            if (!closed) {
                ChatServerImpl.this.disconnect(this);
                closed = true;
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Session is closed");
            }
        }
    }

    private record ActiveSession(Session session, Consumer<Message> onMessage) {
    }
}
