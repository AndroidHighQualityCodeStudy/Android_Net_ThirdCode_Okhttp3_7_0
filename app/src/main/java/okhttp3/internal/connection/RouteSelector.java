/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import okhttp3.Address;
import okhttp3.HttpUrl;
import okhttp3.Route;
import okhttp3.internal.Util;

/**
 * Selects routes to connect to an origin server. Each connection requires a choice of proxy server,
 * IP address, and TLS mode. Connections may also be recycled.
 * <p>
 * 选择路线与自动重连(RouteSelector)
 */
public final class RouteSelector {
    private final Address address;
    private final RouteDatabase routeDatabase;

    /*
     * The most recently attempted route.
     */
    // 最后一次代理
    private Proxy lastProxy;
    // ip+端口
    private InetSocketAddress lastInetSocketAddress;

    /*
     * State for negotiating the next proxy to use.
     */
    // 代理服务器列表
    private List<Proxy> proxies = Collections.emptyList();
    // proxies的索引nextProxyIndex
    private int nextProxyIndex;

    /* State for negotiating the next socket address to use. */
    // InetSocketAddress列表(主机ip+端口号)
    private List<InetSocketAddress> inetSocketAddresses = Collections.emptyList();
    // inetSocketAddresses列表的index索引
    private int nextInetSocketAddressIndex;

    /*
     * State for negotiating failed routes
     */
    //  address  路由  ip + 端口
    private final List<Route> postponedRoutes = new ArrayList<>();

    /**
     * 构造方法
     *
     * @param address       ??????????????
     * @param routeDatabase ???????????
     */
    public RouteSelector(Address address, RouteDatabase routeDatabase) {
        this.address = address;
        this.routeDatabase = routeDatabase;

        resetNextProxy(address.url(), address.proxy());
    }

    /**
     * Returns true if there's another route to attempt. Every address has at least one route.
     */
    public boolean hasNext() {
        return hasNextInetSocketAddress()
                || hasNextProxy()
                || hasNextPostponed();
    }

    /**
     * 获取Route
     *
     * @return
     * @throws IOException
     */
    public Route next() throws IOException {
        // Compute the next route to attempt.
        // inetSocketAddresses列表中是否包含下一个InetSocketAddress
        if (!hasNextInetSocketAddress()) {
            // 列表时候有数据的判断
            if (!hasNextProxy()) {
                // postponedRoutes列表为空判断
                if (!hasNextPostponed()) {
                    // 抛出异常
                    throw new NoSuchElementException();
                }
                // 移除一个
                return nextPostponed();
            }
            // 构建inetSocketAddresses列表(主机ip + 端口号)列表
            lastProxy = nextProxy();
        }
        // ip+端口
        lastInetSocketAddress = nextInetSocketAddress();
        // address // 路由 // ip + 端口
        Route route = new Route(address, lastProxy, lastInetSocketAddress);
        // 包含在失败的列表中
        if (routeDatabase.shouldPostpone(route)) {
            // 添加到postponedRoutes列表，进行下一次递归
            postponedRoutes.add(route);
            // We will only recurse in order to skip previously failed routes. They will be tried last.
            return next();
        }

        return route;
    }

    /**
     * Clients should invoke this method when they encounter a connectivity failure on a connection
     * returned by this route selector.
     */
    public void connectFailed(Route failedRoute, IOException failure) {
        if (failedRoute.proxy().type() != Proxy.Type.DIRECT && address.proxySelector() != null) {
            // Tell the proxy selector when we fail to connect on a fresh connection.
            address.proxySelector().connectFailed(
                    address.url().uri(), failedRoute.proxy().address(), failure);
        }

        routeDatabase.failed(failedRoute);
    }

    /**
     * Prepares the proxy servers to try.
     * <p>
     * 准备尝试代理server
     *
     * @param url
     * @param proxy
     */
    private void resetNextProxy(HttpUrl url, Proxy proxy) {

        // 如果Proxy不为null
        if (proxy != null) {
            // If the user specifies a proxy, try that and only that.
            proxies = Collections.singletonList(proxy);
        } else {

            // proxies列表为Proxy.NO_PROXY
            // Try each of the ProxySelector choices until one connection succeeds.
            List<Proxy> proxiesOrNull = address.proxySelector().select(url.uri());
            proxies = proxiesOrNull != null && !proxiesOrNull.isEmpty()
                    ? Util.immutableList(proxiesOrNull)
                    : Util.immutableList(Proxy.NO_PROXY);
        }
        nextProxyIndex = 0;
    }

