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


package net.sf.ehcache.store;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.event.RegisteredEventListeners;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A disk store implementation.
 * <p/>
 * As of ehcache-1.2 (v1.41 of this file) DiskStore has been changed to a mix of finer grained locking using synchronized collections
 * and synchronizing on the whole instance, as was the case with earlier versions.
 *
 * @author Adam Murdoch
 * @author Greg Luck
 * @author patches contributed: Ben Houston
 * @version $Id$
 */
public class DiskStore implements Store {
    private static final Log LOG = LogFactory.getLog(DiskStore.class.getName());
    private static final int MS_PER_SECOND = 1000;
    private static final int SPOOL_THREAD_INTERVAL = 200;
    private long expiryThreadInterval;

    private final String name;
    private boolean active;
    private RandomAccessFile randomAccessFile;

    private Map diskElements = Collections.synchronizedMap(new HashMap());
    private List freeSpace = Collections.synchronizedList(new ArrayList());
    private final Map spool = Collections.synchronizedMap(new HashMap());


    private Thread spoolThread;
    private Thread expiryThread;

    private Cache cache;

    /**
     * If persistent, the disk file will be kept
     * and reused on next startup. In addition the
     * memory store will flush all contents to spool,
     * and spool will flush all to disk.
     */
    private final boolean persistent;

    private final String diskPath;

    private File dataFile;

    /**
     * Used to persist elements
     */
    private File indexFile;

    private Status status;

    /**
     * The size in bytes of the disk elements
     */
    private long totalSize;

    /**
     * Creates a disk store.
     *
     * @param cache    the {@link net.sf.ehcache.Cache} that the store is part of
     * @param diskPath the directory in which to create data and index files
     */
    public DiskStore(Cache cache, String diskPath) {
        status = Status.STATUS_UNINITIALISED;
        this.cache = cache;
        name = cache.getName();
        this.diskPath = diskPath;
        this.expiryThreadInterval = cache.getDiskExpiryThreadIntervalSeconds();
        this.persistent = cache.isDiskPersistent();

        try {
            initialiseFiles();

            active = true;

            // Always start up the spool thread
            spoolThread = new SpoolThread();
            spoolThread.start();

            // Start up the expiry thread if not eternal
            if (!cache.isEternal()) {
                expiryThread = new ExpiryThread();
                expiryThread.start();
            }

            status = Status.STATUS_ALIVE;
        } catch (final Exception e) {
            // Cleanup on error
            dispose();
            LOG.error(name + "Cache: Could not create disk store. Initial cause was " + e.getMessage(), e);
        }
    }


    private void initialiseFiles() throws Exception {
        // Make sure the cache directory exists
        final File diskDir = new File(diskPath);
        if (diskDir.exists() && !diskDir.isDirectory()) {
            throw new Exception("Store directory \"" + diskDir.getCanonicalPath() + "\" exists and is not a directory.");
        }
        if (!diskDir.exists() && !diskDir.mkdirs()) {
            throw new Exception("Could not create cache directory \"" + diskDir.getCanonicalPath() + "\".");
        }

        dataFile = new File(diskDir, getDataFileName());
        indexFile = new File(diskDir, getIndexFileName());

        deleteIndexIfNoData();

        if (persistent) {
            if (!readIndex()) {
                LOG.debug("Index file dirty or empty. Deleting data file " + getDataFileName());
                dataFile.delete();
            }
        } else {
            LOG.debug("Deleting data file " + getDataFileName());
            dataFile.delete();
            indexFile = null;
        }

        // Open the data file as random access. The dataFile is created if necessary.
        randomAccessFile = new RandomAccessFile(dataFile, "rw");
    }

    private void deleteIndexIfNoData() {
        boolean dataFileExists = dataFile.exists();
        boolean indexFileExists = indexFile.exists();
        if (!dataFileExists && indexFileExists) {
            LOG.debug("Matching data file missing for index file. Deleting index file " + getIndexFileName());
            indexFile.delete();
        }
    }

    /**
     * Asserts that the store is active.
     */
    private void checkActive() throws CacheException {
        if (!active) {
            throw new CacheException(name + " Cache: The Disk store is not active.");
        }
    }

