/*
 * Vendored for UnitMind evaluation from https://github.com/apache/commons-lang @ rel/commons-lang-3.14.0.
 * Original path: src/main/java/org/apache/commons/lang3/builder/ToStringSummary.java
 * Original package `org.apache.commons.lang3.builder` rewritten to `eval.oss.lang.real001` for per-case isolation; no other changes.
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

package eval.oss.lang.real001;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use this annotation on the fields to get the summary instead of the detailed
 * information when using {@link ReflectionToStringBuilder}.
 *
 * <p>
 * Notice that not all {@link ToStringStyle} implementations support the
 * appendSummary method.
 * </p>
 *
 * @since 3.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ToStringSummary {
    // empty
}
