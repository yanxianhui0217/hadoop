/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.FailApplicationAttemptRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FailApplicationAttemptResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.ReservationUpdateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.SignalContainerRequest;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LogAggregationContext;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.api.records.SignalContainerCommand;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.DrainDispatcher;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.resourcemanager.amlauncher.AMLauncherEvent;
import org.apache.hadoop.yarn.server.resourcemanager.amlauncher.ApplicationMasterLauncher;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.NullRMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeStartedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.AbstractYarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplication;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApplicationAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNode;
import org.apache.hadoop.yarn.server.resourcemanager.security.ClientToAMTokenSecretManagerInRM;
import org.apache.hadoop.yarn.server.resourcemanager.security.NMTokenSecretManagerInRM;
import org.apache.hadoop.yarn.server.resourcemanager.security.RMContainerTokenSecretManager;


import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.util.YarnVersionInfo;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.junit.Assert;

@SuppressWarnings("unchecked")
public class MockRM extends ResourceManager {

  static final Logger LOG = Logger.getLogger(MockRM.class);
  static final String ENABLE_WEBAPP = "mockrm.webapp.enabled";
  private static final int SECOND = 1000;
  private static final int TIMEOUT_MS_FOR_ATTEMPT = 40 * SECOND;
  private static final int TIMEOUT_MS_FOR_APP_REMOVED = 40 * SECOND;
  private static final int TIMEOUT_MS_FOR_CONTAINER_AND_NODE = 10 * SECOND;
  private static final int WAIT_MS_PER_LOOP = 10;

  private final boolean useNullRMNodeLabelsManager;

  public MockRM() {
    this(new YarnConfiguration());
  }

  public MockRM(Configuration conf) {
    this(conf, null);    
  }
  
  public MockRM(Configuration conf, RMStateStore store) {
    this(conf, store, true);
  }
  
  public MockRM(Configuration conf, RMStateStore store,
      boolean useNullRMNodeLabelsManager) {
    super();
    this.useNullRMNodeLabelsManager = useNullRMNodeLabelsManager;
    init(conf instanceof YarnConfiguration ? conf : new YarnConfiguration(conf));
    if(store != null) {
      setRMStateStore(store);
    }
    Logger rootLogger = LogManager.getRootLogger();
    rootLogger.setLevel(Level.DEBUG);
  }
  
  @Override
  protected RMNodeLabelsManager createNodeLabelManager()
      throws InstantiationException, IllegalAccessException {
    if (useNullRMNodeLabelsManager) {
      RMNodeLabelsManager mgr = new NullRMNodeLabelsManager();
      mgr.init(getConfig());
      return mgr;
    } else {
      return super.createNodeLabelManager();
    }
  }

  @Override
  protected Dispatcher createDispatcher() {
    return new DrainDispatcher();
  }

  public void drainEvents() {
    Dispatcher rmDispatcher = getRmDispatcher();
    if (rmDispatcher instanceof DrainDispatcher) {
      ((DrainDispatcher) rmDispatcher).await();
    } else {
      throw new UnsupportedOperationException("Not a Drain Dispatcher!");
    }
  }

  /**
   * Wait until an application has reached a specified state.
   * The timeout is 80 seconds.
   * @param appId the id of an application
   * @param finalState the application state waited
   * @throws InterruptedException
   *         if interrupted while waiting for the state transition
   */
  public void waitForState(ApplicationId appId, RMAppState finalState)
      throws InterruptedException {
    RMApp app = getRMContext().getRMApps().get(appId);
    Assert.assertNotNull("app shouldn't be null", app);
    final int timeoutMsecs = 80 * SECOND;
    int timeWaiting = 0;
    while (!finalState.equals(app.getState())) {
      if (timeWaiting >= timeoutMsecs) {
        break;
      }

      LOG.info("App : " + appId + " State is : " + app.getState() +
              " Waiting for state : " + finalState);
      Thread.sleep(WAIT_MS_PER_LOOP);
      timeWaiting += WAIT_MS_PER_LOOP;
    }

    LOG.info("App State is : " + app.getState());
    Assert.assertEquals("App State is not correct (timeout).", finalState,
      app.getState());
  }

  /**
   * Wait until an attempt has reached a specified state.
   * The timeout is 40 seconds.
   * @param attemptId the id of an attempt
   * @param finalState the attempt state waited
   * @throws InterruptedException
   *         if interrupted while waiting for the state transition
   */
  public void waitForState(ApplicationAttemptId attemptId,
      RMAppAttemptState finalState) throws InterruptedException {
    waitForState(attemptId, finalState, TIMEOUT_MS_FOR_ATTEMPT);
  }