    /**
     * Gets an {@link Element} from the Disk Store.
     *
     * @return The element
     */
    public final synchronized Element get(final Object key) {
        try {
            checkActive();

            // Check in the spool.  Remove if present
            Element element;
            synchronized (spool) {
                element = (Element) spool.remove(key);
            }
            if (element != null) {
                element.updateAccessStatistics();
                return element;
            }

            // Check if the element is on disk
            final DiskElement diskElement = (DiskElement) diskElements.get(key);
            if (diskElement == null) {
                // Not on disk
                return null;
            }

            element = loadElementFromDiskElement(diskElement);
            element.updateAccessStatistics();
            return element;
        } catch (Exception exception) {
            LOG.error(name + "Cache: Could not read disk store element for key " + key + ". Error was "
                    + exception.getMessage(), exception);
        }
        return null;
    }

    /**
     * An unsynchronized and very low cost check to see if a key is in the Store. No check is made to see if the Element is expired.
     *
     * @param key The Element key
     * @return true if found. If this method return false, it means that an Element with the given key is definitely not in the MemoryStore.
     *         If it returns true, there is an Element there. An attempt to get it may return null if the Element has expired.
     */
    public final boolean containsKey(Object key) {
        return diskElements.containsKey(key) || spool.containsKey(key);
    }

    private Element loadElementFromDiskElement(DiskElement diskElement) throws IOException, ClassNotFoundException {
        Element element;
        // Load the element
        randomAccessFile.seek(diskElement.position);
        final byte[] buffer = new byte[diskElement.payloadSize];
        randomAccessFile.readFully(buffer);
        final ByteArrayInputStream instr = new ByteArrayInputStream(buffer);
        final ObjectInputStream objstr = new ObjectInputStream(instr) {
            protected Class resolveClass(ObjectStreamClass clazz) throws ClassNotFoundException {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                return Class.forName(clazz.getName(), false, classLoader);
            }
        };
        element = (Element) objstr.readObject();
        objstr.close();
        return element;
    }

    /**
     * Gets an {@link Element} from the Disk Store, without updating statistics
     *
     * @return The element
     */
    public final synchronized Element getQuiet(final Object key) {
        try {
            checkActive();

            // Check in the spool.  Remove if present
            Element element;
            synchronized (spool) {
                element = (Element) spool.remove(key);
            }
            if (element != null) {
                //element.updateAccessStatistics(); Don't update statistics
                return element;
            }

            // Check if the element is on disk
            final DiskElement diskElement = (DiskElement) diskElements.get(key);
            if (diskElement == null) {
                // Not on disk
                return null;
            }

            element = loadElementFromDiskElement(diskElement);
            //element.updateAccessStatistics(); Don't update statistics
            return element;
        } catch (Exception e) {
            LOG.error(name + "Cache: Could not read disk store element for key " + key
                    + ". Initial cause was " + e.getMessage(), e);
        }
        return null;
    }


    /**
     * Gets an Array of the keys for all elements in the disk store.
     *
     * @return An Object[] of {@link Serializable} keys
     * @noinspection SynchronizeOnNonFinalField
     */
    public final synchronized Object[] getKeyArray() {
        Set elementKeySet;
        synchronized (diskElements) {
            elementKeySet = diskElements.keySet();
        }
        Set spoolKeySet;
        synchronized (spool) {
            spoolKeySet = spool.keySet();
        }
        Set allKeysSet = new HashSet(elementKeySet.size() + spoolKeySet.size());
        allKeysSet.addAll(elementKeySet);
        allKeysSet.addAll(spoolKeySet);
        return allKeysSet.toArray();
    }

