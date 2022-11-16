// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.vfs.FileSystemUtils.readContent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.CachedActionEvent;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Base class for integration tests for BwoB. */
public abstract class BuildWithoutTheBytesIntegrationTestBase extends BuildIntegrationTestCase {
  // Concrete implementations should by default set the necessary flags to download minimal outputs.
  // These methods should override the necessary flags to download top-level outputs or all outputs.
  protected abstract void setDownloadToplevel();

  protected abstract void setDownloadAll();

  protected abstract void assertOutputEquals(String realContent, String expectedContent)
      throws Exception;

  protected abstract void assertOutputContains(String content, String contains) throws Exception;

  protected void waitDownloads() throws Exception {
    // Trigger afterCommand of modules so that downloads are waited.
    runtimeWrapper.newCommand();
  }

  @Test
  public void outputsAreNotDownloaded() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");

    buildTarget("//:foobar");
    waitDownloads();

    assertOutputsDoNotExist("//:foo");
    assertOutputsDoNotExist("//:foobar");
  }

  @Test
  public void downloadOutputsWithRegex() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    addOptions("--experimental_remote_download_regex=.*foo\\.txt$");

    buildTarget("//:foobar");
    waitDownloads();

    assertValidOutputFile("out/foo.txt", "foo\n");
    assertOutputsDoNotExist("//:foobar");
  }

  @Test
  public void intermediateOutputsAreInputForLocalActions_prefetchIntermediateOutputs()
      throws Exception {
    // Test that a remote-only output that's an input to a local action is downloaded lazily before
    // executing the local action.
    write(
        "a/BUILD",
        "genrule(",
        "  name = 'remote',",
        "  srcs = [],",
        "  outs = ['remote.txt'],",
        "  cmd = 'echo -n remote > $@',",
        ")",
        "",
        "genrule(",
        "  name = 'local',",
        "  srcs = [':remote'],",
        "  outs = ['local.txt'],",
        "  cmd = 'cat $(location :remote) > $@ && echo -n local >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//a:remote");
    waitDownloads();
    assertOutputsDoNotExist("//a:remote");
    buildTarget("//a:local");
    waitDownloads();

    assertOnlyOutputContent("//a:remote", "remote.txt", "remote");
    assertOnlyOutputContent("//a:local", "local.txt", "remotelocal");
  }

  @Test
  public void symlinkToSourceFile() throws Exception {
    write(
        "a/defs.bzl",
        "def _impl(ctx):",
        "  if ctx.attr.chain_length < 1:",
        "    fail('chain_length must be > 0')",
        "",
        "  file = ctx.file.target",
        "",
        "  for i in range(ctx.attr.chain_length):",
        "    sym = ctx.actions.declare_file(ctx.label.name + '.sym' + str(i))",
        "    ctx.actions.symlink(output = sym, target_file = file)",
        "    file = sym",
        "",
        "  out = ctx.actions.declare_file(ctx.label.name + '.out')",
        "  ctx.actions.run_shell(",
        "    inputs = [sym],",
        "    outputs = [out],",
        "    command = '[[ hello == $(cat $1) ]] && touch $2',",
        "    arguments = [sym.path, out.path],",
        "    execution_requirements = {'no-remote': ''} if ctx.attr.local else {},",
        "  )",
        "",
        "  return DefaultInfo(files = depset([out]))",
        "",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'target': attr.label(allow_single_file = True),",
        "    'chain_length': attr.int(),",
        "    'local': attr.bool(),",
        "  },",
        ")");

    write(
        "a/BUILD",
        "load(':defs.bzl', 'my_rule')",
        "",
        "my_rule(name = 'one_local', target = 'src.txt', local = True, chain_length = 1)",
        "my_rule(name = 'two_local', target = 'src.txt', local = True, chain_length = 2)",
        "my_rule(name = 'one_remote', target = 'src.txt', local = False, chain_length = 1)",
        "my_rule(name = 'two_remote', target = 'src.txt', local = False, chain_length = 2)");

    write("a/src.txt", "hello");

    buildTarget("//a:one_local", "//a:two_local", "//a:one_remote", "//a:two_remote");
  }

  @Test
  public void localAction_stdoutIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-output-message > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) && touch $@',",
        "  tags = ['no-remote'],",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    buildTarget("//:foobar");
    waitDownloads();

    assertOutputContains(outErr.outAsLatin1(), "my-output-message");
  }

  @Test
  public void localAction_stderrIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-error-message > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) >&2 && exit 1',",
        "  tags = ['no-remote'],",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    assertThrows(BuildFailedException.class, () -> buildTarget("//:foobar"));

    assertOutputContains(outErr.errAsLatin1(), "my-error-message");
  }

  @Test
  public void dynamicExecution_stdoutIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }

    addOptions("--internal_spawn_scheduler");
    addOptions("--strategy=Genrule=dynamic");
    addOptions("--experimental_local_execution_delay=9999999");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-output-message > $@',",
        "  tags = ['no-local'],",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) && touch $@',",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    buildTarget("//:foobar");
    waitDownloads();

    assertOutputContains(outErr.outAsLatin1(), "my-output-message");
  }

  @Test
  public void dynamicExecution_stderrIsReported() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    addOptions("--internal_spawn_scheduler");
    addOptions("--strategy=Genrule=dynamic");
    addOptions("--experimental_local_execution_delay=9999999");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo my-error-message > $@',",
        "  tags = ['no-local'],",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) >&2 && exit 1',",
        ")");
    RecordingOutErr outErr = new RecordingOutErr();
    this.outErr = outErr;

    assertThrows(BuildFailedException.class, () -> buildTarget("//:foobar"));

    assertOutputContains(outErr.errAsLatin1(), "my-error-message");
  }

  @Test
  public void downloadToplevel_outputsFromImportantOutputGroupAreDownloaded() throws Exception {
    setDownloadToplevel();
    write(
        "rules.bzl",
        "def _gen_impl(ctx):",
        "  output = ctx.actions.declare_file(ctx.attr.name)",
        "  ctx.actions.run_shell(",
        "    outputs = [output],",
        "    arguments = [ctx.attr.content, output.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  extra1 = ctx.actions.declare_file(ctx.attr.name + '1')",
        "  ctx.actions.run_shell(",
        "    outputs = [extra1],",
        "    arguments = [ctx.attr.content, extra1.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  extra2 = ctx.actions.declare_file(ctx.attr.name + '2')",
        "  ctx.actions.run_shell(",
        "    outputs = [extra2],",
        "    arguments = [ctx.attr.content, extra2.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  return [",
        "    DefaultInfo(files = depset([output])),",
        "    OutputGroupInfo(",
        "      extra1_files = depset([extra1]),",
        "      extra2_files = depset([extra2]),",
        "    ),",
        "  ]",
        "",
        "gen = rule(",
        "  implementation = _gen_impl,",
        "  attrs = {",
        "    'content': attr.string(mandatory = True),",
        "  }",
        ")");
    write(
        "BUILD",
        "load(':rules.bzl', 'gen')",
        "gen(",
        "  name = 'foo',",
        "  content = 'foo-content',",
        ")");
    addOptions("--output_groups=+extra1_files");

    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo", "foo-content\n");
    assertValidOutputFile("foo1", "foo-content\n");
    assertOutputDoesNotExist("foo2");
  }

  @Test
  public void downloadToplevel_outputsFromHiddenOutputGroupAreNotDownloaded() throws Exception {
    setDownloadToplevel();
    write(
        "rules.bzl",
        "def _gen_impl(ctx):",
        "  output = ctx.actions.declare_file(ctx.attr.name)",
        "  ctx.actions.run_shell(",
        "    outputs = [output],",
        "    arguments = [ctx.attr.content, output.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  validation_file = ctx.actions.declare_file(ctx.attr.name + '.validation')",
        "  ctx.actions.run_shell(",
        "    outputs = [validation_file],",
        "    arguments = [ctx.attr.content, validation_file.path],",
        "    command = 'echo $1 > $2',",
        "  )",
        "  return [",
        "    DefaultInfo(files = depset([output])),",
        "    OutputGroupInfo(",
        "      _validation = depset([validation_file]),",
        "    ),",
        "  ]",
        "",
        "gen = rule(",
        "  implementation = _gen_impl,",
        "  attrs = {",
        "    'content': attr.string(mandatory = True),",
        "  }",
        ")");
    write(
        "BUILD",
        "load(':rules.bzl', 'gen')",
        "gen(",
        "  name = 'foo',",
        "  content = 'foo-content',",
        ")");
    addOptions("--output_groups=+_validation");

    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo", "foo-content\n");
    assertOutputDoesNotExist("foo.validation");
  }

  @Test
  public void downloadToplevel_treeArtifacts() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    setDownloadToplevel();
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  manifest = ':manifest',",
        ")");
    write("manifest", "file-1", "file-2", "file-3");

    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo/file-1", "file-1\n");
    assertValidOutputFile("foo/file-2", "file-2\n");
    assertValidOutputFile("foo/file-3", "file-3\n");
  }

  @Test
  public void incrementalBuild_deleteOutputsInUnwritableParentDirectory() throws Exception {
    write(
        "BUILD",
        "genrule(",
        "  name = 'unwritable',",
        "  srcs = ['file.in'],",
        "  outs = ['unwritable/somefile.out'],",
        "  cmd = 'cat $(SRCS) > $@; chmod a-w $$(dirname $@)',",
        "  local = True,",
        ")");
    write("file.in", "content");
    buildTarget("//:unwritable");

    write("file.in", "updated content");

    buildTarget("//:unwritable");
  }

  @Test
  public void incrementalBuild_treeArtifacts_correctlyProducesNewTree() throws Exception {
    // Disable on Windows since it fails for unknown reasons.
    // TODO(chiwang): Enable it on windows.
    if (OS.getCurrent() == OS.WINDOWS) {
      return;
    }
    writeOutputDirRule();
    write(
        "BUILD",
        "load(':output_dir.bzl', 'output_dir')",
        "output_dir(",
        "  name = 'foo',",
        "  manifest = ':manifest',",
        ")");
    write("manifest", "file-1", "file-2", "file-3");
    setDownloadToplevel();
    buildTarget("//:foo");
    waitDownloads();

    write("manifest", "file-1", "file-4");
    restartServer();
    setDownloadToplevel();
    buildTarget("//:foo");
    waitDownloads();

    assertValidOutputFile("foo/file-1", "file-1\n");
    assertValidOutputFile("foo/file-4", "file-4\n");
    assertOutputDoesNotExist("foo/file-2");
    assertOutputDoesNotExist("foo/file-3");
  }

  @Test
  public void incrementalBuild_restartServer_hitActionCache() throws Exception {
    // Prepare workspace
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        ")");
    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);

    // Clean build
    buildTarget("//:foobar");

    // all action should be executed
    assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(3);
    // no outputs are staged
    assertOutputsDoNotExist("//:foobar");

    restartServer();
    actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);

    // Incremental build
    buildTarget("//:foobar");

    // all actions should hit the action cache.
    assertThat(actionEventCollector.getActionExecutedEvents()).isEmpty();
    // no outputs are staged
    assertOutputsDoNotExist("//:foobar");
  }

  @Test
  public void incrementalBuild_sourceModified_rerunActions() throws Exception {
    // Arrange: Prepare workspace and run a clean build
    write("foo.in", "foo");
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = ['foo.in'],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'cat $(SRCS) > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo" + lineSeparator());
    assertValidOutputFile("out/foobar.txt", "foo" + lineSeparator() + "bar\n");

    // Act: Modify source file and run an incremental build
    write("foo.in", "modified");

    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: All actions transitively depend on the source file are re-executed and outputs are
    // correct.
    assertValidOutputFile("out/foo.txt", "modified" + lineSeparator());
    assertValidOutputFile("out/foobar.txt", "modified" + lineSeparator() + "bar\n");
    assertThat(actionEventCollector.getNumActionNodesEvaluated()).isEqualTo(2);
  }

  @Test
  public void incrementalBuild_intermediateOutputDeleted_nothingIsReEvaluated() throws Exception {
    // Arrange: Prepare workspace and run a clean build
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo\n");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");

    // Act: Delete intermediate output and run an incremental build
    var fooPath = getOutputPath("out/foo.txt");
    fooPath.delete();

    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: local output is deleted, skyframe should trust remote files so no nodes will be
    // re-evaluated.
    assertOutputDoesNotExist("out/foo.txt");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
    assertThat(actionEventCollector.getNumActionNodesEvaluated()).isEqualTo(0);
  }

  @Test
  public void incrementalBuild_intermediateOutputModified_rerunGeneratingActions()
      throws Exception {
    // Arrange: Prepare workspace and run a clean build
    write(
        "BUILD",
        "genrule(",
        "  name = 'foo',",
        "  srcs = [],",
        "  outs = ['out/foo.txt'],",
        "  cmd = 'echo foo > $@',",
        ")",
        "genrule(",
        "  name = 'foobar',",
        "  srcs = [':foo'],",
        "  outs = ['out/foobar.txt'],",
        "  cmd = 'cat $(location :foo) > $@ && echo bar >> $@',",
        "  tags = ['no-remote'],",
        ")");

    buildTarget("//:foobar");
    assertValidOutputFile("out/foo.txt", "foo\n");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");

    // Act: Modify the intermediate output and run a incremental build
    var fooPath = getOutputPath("out/foo.txt");
    fooPath.delete();
    writeAbsolute(fooPath, "modified");

    ActionEventCollector actionEventCollector = new ActionEventCollector();
    getRuntimeWrapper().registerSubscriber(actionEventCollector);
    buildTarget("//:foobar");

    // Assert: the stale intermediate file should be deleted by skyframe before executing the
    // generating action. Since download minimal, the output didn't get downloaded. Since the input
    // to action :foobar didn't change, we hit the skyframe cache, so the action node didn't event
    // get evaluated. The input didn't get prefetched neither.
    assertOutputDoesNotExist("out/foo.txt");
    assertValidOutputFile("out/foobar.txt", "foo\nbar\n");
    assertThat(actionEventCollector.getActionExecutedEvents()).hasSize(1);
    assertThat(actionEventCollector.getCachedActionEvents()).isEmpty();
    var executedAction = actionEventCollector.getActionExecutedEvents().get(0).getAction();
    assertThat(executedAction.getPrimaryOutput().getFilename()).isEqualTo("foo.txt");
  }

  protected void assertOutputsDoNotExist(String target) throws Exception {
    for (Artifact output : getArtifacts(target)) {
      assertWithMessage(
              "output %s for target %s should not exist", output.getExecPathString(), target)
          .that(output.getPath().exists())
          .isFalse();
    }
  }

  protected Path getOutputPath(String binRelativePath) {
    return getDirectories()
        .getWorkspace()
        .getRelative(getDirectories().getProductName() + "-bin")
        .getRelative(binRelativePath);
  }

  protected void assertOutputDoesNotExist(String binRelativePath) {
    Path output = getOutputPath(binRelativePath);
    assertThat(output.exists()).isFalse();
  }

  protected void assertOnlyOutputContent(String target, String filename, String content)
      throws Exception {
    Artifact output = getOnlyElement(getArtifacts(target));
    assertThat(output.getFilename()).isEqualTo(filename);
    assertThat(output.getPath().exists()).isTrue();
    assertOutputEquals(readContent(output.getPath(), UTF_8), content);
  }

  protected void assertValidOutputFile(String binRelativePath, String content) throws Exception {
    Path output = getOutputPath(binRelativePath);
    assertOutputEquals(readContent(output, UTF_8), content);
    assertThat(output.isReadable()).isTrue();
    assertThat(output.isWritable()).isFalse();
    assertThat(output.isExecutable()).isTrue();
  }

  protected void writeOutputDirRule() throws IOException {
    write(
        "output_dir.bzl",
        "def _output_dir_impl(ctx):",
        "  output_dir = ctx.actions.declare_directory(ctx.attr.name)",
        "  ctx.actions.run_shell(",
        "    inputs = [ctx.file.manifest],",
        "    outputs = [output_dir],",
        "    arguments = [ctx.file.manifest.path, output_dir.path],",
        "    command = 'while read -r line; do echo $line > $2/$line; done < $1',",
        "  )",
        "  return [DefaultInfo(files = depset([output_dir]))]",
        "",
        "output_dir = rule(",
        "  implementation = _output_dir_impl,",
        "  attrs = {",
        "    'manifest': attr.label(mandatory = True, allow_single_file = True),",
        "  }",
        ")");
  }

  protected static class ActionEventCollector {
    private final List<ActionExecutedEvent> actionExecutedEvents = new ArrayList<>();
    private final List<CachedActionEvent> cachedActionEvents = new ArrayList<>();

    @Subscribe
    public void onActionExecuted(ActionExecutedEvent event) {
      actionExecutedEvents.add(event);
    }

    @Subscribe
    public void onCachedAction(CachedActionEvent event) {
      cachedActionEvents.add(event);
    }

    public int getNumActionNodesEvaluated() {
      return getActionExecutedEvents().size() + getCachedActionEvents().size();
    }

    public void clear() {
      this.actionExecutedEvents.clear();
      this.cachedActionEvents.clear();
    }

    public List<ActionExecutedEvent> getActionExecutedEvents() {
      return actionExecutedEvents;
    }

    public List<CachedActionEvent> getCachedActionEvents() {
      return cachedActionEvents;
    }
  }

  protected void restartServer() throws Exception {
    // Simulates a server restart
    createRuntimeWrapper();
  }

  protected static String lineSeparator() {
    return System.getProperty("line.separator");
  }
}