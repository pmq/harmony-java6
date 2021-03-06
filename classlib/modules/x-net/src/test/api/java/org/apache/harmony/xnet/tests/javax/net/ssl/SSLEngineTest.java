/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.harmony.xnet.tests.javax.net.ssl;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLEngineResult;

import junit.framework.TestCase;

/**
 * Tests for SSLEngine class
 * 
 */

public class SSLEngineTest extends TestCase {

    public static void main(String[] args) {
        junit.textui.TestRunner.run(SSLEngineTest.class);
    }

    /**
     * Test for <code>SSLEngine()</code> constructor Assertion: creates
     * SSLEngine object with null host and -1 port
     */
    public void test01() {
        SSLEngine e = new mySSLEngine();
        assertNull(e.getPeerHost());
        assertEquals(-1, e.getPeerPort());
        String[] suites = { "a", "b", "c" };
        e.setEnabledCipherSuites(suites);
        assertEquals(e.getEnabledCipherSuites().length, suites.length);
    }

    /**
     * Test for <code>SSLEngine(String host, int port)</code> constructor
     */
    public void test02() throws SSLException {
        int port = 1010;
        SSLEngine e = new mySSLEngine(null, port);
        assertNull(e.getPeerHost());
        assertEquals(e.getPeerPort(), port);
        try {
            e.beginHandshake();
        } catch (SSLException ex) {
        }
    }

    /**
     * Test for <code>SSLEngine(String host, int port)</code> constructor
     */
    public void test03() {
        String host = "new host";
        int port = 8080;
        SSLEngine e = new mySSLEngine(host, port);
        assertEquals(e.getPeerHost(), host);
        assertEquals(e.getPeerPort(), port);
        String[] suites = { "a", "b", "c" };
        e.setEnabledCipherSuites(suites);
        assertEquals(e.getEnabledCipherSuites().length, suites.length);
        e.setUseClientMode(true);
        assertTrue(e.getUseClientMode());
    }

    /**
     * Test for <code>wrap(ByteBuffer src, ByteBuffer dst)</code> method
     * Assertions: 
     * throws IllegalArgumentException when src or dst is null 
     * throws ReadOnlyBufferException when dst is ReadOnly byte buffer
     * 
     * Check that implementation behavior follows RI:
     * jdk 1.5 does not throw IllegalArgumentException when parameters are null
     * and does not throw ReadOnlyBufferException if dst is read only byte buffer
     */
    public void testWrap01() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer bbN = null;
        ByteBuffer bb = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine(host, port);

        e.wrap(bbN, bb);
        e.wrap(bb, bbN);

