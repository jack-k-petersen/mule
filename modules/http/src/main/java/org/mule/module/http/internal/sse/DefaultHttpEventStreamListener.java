/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.http.internal.sse;

import static java.lang.Long.parseLong;
import static java.lang.String.format;
import static java.lang.System.lineSeparator;
import static java.lang.Thread.currentThread;
import static java.util.regex.Pattern.compile;
import static org.glassfish.grizzly.http.util.Header.Accept;
import static org.glassfish.grizzly.http.util.Header.CacheControl;
import static org.glassfish.grizzly.http.util.Header.ContentType;
import static org.mule.MessageExchangePattern.ONE_WAY;
import static org.mule.api.MuleEvent.TIMEOUT_WAIT_FOREVER;
import static org.mule.api.transformer.DataType.STRING_DATA_TYPE;
import static org.mule.config.i18n.MessageFactory.createStaticMessage;
import static org.mule.module.http.api.HttpHeaders.Names.COOKIE;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.activation.DataHandler;

import org.mule.DefaultMuleEvent;
import org.mule.DefaultMuleMessage;
import org.mule.api.DefaultMuleException;
import org.mule.api.MessagingException;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleRuntimeException;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.construct.FlowConstructAware;
import org.mule.api.context.MuleContextAware;
import org.mule.api.context.WorkManager;
import org.mule.api.context.notification.ClusterNodeNotificationListener;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.lifecycle.LifecycleUtils;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.registry.RegistrationException;
import org.mule.api.source.MessageSource;
import org.mule.api.store.ListableObjectStore;
import org.mule.api.store.ObjectStoreException;
import org.mule.api.transaction.TransactionConfig;
import org.mule.context.notification.ClusterNodeNotification;
import org.mule.execution.FlowProcessingPhaseTemplate;
import org.mule.execution.MessageProcessContext;
import org.mule.execution.MessageProcessingManager;
import org.mule.module.http.api.HttpAuthentication;
import org.mule.module.http.api.sse.HttpServerSent;
import org.mule.module.http.internal.HttpParser;
import org.mule.module.http.internal.domain.request.HttpRequest;
import org.mule.module.http.internal.domain.request.HttpRequestAuthentication;
import org.mule.module.http.internal.domain.request.HttpRequestBuilder;
import org.mule.module.http.internal.request.DefaultHttpAuthentication;
import org.mule.module.http.internal.request.DefaultHttpRequesterConfig;
import org.mule.module.http.internal.request.HttpRequester;
import org.mule.module.http.internal.request.HttpRequesterRequestBuilder;
import org.mule.module.http.internal.request.ResponseValidator;
import org.mule.module.http.internal.request.SuccessStatusCodeValidator;
import org.mule.module.http.internal.request.grizzly.GrizzlyHttpClient;
import org.mule.session.DefaultMuleSession;
import org.mule.util.AttributeEvaluator;
import org.mule.util.store.MuleObjectStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Request;

public class DefaultHttpEventStreamListener implements HttpServerSent, HttpRequester, MuleContextAware, FlowConstructAware
{

    public static final int DEFAULT_MAX_THREADS = 128;
    private static final int DEFAULT_RECONNECTION_TIME = 3000;

    private static final Logger logger = LoggerFactory.getLogger(DefaultHttpEventStreamListener.class);

    private static final Pattern EVENT_LINE_PATTERN = compile("^(?!:.*)([^:\\r\\n]+)(?:: ?(.*)?)?");
    public static final String LAST_EVENT_ID_KEY = "Last-Event-ID";
    public static final String RECONNECTION_TIME_KEY = "Reconnection-Time";
    private static final String TEXT_EVENT_STREAM_MEDIA_TYPE = "text/event-stream";

    public static final String DEFAULT_FOLLOW_REDIRECTS = "true";

    private DefaultHttpRequesterConfig requestConfig;
    private HttpRequesterRequestBuilder requestBuilder;
    private ResponseValidator responseValidator = new SuccessStatusCodeValidator("0..399");

    private AttributeEvaluator host = new AttributeEvaluator(null);
    private AttributeEvaluator port = new AttributeEvaluator(null);
    private AttributeEvaluator basePath = new AttributeEvaluator(null);
    private AttributeEvaluator path = new AttributeEvaluator(null);
    private AttributeEvaluator url = new AttributeEvaluator(null);

