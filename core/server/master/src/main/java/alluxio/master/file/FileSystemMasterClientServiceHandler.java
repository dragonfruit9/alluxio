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

package alluxio.master.file;

import alluxio.AlluxioURI;
import alluxio.RpcUtils;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.FileDoesNotExistException;
import alluxio.grpc.CancelSyncMetadataPRequest;
import alluxio.grpc.CancelSyncMetadataPResponse;
import alluxio.grpc.CheckAccessPRequest;
import alluxio.grpc.CheckAccessPResponse;
import alluxio.grpc.CheckConsistencyPOptions;
import alluxio.grpc.CheckConsistencyPRequest;
import alluxio.grpc.CheckConsistencyPResponse;
import alluxio.grpc.CompleteFilePRequest;
import alluxio.grpc.CompleteFilePResponse;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateDirectoryPRequest;
import alluxio.grpc.CreateDirectoryPResponse;
import alluxio.grpc.CreateFilePRequest;
import alluxio.grpc.CreateFilePResponse;
import alluxio.grpc.DeletePRequest;
import alluxio.grpc.DeletePResponse;
import alluxio.grpc.ExistsPOptions;
import alluxio.grpc.ExistsPRequest;
import alluxio.grpc.ExistsPResponse;
import alluxio.grpc.FileSystemMasterClientServiceGrpc;
import alluxio.grpc.FreePRequest;
import alluxio.grpc.FreePResponse;
import alluxio.grpc.GetFilePathPRequest;
import alluxio.grpc.GetFilePathPResponse;
import alluxio.grpc.GetJobProgressPRequest;
import alluxio.grpc.GetJobProgressPResponse;
import alluxio.grpc.GetMountTablePRequest;
import alluxio.grpc.GetMountTablePResponse;
import alluxio.grpc.GetNewBlockIdForFilePRequest;
import alluxio.grpc.GetNewBlockIdForFilePResponse;
import alluxio.grpc.GetStateLockHoldersPRequest;
import alluxio.grpc.GetStateLockHoldersPResponse;
import alluxio.grpc.GetStatusPOptions;
import alluxio.grpc.GetStatusPRequest;
import alluxio.grpc.GetStatusPResponse;
import alluxio.grpc.GetSyncPathListPRequest;
import alluxio.grpc.GetSyncPathListPResponse;
import alluxio.grpc.GetSyncProgressPRequest;
import alluxio.grpc.GetSyncProgressPResponse;
import alluxio.grpc.GrpcUtils;
import alluxio.grpc.JobProgressReportFormat;
import alluxio.grpc.ListStatusPRequest;
import alluxio.grpc.ListStatusPResponse;
import alluxio.grpc.ListStatusPartialPRequest;
import alluxio.grpc.ListStatusPartialPResponse;
import alluxio.grpc.MountPRequest;
import alluxio.grpc.MountPResponse;
import alluxio.grpc.NeedsSyncRequest;
import alluxio.grpc.NeedsSyncResponse;
import alluxio.grpc.RenamePRequest;
import alluxio.grpc.RenamePResponse;
import alluxio.grpc.ReverseResolvePRequest;
import alluxio.grpc.ReverseResolvePResponse;
import alluxio.grpc.ScheduleAsyncPersistencePRequest;
import alluxio.grpc.ScheduleAsyncPersistencePResponse;
import alluxio.grpc.SetAclPRequest;
import alluxio.grpc.SetAclPResponse;
import alluxio.grpc.SetAttributePRequest;
import alluxio.grpc.SetAttributePResponse;
import alluxio.grpc.StartSyncPRequest;
import alluxio.grpc.StartSyncPResponse;
import alluxio.grpc.StopJobPRequest;
import alluxio.grpc.StopJobPResponse;
import alluxio.grpc.StopSyncPRequest;
import alluxio.grpc.StopSyncPResponse;
import alluxio.grpc.SubmitJobPRequest;
import alluxio.grpc.SubmitJobPResponse;
import alluxio.grpc.SyncMetadataAsyncPResponse;
import alluxio.grpc.SyncMetadataPRequest;
import alluxio.grpc.SyncMetadataPResponse;
import alluxio.grpc.UnmountPRequest;
import alluxio.grpc.UnmountPResponse;
import alluxio.grpc.UpdateMountPRequest;
import alluxio.grpc.UpdateMountPResponse;
import alluxio.grpc.UpdateUfsModePRequest;
import alluxio.grpc.UpdateUfsModePResponse;
import alluxio.job.JobDescription;
import alluxio.job.JobRequest;
import alluxio.job.util.SerializationUtils;
import alluxio.master.file.contexts.CheckAccessContext;
import alluxio.master.file.contexts.CheckConsistencyContext;
import alluxio.master.file.contexts.CompleteFileContext;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.file.contexts.CreateFileContext;
import alluxio.master.file.contexts.DeleteContext;
import alluxio.master.file.contexts.ExistsContext;
import alluxio.master.file.contexts.FreeContext;
import alluxio.master.file.contexts.GetStatusContext;
import alluxio.master.file.contexts.GrpcCallTracker;
import alluxio.master.file.contexts.ListStatusContext;
import alluxio.master.file.contexts.MountContext;
import alluxio.master.file.contexts.RenameContext;
import alluxio.master.file.contexts.ScheduleAsyncPersistenceContext;
import alluxio.master.file.contexts.SetAclContext;
import alluxio.master.file.contexts.SetAttributeContext;
import alluxio.master.file.contexts.SyncMetadataContext;
import alluxio.master.job.JobFactoryProducer;
import alluxio.master.scheduler.Scheduler;
import alluxio.recorder.Recorder;
import alluxio.scheduler.job.Job;
import alluxio.underfs.UfsMode;
import alluxio.util.io.PathUtils;
import alluxio.wire.MountPointInfo;
import alluxio.wire.SyncPointInfo;

