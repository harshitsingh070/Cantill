package com.corvo.procurement.api;

import com.corvo.procurement.chat.ChatService;
import com.corvo.procurement.domain.ChatMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/messages")
    public Map<String, Object> message(@RequestBody Map<String, Object> body) {
        return chatService.message(body);
    }

    @GetMapping("/sessions/{id}/messages")
    public List<ChatMessage> messages(@PathVariable Long id) {
        return chatService.messages(id);
    }
}
