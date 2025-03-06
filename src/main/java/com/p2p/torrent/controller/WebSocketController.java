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
        try {
            log.info("WEBSOCKET RECEIVED: type={}, from={}, pieceIndex={}, dataSize={}", 
                    message.getType(), 
                    message.getPeerId(), 
                    message.getPieceIndex(), 
                    message.getData() != null ? message.getData().length : 0);
            
            // Check if this is a piece message (which can be large)
            if (message.getType() == Message.MessageType.PIECE) {
                log.info("PIECE RECEIVED: from peer {} for pieceIndex {}, data size: {}", 
                        message.getPeerId(), 
                        message.getPieceIndex(),
                        message.getData() != null ? message.getData().length : 0);
                
                if (message.getData() == null || message.getData().length == 0) {
                    log.error("EMPTY PIECE DATA: Received piece message with no data from peer {}", 
                            message.getPeerId());
                } else {
                    log.info("VALID PIECE DATA: First few bytes: {}", 
                            bytesToHexPreview(message.getData(), 16));
                }
            } else if (message.getType() == Message.MessageType.REQUEST) {
                log.info("REQUEST RECEIVED: Peer {} requesting piece {}", 
                        message.getPeerId(), message.getPieceIndex());
            } else if (message.getType() == Message.MessageType.CHOKE || 
                       message.getType() == Message.MessageType.UNCHOKE) {
                log.info("CHOKE STATUS CHANGE: {} from peer {}", 
                        message.getType(), message.getPeerId());
            }
            
            // Process the message asynchronously to not block WebSocket thread
            Thread processingThread = new Thread(() -> {
                try {
                    messageService.handleMessage(message);
                } catch (Exception e) {
                    log.error("MESSAGE HANDLING ERROR: {}", e.getMessage(), e);
                }
            });
            processingThread.setName("MsgProcessor-" + message.getType() + "-" + 
                                    (message.getPieceIndex() != null ? message.getPieceIndex() : "null"));
            processingThread.setDaemon(true);
            processingThread.start();
            
        } catch (Exception e) {
            log.error("WEBSOCKET CONTROLLER ERROR: {}", e.getMessage(), e);
        }
    }
    
    // Helper method to visualize binary data for debugging
    private String bytesToHexPreview(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) return "EMPTY";
        
        StringBuilder sb = new StringBuilder();
        int previewLength = Math.min(bytes.length, maxBytes);
        
        for (int i = 0; i < previewLength; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        
        if (bytes.length > maxBytes) {
            sb.append("... (").append(bytes.length).append(" bytes total)");
        }
        
        return sb.toString();
    }
}