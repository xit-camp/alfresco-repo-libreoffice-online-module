/*
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package dk.magenta.libreoffice.online;

import java.io.IOException;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptException;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;

import dk.magenta.libreoffice.online.service.PersonInfo;
import dk.magenta.libreoffice.online.service.WOPIAccessTokenInfo;
import dk.magenta.libreoffice.online.service.WOPITokenService;

public class LOOLPutFileWebScript extends AbstractWebScript {
    private static final Log logger = LogFactory.getLog(LOOLPutFileWebScript.class);
    private WOPITokenService wopiTokenService;
    private NodeService nodeService;
    private ContentService contentService;
    private RetryingTransactionHelper retryingTransactionHelper;

    @Override
    public void execute(WebScriptRequest req, WebScriptResponse res) throws IOException {

        String wopiOverrideHeader = req.getHeader("X-WOPI-Override");
        if (wopiOverrideHeader == null) {
            wopiOverrideHeader = req.getHeader("X-WOPIOverride");
        }
        if (wopiOverrideHeader == null || !wopiOverrideHeader.equals("PUT")) {
            throw new WebScriptException("X-WOPI-Override header must be present and equal to 'PUT'");
        }

        try {
            WOPIAccessTokenInfo tokenInfo = wopiTokenService.getTokenInfo(req);
            //Verifying that the user actually exists
            PersonInfo person = wopiTokenService.getUserInfoOfToken(tokenInfo);
            final NodeRef nodeRef = wopiTokenService.getFileNodeRef(tokenInfo);
            if (StringUtils.isBlank(person.getUserName()) && person.getUserName() != tokenInfo.getUserName())
                throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR,
                        "The user no longer appears to exist.");

            if (tokenInfo != null) {
                //https://community.alfresco.com/message/809749-re-why-is-the-modifier-of-a-content-a-random-user-from-the-list-of-logged-in-users?commentID=809749&et=watches.email.thread#comment-809749
                retryingTransactionHelper.doInTransaction(new RetryingTransactionHelper.RetryingTransactionCallback<Void>() {
                    @Override
                    public Void execute() throws Throwable {
                        try {
                            AuthenticationUtil.setFullyAuthenticatedUser(tokenInfo.getUserName());
                            ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
                            writer.putContent(req.getContent().getInputStream());
                            writer.guessMimetype((String) nodeService.getProperty(nodeRef, ContentModel.PROP_NAME));
                            writer.guessEncoding();

                            logger.info("\n****** Debug testing ********\n\t\tToken: " + tokenInfo.getAccessToken()
                                    + "\n\t\tFileId: " + tokenInfo.getFileId() + "\n\t\tUserName: " + tokenInfo.getUserName() + "\n");
                        } finally {
                            AuthenticationUtil.clearCurrentSecurityContext();
                        }
                        return null;
                    }
                }, false, true);
            }

            logger.info("Modifier for the above nodeRef [" + nodeRef.toString() + "] is: "
                    + nodeService.getProperty(nodeRef, ContentModel.PROP_MODIFIER));

        } catch (ContentIOException | NullPointerException | WebScriptException we) {
            we.printStackTrace();
            if (we.getClass() == ContentIOException.class)
                throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR, "Error writing to file");
            else if (we.getClass() == WebScriptException.class)
                throw new WebScriptException(Status.STATUS_UNAUTHORIZED, "Access token invalid or expired");
            else
                throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR, "\nUnidentified problem writing to file" +
                        "please consult system administrator for help on this issue.\n ");
        }
    }

    public void setWopiTokenService(WOPITokenService wopiTokenService) {
        this.wopiTokenService = wopiTokenService;
    }

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setRetryingTransactionHelper(RetryingTransactionHelper retryingTransactionHelper) {
        this.retryingTransactionHelper = retryingTransactionHelper;
    }
}