/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2003 - 2004 Greg Luck.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by Greg Luck
 *       (http://sourceforge.net/users/gregluck) and contributors.
 *       See http://sourceforge.net/project/memberlist.php?group_id=93232
 *       for a list of contributors"
 *    Alternately, this acknowledgement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "EHCache" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For written
 *    permission, please contact Greg Luck (gregluck at users.sourceforge.net).
 *
 * 5. Products derived from this software may not be called "EHCache"
 *    nor may "EHCache" appear in their names without prior written
 *    permission of Greg Luck.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL GREG LUCK OR OTHER
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by contributors
 * individuals on behalf of the EHCache project.  For more
 * information on EHCache, please see <http://ehcache.sourceforge.net/>.
 *
 */


package net.sf.ehcache;

import net.sf.ehcache.store.DiskStore;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test cases for the DiskStore.
 *
 * @author <a href="mailto:amurdoch@thoughtworks.com">Adam Murdoch</a>
 * @author <a href="mailto:gluck@thoughtworks.com">Greg Luck</a>
 * @version $Id: DiskStoreTest.java,v 1.1 2006/03/09 06:38:20 gregluck Exp $
 *          <p/>
 *          total time 149 old i/o
 *          total time 133, 131, 130 nio
 */
public class DiskStoreTest extends AbstractCacheTest {
    private static final Log LOG = LogFactory.getLog(DiskStoreTest.class.getName());

    /**
     * teardown
     */
    protected void tearDown() throws Exception {
        super.tearDown();
        deleteFile("persistentLongExpiryIntervalCache");
        deleteFile("fileTest");
        deleteFile("testPersistent");
    }

    /**
     * Creates a store which is non-expiring so that we can check for
     * size-related characteristics without elements being deleted under us.
     */
    private DiskStore createNonExpiringDiskStore() {
        Cache cache = new Cache("testNonPersistent", 10000, true, true, 2, 1, false, 1);
        cache.initialise();
        DiskStore diskStore = cache.getDiskStore();
        return diskStore;
    }

    private DiskStore createDiskStore() {
        Cache cache = new Cache("testNonPersistent", 10000, true, false, 2, 1, false, 1);
        cache.initialise();
        DiskStore diskStore = cache.getDiskStore();
        return diskStore;
    }

    private DiskStore createPersistentDiskStore(String cacheName) {
        Cache cache = new Cache(cacheName, 10000, true, true, 5, 1, true, 600);
        cache.initialise();
        DiskStore diskStore = cache.getDiskStore();
        return diskStore;
    }

    private DiskStore createPersistentDiskStoreFromCacheManager() {
        Cache cache = manager.getCache("persistentLongExpiryIntervalCache");
        return cache.getDiskStore();
    }

    /**
     * Tests that a file is created with the right size after puts, and that the file is
     * deleted on disposal
     */
    public void testNonPersistentStore() throws IOException, InterruptedException {
        DiskStore diskStore = createNonExpiringDiskStore();
        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());

        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));
            waitForFlush(diskStore);
            int predictedSize = 1259 * (i + 1);
            long actualSize = diskStore.getDataFileSize();
            assertEquals("On the " + i + " iteration: ", predictedSize, actualSize);
        }

        assertEquals(100, diskStore.getSize());
        diskStore.dispose();
        Thread.sleep(1);
        assertFalse("File exists", dataFile.exists());
    }

    /**
     * Tests that a file is created with the right size after puts, and that the file is not
     * deleted on disposal
     * <p/>
     * This test uses a preconfigured cache from the test cache.xml. Note that teardown causes
     * an exception because the disk store is being shut down twice.
     */
    public void testPersistentStore() throws IOException, InterruptedException, CacheException {
        //initialise
        DiskStore diskStore = createPersistentDiskStoreFromCacheManager();
        diskStore.removeAll();

        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());

        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));
        }
        waitForFlush(diskStore);
        assertEquals(100, diskStore.getSize());
        diskStore.dispose();

        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * 1259, dataFile.length());
    }

    /**
     * Tests that the expiry thread dies on dispose.
     */
    public void testExpiryThreadDiesOnDispose() throws IOException, InterruptedException {
        Cache cache = new Cache("testNonPersistent", 10000, true, false, 5, 1, false, 100);
        cache.initialise();
        DiskStore diskStore = cache.getDiskStore();

        //Put in some data
        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));
        }
        waitForFlush(diskStore);

        diskStore.dispose();
        //Give the expiry thread time to be interrupted and die
        Thread.sleep(100);
        assertTrue(!diskStore.isExpiryThreadAlive());


    }

    /**
     * Tests that we can save and load a persistent store in a repeatable way
     */
    public void testLoadPersistentStore() throws IOException, InterruptedException {
        //initialise
        String cacheName = "testPersistent";
        DiskStore diskStore = createPersistentDiskStore(cacheName);
        diskStore.removeAll();


        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));
            waitForFlush(diskStore);
            assertEquals("On the " + i + " iteration: ", 1259 * (i + 1), diskStore.getDataFileSize());
        }
        assertEquals(100, diskStore.getSize());
        diskStore.dispose();
        Thread.sleep(3000);
        //check that we can create and dispose several times with no problems and no lost data
        for (int i = 0; i < 10; i++) {
            diskStore = createPersistentDiskStore(cacheName);
            File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
            assertTrue("File exists", dataFile.exists());
            assertEquals(100 * 1259, dataFile.length());
            assertEquals(100, diskStore.getSize());

            diskStore.dispose();

            assertTrue("File exists", dataFile.exists());
            assertEquals(100 * 1259, dataFile.length());
        }
    }

    /**
     * Tests that we can save and load a persistent store in a repeatable way,
     * and delete and add data.
     */
    public void testLoadPersistentStoreWithDelete() throws IOException, InterruptedException {
        //initialise
        String cacheName = "testPersistent";
        DiskStore diskStore = createPersistentDiskStore(cacheName);
        diskStore.removeAll();


        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));
            waitForFlush(diskStore);
            assertEquals("On the " + i + " iteration: ", 1259 * (i + 1), diskStore.getDataFileSize());
        }
        assertEquals(100, diskStore.getSize());
        diskStore.dispose();

        diskStore = createPersistentDiskStore(cacheName);
        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * 1259, dataFile.length());
        assertEquals(100, diskStore.getSize());

        diskStore.remove("key100");
        assertEquals(100 * 1259, dataFile.length());
        assertEquals(99, diskStore.getSize());

        diskStore.dispose();

        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * 1259, dataFile.length());
    }

    /**
     * Tests that we can load a store after the index has been corrupted
     */
    public void testLoadPersistentStoreAfterCorruption() throws IOException, InterruptedException {
        //initialise
        String cacheName = "testPersistent";
        DiskStore diskStore = createPersistentDiskStore(cacheName);
        diskStore.removeAll();


        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));
            waitForFlush(diskStore);
            assertEquals("On the " + i + " iteration: ", 1259 * (i + 1), diskStore.getDataFileSize());
        }
        assertEquals(100, diskStore.getSize());
        diskStore.dispose();

        File indexFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getIndexFileName());
        FileOutputStream fout = new FileOutputStream(indexFile);
        //corrupt the index file
        fout.write(new byte[]{'q', 'w', 'e', 'r', 't', 'y'});
        fout.close();
        diskStore = createPersistentDiskStore(cacheName);
        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
        assertTrue("File exists", dataFile.exists());

        //Make sure the data file got recreated since the index was corrupt
        assertEquals("Data file was not recreated", 0, dataFile.length());
        assertEquals(0, diskStore.getSize());
    }


    /**
     * Tests that we can save and load a persistent store in a repeatable way,
     * and delete and add data.
     */
    public void testFreeSpaceBehaviour() throws IOException, InterruptedException {
        //initialise
        String cacheName = "testPersistent";
        DiskStore diskStore = createPersistentDiskStore(cacheName);
        diskStore.removeAll();

        byte[] data = new byte[1024];
        for (int i = 0; i < 100; i++) {
            diskStore.put(new Element("key" + (i + 100), data));
            waitForFlush(diskStore);
            int predictedSize = 1259 * (i + 1);
            long actualSize = diskStore.getDataFileSize();
            assertEquals("On the " + i + " iteration: ", predictedSize, actualSize);
        }

        assertEquals(100, diskStore.getSize());
        diskStore.dispose();

        diskStore = createPersistentDiskStore(cacheName);
        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * 1259, dataFile.length());
        assertEquals(100, diskStore.getSize());

        diskStore.remove("key100");
        diskStore.remove("key101");
        diskStore.remove("key102");
        diskStore.remove("key103");
        diskStore.remove("key104");

        diskStore.put(new Element("key100", data));
        diskStore.put(new Element("key101", data));
        waitForFlush(diskStore);
        //The file does not shrink.
        assertEquals(100 * 1259, dataFile.length());
        assertEquals(97, diskStore.getSize());

        diskStore.put(new Element("key102", data));
        diskStore.put(new Element("key103", data));
        diskStore.put(new Element("key104", data));
        diskStore.put(new Element("key201", data));
        diskStore.put(new Element("key202", data));
        waitForFlush(diskStore);
        assertEquals(102 * 1259, dataFile.length());
        assertEquals(102, diskStore.getSize());
        diskStore.dispose();
        assertTrue("File exists", dataFile.exists());
        assertEquals(102 * 1259, dataFile.length());
    }

    /**
     * Tests looking up an entry that does not exist.
     */
    public void testGetUnknown() throws Exception {
        final DiskStore diskStore = createDiskStore();
        final Element element = diskStore.get("key");
        assertNull(element);
    }

    /**
     * Tests adding an entry.
     */
    public void testPut() throws Exception {
        final DiskStore diskStore = createDiskStore();

        // Make sure the element is not found
        assertEquals(0, diskStore.getSize());
        Element element = diskStore.get("key");
        assertNull(element);

        // Add the element
        final String value = "value";
        element = new Element("key", value);
        diskStore.put(element);

        // Get the element
        assertEquals(1, diskStore.getSize());
        element = diskStore.get("key");
        assertNotNull(element);
        assertEquals(value, element.getValue());
    }

    /**
     * Tests adding an entry and waiting for it to be written.
     */
    public void testPutSlow() throws Exception {
        final DiskStore diskStore = createDiskStore();

        // Make sure the element is not found
        assertEquals(0, diskStore.getSize());
        Element element = diskStore.get("key");
        assertNull(element);

        // Add the element
        final String value = "value";
        element = new Element("key", value);
        diskStore.put(element);

        // Wait
        waitForFlush(diskStore);

        // Get the element
        assertEquals(1, diskStore.getSize());
        element = diskStore.get("key");
        assertNotNull(element);
        assertEquals(value, element.getValue());
    }

    /**
     * Tests removing an entry.
     */
    public void testRemove() throws Exception {
        final DiskStore diskStore = createDiskStore();

        // Add the entry
        final String value = "value";
        Element element = new Element("key", value);
        diskStore.put(element);

        // Check the entry is there
        assertEquals(1, diskStore.getSize());
        element = diskStore.get("key");
        assertNotNull(element);

        // Remove it
        diskStore.remove("key");

        // Check the entry is not there
        assertEquals(0, diskStore.getSize());
        element = diskStore.get("key");
        assertNull(element);
    }

    /**
     * Tests removing an entry, after it has been written
     */
    public void testRemoveSlow() throws Exception {
        final DiskStore diskStore = createDiskStore();

        // Add the entry
        final String value = "value";
        Element element = new Element("key", value);
        diskStore.put(element);

        // Wait for the entry
        waitForFlush(diskStore);

        // Check the entry is there
        assertEquals(1, diskStore.getSize());
        element = diskStore.get("key");
        assertNotNull(element);

        // Remove it
        diskStore.remove("key");

        // Check the entry is not there
        assertEquals(0, diskStore.getSize());
        element = diskStore.get("key");
        assertNull(element);
    }

    /**
     * Tests removing all the entries.
     */
    public void testRemoveAll() throws Exception {
        final DiskStore diskStore = createDiskStore();

        // Add the entry
        final String value = "value";
        Element element = new Element("key", value);
        diskStore.put(element);

        // Check the entry is there
        element = diskStore.get("key");
        assertNotNull(element);

        // Remove it
        diskStore.removeAll();

        // Check the entry is not there
        assertEquals(0, diskStore.getSize());
        element = diskStore.get("key");
        assertNull(element);
    }

    /**
     * Tests removing all the entries, after they have been written to disk.
     */
    public void testRemoveAllSlow() throws Exception {
        final DiskStore diskStore = createDiskStore();

        // Add the entry
        final String value = "value";
        Element element = new Element("key", value);
        diskStore.put(element);

        // Wait
        waitForFlush(diskStore);

        // Check the entry is there
        element = diskStore.get("key");
        assertNotNull(element);

        // Remove it
        diskStore.removeAll();

        // Check the entry is not there
        assertEquals(0, diskStore.getSize());
        element = diskStore.get("key");
        assertNull(element);
    }

    /**
     * Tests bulk load.
     */
    public void testBulkLoad() throws Exception {
        final DiskStore diskStore = createDiskStore();

        final Random random = new Random();

        // Add a bunch of entries
        for (int i = 0; i < 500; i++) {
            // Use a random length value
            final String key = "key" + i;
            final String value = "This is a value" + random.nextInt(1000);

            // Add an element, and make sure it is present
            Element element = new Element(key, value);
            diskStore.put(element);
            element = diskStore.get(key);
            assertNotNull(element);

            // Chuck in a delay, to give the spool thread a chance to catch up
            Thread.sleep(2);

            // Remove the element
            diskStore.remove(key);
            element = diskStore.get(key);
            assertNull(element);

            element = new Element(key, value);
            diskStore.put(element);
            element = diskStore.get(key);
            assertNotNull(element);

            // Chuck in a delay
            Thread.sleep(2);
        }
    }

    /**
     * Tests for element expiry.
     */
    public void testExpiry() throws Exception {
        // Create a diskStore with a cranked up expiry thread
        final DiskStore diskStore = createDiskStore();

        // Add an element that will expire.
        Element element = new Element("key", "value");
        diskStore.put(element);
        assertEquals(1, diskStore.getSize());

        assertNotNull(diskStore.get("key"));


        waitForFlush(diskStore);

        // Wait a couple of seconds
        Thread.sleep(3000);

        assertNull(diskStore.get("key"));

    }

    /**
     * Waits for all spooled elements to be written to disk.
     */
    private static void waitForFlush(DiskStore diskStore) throws InterruptedException {
        while (true) {
            if (diskStore.isSpoolEmpty()) {
                //Do not return until spool is empty
                return;
            } else {
                //Wait for 100ms before checking again
                Thread.sleep(5);
            }
        }
    }

    /**
     * Multi-thread read-only test. Will fail on memory constrained VMs
     */
    public void testReadOnlyMultipleThreads() throws Exception {
        final DiskStore diskStore = createNonExpiringDiskStore();

        // Add a couple of elements
        diskStore.put(new Element("key0", "value"));
        diskStore.put(new Element("key1", "value"));

        // Wait for the elements to be written
        waitForFlush(diskStore);

        // Run a set of threads, that attempt to fetch the elements
        final List executables = new ArrayList();
        for (int i = 0; i < 10; i++) {
            final String key = "key" + (i % 2);
            final Executable executable = new Executable() {
                public void execute() throws Exception {
                    final Element element = diskStore.get(key);
                    assertNotNull(element);
                    assertEquals("value", element.getValue());
                }
            };
            executables.add(executable);
        }
        runThreads(executables);
    }

    /**
     * Multi-thread concurrent read remove test.
     */
    public void testReadRemoveMultipleThreads() throws Exception {
        final Random random = new Random();
        final DiskStore diskStore = createDiskStore();

        diskStore.put(new Element("key", "value"));

        // Run a set of threads that get, put and remove an entry
        final List executables = new ArrayList();
        for (int i = 0; i < 5; i++) {
            final Executable executable = new Executable() {
                public void execute() throws Exception {
                    for (int i = 0; i < 100; i++) {
                        diskStore.put(new Element("key" + random.nextInt(100), "value"));
                    }
                }
            };
            executables.add(executable);
        }
        for (int i = 0; i < 5; i++) {
            final Executable executable = new Executable() {
                public void execute() throws Exception {
                    for (int i = 0; i < 100; i++) {
                        diskStore.remove("key" + random.nextInt(100));
                    }
                }
            };
            executables.add(executable);
        }

        runThreads(executables);
    }

    /**
     * Runs a set of threads, for a fixed amount of time.
     */
    private static void runThreads(final List executables) throws Exception {

        final long endTime = System.currentTimeMillis() + 3000;
        final ArrayList errors = new ArrayList();

        // Spin up the threads
        final Thread[] threads = new Thread[executables.size()];
        for (int i = 0; i < threads.length; i++) {
            final Executable executable = (Executable) executables.get(i);
            threads[i] = new Thread() {
                public void run() {
                    try {
                        // Run the thread until the given end time
                        while (System.currentTimeMillis() < endTime) {
                            executable.execute();
                        }
                    } catch (Throwable t) {
                        // Hang on to any errors
                        LOG.error(t.getMessage(), t);
                        errors.add(t);
                    }
                }
            };
            threads[i].start();
        }

        // Wait for the threads to finish
        for (int i = 0; i < threads.length; i++) {
            threads[i].join();
        }

        // Fail if any error happened
        if (errors.size() > 0) {
            fail(errors.size() + " errors");
        }
    }

    /**
     * A runnable, that can throw an exception.
     */
    private interface Executable {
        /**
         * Executes this object.
         */
        void execute() throws Exception;
    }

    /**
     * Tests how data is written to a random access file.
     * <p/>
     * It makes sure that bytes are immediately written to disk after a write.
     */
    public void testWriteToFile() throws IOException {
        // Create and set up file
        String dataFileName = "fileTest";
        RandomAccessFile file = getRandomAccessFile(dataFileName);

        //write data to the file
        byte[] buffer = new byte[1024];
        for (int i = 0; i < 100; i++) {
            file.write(buffer);
        }

        assertEquals(1024 * 100, file.length());

    }

    private RandomAccessFile getRandomAccessFile(String name) throws FileNotFoundException {
        String diskPath = System.getProperty("java.io.tmpdir");
        final File diskDir = new File(diskPath);
        File dataFile = new File(diskDir, name + ".data");
        return new RandomAccessFile(dataFile, "rw");
    }

    /**
     * Test overflow to disk = true, using 100000 records.
     * 15 seconds v1.38 DiskStore
     * 2 seconds v1.42 DiskStore
     * Adjusted for change to laptop
     */
    public void testOverflowToDiskWithLargeNumberofCacheEntries() throws Exception {

        //Set size so the second element overflows to disk.
        Cache cache = new Cache("test", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 500, 500, false, 1, null);
        manager.addCache(cache);
        int i = 0;
        StopWatch stopWatch = new StopWatch();
        for (; i < 100000; i++) {
            cache.put(new Element("" + i,
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        }
        long elapsed = stopWatch.getElapsedTime();
        LOG.info("Elapsed time: " + elapsed / 1000);
        assertTrue("Took too long", elapsed <= 4000);
        assertEquals(100000, cache.getSize());
        assertEquals(99000, cache.getDiskStore().getSize());
    }

    /**
     * Test overflow to disk = true, using 100000 records.
     * 35 seconds v1.38 DiskStore
     * 26 seconds v1.42 DiskStore
     */
    public void testOverflowToDiskWithLargeNumberofCacheEntriesAndGets() throws Exception {

        //Set size so the second element overflows to disk.
        Cache cache = new Cache("test", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 500, 500, false, 1, null);
        manager.addCache(cache);
        Random random = new Random();
        StopWatch stopWatch = new StopWatch();
        for (int i = 0; i < 100000; i++) {
            cache.put(new Element("" + i,
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));

            cache.get("" + random.nextInt(100000));
        }


        long elapsed = stopWatch.getElapsedTime();
        LOG.info("Elapsed time: " + elapsed / 1000);
        assertEquals(100000, cache.getSize());
        //Some entries may be in the Memory Store and Disk Store. cache.getSize removes dupes. a look at the
        //disk store size directly does not.
        assertTrue(99000 <= cache.getDiskStore().getSize());
    }
}
