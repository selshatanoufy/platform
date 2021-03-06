/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.discovery.client;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.io.CharStreams;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import com.google.inject.Inject;
import com.proofpoint.http.client.AsyncHttpClient;
import com.proofpoint.http.client.CacheControl;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.Response;
import com.proofpoint.http.client.ResponseHandler;
import com.proofpoint.json.JsonCodec;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.units.Duration;

import javax.inject.Provider;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static com.proofpoint.http.client.JsonBodyGenerator.jsonBodyGenerator;
import static com.proofpoint.http.client.Request.Builder.prepareDelete;
import static com.proofpoint.http.client.Request.Builder.preparePut;

public class HttpDiscoveryAnnouncementClient implements DiscoveryAnnouncementClient
{
    private static final MediaType MEDIA_TYPE_JSON = MediaType.create("application", "json");

    private final Provider<URI> discoveryServiceURI;
    private final NodeInfo nodeInfo;
    private final JsonCodec<Announcement> announcementCodec;
    private final AsyncHttpClient httpClient;

    @Inject
    public HttpDiscoveryAnnouncementClient(@ForDiscoveryClient Provider<URI> discoveryServiceURI,
            NodeInfo nodeInfo,
            JsonCodec<Announcement> announcementCodec,
            @ForDiscoveryClient AsyncHttpClient httpClient)
    {
        Preconditions.checkNotNull(discoveryServiceURI, "discoveryServiceURI is null");
        Preconditions.checkNotNull(nodeInfo, "nodeInfo is null");
        Preconditions.checkNotNull(announcementCodec, "announcementCodec is null");
        Preconditions.checkNotNull(httpClient, "httpClient is null");

        this.nodeInfo = nodeInfo;
        this.discoveryServiceURI = discoveryServiceURI;
        this.announcementCodec = announcementCodec;
        this.httpClient = httpClient;
    }

    @Override
    public CheckedFuture<Duration, DiscoveryException> announce(Set<ServiceAnnouncement> services)
    {
        Preconditions.checkNotNull(services, "services is null");

        URI uri = discoveryServiceURI.get();
        if (uri == null) {
            return Futures.immediateFailedCheckedFuture(new DiscoveryException("No discovery servers are available"));
        }

        Announcement announcement = new Announcement(nodeInfo.getEnvironment(), nodeInfo.getNodeId(), nodeInfo.getPool(), nodeInfo.getLocation(), services);
        Request request = preparePut()
                .setUri(URI.create(uri + "/v1/announcement/" + nodeInfo.getNodeId()))
                .setHeader("User-Agent", nodeInfo.getNodeId())
                .setHeader("Content-Type", MEDIA_TYPE_JSON.toString())
                .setBodyGenerator(jsonBodyGenerator(announcementCodec, announcement))
                .build();
        return httpClient.executeAsync(request, new DiscoveryResponseHandler<Duration>("Announcement")
        {
            @Override
            public Duration handle(Request request, Response response)
                    throws DiscoveryException
            {
                int statusCode = response.getStatusCode();
                if (!isSuccess(statusCode)) {
                    throw new DiscoveryException(String.format("Announcement failed with status code %s: %s", statusCode, getBodyForError(response)));
                }

                Duration maxAge = extractMaxAge(response);
                return maxAge;
            }
        });
    }

    private boolean isSuccess(int statusCode)
    {
        return statusCode / 100 == 2;
    }

    private static String getBodyForError(Response response)
    {
        try {
            return CharStreams.toString(new InputStreamReader(response.getInputStream(), Charsets.UTF_8));
        }
        catch (IOException e) {
            return "(error getting body)";
        }
    }

    @Override
    public CheckedFuture<Void, DiscoveryException> unannounce()
    {
        URI uri = discoveryServiceURI.get();
        if (uri == null) {
            return Futures.immediateFailedCheckedFuture(new DiscoveryException("No discovery servers are available"));
        }

        Request request = prepareDelete()
                .setUri(URI.create(uri + "/v1/announcement/" + nodeInfo.getNodeId()))
                .setHeader("User-Agent", nodeInfo.getNodeId())
                .build();
        return httpClient.executeAsync(request, new DiscoveryResponseHandler<Void>("Unannouncement"));
    }

    private Duration extractMaxAge(Response response)
    {
        String header = response.getHeader(HttpHeaders.CACHE_CONTROL);
        if (header != null) {
            CacheControl cacheControl = CacheControl.valueOf(header);
            if (cacheControl.getMaxAge() > 0) {
                return new Duration(cacheControl.getMaxAge(), TimeUnit.SECONDS);
            }
        }
        return DEFAULT_DELAY;
    }

    private class DiscoveryResponseHandler<T> implements ResponseHandler<T, DiscoveryException>
    {
        private final String name;

        protected DiscoveryResponseHandler(String name)
        {
            this.name = name;
        }

        @Override
        public T handle(Request request, Response response)
        {
            return null;
        }

        @Override
        public final DiscoveryException handleException(Request request, Exception exception)
        {
            if (exception instanceof InterruptedException) {
                return new DiscoveryException(name + " was interrupted");
            }
            if (exception instanceof CancellationException) {
                return new DiscoveryException(name + " was canceled");
            }
            if (exception instanceof DiscoveryException) {
                return (DiscoveryException) exception;
            }

            return new DiscoveryException(name + " failed", exception);
        }
    }
}
