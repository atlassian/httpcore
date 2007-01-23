/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpInetConnection;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolException;
import org.apache.http.UnsupportedHttpVersionException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.NHttpServiceHandler;
import org.apache.http.nio.concurrent.Executor;
import org.apache.http.nio.impl.SharedInputBuffer;
import org.apache.http.nio.impl.SharedOutputBuffer;
import org.apache.http.nio.impl.entity.BufferedContent;
import org.apache.http.nio.impl.entity.ContentOutputStream;
import org.apache.http.nio.params.HttpNIOParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpExecutionContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerResolver;
import org.apache.http.util.EncodingUtils;

/**
 * HTTP service handler implementation that allocates content buffers of limited 
 * size upon initialization and is capable of controlling the frequency of I/O 
 * events in order to guarantee those content buffers do not ever get overflown. 
 * This helps ensure near constant memory footprint of HTTP connections and to 
 * avoid the 'out of memory' condition while streaming out response content.
 * 
 * <p>The service handler will delegate the task of processing requests and 
 * generating response content to an {@link Executor}, which is expected to
 * perform those tasks using dedicated worker threads in order to avoid 
 * blocking the I/O thread.</p>
 * 
 * @see HttpNIOParams#CONTENT_BUFFER_SIZE
 * 
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 *
 */
public class ThrottlingHttpServiceHandler implements NHttpServiceHandler {

    private static final String IN_BUF = "http.nio.in-buffer";
    private static final String OUT_BUF = "http.nio.out-buffer";
    
    private HttpParams params;
    private HttpProcessor httpProcessor;
    private HttpResponseFactory responseFactory;
    private ConnectionReuseStrategy connStrategy;
    private HttpRequestHandlerResolver handlerResolver;
    private EventListener eventListener;
    private Executor executor;
    
