/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.metastore;

import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.exception.runtime.InternalRuntimeException;
import alluxio.file.options.DescendantType;
import alluxio.master.file.meta.EdgeEntry;
import alluxio.master.file.meta.Inode;
import alluxio.master.file.meta.InodeDirectoryView;
import alluxio.master.file.meta.InodeIterationResult;
import alluxio.master.file.meta.InodeTree;
import alluxio.master.file.meta.LockedInodePath;
import alluxio.master.file.meta.MutableInode;
import alluxio.resource.CloseableIterator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only access to the inode store.
 */
public interface ReadOnlyInodeStore extends Closeable {
  /**
   * @param id an inode id
   * @param option the options
   * @return the inode with the given id, if it exists
   */
  Optional<Inode> get(long id, ReadOption option);

  /**
   * @param id an inode id
   * @return the result of {@link #get(long, ReadOption)} with default option
   */
  default Optional<Inode> get(long id) {
    return get(id, ReadOption.defaults());
  }

  /**
   * Returns a closeable stream of the inodes sorted by filename of the children of the given
   *  directory that come after and including fromName.
   * @param parentId the inode id of the parent directory
   * @param fromName the inode from which to start listing
   * @return an iterator of the children starting from fromName
   */
  default CloseableIterator<? extends Inode> getChildrenFrom(
      final long parentId, final String fromName) {
    return getChildren(parentId,
        ReadOption.newBuilder().setReadFrom(fromName).build());
  }

  /**
   * Returns a closeable stream of the inodes sorted by filename of the children of the given
   *  directory that come after and including fromName.
   * @param parentId the inode id of the parent directory
   * @param prefix the prefix to match
   * @return an iterator of the children starting from fromName
   */
  default CloseableIterator<? extends Inode> getChildrenPrefix(
      final long parentId, final String prefix) {
    return getChildren(parentId,
        ReadOption.newBuilder().setPrefix(prefix).build());
  }

  /**
   * Returns a closeable stream of the inodes sorted by filename of the children of the given
   *  directory that come after and including fromName, and matching the prefix.
   * @param parentId the inode id of the parent directory
   * @param prefix the prefix to match
   * @param fromName the inode from which to start listing
   * @return an iterator of the children starting from fromName
   */
  default CloseableIterator<? extends Inode> getChildrenPrefixFrom(
      final long parentId, final String prefix, final String fromName) {
    return getChildren(parentId,
        ReadOption.newBuilder().setPrefix(prefix).setReadFrom(fromName).build());
  }

  /**
   * Returns an iterable for the ids of the children of the given directory.
   *
   * @param inodeId an inode id to list child ids for
   * @param option the options
   * @return the child ids iterable
   */
  CloseableIterator<Long> getChildIds(Long inodeId, ReadOption option);

  /**
   * @param inodeId an inode id to list child ids for
   * @return the result of {@link #getChildIds(Long, ReadOption)} with default option
   */
  default CloseableIterator<Long> getChildIds(Long inodeId) {
    return getChildIds(inodeId, ReadOption.defaults());
  }

  /**
   * Returns an iterable for the ids of the children of the given directory.
   *
   * @param inode the inode to list child ids for
   * @param option the options
   * @return the child ids iterable
   */
  default CloseableIterator<Long> getChildIds(InodeDirectoryView inode, ReadOption option) {
    return getChildIds(inode.getId(), option);
  }

  /**
   * Returns an iterator over the children of the specified inode.
   *
   * The iterator is weakly consistent. It can operate in the presence of concurrent modification,
   * but it is undefined whether concurrently removed inodes will be excluded or whether
   * concurrently added inodes will be included.
   *
   * @param inodeId an inode id
   * @param option the options
   * @return an iterable over the children of the inode with the given id
   */
  default CloseableIterator<? extends Inode> getChildren(Long inodeId, ReadOption option) {
    CloseableIterator<Long> it = getChildIds(inodeId, option);
    Iterator<Inode> iter =  new Iterator<Inode>() {
      private Inode mNext = null;
      @Override
      public boolean hasNext() {
        advance();
        return mNext != null;
      }

      @Override
      public Inode next() {
        if (!hasNext()) {
          throw new NoSuchElementException(
              "No more children in iterator for inode id " + inodeId);
        }
        Inode next = mNext;
        mNext = null;
        return next;
      }

      void advance() {
        while (mNext == null && it.hasNext()) {
          Long nextId = it.next();
          // Make sure the inode metadata still exists
          Optional<Inode> nextInode = get(nextId, option);
          nextInode.ifPresent(inode -> mNext = inode);
        }
      }
    };
    return CloseableIterator.create(iter, (any) -> it.close());
  }

  /**
   * @param inodeId an inode id
   * @return the result of {@link #getChildren(Long, ReadOption)} with default option
   */
  default CloseableIterator<? extends Inode> getChildren(Long inodeId) {
    return getChildren(inodeId, ReadOption.defaults());
  }

  /**
   * @param inode an inode directory
   * @param option the options
   * @return an iterable over the children of the inode with the given id
   */
  default CloseableIterator<? extends Inode> getChildren(
      InodeDirectoryView inode, ReadOption option) {
    return getChildren(inode.getId(), option);
  }

  /**
   * @param inode an inode directory
   * @return the result of {@link #getChildren(InodeDirectoryView, ReadOption)} with default option
   */
  default CloseableIterator<? extends Inode> getChildren(InodeDirectoryView inode) {
    return getChildren(inode.getId(), ReadOption.defaults());
  }