        ByteBuffer roBb = bb.asReadOnlyBuffer();
        assertTrue("Not read only byte buffer", roBb.isReadOnly());
        e.wrap(bb, roBb);

    }

    /**
     * Test for <code>wrap(ByteBuffer[] srcs, ByteBuffer dst)</code> method
     * 
     * Assertions: throws IllegalArgumentException when srcs or dst is null or
     * srcs contains null byte buffer; throws ReadOnlyBufferException when dst
     * is read only byte buffer
     * 
     * Check that implementation behavior follows RI:
     * jdk 1.5 does not throw IllegalArgumentException when dst is null or
     * if srcs contains null elements It does not throw ReadOnlyBufferException
     * for read only dst
     */
    public void testWrap02() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer[] bbNA = null;
        ByteBuffer[] bbA = { null, ByteBuffer.allocate(10), null };

        ByteBuffer bb = ByteBuffer.allocate(10);
        ByteBuffer bbN = null;
        SSLEngine e = new mySSLEngine(host, port);
        try {
            e.wrap(bbNA, bb);
            fail("IllegalArgumentException must be thrown for null srcs byte buffer array");
        } catch (IllegalArgumentException ex) {
        }

        e.wrap(bbA, bb);
        e.wrap(bbA, bbN);

        ByteBuffer roBb = bb.asReadOnlyBuffer();
        bbA[0] = ByteBuffer.allocate(100);
        bbA[2] = ByteBuffer.allocate(20);
        assertTrue("Not read only byte buffer", roBb.isReadOnly());

        e.wrap(bbA, roBb);

    }

    /**
     * Test for <code>wrap(ByteBuffer src, ByteBuffer dst)</code> and
     * <code>wrap(ByteBuffer[] srcs, ByteBuffer dst)</code> methods
     * 
     * Assertion: these methods throw SSLException
     */
    public void testWrap03() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer bbs = ByteBuffer.allocate(100);
        ByteBuffer bbd = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine1(host, port);
        try {
            e.wrap(bbs, bbd);
            fail("SSLException must be thrown");
        } catch (SSLException ex) {
        }
        SSLEngineResult res = e.wrap(bbd, bbs);
        assertEquals(10, res.bytesConsumed());
        assertEquals(20, res.bytesProduced());

        try {
            e.wrap(new ByteBuffer[] { bbs }, bbd);
            fail("SSLException must be thrown");
        } catch (SSLException ex) {
        }
        res = e.wrap(new ByteBuffer[] { bbd }, bbs);
        assertEquals(10, res.bytesConsumed());
        assertEquals(20, res.bytesProduced());
    }

    /**
     * Test for <code>wrap(ByteBuffer src, ByteBuffer dst)</code> method
     * 
     * Assertion: encodes a buffer data into network data.
     *  
     */
    public void testWrap04() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer bb = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine(host, port);
        
        SSLEngineResult res = e.wrap(bb, ByteBuffer.allocate(10));
        assertEquals(10, res.bytesConsumed());
        assertEquals(20, res.bytesProduced());
    }
    
    /**
     * Test for <code>wrap(ByteBuffer[] srcs, ByteBuffer dst)</code> method
     * 
     * Assertion: encodes datas from buffers into network data.
     */
    public void testWrap05() throws SSLException {
        String host = "new host";
        int port = 8080;

        ByteBuffer bb = ByteBuffer.allocate(10);
        ByteBuffer[] bbA = { ByteBuffer.allocate(5), ByteBuffer.allocate(10), ByteBuffer.allocate(5) };

        SSLEngine e = new mySSLEngine(host, port);

        SSLEngineResult res = e.wrap(bbA, bb);
        assertEquals(10, res.bytesConsumed());
        assertEquals(20, res.bytesProduced());
    }
    
    /**
     * Test for <code>unwrap(ByteBuffer src, ByteBuffer dst)</code> method
     * 
     * Assertions: 
     * throws IllegalArgumentException when src or dst is null
     * throws ReadOnlyBufferException when dst is read only byte buffer
     * 
     * Check that implementation behavior follows RI:
     * jdk 1.5 does not throw IllegalArgumentException when parameters are null
     * and does not throw ReadOnlyBufferException if dst is read only byte buffer
     */
    public void testUnwrap01() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer bbN = null;
        ByteBuffer bb = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine(host, port);

        e.unwrap(bbN, bb);
        e.unwrap(bb, bbN);

        ByteBuffer roBb = bb.asReadOnlyBuffer();
        assertTrue("Not read only byte buffer", roBb.isReadOnly());

        e.unwrap(bb, roBb);
    }

    /**
     * Test for <code>unwrap(ByteBuffer src, ByteBuffer[] dsts)</code> method
     * 
     * Assertions: throws IllegalArgumentException if parameters are null or
     * when dsts contains null elements throws ReadOnlyBufferException when dsts
     * contains read only elements
     * 
     * Check that implementation behavior follows RI:
     * jdk 1.5 does not throw IllegalArgumentException when src is null or
     * if dsts contains null elements It does not throw ReadOnlyBufferException
     * when dsts contains read only elements
     */
    public void testUnwrap02() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer[] bbNA = null;
        ByteBuffer[] bbA = { null, ByteBuffer.allocate(10), null };

        ByteBuffer bb = ByteBuffer.allocate(10);
        ByteBuffer bbN = null;
        SSLEngine e = new mySSLEngine(host, port);
        try {
            e.unwrap(bb, bbNA);
            fail("IllegalArgumentException must be thrown for null dsts byte buffer array");
        } catch (IllegalArgumentException ex) {
        }

        e.unwrap(bb, bbA);
        e.unwrap(bbN, bbA);

        ByteBuffer bb1 = ByteBuffer.allocate(100);
        ByteBuffer roBb = bb1.asReadOnlyBuffer();
        bbA[0] = bb1;
        bbA[2] = roBb;
        assertTrue("Not read only byte buffer", bbA[2].isReadOnly());

        e.unwrap(bb, bbA);

    }

    /**
     * Test for <code>unwrap(ByteBuffersrc, ByteBuffer dst)</code> and
     * <code>unwrap(ByteBuffer src, ByteBuffer[] dsts)</code> methods
     * 
     * Assertion: these methods throw SSLException
     */
    public void testUnwrap03() throws SSLException {
        ByteBuffer bbs = ByteBuffer.allocate(100);
        ByteBuffer bbd = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine1();
        try {
            e.unwrap(bbs, bbd);
            fail("SSLException must be thrown");
        } catch (SSLException ex) {
        }
        SSLEngineResult res = e.unwrap(bbd, bbs);
        assertEquals(1, res.bytesConsumed());
        assertEquals(2, res.bytesProduced());

        try {
            e.unwrap(bbs, new ByteBuffer[] { bbd });
            fail("SSLException must be thrown");
        } catch (SSLException ex) {
        }
        res = e.unwrap(bbd, new ByteBuffer[] { bbs });
        assertEquals(1, res.bytesConsumed());
        assertEquals(2, res.bytesProduced());
    }
    
    /**
     * Test for <code>unwrap(ByteBuffer src, ByteBuffer dst)</code> method
     * 
     * Assertion: decodes  network data into a data buffer. 
     */
    public void testUnwrap04() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer bb = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine(host, port);
        SSLEngineResult res = e.unwrap(bb, ByteBuffer.allocate(10));
        
        assertEquals(1, res.bytesConsumed());
        assertEquals(2, res.bytesProduced());
    }
    
    /**
     * Test for <code>unwrap(ByteBuffer src, ByteBuffer[] dsts)</code> method
     * 
     * Assertion: 
     * decode network data into data buffers.
     */
    public void testUnwrap05() throws SSLException {
        String host = "new host";
        int port = 8080;
        ByteBuffer[] bbA = { ByteBuffer.allocate(100), ByteBuffer.allocate(10), ByteBuffer.allocate(100) };

        ByteBuffer bb = ByteBuffer.allocate(10);
        SSLEngine e = new mySSLEngine(host, port);

        SSLEngineResult res = e.unwrap(bb, bbA);
        assertEquals(1, res.bytesConsumed());
        assertEquals(2, res.bytesProduced());
    }
}

