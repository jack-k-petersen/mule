/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.http.internal.sse;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.lineSeparator;
import static org.mule.api.config.ThreadingProfile.DEFAULT_THREADING_PROFILE;
import static org.mule.module.http.api.HttpConstants.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.mule.module.http.api.HttpHeaders.Names.CACHE_CONTROL;
import static org.mule.module.http.api.HttpHeaders.Names.CONTENT_LENGTH;
import static org.mule.module.http.api.HttpHeaders.Names.CONTENT_TYPE;
import static org.mule.module.http.api.HttpHeaders.Values.NO_CACHE;
import static org.mule.module.http.api.requester.HttpStreamingType.NEVER;
import static org.mule.module.http.internal.listener.DefaultHttpListenerConfig.DEFAULT_MAX_THREADS;
import static org.mule.module.http.internal.sse.DefaultHttpEventStreamListener.LAST_EVENT_ID_KEY;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.resource.spi.work.Work;
import javax.resource.spi.work.WorkException;

import org.mule.AbstractAnnotatedObject;
import org.mule.DefaultMuleMessage;
import org.mule.VoidMuleEvent;
import org.mule.api.DefaultMuleException;
import org.mule.api.MessagingException;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.config.ThreadingProfile;
import org.mule.api.context.MuleContextAware;
import org.mule.api.context.WorkManager;
import org.mule.api.context.WorkManagerSource;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.lifecycle.Lifecycle;
import org.mule.api.lifecycle.LifecycleUtils;
import org.mule.api.store.ListableObjectStore;
import org.mule.api.store.ObjectStoreException;
import org.mule.config.MutableThreadingProfile;
import org.mule.config.i18n.CoreMessages;
import org.mule.module.http.api.HttpConstants.HttpStatus;
import org.mule.module.http.internal.HttpParser;
import org.mule.module.http.internal.domain.ByteArrayHttpEntity;
import org.mule.module.http.internal.domain.request.HttpRequestContext;
import org.mule.module.http.internal.domain.response.HttpResponse;
import org.mule.module.http.internal.listener.DefaultHttpListenerConfig;
import org.mule.module.http.internal.listener.HttpResponseBuilder;
import org.mule.module.http.internal.listener.RequestHandlerManager;
import org.mule.module.http.internal.listener.async.HttpResponseReadyCallback;
import org.mule.module.http.internal.listener.async.RequestHandler;
import org.mule.module.http.internal.listener.async.ResponseStatusCallback;
import org.mule.module.http.internal.listener.matcher.ListenerRequestMatcher;
import org.mule.module.http.internal.listener.matcher.MethodRequestMatcher;
import org.mule.transport.NullPayload;
import org.mule.util.concurrent.ThreadNameHelper;
import org.mule.util.store.MuleObjectStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultHttpEventPublisherConfig extends AbstractAnnotatedObject implements Lifecycle, MuleContextAware
{

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultHttpEventPublisherConfig.class);

    private DefaultHttpListenerConfig config;

    private String name;
    private String path;
    private Integer retry = 3000;
    private ThreadingProfile publisherThreadingProfile;

    private HttpResponseBuilder responseBuilder;
    private HttpResponseBuilder errorResponseBuilder;
    private RequestHandlerManager requestHandlerManager;

    private boolean initialised;
    private boolean started = false;

    private ListableObjectStore<SseEvent> objectStore;

    private WorkManager workManager;

    private MuleContext muleContext;

    @Override
    public void initialise() throws InitialisationException
    {
        if (initialised)
        {
            return;
        }
        path = HttpParser.sanitizePathWithStartSlash(this.path);
        if (publisherThreadingProfile == null)
        {
            publisherThreadingProfile = new MutableThreadingProfile(DEFAULT_THREADING_PROFILE);
            publisherThreadingProfile.setMaxThreadsActive(DEFAULT_MAX_THREADS);
        }

        if (responseBuilder == null)
        {
            responseBuilder = HttpResponseBuilder.emptyInstance(muleContext);
        }

        LifecycleUtils.initialiseIfNeeded(responseBuilder);

        if (errorResponseBuilder == null)
        {
            errorResponseBuilder = HttpResponseBuilder.emptyInstance(muleContext);
        }

        LifecycleUtils.initialiseIfNeeded(errorResponseBuilder);

        responseBuilder.setResponseStreaming(NEVER);
        validatePath();

        try
        {
            requestHandlerManager = config.addRequestHandler(
                    new ListenerRequestMatcher(new MethodRequestMatcher("GET"), path), getRequestHandler());
        }
        catch (Exception e)
        {
            throw new InitialisationException(e, this);
        }

        if (objectStore == null)
        {
            objectStore = (ListableObjectStore<SseEvent>) ((MuleObjectStoreManager) muleContext.getObjectStoreManager())
                    .getUserObjectStore("event-publisher-config-" + this.getName(), true, -1, retry + 500, 30000);
        }

        initialised = true;
    }

    private void validatePath() throws InitialisationException
    {
        final String[] pathParts = this.path.split("/");
        List<String> uriParamNames = new ArrayList<>();
        for (String pathPart : pathParts)
        {
            if (pathPart.startsWith("{") && pathPart.endsWith("}"))
            {
                String uriParamName = pathPart.substring(1, pathPart.length() - 1);
                if (uriParamNames.contains(uriParamName))
                {
                    throw new InitialisationException(
                            CoreMessages.createStaticMessage(String.format(
                                    "Http Listener with path %s contains duplicated uri param names", this.path)),
                            this);
                }
                uriParamNames.add(uriParamName);
            }
            else
            {
                if (pathPart.contains("*") && pathPart.length() > 1)
                {
                    throw new InitialisationException(CoreMessages.createStaticMessage(String.format(
                            "Http Listener with path %s contains an invalid use of a wildcard. Wildcards can only be used at the end of the path (i.e.: /path/*) or between / characters (.i.e.: /path/*/anotherPath))",
                            this.path)), this);
                }
            }
        }
    }

    private Map<String, Writer> consumers = new ConcurrentHashMap<>();

    private RequestHandler getRequestHandler()
    {
        return new RequestHandler()
        {
            @Override
            public void handleRequest(HttpRequestContext requestContext, HttpResponseReadyCallback responseCallback)
            {
                HttpResponse httpResponse;
                try
                {
                    httpResponse = responseBuilder
                            .build(new org.mule.module.http.internal.domain.response.HttpResponseBuilder()
                                    .setStatusCode(200).setReasonPhrase("OK")
                                    .addHeader(CONTENT_TYPE, "text/event-stream")
                                    .addHeader(CACHE_CONTROL, NO_CACHE), new VoidMuleEvent()
                                    {
                                        @Override
                                        public MuleMessage getMessage()
                                        {
                                            return new DefaultMuleMessage(NullPayload.getInstance(), muleContext);
                                        }
                                    });
                }
                catch (MessagingException e)
                {
                    sendErrorResponse(INTERNAL_SERVER_ERROR, "Error subscribing to an SSE stream", responseCallback);
                    return;
                }

                final InetSocketAddress remoteHostAddress = requestContext.getClientConnection().getRemoteHostAddress();
                Writer writer = responseCallback.startResponse(httpResponse, new ResponseStatusCallback()
                {
                    @Override
                    public void responseSendFailure(Throwable exception)
                    {
                        synchronized (consumers)
                        {
                            consumers.remove(remoteHostAddress.getHostString() + ":" + remoteHostAddress.getPort());
                        }
                    }

                    @Override
                    public void responseSendSuccessfully()
                    {
                        synchronized (consumers)
                        {
                            consumers.remove(remoteHostAddress.getHostString() + ":" + remoteHostAddress.getPort());
                        }
                    }
                });
                
                long minTimestamp = currentTimeMillis() - retry;
                synchronized (consumers)
                {
                    consumers.put(remoteHostAddress.getHostString() + ":" + remoteHostAddress.getPort(), writer);
                }
                synchronized(writer)
                {
                    try
                    {
                        if(retry != 3000) {
                            writer.write("retry: " + retry + lineSeparator() + lineSeparator());
                        }
                        
                        if(requestContext.getRequest().getHeaderNames().contains(LAST_EVENT_ID_KEY))
                        {
                            String lastEventId = requestContext.getRequest().getHeaderValue(LAST_EVENT_ID_KEY);
                            
                            boolean lastEventIdSeen = false;
                            for (Object object : objectStore.allKeys())
                            {
                                if(lastEventIdSeen)
                                {
                                    SseEvent event = (SseEvent) objectStore.retrieve((String) object);
                                    if(event.getTimestamp() > minTimestamp) {
                                        event.write(writer);
                                    }
                                }
                                
                                if(object.equals(lastEventId))
                                {
                                    lastEventIdSeen = true;
                                }
                            }
                        }
//                        else {
//                            for (Object object : objectStore.allKeys())
//                            {
//                                SseEvent event = (SseEvent) objectStore.retrieve((String) object);
//                                if(event.getTimestamp() > minTimestamp) {
//                                    event.write(writer);
//                                }
//                            }
//                        }
                        writer.flush();
                    }
                    catch (ObjectStoreException | IOException e)
                    {
                        sendErrorResponse(INTERNAL_SERVER_ERROR, "Error subscribing to an SSE stream", responseCallback);
                    }
                }
            }

            private void sendErrorResponse(final HttpStatus status, String message, HttpResponseReadyCallback responseCallback)
            {

                byte[] responseData = message.getBytes();
                HttpResponse response = new org.mule.module.http.internal.domain.response.HttpResponseBuilder()
                    .setStatusCode(status.getStatusCode())
                    .setReasonPhrase(status.getReasonPhrase())
                    .setEntity(new ByteArrayHttpEntity(responseData))
                    .addHeader(CONTENT_LENGTH, Integer.toString(responseData.length))
                    .build();

                responseCallback.responseReady(response, new ResponseStatusCallback()
                {
                    @Override
                    public void responseSendFailure(Throwable exception)
                    {
                        LOGGER.warn("Error while sending {} response {}", status.getStatusCode(), exception.getMessage());
                        if (LOGGER.isDebugEnabled())
                        {
                            LOGGER.debug("Exception thrown", exception);
                        }
                    }

                    @Override
                    public void responseSendSuccessfully()
                    {
                    }
                });
            }
        };
    }

    @Override
    public synchronized void start() throws MuleException
    {
        if (started)
        {
            return;
        }

        workManager = createWorkManager();
        workManager.start();
        requestHandlerManager.start();

        try
        {
            startHeartbeat();
        }
        catch (WorkException e)
        {
            throw new DefaultMuleException(e);
        }
        
        started = true;
        LOGGER.info("Listening for SSE requests on " + config.getName());
    }

    private WorkManager createWorkManager()
    {
        final WorkManager workManager = publisherThreadingProfile.createWorkManager(
                format("%s%s.%s", ThreadNameHelper.getPrefix(muleContext), name, "publisher"),
                muleContext.getConfiguration().getShutdownTimeout());
        if (workManager instanceof MuleContextAware)
        {
            ((MuleContextAware) workManager).setMuleContext(muleContext);
        }
        return workManager;
    }

    private AtomicLong lastPublish = new AtomicLong(currentTimeMillis());
    private AtomicLong id = new AtomicLong(0);

    public void publishEvent(final String eventType, final String value) throws MuleException
    {
        try
        {
            final long eventId = id.getAndIncrement();
            
            final SseEvent sseEvent = new SseEvent("" + eventId, eventType, value);
            objectStore.store("" + eventId, sseEvent);
            
            final Set<Entry<String, Writer>> consumerEntries;
            synchronized (consumers)
            {
                consumerEntries = new HashSet<>(consumers.entrySet());
            }

            workManager.scheduleWork(new Work()
            {
                
                @Override
                public void run()
                {
                    Set<Object> toRemove = new HashSet<>();
                    for (Entry<String, Writer> entry : consumerEntries)
                    {
                        Writer writer = entry.getValue();
                        synchronized(writer)
                        {
                            try
                            {
                                sseEvent.write(writer);
                                writer.flush();
                            }
                            catch (IOException e)
                            {
                                LOGGER.warn(e.getClass().getName() + " publishing event: " + e.getMessage());
                                toRemove.add(entry.getKey());
                            }
                        }
                    }
                    
                    lastPublish.set(currentTimeMillis());
                    
                    synchronized (consumers)
                    {
                        for (Object key : toRemove)
                        {
                            consumers.remove(key);
                        }
                    }
                }
                
                @Override
                public void release()
                {
                }
            });
        }
        catch (WorkException e)
        {
            throw new DefaultMuleException(e);
        }
        
    }

    public void startHeartbeat() throws WorkException {
        workManager.scheduleWork(new Work()
        {
            
            @Override
            public void run()
            {
                while(15000 > currentTimeMillis() - lastPublish.get())
                {
                    try
                    {
                        Thread.sleep(15000 - (currentTimeMillis() - lastPublish.get()));
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                
                final Set<Entry<String, Writer>> consumerEntries;
                synchronized (consumers)
                {
                    consumerEntries = new HashSet<>(consumers.entrySet());
                }

                Set<Object> toRemove = new HashSet<>();
                for (Entry<String, Writer> entry : consumerEntries)
                {
                    Writer writer = entry.getValue();
                    synchronized(writer)
                    {
                        try
                        {
                            writer.write(": heartbeat");
                            writer.flush();
                        }
                        catch (IOException e)
                        {
                            LOGGER.warn(e.getClass().getName() + " publishing event: " + e.getMessage());
                            toRemove.add(entry.getKey());
                        }
                    }
                }
                synchronized (consumers)
                {
                    for (Object key : toRemove)
                    {
                        consumers.remove(key);
                    }
                }
                
            }
            
            @Override
            public void release()
            {
                // TODO Auto-generated method stub
                
            }
        });  
    }
    
    public static final class SseEvent implements Serializable
    {
        private static final long serialVersionUID = 191118921198317316L;

        private long timestamp;
        private String id;
        private String eventType;
        private String value;

        public SseEvent(String id, String eventType, String value)
        {
            this.timestamp = currentTimeMillis();
            this.id = id;
            this.eventType = eventType;
            this.value = value;
        }
        
        public long getTimestamp()
        {
            return timestamp;
        }

        private void write(Writer writer) throws IOException
        {
            writer.write("id: " + id + lineSeparator());
            if (eventType != null)
            {
                writer.write("event: " + eventType + lineSeparator());
            }

            BufferedReader reader = new BufferedReader(new StringReader(value));
            String line = reader.readLine();
            while (line != null)
            {
                writer.write("data: " + line + lineSeparator());
                line = reader.readLine();
            }
            writer.write(lineSeparator());
        }
        
    }

    @Override
    public void stop() throws MuleException
    {
        if (started)
        {
            requestHandlerManager.stop();

            synchronized (consumers)
            {
                for (Writer writer : consumers.values())
                {
                    try
                    {
                        synchronized (writer)
                        {
                            writer.close();
                        }
                    }
                    catch (IOException e)
                    {
                        LOGGER.warn(e.getClass().getName() + " stopping " + this.getClass().getSimpleName() + " " + getName() + ": " + e.getMessage());
                        if (LOGGER.isDebugEnabled())
                        {
                            LOGGER.debug(e.getMessage(), e);
                        }
                    }
                }
                consumers.clear();
            }

            try
            {
                workManager.dispose();
            }
            catch (Exception e)
            {
                LOGGER.warn("Failure shutting down work manager " + e.getMessage());
                if (LOGGER.isDebugEnabled())
                {
                    LOGGER.debug(e.getMessage(), e);
                }
            }
            finally
            {
                workManager = null;
            }
            started = false;
            LOGGER.info("Stopped SSE listener on " + config.getName());
        }
    }

    @Override
    public void dispose()
    {
        requestHandlerManager.dispose();
    }

    public DefaultHttpListenerConfig getConfig()
    {
        return config;
    }

    public void setConfig(DefaultHttpListenerConfig config)
    {
        this.config = config;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getPath()
    {
        return path;
    }

    public void setPath(String path)
    {
        this.path = path;
    }

    public Integer getRetry()
    {
        return retry;
    }

    public void setRetry(Integer retry)
    {
        this.retry = retry;
    }

    public HttpResponseBuilder getResponseBuilder()
    {
        return responseBuilder;
    }

    public void setResponseBuilder(HttpResponseBuilder responseBuilder)
    {
        this.responseBuilder = responseBuilder;
    }

    public HttpResponseBuilder getErrorResponseBuilder()
    {
        return errorResponseBuilder;
    }

    public void setErrorResponseBuilder(HttpResponseBuilder errorResponseBuilder)
    {
        this.errorResponseBuilder = errorResponseBuilder;
    }

    public ListableObjectStore<SseEvent> getObjectStore()
    {
        return objectStore;
    }
    
    public void setObjectStore(ListableObjectStore<SseEvent> objectStore)
    {
        this.objectStore = objectStore;
    }
    
    public ThreadingProfile getPublisherThreadingProfile()
    {
        return publisherThreadingProfile;
    }
    
    public void setPublisherThreadingProfile(ThreadingProfile publisherThreadingProfile)
    {
        this.publisherThreadingProfile = publisherThreadingProfile;
    }

    @Override
    public void setMuleContext(MuleContext muleContext)
    {
        this.muleContext = muleContext;
    }
}