    private AttributeEvaluator method = new AttributeEvaluator("GET");
    private AttributeEvaluator followRedirects = new AttributeEvaluator(null);

    private AttributeEvaluator requestStreamingMode = new AttributeEvaluator("true");
    private AttributeEvaluator sendBodyMode = new AttributeEvaluator(null);
    private AttributeEvaluator parseResponse = new AttributeEvaluator(null);
    private AttributeEvaluator responseTimeout = new AttributeEvaluator(null);

    private AtomicBoolean started = new AtomicBoolean(false);
    private AtomicBoolean connected = new AtomicBoolean(false);
    private ListenableFuture<String> sseResponse;
    
    private MuleContext muleContext;
    private FlowConstruct flowConstruct;

    private final MessageProcessContext messageProcessContext = new MessageProcessContext()
    {
        
        @Override
        public boolean supportsAsynchronousProcessing()
        {
            return false;
        }
        
        @Override
        public MessageSource getMessageSource()
        {
            return DefaultHttpEventStreamListener.this;
        }
        
        @Override
        public FlowConstruct getFlowConstruct()
        {
            return flowConstruct;
        }
        
        @Override
        public WorkManager getFlowExecutionWorkManager()
        {
            return null;
        }
        
        @Override
        public TransactionConfig getTransactionConfig()
        {
            return null;
        }
        
        @Override
        public ClassLoader getExecutionClassLoader()
        {
            return muleContext.getExecutionClassLoader();
        }
        
    };

    private ListableObjectStore objectStore;

    private MessageProcessor listener;
    private MessageProcessingManager messageProcessingManager;


    @Override
    public void setListener(MessageProcessor listener)
    {
        this.listener = listener;
    }

    @Override
    public void initialise() throws InitialisationException
    {
        if (requestConfig == null)
        {
            throw new InitialisationException(createStaticMessage("The config-ref attribute is required in the HTTP request element"), this);
        }
        if (requestBuilder == null)
        {
            requestBuilder = new HttpRequesterRequestBuilder();
        }
        LifecycleUtils.initialiseIfNeeded(requestBuilder);

        setEmptyAttributesFromConfig();
        validateRequiredProperties();

        basePath = new AttributeEvaluator(requestConfig.getBasePath());

        initializeAttributeEvaluators(host, port, method, path, basePath, url, followRedirects,
                                      requestStreamingMode, sendBodyMode, parseResponse, responseTimeout);

        try
        {
            messageProcessingManager = muleContext.getRegistry().lookupObject(MessageProcessingManager.class);
        }
        catch (RegistrationException e)
        {
            throw new InitialisationException(e, this);
        }

        if (objectStore == null)
        {
            objectStore = (ListableObjectStore) ((MuleObjectStoreManager) muleContext.getObjectStoreManager()).getUserObjectStore("event-stream-listener-" + this.flowConstruct.getName(), true);
        }
    }

    private void setEmptyAttributesFromConfig()  throws InitialisationException
    {
        if (host.getRawValue() == null)
        {
            setHost(requestConfig.getHost());
        }

        if (port.getRawValue() == null)
        {
            setPort(requestConfig.getPort());
        }

        if (followRedirects.getRawValue() == null)
        {
            String requestFollowRedirect = requestConfig.getFollowRedirects();
            if (requestFollowRedirect == null)
            {
                requestFollowRedirect = DEFAULT_FOLLOW_REDIRECTS;
            }
            setFollowRedirects(requestFollowRedirect);
        }

        if (sendBodyMode.getRawValue() == null)
        {
            setSendBodyMode(requestConfig.getSendBodyMode());
        }

        if (parseResponse.getRawValue() == null)
        {
            setParseResponse(requestConfig.getParseResponse());
        }

        if (responseTimeout.getRawValue() == null && requestConfig.getResponseTimeout() != null)
        {
            setResponseTimeout(requestConfig.getResponseTimeout());
        }
    }

    private void initializeAttributeEvaluators(AttributeEvaluator ... attributeEvaluators)
    {
        for (AttributeEvaluator attributeEvaluator : attributeEvaluators)
        {
            if (attributeEvaluator != null)
            {
                attributeEvaluator.initialize(muleContext.getExpressionManager());
            }
        }
    }

