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
package org.apache.cxf.fediz.core.handler;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.w3c.dom.Element;

import org.apache.cxf.fediz.core.FederationConstants;
import org.apache.cxf.fediz.core.config.FedizContext;
import org.apache.cxf.fediz.core.processor.FedizProcessor;
import org.apache.cxf.fediz.core.processor.FedizProcessorFactory;
import org.apache.cxf.fediz.core.processor.RedirectionResponse;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogoutHandler implements RequestHandler<Boolean> {

    private static final Logger LOG = LoggerFactory.getLogger(LogoutHandler.class);
    protected final FedizContext fedizConfig;
    private final String servletContextPath;
    private Element token;

    public LogoutHandler(FedizContext fedConfig) {
        this(fedConfig, "/");
    }

    public LogoutHandler(FedizContext fedConfig, String servletContextPath) {
        this.fedizConfig = fedConfig;
        this.servletContextPath = servletContextPath;
    }

    @Override
    public boolean canHandleRequest(HttpServletRequest request) {
        String wa = request.getParameter(FederationConstants.PARAM_ACTION);
        if (FederationConstants.ACTION_SIGNOUT.equals(wa) || FederationConstants.ACTION_SIGNOUT_CLEANUP.equals(wa)) {
            // Default WS-Federation logout action
            return true;
        }
        //Check for custom logout URL
        String logoutUrl = fedizConfig.getLogoutURL();
        return logoutUrl != null && !logoutUrl.isEmpty() 
            && servletContextPath != null && request.getRequestURI().equals(servletContextPath + logoutUrl);
    }

    @Override
    public Boolean handleRequest(HttpServletRequest request, HttpServletResponse response) {
        String wa = request.getParameter(FederationConstants.PARAM_ACTION);
        if (FederationConstants.ACTION_SIGNOUT.equals(wa)) {
            return signout(request, response);
        } else if (FederationConstants.ACTION_SIGNOUT_CLEANUP.equals(wa)) {
            return signoutCleanup(request, response);
        } else {
            return customLogout(request, response);
        }
    }
    
    protected boolean customLogout(HttpServletRequest request, HttpServletResponse response) {
        LOG.info("Custom Logout URL was invoked.");
        return signout(request, response);
    }

    protected boolean signoutCleanup(HttpServletRequest request, HttpServletResponse response) {
        LOG.info("SignOutCleanup request found. Terminating user session.");
        request.getSession().invalidate();
        String wreply = request.getParameter(FederationConstants.PARAM_REPLY);
        if (wreply != null && !wreply.isEmpty()) {
            try {
                LOG.debug("Redirecting user after logout to: {}", wreply);
                response.sendRedirect(wreply);
            } catch (IOException e) {
                LOG.error("Error redirecting user after logout: {}", e.getMessage());
            }
        } else {
            LOG.debug("No wreply parameter was set in logout action. Returning logout image");
            writeLogoutImage(response);
        }
        return true;
    }

    public void setToken(Element token) {
        this.token = token;
    }

    protected boolean signout(HttpServletRequest request, HttpServletResponse response) {
        LOG.debug("SignOut request found. Redirecting to IDP...");
        //TODO make direct cleanup (session termination) optional via configuration
        try {
            SamlAssertionWrapper assertionToken = null;
            if (token != null) {
                assertionToken = new SamlAssertionWrapper(token);
            }
            FedizProcessor wfProc = FedizProcessorFactory.newFedizProcessor(fedizConfig.getProtocol());
            RedirectionResponse redirectionResponse = wfProc.createSignOutRequest(request, assertionToken, fedizConfig);
            String redirectURL = redirectionResponse.getRedirectionURL();
            if (redirectURL != null) {
                Map<String, String> headers = redirectionResponse.getHeaders();
                if (!headers.isEmpty()) {
                    for (String headerName : headers.keySet()) {
                        response.addHeader(headerName, headers.get(headerName));
                    }
                }
                response.sendRedirect(redirectURL);
                return true;
            } else {
                LOG.warn("Failed to create SignOutRequest.");
            }
        } catch (Exception ex) {
            LOG.warn("Failed to create SignOutRequest: " + ex.getMessage());
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create SignOutRequest.");
            } catch (IOException e) {
                LOG.error("Failed to send error response: {}", e.getMessage());
            }
        }
        return false;
    }

    protected void writeLogoutImage(HttpServletResponse response) {
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("logout.jpg");
        if (inputStream == null) {
            LOG.warn("Could not write logout.jpg");
            return;
        }
        int read = 0;
        byte[] buf = new byte[1024];
        try {
            response.setContentType("image/jpeg");
            ServletOutputStream responseOutputStream = response.getOutputStream();
            while ((read = inputStream.read(buf)) != -1) {
                responseOutputStream.write(buf, 0, read);
            }
            responseOutputStream.flush();
        } catch (IOException e) {
            LOG.error("Could  not send logout image: {}", e.getMessage());
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

}
