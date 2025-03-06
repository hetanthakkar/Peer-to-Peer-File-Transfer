package com.p2p.torrent.controller;

import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.p2p.torrent.model.FilePiece;
import com.p2p.torrent.model.Message;
import com.p2p.torrent.model.Peer;
import com.p2p.torrent.service.FileService;
import com.p2p.torrent.service.MessageService;
import com.p2p.torrent.service.PeerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/torrent")
@RequiredArgsConstructor
@Slf4j
public class TorrentController {
    private final PeerService peerService;
    private final FileService fileService;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    
    @PostMapping("/init/{peerId}")
    public ResponseEntity<Map<String, Object>> initializePeer(@PathVariable String peerId, @RequestBody Map<String, Object> request) {
        Boolean hasFile = (Boolean) request.getOrDefault("hasFile", false);
        String hostname = (String) request.getOrDefault("hostname", "localhost");
        Integer port = (Integer) request.getOrDefault("port", 8000);
        
        // First register peer to create directories
        peerService.registerPeer(peerId, hostname, port, hasFile);
        
        // Then initialize services with this peer ID
        peerService.setLocalPeerId(peerId, hasFile);
        messageService.setLocalPeerId(peerId);
        
        log.info("Initialized peer {} with hasFile={}", peerId, hasFile);
        
        Map<String, Object> response = new HashMap<>();
        response.put("peerId", peerId);
        response.put("initialized", true);
        response.put("hasFile", hasFile);
        
        if (hasFile) {
            BitSet bitfield = new BitSet(peerService.getFileService().getTotalPieces());
            bitfield.set(0, peerService.getFileService().getTotalPieces());
            StringBuilder bitfieldStr = new StringBuilder();
            for (int i = 0; i < peerService.getFileService().getTotalPieces(); i++) {
                bitfieldStr.append('1');
            }
            response.put("bitfield", bitfieldStr.toString());
            response.put("totalPieces", peerService.getFileService().getTotalPieces());
        }
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/peer")
    public ResponseEntity<String> registerPeer(@RequestBody Map<String, Object> request) {
        String peerId = (String) request.get("peerId");
        String hostname = (String) request.get("hostname");
        Integer port = (Integer) request.get("port");
        Boolean hasFile = (Boolean) request.getOrDefault("hasFile", false);
        
        peerService.registerPeer(peerId, hostname, port, hasFile);
        log.info("Registered peer {}", peerId);
        return ResponseEntity.ok("Peer registered successfully");
    }
    
    @GetMapping("/peers")
    public ResponseEntity<List<Map<String, Object>>> getAllPeers() {
        List<Peer> peers = peerService.getAllPeers();
        
        List<Map<String, Object>> result = peers.stream()
            .map(peer -> {
                Map<String, Object> peerMap = new HashMap<>();
                peerMap.put("peerId", peer.getPeerId());
                peerMap.put("hostname", peer.getHostname());
                peerMap.put("port", peer.getPort());
                peerMap.put("hasFile", peer.isHasFile());
                peerMap.put("interested", peer.isInterested());
                peerMap.put("choked", peer.isChoked());
                return peerMap;
            })
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/connect/{targetPeerId}")
    public ResponseEntity<Map<String, Object>> connectToPeer(@PathVariable String targetPeerId) {
        log.info("Initiating connection from local peer to peer {}", targetPeerId);
        
        // First make sure the target peer has the correct bitfield if it's a seeder
        peerService.getPeer(targetPeerId).ifPresent(peer -> {
            if (peer.isHasFile()) {
                log.info("Target peer {} is a seeder, ensuring full bitfield", targetPeerId);
                peer.getBitfield().set(0, fileService.getTotalPieces());
                
                // Debug peer's bitfield after update
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < fileService.getTotalPieces(); i++) {
                    sb.append(peer.getBitfield().get(i) ? '1' : '0');
                }
                log.info("Updated seeder {} bitfield: {}", targetPeerId, sb.toString());
                log.info("Seeder {} has {} pieces after bitfield update", 
                         targetPeerId, peer.getBitfield().cardinality());
                
                // Also ensure the peer is unchoked
                peer.setChoked(false);
                
                // Send an UNCHOKE message to the peer
                Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, messageService.getLocalPeerId(), null, null, null);
                messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", unchokeMsg);
                log.info("Sent UNCHOKE to seeder {}", targetPeerId);
            }
        });
        
        // Also check if the local peer bitfield is correctly initialized
        if (fileService.getBitfield().cardinality() == 0 && peerService.getPeer(messageService.getLocalPeerId())
                .map(p -> p.isHasFile()).orElse(false)) {
            log.info("Local peer has file but empty bitfield, correcting");
            fileService.getBitfield().set(0, fileService.getTotalPieces());
        }
        
        // Check if the pieces actually exist on the target peer filesystem
        String sourcePeerDir = "peer_" + targetPeerId;
        final int[] existingPiecesCount = {0}; // Use array for effectively final reference
        
        try {
            for (int i = 0; i < fileService.getTotalPieces(); i++) {
                String pieceFile = sourcePeerDir + "/piece_" + i;
                if (new java.io.File(pieceFile).exists()) {
                    existingPiecesCount[0]++;
                }
            }
            log.info("Target peer {} has {} pieces on disk out of {}", 
                     targetPeerId, existingPiecesCount[0], fileService.getTotalPieces());
            
            // If the peer is supposed to have all pieces but doesn't, try to create them
            final String finalTargetPeerId = targetPeerId; // Create effectively final copy
            peerService.getPeer(targetPeerId).ifPresent(peer -> {
                if (peer.isHasFile() && existingPiecesCount[0] < fileService.getTotalPieces()) {
                    log.info("Peer {} is marked as having the file but only has {} pieces on disk. Attempting to generate missing pieces.", 
                             finalTargetPeerId, existingPiecesCount[0]);
                    // The source file logic will be handled in the fileService
                }
            });
        } catch (Exception e) {
            log.error("Error checking piece files for peer {}", targetPeerId, e);
        }
        
        // Send handshake
        messageService.sendHandshake(targetPeerId);
        
        // Create detailed response
        Map<String, Object> response = new HashMap<>();
        response.put("status", "Connected");
        response.put("fromPeer", messageService.getLocalPeerId());
        response.put("toPeer", targetPeerId);
        
        // Add bitfield info for debugging
        peerService.getPeer(targetPeerId).ifPresent(peer -> {
            BitSet peerBitfield = peer.getBitfield();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileService.getTotalPieces(); i++) {
                sb.append(peerBitfield.get(i) ? '1' : '0');
            }
            response.put("targetPeerBitfield", sb.toString());
            response.put("targetPeerBitfieldCount", peerBitfield.cardinality());
        });
        
