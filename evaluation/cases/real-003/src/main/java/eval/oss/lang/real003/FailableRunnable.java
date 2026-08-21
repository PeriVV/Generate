/*
 * Vendored for UnitMind evaluation from https://github.com/apache/commons-lang @ rel/commons-lang-3.14.0.
 * Original path: src/main/java/org/apache/commons/lang3/function/FailableRunnable.java
 * Original package `org.apache.commons.lang3.function` rewritten to `eval.oss.lang.real003` for per-case isolation; no other changes.
 * License: Apache-2.0 — see evaluation/licenses/commons-lang-Apache-2.0.txt.
 */
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eval.oss.lang.real003;

/**
 * A functional interface like {@link Runnable} that declares a kind of {@link Throwable}.
 *
 * @param <E> The kind of {@link Throwable}.
 * @since 3.11
 */
@FunctionalInterface
public interface FailableRunnable<E extends Throwable> {

    /**
     * Runs the function.
     *
     * @throws E Thrown when the function fails.
     */
    void run() throws E;
}