  /**
   * Wait until an attempt has reached a specified state.
   * The timeout can be specified by the parameter.
   * @param attemptId the id of an attempt
   * @param finalState the attempt state waited
   * @param timeoutMsecs the length of timeout in milliseconds
   * @throws InterruptedException
   *         if interrupted while waiting for the state transition
   */
  public void waitForState(ApplicationAttemptId attemptId,
      RMAppAttemptState finalState, int timeoutMsecs)
      throws InterruptedException {
    RMApp app = getRMContext().getRMApps().get(attemptId.getApplicationId());
    Assert.assertNotNull("app shouldn't be null", app);
    RMAppAttempt attempt = app.getRMAppAttempt(attemptId);
    MockRM.waitForState(attempt, finalState, timeoutMsecs);
  }

  /**
   * Wait until an attempt has reached a specified state.
   * The timeout is 40 seconds.
   * @param attempt an attempt
   * @param finalState the attempt state waited
   * @throws InterruptedException
   *         if interrupted while waiting for the state transition
   */
  public static void waitForState(RMAppAttempt attempt,
      RMAppAttemptState finalState) throws InterruptedException {
    waitForState(attempt, finalState, TIMEOUT_MS_FOR_ATTEMPT);
  }

  /**
   * Wait until an attempt has reached a specified state.
   * The timeout can be specified by the parameter.
   * @param attempt an attempt
   * @param finalState the attempt state waited
   * @param timeoutMsecs the length of timeout in milliseconds
   * @throws InterruptedException
   *         if interrupted while waiting for the state transition
   */
  public static void waitForState(RMAppAttempt attempt,
      RMAppAttemptState finalState, int timeoutMsecs)
      throws InterruptedException {
    int timeWaiting = 0;
    while (!finalState.equals(attempt.getAppAttemptState())) {
      if (timeWaiting >= timeoutMsecs) {
        break;
      }

      LOG.info("AppAttempt : " + attempt.getAppAttemptId() + " State is : " +
          attempt.getAppAttemptState() + " Waiting for state : " + finalState);
      Thread.sleep(WAIT_MS_PER_LOOP);
      timeWaiting += WAIT_MS_PER_LOOP;
    }

    LOG.info("Attempt State is : " + attempt.getAppAttemptState());
    Assert.assertEquals("Attempt state is not correct (timeout).", finalState,
      attempt.getState());
  }

  public void waitForContainerToComplete(RMAppAttempt attempt,
      NMContainerStatus completedContainer) throws InterruptedException {
    while (true) {
      List<ContainerStatus> containers = attempt.getJustFinishedContainers();
      System.out.println("Received completed containers " + containers);
      for (ContainerStatus container : containers) {
        if (container.getContainerId().equals(
          completedContainer.getContainerId())) {
          return;
        }
      }
      Thread.sleep(WAIT_MS_PER_LOOP);
    }
  }

  public MockAM waitForNewAMToLaunchAndRegister(ApplicationId appId, int attemptSize,
      MockNM nm) throws Exception {
    RMApp app = getRMContext().getRMApps().get(appId);
    Assert.assertNotNull(app);
    while (app.getAppAttempts().size() != attemptSize) {
      System.out.println("Application " + appId
          + " is waiting for AM to restart. Current has "
          + app.getAppAttempts().size() + " attempts.");
      Thread.sleep(WAIT_MS_PER_LOOP);
    }
    return launchAndRegisterAM(app, this, nm);
  }

  /**
   * Wait until a container has reached a specified state.
   * The timeout is 10 seconds.
   * @param nm A mock nodemanager
   * @param containerId the id of a container
   * @param containerState the container state waited
   * @return if reach the state before timeout; false otherwise.
   * @throws Exception
   *         if interrupted while waiting for the state transition
   *         or an unexpected error while MockNM is hearbeating.
   */
  public boolean waitForState(MockNM nm, ContainerId containerId,
      RMContainerState containerState) throws Exception {
    return waitForState(nm, containerId, containerState,
      TIMEOUT_MS_FOR_CONTAINER_AND_NODE);
  }

