/**
 *  Copyright 2003-2006 Greg Luck
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

package net.sf.ehcache;

import net.sf.ehcache.distribution.JVMUtil;
import net.sf.ehcache.store.DiskStore;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import net.sf.ehcache.store.Primitive;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test cases for the DiskStore.
 *
 * @author <a href="mailto:amurdoch@thoughtworks.com">Adam Murdoch</a>
 * @author <a href="mailto:gluck@thoughtworks.com">Greg Luck</a>
 * @version $Id$
 *          <p/>
 *          total time 149 old i/o
 *          total time 133, 131, 130 nio
 */
public class DiskStoreTest extends AbstractCacheTest {
    private static final Log LOG = LogFactory.getLog(DiskStoreTest.class.getName());
    private static final int ELEMENT_ON_DISK_SIZE = 1340;

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

    private DiskStore createAutoPersistentDiskStore(String cacheName) {
        Cache cache = new Cache(cacheName, 10000, true, true, 5, 1, true, 600);
        cache.setDiskStorePath(System.getProperty("java.io.tmpdir") + File.separator + DiskStore.generateUniqueDirectory());
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
            int predictedSize = ELEMENT_ON_DISK_SIZE * (i + 1);
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
        assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
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
            assertEquals("On the " + i + " iteration: ", ELEMENT_ON_DISK_SIZE * (i + 1), diskStore.getDataFileSize());
        }
        assertEquals(100, diskStore.getSize());
        diskStore.dispose();
        Thread.sleep(3000);
        //check that we can create and dispose several times with no problems and no lost data
        for (int i = 0; i < 10; i++) {
            diskStore = createPersistentDiskStore(cacheName);
            File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
            assertTrue("File exists", dataFile.exists());
            assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
            assertEquals(100, diskStore.getSize());

            diskStore.dispose();

            assertTrue("File exists", dataFile.exists());
            assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
        }
    }

    /**
     * Any disk store with an auto generated random directory should not be able to be loaded.
     */
    public void testCannotLoadPersistentStoreWithAutoDir() throws IOException, InterruptedException {
        //initialise
        String cacheName = "testPersistent";
        DiskStore diskStore = createAutoPersistentDiskStore(cacheName);
        diskStore.removeAll();


        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024];
            diskStore.put(new Element("key" + (i + 100), data));

            waitForFlush(diskStore);
            assertEquals("On the " + i + " iteration: ", ELEMENT_ON_DISK_SIZE * (i + 1), diskStore.getDataFileSize());
        }
        assertEquals(100, diskStore.getSize());
        String diskPath = diskStore.getDataFilePath();
        diskStore.dispose();
        Thread.sleep(1000);

        Cache cache = new Cache(cacheName, 10000, true, true, 5, 1, true, 600);
        cache.setDiskStorePath(diskPath);
        cache.initialise();

        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
        assertTrue("File exists", dataFile.exists());
        assertEquals(0, dataFile.length());
        assertEquals(0, cache.getSize());
        diskStore.dispose();
        assertTrue("File exists", dataFile.exists());
        assertEquals(0, dataFile.length());
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
            assertEquals("On the " + i + " iteration: ", ELEMENT_ON_DISK_SIZE * (i + 1), diskStore.getDataFileSize());
        }
        assertEquals(100, diskStore.getSize());
        diskStore.dispose();

        diskStore = createPersistentDiskStore(cacheName);
        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
        assertEquals(100, diskStore.getSize());

        diskStore.remove("key100");
        assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
        assertEquals(99, diskStore.getSize());

        diskStore.dispose();

        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
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
            assertEquals("On the " + i + " iteration: ", ELEMENT_ON_DISK_SIZE * (i + 1), diskStore.getDataFileSize());
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
            int predictedSize = ELEMENT_ON_DISK_SIZE * (i + 1);
            long actualSize = diskStore.getDataFileSize();
            assertEquals("On the " + i + " iteration: ", predictedSize, actualSize);
        }

        assertEquals(100, diskStore.getSize());
        diskStore.dispose();

        diskStore = createPersistentDiskStore(cacheName);
        File dataFile = new File(diskStore.getDataFilePath() + File.separator + diskStore.getDataFileName());
        assertTrue("File exists", dataFile.exists());
        assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
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
        assertEquals(100 * ELEMENT_ON_DISK_SIZE, dataFile.length());
        assertEquals(97, diskStore.getSize());

        diskStore.put(new Element("key102", data));
        diskStore.put(new Element("key103", data));
        diskStore.put(new Element("key104", data));
        diskStore.put(new Element("key201", data));
        diskStore.put(new Element("key202", data));
        waitForFlush(diskStore);
        assertEquals(102 * ELEMENT_ON_DISK_SIZE, dataFile.length());
        assertEquals(102, diskStore.getSize());
        diskStore.dispose();
        assertTrue("File exists", dataFile.exists());
        assertEquals(102 * ELEMENT_ON_DISK_SIZE, dataFile.length());
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
        assertEquals(value, element.getObjectValue());
    }


    /**
     * Tests the loading of classes
     */
    public void testClassloading() throws Exception {
        final DiskStore diskStore = createDiskStore();

        Long value = new Long(123L);
        Element element = new Element("key", value);
        diskStore.put(element);
        Thread.sleep(1000);
        Element elementOut = diskStore.get("key");
        assertEquals(value, elementOut.getObjectValue());



        Primitive primitive = new Primitive();
        primitive.integerPrimitive = 123;
        primitive.longPrimitive = 456L;
        primitive.bytePrimitive = "a".getBytes()[0];
        primitive.charPrimitive = 'B';
        primitive.booleanPrimitive = false;

        //test Serializability
        ByteArrayOutputStream outstr = new ByteArrayOutputStream();
        ObjectOutputStream objstr = new ObjectOutputStream(outstr);
        objstr.writeObject(element);
        objstr.close();


        Element primitiveElement = new Element("primitive", primitive);
        diskStore.put(primitiveElement);
        Thread.sleep(1000);
        elementOut = diskStore.get("primitive");
        assertEquals(primitive, elementOut.getObjectValue());

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
        assertEquals(value, element.getObjectValue());
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
                Thread.sleep(10);
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
                    assertEquals("value", element.getObjectValue());
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
    }


    /**
     * This test is designed to be used with a profiler to explore the ways in which DiskStore
     * uses memory. It does not do much on its own.
     */
    public void testOutOfMemoryErrorOnOverflowToDisk() throws Exception {

        //Set size so the second element overflows to disk.
        Cache cache = new Cache("test", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 500, 500, false, 1, null);
        manager.addCache(cache);
        int i = 0;

        Random random = new Random();
        for (; i < 5500; i++) {
            byte[] bytes = new byte[10000];
            random.nextBytes(bytes);
            cache.put(new Element("" + i, bytes));
        }
        LOG.info("Elements written: " + i);
        //Thread.sleep(100000);
    }

    /**
     * Test overflow to disk = true, using 100000 records.
     * 35 seconds v1.38 DiskStore
     * 26 seconds v1.42 DiskStore
     */
    public void testOverflowToDiskWithLargeNumberofCacheEntriesAndGets() throws Exception {

        //Set size so the second element overflows to disk.
        Cache cache = new Cache("test", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 500, 500, false, 60, null);
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

    /**
     * Runs out of memory at 5,099,999 elements with the standard 64MB VM size.
     * <p/>
     * The reason that it is not infinite is because of a small amount of memory used (about 12 bytes) used for
     * the disk store index in this case.
     * <p/>
     * Slow tests
     */
    public void xtestMaximumCacheEntriesIn64MBWithOverflowToDisk() throws Exception {

        Cache cache = new Cache("test", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 500, 500, false, 1, null);
        manager.addCache(cache);
        StopWatch stopWatch = new StopWatch();
        int i = 0;
        int j = 0;
        Integer index = null;
        try {
            for (; i < 100; i++) {
                for (j = 0; j < 100000; j++) {
                    index = new Integer(((1000000 * i) + j));
                    cache.put(new Element(index,
                            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                }
                //wait to write entries
                int size = cache.getSize();
                Thread.sleep(2000);
            }
            long elapsed = stopWatch.getElapsedTime();
            LOG.info("Elapsed time: " + elapsed / 1000);
            fail();
        } catch (OutOfMemoryError e) {
            LOG.info("Failed at " + index);
            if (JVMUtil.isJDK15()) {
                assertTrue(index.intValue() > 5000000);
            } else {
                assertTrue(index.intValue() > 4099000);
            }
        }
    }

    /**
     * Perf test used by Venkat Subramani
     * Get took 119s with Cache svn21
     * Get took 42s
     * The change was to stop adding DiskStore retrievals into the MemoryStore. This made sense when the only
     * policy was LRU. In the new version an Elment, once evicted from the MemoryStore, stays in the DiskStore
     * until expiry or removal. This avoids a lot of serialization overhead.
     * <p/>
     * Slow tests
     */
    public void xTestLargePutGetPerformanceWithOverflowToDisk() throws Exception {

        Cache cache = new Cache("test", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 500, 500, false, 10000, null);
        manager.addCache(cache);
        StopWatch stopWatch = new StopWatch();
        int i = 0;
        int j = 0;
        Integer index = null;
        for (; i < 5; i++) {
            for (j = 0; j < 100000; j++) {
                index = new Integer(((1000000 * i) + j));
                cache.put(new Element(index,
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
            }
            //wait to write entries
            Thread.sleep(2000);
        }
        long elapsed = stopWatch.getElapsedTime();
        long putTime = ((elapsed / 1000) - 10);
        LOG.info("Put Elapsed time: " + putTime);
        assertTrue(putTime < 8);

        //wait for Disk Store to finish spooling
        Thread.sleep(2000);
        Random random = new Random();
        StopWatch getStopWatch = new StopWatch();
        long getStart = stopWatch.getElapsedTime();

        for (int k = 0; k < 1000000; k++) {
            Integer key = new Integer(random.nextInt(500000));
            cache.get(key);
        }

        long getElapsedTime = getStopWatch.getElapsedTime();
        int time = (int) ((getElapsedTime - getStart) / 1000);
        LOG.info("Get Elapsed time: " + time);

        assertTrue(time < 200);
    }
}
