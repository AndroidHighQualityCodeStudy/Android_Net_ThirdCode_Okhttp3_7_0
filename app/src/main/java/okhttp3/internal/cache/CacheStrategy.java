/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.cache;

import java.util.Date;

import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.http.HttpDate;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.StatusLine;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Given a request and cached response, this figures out whether to use the network, the cache, or
 * both.
 * <p>
 * <p>Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since"
 * header for conditional GETs) or warnings to the cached response (if the cached data is
 * potentially stale).
 * <p>
 * Http缓存
 */
public final class CacheStrategy {
    /**
     * The request to send on the network, or null if this call doesn't use the network.
     */
    public final Request networkRequest;

    /**
     * The cached response to return or validate; or null if this call doesn't use a cache.
     */
    public final Response cacheResponse;

    CacheStrategy(Request networkRequest, Response cacheResponse) {
        this.networkRequest = networkRequest;
        this.cacheResponse = cacheResponse;
    }

    /**
     * Returns true if {@code response} can be stored to later serve another request.
     */
    public static boolean isCacheable(Response response, Request request) {
        // Always go to network for uncacheable response codes (RFC 7231 section 6.1),
        // This implementation doesn't support caching partial content.
        switch (response.code()) {
            case HTTP_OK:
            case HTTP_NOT_AUTHORITATIVE:
            case HTTP_NO_CONTENT:
            case HTTP_MULT_CHOICE:
            case HTTP_MOVED_PERM:
            case HTTP_NOT_FOUND:
            case HTTP_BAD_METHOD:
            case HTTP_GONE:
            case HTTP_REQ_TOO_LONG:
            case HTTP_NOT_IMPLEMENTED:
            case StatusLine.HTTP_PERM_REDIRECT:
                // These codes can be cached unless headers forbid it.
                break;

            case HTTP_MOVED_TEMP:
            case StatusLine.HTTP_TEMP_REDIRECT:
                // These codes can only be cached with the right response headers.
                // http://tools.ietf.org/html/rfc7234#section-3
                // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
                if (response.header("Expires") != null
                        || response.cacheControl().maxAgeSeconds() != -1
                        || response.cacheControl().isPublic()
                        || response.cacheControl().isPrivate()) {
                    break;
                }
                // Fall-through.

            default:
                // All other codes cannot be cached.
                return false;
        }

        // A 'no-store' directive on request or response prevents the response from being cached.
        return !response.cacheControl().noStore() && !request.cacheControl().noStore();
    }

    public static class Factory {
        final long nowMillis;
        final Request request;
        final Response cacheResponse;

        /**
         * The server's time when the cached response was served, if known.
         */
        private Date servedDate;
        private String servedDateString;

        /**
         * The last modified date of the cached response, if known.
         */
        private Date lastModified;
        private String lastModifiedString;

        /**
         * The expiration date of the cached response, if known. If both this field and the max age are
         * set, the max age is preferred.
         */
        private Date expires;

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
         * first initiated.
         */
        private long sentRequestMillis;

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
         * first received.
         */
        private long receivedResponseMillis;

        /**
         * Etag of the cached response.
         */
        private String etag;

        /**
         * Age of the cached response.
         */
        private int ageSeconds = -1;

        public Factory(long nowMillis, Request request, Response cacheResponse) {
            this.nowMillis = nowMillis;
            this.request = request;
            this.cacheResponse = cacheResponse;

            if (cacheResponse != null) {
                this.sentRequestMillis = cacheResponse.sentRequestAtMillis();
                this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis();
                Headers headers = cacheResponse.headers();
                for (int i = 0, size = headers.size(); i < size; i++) {
                    String fieldName = headers.name(i);
                    String value = headers.value(i);
                    if ("Date".equalsIgnoreCase(fieldName)) {
                        servedDate = HttpDate.parse(value);
                        servedDateString = value;
                    } else if ("Expires".equalsIgnoreCase(fieldName)) {
                        expires = HttpDate.parse(value);
                    } else if ("Last-Modified".equalsIgnoreCase(fieldName)) {
                        lastModified = HttpDate.parse(value);
                        lastModifiedString = value;
                    } else if ("ETag".equalsIgnoreCase(fieldName)) {
                        etag = value;
                    } else if ("Age".equalsIgnoreCase(fieldName)) {
                        ageSeconds = HttpHeaders.parseSeconds(value, -1);
                    }
                }
            }
        }

        /**
         * Returns a strategy to satisfy {@code request} using the a cached response {@code response}.
         */
        public CacheStrategy get() {
            CacheStrategy candidate = getCandidate();

            if (candidate.networkRequest != null && request.cacheControl().onlyIfCached()) {
                // We're forbidden from using the network and the cache is insufficient.
                return new CacheStrategy(null, null);
            }

            return candidate;
        }