    /**
     * Returns the current store size.
     * @noinspection SynchronizeOnNonFinalField
     */
    public final synchronized int getSize() {
        try {
            checkActive();
            int spoolSize;
            synchronized (spool) {
                spoolSize = spool.size();
            }
            int diskSize;
            synchronized (diskElements) {
                diskSize = diskElements.size();
            }
            return spoolSize + diskSize;
        } catch (Exception e) {
            LOG.error(name + "Cache: Could not determine size of disk store.. Initial cause was " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Returns the store status.
     */
    public final Status getStatus() {
        return status;
    }

    /**
     * Puts an element into the disk store.
     * <p/>
     * This method is not synchronized. It is however threadsafe. It uses fine-grained
     * synchronization on the spool
     */
    public final void put(final Element element) {
        try {
            checkActive();

            // Spool the element
            if (spoolThread.isAlive()) {
                synchronized (spool) {
                    spool.put(element.getObjectKey(), element);
                }
            } else {
                LOG.error(name + "Cache: Elements cannot be written to disk store because the" +
                        " spool thread has died.");
                synchronized (spool) {
                    spool.clear();
                }
            }

        } catch (Exception e) {
            LOG.error(name + "Cache: Could not write disk store element for " + element.getObjectKey()
                    + ". Initial cause was " + e.getMessage(), e);
        }
    }

    /**
     * Removes an item from the disk store.
     * @noinspection SynchronizeOnNonFinalField
     */
    public final synchronized Element remove(final Object key) {
        Element element;
        try {
            checkActive();

            // Remove the entry from the spool
            synchronized (spool) {
                element = (Element) spool.remove(key);
            }

            // Remove the entry from the file. Could be in both places.
            synchronized (diskElements) {
                final DiskElement diskElement = (DiskElement) diskElements.remove(key);
                if (diskElement != null) {
                    element = loadElementFromDiskElement(diskElement);
                    freeBlock(diskElement);
                }
            }
        } catch (Exception exception) {
            String message = name + "Cache: Could not remove disk store entry for " + key
                    + ". Error was " + exception.getMessage();
            LOG.error(message, exception);
            throw new CacheException(message);
        }
        return element;
    }

    /**
     * Marks a block as free.
     */
    private void freeBlock(final DiskElement element) {
        totalSize -= element.payloadSize;
        element.payloadSize = 0;
        freeSpace.add(element);
    }

    /**
     * Remove all of the elements from the store.
     * <p/>
     * If there are registered <code>CacheEventListener</code>s they are notified of the expiry or removal
     * of the <code>Element</code> as each is removed.
     */
    public final synchronized void removeAll() {
        try {
            checkActive();

            notifyingRemoveAll();

            // Ditch all the elements, and truncate the file
            spool.clear();
            diskElements.clear();
            freeSpace.clear();
            totalSize = 0;
            randomAccessFile.setLength(0);
            if (persistent) {
                indexFile.delete();
                indexFile.createNewFile();
            }
        } catch (Exception e) {
            // Clean up
            LOG.error(name + " Cache: Could not rebuild disk store. Initial cause was " + e.getMessage(), e);
            dispose();
        }
    }

    private void notifyingRemoveAll() {
        RegisteredEventListeners listeners = cache.getCacheEventNotificationService();
        if (!listeners.getCacheEventListeners().isEmpty()) {
            Object[] keys = getKeyArray();
            for (int i = 0; i < keys.length; i++) {
                Serializable key = (Serializable) keys[i];
                Element element = remove(key);
                if (cache.isExpired(element)) {
                    listeners.notifyElementExpiry(element, false);
                } else {
                    listeners.notifyElementRemoved(element, false);
                }
            }
        }
    }

    /**
     * Shuts down the disk store in preparation for cache shutdown
     * <p/>
     * If a VM crash happens, the shutdown hook will not run. The data file and the index file
     * will be out of synchronisation. At initialisation we always delete the index file
     * after we have read the elements, so that it has a zero length. On a dirty restart, it still will have
     * and the data file will automatically be deleted, thus preserving safety.
     */
    public final synchronized void dispose() {

        if (!active) {
            return;
        }

        // Close the cache
        try {
            if (expiryThread != null) {
                expiryThread.interrupt();
            }

            flush();

            //Clear in-memory data structures
            spool.clear();
            diskElements.clear();
            freeSpace.clear();
            if (randomAccessFile != null) {
                randomAccessFile.close();
            }
            if (!persistent) {
                LOG.debug("Deleting file " + dataFile.getName());
                dataFile.delete();
            }
        } catch (Exception e) {
            LOG.error(name + "Cache: Could not shut down disk cache. Initial cause was " + e.getMessage(), e);
        } finally {
            active = false;
            randomAccessFile = null;
            notifyAll();

            //release reference to cache
            cache = null;
        }
    }

    /**
     * Flush the spool if persistent, so we don't lose any data.
     *
     * @throws IOException
     */
    public final void flush() throws IOException {
        if (persistent) {
            flushSpool();
            writeIndex();
        }
    }

    /**
     * Whether there are any elements waiting to be spooled to disk.
     *
     * @return false if there are elements waiting, otherwise true
     */
    public final synchronized boolean isSpoolEmpty() {
        return (!active || spool.size() == 0);
    }

    /**
     * Main method for the spool thread.
     * <p/>
     * Note that the spool thread locks the cache for the entire time it is writing elements to the disk.
     */
    private void spoolThreadMain() {
        while (true) {
            // Wait for elements in the spool
            while (active && spool != null && spool.size() == 0) {
                try {
                    Thread.sleep(SPOOL_THREAD_INTERVAL);
                } catch (InterruptedException e) {
                    LOG.debug("Spool Thread interrupted.");
                    return;
                }
            }


            if (!active) {
                return;
            }


            if (spool != null && spool.size() != 0) {
                // Write elements to disk
                try {
                    flushSpool();
                } catch (Throwable e) {
                    LOG.error(name + "Cache: Could not flush elements to disk due to " + e.getMessage() + ". Continuing...", e);
                }
            }
        }
    }


    /**
     * Flushes all spooled elements to disk.
     * Note that the cache is locked for the entire time that the spool is being flushed.
     * @noinspection SynchronizeOnNonFinalField
     */
    private synchronized void flushSpool() throws IOException {
        // Write elements to the DB
        List spoolCopy = new ArrayList(spool.size());
        synchronized (spool) {
            Collection values = spool.values();
            for (Iterator iterator = values.iterator(); iterator.hasNext();) {
                Element element = (Element) iterator.next();
                spoolCopy.add(element);
            }
            spool.clear();
        }
        for (int i = 0; i < spoolCopy.size(); i++) {
            final Element element = (Element) spoolCopy.get(i);
            if (element == null) {
                continue;
            }
            final Serializable key = (Serializable) element.getObjectKey();

            // Remove the old entry, if any
            final DiskElement oldBlock;
            synchronized (diskElements) {
                oldBlock = (DiskElement) diskElements.remove(key);
            }
            if (oldBlock != null) {
                freeBlock(oldBlock);
            }

            final byte[] buffer;
            try {

                // Serialise the entry
                final ByteArrayOutputStream outstr = new ByteArrayOutputStream();
                final ObjectOutputStream objstr = new ObjectOutputStream(outstr);
                objstr.writeObject(element);
                objstr.close();
                buffer = outstr.toByteArray();

            } catch (Exception e) {
                // Catch any exception that occurs during serialization
                LOG.error(name + "Cache: Failed to write element to disk '" + element.getObjectKey()
                        + "'. Initial cause was " + e.getMessage(), e);

                // Don't write this element to disk but move on to the next
                continue;
            }

            // Check for a free block
            DiskElement diskElement = findFreeBlock(buffer.length);
            if (diskElement == null) {
                diskElement = new DiskElement();
                diskElement.position = randomAccessFile.length();
                diskElement.blockSize = buffer.length;
            }

            // Write the record
            randomAccessFile.seek(diskElement.position);
            randomAccessFile.write(buffer);

            if (cache.isEternal()) {
                // Never expires
                diskElement.expiryTime = Long.MAX_VALUE;
            } else {
                // Calculate expiry time
                long timeToLive = element.getCreationTime() + cache.getTimeToLiveSeconds() * MS_PER_SECOND;
                long timeToIdle = element.getLastAccessTime() + cache.getTimeToIdleSeconds() * MS_PER_SECOND;
                diskElement.expiryTime = Math.max(timeToLive, timeToIdle);
            }

            // Add to index, update stats
            diskElement.payloadSize = buffer.length;
            totalSize += buffer.length;
            synchronized (diskElements) {
                diskElements.put(key, diskElement);
            }
        }
    }

    /**
     * Writes the Index to disk on shutdown
     * <p/>
     * The index consists of the elements Map and the freeSpace List
     * <p/>
     * Note that the cache is locked for the entire time that the index is being written
     */
    private synchronized void writeIndex() throws IOException {

        ObjectOutputStream objectOutputStream = null;
        try {
            FileOutputStream fout = new FileOutputStream(indexFile);
            objectOutputStream = new ObjectOutputStream(fout);
            objectOutputStream.writeObject(diskElements);
            objectOutputStream.writeObject(freeSpace);
        } finally {
            if (objectOutputStream != null) {
                objectOutputStream.close();
            }
        }
    }

    /**
     * Reads Index to disk on startup.
     * <p/>
     * if the index file does not exist, it creates a new one.
     * <p/>
     * Note that the cache is locked for the entire time that the index is being written
     *
     * @return True if the index was read successfully, false otherwise
     */
    private synchronized boolean readIndex() throws IOException {
        ObjectInputStream objectInputStream = null;
        FileInputStream fin = null;
        boolean success = false;
        if (indexFile.exists()) {
            try {
                fin = new FileInputStream(indexFile);
                objectInputStream = new ObjectInputStream(fin);
                diskElements = (Map) objectInputStream.readObject();
                freeSpace = (List) objectInputStream.readObject();
                success = true;
            } catch (StreamCorruptedException e) {
                LOG.error("Corrupt index file. Creating new index.");
            } catch (IOException e) {
                //normal when creating the cache for the first time
                LOG.debug("IOException reading index. Creating new index. ");
            } catch (ClassNotFoundException e) {
                LOG.error("Class loading problem reading index. Creating new index. Initial cause was " + e.getMessage(), e);
            } finally {
                try {
                    if (objectInputStream != null) {
                        objectInputStream.close();
                    } else if (fin != null) {
                        fin.close();
                    }
                } catch (IOException e) {
                    LOG.error("Problem closing the index file.");
                }

                //Always zero out file. That way if there is a dirty shutdown, the file will still be empty
                //the next time we start up and readIndex will automatically fail.
                //If there was a problem reading the index this time we also want to zero it out.
                createNewIndexFile();
            }
        } else {
            createNewIndexFile();
        }

        //Return the success flag
        return success;

    }

    private void createNewIndexFile() throws IOException {
        if (indexFile.exists()) {
            indexFile.delete();
            LOG.debug("Index file " + indexFile + " deleted.");
        }
        if (indexFile.createNewFile()) {
            LOG.debug("Index file " + indexFile + " created successfully");
        } else {
            throw new IOException("Index file " + indexFile + " could not created.");
        }
    }

    /**
     * The main method for the expiry thread.
     * <p/>
     * Will run while the cache is active. After the cache shuts down
     * it will take the expiryThreadInterval to wake up and complete.
     */
    private void expiryThreadMain() {
        long expiryThreadIntervalMillis = expiryThreadInterval * MS_PER_SECOND;
        try {
            while (active) {
                Thread.sleep(expiryThreadIntervalMillis);

                //Expire the elements
                expireElements();
            }
        } catch (InterruptedException e) {
            // Bail on interruption
            if (LOG.isDebugEnabled()) {
                LOG.debug(name + "Cache: Expiry thread interrupted on Disk Store.");
            }
        }
    }

    /**
     * Removes expired elements.
     * Note that the cache is locked for the entire time that elements are being expired.
     * @noinspection SynchronizeOnNonFinalField
     */
    private void expireElements() {
        final long now = System.currentTimeMillis();

        // Clean up the spool
        synchronized (spool) {
            for (Iterator iterator = spool.values().iterator(); iterator.hasNext();) {
                final Element element = (Element) iterator.next();
                if (cache.isExpired(element)) {
                    // An expired element
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(name + "Cache: Removing expired spool element " + element.getObjectKey());
                    }
                    iterator.remove();
                    notifyExpiryListeners(element);
                }
            }
        }

        synchronized (diskElements) {
            // Clean up disk elements
            for (Iterator iterator = diskElements.entrySet().iterator(); iterator.hasNext();) {
                final Map.Entry entry = (Map.Entry) iterator.next();
                final DiskElement diskElement = (DiskElement) entry.getValue();
                if (now >= diskElement.expiryTime) {
                    // An expired element
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(name + "Cache: Removing expired spool element " + entry.getKey() + " from Disk Store");
                    }
                    iterator.remove();
                    Element element = null;
                    try {
                        element = loadElementFromDiskElement(diskElement);
                    } catch (Exception exception) {
                        LOG.error(name + "Cache: Could not remove disk store entry for " + entry.getKey()
                                + ". Error was " + exception.getMessage(), exception);
                    }
                    freeBlock(diskElement);
                    notifyExpiryListeners(element);
                }
            }
        }
    }

    /**
     * It is enough that an element is expiring here. Notify even though there might be another
     * element with the same key elsewhere in the stores.
     *
     * @param element
     */
    private void notifyExpiryListeners(Element element) {
        cache.getCacheEventNotificationService().notifyElementExpiry(element, false);
    }

    /**
     * Allocates a free block.
     */
    private DiskElement findFreeBlock(final int length) {
        for (int i = 0; i < freeSpace.size(); i++) {
            final DiskElement element = (DiskElement) freeSpace.get(i);
            if (element.blockSize >= length) {
                freeSpace.remove(i);
                return element;
            }
        }
        return null;
    }

    /**
     * Returns a {@link String} representation of the {@link DiskStore}
     */
    public final String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("[ dataFile = ").append(dataFile.getAbsolutePath())
                .append(", active=").append(active)
                .append(", totalSize=").append(totalSize)
                .append(", status=").append(status)
                .append(", expiryThreadInterval = ").append(expiryThreadInterval)
                .append(" ]");
        return sb.toString();
    }


