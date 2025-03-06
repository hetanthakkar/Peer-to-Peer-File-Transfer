package com.p2p.torrent.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.p2p.torrent.config.TorrentConfig;
import com.p2p.torrent.model.Message;
import com.p2p.torrent.model.Peer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PeerService {
    private final TorrentConfig config;
    private final SimpMessagingTemplate messagingTemplate;
    private final FileService fileService;
    
    public FileService getFileService() {
        return fileService;
    }
    
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> downloadStatistics = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private String localPeerId;
    
    @PostConstruct
    public void initialize() {
        // Schedule preferred neighbors selection
        scheduler.scheduleAtFixedRate(
            this::selectPreferredNeighbors, 
            config.getUnchokingInterval(), 
            config.getUnchokingInterval(), 
            TimeUnit.SECONDS
        );
        
        // Schedule optimistic unchoke selection
        scheduler.scheduleAtFixedRate(
            this::selectOptimisticUnchokedNeighbor, 
            config.getOptimisticUnchokingInterval(), 
            config.getOptimisticUnchokingInterval(), 
            TimeUnit.SECONDS
        );
    }
    
    public void setLocalPeerId(String peerId, boolean hasFile) {
        this.localPeerId = peerId;
        
        // Create peer directories
        String peerDir = "peer_" + peerId;
        String metadataDir = peerDir + "/metadata";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(peerDir));
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(metadataDir));
        } catch (java.io.IOException e) {
            log.error("Failed to create directories for peer {}", peerId, e);
        }
        
        fileService.initialize(peerId, hasFile);
        
        // Share the peers map with the file service
        fileService.setPeers(peers);
    }
    
    public void registerPeer(String peerId, String hostname, int port, boolean hasFile) {
        // Create peer directories
        String peerDir = "peer_" + peerId;
        String metadataDir = peerDir + "/metadata";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(peerDir));
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(metadataDir));
            log.info("Created directories for peer {}", peerId);
        } catch (java.io.IOException e) {
            log.error("Failed to create directories for peer {}", peerId, e);
        }
        
        Peer peer = new Peer(peerId, hostname, port, hasFile);
        peer.initializeBitfield(fileService.getTotalPieces());
        peers.put(peerId, peer);
        
        if (hasFile) {
            // If this peer has the complete file, ensure its bitfield shows that
            peer.getBitfield().set(0, fileService.getTotalPieces());
            log.info("Set full bitfield for peer {} since it has the complete file", peerId);
        }
    }
    
    public Optional<Peer> getPeer(String peerId) {
        return Optional.ofNullable(peers.get(peerId));
    }
    
    public List<Peer> getAllPeers() {
        return new ArrayList<>(peers.values());
    }
    
    public void updatePeerBitfield(String peerId, int pieceIndex) {
        getPeer(peerId).ifPresent(peer -> {
            peer.getBitfield().set(pieceIndex);
        });
    }
    
    public void recordDownload(String peerId, int bytes) {
        downloadStatistics.compute(peerId, (key, value) -> (value == null) ? bytes : value + bytes);
    }
    
    private void selectPreferredNeighbors() {
        List<Map.Entry<String, Long>> interestedPeers = new ArrayList<>();
        
        // Get all interested peers with their download rates
        peers.forEach((id, peer) -> {
            if (peer.isInterested() && !id.equals(localPeerId)) {
                interestedPeers.add(Map.entry(id, downloadStatistics.getOrDefault(id, 0L)));
            }
        });
        
        // Sort by download rate (descending)
        Collections.sort(interestedPeers, Map.Entry.<String, Long>comparingByValue().reversed());
        
        // Select top k peers as preferred neighbors
        int k = Math.min(config.getNumberOfPreferredNeighbors(), interestedPeers.size());
        List<String> preferredNeighbors = new ArrayList<>();
        
        for (int i = 0; i < k; i++) {
            preferredNeighbors.add(interestedPeers.get(i).getKey());
        }
        
        // Unchoke preferred neighbors, choke others
        peers.forEach((id, peer) -> {
            if (!id.equals(localPeerId)) {
                boolean shouldBeUnchoked = preferredNeighbors.contains(id) || peer.isOptimisticallyUnchoked();
                if (shouldBeUnchoked && peer.isChoked()) {
                    // Unchoke this peer
                    peer.setChoked(false);
                    
                    // Send unchoke message
                    Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(id, "/queue/messages", unchokeMsg);
                    
                    log.info("Unchoked peer: {}", id);
                } else if (!shouldBeUnchoked && !peer.isChoked()) {
                    // Choke this peer
                    peer.setChoked(true);
                    
                    // Send choke message
                    Message chokeMsg = new Message(Message.MessageType.CHOKE, localPeerId, null, null, null);
                    messagingTemplate.convertAndSendToUser(id, "/queue/messages", chokeMsg);
                    
                    log.info("Choked peer: {}", id);
                }
            }
        });
        
        // Reset download statistics
        downloadStatistics.clear();
    }
    
    private void selectOptimisticUnchokedNeighbor() {
        // Reset all optimistically unchoked flags
        peers.values().forEach(peer -> peer.setOptimisticallyUnchoked(false));
        
        // Get all interested and choked peers
        List<Peer> candidates = peers.values().stream()
            .filter(peer -> peer.isInterested() && peer.isChoked() && !peer.getPeerId().equals(localPeerId))
            .collect(Collectors.toList());
        
        if (!candidates.isEmpty()) {
            // Randomly select one peer
            int randomIndex = new Random().nextInt(candidates.size());
            Peer selectedPeer = candidates.get(randomIndex);
            
            // Set as optimistically unchoked
            selectedPeer.setOptimisticallyUnchoked(true);
            selectedPeer.setChoked(false);
            
            // Send unchoke message
            Message unchokeMsg = new Message(Message.MessageType.UNCHOKE, localPeerId, null, null, null);
            messagingTemplate.convertAndSendToUser(selectedPeer.getPeerId(), "/queue/messages", unchokeMsg);
            
            log.info("Optimistically unchoked peer: {}", selectedPeer.getPeerId());
        }
    }
}