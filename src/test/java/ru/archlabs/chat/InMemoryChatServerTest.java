package ru.archlabs.chat;

import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.chat.storage.InMemoryChatStorage;

final class InMemoryChatServerTest extends ChatServerContractTest {
    @Override
    ChatStorage createStorage() {
        return new InMemoryChatStorage();
    }
}