        /**
         * Returns a strategy to use assuming the request can use the network.
         */
        private CacheStrategy getCandidate() {
            // No cached response.
            //如果缓存没有命中(即null),网络请求也不需要加缓存Header了
            if (cacheResponse == null) {
                //`没有缓存的网络请求,查上文的表可知是直接访问
                return new CacheStrategy(request, null);
            }
            // 如果缓存的TLS握手信息丢失,返回进行直接连接
            // Drop the cached response if it's missing a required handshake.
            if (request.isHttps() && cacheResponse.handshake() == null) {
                //直接访问
                return new CacheStrategy(request, null);
            }

            // If this response shouldn't have been stored, it should never be used
            // as a response source. This check should be redundant as long as the
            // persistence store is well-behaved and the rules are constant.
            //检测response的状态码,Expired时间,是否有no-cache标签
            if (!isCacheable(cacheResponse, request)) {
                //直接访问
                return new CacheStrategy(request, null);
            }
            //如果请求报文使用了`no-cache`标签(这个只可能是开发者故意添加的)
            //或者有ETag/Since标签(也就是条件GET请求)
            CacheControl requestCaching = request.cacheControl();
            if (requestCaching.noCache() || hasConditions(request)) {
                //直接连接,把缓存判断交给服务器
                return new CacheStrategy(request, null);
            }
            //根据RFC协议计算
            //计算当前age的时间戳
            //now - sent + age (s)
            long ageMillis = cacheResponseAge();
            //大部分情况服务器设置为max-age
            long freshMillis = computeFreshnessLifetime();

            if (requestCaching.maxAgeSeconds() != -1) {
                //大部分情况下是取max-age
                freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
            }

            long minFreshMillis = 0;
            if (requestCaching.minFreshSeconds() != -1) {
                //大部分情况下设置是0
                minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
            }

            long maxStaleMillis = 0;
            //ParseHeader中的缓存控制信息
            CacheControl responseCaching = cacheResponse.cacheControl();
            //设置最大过期时间,一般设置为0
            if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
                maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
            }
            //缓存在过期时间内,可以使用
            //大部分情况下是进行如下判断
            //now - sent + age + 0 < max-age + 0
            if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
                Response.Builder builder = cacheResponse.newBuilder();
                if (ageMillis + minFreshMillis >= freshMillis) {
                    builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
                }
                long oneDayMillis = 24 * 60 * 60 * 1000L;
                if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
                    builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
                }
                return new CacheStrategy(null, builder.build());
            }

            // Find a condition to add to the request. If the condition is satisfied, the response body
            // will not be transmitted.
            String conditionName;
            String conditionValue;
            if (etag != null) {
                conditionName = "If-None-Match";
                conditionValue = etag;
            } else if (lastModified != null) {
                conditionName = "If-Modified-Since";
                conditionValue = lastModifiedString;
            } else if (servedDate != null) {
                conditionName = "If-Modified-Since";
                conditionValue = servedDateString;
            } else {
                return new CacheStrategy(request, null); // No condition! Make a regular request.
            }
            //缓存失效, 如果有etag等信息
            //进行发送`conditional`请求,交给服务器处理
            Headers.Builder conditionalRequestHeaders = request.headers().newBuilder();
            Internal.instance.addLenient(conditionalRequestHeaders, conditionName, conditionValue);
            //下面请求实质还说网络请求
            Request conditionalRequest = request.newBuilder()
                    .headers(conditionalRequestHeaders.build())
                    .build();
            return new CacheStrategy(conditionalRequest, cacheResponse);
        }

        /**
         * Returns the number of milliseconds that the response was fresh for, starting from the served
         * date.
         */
        private long computeFreshnessLifetime() {
            CacheControl responseCaching = cacheResponse.cacheControl();
            if (responseCaching.maxAgeSeconds() != -1) {
                return SECONDS.toMillis(responseCaching.maxAgeSeconds());
            } else if (expires != null) {
                long servedMillis = servedDate != null
                        ? servedDate.getTime()
                        : receivedResponseMillis;
                long delta = expires.getTime() - servedMillis;
                return delta > 0 ? delta : 0;
            } else if (lastModified != null
                    && cacheResponse.request().url().query() == null) {
                // As recommended by the HTTP RFC and implemented in Firefox, the
                // max age of a document should be defaulted to 10% of the
                // document's age at the time it was served. Default expiration
                // dates aren't used for URIs containing a query.
                long servedMillis = servedDate != null
                        ? servedDate.getTime()
                        : sentRequestMillis;
                long delta = servedMillis - lastModified.getTime();
                return delta > 0 ? (delta / 10) : 0;
            }
            return 0;
        }

        /**
         * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
         * 2616, 13.2.3 Age Calculations.
         */
        private long cacheResponseAge() {
            long apparentReceivedAge = servedDate != null
                    ? Math.max(0, receivedResponseMillis - servedDate.getTime())
                    : 0;
            long receivedAge = ageSeconds != -1
                    ? Math.max(apparentReceivedAge, SECONDS.toMillis(ageSeconds))
                    : apparentReceivedAge;
            long responseDuration = receivedResponseMillis - sentRequestMillis;
            long residentDuration = nowMillis - receivedResponseMillis;
            return receivedAge + responseDuration + residentDuration;
        }

        /**
         * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
         * cached response older than 24 hours, we are required to attach a warning.
         */
        private boolean isFreshnessLifetimeHeuristic() {
            return cacheResponse.cacheControl().maxAgeSeconds() == -1 && expires == null;
        }

        /**
         * Returns true if the request contains conditions that save the server from sending a response
         * that the client has locally. When a request is enqueued with its own conditions, the built-in
         * response cache won't be used.
         */
        private static boolean hasConditions(Request request) {
            return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
        }
    }
}