    private void validateRequiredProperties() throws InitialisationException
    {
        if (url.getRawValue() == null)
        {
            if (host.getRawValue() == null)
            {
                throw new InitialisationException(createStaticMessage("No host defined. Set the host attribute " +
                                                                      "either in the request or request-config elements"), this);
            }
            if (port.getRawValue() == null)
            {
                throw new InitialisationException(createStaticMessage("No port defined. Set the host attribute " +
                                                                      "either in the request or request-config elements"), this);
            }
            if (path.getRawValue() == null)
            {
                throw new InitialisationException(createStaticMessage("The path attribute is required in the HTTP request element"), this);
            }
        }
    }

    @Override
    public void start() throws MuleException
    {
        synchronized (started)
        {
            started.set(true);

            if (muleContext.isPrimaryPollingInstance())
            {
                connect();
            }
            else
            {
                muleContext.registerListener(new ClusterNodeNotificationListener<ClusterNodeNotification>()
                {
                    @Override
                    public void onNotification(ClusterNodeNotification notification)
                    {
                        // Notification thread is bound to the MuleContainerSystemClassLoader, save it 
                        // so we can restore it later
                        ClassLoader notificationClassLoader = currentThread().getContextClassLoader();
                        try
                        {
                            // The connection should use instead the ApplicationClassloader
                            currentThread().setContextClassLoader(muleContext.getExecutionClassLoader());
                            
                            DefaultHttpEventStreamListener.this.connect();
                        }
                        catch (Exception e)
                        {
                            throw new MuleRuntimeException(e);
                        }
                        finally 
                        {
                            // Restore the notification original class loader so we don't interfere in any later
                            // usage of this thread
                            currentThread().setContextClassLoader(notificationClassLoader);
                        }
                    }
                });
            }

        }
    }

