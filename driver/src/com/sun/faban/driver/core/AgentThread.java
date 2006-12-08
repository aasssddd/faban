/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: AgentThread.java,v 1.7 2006/12/08 05:15:54 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.driver.core;

import com.sun.faban.driver.FatalException;
import com.sun.faban.driver.Timing;
import com.sun.faban.driver.util.Random;
import com.sun.faban.driver.util.Timer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.InterruptedIOException;


/**
 * Abstract superclass for all driver threads. It provides a factory for
 * instantiating the right implementation depending on the driver specification.
 * Subclasses execute the provided driver, keeps track of response times,
 * think times, transaction counts, etc.
 *
 * @author Akara Sucharitakul
 */
public abstract class AgentThread extends Thread {

    public static final int NOT_STARTED = 0;
    public static final int INITIALIZING = 1;
    public static final int PRE_RUN = 2;
    public static final int RUNNING = 3;
    public static final int POST_RUN = 4;
    public static final int ENDED = 5;

    String type;
    String name;
    int id;
    int currentOperation = -1; // Global index into the current operation.
    int[] previousOperation; // Index into the previous operation.
    int mixId = 0; // 0 for foreground and 1 for background
    Mix.Selector[] selector; // The selector array, size 1 if no bg, 2 if bg

    DriverContext driverContext;
    Metrics metrics;
    Random random = new Random();
    Timer timer;
    AgentImpl agent;
    RunInfo.DriverConfig driverConfig;
    Class driverClass;
    Object driver;
    boolean inRamp = true; // indicator for rampup or rampdown, initially true
    int[] delayTime;  // recently calculated cycle times
    int[] startTime; // start times for previous tx
    int[] endTime; // end time for the recent tx ended

    private int threadState = NOT_STARTED;

    Logger logger;
    String className;
    int endRampUp, endStdyState, endRampDown;
    int cycleCount = 0; // The cycles executed so far

    /** Run configuration from the Master */
    RunInfo runInfo;

    boolean startTimeSet = false;

    boolean stopped = false;

    /**
     * Factory method for instantiating the right type of AgentThread.
     * @param type The type of this agent
     * @param agentId The display id of this agent
     * @param id The id of this agent
     * @param driverClass The driver class
     * @param timer The timer object reference
     * @param agent The agent calling this thread
     * @return An instance of the AgentThread subclass.
     */
    public static AgentThread getInstance(String type, String agentId, int id,
                                Class driverClass, Timer timer,
                                AgentImpl agent) {
        RunInfo.DriverConfig driverConfig = RunInfo.getInstance().driverConfig;
        AgentThread agentThread = null;
        switch (driverConfig.runControl) {
           case TIME : if (driverConfig.mix[1] != null)
                            agentThread = new TimeThreadWithBackground();
                       else
                            agentThread = new TimeThread();
                       break;
           case CYCLES : agentThread = new CycleThread();
        }

        agentThread.configure(type, agentId, id, driverClass, timer, agent);
        return agentThread;
    }

    /**
     * Configures this AgentThread.
     *
     * @param type The type of this agent
     * @param agentId The display id of this agent
     * @param id The id of this agent
     * @param driverClass The driver class
     * @param timer The timer object reference
     * @param agent The agent calling this thread
     */
    private void configure(String type, String agentId, int id,
                                Class driverClass, Timer timer,
                                AgentImpl agent) {
        this.type = type;
        this.id = id;
        this.driverClass = driverClass;
        this.timer = timer;
        this.runInfo = RunInfo.getInstance();
        this.agent = agent;
        random = new Random(timer.getTime() + hashCode());
        className = getClass().getName();
        driverConfig = runInfo.driverConfig;
        name = type + '[' + agentId + "]." + id;
        setName(name);
        logger = Logger.getLogger(className + '.' + id);
        metrics = new Metrics(this);
        initTimes();
    }

    /**
     *  Allocates and initializes the timing structures which is specific
     *  to the pseudo-thread dimensions.
     */
    abstract void initTimes();