  /**
   * Wait until a container has reached a specified state.
   * The timeout is specified by the parameter.
   * @param nm A mock nodemanager
   * @param containerId the id of a container
   * @param containerState the container state waited
   * @param timeoutMsecs the length of timeout in milliseconds
   * @return if reach the state before timeout; false otherwise.
   * @throws Exception
   *         if interrupted while waiting for the state transition
   *         or an unexpected error while MockNM is hearbeating.
   */
  public boolean waitForState(MockNM nm, ContainerId containerId,
      RMContainerState containerState, int timeoutMsecs) throws Exception {
    return waitForState(Arrays.asList(nm), containerId, containerState,
      timeoutMsecs);
  }

  /**
   * Wait until a container has reached a specified state.
   * The timeout is 10 seconds.
   * @param nms array of mock nodemanagers
   * @param containerId the id of a container
   * @param containerState the container state waited
   * @return if reach the state before timeout; false otherwise.
   * @throws Exception
   *         if interrupted while waiting for the state transition
   *         or an unexpected error while MockNM is hearbeating.
   */
  public boolean waitForState(Collection<MockNM> nms, ContainerId containerId,
      RMContainerState containerState) throws Exception {
    return waitForState(nms, containerId, containerState,
      TIMEOUT_MS_FOR_CONTAINER_AND_NODE);
  }

  /**
   * Wait until a container has reached a specified state.
   * The timeout is specified by the parameter.
   * @param nms array of mock nodemanagers
   * @param containerId the id of a container
   * @param containerState the container state waited
   * @param timeoutMsecs the length of timeout in milliseconds
   * @return if reach the state before timeout; false otherwise.
   * @throws Exception
   *         if interrupted while waiting for the state transition
   *         or an unexpected error while MockNM is hearbeating.
   */
  public boolean waitForState(Collection<MockNM> nms, ContainerId containerId,
      RMContainerState containerState, int timeoutMsecs) throws Exception {
    RMContainer container = getResourceScheduler().getRMContainer(containerId);
    int timeWaiting = 0;
    while (container == null) {
      if (timeWaiting >= timeoutMsecs) {
        return false;
      }

      for (MockNM nm : nms) {
        nm.nodeHeartbeat(true);
      }
      container = getResourceScheduler().getRMContainer(containerId);
      System.out.println("Waiting for container " + containerId + " to be "
          + containerState + ", container is null right now.");
      Thread.sleep(WAIT_MS_PER_LOOP);
      timeWaiting += WAIT_MS_PER_LOOP;
    }

    while (!containerState.equals(container.getState())) {
      if (timeWaiting >= timeoutMsecs) {
        return false;
      }

      System.out.println("Container : " + containerId + " State is : "
          + container.getState() + " Waiting for state : " + containerState);
      for (MockNM nm : nms) {
        nm.nodeHeartbeat(true);
      }
      Thread.sleep(WAIT_MS_PER_LOOP);
      timeWaiting += WAIT_MS_PER_LOOP;
    }

    System.out.println("Container State is : " + container.getState());
    return true;
  }

  // get new application id
  public GetNewApplicationResponse getNewAppId() throws Exception {
    ApplicationClientProtocol client = getClientRMService();
    return client.getNewApplication(Records
        .newRecord(GetNewApplicationRequest.class));
  }

  public RMApp submitApp(int masterMemory) throws Exception {
    return submitApp(masterMemory, false);
  }

  public RMApp submitApp(int masterMemory, Priority priority) throws Exception {
    Resource resource = Resource.newInstance(masterMemory, 0);
    return submitApp(resource, "", UserGroupInformation.getCurrentUser()
        .getShortUserName(), null, false, null,
        super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
            YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null, null, true,
        false, false, null, 0, null, true, priority);
  }

  public RMApp submitApp(int masterMemory, boolean unmanaged)
      throws Exception {
    return submitApp(masterMemory, "", UserGroupInformation.getCurrentUser()
        .getShortUserName(), unmanaged);
  }

