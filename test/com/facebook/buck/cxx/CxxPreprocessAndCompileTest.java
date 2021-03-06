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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKeyPair;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessAndCompileTest {

  private static final Tool DEFAULT_COMPILER = new HashedFileTool(Paths.get("compiler"));
  private static final ImmutableList<String> DEFAULT_FLAGS =
      ImmutableList.of("-fsanitize=address");
  private static final Path DEFAULT_OUTPUT = Paths.get("test.o");
  private static final SourcePath DEFAULT_INPUT = new TestSourcePath("test.cpp");
  private static final CxxHeaders DEFAULT_INCLUDES =
      CxxHeaders.builder()
          .putNameToPathMap(Paths.get("test.h"), new TestSourcePath("foo/test.h"))
          .build();
  private static final ImmutableList<Path> DEFAULT_INCLUDE_ROOTS = ImmutableList.of(
      Paths.get("foo/bar"),
      Paths.get("test"));
  private static final ImmutableList<Path> DEFAULT_SYSTEM_INCLUDE_ROOTS = ImmutableList.of(
      Paths.get("/usr/include"),
      Paths.get("/include"));
  private static final ImmutableList<Path> DEFAULT_FRAMEWORK_ROOTS = ImmutableList.of();
  private static final Optional<DebugPathSanitizer> DEFAULT_SANITIZER = Optional.absent();

  private RuleKeyPair generateRuleKey(
      RuleKeyBuilderFactory factory,
      SourcePathResolver pathResolver,
      AbstractBuildRule rule) {

    RuleKey.Builder builder = factory.newInstance(rule, pathResolver);
    return builder.build();
  }

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("compiler", Strings.repeat("a", 40))
                    .put("test.o", Strings.repeat("b", 40))
                    .put("test.cpp", Strings.repeat("c", 40))
                    .put("different", Strings.repeat("d", 40))
                    .put("foo/test.h", Strings.repeat("e", 40))
                    .put("path/to/a/plugin.so", Strings.repeat("f", 40))
                    .put("path/to/a/different/plugin.so", Strings.repeat("a0", 40))
                    .build()));

    // Generate a rule key for the defaults.
    RuleKeyPair defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));

    // Verify that changing the compiler causes a rulekey change.
    RuleKeyPair compilerChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            new HashedFileTool(Paths.get("different")),
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the operation causes a rulekey change.
    RuleKeyPair operationChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.PREPROCESS,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, operationChange);

    // Verify that changing the flags causes a rulekey change.
    RuleKeyPair flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            ImmutableList.of("-different"),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, flagsChange);

    // Verify that changing the input causes a rulekey change.
    RuleKeyPair inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            new TestSourcePath("different"),
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.
    RuleKeyPair includesChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            ImmutableList.of(Paths.get("different")),
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertEquals(defaultRuleKey, includesChange);

    // Verify that changing the system includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.
    RuleKeyPair systemIncludesChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            ImmutableList.of(Paths.get("different")),
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertEquals(defaultRuleKey, systemIncludesChange);

    // Verify that changing the framework roots causes a rulekey change.
    RuleKeyPair frameworkRootsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            ImmutableList.of(Paths.get("different")),
            DEFAULT_INCLUDES,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, frameworkRootsChange);
  }

  @Test
  public void sanitizedPathsInFlagsDoNotAffectRuleKey() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("compiler", Strings.repeat("a", 40))
                    .put("test.o", Strings.repeat("b", 40))
                    .put("test.cpp", Strings.repeat("c", 40))
                    .put("different", Strings.repeat("d", 40))
                    .put("foo/test.h", Strings.repeat("e", 40))
                    .put("path/to/a/plugin.so", Strings.repeat("f", 40))
                    .put("path/to/a/different/plugin.so", Strings.repeat("a0", 40))
                    .build()));

    // Set up a map to sanitize the differences in the flags.
    int pathSize = 10;
    DebugPathSanitizer sanitizer1 = new DebugPathSanitizer(
        pathSize,
        File.separatorChar,
        "PWD",
        ImmutableBiMap.of(Paths.get("something"), "A"));
    DebugPathSanitizer sanitizer2 = new DebugPathSanitizer(
        pathSize,
        File.separatorChar,
        "PWD",
        ImmutableBiMap.of(Paths.get("different"), "A"));

    // Generate a rule key for the defaults.
    ImmutableList<String> flags1 = ImmutableList.of("-Isomething/foo");
    RuleKeyPair ruleKey1 = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.PREPROCESS,
            flags1,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            Optional.of(sanitizer1)));

    // Generate a rule key for the defaults.
    ImmutableList<String> flags2 = ImmutableList.of("-Idifferent/foo");
    RuleKeyPair ruleKey2 = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxPreprocessAndCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            CxxPreprocessAndCompileStep.Operation.PREPROCESS,
            flags2,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_INCLUDES,
            Optional.of(sanitizer2)));

    assertEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void usesCorrectCommandForCompile() {

    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    ImmutableList<String> flags = ImmutableList.of("-ffunction-sections");
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.ii");

    CxxPreprocessAndCompile buildRule = new CxxPreprocessAndCompile(
        params,
        pathResolver,
        DEFAULT_COMPILER,
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        flags,
        output,
        new TestSourcePath(input.toString()),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        DEFAULT_FRAMEWORK_ROOTS,
        CxxHeaders.builder().build(),
        DEFAULT_SANITIZER);

    ImmutableList<String> expectedCompileCommand = ImmutableList.<String>builder()
        .add("compiler")
        .add("-c")
        .add("-ffunction-sections")
        .add("-o", output.toString())
        .add(input.toString())
        .build();
    ImmutableList<String> actualCompileCommand = buildRule.getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @Test
  public void usesCorrectCommandForPreprocess() {

    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    ImmutableList<String> flags = ImmutableList.of("-Dtest=blah");
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");

    CxxPreprocessAndCompile buildRule = new CxxPreprocessAndCompile(
        params,
        pathResolver,
        DEFAULT_COMPILER,
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        flags,
        output,
        new TestSourcePath(input.toString()),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        DEFAULT_FRAMEWORK_ROOTS,
        CxxHeaders.builder().build(),
        DEFAULT_SANITIZER);

    // Verify it uses the expected command.
    ImmutableList<String> expectedPreprocessCommand = ImmutableList.<String>builder()
        .add("compiler")
        .add("-E")
        .add("-Dtest=blah")
        .add(input.toString())
        .build();
    ImmutableList<String> actualPreprocessCommand = buildRule.getCommand();
    assertEquals(expectedPreprocessCommand, actualPreprocessCommand);
  }
}
