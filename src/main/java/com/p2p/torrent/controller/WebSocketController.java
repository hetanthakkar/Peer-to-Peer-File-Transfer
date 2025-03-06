package com.p2p.torrent.controller;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import com.p2p.torrent.model.Message;
import com.p2p.torrent.service.MessageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@Slf4j
public class WebSocketController {
    private final MessageService messageService;
    
    @MessageMapping("/message")
    public void processMessage(@Payload Message message, SimpMessageHeaderAccessor headerAccessor) {
        log.info("Received WebSocket message: type={}, from={}, pieceIndex={}, dataSize={}", 
                message.getType(), 
                message.getPeerId(), 
                message.getPieceIndex(), 
                message.getData() != null ? message.getData().length : 0);
        
        messageService.handleMessage(message);
    }
}