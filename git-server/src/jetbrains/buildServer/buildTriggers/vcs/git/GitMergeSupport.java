package jetbrains.buildServer.buildTriggers.vcs.git;

import jetbrains.buildServer.vcs.MergeOptions;
import jetbrains.buildServer.vcs.MergeSupport;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

public class GitMergeSupport implements MergeSupport, GitServerExtension {

  private final GitVcsSupport myVcs;
  private final RepositoryManager myRepositoryManager;
  private final TransportFactory myTransportFactory;

  public GitMergeSupport(@NotNull GitVcsSupport vcs,
                         @NotNull RepositoryManager repositoryManager,
                         @NotNull TransportFactory transportFactory) {
    myVcs = vcs;
    myRepositoryManager = repositoryManager;
    myTransportFactory = transportFactory;
    myVcs.addExtension(this);
  }

  public void merge(@NotNull VcsRoot root,
                    @NotNull String srcRevision,
                    @NotNull String dstBranch,
                    @NotNull String message,
                    @NotNull MergeOptions options) throws VcsException {
    OperationContext context = myVcs.createContext(root, "merge");
    try {
      GitVcsRoot gitRoot = context.getGitRoot();
      Repository db = context.getRepository();
      int attemptsLeft = 3;
      boolean success = false;
      while (!success && attemptsLeft > 0) {
        success = doMerge(gitRoot, db, srcRevision, dstBranch, message);
        attemptsLeft--;
      }
      if (!success)
        throw new VcsException("Merge failed");
    } catch (Exception e) {
      throw context.wrapException(e);
    } finally {
      context.close();
    }
  }


  private boolean doMerge(@NotNull GitVcsRoot gitRoot,
                          @NotNull Repository db,
                          @NotNull String srcRevision,
                          @NotNull String dstBranch,
                          @NotNull String message) throws IOException, VcsException {
    RefSpec spec = new RefSpec().setSource(GitUtils.expandRef(dstBranch)).setDestination(GitUtils.expandRef(dstBranch)).setForceUpdate(true);
    myVcs.fetch(db, gitRoot.getRepositoryFetchURL(), spec, gitRoot.getAuthSettings());

    Ref dstRef = db.getRef(dstBranch);
    ObjectId dstBranchLastCommit = dstRef.getObjectId();

    Merger merger = MergeStrategy.SIMPLE_TWO_WAY_IN_CORE.newMerger(db);
    boolean mergeSuccessful = merger.merge(dstBranchLastCommit, ObjectId.fromString(srcRevision));
    if (!mergeSuccessful)
      return false;

    ObjectInserter inserter = db.newObjectInserter();
    DirCache dc = DirCache.newInCore();
    DirCacheBuilder dcb = dc.builder();

    dcb.addTree(new byte[]{}, 0, db.getObjectDatabase().newReader(), merger.getResultTreeId());
    inserter.flush();
    dcb.finish();

    RevWalk revWalk = new RevWalk(db);
    RevCommit commit = revWalk.parseCommit(ObjectId.fromString(srcRevision));

    ObjectId writtenTreeId = dc.writeTree(inserter);

    CommitBuilder commitBuilder = new CommitBuilder();
    commitBuilder.setCommitter(new PersonIdent("teamcity", "teamcity@buildserver", new Date(), TimeZone.getDefault()));
    commitBuilder.setAuthor(commit.getAuthorIdent());
    commitBuilder.setMessage(message);
    commitBuilder.addParentId(dstBranchLastCommit);
    commitBuilder.addParentId(ObjectId.fromString(srcRevision));
    commitBuilder.setTreeId(writtenTreeId);

    ObjectId commitId = inserter.insert(commitBuilder);
    inserter.flush();

    synchronized (myRepositoryManager.getWriteLock(gitRoot.getRepositoryDir())) {
      final Transport tn = myTransportFactory.createTransport(db, gitRoot.getRepositoryPushURL(), gitRoot.getAuthSettings());
      try {
        final PushConnection c = tn.openPush();
        try {
          RemoteRefUpdate ru = new RemoteRefUpdate(db, null, commitId, GitUtils.expandRef(dstBranch), false, null, dstBranchLastCommit);
          c.push(NullProgressMonitor.INSTANCE, Collections.singletonMap(GitUtils.expandRef(dstBranch), ru));
          switch (ru.getStatus()) {
            case UP_TO_DATE:
            case OK:
              return true;
            default:
              return false;
          }
        } finally {
          c.close();
        }
      } finally {
        tn.close();
      }
    }
  }
}