    /**
     * Entry point for starting the thread. Subclasses do not override this
     * method but override doRun instead. Run implicitly calls doRun.
     */
    public final void run() {
        try {
            setThreadState(INITIALIZING);
            doRun();
        } catch (FatalException e) {
            // A fatal exception thrown by the driver is already caught
            // in the run methods and logged there.
            // A fatal exception otherwise thrown by the run
            // methods signals termination of this thread.
            if (!e.wasLogged())  {
                Throwable t = e.getCause();
                if (t != null)
                    logger.log(Level.SEVERE, name + ": " + t.getMessage(), t);
                else
                    logger.log(Level.SEVERE, name + ": " + e.getMessage(), e);
                e.setLogged();
                agent.abortRun();
            }
        } catch (Throwable t) {
            logger.log(Level.SEVERE, name + ": " + t.getMessage(), t);
            agent.abortRun();
        } finally {
            postRun();
        }
    }

    /**
     * Each thread executes in the doRun method until the benchmark time is up
     * The main loop chooses a tx. type according to the mix specified in
     * the parameter file and calls the appropriate transaction
     * method to do the job.
   	 * The stats for the entire run are stored in a Metrics object
   	 * which is returned to the Agent via the getResult() method.
     * @see Metrics
     */
    abstract void doRun();

    /**
     * Checks for a fatal exception. This is called from the invocation loop.
     * @param e The throwable
     * @param op The operation
     */
    void checkFatal(Throwable e, BenchmarkDefinition.Operation op) {
        if (e instanceof FatalException) {
            FatalException fatal = (FatalException) e;
            e = fatal.getCause();
            if (e != null)
                logger.log(Level.SEVERE, name + '.' + op.m.getName() +
                        ": " + e.getMessage(), e);
            else
                logger.log(Level.SEVERE, name + '.' + op.m.getName() +
                        ": " + fatal.getMessage(), fatal);
            fatal.setLogged();
            agent.abortRun();
            throw fatal; // Also don't continue with current thread.
        }
    }

    /**
     * Logs the normal errors occuring in operations. The operation is marked
     * as failed.
     * @param e  The throwable received
     * @param op The operation being executed.
     */
    void logError(Throwable e, BenchmarkDefinition.Operation op) {
        String message = name + "." + op.m.getName() + ": " +
                e.getMessage();
        if (inRamp)
            message += "\nNote: Error not counted in result." +
                    "\nEither transaction start or end time is not " +
                    "within steady state.";
        logger.log(Level.WARNING, message, e);
    }

    private synchronized void setThreadState(int state) {
        threadState = state;
        notifyAll();
    }

    private synchronized boolean compareAndSetThreadState(int orig, int state) {
        boolean set = threadState == orig;
        if (set) {
            threadState = state;
            notifyAll();
        }
        return set;
    }


    /**
     * Obtains the state of the current thread.
     * @return The state of the current thread.
     */
    public synchronized int getThreadState() {
        return threadState;
    }