    private void connect() throws MuleException, DefaultMuleException
    {
        synchronized (connected)
        {
            connected.set(true);

            GrizzlyHttpClient grizzlyHttpClient = (GrizzlyHttpClient)(requestConfig.getHttpClient());
            
            final HttpRequest httpRequest = createHttpRequest(requestConfig.getAuthentication());
            
            final Request ghcReq;
            try
            {
                ghcReq = grizzlyHttpClient.createGrizzlyRequest(httpRequest, resolveResponseTimeout(null), followRedirects.resolveBooleanValue(null), resolveAuthentication(null));
            }
            catch (IOException e)
            {
                throw new DefaultMuleException(e);
            }
            
            AsyncHttpClient asyncHttpClient = grizzlyHttpClient.getAsyncHttpClient();
            
            sseResponse = asyncHttpClient.executeRequest(ghcReq, new AsyncHandler<String>() {
                
                @Override
                public void onThrowable(Throwable t)
                {
                    if(!(t instanceof CancellationException)) {
                        logger.error("Error executing SSE HTTP request. Will attempt to reconnect: ({}) {}", t.getClass().getName(), t.getMessage());
                        reconnect();
                    }
                    else
                    {
                        logger.error("Error executing SSE HTTP request: ({}) {}", t.getClass().getName(), t.getMessage());
                        disconnect();
                    }
                }
                
                private List<String> lines = new ArrayList<>();
                private boolean incompleteBody = false;

                @Override
                public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception
                {
                    final String body = new String(bodyPart.getBodyPartBytes());
                    
                    BufferedReader bodyReader = new BufferedReader(new StringReader(body));
                    
                    String line = bodyReader.readLine();
                    do {
                        if(incompleteBody)
                        {
                            if(lines.isEmpty())
                            {
                                lines.add(line);
                            }
                            else
                            {
                                lines.add(lines.remove(lines.size() - 1) + line);
                            }
                            incompleteBody = false;
                        }
                        else
                        {
                            lines.add(line);
                        }
                        
                        line = bodyReader.readLine();
                        
                        // An empty line marks the end of the event.
                        if("".equals(line))
                        {
                            synchronized (DefaultHttpEventStreamListener.this.connected)
                            {
                                if(DefaultHttpEventStreamListener.this.connected.get())
                                {
                                    List<String> nonCommentLines = new ArrayList<>();
                                    for (String l : lines)
                                    {
                                        if(!l.startsWith(":"))
                                        {
                                            nonCommentLines.add(l);
                                        }
                                    }
                                    
                                    if(!nonCommentLines.isEmpty()) {
                                        DefaultMuleEvent event = buildEvent(nonCommentLines);
                                        // Cannot check for the length of the payload because empty data is valid according to the spec
                                        if(event != null)
                                        {
                                            process(event, body);
                                        }
                                        else
                                        {
                                            logger.warn("Some or all lines from the response of '{}' could not be processed", ghcReq.getUrl());
                                        }
                                    }
                                }
                                else
                                {
                                    return STATE.ABORT;
                                }
                            }
                            
                            lines.clear();
                            line = bodyReader.readLine();
                        }
                    }
                    while (line != null);
                    
                    incompleteBody = !(body.endsWith("\r") && body.endsWith("\n"));

                    return STATE.CONTINUE;
                }
                
                private void process(final MuleEvent event, final String body) throws MessagingException
                {
                    final FlowProcessingPhaseTemplate httpMessageProcessorTemplate = new FlowProcessingPhaseTemplate() {
                        
                        @Override
                        public MuleEvent getMuleEvent() throws MuleException
                        {
                            return event;
                        }
                        
                        @Override
                        public Object getOriginalMessage() throws MuleException
                        {
                            return body;
                        }
                        
                        @Override
                        public MuleEvent beforeRouteEvent(MuleEvent muleEvent) throws MuleException
                        {
                            return muleEvent;
                        }
                        
                        @Override
                        public MuleEvent routeEvent(MuleEvent muleEvent) throws MuleException
                        {
                            return listener.process(muleEvent);
                        }
                        
                        @Override
                        public MuleEvent afterRouteEvent(MuleEvent muleEvent) throws MuleException
                        {
                            return muleEvent;
                        }
                        
                        @Override
                        public void afterSuccessfulProcessingFlow(MuleEvent muleEvent) throws MuleException
                        {
                            
                        }
                        
                        @Override
                        public void afterFailureProcessingFlow(MessagingException messagingException) throws MuleException
                        {
                            logger.error("", messagingException);
                        }
                        
                        @Override
                        public void afterFailureProcessingFlow(MuleException exception) throws MuleException
                        {
                            logger.error("", exception);
                        }
                        
                    };
                    messageProcessingManager.processMessage(httpMessageProcessorTemplate, messageProcessContext);
                }

                private DefaultMuleEvent buildEvent(final List<String> lines) throws MessagingException
                {
                    StringBuilder data = new StringBuilder();
                    Map<String, Object> meta = new HashMap<>();
                    
                    boolean dataPresent = false;
                    
                    for (String line : lines)
                    {
                        Matcher matcher = EVENT_LINE_PATTERN.matcher(line);
                        
                        if(matcher.matches())
                        {
                            String field = matcher.group(1);
                            String value = matcher.group(2) == null ? "" : matcher.group(2);
                            
                            if("data".equals(field))
                            {
                                dataPresent = true;
                                data.append(value + lineSeparator());
                            }
                            else
                            {
                                meta.put(field, value);
                                
                                if("id".equals(field))
                                {
                                    try
                                    {
                                        if(objectStore.contains(LAST_EVENT_ID_KEY)) {
                                            objectStore.remove(LAST_EVENT_ID_KEY);
                                        }
                                        if(!"".equals(value))
                                        {
                                            objectStore.store(LAST_EVENT_ID_KEY, value);
                                        }
                                    }
                                    catch (ObjectStoreException e)
                                    {
                                        logger.error("Exception while trying to store 'lastEventId'. Value will be ignored.", e);
                                    }
                                }
                                else if("retry".equals(field))
                                {
                                    try
                                    {
                                        if(objectStore.contains(RECONNECTION_TIME_KEY)) {
                                            objectStore.remove(RECONNECTION_TIME_KEY);
                                        }
                                        objectStore.store(RECONNECTION_TIME_KEY, parseLong(value));
                                    }
                                    catch (ObjectStoreException|NumberFormatException e)
                                    {
                                        logger.error("Exception while trying to store 'lastEventId' Value will be ignored.", e);
                                    }
                                }
                            }
                        }
                    }
                    
                    if(dataPresent) {
                        DefaultMuleMessage message = new DefaultMuleMessage(data.deleteCharAt(data.length() - 1).toString(),
                                meta, Collections.<String, Object>emptyMap(), Collections.<String, DataHandler>emptyMap(), muleContext, STRING_DATA_TYPE);
                        return new DefaultMuleEvent(message, URI.create(resolveURI(null)), ONE_WAY, flowConstruct, new DefaultMuleSession());
                    } else {
                        return null;
                    }
                }
                
                @Override
                public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception
                {
                    if (responseValidator.isValid(responseStatus.getStatusCode()))
                    {
                        return STATE.CONTINUE;
                    }
                    else
                    {
                        logger.warn("Error code returned as response for SSE HTTP request");
                        return STATE.ABORT;
                    }
                    
                }
                
                @Override
                public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception
                {
                    String contentType = headers.getHeaders().getFirstValue(ContentType.toString());
                    if(TEXT_EVENT_STREAM_MEDIA_TYPE.equals(contentType))
                    {
                        return STATE.CONTINUE;
                    }
                    else
                    {
                        logger.error("Unsupported Content-Type {} received for {}", contentType , ghcReq.getUrl());
                        return STATE.ABORT;
                    }
                    
                }
                
                @Override
                public String onCompleted() throws Exception
                {
                    if (!lines.isEmpty())
                    {
                        logger.warn("Some or all lines from the response of '{}' could not be processed", ghcReq.getUrl());
                    }

                    return "Finished";
                }
                
            });
        }
    }

