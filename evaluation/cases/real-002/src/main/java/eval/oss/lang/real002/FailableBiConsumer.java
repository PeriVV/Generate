/*
 * Vendored for UnitMind evaluation from https://github.com/apache/commons-lang @ rel/commons-lang-3.14.0.
 * Original path: src/main/java/org/apache/commons/lang3/function/FailableBiConsumer.java
 * Original package `org.apache.commons.lang3.function` rewritten to `eval.oss.lang.real002` for per-case isolation; no other changes.
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

package eval.oss.lang.real002;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A functional interface like {@link BiConsumer} that declares a {@link Throwable}.
 *
 * @param <T> Consumed type 1.
 * @param <U> Consumed type 2.
 * @param <E> The kind of thrown exception or error.
 * @since 3.11
 */
@FunctionalInterface
public interface FailableBiConsumer<T, U, E extends Throwable> {

    /** NOP singleton */
    @SuppressWarnings("rawtypes")
    FailableBiConsumer NOP = (t, u) -> {/* NOP */};

    /**
     * Returns The NOP singleton.
     *
     * @param <T> Consumed type 1.
     * @param <U> Consumed type 2.
     * @param <E> The kind of thrown exception or error.
     * @return The NOP singleton.
     */
    @SuppressWarnings("unchecked")
    static <T, U, E extends Throwable> FailableBiConsumer<T, U, E> nop() {
        return NOP;
    }

    /**
     * Accepts the given arguments.
     *
     * @param t the first parameter for the consumable to accept
     * @param u the second parameter for the consumable to accept
     * @throws E Thrown when the consumer fails.
     */
    void accept(T t, U u) throws E;

    /**
     * Returns a composed {@link FailableBiConsumer} like {@link BiConsumer#andThen(BiConsumer)}.
     *
     * @param after the operation to perform after this one.
     * @return a composed {@link FailableBiConsumer} like {@link BiConsumer#andThen(BiConsumer)}.
     * @throws NullPointerException when {@code after} is null.
     */
    default FailableBiConsumer<T, U, E> andThen(final FailableBiConsumer<? super T, ? super U, E> after) {
        Objects.requireNonNull(after);
        return (t, u) -> {
            accept(t, u);
            after.accept(t, u);
        };
    }
}
