package net.sf.ehcache.util;

import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

/**
 * @author Alex Snaps
 */
public class TimestamperTest {

    public static final int TOTAL_RUNS = 750000;
    public static final int THREADS  = 8;

    @Test
    public void testNext() throws Exception {
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final ConcurrentMap<Long, Integer> values = new ConcurrentHashMap<Long, Integer>();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong totalRuns = new AtomicLong();
        final AtomicLong stoppedAt = new AtomicLong();
        
        Thread[] threads = new Thread[THREADS];
        for(int i =0; i < THREADS; i++) {
            threads[i] = new Thread() {

                long runs;

                @Override
                public void run() {
                    while (!stopped.get() && runs < (TOTAL_RUNS / THREADS)) {
//                        Timestamper.next();
                        ++runs;
                        if(values.putIfAbsent(Timestamper.next(), 0) != null) {
                            errors.incrementAndGet();
                        }
                    }
                    if (stopped.compareAndSet(false, true)) {
                      stoppedAt.set(System.nanoTime());
                    }
                    totalRuns.addAndGet(runs);
                }
            };
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat("Shouldn't wait that long for all threads to join!",
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stoppedAt.get()), is(0L));
        // Only meaning full if you don't put values in the chm!
//        System.out.println(totalRuns.get() / DURATION / THREADS + " tps per thread" );
        assertThat(errors.get(), is(0L));
    }
}