  /**
   * Creates an iterator starting from the path, and including its
   * children.
   * @param option the read option
   * @param descendantType the type of descendants to load
   * @param includeBaseInode if the iterator should include the inode from the base path
   * @param lockedPath the locked path to the root inode
   * @return a skippable iterator that supports to skip children during the iteration
   */
  default SkippableInodeIterator getSkippableChildrenIterator(
      ReadOption option, DescendantType descendantType, boolean includeBaseInode,
      LockedInodePath lockedPath) {
    Inode inode;
    try {
      inode = lockedPath.getInode();
    } catch (FileDoesNotExistException e) {
      return new SkippableInodeIterator() {
        @Override
        public void skipChildrenOfTheCurrent() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean hasNext() {
          return false;
        }

        @Override
        public InodeIterationResult next() {
          throw new NoSuchElementException();
        }
      };
    }
    if (descendantType == DescendantType.ALL) {
      return new RecursiveInodeIterator(this, inode, includeBaseInode, option, lockedPath);
    } else if (descendantType == DescendantType.NONE) {
      Preconditions.checkState(includeBaseInode);
      // if descendant type is none, we should only return the parent node
      return new SkippableInodeIterator() {
        InodeIterationResult mFirst = new InodeIterationResult(inode, lockedPath);
        @Override
        public void close() {
        }

        @Override
        public void skipChildrenOfTheCurrent() {
        }

        @Override
        public boolean hasNext() {
          return mFirst != null;
        }

        @Override
        public InodeIterationResult next() {
          if (mFirst == null) {
            throw new NoSuchElementException();
          }
          InodeIterationResult ret = mFirst;
          mFirst = null;
          return ret;
        }
      };
    }

    final CloseableIterator<? extends Inode> iterator = getChildren(inode.getId(), option);
    return new SkippableInodeIterator() {

      LockedInodePath mPreviousPath = null;
      final LockedInodePath mRootPath = lockedPath;
      Inode mFirst = includeBaseInode ? inode : null;

      @Override
      public void skipChildrenOfTheCurrent() {
        // No-op
      }

      @Override
      public boolean hasNext() {
        return mFirst != null || iterator.hasNext();
      }

      @Override
      public InodeIterationResult next() {
        if (mFirst != null) {
          Inode ret = mFirst;
          mFirst = null;
          return new InodeIterationResult(ret, lockedPath);
        }
        if (mPreviousPath != null) {
          mPreviousPath.close();
        }
        Inode inode = iterator.next();

        try {
          mPreviousPath = mRootPath.lockChild(inode, InodeTree.LockPattern.WRITE_EDGE, false);
        } catch (InvalidPathException e) {
          // Should not reach here since the path should be valid
          throw new InternalRuntimeException(e);
        }
        return new InodeIterationResult(inode, mPreviousPath);
      }

      @Override
      public void close() throws IOException {
        iterator.close();
        if (mPreviousPath != null) {
          mPreviousPath.close();
        }
      }
    };
  }

  /**
   * @param inodeId an inode id
   * @param name an inode name
   * @param option the options
   * @return the id of the child of the inode with the given name
   */
  Optional<Long> getChildId(Long inodeId, String name, ReadOption option);

  /**
   * @param inodeId an inode id
   * @param name an inode name
   * @return the result of {@link #getChildId(Long, String, ReadOption)} with default option
   */
  default Optional<Long> getChildId(Long inodeId, String name) {
    return getChildId(inodeId, name, ReadOption.defaults());
  }

  /**
   * @param inode an inode directory
   * @param name an inode name
   * @param option the options
   * @return the id of the child of the inode with the given name
   */
  default Optional<Long> getChildId(InodeDirectoryView inode, String name, ReadOption option) {
    return getChildId(inode.getId(), name, option);
  }

  /**
   * @param inodeId an inode id
   * @param name an inode name
   * @param option the options
   * @return the child of the inode with the given name
   */
  Optional<Inode> getChild(Long inodeId, String name, ReadOption option);

  /**
   * @param inodeId an inode id
   * @param name an inode name
   * @return the result of {@link #getChild(Long, String, ReadOption)} with default option
   */
  default Optional<Inode> getChild(Long inodeId, String name) {
    return getChild(inodeId, name, ReadOption.defaults());
  }

  /**
   * @param inode an inode directory
   * @param name an inode name
   * @param option the options
   * @return the child of the inode with the given name
   */
  default Optional<Inode> getChild(InodeDirectoryView inode, String name, ReadOption option) {
    return getChild(inode.getId(), name, option);
  }

  /**
   * @param inode an inode directory
   * @param name an inode name
   * @return the result of {@link #getChild(InodeDirectoryView, String, ReadOption)} with default
   *    option
   */
  default Optional<Inode> getChild(InodeDirectoryView inode, String name) {
    return getChild(inode.getId(), name, ReadOption.defaults());
  }

  /**
   * @param inode an inode directory
   * @param option the options
   * @return whether the inode has any children
   */
  boolean hasChildren(InodeDirectoryView inode, ReadOption option);

  /**
   * @param inode an inode directory
   * @return the result of {@link #hasChildren(InodeDirectoryView, ReadOption)} with default option
   */
  default boolean hasChildren(InodeDirectoryView inode) {
    return hasChildren(inode, ReadOption.defaults());
  }

  /**
   * @return all edges in the inode store
   */
  @VisibleForTesting
  Set<EdgeEntry> allEdges();

  /**
   * @return all inodes in the inode store
   */
  @VisibleForTesting
  Set<MutableInode<?>> allInodes();
}
