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
package okhttp3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;

/**
 * Policy on when async requests are executed.
 * <p>
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 */
public final class Dispatcher {

    // 最大请求数
    private int maxRequests = 64;
    // 每个主机最大请求数为5
    private int maxRequestsPerHost = 5;
    // ??????????????????????
    private Runnable idleCallback;

    /**
     * Executes calls. Created lazily.
     * 线程池
     */
    private ExecutorService executorService;

    /**
     * 封装了一个请求网络的Runable(将要执行的Runable)
     * Ready async calls in the order they'll be run.
     */
    private final Deque<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

    /**
     * 封装了一个请求网络的Runable(正在执行的Runable)
     * Running asynchronous calls. Includes canceled calls that haven't finished yet.
     */
    private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

    /**
     * 同步网络请求队列
     * Running synchronous calls. Includes canceled calls that haven't finished yet.
     */
    private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

    /**
     * 构造方法传入一个线程池
     *
     * @param executorService 构造方法传入一个线程池
     */
    public Dispatcher(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public Dispatcher() {
    }

    /**
     * 获取线程池
     *
     * @return
     */
    public synchronized ExecutorService executorService() {
        if (executorService == null) {
            // 线程池配置
            executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
        }
        return executorService;
    }

    /**
     * Set the maximum number of requests to execute concurrently. Above this requests queue in
     * memory, waiting for the running calls to complete.
     * 设置最大的maxRequests请求数量，默认为64。并将队列中的异步任务添加到线程池
     * <p>
     * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
     * will remain in flight.
     */
    public synchronized void setMaxRequests(int maxRequests) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequests);
        }
        this.maxRequests = maxRequests;

        // 将readyAsyncCalls中的异步任务添加到线程池
        promoteCalls();
    }

    /**
     * 获取最大的maxRequests请求数量，默认为64
     *
     * @return
     */
    public synchronized int getMaxRequests() {
        return maxRequests;
    }

    /**
     * Set the maximum number of requests for each host to execute concurrently. This limits requests
     * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
     * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
     * proxy.
     * <p>
     * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
     * requests will remain in flight.
     */
    public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
        if (maxRequestsPerHost < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
        }
        this.maxRequestsPerHost = maxRequestsPerHost;
        promoteCalls();
    }

    public synchronized int getMaxRequestsPerHost() {
        return maxRequestsPerHost;
    }

    /**
     * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
     * calls returns to zero).
     * <p>
     * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
     * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
     * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
     * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
     * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
     * means that if you are doing synchronous calls the network layer will not truly be idle until
     * every returned {@link Response} has been closed.
     */
    public synchronized void setIdleCallback(Runnable idleCallback) {
        this.idleCallback = idleCallback;
    }

    /**
     * 异步Runable 入队列。当满足runningRequests<64 && runningRequestsPerHost<5
     *
     * @param call
     */
    synchronized void enqueue(AsyncCall call) {
        // 正在运行队列<64 && 正在运行队列host地址数量判断< 5
        if (runningAsyncCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
            // 入队列
            runningAsyncCalls.add(call);
            // 将该请求放到线程池中
            executorService().execute(call);
        }
        // 不满足条件，则只入readyAsyncCalls队列
        else {
            readyAsyncCalls.add(call);
        }
    }

    /**
     * 取消任务
     * <p>
     * Cancel all calls currently enqueued or executing. Includes calls executed both {@linkplain
     * Call#execute() synchronously} and {@linkplain Call#enqueue asynchronously}.
     */
    public synchronized void cancelAll() {
        for (AsyncCall call : readyAsyncCalls) {
            call.get().cancel();
        }

        for (AsyncCall call : runningAsyncCalls) {
            call.get().cancel();
        }

        for (RealCall call : runningSyncCalls) {
            call.cancel();
        }
    }

    /**
     * 从readyAsyncCalls中取任务添加到runningAsyncCalls队列中
     */
    private void promoteCalls() {
        // 正在运行的数量超过64，则返回
        if (runningAsyncCalls.size() >= maxRequests) return; // Already running max capacity.
        // readyAsyncCalls队列为null，则返回
        if (readyAsyncCalls.isEmpty()) return; // No ready calls to promote.
        // 遍历readyAsyncCalls队列
        for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
            AsyncCall call = i.next();
            // 运行队列host地址数量判断< 5
            if (runningCallsForHost(call) < maxRequestsPerHost) {
                // readyAsyncCalls队列移除
                i.remove();
                // 添加到运行队列
                runningAsyncCalls.add(call);
                // 添加到线程池
                executorService().execute(call);
            }
            // 如果runningAsyncCalls> 64则退出循环
            if (runningAsyncCalls.size() >= maxRequests) return; // Reached max capacity.
        }
    }

    /**
     * Returns the number of running calls that share a host with {@code call}.
     * <p>
     * 这里做正在运行队列host地址数量判断是为了什么?????????????
     */
    private int runningCallsForHost(AsyncCall call) {
        int result = 0;
        for (AsyncCall c : runningAsyncCalls) {
            if (c.host().equals(call.host())) result++;
        }
        return result;
    }

    /**
     * 添加到runningSyncCalls请求队列
     * Used by {@code Call#execute} to signal it is in-flight.
     */
    synchronized void executed(RealCall call) {
        runningSyncCalls.add(call);
    }

    /**
     * Used by {@code AsyncCall#run} to signal completion.
     * <p>
     * 运行结束后，在runningAsyncCalls队列中进行手动移除操作
     */
    void finished(AsyncCall call) {
        finished(runningAsyncCalls, call, true);
    }

    /**
     * Used by {@code Call#execute} to signal completion.
     * <p>
     * 运行结束后，在runningAsyncCalls队列中进行手动移除操作
     */
    void finished(RealCall call) {
        finished(runningSyncCalls, call, false);
    }

    /**
     * calls 队列中手动移除call
     *
     * @param calls
     * @param call
     * @param promoteCalls
     * @param <T>
     */
    private <T> void finished(Deque<T> calls, T call, boolean promoteCalls) {
        int runningCallsCount;
        Runnable idleCallback;
        // 同步该类的对象
        synchronized (this) {
            // 手动移除，如果移除失败抛出异常
            if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
            // 将 runningAsyncCalls 队列中的请求添加到线程池
            if (promoteCalls) promoteCalls();
            runningCallsCount = runningCallsCount();
            idleCallback = this.idleCallback;
        }

        if (runningCallsCount == 0 && idleCallback != null) {
            idleCallback.run();
        }
    }

    /**
     * Returns a snapshot of the calls currently awaiting execution.
     * <p>
     * 获取readyAsyncCalls的数组
     */
    public synchronized List<Call> queuedCalls() {
        List<Call> result = new ArrayList<>();
        for (AsyncCall asyncCall : readyAsyncCalls) {
            result.add(asyncCall.get());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns a snapshot of the calls currently being executed.
     * <p>
     * 获取runningSyncCalls + runningAsyncCalls的数组
     */
    public synchronized List<Call> runningCalls() {
        List<Call> result = new ArrayList<>();
        result.addAll(runningSyncCalls);
        for (AsyncCall asyncCall : runningAsyncCalls) {
            result.add(asyncCall.get());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取readyAsyncCalls的队列长度
     *
     * @return
     */
    public synchronized int queuedCallsCount() {
        return readyAsyncCalls.size();
    }

    /**
     * 获取runningAsyncCalls+ runningSyncCalls的队列长度
     *
     * @return
     */
    public synchronized int runningCallsCount() {
        return runningAsyncCalls.size() + runningSyncCalls.size();
    }
}
