/**
 *  Copyright 2003-2010 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.constructs.nonstop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;
import junit.framework.TestCase;
import net.sf.ehcache.constructs.nonstop.ThreadDump.ThreadInformation;

public class ExecutorServiceTest extends TestCase {

    private NonStopCacheExecutorService service;
    private int initialThreadsCount;

    @Override
    protected void setUp() throws Exception {
        initialThreadsCount = countExecutorThreads();
        System.out.println("Initial thread count: " + initialThreadsCount);
        service = new NonStopCacheExecutorService();
    }

    @Override
    protected void tearDown() throws Exception {
        service.shutdown();
        Thread.sleep(2000);
        int threads = countExecutorThreads();
        System.out.println("After shutting down service, thread count: " + threads);
        Assert.assertEquals(initialThreadsCount, threads);
        System.out.println("Test complete successfully");
    }

    public void testExecutorThreadsCreated() throws Exception {
        int corePoolSize = NonStopCacheExecutorService.DEFAULT_CORE_THREAD_POOL_SIZE;
        int maxPoolSize = NonStopCacheExecutorService.DEFAULT_MAX_THREAD_POOL_SIZE;
        for (int i = 0; i < corePoolSize; i++) {
            service.execute(new NoopCallable(), 1000);
        }
        // assert at least core pool size threads has been created
        assertTrue(countExecutorThreads() - initialThreadsCount >= corePoolSize);

        // submit another maxPoolSize jobs
        for (int i = 0; i < maxPoolSize; i++) {
            try {
                service.execute(new NoopCallable(), 1);
            } catch (TimeoutException e) {
                // ignore
            }
        }
        Thread.sleep(1000);
        // assert maxPoolSize threads has been created
        assertEquals("", maxPoolSize, countExecutorThreads() - initialThreadsCount);

        int extraThreads = 10;
        int numAppThreads = maxPoolSize + extraThreads;
        final List<Exception> exceptionList = new ArrayList<Exception>();
        final CyclicBarrier barrier = new CyclicBarrier(numAppThreads + 1);
        final AtomicInteger finishedThreadsCount = new AtomicInteger();
        final List<BlockingCallable> blockingCallables = new ArrayList<BlockingCallable>();
        for (int i = 0; i < numAppThreads; i++) {
            Thread thread = new Thread(new Runnable() {

                public void run() {
                    try {
                        barrier.await();
                        BlockingCallable blockingCallable = new BlockingCallable(false);
                        blockingCallables.add(blockingCallable);
                        service.execute(blockingCallable, 5000);
                    } catch (TimeoutException e) {
                        // ignore
                    } catch (Exception e) {
                        exceptionList.add(e);
                    }
                    finishedThreadsCount.incrementAndGet();
                }
            });
            thread.start();
        }

        // start the app threads
        System.out.println("Letting all app threads go ahead");
        barrier.await();
        // wait for one second so that all threads go inside executor
        Thread.sleep(1000);

        // now assert extraThread tasks are in the queue as maxPoolSize tasks should be picked up by the executor threads
        assertEquals(extraThreads, service.getTaskQueue().size());
        System.out.println("Asserted task queue size");

        System.out.println("Waiting for all app threads to complete");
        while (finishedThreadsCount.get() != numAppThreads) {
            Thread.sleep(1000);
            System.out.println("Finished: " + finishedThreadsCount.get() + "/" + numAppThreads);
        }
        // assert no other exception other than timeoutException happened
        assertEquals(0, exceptionList.size());

        // assert no more than maxPoolSize threads created
        assertEquals(maxPoolSize, countExecutorThreads() - initialThreadsCount);

        // cleanup - unblock all executor threads
        for (BlockingCallable blockingCallable : blockingCallables) {
            blockingCallable.unblock();
        }
    }

    private int countExecutorThreads() {
        List<ThreadInformation> threadDump = ThreadDump.getThreadDump();
        int rv = 0;
        for (ThreadInformation info : threadDump) {
            if (info.getThreadName().contains(NonStopCacheExecutorService.EXECUTOR_THREAD_NAME_PREFIX)) {
                // System.out.println("Thread: id=" + info.getThreadId() + ", name=\"" + info.getThreadName() +
                // "\": is an executor thread");
                rv++;
            }
        }
        System.out.println("Counting number of executor threads created till now: " + rv);
        return rv;
    }

    private static class NoopCallable implements Callable<Void> {

        public Void call() throws Exception {
            // do nothing
            return null;
        }

    }

}