    /**
     * Waits for a given state of the thread to arrive.
     * @param state The state to wait for.
     */
    public synchronized void waitThreadState(int state) {
        while (threadState < state) {
            try {
                wait(10000);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Executes the method market with @OnceBefore in thread 0
     */
    void preRun() {
        // Thread 0 needs to do the preRun
        if (id == 0 && driverConfig.preRun != null) {
            setThreadState(PRE_RUN);
            try {
                invokePrePost(driverConfig.preRun.m);
            } catch (InterruptedIOException e) {
                // Should not happen unless run is cancelled. And if so,
                // we don't really care to redo this.
            }
            agent.preRunLatch.countDown();
        }
        setThreadState(RUNNING);
    }

    /**
     * Executes the method market with @OnceAfter in thread 0
     */
    void postRun() {
        if (id == 0 && driverConfig.postRun != null &&
                compareAndSetThreadState(RUNNING, POST_RUN)) {
            while (agent.postRunLatch.getCount() > 0l)
                try {
                    agent.postRunLatch.await();
                } catch (InterruptedException e) {
                }
            // We need to make sure this method is re-run if I/O is interrupted.
            // This may happen if terminate gets called while thread is
            // switching to POST_RUN state.
            boolean interrupted = false;
            do {
                try {
                    invokePrePost(driverConfig.postRun.m);
                } catch (InterruptedIOException e) {
                    interrupted = true;
                }
            } while (interrupted);
        }
        setThreadState(ENDED);
    }

    private void invokePrePost(Method m) throws InterruptedIOException {
        try {
            m.invoke(driver);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause == null)
                cause = e;
            logger.log(Level.WARNING, name + "." + m.getName() + ": " +
                    e.getMessage(), e);
            if (cause instanceof InterruptedIOException)
                throw (InterruptedIOException) cause;
        } catch (IllegalAccessException e) {
            logger.log(Level.SEVERE, name + "." + m.getName() + ": " +
                    e.getMessage(), e);
        }

    }

    /**
     * Obtains the invoke time of the next operaion.
     * @param op The operation
     * @param mixId The mix
     * @return The targeted invoke time.
     */
    int getInvokeTime(BenchmarkDefinition.Operation op, int mixId) {
        Cycle cycle;
        if (op == null)
            // No op, this is the initial cycle, use initialDelay
            cycle = runInfo.driverConfig.initialDelay[mixId];
        else
            // Set the start time based on the operation selected
            cycle = op.cycle;

        int invokeTime = -1;
        delayTime[mixId] = cycle.getDelay(random);

        switch (cycle.cycleType) {
            case CYCLETIME :
                invokeTime = startTime[mixId] + delayTime[mixId];
                break;
            case THINKTIME :
                invokeTime = endTime[mixId] + delayTime[mixId];
                break;
        }
        return invokeTime;
    }

    /**
     * This method blocks until the start time is set by the master.
     * Called by AgentThread implementations.
     */
    void waitStartTime() {
        try {
            agent.timeSetLatch.await();
            startTimeSet = true;
            int delay = runInfo.benchStartTime - timer.getTime();
            if (delay <= 0) {
                logger.severe(name + ": TriggerTime has expired. " +
                        "Need " + (-delay) + " ms more");
                agent.abortRun();
            } else {
                // debug.println(3, ident + "Sleeping for " + delay + "ms");
                Thread.sleep(delay);
            }
        } catch (InterruptedException e) { // Run is killed.
            throw new FatalException(e);
        }
    }

    /**
     * Validates whether the times for a successful operation are properly
     * captured. Called by AgentThread implementations.
     * @param op The operation.
     */
    void validateTimeCompletion(BenchmarkDefinition.Operation op) {
        DriverContext.TimingInfo timingInfo = driverContext.timingInfo;

        // If there is no error, we still check for the
        // unusual case of time not recorded and issue the
        // proper message.
        if (timingInfo.invokeTime == -1) {
            String msg = null;
            if (driverConfig.operations[
                    currentOperation].timing == Timing.AUTO) {
                msg = name + '.' + op.m.getName() +
                        ": Transport not called! " +
                        "Please ensure transport instantiation " +
                        "before making any remote calls!";
            } else {
                msg = name + '.' + op.m.getName() +
                        ": Cannot determine time! " +
                        "DriverContext.recordTime() not called " +
                        "before critical section in operation.";

            }
            logger.severe(msg);
            agent.abortRun();
            throw new FatalException(msg);
        } else if (timingInfo.respondTime == -1) {
            String msg = null;
            if (driverConfig.operations[
                    currentOperation].timing == Timing.AUTO) {
                msg = name + '.' + op.m.getName() +
                        ": Transport incomplete! " +
                        "Please ensure transport exception is " +
                        "thrown from operation.";
            } else {
                msg = name + '.' + op.m.getName() +
                        ": Cannot determine end time! " +
                        "DriverContext.recordTime() not called " +
                        "after critical section in operation.";

            }
            logger.severe(msg);
            agent.abortRun();
            throw new FatalException(msg);
        }
    }

    /**
     * Checks whether the last operation is in the ramp-up or ramp-down or
     * not. Updates the inRamp parameter accordingly.  This is only supposed
     * to be called from a subclass.
     */
    abstract void checkRamp();

    /**
     * Tests whether the last operation is in steady state or not. This is
     * called through the context from the driver from within the operation
     * so we need to be careful not to change run control parameters. This
     * method only reads the stats.
     * @return True if the last operation is in steady state, false otherwise.
     */
    abstract boolean isSteadyState();

    /**
     * Tests whether the time between start and end is in steady state or not.
     * For non time-based steady state, this will depend on the current cycle
     * count. Otherwise time is used.
     * @param start The start of a time span
     * @param end The end of a time span
     * @return true if this time span is in steady state, false otherwise.
     */
    abstract boolean isSteadyState(int start, int end);

    /**
     * Return results of this thread.
     * @return Final stats
     */
    public Metrics getResult() {
        return(metrics);
    }

    /**
     * Triggers stopping and exiting of this thread.
     */
    public void stopExecution() {
        stopped = true;
        interrupt();
    }
}
