/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.http.internal.listener.grizzly;

import static java.lang.Long.MAX_VALUE;
import static java.lang.System.arraycopy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.glassfish.grizzly.http.HttpServerFilter.RESPONSE_COMPLETE_EVENT;
import static org.mule.config.i18n.MessageFactory.createStaticMessage;

import java.io.IOException;
import java.io.Writer;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.memory.MemoryManager;
import org.mule.api.DefaultMuleException;
import org.mule.module.http.internal.domain.response.HttpResponse;
import org.mule.module.http.internal.listener.async.ResponseStatusCallback;

public final class ResponseDelayedCompletionHandler extends BaseResponseCompletionHandler
{

    protected final MemoryManager memoryManager;
    protected final FilterChainContext ctx;
    protected final HttpResponsePacket httpResponsePacket;
    private final ResponseStatusCallback responseStatusCallback;

    ResponseDelayedCompletionHandler(FilterChainContext ctx, HttpRequestPacket request,
            HttpResponse httpResponse, ResponseStatusCallback responseStatusCallback)
    {
        this.ctx = ctx;
        httpResponsePacket = buildHttpResponsePacket(request, httpResponse);
        memoryManager = ctx.getConnection().getTransport().getMemoryManager();
        this.responseStatusCallback = responseStatusCallback;
    }

    private boolean written = false;
    
    public void start() throws IOException
    {
        httpResponsePacket.setContentLengthLong(MAX_VALUE);
        httpResponsePacket.setChunkingAllowed(false);
        final HttpContent content = httpResponsePacket.httpContentBuilder().build();
        
        ctx.write(content, this);
        written = true;
    }

    public Writer buildWriter()
    {
        return new Writer()
        {
            private final StringBuilder bufferSB = new StringBuilder();

            @Override
            public void write(char[] cbuf, int off, int len) throws IOException
            {
                bufferSB.append(cbuf, off, len);
            }

            @Override
            public void flush() throws IOException
            {
                final Buffer buffer = memoryManager.allocate(bufferSB.length());
                final byte[] bufferByteArray = buffer.array();
                final int offset = buffer.arrayOffset();

                arraycopy(bufferSB.toString().getBytes(UTF_8), 0, bufferByteArray, offset, bufferSB.length());
                bufferSB.setLength(0);
                ctx.write(buffer, ResponseDelayedCompletionHandler.this);
                written = true;
            }

            @Override
            public void close() throws IOException
            {
                responseStatusCallback.responseSendSuccessfully();
                ctx.notifyDownstream(RESPONSE_COMPLETE_EVENT);
                resume();
            }
        };
    }

    @Override
    public void completed(WriteResult result)
    {
        // Nothing to do
    }
    
    /**
     * The method will be called, when file transferring was canceled
     */
    @Override
    public void cancelled()
    {
        super.cancelled();
        responseStatusCallback.responseSendFailure(new DefaultMuleException(createStaticMessage("HTTP response sending task was cancelled")));
        resume();
    }

    /**
     * The method will be called, if file transferring was failed.
     *
     * @param throwable the cause
     */
    @Override
    public void failed(Throwable throwable)
    {
        super.failed(throwable);
        responseStatusCallback.responseSendFailure(throwable);
        resume();
    }

    /**
     * Resume the HttpRequestPacket processing
     */
    private void resume()
    {
        ctx.resume(ctx.getStopAction());
    }
    
}