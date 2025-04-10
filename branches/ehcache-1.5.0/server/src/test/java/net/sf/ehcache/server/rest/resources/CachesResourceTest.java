/**
 *  Copyright 2003-2008 Luck Consulting Pty Ltd
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

package net.sf.ehcache.server.rest.resources;

import net.sf.ehcache.server.HttpUtil;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import java.net.HttpURLConnection;


/**
 * Tests the REST web resource using the lightweight http container
 * <p/>
 * @author Greg Luck
 * @version $Id$
 */
public class CachesResourceTest {

    public static final Logger LOG = Logger.getLogger(CachesResourceTest.class.getName());

    @Test
    public void testGetCaches() throws IOException, ParserConfigurationException, SAXException {
        HttpURLConnection result = HttpUtil.get("http://localhost:8080/ehcache/rest/");
        assertEquals(200, result.getResponseCode());
    }



    /**
     * Stick in some text with MIME Type plain/text and make sure it comes back.
     * @throws java.io.IOException 
     * @throws javax.xml.parsers.ParserConfigurationException
     * @throws org.xml.sax.SAXException
     */
    @Test
    public void testPutElementPlain() throws IOException, ParserConfigurationException, SAXException {
        String originalString = "The rain in Spain falls mainly on the plain";
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(originalString.getBytes());

        HttpUtil.put("http://localhost:8080/ehcache/rest/sampleCache2/1", "text/plain", byteArrayInputStream);
        InputStream responseBody = HttpUtil.get("http://localhost:8080/ehcache/rest/sampleCache2/1").getInputStream();
        byte[] bytes = HttpUtil.inputStreamToBytes(responseBody);
        String plainText = new String(bytes);
        assertEquals(originalString, plainText);
    }

    @Test
    public void testPutElementXML() throws IOException, ParserConfigurationException, SAXException, XPathExpressionException, InterruptedException {
        //Use ehcache.xml as an example xml document.

        String xmlDocument = "<?xml version=\"1.0\"?>\n" +
                "<oldjoke>\n" +
                "<burns>Say <quote>goodnight</quote>,\n" +
                "Gracie.</burns>\n" +
                "<allen><quote>Goodnight, \n" +
                "Gracie.</quote></allen>\n" +
                "<applause/>\n" +
                "</oldjoke>";

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(xmlDocument.getBytes());


        HttpUtil.put("http://localhost:8080/ehcache/rest/sampleCache2/2", "text/xml", byteArrayInputStream);
        Thread.sleep(1000);
        LOG.info("About to do get");

        InputStream responseBody = HttpUtil.get("http://localhost:8080/ehcache/rest/sampleCache2/2").getInputStream();

        DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document document = documentBuilder.parse(responseBody);

        XPath xpath = XPathFactory.newInstance().newXPath();
        String expression = "/oldjoke/burns";
        Node node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);

        assertEquals("burns", node.getNodeName());


    }

}