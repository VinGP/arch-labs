package ru.archlabs.chat.storage;

import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.time.Instant;
import java.util.*;

public final class InMemoryChatStorage implements ChatStorage {
    private final Map<String, UserState> users = new HashMap<>();
    private final List<Message> messages = new ArrayList<>();

    @Override
    public void connect(String name, Instant at) {
        var previous = users.get(name);
        if (previous != null && previous.online()) {
            throw new IllegalStateException("User is already online: " + name);
        }
        users.put(
                name,
                new UserState(
                        name,
                        true,
                        at,
                        previous == null || previous.notificationsEnabled()));
    }

    @Override
    public void disconnect(String name, Instant at) {
        var user = requiredUser(name);
        users.put(name, new UserState(name, false, at, user.notificationsEnabled()));
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        var user = requiredUser(name);
        users.put(name, new UserState(name, user.online(), user.lastConnected(), enabled));
    }

    @Override
    public Message append(String sender, String text, Instant sentAt) {
        var message = new Message(messages.size() + 1L, sender, text, sentAt);
        messages.add(message);
        return message;
    }

    @Override
    public List<Message> history() {
        return List.copyOf(messages);
    }

    @Override
    public Optional<UserState> user(String name) {
        return Optional.ofNullable(users.get(name));
    }

    @Override
    public List<UserState> onlineUsersWithNotificationsEnabled() {
        return users.values().stream()
                .filter(UserState::online)
                .filter(UserState::notificationsEnabled)
                .toList();
    }

    private UserState requiredUser(String name) {
        var user = users.get(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }
}
