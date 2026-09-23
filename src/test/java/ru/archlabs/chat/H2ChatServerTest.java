package ru.archlabs.chat;

import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.chat.storage.H2ChatStorage;

final class H2ChatServerTest extends ChatServerContractTest {
  @Override
  ChatStorage createStorage() {
    return new H2ChatStorage("jdbc:h2:mem:chat");
  }
}
