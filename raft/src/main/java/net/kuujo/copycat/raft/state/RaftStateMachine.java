/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.raft.state;

import net.kuujo.copycat.cluster.MemberInfo;
import net.kuujo.copycat.cluster.Session;
import net.kuujo.copycat.raft.*;
import net.kuujo.copycat.raft.log.Compaction;
import net.kuujo.copycat.raft.log.entry.*;
import net.kuujo.copycat.util.ExecutionContext;
import net.kuujo.copycat.util.concurrent.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Resource state machine.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class RaftStateMachine {
  private final StateMachine stateMachine;
  private final ExecutionContext context;
  private final Map<Long, RaftSession> sessions = new HashMap<>();
  private final Map<Long, List<Runnable>> queries = new HashMap<>();
  private long sessionTimeout = 5000;
  private long lastApplied;

  public RaftStateMachine(StateMachine stateMachine, ExecutionContext context) {
    this.stateMachine = stateMachine;
    this.context = context;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public long getSessionTimeout() {
    return sessionTimeout;
  }

  /**
   * Sets the session timeout.
   *
   * @param sessionTimeout The session timeout.
   * @return The Raft state machine.
   */
  public RaftStateMachine setSessionTimeout(long sessionTimeout) {
    if (sessionTimeout <= 0)
      throw new IllegalArgumentException("session timeout must be positive");
    this.sessionTimeout = sessionTimeout;
    return this;
  }

  /**
   * Returns the last index applied to the state machine.
   *
   * @return The last index applied to the state machine.
   */
  public long getLastApplied() {
    return lastApplied;
  }

  /**
   * Sets the last index applied to the state machine.
   *
   * @param lastApplied The last index applied to the state machine.
   */
  private void setLastApplied(long lastApplied) {
    this.lastApplied = lastApplied;
    List<Runnable> queries = this.queries.remove(lastApplied);
    if (queries != null) {
      queries.forEach(Runnable::run);
    }
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(Entry entry, Compaction compaction) {
    if (entry instanceof OperationEntry) {
      return filter((OperationEntry) entry, compaction);
    } else if (entry instanceof RegisterEntry) {
      return filter((RegisterEntry) entry, compaction);
    } else if (entry instanceof KeepAliveEntry) {
      return filter((KeepAliveEntry) entry, compaction);
    }
    return CompletableFuture.completedFuture(false);
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(RegisterEntry entry, Compaction compaction) {
    return CompletableFuture.completedFuture(sessions.containsKey(entry.getIndex()));
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(KeepAliveEntry entry, Compaction compaction) {
    return CompletableFuture.completedFuture(sessions.containsKey(entry.getIndex()) && sessions.get(entry.getIndex()).index == entry.getIndex());
  }

  /**
   * Filters a no-op entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(NoOpEntry entry, Compaction compaction) {
    return CompletableFuture.completedFuture(false);
  }

  /**
   * Filters an entry.
   *
   * @param entry The entry to filter.
   * @return A boolean value indicating whether to keep the entry.
   */
  public CompletableFuture<Boolean> filter(CommandEntry entry, Compaction compaction) {
    RaftSession session = sessions.get(entry.getSession());
    if (session == null) {
      session = new RaftSession(entry.getSession(), null, entry.getTimestamp());
      session.expire();
    }
    Commit<? extends Command> commit = new Commit<>(entry.getIndex(), session, entry.getTimestamp(), entry.getCommand());
    return CompletableFuture.supplyAsync(() -> stateMachine.filter(commit, compaction), context);
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<?> apply(Entry entry) {
    if (entry instanceof CommandEntry) {
      return apply((CommandEntry) entry);
    } else if (entry instanceof QueryEntry) {
      return apply((QueryEntry) entry);
    } else if (entry instanceof RegisterEntry) {
      return apply((RegisterEntry) entry);
    } else if (entry instanceof KeepAliveEntry) {
      return apply((KeepAliveEntry) entry);
    } else if (entry instanceof NoOpEntry) {
      return apply((NoOpEntry) entry);
    }
    return Futures.exceptionalFuture(new InternalException("unknown state machine operation"));
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Long> apply(RegisterEntry entry) {
    return register(entry.getIndex(), entry.getTimestamp(), entry.getMember());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   */
  public CompletableFuture<Void> apply(KeepAliveEntry entry) {
    return keepAlive(entry.getIndex(), entry.getTimestamp(), entry.getSession());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Object> apply(CommandEntry entry) {
    return command(entry.getIndex(), entry.getSession(), entry.getRequest(), entry.getResponse(), entry.getTimestamp(), entry.getCommand());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Object> apply(QueryEntry entry) {
    return query(entry.getIndex(), entry.getSession(), entry.getTimestamp(), entry.getQuery());
  }

  /**
   * Applies an entry to the state machine.
   *
   * @param entry The entry to apply.
   * @return The result.
   */
  public CompletableFuture<Long> apply(NoOpEntry entry) {
    return noop(entry.getIndex());
  }

  /**
   * Registers a member session.
   *
   * @param index The registration index.
   * @param timestamp The registration timestamp.
   * @param member The member info.
   * @return The session ID.
   */
  private CompletableFuture<Long> register(long index, long timestamp, MemberInfo member) {
    RaftSession session = new RaftSession(index, member, timestamp);
    sessions.put(index, session);
    setLastApplied(index);
    return CompletableFuture.supplyAsync(() -> {
      stateMachine.register(session);
      return session.id();
    }, context);
  }

  /**
   * Keeps a member session alive.
   *
   * @param index The keep alive index.
   * @param timestamp The keep alive timestamp.
   * @param sessionId The session to keep alive.
   */
  private CompletableFuture<Void> keepAlive(long index, long timestamp, long sessionId) {
    RaftSession session = sessions.get(sessionId);
    setLastApplied(index);
    if (session == null) {
      return Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + sessionId));
    } else if (!session.update(index, timestamp)) {
      sessions.remove(sessionId);
      context.execute(() -> stateMachine.expire(session));
      return Futures.exceptionalFuture(new UnknownSessionException("session expired: " + sessionId));
    }
    return CompletableFuture.runAsync(() -> {}, context);
  }

  /**
   * Applies a no-op to the state machine.
   *
   * @param index The no-op index.
   * @return The no-op index.
   */
  private CompletableFuture<Long> noop(long index) {
    setLastApplied(index);
    return CompletableFuture.supplyAsync(() -> index, context);
  }

  /**
   * Applies a command to the state machine.
   *
   * @param index The command index.
   * @param sessionId The command session ID.
   * @param request The command request ID.
   * @param response The command response ID.
   * @param timestamp The command timestamp.
   * @param command The command to apply.
   * @return The command result.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Object> command(long index, long sessionId, long request, long response, long timestamp, Command command) {
    // First check to ensure that the session exists.
    RaftSession session = sessions.get(sessionId);
    if (session == null) {
      return Futures.exceptionalFuture(new UnknownSessionException("unknown session " + sessionId));
    } else if (!session.update(index, timestamp)) {
      sessions.remove(sessionId);
      context.execute(() -> stateMachine.expire(session));
      return Futures.exceptionalFuture(new UnknownSessionException("unknown session " + sessionId));
    }

    // Given the session, check for an existing result for this command.
    if (session.responses.containsKey(request)) {
      return CompletableFuture.completedFuture(session.responses.get(request));
    }

    // Apply the command to the state machine.
    setLastApplied(index);
    return CompletableFuture.supplyAsync(() -> stateMachine.apply(new Commit(index, session, timestamp, command)), context)
      .thenApply(result -> {
        // Store the command result in the session.
        session.responses.put(request, result);

        // Clear any responses that have been received by the client for the session.
        session.responses.headMap(response, true).clear();
        return result;
      });
  }

  /**
   * Applies a query to the state machine.
   *
   * @param index The query index.
   * @param sessionId The query session ID.
   * @param timestamp The query timestamp.
   * @param query The query to apply.
   * @return The query result.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Object> query(long index, long sessionId, long timestamp, Query query) {
    if (sessionId > lastApplied) {
      CompletableFuture<Object> future = new CompletableFuture<>();
      List<Runnable> queries = this.queries.computeIfAbsent(sessionId, id -> new ArrayList<>());
      queries.add(() -> CompletableFuture.supplyAsync(() -> stateMachine.apply(new Commit(index, sessions.get(sessionId), timestamp, query)), context).whenComplete((result, error) -> {
        if (error == null) {
          future.complete(result);
        } else {
          future.completeExceptionally((Throwable) error);
        }
      }));
      return future;
    } else {
      RaftSession session = sessions.get(sessionId);
      if (session == null) {
        return Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + sessionId));
      } else if (!session.expire(timestamp)) {
        context.execute(() -> stateMachine.expire(session));
        return Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + sessionId));
      } else {
        return CompletableFuture.supplyAsync(() -> stateMachine.apply(new Commit(index, session, timestamp, query)), context);
      }
    }
  }

  /**
   * Cluster session.
   */
  private class RaftSession extends Session {
    private long index;
    private long timestamp;
    private final TreeMap<Long, Object> responses = new TreeMap<>();

    private RaftSession(long id, MemberInfo member, long timestamp) {
      super(id, member);
      this.timestamp = timestamp;
    }

    /**
     * Expires the session.
     */
    public void expire() {
      super.expire();
    }

    /**
     * Returns the session timestamp.
     *
     * @return The session timestamp.
     */
    public long timestamp() {
      return timestamp;
    }

    /**
     * Updates the session.
     *
     * @param timestamp The session.
     */
    private boolean expire(long timestamp) {
      if (timestamp - sessionTimeout > this.timestamp) {
        expire();
        return false;
      }
      this.timestamp = timestamp;
      return true;
    }

    /**
     * Updates the session.
     *
     * @param timestamp The session.
     */
    private boolean update(long index, long timestamp) {
      if (timestamp - sessionTimeout > this.timestamp) {
        expire();
        return false;
      }
      this.index = index;
      this.timestamp = timestamp;
      return true;
    }

  }

}
