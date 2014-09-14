/**
 * Copyright 2012-2014 Cask, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tigon.sql.internal;

import co.cask.tigon.sql.conf.Constants;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AbstractIdleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps track of the number of heartbeat records received from all SQL Compiler processes.
 */
public class HealthInspector extends AbstractIdleService {
  private static final Logger LOG = LoggerFactory.getLogger(HealthInspector.class);
  private ScheduledFuture monitorFuture;
  private ScheduledFuture initialTimeout;
  private final ProcessMonitor serviceFailed;
  private final Set<String> heartbeatCounter;
  private final Set<String> modelCounter;

  /**
   * Constructor for HealthInspector which ensures the liveness of the SQL Compiler processes.
   * If the class fails to detect a heartbeat from every registered process in the last two seconds then this class
   * invokes the {@link ProcessMonitor#notifyFailure(java.util.Set)} defined in the {@link ProcessMonitor} object.
   *
   * @param processMonitor The reference of the class that implements
   * {@link ProcessMonitor#notifyFailure(java.util.Set)}
   */
  public HealthInspector(ProcessMonitor processMonitor) {
    serviceFailed = processMonitor;
    Map<String, Boolean> heartbeatMap = Maps.newConcurrentMap();
    Map<String, Boolean> modelMap = Maps.newConcurrentMap();
    heartbeatCounter = Sets.newSetFromMap(heartbeatMap);
    modelCounter = Sets.newSetFromMap(modelMap);
  }

  /**
   * Registers the provided process name for health inspection.
   * This function should be invoked for every process that has to be monitored by this class.
   *
   * @param processName The unique name of the process that is being registered for monitoring
   */
  public void register(String processName) {
    modelCounter.add(processName);
  }

  /**
   * Logs the ping generated by the given process name.
   * This function should be invoked for every ping that is received.
   *
   * @param processName Name of the pinging process
   */
  public void ping(String processName) {
    heartbeatCounter.add(processName);
  }

  private boolean checkState() {
    // Check if all processes returned at least one ping since the last call. Actual expectation is 1 per second.
    if (!heartbeatCounter.containsAll(modelCounter)) {
      LOG.info("Heartbeat detection failed");
      return false;
    }
    heartbeatCounter.clear();
    return true;
  }

  /**
   * This function initiates the monitoring services that checks if a heartbeat from each registered process was
   * detected in the last 2 seconds. In case of failure, {@link ProcessMonitor#notifyFailure(java.util.Set)} of the
   * constructor's parameter object is invoked.
   */
  @Override
  protected void startUp() throws Exception {
    initialTimeout = Executors.newScheduledThreadPool(1).schedule(
      new Runnable() {
      @Override
      public void run() {
        if (modelCounter.size() <= 0) {
          LOG.info("Heartbeat detection failed");
          serviceFailed.notifyFailure(null);
        }
      }
    }, Constants.INITIALIZATION_TIMEOUT, TimeUnit.SECONDS);
    monitorFuture = Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(
      new Runnable() {
        @Override
        public void run() {
          if (!checkState()) {
            Set<String> missingHeartbeatRecord = Sets.newHashSet(modelCounter);
            missingHeartbeatRecord.removeAll(heartbeatCounter);
            serviceFailed.notifyFailure(missingHeartbeatRecord);
          }
        }
      }, Constants.INITIALIZATION_TIMEOUT + Constants.HEARTBEAT_FREQUENCY
      , Constants.HEARTBEAT_FREQUENCY
      , TimeUnit.SECONDS);
  }

  /**
   * Stops the monitoring service.
   */
  @Override
  protected void shutDown() throws Exception {
    monitorFuture.cancel(true);
    heartbeatCounter.clear();
  }
}