    /**
     * A reference to an on-disk elements.
     * @noinspection SerializableHasSerializationMethods
     */
    private static final class DiskElement implements Serializable {

        private static final long serialVersionUID = -717310932566592289L;

        /**
         * the file pointer
         */
        private long position;

        /**
         * The size used for data.
         */
        private int payloadSize;

        /**
         * the size of this element.
         */
        private int blockSize;

        /**
         * The expiry time in milliseconds
         */
        private long expiryTime;

    }

    /**
     * A background daemon thread that writes objects to the file.
     */
    private final class SpoolThread extends Thread {
        public SpoolThread() {
            super("Store " + name + " Spool Thread");
            setDaemon(true);
            setPriority(2);
        }

        /**
         * Main thread method.
         */
        public final void run() {
            spoolThreadMain();
        }
    }

    /**
     * A background daemon thread that removes expired objects.
     */
    private final class ExpiryThread extends Thread {
        public ExpiryThread() {
            super("Store " + name + " Expiry Thread");
            setDaemon(true);
            setPriority(1);
        }

        /**
         * Main thread method.
         */
        public final void run() {
            expiryThreadMain();
        }
    }

    /**
     * @return the total size of the data file and the index file, in bytes.
     */
    public final long getTotalFileSize() {
        return getDataFileSize() + getIndexFileSize();
    }

