package com.p2p.torrent.service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

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
        log.info("PEER ID SET: MessageService local peer ID is now {}", peerId);
    }
    
    public String getLocalPeerId() {
        if (localPeerId == null || localPeerId.isEmpty()) {
            log.error("PEER ID ERROR: Local peer ID is null or empty!");
            
            // Try to get it from the file service as fallback
            try {
                String fileServicePeerId = fileService.getLocalPeerId();
                if (fileServicePeerId != null && !fileServicePeerId.isEmpty()) {
                    log.info("PEER ID RECOVERY: Found peer ID {} in FileService, using it", fileServicePeerId);
                    this.localPeerId = fileServicePeerId;
                } else {
                    // Generate a random ID as absolute last resort
                    String randomId = "auto-" + new Random().nextInt(10000);
                    log.warn("PEER ID EMERGENCY: Generating random peer ID {} as fallback", randomId);
                    this.localPeerId = randomId;
                }
            } catch (Exception e) {
                log.error("PEER ID RECOVERY FAILED: {}", e.getMessage());
            }
        }
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
            
            // Handle case where bitfield string length doesn't match total pieces
            int totalPieces = fileService.getTotalPieces();
            if (bitfieldStr.length() < totalPieces) {
                log.warn("Received bitfield length {} is shorter than total pieces {}, padding with zeros", 
                         bitfieldStr.length(), totalPieces);
            } else if (bitfieldStr.length() > totalPieces) {
                log.warn("Received bitfield length {} is longer than total pieces {}, truncating", 
                         bitfieldStr.length(), totalPieces);
            }
            
            // Process bits up to the min of bitfield length and total pieces
            int processLength = Math.min(bitfieldStr.length(), totalPieces);
            for (int i = 0; i < processLength; i++) {
                if (bitfieldStr.charAt(i) == '1') {
                    peerBitfield.set(i);
                }
            }
            
            // If peer has the complete file, ensure its bitfield shows that
            if (peer.isHasFile() && peerBitfield.cardinality() < totalPieces) {
                log.info("Peer {} is marked as having complete file but bitfield shows only {}/{} pieces. Updating to full bitfield.", 
                         peerId, peerBitfield.cardinality(), totalPieces);
                peerBitfield.set(0, totalPieces);
            }
            
            log.debug("Peer {} bitfield: {} (has {}/{} pieces)", 
                     peerId, bitfieldStr, peerBitfield.cardinality(), totalPieces);
            
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
                
                log.info("Sent INTERESTED to peer {} (they have {} pieces we need)", 
                         peerId, missingPieces.cardinality());
                
                // Force unchoke the peer if it's a seeder
                if (peer.isHasFile() && peer.isChoked()) {
                    // Send unchoke message to the seeder
                    Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", unchokeMsg);
                    log.info("Force-sent UNCHOKE to seeder peer {}", peerId);
                }
            } else {
                // Send NOT_INTERESTED message
                Message notInterestedMsg = new Message(Message.MessageType.NOT_INTERESTED, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", notInterestedMsg);
                
                log.info("Sent NOT_INTERESTED to peer {}", peerId);
            }
        });
    }
    
    private void handleRequest(String peerId, int pieceIndex) {
        log.info("REQUEST PROCESSING: Peer {} wants piece {}", peerId, pieceIndex);
        
        boolean peerFound = false;
        try {
            peerFound = peerService.getPeer(peerId).isPresent();
            log.info("PEER FOUND: {}", peerFound);
        } catch (Exception e) {
            log.error("PEER LOOKUP ERROR: {}", e.getMessage());
        }
        
        peerService.getPeer(peerId).ifPresent(peer -> {
            // Force unchoke the peer making the request
            if (peer.isChoked()) {
                log.info("UNCHOKE ACTION: Auto-unchoking peer {} to fulfill piece request", peerId);
                peer.setChoked(false);
                
                // Send UNCHOKE message
                try {
                    Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", unchokeMsg);
                    log.info("UNCHOKE SENT: To peer {}", peerId);
                } catch (Exception e) {
                    log.error("UNCHOKE FAILED: Could not send unchoke to peer {}: {}", peerId, e.getMessage());
                }
            }
            
            // Check if we have the complete file ourselves
            boolean hasCompleteFile = false;
            String fileName = null;
            File sourceFile = null;
            
            // Try multiple possible file path locations
            File configFile = null;
            File peerDirFile = null;
            File relativeFile = null;
            
            try {
                // Check config file path
                fileName = fileService.getCurrentFilename();
                log.info("CHECKING FILES: Current filename from config is '{}'", fileName);
                
                if (fileName != null) {
                    // Try different locations
                    configFile = new File(fileName);
                    peerDirFile = new File("peer_" + localPeerId, fileName);
                    relativeFile = new File(".", fileName);
                    
                    log.info("CHECKING FILES: Config path '{}' exists: {}", configFile.getPath(), configFile.exists());
                    log.info("CHECKING FILES: Peer dir path '{}' exists: {}", peerDirFile.getPath(), peerDirFile.exists());
                    log.info("CHECKING FILES: Relative path '{}' exists: {}", relativeFile.getPath(), relativeFile.exists());
                    
                    // Get the first one that exists
                    if (configFile.exists()) {
                        sourceFile = configFile;
                        hasCompleteFile = true;
                        log.info("FILE FOUND: Using config path: {}", configFile.getAbsolutePath());
                    } else if (peerDirFile.exists()) {
                        sourceFile = peerDirFile;
                        hasCompleteFile = true;
                        log.info("FILE FOUND: Using peer dir path: {}", peerDirFile.getAbsolutePath());
                    } else if (relativeFile.exists()) {
                        sourceFile = relativeFile;
                        hasCompleteFile = true;
                        log.info("FILE FOUND: Using relative path: {}", relativeFile.getAbsolutePath());
                    } else {
                        // Look in all peer directories
                        log.info("SEARCHING PEERS: Checking all peer directories for the file");
                        File peersRoot = new File(".");
                        File[] peerDirs = peersRoot.listFiles(file -> 
                            file.isDirectory() && file.getName().startsWith("peer_"));
                        
                        if (peerDirs != null) {
                            for (File dir : peerDirs) {
                                File possibleFile = new File(dir, fileName);
                                if (possibleFile.exists()) {
                                    sourceFile = possibleFile;
                                    hasCompleteFile = true;
                                    log.info("FILE FOUND: In peer directory: {}", possibleFile.getAbsolutePath());
                                    break;
                                }
                            }
                        }
                    }
                }
                
                // Log final result
                if (hasCompleteFile && sourceFile != null) {
                    log.info("FILE CHECK: Found source file: {} (size: {} bytes)", 
                            sourceFile.getAbsolutePath(), sourceFile.length());
                } else {
                    log.warn("FILE CHECK: Source file not found in any location");
                }
            } catch (Exception e) {
                log.error("FILE CHECK ERROR: {}", e.getMessage(), e);
            }
            
            // Always try to send piece, regardless of choke state
            log.info("PIECE RETRIEVAL: Getting piece {} for peer {} (we are {})", 
                     pieceIndex, peerId, hasCompleteFile ? "a seeder" : "not a seeder");
            
            // Check if this is us trying to request from ourselves (which is a problem)
            if (peerId.equals(localPeerId)) {
                log.error("SELF REQUEST: Peer {} is requesting piece {} from itself!", peerId, pieceIndex);
                return;
            }
            
            byte[] pieceData = null;
            try {
                pieceData = fileService.getPiece(localPeerId, pieceIndex);
                if (pieceData == null) {
                    log.warn("PIECE NULL: getPiece returned null for piece {}", pieceIndex);
                } else if (pieceData.length == 0) {
                    log.warn("PIECE EMPTY: getPiece returned empty data for piece {}", pieceIndex);
                } else {
                    log.info("PIECE FOUND: piece {} has {} bytes", pieceIndex, pieceData.length);
                }
            } catch (Exception e) {
                log.error("PIECE RETRIEVAL ERROR: Failed to get piece {}: {}", pieceIndex, e.getMessage());
                e.printStackTrace();
            }
            
            if (pieceData != null && pieceData.length > 0) {
                sendPieceToUser(peerId, pieceIndex, pieceData);
            } else {
                log.warn("PIECE MISSING: Piece {} not found for peer {} (data is {})", 
                         pieceIndex, localPeerId, pieceData == null ? "null" : "empty");
                
                // If we're supposed to be a seeder but can't find the piece, try direct file access
                if (hasCompleteFile && sourceFile != null) {
                    log.info("EMERGENCY MODE: Attempting direct file access for piece {}", pieceIndex);
                    try {
                        int pieceSize = 1048576; // 1MB default
                        
                        // Try to get the actual piece size from config
                        try {
                            if (fileService.getTotalPieces() > 0) {
                                pieceSize = (int)(sourceFile.length() / fileService.getTotalPieces());
                                log.info("EMERGENCY MODE: Calculated piece size: {} bytes", pieceSize);
                            }
                        } catch (Exception e) {
                            log.warn("EMERGENCY MODE: Could not calculate piece size, using default: {}", pieceSize);
                        }
                        
                        try (RandomAccessFile raf = new RandomAccessFile(sourceFile, "r")) {
                            long fileSize = raf.length();
                            log.info("EMERGENCY MODE: File size: {} bytes, piece size: {} bytes, pieceIndex: {}", 
                                    fileSize, pieceSize, pieceIndex);
                            
                            if (pieceIndex * pieceSize < fileSize) {
                                int currentPieceSize = (int) Math.min(pieceSize, fileSize - pieceIndex * pieceSize);
                                byte[] newPiece = new byte[currentPieceSize];
                                
                                log.info("EMERGENCY MODE: Reading {} bytes at offset {} for piece {}", 
                                        currentPieceSize, pieceIndex * pieceSize, pieceIndex);
                                
                                raf.seek(pieceIndex * pieceSize);
                                raf.readFully(newPiece, 0, currentPieceSize);
                                
                                log.info("EMERGENCY MODE: Successfully read piece {} (size: {})", 
                                        pieceIndex, currentPieceSize);
                                
                                if (newPiece.length > 0) {
                                    sendPieceToUser(peerId, pieceIndex, newPiece);
                                    
                                    // Save piece for future requests
                                    fileService.savePieceToDisk(localPeerId, pieceIndex, newPiece);
                                    log.info("EMERGENCY MODE: Saved piece {} to disk for future use", pieceIndex);
                                } else {
                                    log.error("EMERGENCY MODE: Read piece is empty!");
                                }
                            } else {
                                log.error("EMERGENCY MODE: Piece index {} is beyond file size {} (piece size: {})", 
                                        pieceIndex, fileSize, pieceSize);
                            }
                        }
                    } catch (Exception e) {
                        log.error("EMERGENCY MODE ERROR: Direct file access failed for piece {}: {}", 
                                 pieceIndex, e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    // If we're not a seeder, try to send a HAVE message for pieces we actually have
                    log.info("NOT A SEEDER: Cannot provide piece {}, checking what pieces we have", pieceIndex);
                    try {
                        BitSet ourBitfield = fileService.getBitfield();
                        int totalPieces = fileService.getTotalPieces();
                        log.info("OUR BITFIELD: We have {}/{} pieces", ourBitfield.cardinality(), totalPieces);
                        
                        // If we have at least one piece, try to send a HAVE message to keep communication going
                        if (ourBitfield.cardinality() > 0) {
                            int pieceWeHave = -1;
                            for (int i = 0; i < totalPieces; i++) {
                                if (ourBitfield.get(i)) {
                                    pieceWeHave = i;
                                    break;
                                }
                            }
                            
                            if (pieceWeHave >= 0) {
                                log.info("FALLBACK: Sending HAVE for piece {} instead", pieceWeHave);
                                Message haveMsg = new Message(Message.MessageType.HAVE, localPeerId, pieceWeHave, null, null);
                                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", haveMsg);
                                log.info("FALLBACK: HAVE message sent for piece {}", pieceWeHave);
                            }
                        }
                    } catch (Exception e) {
                        log.error("FALLBACK ERROR: {}", e.getMessage());
                    }
                }
            }
        });
    }
    
    /**
     * Helper method to send piece data to a user with robust error handling
     */
    private void sendPieceToUser(String peerId, int pieceIndex, byte[] pieceData) {
        try {
            log.info("SENDING PIECE: {} (size: {} bytes) to peer {}", 
                    pieceIndex, pieceData.length, peerId);
            
            // Send using traditional route
            try {
                Message pieceMsg = new Message(Message.MessageType.PIECE, localPeerId, pieceIndex, pieceData, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", pieceMsg);
                log.info("PIECE SENT: {} to peer {} (size: {} bytes) via user queue", 
                        pieceIndex, peerId, pieceData.length);
            } catch (Exception e) {
                log.error("PIECE SEND ERROR (user queue): Failed to send piece {}: {}", pieceIndex, e.getMessage());
                
                // Try alternate send method
                try {
                    Message pieceMsg = new Message(Message.MessageType.PIECE, localPeerId, pieceIndex, pieceData, null);
                    messagingTemplate.convertAndSend("/user/" + peerId + "/queue/messages", pieceMsg);
                    log.info("PIECE SENT: {} to peer {} (size: {} bytes) via direct path", 
                            pieceIndex, peerId, pieceData.length);
                } catch (Exception e2) {
                    log.error("PIECE SEND ERROR (direct path): Failed to send piece {}: {}", pieceIndex, e2.getMessage());
                    
                    // Try third method - via topic
                    try {
                        Message pieceMsg = new Message(Message.MessageType.PIECE, localPeerId, pieceIndex, pieceData, null);
                        messagingTemplate.convertAndSend("/topic/pieces", pieceMsg);
                        log.info("PIECE SENT: {} (size: {} bytes) via topic broadcast", pieceIndex, pieceData.length);
                    } catch (Exception e3) {
                        log.error("ALL SEND METHODS FAILED for piece {}", pieceIndex);
                    }
                }
            }
        } catch (Exception e) {
            log.error("CRITICAL ERROR sending piece {}: {}", pieceIndex, e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handlePiece(String peerId, int pieceIndex, byte[] data) {
        log.info("Received PIECE {} (size: {} bytes) from peer {}", 
                 pieceIndex, (data != null ? data.length : 0), peerId);
        
        if (data == null || data.length == 0) {
            log.error("Received empty data for piece {} from peer {}, requesting again", pieceIndex, peerId);
            
            // Request the same piece again
            Message requestMsg = new Message(Message.MessageType.REQUEST, localPeerId, pieceIndex, null, null);
            messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", requestMsg);
            log.info("Re-sent REQUEST for piece {} to peer {} due to empty data", pieceIndex, peerId);
            
            // Also request a different piece to keep the download moving
            requestPieceFrom(peerId);
            return;
        }
        
        // Save the piece
        try {
            FilePiece piece = new FilePiece(pieceIndex, data);
            log.info("Saving piece {} to local storage (size: {} bytes)", pieceIndex, data.length);
            fileService.receivePiece(localPeerId, piece);
            
            // Record download statistics
            peerService.recordDownload(peerId, data.length);
            
            // Notify all peers that we have this piece
            Message haveMsg = new Message(Message.MessageType.HAVE, localPeerId, pieceIndex, null, null);
            peerService.getAllPeers().forEach(p -> {
                if (!p.getPeerId().equals(localPeerId)) {
                    try {
                        messagingTemplate.convertAndSendToUser(p.getPeerId(), "/queue/messages", haveMsg);
                    } catch (Exception e) {
                        log.error("Error sending HAVE message to peer {}: {}", p.getPeerId(), e.getMessage());
                    }
                }
            });
            
            log.info("Sent HAVE for piece {} to all peers", pieceIndex);
        } catch (Exception e) {
            log.error("Error saving received piece {}: {}", pieceIndex, e.getMessage());
            e.printStackTrace();
        }
        
        try {
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
                
                // Always send INTERESTED message back to the seeder to ensure ongoing communication
                peerService.getPeer(peerId).ifPresent(peer -> {
                    if (peer.isHasFile()) {
                        try {
                            Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                            messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", interestedMsg);
                            log.info("Re-sent INTERESTED to seeder peer {} to maintain connection", peerId);
                            peer.setInterested(true);
                            
                            // Also send unchoke message to the seeder
                            Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                            messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", unchokeMsg);
                            log.info("Re-sent UNCHOKE to seeder peer {} to maintain connection", peerId);
                            peer.setChoked(false);
                        } catch (Exception e) {
                            log.error("Error sending messages to maintain connection with peer {}: {}", 
                                    peerId, e.getMessage());
                        }
                    }
                });
                
                // Immediately request the next piece from the same peer that just sent us data
                // This prioritizes peers that are actively sending data
                log.info("Immediately requesting next piece from peer {} that just sent data", peerId);
                requestPieceFrom(peerId);
                
                // Request more pieces in parallel - but stagger them slightly to avoid overwhelming the network
                Thread requestMorePiecesThread = new Thread(() -> {
                    try {
                        // Wait a tiny bit before requesting more pieces
                        Thread.sleep(50);
                        
                        // Request multiple pieces in parallel from the current peer
                        for (int i = 0; i < 5; i++) {
                            requestPieceFrom(peerId);
                        }
                        
                        // Request pieces from all available unchoked peers
                        List<Peer> unchokedPeers = peerService.getAllPeers().stream()
                            .filter(p -> !p.getPeerId().equals(localPeerId))
                            .filter(p -> !p.isChoked() && p.getBitfield().cardinality() > 0)
                            .collect(Collectors.toList());
                        
                        log.info("Requesting pieces from {} available unchoked peers", unchokedPeers.size());
                        
                        for (Peer p : unchokedPeers) {
                            if (!p.getPeerId().equals(peerId)) { // Skip the peer we just requested from above
                                log.info("Requesting piece from additional peer {}", p.getPeerId());
                                requestPieceFrom(p.getPeerId());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error in request thread: {}", e.getMessage());
                    }
                });
                requestMorePiecesThread.setDaemon(true);
                requestMorePiecesThread.start();
            }
        } catch (Exception e) {
            log.error("Error in piece handling: {}", e.getMessage());
            e.printStackTrace();
            
            // Continue requesting pieces despite errors
            requestPieceFrom(peerId);
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
                
                // Always ensure seeder has complete bitfield
                if (peer.isHasFile()) {
                    int totalPieces = fileService.getTotalPieces();
                    if (peerBitfield.cardinality() < totalPieces) {
                        log.info("Peer {} is a seeder, ensuring full bitfield ({} pieces)", 
                                peerId, totalPieces);
                        peerBitfield.set(0, totalPieces);
                        peer.setBitfield(peerBitfield);
                    }
                }
                
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
                List<Integer> piecesToRequest = new ArrayList<>(missingPieces);
                piecesToRequest.removeIf(pieceIndex -> !peerBitfield.get(pieceIndex));
                log.info("After filtering, peer {} has {} of our missing pieces", 
                          peerId, piecesToRequest.size());
                
                if (piecesToRequest.isEmpty() && peer.isHasFile()) {
                    log.warn("Seeder peer {} shows no available pieces despite having the file", peerId);
                    
                    // For seeders, explicitly request the next missing piece even if bitfield doesn't show it
                    // This handles the case where bitfield might be incorrect but the seeder actually has all pieces
                    if (!missingPieces.isEmpty()) {
                        int nextPieceToRequest = missingPieces.get(0);
                        log.info("Forcing request for piece {} from seeder peer {} despite bitfield", 
                                nextPieceToRequest, peerId);
                        
                        // Send REQUEST message
                        Message requestMsg = new Message(Message.MessageType.REQUEST, localPeerId, nextPieceToRequest, null, null);
                        messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", requestMsg);
                        
                        log.info("Sent FORCED REQUEST for piece {} to seeder peer {}", nextPieceToRequest, peerId);
                        return;
                    }
                }
                
                if (!piecesToRequest.isEmpty()) {
                    int selectedPieceIndex;
                    
                    // For video files, request pieces sequentially for better streaming
                    String filename = fileService.getCurrentFilename();
                    boolean isVideo = filename != null && 
                                     (filename.toLowerCase().endsWith(".mp4") || 
                                      filename.toLowerCase().endsWith(".webm") ||
                                      filename.toLowerCase().endsWith(".mov"));
                    
                    if (isVideo) {
                        // For video streaming, pick the lowest index missing piece
                        selectedPieceIndex = piecesToRequest.stream()
                                            .min(Integer::compare)
                                            .orElse(piecesToRequest.get(0));
                        log.info("Video streaming mode: Selected piece {} (sequential)", selectedPieceIndex);
                    } else {
                        // For other files, random selection
                        int randomIndex = new Random().nextInt(piecesToRequest.size());
                        selectedPieceIndex = piecesToRequest.get(randomIndex);
                        log.info("Normal mode: Selected piece {} (random)", selectedPieceIndex);
                    }
                    
                    // Send REQUEST message
                    Message requestMsg = new Message(Message.MessageType.REQUEST, localPeerId, selectedPieceIndex, null, null);
                    messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", requestMsg);
                    
                    log.info("Sent REQUEST for piece {} to peer {}", selectedPieceIndex, peerId);
                } else {
                    // Only send NOT_INTERESTED if this isn't a seeder - we're always interested in seeders
                    if (!peer.isHasFile()) {
                        // We're not interested in this peer anymore
                        peer.setInterested(false);
                        
                        Message notInterestedMsg = new Message(Message.MessageType.NOT_INTERESTED, localPeerId, null, null, null);
                        messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", notInterestedMsg);
                        
                        log.info("Sent NOT_INTERESTED to peer {} (no needed pieces)", peerId);
                    }
                }
            } else {
                // Try sending INTERESTED to see if the peer will unchoke us
                log.info("Peer {} has choked us, sending INTERESTED to request unchoke", peerId);
                Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                messagingTemplate.convertAndSendToUser(peerId, "/queue/messages", interestedMsg);
                peer.setInterested(true);
            }
        });
    }
    
    public void sendHandshake(String targetPeerId) {
        Message handshakeMsg = new Message(Message.MessageType.HANDSHAKE, localPeerId, null, null, null);
        
        try {
            // Get target peer info
            boolean isTargetSeeder = false;
            peerService.getPeer(targetPeerId).ifPresent(peer -> {
                if (peer.isHasFile()) {
                    log.info("Target peer {} is a seeder", targetPeerId);
                }
            });
            
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
            log.info("Sent BITFIELD to peer {} after handshake (we have {}/{} pieces)", 
                    targetPeerId, bitfield.cardinality(), fileService.getTotalPieces());
            
            // Always send INTERESTED to seeders to ensure connection stays alive
            peerService.getPeer(targetPeerId).ifPresent(peer -> {
                if (peer.isHasFile()) {
                    // Send INTERESTED message to the seeder
                    Message interestedMsg = new Message(Message.MessageType.INTERESTED, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", interestedMsg);
                    log.info("Sent INTERESTED to seeder peer {}", targetPeerId);
                    
                    // Also send UNCHOKE to the seeder (even though they're not downloading from us)
                    // This helps establish bidirectional communication
                    Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", unchokeMsg);
                    log.info("Sent UNCHOKE to seeder peer {}", targetPeerId);
                    
                    // Update peer state in our local tracking
                    peer.setInterested(true);
                    peer.setChoked(false);
                }
            });
            
            // Now force a connection from the other direction too
            sendRequestForMissingPieces(targetPeerId);
            
            // For seeders, schedule repeated piece requests to maintain connection
            peerService.getPeer(targetPeerId).ifPresent(peer -> {
                if (peer.isHasFile()) {
                    Thread keepAliveThread = new Thread(() -> {
                        try {
                            // Wait a bit before starting the keep-alive requests
                            Thread.sleep(1000);
                            
                            // Send request for up to 5 missing pieces immediately
                            List<Integer> missingPieces = fileService.getMissingPieces();
                            int piecesToRequest = Math.min(5, missingPieces.size());
                            
                            for (int i = 0; i < piecesToRequest; i++) {
                                int pieceIndex = missingPieces.get(i);
                                requestSpecificPiece(targetPeerId, pieceIndex);
                                // Add a small delay between requests
                                Thread.sleep(100);
                            }
                        } catch (Exception e) {
                            log.error("Error in keep-alive thread for seeder {}", targetPeerId, e);
                        }
                    });
                    keepAliveThread.setDaemon(true);
                    keepAliveThread.start();
                }
            });
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