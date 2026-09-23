package ru.archlabs.chat;

import ru.archlabs.chat.storage.ChatStorage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class ChatServerImpl implements ChatServer {
    private final ChatStorage storage;
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
            return session;
        }
    }

    @Override
    public UserState user(String username) {
        requireText(username, "Username");
        synchronized (this) {
            return storage
                    .user(username)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + username));
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
        }
        for (var recipient : recipients) {
            try {
                recipient.accept(message);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void setNotificationsEnabled(Session session, boolean enabled) {
        synchronized (this) {
            requireActive(session);
            storage.setNotificationsEnabled(session.username, enabled);
        }
    }

    private List<Message> history(Session session) {
        synchronized (this) {
            requireActive(session);
            var user = storage.user(session.username).orElseThrow();
            if (user.notificationsEnabled()) {
                throw new IllegalStateException("Disable notifications before requesting history");
            }
            return storage.history();
        }
    }

    private void disconnect(Session session) {
        synchronized (this) {
            requireActive(session);
            storage.disconnect(session.username, Instant.now());
            activeSessions.remove(session.username);
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