import com.google.common.base.Preconditions;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class is a gRPC handler for file system master RPCs invoked by an Alluxio client.
 */
public final class FileSystemMasterClientServiceHandler
    extends FileSystemMasterClientServiceGrpc.FileSystemMasterClientServiceImplBase {
  private static final Logger LOG =
      LoggerFactory.getLogger(FileSystemMasterClientServiceHandler.class);
  private final FileSystemMaster mFileSystemMaster;
  private final Scheduler mScheduler;

  /**
   * Creates a new instance of {@link FileSystemMasterClientServiceHandler}.
   *
   * @param fileSystemMaster the {@link FileSystemMaster} the handler uses internally
   * @param scheduler the {@link Scheduler}
   */
  public FileSystemMasterClientServiceHandler(FileSystemMaster fileSystemMaster,
      Scheduler scheduler) {
    Preconditions.checkNotNull(fileSystemMaster, "fileSystemMaster");
    mFileSystemMaster = fileSystemMaster;
    mScheduler = Preconditions.checkNotNull(scheduler, "scheduler");
  }

  @Override
  public void checkAccess(CheckAccessPRequest request,
      StreamObserver<CheckAccessPResponse> responseObserver) {
    RpcUtils.call(LOG,
        () -> {
          AlluxioURI pathUri = getAlluxioURI(request.getPath());
          mFileSystemMaster.checkAccess(pathUri,
              CheckAccessContext.create(request.getOptions().toBuilder()));
          return CheckAccessPResponse.getDefaultInstance();
        }, "CheckAccess", "request=%s", responseObserver, request);
  }

  @Override
  public void checkConsistency(CheckConsistencyPRequest request,
      StreamObserver<CheckConsistencyPResponse> responseObserver) {
    CheckConsistencyPOptions options = request.getOptions();
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      List<AlluxioURI> inconsistentUris = mFileSystemMaster.checkConsistency(pathUri,
          CheckConsistencyContext.create(options.toBuilder()));
      List<String> uris = new ArrayList<>(inconsistentUris.size());
      for (AlluxioURI uri : inconsistentUris) {
        uris.add(uri.getPath());
      }
      return CheckConsistencyPResponse.newBuilder().addAllInconsistentPaths(uris).build();
    }, "CheckConsistency", "request=%s", responseObserver, request);
  }

  @Override
  public void exists(ExistsPRequest request,
          StreamObserver<ExistsPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      boolean exists = mFileSystemMaster.exists(pathUri,
          ExistsContext.create(request.getOptions().toBuilder()));
      return ExistsPResponse.newBuilder().setExists(exists).build();
    }, "CheckExistence", "request=%s", responseObserver, request);
  }

  @Override
  public void completeFile(CompleteFilePRequest request,
      StreamObserver<CompleteFilePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      mFileSystemMaster.completeFile(pathUri,
          CompleteFileContext.create(request.getOptions().toBuilder()));
      return CompleteFilePResponse.newBuilder().build();
    }, "CompleteFile", "request=%s", responseObserver, request);
  }

  private void checkBucketPathExists(String path)
      throws AlluxioException, IOException {

    String bucketPath = PathUtils.getFirstLevelDirectory(path);
    boolean exists = mFileSystemMaster.exists(getAlluxioURI(bucketPath),
        ExistsContext.create(ExistsPOptions.getDefaultInstance().toBuilder()));
    if (!exists) {
      throw new FileDoesNotExistException(
          ExceptionMessage.BUCKET_DOES_NOT_EXIST.getMessage(bucketPath));
    }
  }

  @Override
  public void createDirectory(CreateDirectoryPRequest request,
      StreamObserver<CreateDirectoryPResponse> responseObserver) {
    CreateDirectoryPOptions options = request.getOptions();
    RpcUtils.call(LOG, () -> {
      if (request.getOptions().getCheckS3BucketPath()) {
        checkBucketPathExists(request.getPath());
      }
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      mFileSystemMaster.createDirectory(pathUri, CreateDirectoryContext.create(options.toBuilder())
          .withTracker(new GrpcCallTracker(responseObserver)));
      return CreateDirectoryPResponse.newBuilder().build();
    }, "CreateDirectory", "request=%s", responseObserver, request);
  }

  @Override
  public void createFile(CreateFilePRequest request,
      StreamObserver<CreateFilePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      if (request.getOptions().getCheckS3BucketPath()) {
        checkBucketPathExists(request.getPath());
      }
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      return CreateFilePResponse.newBuilder()
          .setFileInfo(GrpcUtils.toProto(mFileSystemMaster.createFile(pathUri,
              CreateFileContext.create(request.getOptions().toBuilder())
                  .withTracker(new GrpcCallTracker(responseObserver)))))
          .build();
    }, "CreateFile", "request=%s", responseObserver, request);
  }

  @Override
  public void free(FreePRequest request, StreamObserver<FreePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      mFileSystemMaster.free(pathUri, FreeContext.create(request.getOptions().toBuilder()));
      return FreePResponse.newBuilder().build();
    }, "Free", "request=%s", responseObserver, request);
  }

  @Override
  public void getNewBlockIdForFile(GetNewBlockIdForFilePRequest request,
      StreamObserver<GetNewBlockIdForFilePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      return GetNewBlockIdForFilePResponse.newBuilder()
          .setId(mFileSystemMaster.getNewBlockIdForFile(pathUri)).build();
    }, "GetNewBlockIdForFile", "request=%s", responseObserver, request);
  }

  @Override
  public void getFilePath(GetFilePathPRequest request,
      StreamObserver<GetFilePathPResponse> responseObserver) {
    long fileId = request.getFileId();
    RpcUtils.call(LOG,
        () -> GetFilePathPResponse.newBuilder()
            .setPath(mFileSystemMaster.getPath(fileId).toString()).build(),
        "GetFilePath", true, "request=%s", responseObserver, request);
  }

  @Override
  public void getStatus(GetStatusPRequest request,
      StreamObserver<GetStatusPResponse> responseObserver) {
    GetStatusPOptions options = request.getOptions();
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      return GetStatusPResponse.newBuilder()
          .setFileInfo(GrpcUtils.toProto(mFileSystemMaster.getFileInfo(pathUri, GetStatusContext
              .create(options.toBuilder()).withTracker(new GrpcCallTracker(responseObserver)))))
          .build();
    }, "GetStatus", true, "request=%s", responseObserver, request);
  }

  @Override
  public void listStatus(ListStatusPRequest request,
      StreamObserver<ListStatusPResponse> responseObserver) {
    final int listStatusBatchSize =
        Configuration.getInt(PropertyKey.MASTER_FILE_SYSTEM_LISTSTATUS_RESULTS_PER_MESSAGE);

    // Result streamer for listStatus.
    ListStatusResultStream resultStream =
        new ListStatusResultStream(listStatusBatchSize, responseObserver);

    try {
      RpcUtils.callAndReturn(LOG, () -> {
        AlluxioURI pathUri = getAlluxioURI(request.getPath());
        mFileSystemMaster.listStatus(pathUri,
            ListStatusContext.create(request.getOptions().toBuilder())
                .withTracker(new GrpcCallTracker(responseObserver)),
            resultStream);
        // Return just something.
        return null;
      }, "ListStatus", false, "request=%s", request);
    } catch (Exception e) {
      resultStream.fail(e);
    } finally {
      resultStream.complete();
    }
  }

  @Override
  public void listStatusPartial(ListStatusPartialPRequest request,
                                StreamObserver<ListStatusPartialPResponse> responseObserver) {
    ListStatusContext context = ListStatusContext.create(request.getOptions().toBuilder());
    ListStatusPartialResultStream resultStream =
        new ListStatusPartialResultStream(responseObserver, context);
    try {
      RpcUtils.callAndReturn(LOG, () -> {
        AlluxioURI pathUri = getAlluxioURI(request.getPath());
        mFileSystemMaster.listStatus(pathUri,
            context.withTracker(new GrpcCallTracker(responseObserver)),
            resultStream);
        return null;
      }, "ListStatus", false, "request=%s", request);
    } catch (Exception e) {
      resultStream.onError(e);
    } finally {
      resultStream.complete();
    }
  }

  @Override
  public void mount(MountPRequest request, StreamObserver<MountPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      MountContext mountContext = MountContext.create(request.getOptions().toBuilder())
          .withTracker(new GrpcCallTracker(responseObserver));
      // the mount execution process is recorded so that
      // when an exception occurs during mounting, the user can get detailed debugging messages
      try {
        mFileSystemMaster.mount(new AlluxioURI(request.getAlluxioPath()),
            new AlluxioURI(request.getUfsPath()), mountContext);
      } catch (Exception e) {
        Recorder recorder = mountContext.getRecorder();
        recorder.record(e.getMessage());
        // put the messages in an exception and let it carry over to the user
        throw new AlluxioException(String.join("\n", recorder.takeRecords()), e);
      }
      return MountPResponse.newBuilder().build();
    }, "Mount", "request=%s", responseObserver, request);
  }

  @Override
  public void updateMount(UpdateMountPRequest request,
      StreamObserver<UpdateMountPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      mFileSystemMaster.updateMount(new AlluxioURI(request.getAlluxioPath()),
          MountContext.create(request.getOptions().toBuilder())
              .withTracker(new GrpcCallTracker(responseObserver)));
      return UpdateMountPResponse.newBuilder().build();
    }, "UpdateMount", "request=%s", responseObserver, request);
  }

  @Override
  public void getMountTable(GetMountTablePRequest request,
      StreamObserver<GetMountTablePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      // Set the checkUfs default to true to include ufs usage info, etc.,
      // which requires talking to UFS and comes at a cost.
      boolean checkUfs = request.hasCheckUfs() ? request.getCheckUfs() : true;
      Map<String, MountPointInfo> mountTableWire = mFileSystemMaster.getMountPointInfoSummary(
          checkUfs);
      Map<String, alluxio.grpc.MountPointInfo> mountTableProto = new HashMap<>();
      for (Map.Entry<String, MountPointInfo> entry : mountTableWire.entrySet()) {
        mountTableProto.put(entry.getKey(), GrpcUtils.toProto(entry.getValue()));
      }
      return GetMountTablePResponse.newBuilder().putAllMountPoints(mountTableProto).build();
    }, "GetMountTable", "request=%s", responseObserver, request);
  }

  @Override
  public void getSyncPathList(GetSyncPathListPRequest request,
      StreamObserver<GetSyncPathListPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      List<SyncPointInfo> pathList = mFileSystemMaster.getSyncPathList();
      List<alluxio.grpc.SyncPointInfo> syncPointInfoList =
          pathList.stream().map(SyncPointInfo::toProto).collect(Collectors.toList());
      return GetSyncPathListPResponse.newBuilder().addAllSyncPaths(syncPointInfoList).build();
    }, "getSyncPathList", "request=%s", responseObserver, request);
  }

  @Override
  public void remove(DeletePRequest request, StreamObserver<DeletePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      mFileSystemMaster.delete(pathUri, DeleteContext.create(request.getOptions().toBuilder())
          .withTracker(new GrpcCallTracker(responseObserver)));
      return DeletePResponse.newBuilder().build();
    }, "Remove", "request=%s", responseObserver, request);
  }

  @Override
  public void rename(RenamePRequest request, StreamObserver<RenamePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI srcPathUri = getAlluxioURI(request.getPath());
      AlluxioURI dstPathUri = getAlluxioURI(request.getDstPath());
      mFileSystemMaster.rename(srcPathUri, dstPathUri,
          RenameContext.create(request.getOptions().toBuilder())
              .withTracker(new GrpcCallTracker(responseObserver)));
      return RenamePResponse.newBuilder().build();
    }, "Rename", "request=%s", responseObserver, request);
  }

  @Override
  public void reverseResolve(ReverseResolvePRequest request,
      StreamObserver<ReverseResolvePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI ufsUri = new AlluxioURI(request.getUfsUri());
      AlluxioURI alluxioPath = mFileSystemMaster.reverseResolve(ufsUri);
      return ReverseResolvePResponse.newBuilder().setAlluxioPath(alluxioPath.getPath()).build();
    }, "ReverseResolve", "request=%s", responseObserver, request);
  }

  @Override
  public void scheduleAsyncPersistence(ScheduleAsyncPersistencePRequest request,
      StreamObserver<ScheduleAsyncPersistencePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      mFileSystemMaster.scheduleAsyncPersistence(new AlluxioURI(request.getPath()),
          ScheduleAsyncPersistenceContext.create(request.getOptions().toBuilder()));
      return ScheduleAsyncPersistencePResponse.newBuilder().build();
    }, "ScheduleAsyncPersist", "request=%s", responseObserver, request);
  }

  @Override
  public void setAttribute(SetAttributePRequest request,
      StreamObserver<SetAttributePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      mFileSystemMaster.setAttribute(pathUri,
          SetAttributeContext.create(request.getOptions().toBuilder())
              .withTracker(new GrpcCallTracker(responseObserver)));
      return SetAttributePResponse.newBuilder().build();
    }, "SetAttribute", "request=%s", responseObserver, request);
  }

  @Override
  public void startSync(StartSyncPRequest request,
      StreamObserver<StartSyncPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      mFileSystemMaster.startSync(new AlluxioURI(request.getPath()));
      return StartSyncPResponse.newBuilder().build();
    }, "startSync", "request=%s", responseObserver, request);
  }

  @Override
  public void stopSync(StopSyncPRequest request,
      StreamObserver<StopSyncPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      mFileSystemMaster.stopSync(new AlluxioURI(request.getPath()));
      return StopSyncPResponse.newBuilder().build();
    }, "stopSync", "request=%s", responseObserver, request);
  }

  @Override
  public void unmount(UnmountPRequest request, StreamObserver<UnmountPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      mFileSystemMaster.unmount(new AlluxioURI(request.getAlluxioPath()));
      return UnmountPResponse.newBuilder().build();
    }, "Unmount", "request=%s", responseObserver, request);
  }

  @Override
  public void updateUfsMode(UpdateUfsModePRequest request,
      StreamObserver<UpdateUfsModePResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      UfsMode ufsMode;
      switch (request.getOptions().getUfsMode()) {
        case NO_ACCESS:
          ufsMode = UfsMode.NO_ACCESS;
          break;
        case READ_ONLY:
          ufsMode = UfsMode.READ_ONLY;
          break;
        default:
          ufsMode = UfsMode.READ_WRITE;
          break;
      }
      mFileSystemMaster.updateUfsMode(new AlluxioURI(request.getUfsPath()), ufsMode);
      return UpdateUfsModePResponse.newBuilder().build();
    }, "UpdateUfsMode", "request=%s", responseObserver, request);
  }

  @Override
  public void setAcl(SetAclPRequest request, StreamObserver<SetAclPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      AlluxioURI pathUri = getAlluxioURI(request.getPath());
      mFileSystemMaster.setAcl(pathUri, request.getAction(),
          request.getEntriesList().stream().map(GrpcUtils::fromProto).collect(Collectors.toList()),
          SetAclContext.create(request.getOptions().toBuilder())
              .withTracker(new GrpcCallTracker(responseObserver)));
      return SetAclPResponse.newBuilder().build();
    }, "setAcl", "request=%s", responseObserver, request);
  }

  @Override
  public void getStateLockHolders(GetStateLockHoldersPRequest request,
      StreamObserver<GetStateLockHoldersPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      final Collection<String> holders = mFileSystemMaster.getStateLockSharedWaitersAndHolders();
      return GetStateLockHoldersPResponse.newBuilder().addAllThreads(holders).build();
    }, "getStateLockHolders", "request=%s", responseObserver, request);
  }

  @Override
  public void needsSync(NeedsSyncRequest request,
                        StreamObserver<NeedsSyncResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      mFileSystemMaster.needsSync(new AlluxioURI(request.getPath()));
      return NeedsSyncResponse.getDefaultInstance();
    }, "NeedsSync", true, "request=%s", responseObserver, request);
  }

  @Override
  public void submitJob(SubmitJobPRequest request,
      StreamObserver<SubmitJobPResponse> responseObserver) {

    RpcUtils.call(LOG, () -> {
      JobRequest jobRequest;
      try {
        jobRequest = (JobRequest) SerializationUtils.deserialize(request
            .getRequestBody()
            .toByteArray());
      } catch (Exception e) {
        throw new IllegalArgumentException("fail to parse job request", e);
      }
      Job<?> job = JobFactoryProducer.create(jobRequest, mFileSystemMaster).create();
      boolean submitted = mScheduler.submitJob(job);
      SubmitJobPResponse.Builder builder = SubmitJobPResponse.newBuilder();
      if (submitted) {
        builder.setJobId(job.getJobId());
      }
      return builder.build();
    }, "submitJob", "request=%s", responseObserver, request);
  }

  @Override
  public void stopJob(StopJobPRequest request,
      StreamObserver<StopJobPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      boolean stopped = mScheduler.stopJob(JobDescription.from(request.getJobDescription()));
      return alluxio.grpc.StopJobPResponse.newBuilder()
          .setJobStopped(stopped)
          .build();
    }, "stopJob", "request=%s", responseObserver, request);
  }

  @Override
  public void getJobProgress(GetJobProgressPRequest request,
      StreamObserver<GetJobProgressPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      JobProgressReportFormat format = JobProgressReportFormat.TEXT;
      if (request.hasOptions() && request.getOptions().hasFormat()) {
        format = request.getOptions().getFormat();
      }
      boolean verbose = false;
      if (request.hasOptions() && request.getOptions().hasVerbose()) {
        verbose = request.getOptions().getVerbose();
      }
      return GetJobProgressPResponse.newBuilder()
          .setProgressReport(mScheduler.getJobProgress(
              JobDescription.from(request.getJobDescription()), format, verbose))
          .build();
    }, "getJobProgress", "request=%s", responseObserver, request);
  }

  /**
   * Helper to return {@link AlluxioURI} from transport URI.
   *
   * @param uriStr transport uri string
   * @return a {@link AlluxioURI} instance
   */
  private AlluxioURI getAlluxioURI(String uriStr) {
    return new AlluxioURI(uriStr);
  }

  @Override
  public void syncMetadata(
      SyncMetadataPRequest request,
      StreamObserver<SyncMetadataPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      return mFileSystemMaster.syncMetadata(
          new AlluxioURI(request.getPath()),
          SyncMetadataContext.create(request.getOptions().toBuilder()));
    }, "syncMetadata", "request=%s", responseObserver, request);
  }

  @Override
  public void syncMetadataAsync(
      SyncMetadataPRequest request,
      StreamObserver<SyncMetadataAsyncPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      return mFileSystemMaster.syncMetadataAsync(
          new AlluxioURI(request.getPath()),
          SyncMetadataContext.create(request.getOptions().toBuilder()));
    }, "syncMetadataAsync", "request=%s", responseObserver, request);
  }

  @Override
  public void getSyncProgress(
      GetSyncProgressPRequest request,
      StreamObserver<GetSyncProgressPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      return mFileSystemMaster.getSyncProgress(
          request.getTaskGroupId());
    }, "syncMetadataAsync", "request=%s", responseObserver, request);
  }

  @Override
  public void cancelSyncMetadata(
      CancelSyncMetadataPRequest request,
      StreamObserver<CancelSyncMetadataPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      return mFileSystemMaster.cancelSyncMetadata(
          request.getTaskGroupId());
    }, "cancelSyncMetadata", "request=%s", responseObserver, request);
  }
}