    private HttpRequestAuthentication resolveAuthentication(MuleEvent event)
    {
        HttpRequestAuthentication requestAuthentication = null;

        if (requestConfig.getAuthentication() instanceof DefaultHttpAuthentication)
        {
            requestAuthentication = ((DefaultHttpAuthentication)requestConfig.getAuthentication()).resolveRequestAuthentication(event);
        }
        return requestAuthentication;
    }

    private int resolveResponseTimeout(MuleEvent muleEvent)
    {
        if (muleContext.getConfiguration().isDisableTimeouts())
        {
            return TIMEOUT_WAIT_FOREVER;
        }
        if (responseTimeout.getRawValue() == null)
        {
            return muleContext.getConfiguration().getDefaultResponseTimeout();
        }
        else
        {
            return responseTimeout.resolveIntegerValue(muleEvent);
        }
    }

    private String replaceUriParams(String path, MuleEvent event)
    {
        if (requestBuilder == null)
        {
            return path;
        }
        else
        {
            return requestBuilder.replaceUriParams(path, event);
        }
    }

    private String resolveURI(MuleEvent muleEvent) throws MessagingException
    {
        if (url.getRawValue() != null)
        {
            return url.resolveStringValue(muleEvent);
        }
        else
        {
            String resolvedPath = replaceUriParams(buildPath(basePath.resolveStringValue(muleEvent),
                                                             path.resolveStringValue(muleEvent)), muleEvent);

            // Encode spaces to generate a valid HTTP request.
            resolvedPath = HttpParser.encodeSpaces(resolvedPath);

            return format("%s://%s:%s%s", requestConfig.getScheme(), host.resolveStringValue(muleEvent),
                    port.resolveIntegerValue(muleEvent), resolvedPath);
        }

    }

    protected String buildPath(String basePath, String path)
    {
        String resolvedBasePath = basePath;
        String resolvedRequestPath = path;

        if (!resolvedBasePath.startsWith("/"))
        {
            resolvedBasePath = "/" + resolvedBasePath;
        }

        if (resolvedBasePath.endsWith("/") && resolvedRequestPath.startsWith("/"))
        {
            resolvedBasePath = resolvedBasePath.substring(0, resolvedBasePath.length() - 1);
        }

        if (!resolvedBasePath.endsWith("/") && !resolvedRequestPath.startsWith("/") && !resolvedRequestPath.isEmpty())
        {
            resolvedBasePath += "/";
        }


        return resolvedBasePath + resolvedRequestPath;

    }

