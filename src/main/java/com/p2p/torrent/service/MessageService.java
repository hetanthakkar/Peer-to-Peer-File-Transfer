package com.p2p.torrent.service;

import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.p2p.torrent.model.FilePiece;
import com.p2p.torrent.model.Message;
import com.p2p.torrent.model.Peer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {
    private final SimpMessagingTemplate messagingTemplate;
    private final PeerService peerService;
    private final FileService fileService;
    
    private String localPeerId;
    
    public void setLocalPeerId(String peerId) {
        this.localPeerId = peerId;
    }
    
    public String getLocalPeerId() {
        return localPeerId;
    }
    
    public void handleMessage(Message message) {
        String senderPeerId = message.getPeerId();
        
        switch (message.getType()) {
            case CHOKE:
                handleChoke(senderPeerId);
                break;
            case UNCHOKE:
                handleUnchoke(senderPeerId);
                break;
            case INTERESTED:
                handleInterested(senderPeerId);
                break;
            case NOT_INTERESTED:
                handleNotInterested(senderPeerId);
                break;
            case HAVE:
                handleHave(senderPeerId, message.getPieceIndex());
                break;
            case BITFIELD:
                handleBitfield(senderPeerId, message.getBitfield());
                break;
            case REQUEST:
                handleRequest(senderPeerId, message.getPieceIndex());
                break;
            case PIECE:
                handlePiece(senderPeerId, message.getPieceIndex(), message.getData());
                break;
            case HANDSHAKE:
                handleHandshake(senderPeerId);
                break;
            default:
                log.warn("Received unknown message type: {}", message.getType());
        }
    }
    
    private void handleChoke(String peerId) {
        log.info("Received CHOKE from peer {}", peerId);
        // Mark that this peer has choked us
        peerService.getPeer(peerId).ifPresent(peer -> {
            peer.setChoked(true);
        });
    }
    
    private void handleUnchoke(String peerId) {
        log.info("Received UNCHOKE from peer {}", peerId);
        peerService.getPeer(peerId).ifPresent(peer -> {
            peer.setChoked(false);
            // Request multiple pieces now that we're unchoked
            requestPiecesAfterUnchoke(peerId);
        });
    }
    
    public void requestPiecesAfterUnchoke(String peerId) {
        log.info("Requesting multiple pieces after being unchoked by peer {}", peerId);
        
        // Get how many pieces we need
        List<Integer> missingPieces = fileService.getMissingPieces();
        if (missingPieces.isEmpty()) {
            log.info("No missing pieces to request from peer {}", peerId);
            return;
        }
        
        // Request up to 10 pieces at once
        int piecesToRequest = Math.min(10, missingPieces.size());
        log.info("Will request {} pieces from peer {}", piecesToRequest, peerId);
        
        for (int i = 0; i < piecesToRequest; i++) {
            int pieceIndex = missingPieces.get(i);
            log.info("Auto-requesting piece {} from peer {} after unchoke", pieceIndex, peerId);
            requestSpecificPiece(peerId, pieceIndex);
        }
    }
    
    private void handleInterested(String peerId) {
        log.info("Received INTERESTED from peer {}", peerId);
        peerService.getPeer(peerId).ifPresent(peer -> {
            peer.setInterested(true);
        });
    }
    
    private void handleNotInterested(String peerId) {
        log.info("Received NOT_INTERESTED from peer {}", peerId);
        peerService.getPeer(peerId).ifPresent(peer -> {
            peer.setInterested(false);
        });
    }
    
    private void handleHave(String peerId, int pieceIndex) {
        log.info("Received HAVE from peer {} for piece {}", peerId, pieceIndex);
        
        // Update peer's bitfield
        peerService.updatePeerBitfield(peerId, pieceIndex);
        
        // Check if we're interested in this peer now
        peerService.getPeer(peerId).ifPresent(peer -> {
            BitSet localBitfield = fileService.getBitfield();
            BitSet peerBitfield = peer.getBitfield();
            
            // Clone and invert our bitfield to find what we're missing
            BitSet missingPieces = (BitSet) localBitfield.clone();
            missingPieces.flip(0, fileService.getTotalPieces());
            
            // AND with peer's bitfield to see if they have pieces we need
            missingPieces.and(peerBitfield);
            
            // If there are any bits set, we're interested
            if (missingPieces.cardinality() > 0 && !peer.isInterested()) {
                peer.setInterested(true);
                
                // Send INTERESTED message
                Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", interestedMsg);
                
                log.info("Sent INTERESTED to peer {}", peerId);
            }
        });
    }
    
    private void handleBitfield(String peerId, String bitfieldStr) {
        log.info("Received BITFIELD from peer {}", peerId);
        
        peerService.getPeer(peerId).ifPresent(peer -> {
            // Convert string representation to BitSet
            BitSet peerBitfield = new BitSet(fileService.getTotalPieces());
            for (int i = 0; i < bitfieldStr.length(); i++) {
                if (bitfieldStr.charAt(i) == '1') {
                    peerBitfield.set(i);
                }
            }
            
            log.debug("Peer {} bitfield: {}", peerId, bitfieldStr);
            
            // Update peer's bitfield
            peer.setBitfield(peerBitfield);
            
            // Check if we're interested in this peer
            BitSet localBitfield = fileService.getBitfield();
            BitSet missingPieces = (BitSet) localBitfield.clone();
            missingPieces.flip(0, fileService.getTotalPieces());
            missingPieces.and(peerBitfield);
            
            if (missingPieces.cardinality() > 0) {
                peer.setInterested(true);
                
                // Send INTERESTED message
                Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", interestedMsg);
                
                log.info("Sent INTERESTED to peer {}", peerId);
            } else {
                // Send NOT_INTERESTED message
                Message notInterestedMsg = new Message(Message.MessageType.NOT_INTERESTED, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", notInterestedMsg);
                
                log.info("Sent NOT_INTERESTED to peer {}", peerId);
            }
        });
    }
    
    private void handleRequest(String peerId, int pieceIndex) {
        log.info("Received REQUEST from peer {} for piece {}", peerId, pieceIndex);
        
        peerService.getPeer(peerId).ifPresent(peer -> {
            // Force unchoke the peer making the request
            if (peer.isChoked()) {
                log.info("Auto-unchoking peer {} to fulfill piece request", peerId);
                peer.setChoked(false);
                
                // Send UNCHOKE message
                Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", unchokeMsg);
                log.info("Sent UNCHOKE to peer {}", peerId);
            }
            
            // Always try to send piece, regardless of choke state
            log.debug("Retrieving piece {} for peer {}", pieceIndex, peerId);
            byte[] pieceData = fileService.getPiece(localPeerId, pieceIndex);
            
            if (pieceData != null) {
                log.debug("Found piece {} (size: {} bytes), sending to peer {}", 
                          pieceIndex, pieceData.length, peerId);
                
                // Send PIECE message
                Message pieceMsg = new Message(Message.MessageType.PIECE, localPeerId, pieceIndex, pieceData, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", pieceMsg);
                
                log.info("Sent PIECE {} to peer {}", pieceIndex, peerId);
            } else {
                log.warn("Requested piece {} not found for peer {}", pieceIndex, localPeerId);
            }
        });
    }
    
    private void handlePiece(String peerId, int pieceIndex, byte[] data) {
        log.info("Received PIECE {} (size: {} bytes) from peer {}", 
                 pieceIndex, (data != null ? data.length : 0), peerId);
        
        if (data == null || data.length == 0) {
            log.error("Received empty data for piece {} from peer {}", pieceIndex, peerId);
            return;
        }
        
        // Save the piece
        FilePiece piece = new FilePiece(pieceIndex, data);
        log.info("Saving piece {} to local storage", pieceIndex);
        fileService.receivePiece(localPeerId, piece);
        
        // Record download statistics
        peerService.recordDownload(peerId, data.length);
        
        // Notify all peers that we have this piece
        Message haveMsg = new Message(Message.MessageType.HAVE, localPeerId, pieceIndex, null, null);
        peerService.getAllPeers().forEach(p -> {
            if (!p.getPeerId().equals(localPeerId)) {
                messagingTemplate.convertAndSendToUser(p.getPeerId(), "/queue/messages", haveMsg);
            }
        });
        
        log.info("Sent HAVE for piece {} to all peers", pieceIndex);
        
        // Check if file is complete
        if (fileService.hasCompletedDownload()) {
            log.info("Download complete! Merging file...");
            fileService.mergeFile(localPeerId);
        } else {
            // Check how many pieces we have now
            BitSet bitfield = fileService.getBitfield();
            int totalPieces = fileService.getTotalPieces();
            int downloadedPieces = bitfield.cardinality();
            double progress = (double) downloadedPieces / totalPieces * 100;
            
            log.info("Download progress: {}/{} pieces ({}%)", 
                    downloadedPieces, totalPieces, String.format("%.2f", progress));
            
            // Request multiple pieces in parallel - request at least 5 pieces at once
            for (int i = 0; i < 5; i++) {
                requestPieceFrom(peerId);
            }
            
            // Also request pieces from all available unchoked peers
            log.info("Requesting pieces from all available unchoked peers");
            peerService.getAllPeers().stream()
                .filter(p -> !p.getPeerId().equals(localPeerId))
                .filter(p -> !p.isChoked() && p.getBitfield().cardinality() > 0)
                .forEach(p -> {
                    log.info("Requesting piece from peer {}", p.getPeerId());
                    requestPieceFrom(p.getPeerId());
                });
        }
    }
    
    private void handleHandshake(String peerId) {
        log.info("Received HANDSHAKE from peer {}", peerId);
        
        // Send our bitfield
        BitSet bitfield = fileService.getBitfield();
        StringBuilder bitfieldStr = new StringBuilder();
        
        for (int i = 0; i < fileService.getTotalPieces(); i++) {
            bitfieldStr.append(bitfield.get(i) ? '1' : '0');
        }
        
        Message bitfieldMsg = new Message(Message.MessageType.BITFIELD, localPeerId, null, null, bitfieldStr.toString());
        messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", bitfieldMsg);
        
        log.info("Sent BITFIELD to peer {}", peerId);
    }
    
    private void requestPieceFrom(String peerId) {
        peerService.getPeer(peerId).ifPresent(peer -> {
            if (!peer.isChoked()) {
                log.info("Requesting piece from peer {} (not choked)", peerId);
                List<Integer> missingPieces = fileService.getMissingPieces();
                
                log.info("We have {} missing pieces total", missingPieces.size());
                
                // Find pieces that the peer has
                BitSet peerBitfield = peer.getBitfield();
                StringBuilder bitfieldStr = new StringBuilder();
                for (int i = 0; i < fileService.getTotalPieces(); i++) {
                    bitfieldStr.append(peerBitfield.get(i) ? '1' : '0');
                }
                log.info("Peer {} bitfield: {}", peerId, bitfieldStr.toString());
                
                // Debug peerBitfield cardinality
                log.info("Peer {} has {} pieces according to bitfield", peerId, peerBitfield.cardinality());
                
                if (missingPieces.isEmpty()) {
                    log.info("No missing pieces to request from peer {}", peerId);
                    return;
                }
                
                // Find pieces that the peer has and we don't
                int originalSize = missingPieces.size();
                missingPieces.removeIf(pieceIndex -> !peerBitfield.get(pieceIndex));
                log.info("After filtering, peer {} has {} of our missing pieces", 
                          peerId, missingPieces.size());
                
                if (missingPieces.size() == 0 && originalSize > 0) {
                    log.warn("Peer {} has no pieces we need despite having missing pieces", peerId);
                    
                    // Try to force-update bitfield for this peer if it's a seeder
                    if (peer.isHasFile()) {
                        log.info("Peer {} is a seeder, attempting to update its bitfield", peerId);
                        peerBitfield.set(0, fileService.getTotalPieces());
                        log.info("Updated peer {}'s bitfield to have all pieces", peerId);
                        
                        // Re-evaluate missing pieces
                        missingPieces.clear();
                        for (int i = 0; i < fileService.getTotalPieces(); i++) {
                            if (!fileService.getBitfield().get(i)) {
                                missingPieces.add(i);
                            }
                        }
                        
                        // Re-filter to find pieces the peer has
                        int newSize = missingPieces.size();
                        missingPieces.removeIf(pieceIndex -> !peerBitfield.get(pieceIndex));
                        log.info("After seeder bitfield update, peer {} has {} of our missing pieces (out of {})", 
                                peerId, missingPieces.size(), newSize);
                    }
                }
                
                if (!missingPieces.isEmpty()) {
                    int selectedPieceIndex;
                    
                    // For video files, request pieces sequentially for better streaming
                    String filename = fileService.getCurrentFilename();
                    boolean isVideo = filename != null && 
                                     (filename.toLowerCase().endsWith(".mp4") || 
                                      filename.toLowerCase().endsWith(".webm") ||
                                      filename.toLowerCase().endsWith(".mov"));
                    
                    if (isVideo) {
                        // For video streaming, pick the lowest index missing piece
                        selectedPieceIndex = missingPieces.stream()
                                            .min(Integer::compare)
                                            .orElse(missingPieces.get(0));
                        log.info("Video streaming mode: Selected piece {} (sequential)", selectedPieceIndex);
                    } else {
                        // For other files, random selection
                        int randomIndex = new Random().nextInt(missingPieces.size());
                        selectedPieceIndex = missingPieces.get(randomIndex);
                        log.info("Normal mode: Selected piece {} (random)", selectedPieceIndex);
                    }
                    
                    // Send REQUEST message
                    Message requestMsg = new Message(Message.MessageType.REQUEST, localPeerId, selectedPieceIndex, null, null);
                    messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", requestMsg);
                    
                    log.info("Sent REQUEST for piece {} to peer {}", selectedPieceIndex, peerId);
                } else {
                    // We're not interested in this peer anymore
                    peer.setInterested(false);
                    
                    Message notInterestedMsg = new Message(Message.MessageType.NOT_INTERESTED, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", notInterestedMsg);
                    
                    log.info("Sent NOT_INTERESTED to peer {} (no needed pieces)", peerId);
                }
            } else {
                log.info("Cannot request piece from peer {} because they have choked us", peerId);
            }
        });
    }
    
    public void sendHandshake(String targetPeerId) {
        Message handshakeMsg = new Message(Message.MessageType.HANDSHAKE, localPeerId, null, null, null);
        
        try {
            messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", handshakeMsg);
            log.info("Sent HANDSHAKE to peer {}", targetPeerId);
            
            // Force immediate BITFIELD message to follow handshake
            BitSet bitfield = fileService.getBitfield();
            StringBuilder bitfieldStr = new StringBuilder();
            
            for (int i = 0; i < fileService.getTotalPieces(); i++) {
                bitfieldStr.append(bitfield.get(i) ? '1' : '0');
            }
            
            Message bitfieldMsg = new Message(Message.MessageType.BITFIELD, localPeerId, null, null, bitfieldStr.toString());
            messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", bitfieldMsg);
            log.info("Sent BITFIELD to peer {} after handshake", targetPeerId);
            
            // Now force a connection from the other direction too
            sendRequestForMissingPieces(targetPeerId);
        } catch (Exception e) {
            log.error("Error sending handshake to peer {}", targetPeerId, e);
        }
    }
    
    private void sendRequestForMissingPieces(String targetPeerId) {
        try {
            // If we need pieces, immediately request them
            List<Integer> missingPieces = fileService.getMissingPieces();
            
            if (!missingPieces.isEmpty()) {
                peerService.getPeer(targetPeerId).ifPresent(peer -> {
                    // Send INTERESTED message
                    Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", interestedMsg);
                    log.info("Sent INTERESTED to peer {} to start download", targetPeerId);
                    
                    // Mark as interested so future unchokes will trigger requests
                    peer.setInterested(true);
                    
                    // If peer is a seeder, also attempt to immediately request a piece
                    if (peer.isHasFile() && !peer.isChoked()) {
                        // Only request if we're not choked
                        int pieceToRequest = missingPieces.get(0); // Get first missing piece
                        log.info("Seeder detected: Immediately requesting piece {} from peer {}", pieceToRequest, targetPeerId);
                        
                        Message requestMsg = new Message(Message.MessageType.REQUEST, localPeerId, pieceToRequest, null, null);
                        messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", requestMsg);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error sending requests for missing pieces to peer {}", targetPeerId, e);
        }
    }
    
    /**
     * Manually request a specific piece from a peer
     * This is useful for testing or recovering from stalled downloads
     */
    public void requestSpecificPiece(String targetPeerId, int pieceIndex) {
        log.info("Manually requesting piece {} from peer {}", pieceIndex, targetPeerId);
        
        peerService.getPeer(targetPeerId).ifPresent(peer -> {
            // First make sure we're not choked
            if (peer.isChoked()) {
                log.info("Peer {} has choked us, sending INTERESTED first", targetPeerId);
                Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", interestedMsg);
                
                // Force unchoke the peer so we can request pieces immediately
                log.info("Force-unchoking peer {} to allow immediate piece requests", targetPeerId);
                peer.setChoked(false);
                
                // Send an unchoke message to the peer as well
                Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", unchokeMsg);
            }
            
            // Now send the REQUEST message regardless of previous choke state
            Message requestMsg = new Message(Message.MessageType.REQUEST, localPeerId, pieceIndex, null, null);
            messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", requestMsg);
            
            log.info("Sent REQUEST for piece {} to peer {}", pieceIndex, targetPeerId);
        });
    }
}