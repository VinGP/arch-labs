package ru.archlabs.chat;

import java.util.function.Consumer;

public interface ChatServer extends AutoCloseable {
    ChatSession connect(String username, Consumer<Message> onMessage);

    UserState user(String username);

    @Override
    void close();
}