  // client
  public RMApp submitApp(int masterMemory, String name, String user) throws Exception {
    return submitApp(masterMemory, name, user, false);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      boolean unmanaged)
      throws Exception {
    return submitApp(masterMemory, name, user, null, unmanaged, null,
        super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
            YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls) throws Exception {
    return submitApp(masterMemory, name, user, acls, false, null,
      super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
        YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null);
  }  

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, String queue) throws Exception {
    return submitApp(masterMemory, name, user, acls, false, queue,
      super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
        YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, String queue, String amLabel)
      throws Exception {
    Resource resource = Records.newRecord(Resource.class);
    resource.setMemorySize(masterMemory);
    Priority priority = Priority.newInstance(0);
    return submitApp(resource, name, user, acls, false, queue,
      super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
      YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null, null, true, false,
      false, null, 0, null, true, priority, amLabel);
  }

  public RMApp submitApp(Resource resource, String name, String user,
      Map<ApplicationAccessType, String> acls, String queue) throws Exception {
    return submitApp(resource, name, user, acls, false, queue,
        super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
          YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null, null,
          true, false, false, null, 0, null, true, null);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, String queue, 
      boolean waitForAccepted) throws Exception {
    return submitApp(masterMemory, name, user, acls, false, queue,
      super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
        YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null, null,
        waitForAccepted);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts) throws Exception {
    return submitApp(masterMemory, name, user, acls, unmanaged, queue,
      maxAppAttempts, ts, null);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts, String appType) throws Exception {
    return submitApp(masterMemory, name, user, acls, unmanaged, queue,
      maxAppAttempts, ts, appType, true);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts, String appType,
      boolean waitForAccepted)
      throws Exception {
    return submitApp(masterMemory, name, user, acls, unmanaged, queue,
      maxAppAttempts, ts, appType, waitForAccepted, false);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts, String appType,
      boolean waitForAccepted, boolean keepContainers) throws Exception {
    Resource resource = Records.newRecord(Resource.class);
    resource.setMemorySize(masterMemory);
    return submitApp(resource, name, user, acls, unmanaged, queue,
        maxAppAttempts, ts, appType, waitForAccepted, keepContainers,
        false, null, 0, null, true, Priority.newInstance(0));
  }

  public RMApp submitApp(int masterMemory, long attemptFailuresValidityInterval)
      throws Exception {
    Resource resource = Records.newRecord(Resource.class);
    resource.setMemorySize(masterMemory);
    Priority priority = Priority.newInstance(0);
    return submitApp(resource, "", UserGroupInformation.getCurrentUser()
      .getShortUserName(), null, false, null,
      super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
      YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null, null, true, false,
      false, null, attemptFailuresValidityInterval, null, true, priority);
  }

  public RMApp submitApp(int masterMemory, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts, String appType,
      boolean waitForAccepted, boolean keepContainers, boolean isAppIdProvided,
      ApplicationId applicationId) throws Exception {
    Resource resource = Records.newRecord(Resource.class);
    resource.setMemorySize(masterMemory);
    Priority priority = Priority.newInstance(0);
    return submitApp(resource, name, user, acls, unmanaged, queue,
      maxAppAttempts, ts, appType, waitForAccepted, keepContainers,
      isAppIdProvided, applicationId, 0, null, true, priority);
  }

  public RMApp submitApp(int masterMemory,
      LogAggregationContext logAggregationContext) throws Exception {
    Resource resource = Records.newRecord(Resource.class);
    resource.setMemorySize(masterMemory);
    Priority priority = Priority.newInstance(0);
    return submitApp(resource, "", UserGroupInformation.getCurrentUser()
      .getShortUserName(), null, false, null,
      super.getConfig().getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS,
      YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS), null, null, true, false,
      false, null, 0, logAggregationContext, true, priority);
   }

  public RMApp submitApp(Resource capability, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts, String appType,
      boolean waitForAccepted, boolean keepContainers, boolean isAppIdProvided,
      ApplicationId applicationId, long attemptFailuresValidityInterval,
      LogAggregationContext logAggregationContext,
      boolean cancelTokensWhenComplete, Priority priority) throws Exception {
    return submitApp(capability, name, user, acls, unmanaged, queue,
      maxAppAttempts, ts, appType, waitForAccepted, keepContainers,
      isAppIdProvided, applicationId, attemptFailuresValidityInterval,
      logAggregationContext, cancelTokensWhenComplete, priority, "");
  }

