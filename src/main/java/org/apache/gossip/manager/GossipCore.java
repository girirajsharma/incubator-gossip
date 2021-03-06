/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gossip.manager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.gossip.GossipMember;
import org.apache.gossip.LocalGossipMember;
import org.apache.gossip.RemoteGossipMember;
import org.apache.gossip.model.ActiveGossipMessage;
import org.apache.gossip.model.Base;
import org.apache.gossip.model.GossipDataMessage;
import org.apache.gossip.model.Response;
import org.apache.gossip.model.SharedGossipDataMessage;
import org.apache.gossip.udp.Trackable;
import org.apache.gossip.udp.UdpActiveGossipMessage;
import org.apache.gossip.udp.UdpActiveGossipOk;
import org.apache.gossip.udp.UdpGossipDataMessage;
import org.apache.gossip.udp.UdpNotAMemberFault;
import org.apache.gossip.udp.UdpSharedGossipDataMessage;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;

public class GossipCore {
  
  public static final Logger LOGGER = Logger.getLogger(GossipCore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final GossipManager gossipManager;
  private ConcurrentHashMap<String, Base> requests;
  private ExecutorService service;
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, GossipDataMessage>> perNodeData;
  private final ConcurrentHashMap<String, SharedGossipDataMessage> sharedData;
  private final BlockingQueue<Runnable> workQueue;
  
  
  public GossipCore(GossipManager manager){
    this.gossipManager = manager;
    requests = new ConcurrentHashMap<>();
    workQueue = new ArrayBlockingQueue<>(1024);
    service = new ThreadPoolExecutor(1, 5, 1, TimeUnit.SECONDS, workQueue, new DiscardPolicy());
    perNodeData = new ConcurrentHashMap<>();
    sharedData = new ConcurrentHashMap<>();
  }
  
  public void addSharedData(SharedGossipDataMessage message){
     SharedGossipDataMessage previous = sharedData.get(message.getKey());
     if (previous == null){
       sharedData.putIfAbsent(message.getKey(), message);
     } else {
       if (previous.getTimestamp() < message.getTimestamp()){
         sharedData.replace(message.getKey(), previous, message);
       }
     }
  }

  public void addPerNodeData(GossipDataMessage message){
    ConcurrentHashMap<String,GossipDataMessage> nodeMap = new ConcurrentHashMap<>();
    nodeMap.put(message.getKey(), message);
    nodeMap = perNodeData.putIfAbsent(message.getNodeId(), nodeMap);
    if (nodeMap != null){
      GossipDataMessage current = nodeMap.get(message.getKey());
      if (current == null){
        nodeMap.putIfAbsent(message.getKey(), message);
      } else {
        if (current.getTimestamp() < message.getTimestamp()){
          nodeMap.replace(message.getKey(), current, message);
        }
      }
    }
  }
  
  public ConcurrentHashMap<String, ConcurrentHashMap<String, GossipDataMessage>> getPerNodeData(){
    return perNodeData;
  }
  
  public ConcurrentHashMap<String, SharedGossipDataMessage> getSharedData() {
    return sharedData;
  }

  public void shutdown(){
    service.shutdown();
    try {
      service.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      LOGGER.warn(e);
    }
  }
  
  public void receive(Base base){
    if (base instanceof Response){
      if (base instanceof Trackable){
        Trackable t = (Trackable) base;
        requests.put(t.getUuid() + "/" + t.getUriFrom(), (Base) t);
      }
    }
    if (base instanceof GossipDataMessage) {
      UdpGossipDataMessage message = (UdpGossipDataMessage) base;
      addPerNodeData(message);
    }
    if (base instanceof SharedGossipDataMessage){
      UdpSharedGossipDataMessage message = (UdpSharedGossipDataMessage) base;
      addSharedData(message);
    }
    if (base instanceof ActiveGossipMessage){
      List<GossipMember> remoteGossipMembers = new ArrayList<>();
      RemoteGossipMember senderMember = null;
      UdpActiveGossipMessage activeGossipMessage = (UdpActiveGossipMessage) base;
      for (int i = 0; i < activeGossipMessage.getMembers().size(); i++) {
        URI u = null;
        try {
          u = new URI(activeGossipMessage.getMembers().get(i).getUri());
        } catch (URISyntaxException e) {
          LOGGER.debug("Gossip message with faulty URI", e);
          continue;
        }
        RemoteGossipMember member = new RemoteGossipMember(
                activeGossipMessage.getMembers().get(i).getCluster(),
                u,
                activeGossipMessage.getMembers().get(i).getId(),
                activeGossipMessage.getMembers().get(i).getHeartbeat());
        if (i == 0) {
          senderMember = member;
        } 
        if (!(member.getClusterName().equals(gossipManager.getMyself().getClusterName()))){
          UdpNotAMemberFault f = new UdpNotAMemberFault();
          f.setException("Not a member of this cluster " + i);
          f.setUriFrom(activeGossipMessage.getUriFrom());
          f.setUuid(activeGossipMessage.getUuid());
          LOGGER.warn(f);
          sendOneWay(f, member.getUri());
          continue;
        }
        remoteGossipMembers.add(member);
      }
      UdpActiveGossipOk o = new UdpActiveGossipOk();
      o.setUriFrom(activeGossipMessage.getUriFrom());
      o.setUuid(activeGossipMessage.getUuid());
      sendOneWay(o, senderMember.getUri());
      mergeLists(gossipManager, senderMember, remoteGossipMembers);
    }
  }
  
  private void sendInternal(Base message, URI uri){
    byte[] json_bytes;
    try {
      json_bytes = MAPPER.writeValueAsString(message).getBytes();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    int packet_length = json_bytes.length;
    if (packet_length < GossipManager.MAX_PACKET_SIZE) {
      byte[] buf = UdpUtil.createBuffer(packet_length, json_bytes);
      try (DatagramSocket socket = new DatagramSocket()) {
        InetAddress dest = InetAddress.getByName(uri.getHost());
        DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length, dest, uri.getPort());
        socket.send(datagramPacket);
      } catch (IOException e) {
        throw new RuntimeException(e);
      } 
    }
  }
  
  public Response send(Base message, URI uri){
    final Trackable t;
    if (message instanceof Trackable){
      t = (Trackable) message;
    } else {
      t = null;
    }
    sendInternal(message, uri);
    if (t == null){
      return null;
    }
    final Future<Response> response = service.submit( new Callable<Response>(){
      @Override
      public Response call() throws Exception {
        while(true){
          Base b = requests.remove(t.getUuid() + "/" + t.getUriFrom());
          if (b != null){
            return (Response) b;
          }
          try {
            Thread.sleep(0, 1000);
          } catch (InterruptedException e) {
            
          }
        }
      }
    });
    
    try {
      return response.get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      LOGGER.debug(e.getMessage(), e);
      return null;
    } catch (TimeoutException e) {
      boolean cancelled = response.cancel(true);
      LOGGER.debug(String.format("Threadpool timeout attempting to contact %s, cancelled ? %b", uri.toString(), cancelled));
      return null; 
    } finally {
      if (t != null){
        requests.remove(t.getUuid() + "/" + t.getUriFrom());
      }
    }
    
  }
  
  public void sendOneWay(Base message, URI u){
    byte[] json_bytes;
    try {
      json_bytes = MAPPER.writeValueAsString(message).getBytes();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    int packet_length = json_bytes.length;
    if (packet_length < GossipManager.MAX_PACKET_SIZE) {
      byte[] buf = UdpUtil.createBuffer(packet_length, json_bytes);
      try (DatagramSocket socket = new DatagramSocket()) {
        InetAddress dest = InetAddress.getByName(u.getHost());
        DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length, dest, u.getPort());
        socket.send(datagramPacket);
      } catch (IOException ex) { }
    }
  }
  

  /**
   * Merge remote list (received from peer), and our local member list. Simply, we must update the
   * heartbeats that the remote list has with our list. Also, some additional logic is needed to
   * make sure we have not timed out a member and then immediately received a list with that member.
   * 
   * @param gossipManager
   * @param senderMember
   * @param remoteList
   * 
   * COPIED FROM PASSIVE GOSSIP THREAD
   */
  protected void mergeLists(GossipManager gossipManager, RemoteGossipMember senderMember,
          List<GossipMember> remoteList) {

    // if the person sending to us is in the dead list consider them up
    for (LocalGossipMember i : gossipManager.getDeadMembers()) {
      if (i.getId().equals(senderMember.getId())) {
        LOGGER.info(gossipManager.getMyself() + " contacted by dead member " + senderMember.getUri());
        LocalGossipMember newLocalMember = new LocalGossipMember(senderMember.getClusterName(),
                senderMember.getUri(), senderMember.getId(),
                senderMember.getHeartbeat(), gossipManager, gossipManager.getSettings()
                        .getCleanupInterval());
        gossipManager.reviveMember(newLocalMember);
        newLocalMember.startTimeoutTimer();
      }
    }
    for (GossipMember remoteMember : remoteList) {
      if (remoteMember.getId().equals(gossipManager.getMyself().getId())) {
        continue;
      }
      if (gossipManager.getLiveMembers().contains(remoteMember)) {
        LocalGossipMember localMember = gossipManager.getLiveMembers().get(
                gossipManager.getLiveMembers().indexOf(remoteMember));
        if (remoteMember.getHeartbeat() > localMember.getHeartbeat()) {
          localMember.setHeartbeat(remoteMember.getHeartbeat());
          localMember.resetTimeoutTimer();
        }
      } else if (!gossipManager.getLiveMembers().contains(remoteMember)
              && !gossipManager.getDeadMembers().contains(remoteMember)) {
        LocalGossipMember newLocalMember = new LocalGossipMember(remoteMember.getClusterName(),
                remoteMember.getUri(), remoteMember.getId(),
                remoteMember.getHeartbeat(), gossipManager, gossipManager.getSettings()
                        .getCleanupInterval());
        gossipManager.createOrReviveMember(newLocalMember);
        newLocalMember.startTimeoutTimer();
      } else {
        if (gossipManager.getDeadMembers().contains(remoteMember)) {
          LocalGossipMember localDeadMember = gossipManager.getDeadMembers().get(
                  gossipManager.getDeadMembers().indexOf(remoteMember));
          if (remoteMember.getHeartbeat() > localDeadMember.getHeartbeat()) {
            LocalGossipMember newLocalMember = new LocalGossipMember(remoteMember.getClusterName(),
                    remoteMember.getUri(), remoteMember.getId(),
                    remoteMember.getHeartbeat(), gossipManager, gossipManager.getSettings()
                            .getCleanupInterval());
            gossipManager.reviveMember(newLocalMember);
            newLocalMember.startTimeoutTimer();
            LOGGER.debug("Removed remote member " + remoteMember.getAddress()
                    + " from dead list and added to local member list.");
          } else {
            LOGGER.debug("me " + gossipManager.getMyself());
            LOGGER.debug("sender " + senderMember);
            LOGGER.debug("remote " + remoteList);
            LOGGER.debug("live " + gossipManager.getLiveMembers());
            LOGGER.debug("dead " + gossipManager.getDeadMembers());
          }
        } else {
          LOGGER.debug("me " + gossipManager.getMyself());
          LOGGER.debug("sender " + senderMember);
          LOGGER.debug("remote " + remoteList);
          LOGGER.debug("live " + gossipManager.getLiveMembers());
          LOGGER.debug("dead " + gossipManager.getDeadMembers());
          // throw new IllegalArgumentException("wtf");
        }
      }
    }
  }

  
}