    public ThrottlingHttpServiceHandler(
            final HttpProcessor httpProcessor, 
            final HttpResponseFactory responseFactory,
            final ConnectionReuseStrategy connStrategy,
            final Executor executor,
            final HttpParams params) {
        super();
        if (httpProcessor == null) {
            throw new IllegalArgumentException("HTTP processor may not be null.");
        }
        if (connStrategy == null) {
            throw new IllegalArgumentException("Connection reuse strategy may not be null");
        }
        if (responseFactory == null) {
            throw new IllegalArgumentException("Response factory may not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor may not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("HTTP parameters may not be null");
        }
        this.httpProcessor = httpProcessor;
        this.connStrategy = connStrategy;
        this.responseFactory = responseFactory;
        this.executor = executor;
        this.params = params;
    }

    public void setEventListener(final EventListener eventListener) {
        this.eventListener = eventListener;
    }

    public void setHandlerResolver(final HttpRequestHandlerResolver handlerResolver) {
        this.handlerResolver = handlerResolver;
    }

    public HttpParams getParams() {
        return this.params;
    }
    
    public void connected(final NHttpServerConnection conn) {
        HttpContext context = conn.getContext();

        int bufsize = HttpNIOParams.getContentBufferSize(this.params);
        if (bufsize < 0) {
            bufsize = 20480;
        }
        
        SharedInputBuffer inbuffer = new SharedInputBuffer(bufsize, conn); 
        SharedOutputBuffer outbuffer = new SharedOutputBuffer(bufsize, conn); 
     
        context.setAttribute(IN_BUF, inbuffer);
        context.setAttribute(OUT_BUF, outbuffer);
        
        if (this.eventListener != null) {
            InetAddress address = null;
            if (conn instanceof HttpInetConnection) {
                address = ((HttpInetConnection) conn).getRemoteAddress();
            }
            this.eventListener.connectionOpen(address);
        }
    }

    public void closed(final NHttpServerConnection conn) {
        HttpContext context = conn.getContext();
        
        SharedInputBuffer inbuffer = (SharedInputBuffer) context.getAttribute(IN_BUF);
        SharedOutputBuffer outbuffer = (SharedOutputBuffer) context.getAttribute(OUT_BUF);
        
        inbuffer.shutdown();
        outbuffer.shutdown();
        
        if (this.eventListener != null) {
            InetAddress address = null;
            if (conn instanceof HttpInetConnection) {
                address = ((HttpInetConnection) conn).getRemoteAddress();
            }
            this.eventListener.connectionClosed(address);
        }
    }
    
    public void exception(final NHttpServerConnection conn, final HttpException httpex) {

        this.executor.execute(new Runnable() {
            
            public void run() {
                try {
                    
                    HttpResponse response = handleException(conn, httpex);
                    commitResponse(conn, response);
                    
                } catch (IOException ex) {
                    shutdownConnection(conn);
                    if (eventListener != null) {
                        eventListener.fatalIOException(ex);
                    }
                } catch (HttpException ex) {
                    shutdownConnection(conn);
                    if (eventListener != null) {
                        eventListener.fatalProtocolException(ex);
                    }
                }
            }
            
        });        
    }

    public void exception(final NHttpServerConnection conn, final IOException ex) {
        shutdownConnection(conn);
        
        if (this.eventListener != null) {
            this.eventListener.fatalIOException(ex);
        }
    }

    public void timeout(final NHttpServerConnection conn) {
        shutdownConnection(conn);

        if (this.eventListener != null) {
            InetAddress address = null;
            if (conn instanceof HttpInetConnection) {
                address = ((HttpInetConnection) conn).getRemoteAddress();
            }
            this.eventListener.connectionTimeout(address);
        }
    }

    public void requestReceived(final NHttpServerConnection conn) {
        final HttpContext context = conn.getContext();
        final HttpRequest request = conn.getHttpRequest();

        SharedInputBuffer inbuffer = (SharedInputBuffer) context.getAttribute(IN_BUF);
        SharedOutputBuffer outbuffer = (SharedOutputBuffer) context.getAttribute(OUT_BUF);

        // Clean the buffers just in case
        inbuffer.reset();
        outbuffer.reset();

        if (request instanceof HttpEntityEnclosingRequest) {
            if (((HttpEntityEnclosingRequest) request).expectContinue()) {
                HttpVersion ver = request.getRequestLine().getHttpVersion();
                HttpResponse ack = this.responseFactory.newHttpResponse(ver, 100);
                try {
                    conn.submitResponse(ack);
                } catch (HttpException ex) {
                    shutdownConnection(conn);
                    if (this.eventListener != null) {
                        this.eventListener.fatalProtocolException(ex);
                    }
                    return;
                }
            }
            // Create a wrapper entity instead of the original one
            BufferedContent.wrapEntity((HttpEntityEnclosingRequest) request, inbuffer);
        }
        
        this.executor.execute(new Runnable() {
            
            public void run() {
                try {
                    
                    processRequest(conn, request);
                    
                } catch (IOException ex) {
                    shutdownConnection(conn);
                    if (eventListener != null) {
                        eventListener.fatalIOException(ex);
                    }
                } catch (HttpException ex) {
                    shutdownConnection(conn);
                    if (eventListener != null) {
                        eventListener.fatalProtocolException(ex);
                    }
                }
            }
            
        });

    }

    public void inputReady(final NHttpServerConnection conn, final ContentDecoder decoder) {
        HttpContext context = conn.getContext();

        SharedInputBuffer inbuffer = (SharedInputBuffer) context.getAttribute(IN_BUF);
        
        try {
            
            inbuffer.consumeContent(decoder);
            
        } catch (IOException ex) {
            shutdownConnection(conn);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex);
            }
        }
    }

    public void outputReady(final NHttpServerConnection conn, final ContentEncoder encoder) {
        HttpContext context = conn.getContext();
        HttpResponse response = conn.getHttpResponse();

        SharedOutputBuffer outbuffer = (SharedOutputBuffer) context.getAttribute(OUT_BUF);
        
        try {

            outbuffer.produceContent(encoder);
            if (encoder.isCompleted()) {
                if (!this.connStrategy.keepAlive(response, context)) {
                    conn.close();
                }
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex);
            }
        }
    }
 
    private void shutdownConnection(final NHttpConnection conn) {
        HttpContext context = conn.getContext();

        SharedInputBuffer inbuffer = (SharedInputBuffer) context.getAttribute(IN_BUF);
        SharedOutputBuffer outbuffer = (SharedOutputBuffer) context.getAttribute(OUT_BUF);
        
        try {
            conn.shutdown();
        } catch (IOException ignore) {
        }
        
        if (inbuffer != null) {
            inbuffer.shutdown();
        }
        if (outbuffer != null) {
            outbuffer.shutdown();
        }
    }
    
    private HttpResponse handleException(
            final NHttpServerConnection conn,
            final HttpException ex) {
        HttpRequest request = conn.getHttpRequest();
        HttpVersion ver = request.getRequestLine().getHttpVersion();
        HttpResponse response =  this.responseFactory.newHttpResponse(
                ver, HttpStatus.SC_BAD_REQUEST);

        int code = HttpStatus.SC_INTERNAL_SERVER_ERROR;
        if (ex instanceof MethodNotSupportedException) {
            code = HttpStatus.SC_NOT_IMPLEMENTED;
        } else if (ex instanceof UnsupportedHttpVersionException) {
            code = HttpStatus.SC_HTTP_VERSION_NOT_SUPPORTED;
        } else if (ex instanceof ProtocolException) {
            code = HttpStatus.SC_BAD_REQUEST;
        }
        
        response.setStatusLine(HttpVersion.HTTP_1_0, code);
        byte[] msg = EncodingUtils.getAsciiBytes(ex.getMessage());
        ByteArrayEntity entity = new ByteArrayEntity(msg);
        entity.setContentType("text/plain; charset=US-ASCII");
        response.setEntity(entity);

        return response;
    }
    
    private void processRequest(
            final NHttpServerConnection conn,
            final HttpRequest request) throws HttpException, IOException {
        HttpContext context = conn.getContext();
        HttpVersion ver = request.getRequestLine().getHttpVersion();

        if (!ver.lessEquals(HttpVersion.HTTP_1_1)) {
            // Downgrade protocol version if greater than HTTP/1.1 
            ver = HttpVersion.HTTP_1_1;
        }

        HttpResponse response = this.responseFactory.newHttpResponse(ver, HttpStatus.SC_OK);
        response.getParams().setDefaults(this.params);
        
        context.setAttribute(HttpExecutionContext.HTTP_REQUEST, request);
        context.setAttribute(HttpExecutionContext.HTTP_CONNECTION, conn);
        context.setAttribute(HttpExecutionContext.HTTP_RESPONSE, response);
        
        try {

            this.httpProcessor.process(request, context);

            HttpRequestHandler handler = null;
            if (this.handlerResolver != null) {
                String requestURI = request.getRequestLine().getUri();
                handler = this.handlerResolver.lookup(requestURI);
            }
            if (handler != null) {
                handler.handle(request, response, context);
            } else {
                response.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
            }
            
        } catch (HttpException ex) {
            response = handleException(conn, ex);
        }

        commitResponse(conn, response);
    }
    
    private void commitResponse(
            final NHttpServerConnection conn,
            final HttpResponse response) throws IOException, HttpException {

        HttpContext context = conn.getContext();
        SharedOutputBuffer outbuffer = (SharedOutputBuffer) context.getAttribute(OUT_BUF);

        this.httpProcessor.process(response, context);
        conn.submitResponse(response);

        if (response.getEntity() != null) {
            HttpEntity entity = response.getEntity();
            OutputStream outstream = new ContentOutputStream(outbuffer);
            entity.writeTo(outstream);
            outstream.flush();
        }
    }
    
}