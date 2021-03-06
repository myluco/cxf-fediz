/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.fediz.core.samlsso;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.apache.cxf.fediz.common.STSUtil;
import org.apache.cxf.fediz.common.SecurityTestUtil;
import org.apache.cxf.fediz.core.AbstractSAMLCallbackHandler;
import org.apache.cxf.fediz.core.AbstractSAMLCallbackHandler.MultiValue;
import org.apache.cxf.fediz.core.Claim;
import org.apache.cxf.fediz.core.ClaimTypes;
import org.apache.cxf.fediz.core.FedizConstants;
import org.apache.cxf.fediz.core.KeystoreCallbackHandler;
import org.apache.cxf.fediz.core.RequestState;
import org.apache.cxf.fediz.core.SAML1CallbackHandler;
import org.apache.cxf.fediz.core.SAML2CallbackHandler;
import org.apache.cxf.fediz.core.TokenValidator;
import org.apache.cxf.fediz.core.config.FedizConfigurator;
import org.apache.cxf.fediz.core.config.FedizContext;
import org.apache.cxf.fediz.core.config.Protocol;
import org.apache.cxf.fediz.core.exception.ProcessingException;
import org.apache.cxf.fediz.core.exception.ProcessingException.TYPE;
import org.apache.cxf.fediz.core.processor.FedizProcessor;
import org.apache.cxf.fediz.core.processor.FedizRequest;
import org.apache.cxf.fediz.core.processor.FedizResponse;
import org.apache.cxf.fediz.core.processor.SAMLProcessorImpl;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.crypto.CryptoFactory;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.OpenSAMLUtil;
import org.apache.wss4j.common.saml.SAMLCallback;
import org.apache.wss4j.common.saml.SAMLUtil;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.common.saml.bean.AudienceRestrictionBean;
import org.apache.wss4j.common.saml.bean.ConditionsBean;
import org.apache.wss4j.common.saml.bean.SubjectConfirmationDataBean;
import org.apache.wss4j.common.saml.builder.SAML2Constants;
import org.apache.wss4j.common.util.DOM2Writer;
import org.apache.wss4j.dom.WSConstants;
import org.apache.xml.security.utils.Base64;
import org.easymock.EasyMock;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;

import static org.junit.Assert.fail;

/**
 * Some tests for processing SAMLResponses using the SAMLProcessorImpl
 */
public class SAMLResponseTest {
    static final String TEST_USER = "alice";
    static final String TEST_REQUEST_URL = "https://localhost/fedizhelloworld/";
    static final String TEST_REQUEST_URI = "/fedizhelloworld";
    static final String TEST_IDP_ISSUER = "http://url_to_the_issuer";
    static final String TEST_CLIENT_ADDRESS = "https://127.0.0.1";
    
    private static final String CONFIG_FILE = "fediz_test_config_saml.xml";
    
    private static Crypto crypto;
    private static CallbackHandler cbPasswordHandler;
    private static FedizConfigurator configurator;
    private static DocumentBuilderFactory docBuilderFactory;
    
