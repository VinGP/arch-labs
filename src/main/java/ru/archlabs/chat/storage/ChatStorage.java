package ru.archlabs.chat.storage;

import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatStorage extends AutoCloseable {
    void connect(String name, Instant at);

    void disconnect(String name, Instant at);

    void setNotificationsEnabled(String name, boolean enabled);

    Message append(String sender, String text, Instant sentAt);

    List<Message> history();

    Optional<UserState> user(String name);

    List<UserState> onlineUsersWithNotificationsEnabled();

    @Override
    default void close() {
    }
}
