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
package net.kuujo.copycat.raft.log.entry;

import net.kuujo.copycat.raft.log.Compaction;

import java.util.concurrent.CompletableFuture;

/**
 * Raft log entry filter.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@FunctionalInterface
public interface EntryFilter {

  /**
   * Returns a boolean value indicating whether to keep the given entry in the log.
   *
   * @param entry The entry to check.
   * @param compaction The compaction context.
   * @return Indicates whether to keep the entry in the log.
   */
  CompletableFuture<Boolean> accept(Entry entry, Compaction compaction);

}
