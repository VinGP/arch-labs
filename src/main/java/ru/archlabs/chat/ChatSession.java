package ru.archlabs.chat;

import java.util.List;

public interface ChatSession extends AutoCloseable {
    void send(String text);

    void setNotificationsEnabled(boolean enabled);

    List<Message> history();

    @Override
    void close();
}
