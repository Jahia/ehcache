/**
 *  Copyright 2003-2007 Luck Consulting Pty Ltd
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

package net.sf.ehcache.constructs.web.filter;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import net.sf.ehcache.StopWatch;
import net.sf.ehcache.constructs.web.AbstractWebTest;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;

/**
 * @author Greg Luck
 * @version $Id$
 */
public class SpeedTest extends AbstractWebTest {
    private static final Log LOG = LogFactory.getLog(SpeedTest.class.getName());

    /**
     * Time to get 200 Cached Pages
     * StopWatch time: 947ms
     */
    public void testSpeedHttpClientNotCached() throws IOException {
        StopWatch stopWatch = new StopWatch();
        String url = "http://localhost:9080/Login.jsp";
        HttpClient httpClient = new HttpClient();
        HttpMethod httpMethod = new GetMethod(url);
        stopWatch.getElapsedTime();
        for (int i = 0; i < 200; i++) {
            httpClient.executeMethod(httpMethod);
            httpMethod.getResponseBodyAsStream();
        }
        long time = stopWatch.getElapsedTime();
        LOG.info("Time for 200 uncached page requests: " + time);
    }

    /**
     * Time to get 200 Cached Pages
     * StopWatch time: 1021ms
     */
    public void testSpeedHttpClientCached() throws IOException {
        StopWatch stopWatch = new StopWatch();
        String url = "http://localhost:9080/CachedPage.jsp";
        HttpClient httpClient = new HttpClient();
        HttpMethod httpMethod = new GetMethod(url);
        stopWatch.getElapsedTime();
        for (int i = 0; i < 200; i++) {
            httpClient.executeMethod(httpMethod);
            httpMethod.getResponseBodyAsStream();
        }
        long time = stopWatch.getElapsedTime();
        LOG.info("Time for 200 cached page requests: " + time);
    }

    /**
     * StopWatch time: 2251ms
     */
    public void testSpeedNoDom() throws Exception {
        StopWatch stopWatch = new StopWatch();
        final WebConversation conversation = createWebConversation(true);

        String requestUrl = "http://localhost:9080/CachedPage.jsp";
        stopWatch.getElapsedTime();
        for (int i = 0; i < 200; i++) {
            WebResponse response = conversation.getResponse(requestUrl);
            response.getText().indexOf("timestamp");
        }
        long time = stopWatch.getElapsedTime();
        LOG.info("Time for 200 cached page requests: " + time);

    }

    /**
     * Test time: 10.2s, 11.5    4.2 without JavaScript library
     * StopWatch time: 2686ms without JavaScript library
     * This test gets the CacheLogin twice so we need half as many
     */
    public void testSpeedDom() throws Exception {
        StopWatch stopWatch = new StopWatch();
        CachingFilterTest cachingFilterTest = new CachingFilterTest();
        cachingFilterTest.testCachedPageIsCached();
        stopWatch.getElapsedTime();
        for (int i = 0; i < 100; i++) {
            cachingFilterTest.testCachedPageIsCached();
        }
        long time = stopWatch.getElapsedTime();
        LOG.info("Time for 200 cached page requests: " + time);

    }


    /**
     * This is the last test run in the suite. It tells clover to flush.
     */
    public void testFlushClover() {
        ///CLOVER:FLUSH
    }
}
