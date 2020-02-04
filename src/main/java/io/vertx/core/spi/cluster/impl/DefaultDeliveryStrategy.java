/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.spi.cluster.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.TaskQueue;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.spi.cluster.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;

/**
 * Default {@link DeliveryStrategy}.
 *
 * @author Thomas Segismont
 */
public class DefaultDeliveryStrategy implements DeliveryStrategy {

  private final ConcurrentMap<String, NodeSelector> selectors = new ConcurrentHashMap<>();
  private final TaskQueue taskQueue = new TaskQueue();

  private ClusterManager clusterManager;
  private NodeInfo nodeInfo;

  @Override
  public void setVertx(VertxInternal vertx) {
    clusterManager = vertx.getClusterManager();
  }

  @Override
  public void setNodeInfo(NodeInfo nodeInfo) {
    this.nodeInfo = nodeInfo;
  }

  @Override
  public Future<List<NodeInfo>> chooseNodes(Message<?> message) {
    ContextInternal context = (ContextInternal) Vertx.currentContext();
    Objects.requireNonNull(context, "Method must be invoked on a Vert.x thread");

    String address = message.address();

    Queue<Waiter> waiters = getWaiters(context, address);

    NodeSelector selector = selectors.get(address);
    if (selector != null && waiters.isEmpty()) {
      return context.succeededFuture(selector.selectNode(message));
    }

    Promise<List<NodeInfo>> promise = context.promise();

    waiters.add(new Waiter(message, promise));
    if (waiters.size() == 1) {
      dequeueWaiters(context, address);
    }
    return promise.future();
  }

  @SuppressWarnings("unchecked")
  private Queue<Waiter> getWaiters(ContextInternal context, String address) {
    Map<String, Queue<Waiter>> map = (Map<String, Queue<Waiter>>) context.contextData().computeIfAbsent(this, ctx -> new HashMap<>());
    return map.computeIfAbsent(address, a -> new ArrayDeque<>());
  }

  private void dequeueWaiters(ContextInternal context, String address) {
    if (Vertx.currentContext() != context) {
      throw new IllegalStateException();
    }

    Queue<Waiter> waiters = getWaiters(context, address);

    Waiter waiter;
    for (; ; ) {
      // FIXME: give a chance to other tasks every 10 iterations
      Waiter peeked = waiters.peek();
      if (peeked == null) {
        throw new IllegalStateException();
      }
      NodeSelector selector = selectors.get(address);
      if (selector != null) {
        Message<?> message = peeked.message;
        peeked.promise.complete(selector.selectNode(message));
        waiters.remove();
        if (waiters.isEmpty()) {
          // TODO: clear waiters?
          return;
        }
      } else {
        waiter = peeked;
        break;
      }
    }

    clusterManager.registrationListener(address)
      .onFailure(t -> {
        waiter.promise.fail(t);
        removeFirstAndDequeueWaiters(context, address);
      })
      .onSuccess(stream -> registrationListenerCreated(context, address, stream));
  }

  private void registrationListenerCreated(ContextInternal context, String address, RegistrationStream registrationStream) {
    if (Vertx.currentContext() != context) {
      throw new IllegalStateException();
    }

    Queue<Waiter> waiters = getWaiters(context, address);

    Waiter waiter = waiters.peek();
    if (waiter == null) {
      throw new IllegalStateException();
    }

    if (registrationStream.initialState().isEmpty()) {
      waiter.promise.complete(Collections.emptyList());
      removeFirstAndDequeueWaiters(context, address);
      return;
    }

    NodeSelector candidate = new NodeSelector(selectors, registrationStream, nodeInfo);
    NodeSelector previous = selectors.putIfAbsent(address, candidate);
    NodeSelector current = previous != null ? previous : candidate;

    waiter.promise.complete(current.selectNode(waiter.message));
    removeFirstAndDequeueWaiters(context, address);

    if (previous == null) {
      current.startListening();
    }
  }

  private void removeFirstAndDequeueWaiters(ContextInternal context, String address) {
    if (Vertx.currentContext() != context) {
      throw new IllegalStateException();
    }

    Queue<Waiter> waiters = getWaiters(context, address);
    waiters.remove();
    if (!waiters.isEmpty()) {
      context.runOnContext(v -> {
        dequeueWaiters(context, address);
      });
    }
  }

  private static class Waiter {
    final Message<?> message;
    final Promise<List<NodeInfo>> promise;

    Waiter(Message<?> message, Promise<List<NodeInfo>> promise) {
      this.message = message;
      this.promise = promise;
    }
  }

  private static class NodeSelector {
    final ConcurrentMap<String, NodeSelector> selectors;
    final RegistrationStream registrationStream;
    final NodeInfo nodeInfo;
    final List<NodeInfo> accessibleNodes;
    final AtomicInteger index = new AtomicInteger(0);

    NodeSelector(ConcurrentMap<String, NodeSelector> selectors, RegistrationStream registrationStream, NodeInfo nodeInfo) {
      this.selectors = selectors;
      this.registrationStream = registrationStream;
      this.nodeInfo = nodeInfo;
      accessibleNodes = compute(registrationStream.initialState());
    }

    NodeSelector(ConcurrentMap<String, NodeSelector> selectors, RegistrationStream registrationStream, NodeInfo nodeInfo, List<NodeInfo> accessibleNodes) {
      this.selectors = selectors;
      this.registrationStream = registrationStream;
      this.nodeInfo = nodeInfo;
      this.accessibleNodes = accessibleNodes;
    }

    List<NodeInfo> compute(List<RegistrationInfo> registrationInfos) {
      return registrationInfos.stream()
        .filter(this::isNodeAccessible)
        .map(RegistrationInfo::getNodeInfo)
        .collect(collectingAndThen(toCollection(ArrayList::new), Collections::unmodifiableList));
    }

    boolean isNodeAccessible(RegistrationInfo registrationInfo) {
      return !registrationInfo.isLocalOnly() || registrationInfo.getNodeInfo().equals(nodeInfo);
    }

    List<NodeInfo> selectNode(Message<?> message) {
      if (accessibleNodes.isEmpty()) {
        return Collections.emptyList();
      }
      if (message.isSend()) {
        return Collections.singletonList(accessibleNodes.get(index.incrementAndGet() % accessibleNodes.size()));
      }
      return accessibleNodes;
    }

    void startListening() {
      registrationStream.exceptionHandler(t -> end()).endHandler(v -> end())
        .pause()
        .handler(this::registrationsUpdated)
        .fetch(1);
    }

    void registrationsUpdated(List<RegistrationInfo> registrationInfos) {
      if (!registrationInfos.isEmpty()) {
        NodeSelector newValue = new NodeSelector(selectors, registrationStream, nodeInfo, compute(registrationInfos));
        if (selectors.replace(registrationStream.address(), this, newValue)) {
          registrationStream.fetch(1);
          return;
        }
      }
      end();
    }

    void end() {
      registrationStream.close();
      selectors.remove(registrationStream.address());
    }
  }
}
