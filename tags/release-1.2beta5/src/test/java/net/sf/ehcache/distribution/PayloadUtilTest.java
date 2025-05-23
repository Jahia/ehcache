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
package net.sf.ehcache.distribution;

import junit.framework.TestCase;
import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;

/**
 *
 * Note these tests need a live network interface running in multicast mode to work
 *
 * @author <a href="mailto:gluck@thoughtworks.com">Greg Luck</a>
 * @version $Id: PayloadUtilTest.java,v 1.2 2006/03/09 23:36:47 gregluck Exp $
 *
 */
public class PayloadUtilTest extends TestCase {

    private static final Log LOG = LogFactory.getLog(PayloadUtilTest.class.getName());
    private CacheManager manager;

    /**
     * setup test
     * @throws Exception
     */
    protected void setUp() throws Exception {
        String fileName = AbstractCacheTest.TEST_CONFIG_DIR + "ehcache-big.xml";
        manager = new CacheManager(fileName);
    }

    /**
     * Shuts down the cachemanager
     * @throws Exception
     */
    protected void tearDown() throws Exception {
        manager.shutdown();
    }

    /**
     * The maximum Ethernet MTU is 1500 bytes.
     * <p/>
     * We want to be able to work with 100 caches
     */
    public void testMaximumDatagram() throws IOException {
        String payload = createReferenceString();

        final byte[] compressed = PayloadUtil.gzip(payload.getBytes());

        int length = compressed.length;
        LOG.info("gzipped size: " + length);
        assertTrue("Heartbeat too big for one Datagram " + length, length <= 1500);

    }

    /**
     * 376 µs per one gzipping each time.
     * .1 µs if we compare hashCodes on the String and only gzip as necessary.
     * @throws IOException
     * @throws InterruptedException
     */
    public void testGzipSanityAndPerformance() throws IOException, InterruptedException {
        String payload = createReferenceString();
        //warmup vm
        for (int i = 0; i < 10; i++) {
            byte[] compressed = PayloadUtil.gzip(payload.getBytes());
            //make sure we don't forget to close the stream
            assertTrue(compressed.length > 300);
            Thread.sleep(10);
        }
        int hashCode = payload.hashCode();
        StopWatch stopWatch = new StopWatch();
        for (int i = 0; i < 10000; i++) {
            if (hashCode != payload.hashCode()) {
                PayloadUtil.gzip(payload.getBytes());
            }
        }
        long elapsed = stopWatch.getElapsedTime();
        LOG.info("Gzip took " + elapsed / 10F + " µs");
    }

    /**
     * 169 µs per one.
     * @throws IOException
     * @throws InterruptedException
     */
    public void testUngzipPerformance() throws IOException, InterruptedException {
        String payload = createReferenceString();
        int length = payload.toCharArray().length;
        byte[] original = payload.getBytes();
        int byteLength = original.length;
        assertEquals(length, byteLength);
        byte[] compressed = PayloadUtil.gzip(original);
        //warmup vm
        for (int i = 0; i < 10; i++) {
            byte[] uncompressed = PayloadUtil.ungzip(compressed);
            uncompressed.hashCode();
            assertEquals(original.length, uncompressed.length);
            Thread.sleep(10);
        }
        StopWatch stopWatch = new StopWatch();
        for (int i = 0; i < 10000; i++) {
            PayloadUtil.ungzip(compressed);
        }
        long elapsed = stopWatch.getElapsedTime();
        LOG.info("Ungzip took " + elapsed / 10000F + " µs");
    }



    private String createReferenceString() {

        String[] names = manager.getCacheNames();
        String urlBase = "//localhost.localdomain:12000/";
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            buffer.append(urlBase);
            buffer.append(name);
            buffer.append("|");
        }
        String payload = buffer.toString();
        return payload;
    }



}
