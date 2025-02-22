// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.FilesetManifest.RelativeSymlinkBehavior.ERROR;
import static com.google.devtools.build.lib.actions.FilesetManifest.RelativeSymlinkBehavior.IGNORE;
import static com.google.devtools.build.lib.actions.FilesetManifest.RelativeSymlinkBehavior.RESOLVE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArchivedTreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetManifest;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.util.AnalysisTestUtil;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SpawnInputExpander}. */
@RunWith(JUnit4.class)
public class SpawnInputExpanderTest {
  private static final byte[] FAKE_DIGEST = new byte[] {1, 2, 3, 4};

  private static final ArtifactExpander NO_ARTIFACT_EXPANDER =
      (a, b) -> fail("expected no interactions");

  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path execRoot = fs.getPath("/root");
  private final ArtifactRoot rootDir = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");

  private SpawnInputExpander expander = new SpawnInputExpander(execRoot, /*strict=*/ true);
  private Map<PathFragment, ActionInput> inputMappings = new HashMap<>();

  @Test
  public void testEmptyRunfiles() throws Exception {
    RunfilesSupplier supplier = EmptyRunfilesSupplier.INSTANCE;
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).isEmpty();
  }

  @Test
  public void testRunfilesSingleFile() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(
        artifact,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /*proxy=*/ null, /*size=*/ 0L));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/file"), artifact);
  }

  @Test
  public void testRunfilesWithFileset() throws Exception {
    Artifact artifact = createFilesetArtifact("foo/biz/fs_out");
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(
        artifact,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /*proxy=*/ null, /*size=*/ 0L));

    ArtifactExpander filesetExpander =
        new ArtifactExpander() {
          @Override
          public void expand(Artifact artifact, Collection<? super Artifact> output) {
            throw new IllegalStateException("Unexpected tree expansion");
          }

          @Override
          public ImmutableList<FilesetOutputSymlink> getFileset(Artifact artifact) {
            return ImmutableList.of(
                FilesetOutputSymlink.createForTesting(
                    PathFragment.create("zizz"),
                    PathFragment.create("/foo/fake_exec/xyz/zizz"),
                    PathFragment.create("/foo/fake_exec/")));
          }
        };

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        filesetExpander,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("runfiles/workspace/foo/biz/fs_out/zizz"),
            ActionInputHelper.fromPath("/root/xyz/zizz"));
  }

  @Test
  public void testRunfilesDirectoryStrict() {
    Artifact artifact =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createForDirectoryWithMtime(-1));

    ForbiddenActionInputException expected =
        assertThrows(
            ForbiddenActionInputException.class,
            () ->
                expander.addRunfilesToInputs(
                    inputMappings,
                    supplier,
                    mockCache,
                    NO_ARTIFACT_EXPANDER,
                    PathMapper.NOOP,
                    PathFragment.EMPTY_FRAGMENT));
    assertThat(expected).hasMessageThat().isEqualTo("Not a file: dir/file");
  }

  @Test
  public void testRunfilesDirectoryNonStrict() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(artifact).build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(artifact, FileArtifactValue.createForDirectoryWithMtime(-1));

    expander = new SpawnInputExpander(execRoot, /*strict=*/ false);
    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/file"), artifact);
  }

  @Test
  public void testRunfilesTwoFiles() throws Exception {
    Artifact artifact1 =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Artifact artifact2 =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/baz"));
    Runfiles runfiles =
        new Runfiles.Builder("workspace").addArtifact(artifact1).addArtifact(artifact2).build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(
        artifact1,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /*proxy=*/ null, /*size=*/ 1L));
    mockCache.put(
        artifact2,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /*proxy=*/ null, /*size=*/ 12L));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/file"), artifact1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/dir/baz"), artifact2);
  }

  @Test
  public void testRunfilesTwoFiles_pathMapped() throws Exception {
    Artifact artifact1 =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Artifact artifact2 =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/baz"));
    Runfiles runfiles =
        new Runfiles.Builder("workspace").addArtifact(artifact1).addArtifact(artifact2).build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(
            PathFragment.create("bazel-out/k8-opt/bin/foo.runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(
        artifact1,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /* proxy= */ null, /* size= */ 1L));
    mockCache.put(
        artifact2,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /* proxy= */ null, /* size= */ 12L));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        execPath -> PathFragment.create(execPath.getPathString().replace("k8-opt/", "")),
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("bazel-out/bin/foo.runfiles/workspace/dir/file"), artifact1);
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("bazel-out/bin/foo.runfiles/workspace/dir/baz"), artifact2);
  }

  @Test
  public void testRunfilesSymlink() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Runfiles runfiles =
        new Runfiles.Builder("workspace")
            .addSymlink(PathFragment.create("symlink"), artifact)
            .build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(
        artifact,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /*proxy=*/ null, /*size=*/ 1L));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/symlink"), artifact);
  }

  @Test
  public void testRunfilesRootSymlink() throws Exception {
    Artifact artifact =
        ActionsTestUtil.createArtifact(
            ArtifactRoot.asSourceRoot(Root.fromPath(fs.getPath("/root"))),
            fs.getPath("/root/dir/file"));
    Runfiles runfiles =
        new Runfiles.Builder("workspace")
            .addRootSymlink(PathFragment.create("symlink"), artifact)
            .build();
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache mockCache = new FakeActionInputFileCache();
    mockCache.put(
        artifact,
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, /*proxy=*/ null, /*size=*/ 1L));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        mockCache,
        NO_ARTIFACT_EXPANDER,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings).containsEntry(PathFragment.create("runfiles/symlink"), artifact);
    // If there's no other entry, Runfiles adds an empty file in the workspace to make sure the
    // directory gets created.
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("runfiles/workspace/.runfile"), VirtualActionInput.EMPTY_MARKER);
  }

  @Test
  public void testRunfilesWithTreeArtifacts() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = TreeFileArtifact.createTreeOutput(treeArtifact, "file1");
    TreeFileArtifact file2 = TreeFileArtifact.createTreeOutput(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");

    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(treeArtifact).build();
    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.createForTesting(file1));
    fakeCache.put(file2, FileArtifactValue.createForTesting(file2));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        fakeCache,
        artifactExpander,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/treeArtifact/file1"), file1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/treeArtifact/file2"), file2);
  }

  @Test
  public void testRunfilesWithTreeArtifacts_pathMapped() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = TreeFileArtifact.createTreeOutput(treeArtifact, "file1");
    TreeFileArtifact file2 = TreeFileArtifact.createTreeOutput(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");

    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(treeArtifact).build();
    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(
            PathFragment.create("bazel-out/k8-opt/bin/foo.runfiles"), runfiles);
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.createForTesting(file1));
    fakeCache.put(file2, FileArtifactValue.createForTesting(file2));

    PathMapper pathMapper =
        execPath -> {
          // Replace the config segment "k8-opt" in "bazel-bin/k8-opt/bin" with a hash of the full
          // path to verify that the new paths are constructed by appending the child paths to the
          // mapped parent path, not by mapping the child paths directly.
          PathFragment runfilesPath = execPath.subFragment(3);
          String runfilesPathHash =
              DigestHashFunction.SHA256
                  .getHashFunction()
                  .hashString(runfilesPath.getPathString(), UTF_8)
                  .toString();
          return execPath
              .subFragment(0, 1)
              .getRelative(runfilesPathHash.substring(0, 8))
              .getRelative(execPath.subFragment(2));
        };

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        fakeCache,
        artifactExpander,
        pathMapper,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("bazel-out/2c26b46b/bin/foo.runfiles/workspace/treeArtifact/file1"),
            file1);
    assertThat(inputMappings)
        .containsEntry(
            PathFragment.create("bazel-out/2c26b46b/bin/foo.runfiles/workspace/treeArtifact/file2"),
            file2);
  }

  @Test
  public void testRunfilesWithArchivedTreeArtifacts() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    ArchivedTreeArtifact archivedTreeArtifact = ArchivedTreeArtifact.createForTree(treeArtifact);
    assertThat(archivedTreeArtifact).isNotNull();
    assertThat(treeArtifact.isTreeArtifact()).isTrue();

    Runfiles runfiles = new Runfiles.Builder("workspace").addArtifact(treeArtifact).build();
    ArtifactExpander artifactExpander =
        new ArtifactExpander() {
          @Override
          public void expand(Artifact artifact, Collection<? super Artifact> output) {
            throw new IllegalStateException("Should not do expansion for archived tree");
          }

          @Nullable
          @Override
          public ArchivedTreeArtifact getArchivedTreeArtifact(SpecialArtifact treeArtifact) {
            return archivedTreeArtifact;
          }
        };
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);

    expander =
        new SpawnInputExpander(
            execRoot, /* strict= */ true, IGNORE, /* expandArchivedTreeArtifacts= */ false);
    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        new FakeActionInputFileCache(),
        artifactExpander,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(1);
    assertThat(inputMappings)
        .containsExactly(PathFragment.create("runfiles/workspace/treeArtifact"), treeArtifact);
  }

  @Test
  public void testRunfilesWithTreeArtifactsInSymlinks() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = TreeFileArtifact.createTreeOutput(treeArtifact, "file1");
    TreeFileArtifact file2 = TreeFileArtifact.createTreeOutput(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");
    Runfiles runfiles =
        new Runfiles.Builder("workspace")
            .addSymlink(PathFragment.create("symlink"), treeArtifact)
            .build();

    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    RunfilesSupplier supplier =
        AnalysisTestUtil.createRunfilesSupplier(PathFragment.create("runfiles"), runfiles);
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.createForTesting(file1));
    fakeCache.put(file2, FileArtifactValue.createForTesting(file2));

    expander.addRunfilesToInputs(
        inputMappings,
        supplier,
        fakeCache,
        artifactExpander,
        PathMapper.NOOP,
        PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/symlink/file1"), file1);
    assertThat(inputMappings)
        .containsEntry(PathFragment.create("runfiles/workspace/symlink/file2"), file2);
  }

  @Test
  public void testTreeArtifactsInInputs() throws Exception {
    SpecialArtifact treeArtifact = createTreeArtifact("treeArtifact");
    assertThat(treeArtifact.isTreeArtifact()).isTrue();
    TreeFileArtifact file1 = TreeFileArtifact.createTreeOutput(treeArtifact, "file1");
    TreeFileArtifact file2 = TreeFileArtifact.createTreeOutput(treeArtifact, "file2");
    FileSystemUtils.writeContentAsLatin1(file1.getPath(), "foo");
    FileSystemUtils.writeContentAsLatin1(file2.getPath(), "bar");

    ArtifactExpander artifactExpander =
        (Artifact artifact, Collection<? super Artifact> output) -> {
          if (artifact.equals(treeArtifact)) {
            output.addAll(Arrays.asList(file1, file2));
          }
        };
    FakeActionInputFileCache fakeCache = new FakeActionInputFileCache();
    fakeCache.put(file1, FileArtifactValue.createForTesting(file1));
    fakeCache.put(file2, FileArtifactValue.createForTesting(file2));

    Spawn spawn = new SpawnBuilder("/bin/echo", "Hello World").withInput(treeArtifact).build();
    inputMappings =
        expander.getInputMapping(spawn, artifactExpander, PathFragment.EMPTY_FRAGMENT, fakeCache);
    assertThat(inputMappings).hasSize(2);
    assertThat(inputMappings).containsEntry(PathFragment.create("out/treeArtifact/file1"), file1);
    assertThat(inputMappings).containsEntry(PathFragment.create("out/treeArtifact/file2"), file2);
  }

  private SpecialArtifact createTreeArtifact(String relPath) throws IOException {
    SpecialArtifact treeArtifact = createSpecialArtifact(relPath, SpecialArtifactType.TREE);
    treeArtifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    return treeArtifact;
  }

  private SpecialArtifact createFilesetArtifact(String relPath) throws IOException {
    return createSpecialArtifact(relPath, SpecialArtifactType.FILESET);
  }

  private SpecialArtifact createSpecialArtifact(String relPath, SpecialArtifactType type)
      throws IOException {
    String outputSegment = "out";
    Path outputDir = execRoot.getRelative(outputSegment);
    Path outputPath = outputDir.getRelative(relPath);
    outputPath.createDirectoryAndParents();
    ArtifactRoot derivedRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, outputSegment);
    return SpecialArtifact.create(
        derivedRoot,
        derivedRoot.getExecPath().getRelative(derivedRoot.getRoot().relativize(outputPath)),
        ActionsTestUtil.NULL_ARTIFACT_OWNER,
        type);
  }

  @Test
  public void testEmptyManifest() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(createFileset("out"), ImmutableList.of());

    expander.addFilesetManifests(filesetMappings, inputMappings, PathFragment.EMPTY_FRAGMENT);

    assertThat(inputMappings).isEmpty();
  }

  @Test
  public void testManifestWithSingleFile() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(
            createFileset("out"), ImmutableList.of(filesetSymlink("foo/bar", "/dir/file")));

    expander.addFilesetManifests(filesetMappings, inputMappings, PathFragment.EMPTY_FRAGMENT);

    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/foo/bar"), ActionInputHelper.fromPath("/dir/file"));
  }

  @Test
  public void testManifestWithTwoFiles() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(
            createFileset("out"),
            ImmutableList.of(
                filesetSymlink("foo/bar", "/dir/file"), filesetSymlink("foo/baz", "/dir/file")));

    expander.addFilesetManifests(filesetMappings, inputMappings, PathFragment.EMPTY_FRAGMENT);

    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/foo/bar"), ActionInputHelper.fromPath("/dir/file"),
            PathFragment.create("out/foo/baz"), ActionInputHelper.fromPath("/dir/file"));
  }

  @Test
  public void testManifestWithDirectory() throws Exception {
    Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.of(createFileset("out"), ImmutableList.of(filesetSymlink("foo/bar", "/some")));

    expander.addFilesetManifests(filesetMappings, inputMappings, PathFragment.EMPTY_FRAGMENT);

    assertThat(inputMappings)
        .containsExactly(PathFragment.create("out/foo/bar"), ActionInputHelper.fromPath("/some"));
  }

  private static FilesetOutputSymlink filesetSymlink(String from, String to) {
    return FilesetOutputSymlink.createForTesting(
        PathFragment.create(from), PathFragment.create(to), PathFragment.create("/root"));
  }

  private ImmutableMap<Artifact, ImmutableList<FilesetOutputSymlink>> simpleFilesetManifest() {
    return ImmutableMap.of(
        createFileset("out"),
        ImmutableList.of(
            filesetSymlink("workspace/bar", "foo"), filesetSymlink("workspace/foo", "/root/bar")));
  }

  private SpecialArtifact createFileset(String execPath) {
    return SpecialArtifact.create(
        rootDir,
        PathFragment.create(execPath),
        ActionsTestUtil.NULL_ARTIFACT_OWNER,
        SpecialArtifactType.FILESET);
  }

  @Test
  public void testManifestWithErrorOnRelativeSymlink() {
    expander = new SpawnInputExpander(execRoot, /*strict=*/ true, ERROR);
    FilesetManifest.ForbiddenRelativeSymlinkException e =
        assertThrows(
            FilesetManifest.ForbiddenRelativeSymlinkException.class,
            () ->
                expander.addFilesetManifests(
                    simpleFilesetManifest(), inputMappings, PathFragment.EMPTY_FRAGMENT));
    assertThat(e).hasMessageThat().contains("Fileset symlink foo is not absolute");
  }

  @Test
  public void testManifestWithIgnoredRelativeSymlink() throws Exception {
    expander = new SpawnInputExpander(execRoot, /*strict=*/ true, IGNORE);
    expander.addFilesetManifests(
        simpleFilesetManifest(), inputMappings, PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/workspace/foo"), ActionInputHelper.fromPath("/root/bar"));
  }

  @Test
  public void testManifestWithResolvedRelativeSymlink() throws Exception {
    expander = new SpawnInputExpander(execRoot, /*strict=*/ true, RESOLVE);
    expander.addFilesetManifests(
        simpleFilesetManifest(), inputMappings, PathFragment.EMPTY_FRAGMENT);
    assertThat(inputMappings)
        .containsExactly(
            PathFragment.create("out/workspace/bar"), ActionInputHelper.fromPath("/root/bar"),
            PathFragment.create("out/workspace/foo"), ActionInputHelper.fromPath("/root/bar"));
  }
}