  public RMApp submitApp(Resource capability, String name, String user,
      Map<ApplicationAccessType, String> acls, boolean unmanaged, String queue,
      int maxAppAttempts, Credentials ts, String appType,
      boolean waitForAccepted, boolean keepContainers, boolean isAppIdProvided,
      ApplicationId applicationId, long attemptFailuresValidityInterval,
      LogAggregationContext logAggregationContext,
      boolean cancelTokensWhenComplete, Priority priority, String amLabel)
      throws Exception {
    ApplicationId appId = isAppIdProvided ? applicationId : null;
    ApplicationClientProtocol client = getClientRMService();
    if (! isAppIdProvided) {
      GetNewApplicationResponse resp = client.getNewApplication(Records
          .newRecord(GetNewApplicationRequest.class));
      appId = resp.getApplicationId();
    }
    SubmitApplicationRequest req = Records
        .newRecord(SubmitApplicationRequest.class);
    ApplicationSubmissionContext sub = Records
        .newRecord(ApplicationSubmissionContext.class);
    sub.setKeepContainersAcrossApplicationAttempts(keepContainers);
    sub.setApplicationId(appId);
    sub.setApplicationName(name);
    sub.setMaxAppAttempts(maxAppAttempts);
    if (unmanaged) {
      sub.setUnmanagedAM(true);
    }
    if (queue != null) {
      sub.setQueue(queue);
    }
    if (priority != null) {
      sub.setPriority(priority);
    }
    sub.setApplicationType(appType);
    ContainerLaunchContext clc = Records
        .newRecord(ContainerLaunchContext.class);
    sub.setResource(capability);
    clc.setApplicationACLs(acls);
    if (ts != null && UserGroupInformation.isSecurityEnabled()) {
      DataOutputBuffer dob = new DataOutputBuffer();
      ts.writeTokenStorageToStream(dob);
      ByteBuffer securityTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
      clc.setTokens(securityTokens);
    }
    sub.setAMContainerSpec(clc);
    sub.setAttemptFailuresValidityInterval(attemptFailuresValidityInterval);
    if (logAggregationContext != null) {
      sub.setLogAggregationContext(logAggregationContext);
    }
    sub.setCancelTokensWhenComplete(cancelTokensWhenComplete);
    ResourceRequest amResourceRequest = ResourceRequest.newInstance(
        Priority.newInstance(0), ResourceRequest.ANY, capability, 1);
    if (amLabel != null && !amLabel.isEmpty()) {
      amResourceRequest.setNodeLabelExpression(amLabel.trim());
    }
    sub.setAMContainerResourceRequest(amResourceRequest);
    req.setApplicationSubmissionContext(sub);
    UserGroupInformation fakeUser =
      UserGroupInformation.createUserForTesting(user, new String[] {"someGroup"});
    PrivilegedAction<SubmitApplicationResponse> action =
      new PrivilegedAction<SubmitApplicationResponse>() {
      ApplicationClientProtocol client;
      SubmitApplicationRequest req;
      @Override
      public SubmitApplicationResponse run() {
        try {
          return client.submitApplication(req);
        } catch (YarnException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
        return null;
      }
      PrivilegedAction<SubmitApplicationResponse> setClientReq(
        ApplicationClientProtocol client, SubmitApplicationRequest req) {
        this.client = client;
        this.req = req;
        return this;
      }
    }.setClientReq(client, req);
    fakeUser.doAs(action);
    // make sure app is immediately available after submit
    if (waitForAccepted) {
      waitForState(appId, RMAppState.ACCEPTED);
    }
    RMApp rmApp = getRMContext().getRMApps().get(appId);

    // unmanaged AM won't go to RMAppAttemptState.SCHEDULED.
    if (waitForAccepted && !unmanaged) {
      waitForState(rmApp.getCurrentAppAttempt().getAppAttemptId(),
          RMAppAttemptState.SCHEDULED);
    }

    return rmApp;
  }

  public MockNM registerNode(String nodeIdStr, int memory) throws Exception {
    MockNM nm = new MockNM(nodeIdStr, memory, getResourceTrackerService());
    nm.registerNode();
    drainEvents();
    return nm;
  }

  public MockNM registerNode(String nodeIdStr, int memory, int vCores)
      throws Exception {
    MockNM nm =
        new MockNM(nodeIdStr, memory, vCores, getResourceTrackerService());
    nm.registerNode();
    drainEvents();
    return nm;
  }
  
  public MockNM registerNode(String nodeIdStr, int memory, int vCores,
      List<ApplicationId> runningApplications) throws Exception {
    MockNM nm =
        new MockNM(nodeIdStr, memory, vCores, getResourceTrackerService(),
            YarnVersionInfo.getVersion());
    nm.registerNode(runningApplications);
    drainEvents();
    return nm;
  }

  public void sendNodeStarted(MockNM nm) throws Exception {
    RMNodeImpl node = (RMNodeImpl) getRMContext().getRMNodes().get(
        nm.getNodeId());
    node.handle(new RMNodeStartedEvent(nm.getNodeId(), null, null));
  }
  
  public void sendNodeLost(MockNM nm) throws Exception {
    RMNodeImpl node = (RMNodeImpl) getRMContext().getRMNodes().get(
        nm.getNodeId());
    node.handle(new RMNodeEvent(nm.getNodeId(), RMNodeEventType.EXPIRE));
  }

  /**
   * Wait until a node has reached a specified state.
   * The timeout is 10 seconds.
   * @param nodeId the id of a node
   * @param finalState the node state waited
   * @throws InterruptedException
   *         if interrupted while waiting for the state transition
   */
  public void waitForState(NodeId nodeId, NodeState finalState)
      throws InterruptedException {
    RMNode node = getRMContext().getRMNodes().get(nodeId);
    if (node == null) {
      node = getRMContext().getInactiveRMNodes().get(nodeId);
    }
    Assert.assertNotNull("node shouldn't be null", node);
    int timeWaiting = 0;
    while (!finalState.equals(node.getState())) {
      if (timeWaiting >= TIMEOUT_MS_FOR_CONTAINER_AND_NODE) {
        break;
      }

      System.out.println("Node State is : " + node.getState()
          + " Waiting for state : " + finalState);
      Thread.sleep(WAIT_MS_PER_LOOP);
      timeWaiting += WAIT_MS_PER_LOOP;
    }

    System.out.println("Node " + nodeId + " State is : " + node.getState());
    Assert.assertEquals("Node state is not correct (timedout)", finalState,
        node.getState());
  }

  public void sendNodeEvent(MockNM nm, RMNodeEventType event) throws Exception {
    RMNodeImpl node = (RMNodeImpl)
        getRMContext().getRMNodes().get(nm.getNodeId());
    node.handle(new RMNodeEvent(nm.getNodeId(), event));
  }

  public KillApplicationResponse killApp(ApplicationId appId) throws Exception {
    ApplicationClientProtocol client = getClientRMService();
    KillApplicationRequest req = KillApplicationRequest.newInstance(appId);
    return client.forceKillApplication(req);
  }

  public FailApplicationAttemptResponse failApplicationAttempt(
      ApplicationAttemptId attemptId) throws Exception {
    ApplicationClientProtocol client = getClientRMService();
    FailApplicationAttemptRequest req =
        FailApplicationAttemptRequest.newInstance(attemptId);
    return client.failApplicationAttempt(req);
  }

  /**
   * recommend to use launchAM, or use sendAMLaunched like:
   * 1, wait RMAppAttempt scheduled
   * 2, send node heartbeat
   * 3, sendAMLaunched
   */
  public MockAM sendAMLaunched(ApplicationAttemptId appAttemptId)
      throws Exception {
    MockAM am = new MockAM(getRMContext(), masterService, appAttemptId);
    waitForState(appAttemptId, RMAppAttemptState.ALLOCATED);
    //create and set AMRMToken
    Token<AMRMTokenIdentifier> amrmToken =
        this.rmContext.getAMRMTokenSecretManager().createAndGetAMRMToken(
          appAttemptId);
    ((RMAppAttemptImpl) this.rmContext.getRMApps()
      .get(appAttemptId.getApplicationId()).getRMAppAttempt(appAttemptId))
      .setAMRMToken(amrmToken);
    getRMContext()
        .getDispatcher()
        .getEventHandler()
        .handle(
            new RMAppAttemptEvent(appAttemptId, RMAppAttemptEventType.LAUNCHED));
    return am;
  }

  public void sendAMLaunchFailed(ApplicationAttemptId appAttemptId)
      throws Exception {
    MockAM am = new MockAM(getRMContext(), masterService, appAttemptId);
    waitForState(am.getApplicationAttemptId(), RMAppAttemptState.ALLOCATED);
    getRMContext().getDispatcher().getEventHandler()
        .handle(new RMAppAttemptEvent(appAttemptId,
            RMAppAttemptEventType.LAUNCH_FAILED, "Failed"));
  }

  @Override
  protected ClientRMService createClientRMService() {
    return new ClientRMService(getRMContext(), getResourceScheduler(),
        rmAppManager, applicationACLsManager, queueACLsManager,
        getRMContext().getRMDelegationTokenSecretManager()) {
      @Override
      protected void serviceStart() {
        // override to not start rpc handler
      }

      @Override
      protected void serviceStop() {
        // don't do anything
      }
    };
  }

  @Override
  protected ResourceTrackerService createResourceTrackerService() {

    RMContainerTokenSecretManager containerTokenSecretManager =
        getRMContext().getContainerTokenSecretManager();
    containerTokenSecretManager.rollMasterKey();
    NMTokenSecretManagerInRM nmTokenSecretManager =
        getRMContext().getNMTokenSecretManager();
    nmTokenSecretManager.rollMasterKey();
    return new ResourceTrackerService(getRMContext(), nodesListManager,
        this.nmLivelinessMonitor, containerTokenSecretManager,
        nmTokenSecretManager) {

      @Override
      protected void serviceStart() {
        // override to not start rpc handler
      }

      @Override
      protected void serviceStop() {
        // don't do anything
      }
    };
  }

  @Override
  protected ApplicationMasterService createApplicationMasterService() {
    if (this.rmContext.getYarnConfiguration().getBoolean(
        YarnConfiguration.OPPORTUNISTIC_CONTAINER_ALLOCATION_ENABLED,
        YarnConfiguration.OPPORTUNISTIC_CONTAINER_ALLOCATION_ENABLED_DEFAULT)) {
      return new OpportunisticContainerAllocatorAMService(getRMContext(),
          scheduler) {
        @Override
        protected void serviceStart() {
          // override to not start rpc handler
        }

        @Override
        protected void serviceStop() {
          // don't do anything
        }
      };
    }
    return new ApplicationMasterService(getRMContext(), scheduler) {
      @Override
      protected void serviceStart() {
        // override to not start rpc handler
      }

      @Override
      protected void serviceStop() {
        // don't do anything
      }
    };
  }

  @Override
  protected ApplicationMasterLauncher createAMLauncher() {
    return new ApplicationMasterLauncher(getRMContext()) {
      @Override
      protected void serviceStart() {
        // override to not start rpc handler
      }

      @Override
      public void handle(AMLauncherEvent appEvent) {
        // don't do anything
      }

      @Override
      protected void serviceStop() {
        // don't do anything
      }
    };
  }

  @Override
  protected AdminService createAdminService() {
    return new AdminService(this, getRMContext()) {
      @Override
      protected void startServer() {
        // override to not start rpc handler
      }

      @Override
      protected void stopServer() {
        // don't do anything
      }

      @Override
      protected EmbeddedElectorService createEmbeddedElectorService() {
        return null;
      }
    };
  }

  public NodesListManager getNodesListManager() {
    return this.nodesListManager;
  }

  public ClientToAMTokenSecretManagerInRM getClientToAMTokenSecretManager() {
    return this.getRMContext().getClientToAMTokenSecretManager();
  }

  public RMAppManager getRMAppManager() {
    return this.rmAppManager;
  }
  
  public AdminService getAdminService() {
    return this.adminService;
  }

  @Override
  protected void startWepApp() {
    if (getConfig().getBoolean(ENABLE_WEBAPP, false)) {
      super.startWepApp();
      return;
    }

    // Disable webapp
  }

  public static void finishAMAndVerifyAppState(RMApp rmApp, MockRM rm, MockNM nm,
      MockAM am) throws Exception {
    FinishApplicationMasterRequest req =
        FinishApplicationMasterRequest.newInstance(
          FinalApplicationStatus.SUCCEEDED, "", "");
    am.unregisterAppAttempt(req,true);
    rm.waitForState(am.getApplicationAttemptId(), RMAppAttemptState.FINISHING);
    nm.nodeHeartbeat(am.getApplicationAttemptId(), 1, ContainerState.COMPLETE);
    rm.waitForState(am.getApplicationAttemptId(), RMAppAttemptState.FINISHED);
    rm.waitForState(rmApp.getApplicationId(), RMAppState.FINISHED);
  }

  @SuppressWarnings("rawtypes")
  private static void waitForSchedulerAppAttemptAdded(
      ApplicationAttemptId attemptId, MockRM rm) throws InterruptedException {
    int tick = 0;
    // Wait for at most 5 sec
    while (null == ((AbstractYarnScheduler) rm.getResourceScheduler())
        .getApplicationAttempt(attemptId) && tick < 50) {
      Thread.sleep(100);
      if (tick % 10 == 0) {
        System.out.println("waiting for SchedulerApplicationAttempt="
            + attemptId + " added.");
      }
      tick++;
    }
    Assert.assertNotNull("Timed out waiting for SchedulerApplicationAttempt=" +
      attemptId + " to be added.", ((AbstractYarnScheduler)
        rm.getResourceScheduler()).getApplicationAttempt(attemptId));
  }

  /**
   * NOTE: nm.nodeHeartbeat is explicitly invoked,
   * don't invoke it before calling launchAM
   */
  public static MockAM launchAM(RMApp app, MockRM rm, MockNM nm)
      throws Exception {
    RMAppAttempt attempt = waitForAttemptScheduled(app, rm);
    System.out.println("Launch AM " + attempt.getAppAttemptId());
    nm.nodeHeartbeat(true);
    MockAM am = rm.sendAMLaunched(attempt.getAppAttemptId());
    rm.waitForState(attempt.getAppAttemptId(), RMAppAttemptState.LAUNCHED);
    return am;
  }

  public static MockAM launchUAM(RMApp app, MockRM rm, MockNM nm)
      throws Exception {
    // UAMs go directly to LAUNCHED state
    rm.waitForState(app.getApplicationId(), RMAppState.ACCEPTED);
    RMAppAttempt attempt = app.getCurrentAppAttempt();
    waitForSchedulerAppAttemptAdded(attempt.getAppAttemptId(), rm);
    System.out.println("Launch AM " + attempt.getAppAttemptId());
    nm.nodeHeartbeat(true);
    MockAM am = new MockAM(rm.getRMContext(), rm.masterService,
        attempt.getAppAttemptId());
    rm.waitForState(attempt.getAppAttemptId(), RMAppAttemptState.LAUNCHED);
    return am;
  }

  public static RMAppAttempt waitForAttemptScheduled(RMApp app, MockRM rm)
      throws Exception {
    rm.waitForState(app.getApplicationId(), RMAppState.ACCEPTED);
    RMAppAttempt attempt = app.getCurrentAppAttempt();
    waitForSchedulerAppAttemptAdded(attempt.getAppAttemptId(), rm);
    rm.waitForState(attempt.getAppAttemptId(), RMAppAttemptState.SCHEDULED);
    return attempt;
  }

  public static MockAM launchAndRegisterAM(RMApp app, MockRM rm, MockNM nm)
      throws Exception {
    MockAM am = launchAM(app, rm, nm);
    am.registerAppAttempt();
    rm.waitForState(app.getApplicationId(), RMAppState.RUNNING);
    return am;
  }

  public ApplicationReport getApplicationReport(ApplicationId appId)
      throws YarnException, IOException {
    ApplicationClientProtocol client = getClientRMService();
    GetApplicationReportResponse response =
        client.getApplicationReport(GetApplicationReportRequest
            .newInstance(appId));
    return response.getApplicationReport();
  }

  public void updateReservationState(ReservationUpdateRequest request)
      throws IOException, YarnException {
    ApplicationClientProtocol client = getClientRMService();
    client.updateReservation(request);
  }

  // Explicitly reset queue metrics for testing.
  @SuppressWarnings("static-access")
  public void clearQueueMetrics(RMApp app) {
    ((AbstractYarnScheduler<SchedulerApplicationAttempt, SchedulerNode>) getResourceScheduler())
      .getSchedulerApplications().get(app.getApplicationId()).getQueue()
      .getMetrics().clearQueueMetrics();
  }
  
  public RMActiveServices getRMActiveService() {
    return activeServices;
  }

  public void signalToContainer(ContainerId containerId,
      SignalContainerCommand command) throws Exception {
    ApplicationClientProtocol client = getClientRMService();
    SignalContainerRequest req =
        SignalContainerRequest.newInstance(containerId, command);
    client.signalToContainer(req);
  }


  /**
   * Wait until an app removed from scheduler.
   * The timeout is 40 seconds.
   * @param appId the id of an app
   * @throws InterruptedException
   *         if interrupted while waiting for app removed
   */
  public void waitForAppRemovedFromScheduler(ApplicationId appId)
      throws InterruptedException {
    waitForAppRemovedFromScheduler(appId, TIMEOUT_MS_FOR_APP_REMOVED);
  }

  /**
   * Wait until an app is removed from scheduler.
   * @param appId the id of an app
   * @param timeoutMsecs the length of timeout in milliseconds
   * @throws InterruptedException
   *         if interrupted while waiting for app removed
   */
  public void waitForAppRemovedFromScheduler(ApplicationId appId,
      long timeoutMsecs) throws InterruptedException {
    int timeWaiting = 0;

    Map<ApplicationId, SchedulerApplication> apps  =
        ((AbstractYarnScheduler) getResourceScheduler())
            .getSchedulerApplications();
    while (apps.containsKey(appId)) {
      if (timeWaiting >= timeoutMsecs) {
        break;
      }
      LOG.info("wait for app removed, " + appId);
      Thread.sleep(WAIT_MS_PER_LOOP);
      timeWaiting += WAIT_MS_PER_LOOP;
    }
    Assert.assertTrue("app is not removed from scheduler (timeout).",
        !apps.containsKey(appId));
    LOG.info("app is removed from scheduler, " + appId);
  }
}