    private HttpRequest createHttpRequest(HttpAuthentication authentication) throws MuleException
    {
        HttpRequestBuilder builder = new HttpRequestBuilder();

        String resolvedUri = resolveURI(null);
        String resolvedMethod = method.resolveStringValue(null);
        builder.setUri(resolvedUri);
        builder.setMethod(resolvedMethod);
        builder.setHeaders(requestBuilder.getHeaders(null));
        builder.addHeader(CacheControl.toString(), "no-cache");
        builder.addHeader(Accept.toString(), TEXT_EVENT_STREAM_MEDIA_TYPE);
        
        if(objectStore.contains(LAST_EVENT_ID_KEY))
        {
            builder.addHeader(LAST_EVENT_ID_KEY, objectStore.retrieve(LAST_EVENT_ID_KEY).toString());
        }
        
        builder.setQueryParams(requestBuilder.getQueryParams(null));

        if (getConfig().isEnableCookies())
        {
            try
            {
                Map<String, List<String>> headers = getConfig().getCookieManager().get(URI.create(resolvedUri),
                                                                                                 Collections.<String, List<String>>emptyMap());
                List<String> cookies = headers.get(COOKIE);
                if (cookies != null)
                {
                    for (String cookie : cookies)
                    {
                        builder.addHeader(COOKIE, cookie);
                    }
                }
            }
            catch (IOException e)
            {
                logger.warn("Error reading cookies for URI " + resolvedUri, e);
            }

        }

        if (authentication != null)
        {
            authentication.authenticate(null, builder);
        }
        return builder.build();
    }

    @Override
    public void stop() throws MuleException
    {
        synchronized (started)
        {
            synchronized (connected)
            {
                if(sseResponse != null)
                {
                    sseResponse.cancel(true);
                    sseResponse = null;
                }
                connected.set(false);
            }
            started.set(false);
        }
    }

    private void reconnect()
    {
        synchronized (connected)
        {
            try
            {
                sseResponse.cancel(true);
                if(objectStore.contains(RECONNECTION_TIME_KEY))
                {
                    Thread.sleep((Long)objectStore.retrieve(RECONNECTION_TIME_KEY));
                }
                else
                {
                    Thread.sleep(DEFAULT_RECONNECTION_TIME);
                }
                connect();
            }
            catch (MuleException e)
            {
                logger.error("Error restarting SSE dource", e);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void disconnect()
    {
        synchronized (connected)
        {
            sseResponse.cancel(true);
        }
    }
    
    @Override
    public void dispose()
    {
    }

    public DefaultHttpRequesterConfig getConfig()
    {
        return requestConfig;
    }

    public void setConfig(DefaultHttpRequesterConfig requestConfig)
    {
        this.requestConfig = requestConfig;
    }

    public HttpRequesterRequestBuilder getRequestBuilder()
    {
        return requestBuilder;
    }

    public void setRequestBuilder(HttpRequesterRequestBuilder requestBuilder)
    {
        this.requestBuilder = requestBuilder;
    }

    public ResponseValidator getResponseValidator()
    {
        return responseValidator;
    }

    public void setResponseValidator(ResponseValidator responseValidator)
    {
        this.responseValidator = responseValidator;
    }

    public String getHost()
    {
        return host.getRawValue();
    }

    public void setHost(String host)
    {
        this.host = new AttributeEvaluator(host);
    }

    public String getPort()
    {
        return port.getRawValue();
    }

    public void setPort(String port)
    {
        this.port = new AttributeEvaluator(port);
    }

    public String getPath()
    {
        return path.getRawValue();
    }

    public void setPath(String path)
    {
        this.path = new AttributeEvaluator(path);
    }

    public String getUrl()
    {
        return url.getRawValue();
    }

    public void setUrl(String url)
    {
        this.url = new AttributeEvaluator(url);
    }

    public String getMethod()
    {
        return method.getRawValue();
    }

    public void setMethod(String method)
    {
        this.method = new AttributeEvaluator(method);
    }

    public void setFollowRedirects(String followsRedirects)
    {
        this.followRedirects = new AttributeEvaluator(followsRedirects);
    }

    public void setSendBodyMode(String sendBodyMode)
    {
        this.sendBodyMode = new AttributeEvaluator(sendBodyMode);
    }

    public void setParseResponse(String parseResponse)
    {
        this.parseResponse = new AttributeEvaluator(parseResponse);
    }

    public void setResponseTimeout(String responseTimeout)
    {
        this.responseTimeout = new AttributeEvaluator(responseTimeout);
    }

    public MessageProcessor getListener()
    {
        return listener;
    }

    @Override
    public void setMuleContext(MuleContext muleContext)
    {
        this.muleContext = muleContext;
    }
    
    @Override
    public void setFlowConstruct(FlowConstruct flowConstruct)
    {
        this.flowConstruct = flowConstruct;
    }
    
    public ListableObjectStore getObjectStore()
    {
        return objectStore;
    }
    
    public void setObjectStore(ListableObjectStore objectStore)
    {
        this.objectStore = objectStore;
    }

}
