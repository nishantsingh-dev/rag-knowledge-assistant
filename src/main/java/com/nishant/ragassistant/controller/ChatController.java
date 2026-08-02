package com.nishant.ragassistant.controller;

import com.nishant.ragassistant.dto.ChatRequest;
import com.nishant.ragassistant.dto.ChatResponse;
import com.nishant.ragassistant.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        // Rate limiting already happened in ChatRateLimitFilter, before this
        // method was even called - a 429 never reaches here. Semantic cache
        // check happens inside ChatService.ask() itself. Controller stays thin.
        return chatService.ask(request.question());
    }
}
