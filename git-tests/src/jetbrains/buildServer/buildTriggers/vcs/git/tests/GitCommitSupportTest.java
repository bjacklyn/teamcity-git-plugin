/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.git.tests;

import jetbrains.buildServer.buildTriggers.vcs.git.GitCommitSupport;
import jetbrains.buildServer.buildTriggers.vcs.git.GitVcsSupport;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.TestFor;
import jetbrains.buildServer.vcs.*;
import org.assertj.core.groups.Tuple;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static jetbrains.buildServer.buildTriggers.vcs.git.tests.GitSupportBuilder.gitSupport;
import static jetbrains.buildServer.buildTriggers.vcs.git.tests.VcsRootBuilder.vcsRoot;
import static jetbrains.buildServer.util.Util.map;
import static org.assertj.core.api.BDDAssertions.then;
import static org.testng.AssertJUnit.*;

@Test
public class GitCommitSupportTest extends BaseRemoteRepositoryTest {

  private GitVcsSupport myGit;
  private CommitSupport myCommitSupport;
  private VcsRoot myRoot;

  public GitCommitSupportTest() {
    super("merge");
  }

  @BeforeMethod
  @Override
  public void setUp() throws Exception {
    super.setUp();
    ServerPaths myPaths = new ServerPaths(myTempFiles.createTempDir().getAbsolutePath());
    GitSupportBuilder builder = gitSupport().withServerPaths(myPaths);
    myGit = builder.build();
    myCommitSupport = new GitCommitSupport(myGit, builder.getCommitLoader(), builder.getRepositoryManager(), builder.getTransportFactory(),
                                           builder.getPluginConfig());
    myRoot = vcsRoot().withFetchUrl(getRemoteRepositoryDir("merge")).build();
  }


  public void test_commit() throws Exception {
    RepositoryStateData state1 = myGit.getCurrentState(myRoot);

    CommitPatchBuilder patchBuilder = myCommitSupport.getCommitPatchBuilder(myRoot);
    byte[] bytes = "test-content".getBytes();
    patchBuilder.createFile("file-to-commit", new ByteArrayInputStream(bytes));
    patchBuilder.commit(new CommitSettingsImpl("user", "Commit description"));
    patchBuilder.dispose();

    RepositoryStateData state2 = myGit.getCurrentState(myRoot);
    List<ModificationData> changes = myGit.getCollectChangesPolicy().collectChanges(myRoot, state1, state2, CheckoutRules.DEFAULT);
    assertEquals(1, changes.size());
    ModificationData m = changes.get(0);
    assertEquals("user", m.getUserName());
    assertEquals("Commit description", m.getDescription());
    assertEquals("file-to-commit", m.getChanges().get(0).getFileName());
  }


  @TestFor(issues = "TW-38226")
  public void should_canonicalize_line_endings_on_commit() throws Exception {
    CommitPatchBuilder patchBuilder = myCommitSupport.getCommitPatchBuilder(myRoot);
    String committedContent = "a\r\nb\r\nc\r\n";
    byte[] bytes = committedContent.getBytes();
    patchBuilder.createFile("file-to-commit", new ByteArrayInputStream(bytes));
    patchBuilder.commit(new CommitSettingsImpl("user", "Commit description"));
    patchBuilder.dispose();

    RepositoryStateData state2 = myGit.getCurrentState(myRoot);
    byte[] content = myGit.getContentProvider().getContent("file-to-commit", myRoot, state2.getBranchRevisions().get(state2.getDefaultBranchName()));
    assertEquals("Line-endings were not normalized", "a\nb\nc\n", new String(content));

    VcsRoot autoCrlfRoot = vcsRoot().withAutoCrlf(true).withFetchUrl(getRemoteRepositoryDir("merge")).build();
    assertEquals(committedContent, new String(myGit.getContentProvider().getContent("file-to-commit", autoCrlfRoot, state2.getBranchRevisions().get(state2.getDefaultBranchName()))));
  }


  @TestFor(issues = "TW-39051")
  public void should_throw_meaningful_error_if_destination_branch_doesnt_exist() throws Exception {
    String nonExistingBranch = "refs/heads/nonExisting";
    try {
      VcsRoot root = vcsRoot().withFetchUrl(getRemoteRepositoryDir("merge")).withBranch(nonExistingBranch).build();
      CommitPatchBuilder patchBuilder = myCommitSupport.getCommitPatchBuilder(root);
      byte[] bytes = "test-content".getBytes();
      patchBuilder.createFile("file-to-commit", new ByteArrayInputStream(bytes));
      patchBuilder.commit(new CommitSettingsImpl("user", "Commit description"));
      patchBuilder.dispose();
      fail();
    } catch (VcsException e) {
      assertTrue(e.getMessage().contains("The '" + nonExistingBranch + "' destination branch doesn't exist"));
    }
  }