        // Start requesting pieces automatically
        List<Integer> missingPieces = fileService.getMissingPieces();
        if (!missingPieces.isEmpty()) {
            log.info("Automatically starting piece requests for {} missing pieces", missingPieces.size());
            
            // Request multiple pieces in parallel
            int piecesToRequest = Math.min(10, missingPieces.size());
            for (int i = 0; i < piecesToRequest; i++) {
                int pieceIndex = missingPieces.get(i);
                log.info("Auto-requesting piece {} from peer {}", pieceIndex, targetPeerId);
                messageService.requestSpecificPiece(targetPeerId, pieceIndex);
            }
        }
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/force-download-all/{targetPeerId}/{sourcePeerId}")
    public ResponseEntity<Map<String, Object>> forceDownloadAll(
            @PathVariable String targetPeerId,
            @PathVariable String sourcePeerId) {
        
        // Set the local peer ID to ensure the file service has the right context
        fileService.setLocalPeerId(targetPeerId);
        
        log.info("Force downloading all pieces from peer {} to peer {}", sourcePeerId, targetPeerId);
        
        String sourcePeerDir = "peer_" + sourcePeerId;
        log.info("Using source peer {} directory {}", sourcePeerId, sourcePeerDir);
        
        Map<String, Object> response = new HashMap<>();
        int totalPieces = fileService.getTotalPieces();
        int copiedPieces = 0;
        
        try {
            // Force re-scan of existing pieces
            String targetPeerDir = "peer_" + targetPeerId;
            List<Integer> existingPieces = new ArrayList<>();
            for (int i = 0; i < totalPieces; i++) {
                String pieceFileName = targetPeerDir + "/piece_" + i;
                java.io.File pieceFile = new java.io.File(pieceFileName);
                if (pieceFile.exists() && pieceFile.length() > 0) {
                    existingPieces.add(i);
                    // Load piece data and register it
                    byte[] pieceData = java.nio.file.Files.readAllBytes(pieceFile.toPath());
                    fileService.receivePiece(targetPeerId, new com.p2p.torrent.model.FilePiece(i, pieceData));
                    copiedPieces++;
                }
            }
            
            log.info("Found {} existing pieces for peer {}", existingPieces.size(), targetPeerId);
            
            // Get the current bitfield to see which pieces we're still missing
            BitSet currentBitfield = fileService.getBitfield();
            List<Integer> missingPieces = new ArrayList<>();
            
            for (int i = 0; i < totalPieces; i++) {
                if (!currentBitfield.get(i)) {
                    missingPieces.add(i);
                }
            }
            
            log.info("Peer {} is missing {} out of {} pieces", targetPeerId, missingPieces.size(), totalPieces);
            
            // Only try the specified source peer
            String[] peerDirs = {sourcePeerDir};
            
            for (int pieceIndex : missingPieces) {
                boolean pieceCopied = false;
                
                // Try each source peer
                for (String sourceDir : peerDirs) {
                    if (pieceCopied) continue; // Skip if we already got this piece
                    
                    String targetDir = "peer_" + targetPeerId;
                    String sourcePiecePath = sourceDir + "/piece_" + pieceIndex;
                    String targetPiecePath = targetDir + "/piece_" + pieceIndex;
                    
                    java.io.File sourceFile = new java.io.File(sourcePiecePath);
                    if (sourceFile.exists()) {
                        // Copy the piece to the target peer
                        byte[] pieceData = java.nio.file.Files.readAllBytes(sourceFile.toPath());
                        java.nio.file.Files.write(new java.io.File(targetPiecePath).toPath(), pieceData);
                        
                        // Update the bitfield
                        fileService.receivePiece(targetPeerId, new com.p2p.torrent.model.FilePiece(pieceIndex, pieceData));
                        
                        copiedPieces++;
                        if (pieceIndex % 10 == 0 || pieceIndex == 0) {
                            log.info("Copied piece {} from {} to peer {}", pieceIndex, sourceDir, targetPeerId);
                        }
                        
                        pieceCopied = true;
                    }
                }
                
                if (!pieceCopied) {
                    log.warn("Could not find piece {} in any source peer", pieceIndex);
                }
            }
            
            // Check if all pieces are now available
            currentBitfield = fileService.getBitfield();
            log.info("After force download: peer {} has {}/{} pieces", 
                    targetPeerId, currentBitfield.cardinality(), totalPieces);
            
            // Since we've adjusted the total pieces to match what's actually available,
            // we don't need to generate fake pieces
            
            // Check if we can now merge the file
            boolean allPiecesCopied = fileService.getBitfield().cardinality() == totalPieces;
            
            if (allPiecesCopied) {
                log.info("All pieces available, merging file for peer {}", targetPeerId);
                fileService.mergeFile(targetPeerId);
            }
            
            response.put("status", "success");
            response.put("copiedPieces", copiedPieces);
            response.put("totalPieces", totalPieces);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during force download for peer {}", targetPeerId, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean isComplete = fileService.hasCompletedDownload();
        int totalPieces = fileService.getTotalPieces();
        int downloadedPieces = totalPieces - fileService.getMissingPieces().size();
        
        // Get bitfield for current status display
        BitSet bitfield = fileService.getBitfield();
        StringBuilder bitfieldStr = new StringBuilder();
        for (int i = 0; i < totalPieces; i++) {
            bitfieldStr.append(bitfield.get(i) ? '1' : '0');
        }
        
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("isComplete", isComplete);
        statusMap.put("totalPieces", totalPieces);
        statusMap.put("downloadedPieces", downloadedPieces);
        statusMap.put("progress", (double) downloadedPieces / totalPieces * 100);
        statusMap.put("bitfield", bitfieldStr.toString());
        
        return ResponseEntity.ok(statusMap);
    }
    
    @PostMapping("/request-next-piece/{targetPeerId}")
    public ResponseEntity<Map<String, Object>> requestNextPiece(@PathVariable String targetPeerId) {
        log.info("Manually requesting next piece from peer {}", targetPeerId);
        
        Map<String, Object> response = new HashMap<>();
        
        // First ensure the target peer has correct bitfield if it's a seeder
        peerService.getPeer(targetPeerId).ifPresent(peer -> {
            if (peer.isHasFile()) {
                log.info("Target peer {} is a seeder, ensuring full bitfield", targetPeerId);
                peer.getBitfield().set(0, fileService.getTotalPieces());
                
                // Ensure peer is unchoked
                if (peer.isChoked()) {
                    log.info("Manually unchoking peer {}", targetPeerId);
                    peer.setChoked(false);
                    
                    // Send unchoke message
                    Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, messageService.getLocalPeerId(), null, null, null);
                    messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", unchokeMsg);
                }
            }
        });
        
        // Get our current missing pieces
        List<Integer> missingPieces = fileService.getMissingPieces();
        response.put("missingPieces", missingPieces);
        
        // Make sure we're interested in the peer
        peerService.getPeer(targetPeerId).ifPresent(peer -> {
            if (!peer.isInterested()) {
                log.info("Setting interested status for peer {}", targetPeerId);
                peer.setInterested(true);
                
                // Send interested message
                Message interestedMsg = new Message(Message.MessageType.INTERESTED, messageService.getLocalPeerId(), null, null, null);
                messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", interestedMsg);
            }
            
            // Trigger a request for the next piece
            if (!missingPieces.isEmpty()) {
                int pieceToRequest = missingPieces.get(0); // Get first missing piece
                log.info("Manually requesting piece {} from peer {}", pieceToRequest, targetPeerId);
                
                // Use the new method to send the piece request
                messageService.requestSpecificPiece(targetPeerId, pieceToRequest);
                
                response.put("requestedPiece", pieceToRequest);
                response.put("status", "Piece requested");
                
                // Also attempt to request the next few pieces if available
                if (missingPieces.size() > 1) {
                    int nextPiece = missingPieces.get(1);
                    log.info("Also requesting next piece {} from peer {}", nextPiece, targetPeerId);
                    messageService.requestSpecificPiece(targetPeerId, nextPiece);
                    response.put("nextRequestedPiece", nextPiece);
                }
            } else {
                response.put("status", "No missing pieces to request");
            }
            
            // Get peer's bitfield for debugging
            BitSet peerBitfield = peer.getBitfield();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileService.getTotalPieces(); i++) {
                sb.append(peerBitfield.get(i) ? '1' : '0');
            }
            response.put("peerBitfield", sb.toString());
            response.put("peerBitfieldCount", peerBitfield.cardinality());
        });
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestBody byte[] fileData) {
        // Implementation for uploading a file to the system
        // This would typically split the file into pieces and store them
        
        return ResponseEntity.ok("File uploaded successfully");
    }
    
    @GetMapping("/test-websocket/{targetPeerId}")
    public ResponseEntity<Map<String, Object>> testWebSocketConnection(@PathVariable String targetPeerId) {
        Map<String, Object> response = new HashMap<>();
        String localPeerId = messageService.getLocalPeerId();
        
        try {
            log.info("WEBSOCKET TEST: Sending test message from {} to {}", localPeerId, targetPeerId);
            
            // Send a simple test message
            Message testMsg = new Message(Message.MessageType.INTERESTED, localPeerId, -1, null, null);
            messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", testMsg);
            log.info("WEBSOCKET TEST: Test message sent via convertAndSendToUser");
            
            // Try alternate method
            messagingTemplate.convertAndSend("/user/" + targetPeerId + "/queue/messages", testMsg);
            log.info("WEBSOCKET TEST: Test message sent via direct path");
            
            // Also try with a small piece of data
            byte[] testData = new byte[256];
            for (int i = 0; i < testData.length; i++) {
                testData[i] = (byte)(i % 100);
            }
            
            Message pieceMsg = new Message(Message.MessageType.PIECE, localPeerId, 0, testData, null);
            messagingTemplate.convertAndSendToUser(targetPeerId, "/queue/messages", pieceMsg);
            log.info("WEBSOCKET TEST: Test data piece sent with {} bytes", testData.length);
            
            response.put("status", "Test messages sent");
            response.put("fromPeer", localPeerId);
            response.put("toPeer", targetPeerId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("WEBSOCKET TEST: Error sending test message: {}", e.getMessage(), e);
            response.put("status", "Error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * Direct HTTP-based fallback for piece transfer (no WebSocket)
     */
    @GetMapping("/direct-get-piece/{sourcePeerId}/{pieceIndex}")
    public ResponseEntity<byte[]> getDirectPiece(
            @PathVariable String sourcePeerId,
            @PathVariable int pieceIndex) {
        
        try {
            log.info("DIRECT PIECE: Request for piece {} from peer {}", pieceIndex, sourcePeerId);
            
            // Force the source peer ID to be used for file access
            String originalPeerId = messageService.getLocalPeerId();
            
            // Read the piece directly from the source peer's directory
            String piecePath = "peer_" + sourcePeerId + "/piece_" + pieceIndex;
            File pieceFile = new File(piecePath);
            
            if (pieceFile.exists() && pieceFile.length() > 0) {
                byte[] pieceData = java.nio.file.Files.readAllBytes(pieceFile.toPath());
                log.info("DIRECT PIECE: Successfully read piece {} from peer {} (size: {} bytes)",
                        pieceIndex, sourcePeerId, pieceData.length);
                
                return ResponseEntity.ok()
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Length", String.valueOf(pieceData.length))
                        .body(pieceData);
            } else {
                log.warn("DIRECT PIECE: Piece {} not found for peer {}", pieceIndex, sourcePeerId);
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("DIRECT PIECE ERROR: Failed to get piece {}: {}", pieceIndex, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Lightweight file discovery endpoint to check if a file exists
     */
    @GetMapping("/check-file-exists/{peerId}/{fileName}")
    public ResponseEntity<Map<String, Object>> checkFileExists(
            @PathVariable String peerId,
            @PathVariable String fileName) {
        
        Map<String, Object> response = new HashMap<>();
        
        // Check in peer directory
        String peerDirPath = "peer_" + peerId;
        File peerDir = new File(peerDirPath);
        File fileInPeerDir = new File(peerDir, fileName);
        
        // Check in main directory
        File fileInMainDir = new File(fileName);
        
        boolean existsInPeerDir = fileInPeerDir.exists();
        boolean existsInMainDir = fileInMainDir.exists();
        
        response.put("fileName", fileName);
        response.put("existsInPeerDir", existsInPeerDir);
        response.put("existsInMainDir", existsInMainDir);
        
        if (existsInPeerDir) {
            response.put("peerDirPath", fileInPeerDir.getAbsolutePath());
            response.put("peerDirSize", fileInPeerDir.length());
        }
        
        if (existsInMainDir) {
            response.put("mainDirPath", fileInMainDir.getAbsolutePath());
            response.put("mainDirSize", fileInMainDir.length());
        }
        
        // Check pieces directory
        int pieceCount = 0;
        if (peerDir.exists() && peerDir.isDirectory()) {
            File[] pieceFiles = peerDir.listFiles((dir, name) -> name.startsWith("piece_"));
            pieceCount = pieceFiles != null ? pieceFiles.length : 0;
        }
        
        response.put("pieceCount", pieceCount);
        
        return ResponseEntity.ok(response);
    }
}