    /**
     * @return the size of the data file in bytes.
     */
    public final long getDataFileSize() {
        return dataFile.length();
    }

    /**
     * The design of the layout on the data file means that there will be small gaps created when DiskElements
     * are reused.
     *
     * @return the sparseness, measured as the percentage of space in the Data File not used for holding data
     */
    public final float calculateDataFileSparseness() {
        return 1 - ((float) getUsedDataSize() / (float) getDataFileSize());
    }

    /**
     * When elements are deleted, spaces are left in the file. These spaces are tracked and are reused
     * when new elements need to be written.
     * <p/>
     * This method indicates the actual size used for data, excluding holes. It can be compared with
     * {@link #getDataFileSize()} as a measure of fragmentation.
     */
    public final long getUsedDataSize() {
        return totalSize;
    }

    /**
     * @return the size of the index file, in bytes.
     */
    public final long getIndexFileSize() {
        if (indexFile == null) {
            return 0;
        } else {
            return indexFile.length();
        }
    }

    /**
     * @return the file name of the data file where the disk store stores data, without any path information.
     */
    public final String getDataFileName() {
        return name + ".data";
    }

    /**
     * @return the disk path, which will be dependent on the operating system
     */
    public final String getDataFilePath() {
        return diskPath;
    }

    /**
     * @return the file name of the index file, which maintains a record of elements and their addresses
     *         on the data file, without any path information.
     */
    public final String getIndexFileName() {
        return name + ".index";
    }


    /**
     * The expiry thread is started provided the cache is not eternal
     * <p/>
     * If started it will continue to run until the {@link #dispose()} method is called,
     * at which time it should be interrupted and then die.
     *
     * @return true if an expiryThread was created and is still alive.
     */
    public final boolean isExpiryThreadAlive() {
        if (expiryThread == null) {
            return false;
        } else {
            return expiryThread.isAlive();
        }
    }

}
