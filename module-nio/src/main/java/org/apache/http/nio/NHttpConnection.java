/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 *
 *  Copyright 1999-2006 The Apache Software Foundation
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
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio;

import org.apache.http.HttpConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;

/**
 * Abstract non-blocking HTTP connection interface. It contains the current
 * HTTP context, as well as the actual HTTP request and HTTP response objects
 * that are being received / transferred over this connection.
 * 
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 */
public interface NHttpConnection extends HttpConnection, ContentIOControl {

    /** 
     * Returns the current HTTP request if one is being received / transmitted.
     * Otherwise returns <tt>null</tt>.
     * 
     * @return an HTTP request if available. Otherwise returns <tt>null</tt>.
     */
    HttpRequest getHttpRequest();

    /** 
     * Returns the current HTTP response if one is being received / transmitted. 
     * Otherwise returns <tt>null</tt>.
     * 
     * @return an HTTP response if available. Otherwise returns <tt>null</tt>.
     */
    HttpResponse getHttpResponse();
    
    /**
     * Returns an HTTP execution context associated with this connection.
     * @return HTTP context
     */
    HttpContext getContext();
    
}