  @TestFor(issues = "TW-39051")
  public void should_create_branch_if_repository_has_no_branches() throws Exception {
    String nonExistingBranch = "refs/heads/nonExisting";

    File remoteRepo = myTempFiles.createTempDir();
    Repository r = new RepositoryBuilder().setBare().setGitDir(remoteRepo).build();
    r.create(true);
    VcsRoot root = vcsRoot().withFetchUrl(remoteRepo).withBranch(nonExistingBranch).build();

    CommitPatchBuilder patchBuilder = myCommitSupport.getCommitPatchBuilder(root);
    byte[] bytes = "test-content".getBytes();
    patchBuilder.createFile("file-to-commit", new ByteArrayInputStream(bytes));
    patchBuilder.commit(new CommitSettingsImpl("user", "Commit description"));
    patchBuilder.dispose();

    RepositoryStateData state2 = myGit.getCurrentState(root);
    assertNotNull(state2.getBranchRevisions().get(nonExistingBranch));
  }


  @TestFor(issues = "TW-42737")
  public void test_directory_remove() throws Exception {
    //create the dir directory with the a file
    CommitPatchBuilder patchBuilder = myCommitSupport.getCommitPatchBuilder(myRoot);
    patchBuilder.createFile("dir/file", new ByteArrayInputStream("content".getBytes()));
    patchBuilder.createFile("dir2/file", new ByteArrayInputStream("content".getBytes()));
    patchBuilder.commit(new CommitSettingsImpl("user", "Create dir with file"));
    patchBuilder.dispose();

    RepositoryStateData state1 = myGit.getCurrentState(myRoot);

    patchBuilder = myCommitSupport.getCommitPatchBuilder(myRoot);
    patchBuilder.deleteDirectory("dir");
    patchBuilder.commit(new CommitSettingsImpl("user", "Delete dir"));
    patchBuilder.dispose();

    RepositoryStateData state2 = myGit.getCurrentState(myRoot);

    List<ModificationData> changes = myGit.getCollectChangesPolicy().collectChanges(myRoot, state1, state2, CheckoutRules.DEFAULT);
    then(changes).hasSize(1);
    then(changes.get(0).getChanges()).extracting("fileName", "type").containsOnly(Tuple.tuple("dir/file", VcsChange.Type.REMOVED));
  }


  @TestFor(issues = "TW-48463")
  public void concurrent_commit() throws Exception {
    //make clone on the server, so that none of the merges perform the clone
    RepositoryStateData s1 = RepositoryStateData.createVersionState("refs/heads/master", map(
      "refs/heads/master", "f727882267df4f8fe0bc58c18559591918aefc54"));
    RepositoryStateData s2 = RepositoryStateData.createVersionState("refs/heads/master", map(
      "refs/heads/master", "f727882267df4f8fe0bc58c18559591918aefc54",
      "refs/heads/topic2", "cc69c22bd5d25779e58ad91008e685cbbe7f700a"));
    myGit.getCollectChangesPolicy().collectChanges(myRoot, s1, s2, CheckoutRules.DEFAULT);

    RepositoryStateData state1 = myGit.getCurrentState(myRoot);

    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch t1Ready = new CountDownLatch(1);
    CountDownLatch t2Ready = new CountDownLatch(1);
    AtomicReference<VcsException> error1 = new AtomicReference<>();
    AtomicReference<VcsException> error2 = new AtomicReference<>();
    Thread t1 = new Thread(() -> {
      CommitPatchBuilder patchBuilder = null;
      try {
        patchBuilder = myCommitSupport.getCommitPatchBuilder(myRoot);
        patchBuilder.createFile("file-to-commit", new ByteArrayInputStream("content1".getBytes()));
        t1Ready.countDown();
        latch.await();
        patchBuilder.commit(new CommitSettingsImpl("user", "Commit1"));
      } catch (VcsException e) {
        error1.set(e);
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        if (patchBuilder != null)
          patchBuilder.dispose();
      }
    });
    t1.start();
    Thread t2 = new Thread(() -> {
      CommitPatchBuilder patchBuilder = null;
      try {
        patchBuilder = myCommitSupport.getCommitPatchBuilder(myRoot);
        patchBuilder.createFile("file-to-commit", new ByteArrayInputStream("content2".getBytes()));
        t2Ready.countDown();
        latch.await();
        patchBuilder.commit(new CommitSettingsImpl("user", "Commit2"));
      } catch (VcsException e) {
        error2.set(e);
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        if (patchBuilder != null)
          patchBuilder.dispose();
      }
    });
    t2.start();
    t1Ready.await();
    t2Ready.await();
    latch.countDown();
    t1.join();
    t2.join();

    RepositoryStateData state2 = myGit.getCurrentState(myRoot);

    List<ModificationData> changes = myGit.getCollectChangesPolicy().collectChanges(myRoot, state1, state2, CheckoutRules.DEFAULT);

    then(changes.size() == 2 || (error1.get() != null || error2.get() != null)) //either both commits succeeds, or one finishes with an error
      .overridingErrorMessage("Non-fast-forward push succeeds")
      .isTrue();
  }


  private class CommitSettingsImpl implements CommitSettings {
    private final String myUserName;
    private final String myDescription;

    public CommitSettingsImpl(@NotNull String userName, @NotNull String description) {
      myUserName = userName;
      myDescription = description;
    }

    @NotNull
    public String getUserName() {
      return myUserName;
    }

    @NotNull
    public String getDescription() {
      return myDescription;
    }
  }
}
