/*
 * oxAuth-CIBA is available under the Gluu Enterprise License (2019).
 *
 * Copyright (c) 2020, Janssen Project
 */

package org.gluu.oxauth.client.ciba.push;

import org.apache.log4j.Logger;
import org.gluu.oxauth.client.BaseResponse;
import org.jboss.resteasy.client.ClientResponse;

/**
 * @author Javier Rojas Blum
 * @version May 9, 2020
 */
public class PushErrorResponse extends BaseResponse {

    private static final Logger LOG = Logger.getLogger(PushErrorResponse.class);

    public PushErrorResponse(ClientResponse<String> clientResponse) {
        super(clientResponse);

        String entity = clientResponse.getEntity(String.class);
        setEntity(entity);
        setHeaders(clientResponse.getMetadata());
    }
}
