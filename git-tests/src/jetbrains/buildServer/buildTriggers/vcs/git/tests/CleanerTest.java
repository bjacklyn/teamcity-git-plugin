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

import jetbrains.buildServer.BaseTestCase;
import jetbrains.buildServer.TempFiles;
import jetbrains.buildServer.buildTriggers.vcs.git.*;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static jetbrains.buildServer.buildTriggers.vcs.git.tests.GitSupportBuilder.gitSupport;
import static org.assertj.core.api.BDDAssertions.then;

@Test
public class CleanerTest extends BaseTestCase {

  private static final TempFiles ourTempFiles = new TempFiles();
  private Cleanup myCleanup;
  private AtomicBoolean myCleanupCalled;
  private GitVcsSupport mySupport;
  private RepositoryManager myRepositoryManager;
  private ServerPluginConfig myConfig;
  private PluginConfigBuilder myConfigBuilder;

  @BeforeMethod
  public void setUp() throws IOException {
    ServerPaths paths = new ServerPaths(ourTempFiles.createTempDir().getAbsolutePath());
    myConfigBuilder = new PluginConfigBuilder(paths);

    if (System.getenv(Constants.TEAMCITY_AGENT_GIT_PATH) != null)
      myConfigBuilder.setPathToGit(System.getenv(Constants.TEAMCITY_AGENT_GIT_PATH));
  }

  @AfterMethod
  public void tearDown() {
    ourTempFiles.cleanup();
  }


  @Test(dataProvider = "true,false")
  public void test_clean(Boolean useJgitGC) throws VcsException, InterruptedException {
    myConfigBuilder.setMirrorExpirationTimeoutMillis(5000);
    if (useJgitGC) {
      myConfigBuilder.setRunJGitGC(true);
      myConfigBuilder.setRunNativeGC(false);
    } else {
      myConfigBuilder.setRunJGitGC(false);
      myConfigBuilder.setRunNativeGC(true);
    }
    initCleanup();

    File baseMirrorsDir = myRepositoryManager.getBaseMirrorsDir();
    generateGarbage(baseMirrorsDir);

    Thread.sleep(2 * myConfig.getMirrorExpirationTimeoutMillis());

    final VcsRoot root = GitTestUtil.getVcsRoot();
    mySupport.collectChanges(root, "70dbcf426232f7a33c7e5ebdfbfb26fc8c467a46", "a894d7d58ffde625019a9ecf8267f5f1d1e5c341", CheckoutRules.DEFAULT);

    mySupport.getCurrentState(root);//it will create dir in cache directory
    File repositoryDir = getRepositoryDir(root);

    myCleanup.run();

    assertTrue(myCleanupCalled.get());
    File[] files = baseMirrorsDir.listFiles(new FileFilter() {
      public boolean accept(File f) {
        return f.isDirectory();
      }
    });
    assertEquals(1, files.length);
    assertEquals(repositoryDir, files[0]);

    mySupport.getCurrentState(root);//check that repository is fine after git gc
  }


  public void nonInplaceGc() throws Exception {
    myConfigBuilder.setRunNativeGC(true);
    myConfigBuilder.setRunInPlaceGc(false);
    initCleanup();

    VcsRoot root = GitTestUtil.getVcsRoot();
    //clone repository
    mySupport.collectChanges(root, "70dbcf426232f7a33c7e5ebdfbfb26fc8c467a46", "a894d7d58ffde625019a9ecf8267f5f1d1e5c341", CheckoutRules.DEFAULT);
    File repositoryDir = getRepositoryDir(root);

    //create more than 50 packs to trigger gc:
    File packDir = new File(repositoryDir, "objects/pack");
    File pack = new File(packDir, "pack-3763fffad1c368b0a79f9a196ee098e303fc0c29.pack");
    File idx = new File(packDir, "pack-3763fffad1c368b0a79f9a196ee098e303fc0c29.idx");
    for (int i = 10; i <= 60; i++) {
      FileUtil.copy(pack, new File(packDir, "pack-" + i + "63fffad1c368b0a79f9a196ee098e303fc0c29.pack"));
      FileUtil.copy(idx, new File(packDir, "pack-" + i + "63fffad1c368b0a79f9a196ee098e303fc0c29.idx"));
    }
    FileRepository db = (FileRepository) new RepositoryBuilder().setGitDir(repositoryDir).build();
    then(db.getObjectDatabase().getPacks().size() > 50).isTrue();

    myCleanup.run();

    db = (FileRepository) new RepositoryBuilder().setGitDir(repositoryDir).build();
    then(db.getObjectDatabase().getPacks().size()).isEqualTo(1);
  }


  private void initCleanup() {
    myConfig = myConfigBuilder.build();
    GitSupportBuilder gitBuilder = gitSupport().withPluginConfig(myConfig);
    mySupport = gitBuilder.build();
    myRepositoryManager = gitBuilder.getRepositoryManager();
    myCleanup = new Cleanup(myConfig, myRepositoryManager, new GcErrors());
    myCleanupCalled = new AtomicBoolean();
    myCleanup.setCleanupCallWrapper(cleanup -> {
      myCleanupCalled.set(true);
      cleanup.run();
    });
  }


  private File getRepositoryDir(VcsRoot root) throws VcsException {
    GitVcsRoot gitRoot = new GitVcsRoot(myRepositoryManager, root);
    return gitRoot.getRepositoryDir();
  }

  private void generateGarbage(File dir) {
    dir.mkdirs();
    for (int i = 0; i < 10; i++) {
      new File(dir, "git-AHAHAHA"+i+".git").mkdir();
    }
  }


  @DataProvider(name = "true,false")
  public static Object[][] createData() {
    return new Object[][] {
      new Object[] { Boolean.TRUE },
      new Object[] { Boolean.FALSE }
    };
  }
}
