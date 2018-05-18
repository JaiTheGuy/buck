/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/** Transitive C++ preprocessor input cache */
public class TransitiveCxxPreprocessorInputCache {
  private final Cache<CxxPlatform, ImmutableSortedMap<BuildTarget, CxxPreprocessorInput>> cache =
      CacheBuilder.newBuilder().build();
  private final CxxPreprocessorDep preprocessorDep;
  private final Parallelizer parallelizer;

  public TransitiveCxxPreprocessorInputCache(CxxPreprocessorDep preprocessorDep) {
    this(preprocessorDep, Parallelizer.SERIAL);
  }

  public TransitiveCxxPreprocessorInputCache(
      CxxPreprocessorDep preprocessorDep, Parallelizer parallelizer) {
    this.preprocessorDep = preprocessorDep;
    this.parallelizer = parallelizer;
  }

  /** Get a value from the cache */
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getUnchecked(
      CxxPlatform key, BuildRuleResolver ruleResolver) {
    try {
      return cache.get(
          key,
          () ->
              computeTransitiveCxxToPreprocessorInputMap(
                  key, preprocessorDep, true, ruleResolver, parallelizer));
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  public static ImmutableMap<BuildTarget, CxxPreprocessorInput>
      computeTransitiveCxxToPreprocessorInputMap(
          @Nonnull CxxPlatform key,
          CxxPreprocessorDep preprocessorDep,
          boolean includeDep,
          BuildRuleResolver ruleResolver) {
    return computeTransitiveCxxToPreprocessorInputMap(
        key, preprocessorDep, includeDep, ruleResolver, Parallelizer.SERIAL);
  }

  private static ImmutableSortedMap<BuildTarget, CxxPreprocessorInput>
      computeTransitiveCxxToPreprocessorInputMap(
          @Nonnull CxxPlatform key,
          CxxPreprocessorDep preprocessorDep,
          boolean includeDep,
          BuildRuleResolver ruleResolver,
          Parallelizer parallelizer) {
    Map<BuildTarget, CxxPreprocessorInput> builder = new HashMap<>();
    if (includeDep) {
      builder.put(
          preprocessorDep.getBuildTarget(),
          preprocessorDep.getCxxPreprocessorInput(key, ruleResolver));
    }

    Stream<CxxPreprocessorDep> transitiveDepInputs =
        parallelizer.maybeParallelize(
            RichStream.from(preprocessorDep.getCxxPreprocessorDeps(key, ruleResolver)));

    // We get CxxProcessorInput in parallel for each dep.
    // We have one cache per CxxPreprocessable. Cache miss may trigger the creation of more
    // BuildRules, acyclically.
    // The creation of new BuildRules will be through forked tasks, and because we wait on the
    // Futures of the tasks directly, FJP will have current thread steal the work for those tasks
    // and no deadlock will occur {@link BuildRuleResolverTest.deadLockOnDependencyTest() }.
    transitiveDepInputs
        .map(dep -> dep.getTransitiveCxxPreprocessorInput(key, ruleResolver))
        .forEachOrdered(builder::putAll);

    // Using an ImmutableSortedMap here:
    //
    // 1. Memory efficiency. ImmutableSortedMap is implemented with 2 lists (an ImmutableSortedSet
    // of keys, and a ImmutableList of values). This is much more efficient than an ImmutableMap,
    // which creates an Entry instance for each entry.
    //
    // 2. Historically we seem to care that the result has some definite order.
    //
    // 3. We mostly iterate over these maps rather than do lookups, so ImmutableSortedMap
    // binary-search based lookup is not an issue.
    return ImmutableSortedMap.copyOf(builder);
  }
}
