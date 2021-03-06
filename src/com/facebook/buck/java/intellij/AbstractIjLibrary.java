/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.java.intellij;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;

import org.immutables.value.Value;

/**
 * Represents a prebuilt library (.jar or .aar) as seen by IntelliJ.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjLibrary {
  /**
   * @return unique string identifying the library. This will be used by modules to refer to the
   *         library.
   */
  public abstract String getName();

  /**
   * @return path to the binary (.jar or .aar) the library represents.
   */
  public abstract SourcePath getBinaryJar();

  /**
   * @return path to the jar containing sources for the library.
   */
  public abstract Optional<SourcePath> getSourceJar();

  /**
   * @return url to the javadoc.
   */
  public abstract Optional<String> getJavadocUrl();
}