    /**
     * Returns true if there's another proxy to try.
     * proxies 列表时候有数据的判断
     */
    private boolean hasNextProxy() {
        return nextProxyIndex < proxies.size();
    }

    /**
     * Returns the next proxy to try. May be PROXY.NO_PROXY but never null.
     * <p>
     * 构建inetSocketAddresses列表(主机ip + 端口号)列表
     */
    private Proxy nextProxy() throws IOException {
        if (!hasNextProxy()) {
            throw new SocketException("No route to " + address.url().host()
                    + "; exhausted proxy configurations: " + proxies);
        }
        // 取一个代理
        Proxy result = proxies.get(nextProxyIndex++);
        // 构建inetSocketAddresses列表(主机ip + 端口号)列表
        resetNextInetSocketAddress(result);
        return result;
    }

    /**
     * Prepares the socket addresses to attempt for the current proxy or host.
     * <p>
     * 构建inetSocketAddresses列表(主机ip + 端口号)
     */
    private void resetNextInetSocketAddress(Proxy proxy) throws IOException {
        // Clear the addresses. Necessary if getAllByName() below throws!
        // 清空 inetSocketAddresses列表
        inetSocketAddresses = new ArrayList<>();

        // 主机ip
        String socketHost;
        // 端口号
        int socketPort;
        // 没有代理 或者 socks代理
        if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
            socketHost = address.url().host();
            socketPort = address.url().port();
        }
        // http代理
        else {
            // 如果非InetSocketAddress代理，抛出异常
            SocketAddress proxyAddress = proxy.address();
            if (!(proxyAddress instanceof InetSocketAddress)) {
                throw new IllegalArgumentException(
                        "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
            }
            // 获取InetSocketAddress(主机+端口)
            InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
            // 获取代理的ip地址
            socketHost = getHostString(proxySocketAddress);
            // 获取端口信息
            socketPort = proxySocketAddress.getPort();
        }
        // 端口异常判断
        if (socketPort < 1 || socketPort > 65535) {
            throw new SocketException("No route to " + socketHost + ":" + socketPort
                    + "; port is out of range");
        }

        // 如果代理是socks代理,直接添加到inetSocketAddresses列表中
        if (proxy.type() == Proxy.Type.SOCKS) {
            inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
        }
        // 如果是DIRECT或者HTTP代理，则通过主机名称获取ip
        else {
            // 根据socketHost获取InetAddress数组
            // Try each address for best behavior in mixed IPv4/IPv6 environments.
            List<InetAddress> addresses = address.dns().lookup(socketHost);
            // 循环InetAddress数组
            for (int i = 0, size = addresses.size(); i < size; i++) {
                InetAddress inetAddress = addresses.get(i);
                inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
            }
        }
        // inetSocketAddresses列表的索引
        nextInetSocketAddressIndex = 0;
    }

    /**
     * Obtain a "host" from an {@link InetSocketAddress}. This returns a string containing either an
     * actual host name or a numeric IP address.
     * <p>
     * 获取InetSocketAddress对应的ip地址
     */
    // Visible for testing
    static String getHostString(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();
        if (address == null) {
            // The InetSocketAddress was specified with a string (either a numeric IP or a host name). If
            // it is a name, all IPs for that name should be tried. If it is an IP address, only that IP
            // address should be tried.
            return socketAddress.getHostName();
        }
        // The InetSocketAddress has a specific address: we should only try that address. Therefore we
        // return the address and ignore any host name that may be available.
        return address.getHostAddress();
    }

    /**
     * Returns true if there's another socket address to try.
     * <p>
     * // inetSocketAddresses列表中是否包含下一个InetSocketAddress
     */
    private boolean hasNextInetSocketAddress() {
        return nextInetSocketAddressIndex < inetSocketAddresses.size();
    }

    /**
     * Returns the next socket address to try.
     */
    private InetSocketAddress nextInetSocketAddress() throws IOException {
        // inetSocketAddresses列表中是否包含下一个InetSocketAddress
        if (!hasNextInetSocketAddress()) {
            throw new SocketException("No route to " + address.url().host()
                    + "; exhausted inet socket addresses: " + inetSocketAddresses);
        }
        // 根据index取下一个
        return inetSocketAddresses.get(nextInetSocketAddressIndex++);
    }

    /**
     * Returns true if there is another postponed route to try.
     */
    private boolean hasNextPostponed() {
        return !postponedRoutes.isEmpty();
    }

    /**
     * Returns the next postponed route to try.
     */
    private Route nextPostponed() {
        return postponedRoutes.remove(0);
    }
}
