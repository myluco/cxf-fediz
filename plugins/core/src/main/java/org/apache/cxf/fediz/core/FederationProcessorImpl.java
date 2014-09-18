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

package org.apache.cxf.fediz.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.servlet.http.HttpServletRequest;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.apache.cxf.fediz.core.config.FederationContext;
import org.apache.cxf.fediz.core.config.FederationProtocol;
import org.apache.cxf.fediz.core.config.KeyManager;
import org.apache.cxf.fediz.core.exception.ProcessingException;
import org.apache.cxf.fediz.core.exception.ProcessingException.TYPE;
import org.apache.cxf.fediz.core.metadata.MetadataWriter;
import org.apache.cxf.fediz.core.spi.FreshnessCallback;
import org.apache.cxf.fediz.core.spi.HomeRealmCallback;
import org.apache.cxf.fediz.core.spi.IDPCallback;
import org.apache.cxf.fediz.core.spi.RealmCallback;
import org.apache.cxf.fediz.core.spi.SignInQueryCallback;
import org.apache.cxf.fediz.core.spi.WAuthCallback;
import org.apache.cxf.fediz.core.spi.WReqCallback;
import org.apache.cxf.fediz.core.util.DOMUtils;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSDataRef;
import org.apache.ws.security.WSDocInfo;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityEngine;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.processor.EncryptedDataProcessor;
import org.apache.ws.security.processor.Processor;
import org.apache.ws.security.util.XmlSchemaDateFormat;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FederationProcessorImpl implements FederationProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(FederationProcessorImpl.class);

    static {
        WSSConfig.init();
    }

    /**
     * Default constructor
     */
    public FederationProcessorImpl() {
        super();
    }

    @Override
    public FederationResponse processRequest(FederationRequest request,
                                             FederationContext config)
        throws ProcessingException {
        
        if (!(config.getProtocol() instanceof FederationProtocol)) {
            LOG.error("Unsupported protocol");
            throw new IllegalStateException("Unsupported protocol");
        }
        FederationResponse response = null;
        if (FederationConstants.ACTION_SIGNIN.equals(request.getWa())) {
            response = this.processSignInRequest(request, config);
        } else {
            LOG.error("Invalid action '" + request.getWa() + "'");
            throw new ProcessingException(TYPE.INVALID_REQUEST);
        }
        return response;
    }
    

    public Document getMetaData(FederationContext config) throws ProcessingException {
        return new MetadataWriter().getMetaData(config);
    }
    
    protected FederationResponse processSignInRequest(
            FederationRequest request, FederationContext config)
        throws ProcessingException {
        
        byte[] wresult = request.getWresult().getBytes();

        Document doc = null;
        Element el = null;
        try {
            doc = DOMUtils.readXml(new ByteArrayInputStream(wresult));
            el = doc.getDocumentElement();

        } catch (Exception e) {
            LOG.warn("Failed to parse wresult: " + e.getMessage());
            throw new ProcessingException(TYPE.INVALID_REQUEST);
        }

        if ("RequestSecurityTokenResponseCollection".equals(el.getLocalName())) {
            el = DOMUtils.getFirstElement(el);
        }
        if (!"RequestSecurityTokenResponse".equals(el.getLocalName())) {
            LOG.warn("Unexpected root element of wresult: '" + el.getLocalName() + "'");
            throw new ProcessingException(TYPE.INVALID_REQUEST);
        }
        el = DOMUtils.getFirstElement(el);
        Element rst = null;
        Element lifetimeElem = null;
        String tt = null;

        while (el != null) {
            String ln = el.getLocalName();
            if (FederationConstants.WS_TRUST_13_NS.equals(el.getNamespaceURI()) 
                || FederationConstants.WS_TRUST_2005_02_NS.equals(el.getNamespaceURI())) {
                if ("Lifetime".equals(ln)) {
                    lifetimeElem = el;
                } else if ("RequestedSecurityToken".equals(ln)) {
                    rst = DOMUtils.getFirstElement(el);
                } else if ("TokenType".equals(ln)) {
                    tt = DOMUtils.getContent(el);
                }
            }
            el = DOMUtils.getNextElement(el);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("RST: " + ((rst != null) ? rst.toString() : "null"));
            LOG.debug("Lifetime: "
                    + ((lifetimeElem != null) ? lifetimeElem.toString()
                            : "null"));
            LOG.debug("Tokentype: " + ((tt != null) ? tt.toString() : "null"));
        }
        if (rst == null) {
            LOG.warn("RequestedSecurityToken element not found in wresult");
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }
        LifeTime lifeTime = null;
        if (lifetimeElem != null) {
            lifeTime = processLifeTime(lifetimeElem);
        }

        if (config.isDetectExpiredTokens() && lifeTime != null) {
            Date currentDate = new Date();
            if (currentDate.after(lifeTime.getExpires())) {
                LOG.warn("RSTR Lifetime expired");
                throw new ProcessingException(TYPE.TOKEN_EXPIRED);
            }
            DateTime currentTime = new DateTime();
            DateTime validFrom = new DateTime(lifeTime.created);
            currentTime = currentTime.plusSeconds(config.getMaximumClockSkew().intValue());
            if (validFrom.isAfter(currentTime)) {
                LOG.debug("RSTR Lifetime not yet valid");
                throw new ProcessingException(TYPE.TOKEN_INVALID);
            }
        }
        
        // Check to see if RST is encrypted
        if ("EncryptedData".equals(rst.getLocalName())
            && WSConstants.ENC_NS.equals(rst.getNamespaceURI())) {
            Element decryptedRST = decryptEncryptedRST(rst, config);
            if (decryptedRST != null) {
                rst = decryptedRST;
            }
        }
        
        TokenValidatorResponse validatorResponse = null;
        List<TokenValidator> validators = ((FederationProtocol)config.getProtocol()).getTokenValidators();
        for (TokenValidator validator : validators) {
            boolean canHandle = false;
            if (tt != null) {
                canHandle = validator.canHandleTokenType(tt);
            } else {
                canHandle = validator.canHandleToken(rst);
            }
            if (canHandle) {
                try {
                    TokenValidatorRequest validatorRequest = 
                        new TokenValidatorRequest(rst, request.getCerts());
                    validatorResponse = validator.validateAndProcessToken(validatorRequest, config);
                } catch (ProcessingException ex) {
                    throw ex;
                } catch (Exception ex) {
                    LOG.warn("Failed to validate token", ex);
                    throw new ProcessingException(TYPE.TOKEN_INVALID);
                }
                break;
            } else {
                LOG.warn("No security token validator found for '" + tt + "'");
                throw new ProcessingException(TYPE.BAD_REQUEST);
            }
        }

        // Check whether token already used for signin
        if (validatorResponse.getUniqueTokenId() != null
                && config.isDetectReplayedTokens()) {
            // Check whether token has already been processed once, prevent
            // replay attack
            if (config.getTokenReplayCache().getId(validatorResponse.getUniqueTokenId()) == null) {
                // not cached
                Date expires = null;
                if (lifeTime != null && lifeTime.getExpires() != null) {
                    expires = lifeTime.getExpires();
                } else {
                    expires = validatorResponse.getExpires();
                }
                if (expires != null) {
                    Date currentTime = new Date();
                    long ttl = expires.getTime() - currentTime.getTime();
                    config.getTokenReplayCache().putId(validatorResponse.getUniqueTokenId(), ttl / 1000L);
                } else {
                    config.getTokenReplayCache().putId(validatorResponse.getUniqueTokenId());
                }
            } else {
                LOG.error("Replay attack with token id: " + validatorResponse.getUniqueTokenId());
                throw new ProcessingException("Replay attack with token id: "
                        + validatorResponse.getUniqueTokenId(), TYPE.TOKEN_REPLAY);
            }
        }

        FederationResponse fedResponse = new FederationResponse(
                validatorResponse.getUsername(), validatorResponse.getIssuer(),
                validatorResponse.getRoles(), validatorResponse.getClaims(),
                validatorResponse.getAudience(),
                (lifeTime != null) ? lifeTime.getCreated() : null,
                        (lifeTime != null) ? lifeTime.getExpires() : null, rst,
                            validatorResponse.getUniqueTokenId());

        return fedResponse;
    }
    
    private Element decryptEncryptedRST(
        Element encryptedRST,
        FederationContext config
    ) throws ProcessingException {

        KeyManager decryptionKeyManager = config.getDecryptionKey();
        if (decryptionKeyManager == null || decryptionKeyManager.getCrypto() == null) {
            LOG.debug(
                "We must have a decryption Crypto instance configured to decrypt encrypted tokens"
            );
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }
        String keyPassword = decryptionKeyManager.getKeyPassword();
        if (keyPassword == null) {
            LOG.debug(
                "We must have a decryption key password to decrypt encrypted tokens"
            );
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }
        
        EncryptedDataProcessor proc = new EncryptedDataProcessor();
        WSDocInfo docInfo = new WSDocInfo(encryptedRST.getOwnerDocument());
        RequestData data = new RequestData();
        
        // Disable WSS4J processing of the (decrypted) SAML Token
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        wssConfig.setProcessor(WSSecurityEngine.SAML_TOKEN, new NOOpProcessor());
        wssConfig.setProcessor(WSSecurityEngine.SAML2_TOKEN, new NOOpProcessor());
        data.setWssConfig(wssConfig);
        
        data.setDecCrypto(decryptionKeyManager.getCrypto());
        data.setCallbackHandler(new DecryptionCallbackHandler(keyPassword));
        try {
            List<WSSecurityEngineResult> result =
                proc.handleToken(encryptedRST, data, docInfo);
            if (result.size() > 0) {
                @SuppressWarnings("unchecked")
                List<WSDataRef> dataRefs = 
                    (List<WSDataRef>)result.get(result.size() - 1).get(WSSecurityEngineResult.TAG_DATA_REF_URIS);
                if (dataRefs != null && dataRefs.size() > 0) {
                    return dataRefs.get(0).getProtectedElement();
                }
            }
        } catch (WSSecurityException e) {
            LOG.debug(e.getMessage(), e);
            throw new ProcessingException(TYPE.TOKEN_INVALID);
        }
        return null;
    }

    private LifeTime processLifeTime(Element lifetimeElem) throws ProcessingException {
        try {
            Element createdElem = DOMUtils.getFirstChildWithName(lifetimeElem,
                    WSConstants.WSU_NS, WSConstants.CREATED_LN);
            DateFormat zulu = new XmlSchemaDateFormat();

            Date created = zulu.parse(DOMUtils.getContent(createdElem));

            Element expiresElem = DOMUtils.getFirstChildWithName(lifetimeElem,
                    WSConstants.WSU_NS, WSConstants.EXPIRES_LN);
            Date expires = zulu.parse(DOMUtils.getContent(expiresElem));

            return new LifeTime(created, expires);

        } catch (ParseException e) {
            LOG.error("Failed to parse lifetime element in wresult: " + e.getMessage());
            throw new ProcessingException(TYPE.BAD_REQUEST);
        }
    }

    public class LifeTime {

        private Date created;
        private Date expires;

        public LifeTime(Date created, Date expires) {
            this.created = created;
            this.expires = expires;
        }

        public Date getCreated() {
            return created;
        }

        public Date getExpires() {
            return expires;
        }

    }

    @Override
    public String createSignInRequest(HttpServletRequest request, FederationContext config)
        throws ProcessingException {

        String redirectURL = null;
        try {
            if (!(config.getProtocol() instanceof FederationProtocol)) {
                LOG.error("Unsupported protocol");
                throw new IllegalStateException("Unsupported protocol");
            }
            
            String issuerURL = resolveIssuer(request, config);
            LOG.info("Issuer url: " + issuerURL);
            if (issuerURL != null && issuerURL.length() > 0) {
                redirectURL = issuerURL;
            }
            
            String wAuth = resolveAuthenticationType(request, config);
            LOG.info("WAuth: " + wAuth);
            
            String wReq = resolveRequest(request, config);
            LOG.info("WReq: " + wReq);
            
            String homeRealm = resolveHomeRealm(request, config);
            LOG.info("HomeRealm: " + homeRealm);
            
            String freshness = resolveFreshness(request, config);
            LOG.info("Freshness: " + freshness);
            
            String signInQuery = resolveSignInQuery(request, config);
            LOG.info("SignIn Query: " + signInQuery);
            
             
            StringBuilder sb = new StringBuilder();
            sb.append(FederationConstants.PARAM_ACTION).append('=').append(FederationConstants.ACTION_SIGNIN);
            
            String reply = ((FederationProtocol)config.getProtocol()).getReply();
            if (reply == null || reply.length() == 0) {
                reply = request.getRequestURL().toString();
            } else {
                try {
                    new URL(reply);
                } catch (MalformedURLException ex) {
                    if (reply.startsWith("/")) {
                        reply = extractFullContextPath(request).concat(reply.substring(1));
                    } else {
                        reply = extractFullContextPath(request).concat(reply);
                    }
                }
            }
            
            LOG.debug("wreply=" + reply);
            sb.append('&').append(FederationConstants.PARAM_REPLY).append('=');
            sb.append(URLEncoder.encode(reply, "UTF-8"));

            String realm = resolveWTRealm(request, config);
            LOG.debug("wtrealm=" + realm);

            //add wtrealm parameter
            sb.append('&').append(FederationConstants.PARAM_TREALM).append('=')
                .append(URLEncoder.encode(realm, "UTF-8"));
            
            // add authentication type parameter wauth if set
            if (wAuth != null && wAuth.length() > 0) {
                sb.append('&').append(FederationConstants.PARAM_AUTH_TYPE).append('=')
                    .append(URLEncoder.encode(wAuth, "UTF-8"));
            }
            
            // add tokenRequest parameter wreq if set
            if (wReq != null && wReq.length() > 0) {
                sb.append('&').append(FederationConstants.PARAM_REQUEST).append('=')
                    .append(URLEncoder.encode(wReq, "UTF-8"));
            }
            
            // add home realm parameter whr if set
            if (homeRealm != null && homeRealm.length() > 0) {
                sb.append('&').append(FederationConstants.PARAM_HOME_REALM).append('=')
                    .append(URLEncoder.encode(homeRealm, "UTF-8"));
            }
            
            // add freshness parameter wfresh if set
            if (freshness != null && freshness.length() > 0) {
                sb.append('&').append(FederationConstants.PARAM_FRESHNESS).append('=')
                    .append(URLEncoder.encode(freshness, "UTF-8"));
            }
            
            // add current time parameter wct
            Date creationTime = new Date();
            XmlSchemaDateFormat fmt = new XmlSchemaDateFormat();
            String wct = fmt.format(creationTime);
            sb.append('&').append(FederationConstants.PARAM_CURRENT_TIME).append('=')
            .append(URLEncoder.encode(wct, "UTF-8"));
            
            // add signin query extensions
            if (signInQuery != null && signInQuery.length() > 0) {
                sb.append('&').append(signInQuery);
            }
            
            redirectURL = redirectURL + "?" + sb.toString();
        } catch (Exception ex) {
            LOG.error("Failed to create SignInRequest", ex);
            throw new ProcessingException("Failed to create SignInRequest");
        }        
        return redirectURL;
    }

    private String resolveSignInQuery(HttpServletRequest request, FederationContext config)
        throws IOException, UnsupportedCallbackException, UnsupportedEncodingException {
        Object signInQueryObj = ((FederationProtocol)config.getProtocol()).getSignInQuery();
        String signInQuery = null;
        if (signInQueryObj != null) {
            if (signInQueryObj instanceof String) {
                signInQuery = (String)signInQueryObj;
            } else if (signInQueryObj instanceof CallbackHandler) {
                CallbackHandler frCB = (CallbackHandler)signInQueryObj;
                SignInQueryCallback callback = new SignInQueryCallback(request);
                frCB.handle(new Callback[] {callback});
                Map<String, String> signInQueryMap = callback.getSignInQueryParamMap();
                StringBuilder sbQuery = new StringBuilder();
                for (String key : signInQueryMap.keySet()) {
                    if (sbQuery.length() > 0) {
                        sbQuery.append("&");
                    }
                    sbQuery.append(key).append('=').
                    append(URLEncoder.encode(signInQueryMap.get(key), "UTF-8"));
                }
                signInQuery = sbQuery.toString();
               
            }
        }
        return signInQuery;
    }

    private String resolveFreshness(HttpServletRequest request, FederationContext config) throws IOException,
        UnsupportedCallbackException {
        Object freshnessObj = ((FederationProtocol)config.getProtocol()).getFreshness();
        String freshness = null;
        if (freshnessObj != null) {
            if (freshnessObj instanceof String) {
                freshness = (String)freshnessObj;
            } else if (freshnessObj instanceof CallbackHandler) {
                CallbackHandler frCB = (CallbackHandler)freshnessObj;
                FreshnessCallback callback = new FreshnessCallback(request);
                frCB.handle(new Callback[] {callback});
                freshness = callback.getFreshness();
            }
        }
        return freshness;
    }

    private String resolveHomeRealm(HttpServletRequest request, FederationContext config) throws IOException,
        UnsupportedCallbackException {
        Object homeRealmObj = ((FederationProtocol)config.getProtocol()).getHomeRealm();
        String homeRealm = null;
        if (homeRealmObj != null) {
            if (homeRealmObj instanceof String) {
                homeRealm = (String)homeRealmObj;
            } else if (homeRealmObj instanceof CallbackHandler) {
                CallbackHandler hrCB = (CallbackHandler)homeRealmObj;
                HomeRealmCallback callback = new HomeRealmCallback(request);
                hrCB.handle(new Callback[] {callback});
                homeRealm = callback.getHomeRealm();
            }
        }
        return homeRealm;
    }

    private String resolveAuthenticationType(HttpServletRequest request, FederationContext config)
        throws IOException, UnsupportedCallbackException {
        Object wAuthObj = ((FederationProtocol)config.getProtocol()).getAuthenticationType();
        String wAuth = null;
        if (wAuthObj != null) {
            if (wAuthObj instanceof String) {
                wAuth = (String)wAuthObj;
            } else if (wAuthObj instanceof CallbackHandler) {
                CallbackHandler wauthCB = (CallbackHandler)wAuthObj;
                WAuthCallback callback = new WAuthCallback(request);
                wauthCB.handle(new Callback[] {callback});
                wAuth = callback.getWauth();
            }  
        }
        return wAuth;
    }
    
    private String resolveRequest(HttpServletRequest request, FederationContext config)
        throws IOException, UnsupportedCallbackException {
        Object wReqObj = ((FederationProtocol)config.getProtocol()).getRequest();
        String wReq = null;
        if (wReqObj != null) {
            if (wReqObj instanceof String) {
                wReq = (String)wReqObj;
            } else if (wReqObj instanceof CallbackHandler) {
                CallbackHandler wauthCB = (CallbackHandler)wReqObj;
                WReqCallback callback = new WReqCallback(request);
                wauthCB.handle(new Callback[] {callback});
                wReq = callback.getWreq();
            }  
        }
        return wReq;
    }

    private String resolveIssuer(HttpServletRequest request, FederationContext config) throws IOException,
        UnsupportedCallbackException {
        Object issuerObj = ((FederationProtocol)config.getProtocol()).getIssuer();
        String issuerURL = null;
        if (issuerObj instanceof String) {
            issuerURL = (String)issuerObj;
        } else if (issuerObj instanceof CallbackHandler) {
            CallbackHandler issuerCB = (CallbackHandler)issuerObj;
            IDPCallback callback = new IDPCallback(request);
            issuerCB.handle(new Callback[] {callback});
            issuerURL = callback.getIssuerUrl().toString();
        }
        return issuerURL;
    }

    private String resolveWTRealm(HttpServletRequest request, FederationContext config) throws IOException,
        UnsupportedCallbackException {
        Object wtRealmObj = ((FederationProtocol)config.getProtocol()).getRealm();
        String wtRealm = null;
        if (wtRealmObj != null) {
            if (wtRealmObj instanceof String) {
                wtRealm = (String)wtRealmObj;
            } else if (wtRealmObj instanceof CallbackHandler) {
                CallbackHandler hrCB = (CallbackHandler)wtRealmObj;
                RealmCallback callback = new RealmCallback(request);
                hrCB.handle(new Callback[] {callback});
                wtRealm = callback.getRealm();
            }
        } else {
            wtRealm = extractFullContextPath(request); //default value
        }
        return wtRealm;
    }


    private String extractFullContextPath(HttpServletRequest request) throws MalformedURLException {
        String result = null;
        String contextPath = request.getContextPath();
        String requestUrl = request.getRequestURL().toString();
        String requestPath = new URL(requestUrl).getPath();
        // Cut request path of request url and add context path if not ROOT
        if (requestPath != null && requestPath.length() > 0) {
            int lastIndex = requestUrl.lastIndexOf(requestPath);
            result = requestUrl.substring(0, lastIndex);
        } else {
            result = requestUrl;
        }
        if (contextPath != null && contextPath.length() > 0) {
            // contextPath contains starting slash
            result = result + contextPath + "/";
        } else {
            result = result + "/";
        }
        return result;
    }
    
    private static class DecryptionCallbackHandler implements CallbackHandler {
        
        private final String password;
        
        public DecryptionCallbackHandler(String password) {
            this.password = password;
        }

        @Override
        public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
            for (int i = 0; i < callbacks.length; i++) {
                if (callbacks[i] instanceof WSPasswordCallback) {
                    WSPasswordCallback pc = (WSPasswordCallback) callbacks[i];
                    pc.setPassword(password);
                } else {
                    throw new UnsupportedCallbackException(callbacks[i], "Unrecognized Callback");
                }
            }
        }
        
    }

    private static class NOOpProcessor implements Processor {

        @Override
        public List<WSSecurityEngineResult> handleToken(Element arg0, RequestData arg1, WSDocInfo arg2)
            throws WSSecurityException {
            return new ArrayList<WSSecurityEngineResult>();
        }
        
    }

}