    static {
        docBuilderFactory = DocumentBuilderFactory.newInstance();
        docBuilderFactory.setNamespaceAware(true);
    }
    
    
    @BeforeClass
    public static void init() {
        try {
            crypto = CryptoFactory.getInstance("signature.properties");
            cbPasswordHandler = new KeystoreCallbackHandler();
            getFederationConfigurator();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Assert.assertNotNull(configurator);

    }
    
    @AfterClass
    public static void cleanup() {
        SecurityTestUtil.cleanup();
    }
    

    private static FedizConfigurator getFederationConfigurator() {
        if (configurator != null) {
            return configurator;
        }
        try {
            configurator = new FedizConfigurator();
            final URL resource = Thread.currentThread().getContextClassLoader()
                    .getResource(CONFIG_FILE);
            File f = new File(resource.toURI());
            configurator.loadConfig(f);
            return configurator;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Successfully validate a SAMLResponse
     */
    @org.junit.Test
    public void validateSAMLResponse() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        
        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        
        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());
        
        // Create SAML Response
        String responseStr = createSamlResponseStr(requestId);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);
        
        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);
        
        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles()
                            .size());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
        assertClaims(wfRes.getClaims(), FedizConstants.DEFAULT_ROLE_URI.toString());
    }
    
    /**
     * Validate SAMLResponse with a Response without an internal token parameter
     */
    @org.junit.Test
    public void validateResponseWithoutToken() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        
        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        
        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());
        
        Document doc = STSUtil.toSOAPPart(SAMLSSOTestUtils.SAMPLE_EMPTY_SAML_RESPONSE);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);
        
        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(DOM2Writer.nodeToString(doc));
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);
        
        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on missing security token in response");
        } catch (ProcessingException ex) {
            if (!TYPE.INVALID_REQUEST.equals(ex.getType())) {
                fail("Expected ProcessingException with BAD_REQUEST type");
            }
        }
    }
    
    @org.junit.Test
    public void testMissingRelayState() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        
        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     null,
                                                     System.currentTimeMillis());
        
        // Create SAML Response
        String responseStr = createSamlResponseStr(requestId);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);
        
        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);
        
        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on missing relay state in response");
        } catch (ProcessingException ex) {
            if (!TYPE.INVALID_REQUEST.equals(ex.getType())) {
                fail("Expected ProcessingException with BAD_REQUEST type");
            }
        }
    }
    
    /**
     * Validate SAML 1 token (this is not allowed / supported)
     */
    @org.junit.Test
    public void validateSAML1Token() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("CUSTOMROLEURI");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML1CallbackHandler callbackHandler = new SAML1CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on an unsupported token type in response");
        } catch (ProcessingException ex) {
            if (!TYPE.INVALID_REQUEST.equals(ex.getType())) {
                fail("Expected ProcessingException with BAD_REQUEST type");
            }
        }
    }
    
    /**
     * Validate SAML 2 token which doesn't include the role SAML attribute
     */
    @org.junit.Test
    public void validateSAML2TokenWithoutRoles() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        
        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        
        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());
        
        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        callbackHandler.setRoles(null);
        
        String responseStr = createSamlResponseStr(callbackHandler, requestId);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);
        
        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);
        
        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("No roles must be found", null, wfRes.getRoles());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
    }
    
    
    /**
     * Validate SAML 2 token where role information is provided
     * within another SAML attribute
     */
    @org.junit.Test
    public void validateSAML2TokenDifferentRoleURI() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("CUSTOMROLEURI");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        callbackHandler.setRoleAttributeName("http://schemas.mycompany.com/claims/role");

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);

        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles().size());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
        assertClaims(wfRes.getClaims(), callbackHandler.getRoleAttributeName());
    }
    
    /**
     * Validate SAML 2 token which includes role attribute
     * but RoleURI is not configured
     */
    @org.junit.Test
    public void validateSAML2TokenRoleURINotConfigured() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("CUSTOMROLEURI");
        config.getProtocol().setRoleURI(null);

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        callbackHandler.setRoleAttributeName("http://schemas.mycompany.com/claims/role");

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);

        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", null, wfRes.getRoles());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
    }
    
    
    /**
     * Validate SAML 2 token which includes the role attribute with 2 values
     * Roles are encoded as a multiple saml attributes with the same name
     */
    @org.junit.Test
    public void validateSAML2TokenRoleMultiAttributes() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        callbackHandler.setMultiValueType(MultiValue.MULTI_ATTR);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);

        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles().size());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
        assertClaims(wfRes.getClaims(), callbackHandler.getRoleAttributeName());
    }

    /**
     * Validate SAML 2 token which includes the role attribute with 2 values
     * Roles are encoded as a single saml attribute with encoded value
     */
    @org.junit.Test
    public void validateSAML2TokenRoleEncodedValue() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        Protocol protocol = config.getProtocol();
        protocol.setRoleDelimiter(",");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        callbackHandler.setMultiValueType(MultiValue.ENC_VALUE);
        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);

        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        System.out.println("ROLE: " + wfRes.getRoles().get(0));
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles().size());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
        assertClaims(wfRes.getClaims(), callbackHandler.getRoleAttributeName());
    }
    
    /**
     * Validate SAML 2 token which includes the role attribute with 2 values
     * The configured subject of the trusted issuer doesn't match with
     * the issuer of the SAML token
     */
    @org.junit.Test
    public void validateUnsignedSAML2Token() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);

        ConditionsBean cp = new ConditionsBean();
        AudienceRestrictionBean audienceRestriction = new AudienceRestrictionBean();
        audienceRestriction.getAudienceURIs().add(TEST_REQUEST_URL);
        cp.setAudienceRestrictions(Collections.singletonList(audienceRestriction));
        callbackHandler.setConditions(cp);
        
        // Subject Confirmation Data
        SubjectConfirmationDataBean subjectConfirmationData = new SubjectConfirmationDataBean();
        subjectConfirmationData.setAddress(TEST_CLIENT_ADDRESS);
        subjectConfirmationData.setInResponseTo(requestId);
        subjectConfirmationData.setNotAfter(new DateTime().plusMinutes(5));
        subjectConfirmationData.setRecipient(TEST_REQUEST_URL);
        callbackHandler.setSubjectConfirmationData(subjectConfirmationData);
        
        SAMLCallback samlCallback = new SAMLCallback();
        SAMLUtil.doSAMLCallback(callbackHandler, samlCallback);
        SamlAssertionWrapper assertion = new SamlAssertionWrapper(samlCallback);
        Element response = createSamlResponse(assertion, "mystskey", false, requestId);
        String responseStr = encodeResponse(response);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            Assert.fail("Processing must fail because of missing signature");
        } catch (ProcessingException ex) {
            if (!TYPE.TOKEN_NO_SIGNATURE.equals(ex.getType())) {
                fail("Expected ProcessingException with TOKEN_NO_SIGNATURE type");
            }
        }
    }
    
    /**
     * Validate SAML 2 token twice which causes an exception
     * due to replay attack
     */
    @org.junit.Test
    public void testReplayAttack() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        callbackHandler.setMultiValueType(MultiValue.ENC_VALUE);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL)).times(2);
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS).times(2);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);

        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        
        wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on a replay attack");
        } catch (ProcessingException ex) {
            if (!TYPE.TOKEN_REPLAY.equals(ex.getType())) {
                fail("Expected ProcessingException with INVALID_REQUEST type");
            }
        }
    }
    
    /**
     * Validate SAML 2 token which includes the role attribute with 2 values
     * The configured subject of the trusted issuer doesn't match with
     * the issuer of the SAML token
     */
    @org.junit.Test
    public void validateSAML2TokenSeveralCertStore() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT2");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles()
                            .size());
    }

    /**
     * Validate SAML 2 token which includes the role attribute with 2 values
     * The configured subject of the trusted issuer doesn't match with
     * the issuer of the SAML token
     */
    @org.junit.Test
    public void validateSAML2TokenSeveralCertStoreTrustedIssuer() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT3");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles()
                            .size());
    }
    
    /**
     * Validate SAML 2 token which is expired
     */
    @org.junit.Test
    public void validateSAML2TokenExpired() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        
        ConditionsBean cp = new ConditionsBean();
        DateTime currentTime = new DateTime();
        currentTime = currentTime.minusSeconds(60);
        cp.setNotAfter(currentTime);
        currentTime = new DateTime();
        currentTime = currentTime.minusSeconds(300);
        cp.setNotBefore(currentTime);
        AudienceRestrictionBean audienceRestriction = new AudienceRestrictionBean();
        audienceRestriction.getAudienceURIs().add(TEST_REQUEST_URL);
        cp.setAudienceRestrictions(Collections.singletonList(audienceRestriction));
        callbackHandler.setConditions(cp);
        
        // Subject Confirmation Data
        SubjectConfirmationDataBean subjectConfirmationData = new SubjectConfirmationDataBean();
        subjectConfirmationData.setAddress(TEST_CLIENT_ADDRESS);
        subjectConfirmationData.setInResponseTo(requestId);
        subjectConfirmationData.setNotAfter(new DateTime().plusMinutes(5));
        subjectConfirmationData.setRecipient(TEST_REQUEST_URL);
        callbackHandler.setSubjectConfirmationData(subjectConfirmationData);
        
        SAMLCallback samlCallback = new SAMLCallback();
        SAMLUtil.doSAMLCallback(callbackHandler, samlCallback);
        SamlAssertionWrapper assertion = new SamlAssertionWrapper(samlCallback);
        Element response = createSamlResponse(assertion, "mystskey", true, requestId);
        String responseStr = encodeResponse(response);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on expired SAML token");
        } catch (ProcessingException ex) {
            if (!TYPE.TOKEN_EXPIRED.equals(ex.getType())) {
                fail("Expected ProcessingException with TOKEN_EXPIRED type");
            }
        }
    }
    
    /**
     * Validate SAML 2 token which is not yet valid (in 30 seconds)
     * but within the maximum clock skew range (60 seconds)
     */
    @org.junit.Test
    public void validateSAML2TokenClockSkewRange() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        config.setMaximumClockSkew(BigInteger.valueOf(60));

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        
        ConditionsBean cp = new ConditionsBean();
        DateTime currentTime = new DateTime();
        currentTime = currentTime.plusSeconds(300);
        cp.setNotAfter(currentTime);
        currentTime = new DateTime();
        currentTime = currentTime.plusSeconds(30);
        cp.setNotBefore(currentTime);
        AudienceRestrictionBean audienceRestriction = new AudienceRestrictionBean();
        audienceRestriction.getAudienceURIs().add(TEST_REQUEST_URL);
        cp.setAudienceRestrictions(Collections.singletonList(audienceRestriction));
        callbackHandler.setConditions(cp);
        
        // Subject Confirmation Data
        SubjectConfirmationDataBean subjectConfirmationData = new SubjectConfirmationDataBean();
        subjectConfirmationData.setAddress(TEST_CLIENT_ADDRESS);
        subjectConfirmationData.setInResponseTo(requestId);
        subjectConfirmationData.setNotAfter(new DateTime().plusMinutes(5));
        subjectConfirmationData.setRecipient(TEST_REQUEST_URL);
        callbackHandler.setSubjectConfirmationData(subjectConfirmationData);
        
        SAMLCallback samlCallback = new SAMLCallback();
        SAMLUtil.doSAMLCallback(callbackHandler, samlCallback);
        SamlAssertionWrapper assertion = new SamlAssertionWrapper(samlCallback);
        Element response = createSamlResponse(assertion, "mystskey", true, requestId);
        String responseStr = encodeResponse(response);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles()
                            .size());
    }

    /**
     * "Validate" SAML 2 token with a custom token validator
     * If a validator is configured it precedes the SAMLTokenValidator as part of Fediz
     */
    @org.junit.Test
    public void validateSAML2TokenCustomValidator() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("CUSTTOK");
        Protocol protocol = config.getProtocol();
        List<TokenValidator> validators = protocol.getTokenValidators();
        Assert.assertEquals("Two validators must be found", 2, validators.size());
        Assert.assertEquals("First validator must be custom validator",
                            CustomValidator.class.getName(), validators.get(0).getClass().getName());

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
    }

    /**
     * "Validate" SAML 2 token with a custom token validator
     * If a validator is configured it precedes the SAMLTokenValidator as part of Fediz
     */
    @org.junit.Test
    public void validateSAML2TokenMaxClockSkewNotDefined() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("NOCLOCKSKEW");

        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");

        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());

        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);

        String responseStr = createSamlResponseStr(callbackHandler, requestId);

        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);

        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);

        FedizProcessor wfProc = new SAMLProcessorImpl();
        FedizResponse wfRes = wfProc.processRequest(wfReq, config);
        
        Assert.assertEquals("Principal name wrong", TEST_USER,
                            wfRes.getUsername());
        Assert.assertEquals("Issuer wrong", TEST_IDP_ISSUER, wfRes.getIssuer());
        Assert.assertEquals("Two roles must be found", 2, wfRes.getRoles()
                            .size());
        Assert.assertEquals("Audience wrong", TEST_REQUEST_URL, wfRes.getAudience());
    }
    
    @org.junit.Test
    public void testModifiedSignature() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("ROOT");
        
        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        
        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());
        
        // Create SAML Response
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        
        ConditionsBean cp = new ConditionsBean();
        AudienceRestrictionBean audienceRestriction = new AudienceRestrictionBean();
        audienceRestriction.getAudienceURIs().add(TEST_REQUEST_URL);
        cp.setAudienceRestrictions(Collections.singletonList(audienceRestriction));
        callbackHandler.setConditions(cp);
        
        // Subject Confirmation Data
        SubjectConfirmationDataBean subjectConfirmationData = new SubjectConfirmationDataBean();
        subjectConfirmationData.setAddress(TEST_CLIENT_ADDRESS);
        subjectConfirmationData.setInResponseTo(requestId);
        subjectConfirmationData.setNotAfter(new DateTime().plusMinutes(5));
        subjectConfirmationData.setRecipient(TEST_REQUEST_URL);
        callbackHandler.setSubjectConfirmationData(subjectConfirmationData);
        
        SAMLCallback samlCallback = new SAMLCallback();
        SAMLUtil.doSAMLCallback(callbackHandler, samlCallback);
        SamlAssertionWrapper assertion = new SamlAssertionWrapper(samlCallback);
        
        WSPasswordCallback[] cb = {
            new WSPasswordCallback("mystskey", WSPasswordCallback.SIGNATURE)
        };
        cbPasswordHandler.handle(cb);
        String password = cb[0].getPassword();

        assertion.signAssertion("mystskey", password, crypto, false);

        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        Document doc = docBuilder.newDocument();

        Status status =
            SAML2PResponseComponentBuilder.createStatus(
                "urn:oasis:names:tc:SAML:2.0:status:Success", null
            );
        Response response =
            SAML2PResponseComponentBuilder.createSAMLResponse(requestId, 
                                                              assertion.getIssuerString(), 
                                                              status);

        response.getAssertions().add(assertion.getSaml2());

        Element policyElement = OpenSAMLUtil.toDom(response, doc);
        doc.appendChild(policyElement);
        
        NodeList assertionNodes = 
            policyElement.getElementsByTagNameNS(WSConstants.SAML2_NS, "Assertion");
        Assert.assertTrue(assertionNodes != null && assertionNodes.getLength() == 1);
        
        Element assertionElement = (Element)assertionNodes.item(0);
        
        // Change IssueInstant attribute
        String issueInstance = assertionElement.getAttributeNS(null, "IssueInstant");
        DateTime issueDateTime = new DateTime(issueInstance, DateTimeZone.UTC);
        issueDateTime = issueDateTime.plusSeconds(1);
        assertionElement.setAttributeNS(null, "IssueInstant", issueDateTime.toString());

        String responseStr = encodeResponse(policyElement);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);
        
        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);
        
        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on modified Signature");
        } catch (ProcessingException ex) {
            // expected
        }
    }
    
    @org.junit.Test
    public void testTrustFailure() throws Exception {
        // Mock up a Request
        FedizContext config = getFederationConfigurator().getFedizContext("CLIENT_TRUST");
        
        String requestId = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        
        String relayState = URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8");
        RequestState requestState = new RequestState(TEST_REQUEST_URL,
                                                     TEST_IDP_ISSUER,
                                                     requestId,
                                                     TEST_REQUEST_URL,
                                                     (String)config.getProtocol().getIssuer(),
                                                     null,
                                                     relayState,
                                                     System.currentTimeMillis());
        
        // Create SAML Response
        String responseStr = createSamlResponseStr(requestId);
        
        HttpServletRequest req = EasyMock.createMock(HttpServletRequest.class);
        EasyMock.expect(req.getRequestURL()).andReturn(new StringBuffer(TEST_REQUEST_URL));
        EasyMock.expect(req.getRemoteAddr()).andReturn(TEST_CLIENT_ADDRESS);
        EasyMock.replay(req);
        
        FedizRequest wfReq = new FedizRequest();
        wfReq.setResponseToken(responseStr);
        wfReq.setState(relayState);
        wfReq.setRequest(req);
        wfReq.setRequestState(requestState);
        
        FedizProcessor wfProc = new SAMLProcessorImpl();
        try {
            wfProc.processRequest(wfReq, config);
            fail("Failure expected on non-trusted signing cert");
        } catch (ProcessingException ex) {
            // expected
        }
    }
    
    private String createSamlResponseStr(String requestId) throws Exception {
        // Create SAML Assertion
        SAML2CallbackHandler callbackHandler = new SAML2CallbackHandler();
        callbackHandler.setAlsoAddAuthnStatement(true);
        callbackHandler.setStatement(SAML2CallbackHandler.Statement.ATTR);
        callbackHandler.setConfirmationMethod(SAML2Constants.CONF_BEARER);
        callbackHandler.setIssuer(TEST_IDP_ISSUER);
        callbackHandler.setSubjectName(TEST_USER);
        
        return createSamlResponseStr(callbackHandler, requestId);
    }
    
    private String createSamlResponseStr(AbstractSAMLCallbackHandler saml2CallbackHandler,
                                         String requestId) throws Exception {
        ConditionsBean cp = new ConditionsBean();
        AudienceRestrictionBean audienceRestriction = new AudienceRestrictionBean();
        audienceRestriction.getAudienceURIs().add(TEST_REQUEST_URL);
        cp.setAudienceRestrictions(Collections.singletonList(audienceRestriction));
        saml2CallbackHandler.setConditions(cp);
        
        // Subject Confirmation Data
        SubjectConfirmationDataBean subjectConfirmationData = new SubjectConfirmationDataBean();
        subjectConfirmationData.setAddress(TEST_CLIENT_ADDRESS);
        subjectConfirmationData.setInResponseTo(requestId);
        subjectConfirmationData.setNotAfter(new DateTime().plusMinutes(5));
        subjectConfirmationData.setRecipient(TEST_REQUEST_URL);
        saml2CallbackHandler.setSubjectConfirmationData(subjectConfirmationData);
        
        SAMLCallback samlCallback = new SAMLCallback();
        SAMLUtil.doSAMLCallback(saml2CallbackHandler, samlCallback);
        SamlAssertionWrapper assertion = new SamlAssertionWrapper(samlCallback);
        Element response = createSamlResponse(assertion, "mystskey", true, requestId);
        return encodeResponse(response);
    }
    
    private Element createSamlResponse(SamlAssertionWrapper assertion, String alias, 
                                      boolean sign, String requestID)
        throws IOException, UnsupportedCallbackException, WSSecurityException, Exception {
        WSPasswordCallback[] cb = {
            new WSPasswordCallback(alias, WSPasswordCallback.SIGNATURE)
        };
        cbPasswordHandler.handle(cb);
        String password = cb[0].getPassword();

        if (sign) {
            assertion.signAssertion(alias, password, crypto, false);
        }
        
        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        Document doc = docBuilder.newDocument();

        Status status =
            SAML2PResponseComponentBuilder.createStatus(
                "urn:oasis:names:tc:SAML:2.0:status:Success", null
            );
        Response response =
            SAML2PResponseComponentBuilder.createSAMLResponse(requestID, 
                                                              assertion.getIssuerString(), 
                                                              status);

        response.getAssertions().add(assertion.getSaml2());

        Element policyElement = OpenSAMLUtil.toDom(response, doc);
        doc.appendChild(policyElement);

        return policyElement;
    }
    
    
    /**
     * Returns the first element that matches <code>name</code> and
     * <code>namespace</code>. <p/> This is a replacement for a XPath lookup
     * <code>//name</code> with the given namespace. It's somewhat faster than
     * XPath, and we do not deal with prefixes, just with the real namespace URI
     * 
     * @param startNode Where to start the search
     * @param name Local name of the element
     * @param namespace Namespace URI of the element
     * @return The found element or <code>null</code>
     */
    public static Element findElement(Node startNode, String name, String namespace) {
        //
        // Replace the formerly recursive implementation with a depth-first-loop
        // lookup
        //
        if (startNode == null) {
            return null;
        }
        Node startParent = startNode.getParentNode();
        Node processedNode = null;

        while (startNode != null) {
            // start node processing at this point
            if (startNode.getNodeType() == Node.ELEMENT_NODE
                && startNode.getLocalName().equals(name)) {
                String ns = startNode.getNamespaceURI();
                if (ns != null && ns.equals(namespace)) {
                    return (Element)startNode;
                }

                if ((namespace == null || namespace.length() == 0)
                    && (ns == null || ns.length() == 0)) {
                    return (Element)startNode;
                }
            }
            processedNode = startNode;
            startNode = startNode.getFirstChild();

            // no child, this node is done.
            if (startNode == null) {
                // close node processing, get sibling
                startNode = processedNode.getNextSibling();
            }
            // no more siblings, get parent, all children
            // of parent are processed.
            while (startNode == null) {
                processedNode = processedNode.getParentNode();
                if (processedNode == startParent) {
                    return null;
                }
                // close parent node processing (processed node now)
                startNode = processedNode.getNextSibling();
            }
        }
        return null;
    }

    private void assertClaims(List<Claim> claims, String roleClaimType) {
        for (Claim c : claims) {
            Assert.assertTrue("Invalid ClaimType URI: " + c.getClaimType(), 
                              c.getClaimType().equals(roleClaimType)
                              || c.getClaimType().equals(ClaimTypes.COUNTRY)
                              || c.getClaimType().equals(AbstractSAMLCallbackHandler.CLAIM_TYPE_LANGUAGE)
                              );
        }
    }
    
    private String encodeResponse(Element response) throws IOException {
        String responseMessage = DOM2Writer.nodeToString(response);

        byte[] deflatedBytes = CompressionUtils.deflate(responseMessage.getBytes("UTF-8"));

        return Base64.encode(deflatedBytes);
    }


}