/*
 * Additional class for verification SSLEngine constructors
 */

class mySSLEngine extends SSLEngine {

    private boolean useClientMode;

    private boolean needClientAuth;

    private boolean enableSessionCreation;

    private boolean wantClientAuth;

    private String[] enabledProtocols;

    private String[] enabledCipherSuites;

    public mySSLEngine() {
        super();
    }

    protected mySSLEngine(String host, int port) {
        super(host, port);
    }

    @Override
    public void beginHandshake() throws SSLException {
        String host = super.getPeerHost();
        if ((host == null) || (host.length() == 0)) {
            throw new SSLException("");
        }
    }

    @Override
    public void closeInbound() throws SSLException {
    }

    @Override
    public void closeOutbound() {
    }

    @Override
    public Runnable getDelegatedTask() {
        return null;
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return enabledCipherSuites;
    }

    @Override
    public String[] getEnabledProtocols() {
        return enabledProtocols;
    }

    @Override
    public boolean getEnableSessionCreation() {
        return enableSessionCreation;
    }

    @Override
    public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
        return SSLEngineResult.HandshakeStatus.FINISHED;
    }

    @Override
    public boolean getNeedClientAuth() {
        return needClientAuth;
    }

    @Override
    public SSLSession getSession() {
        return null;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return new String[0];
    }

    @Override
    public String[] getSupportedProtocols() {
        return new String[0];
    }

    @Override
    public boolean getUseClientMode() {
        return useClientMode;
    }

    @Override
    public boolean getWantClientAuth() {
        return wantClientAuth;
    }

    @Override
    public boolean isInboundDone() {
        return false;
    }

    @Override
    public boolean isOutboundDone() {
        return false;
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {
        enabledCipherSuites = suites;
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {
        enabledProtocols = protocols;
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {
        enableSessionCreation = flag;
    }

    @Override
    public void setNeedClientAuth(boolean need) {
        needClientAuth = need;
    }

    @Override
    public void setUseClientMode(boolean mode) {
        useClientMode = mode;
    }

    @Override
    public void setWantClientAuth(boolean want) {
        wantClientAuth = want;
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts,
            int offset, int length) throws SSLException {
        return new SSLEngineResult(SSLEngineResult.Status.OK,
                SSLEngineResult.HandshakeStatus.FINISHED, 1, 2);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length,
            ByteBuffer dst) throws SSLException {
        return new SSLEngineResult(SSLEngineResult.Status.OK,
                SSLEngineResult.HandshakeStatus.FINISHED, 10, 20);
    }

    @Override
    public SSLParameters getSSLParameters() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setSSLParameters(SSLParameters sslP) {
        // TODO Auto-generated method stub

    }
}

class mySSLEngine1 extends mySSLEngine {

    public mySSLEngine1() {
    }

    public mySSLEngine1(String host, int port) {
        super(host, port);
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer dst)
            throws SSLException {
        if (src.limit() > dst.limit()) {
            throw new SSLException("incorrect limits");
        }
        return super.unwrap(src, dst);
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts)
            throws SSLException {
        if (src.limit() > dsts[0].limit()) {
            throw new SSLException("incorrect limits");
        }
        return super.unwrap(src, dsts);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] srcs, ByteBuffer dst)
            throws SSLException {
        if (srcs[0].limit() > dst.limit()) {
            throw new SSLException("incorrect limits");
        }
        return super.wrap(srcs, dst);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer src, ByteBuffer dst)
            throws SSLException {
        if (src.limit() > dst.limit()) {
            throw new SSLException("incorrect limits");
        }
        return super.wrap(src, dst);
    }

}