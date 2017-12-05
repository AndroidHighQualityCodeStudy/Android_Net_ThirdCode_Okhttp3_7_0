package com.xiaxl.okhttp;

import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by Administrator on 2017/4/19 0019.
 */

public class OkHttpAgent {

    private static final String TAG = "OkHttpAgent";


    /**
     * 同步get请求
     *
     * @param url     请求地址
     * @param headers 请求header
     * @return
     * @throws IOException
     */
    public static String _get_Sync(String url, HashMap<String, String> headers) throws IOException {
        Log.e(TAG, "_get_Sync");
        Log.e(TAG, "url: " + url);
        Log.e(TAG, "headers: " + headers);

        // 请求开始时间
        long starttime = System.currentTimeMillis();
        // #########添加请求数据#########
        Request.Builder requestBuilder = new Request.Builder();
        // 请求地址
        requestBuilder.url(url);
        // 请求header
        requestBuilder = addHeaders(requestBuilder, headers);
        // #########get请求数据#########
        Response response = getOkHttpClient().newCall(requestBuilder.build()).execute();


        // 请求时间日志
        Log.e(TAG, "request time: " + (System.currentTimeMillis() - starttime));
        if (response.isSuccessful()) {
            String str = response.body().string();
            Log.e(TAG, "str: " + str);
            return str;
        }
        Log.e(TAG, "okHttp is request error");
        return "";
    }

    /**
     * 异步回调
     *
     * @param url
     */
    public static void _get_Async(String url, HashMap<String, String> headers) {
        Log.e(TAG, "_get_Async");
        Log.e(TAG, "url: " + url);
        Log.e(TAG, "headers: " + headers);

        // #########添加请求数据#########
        Request.Builder requestBuilder = new Request.Builder();
        // 请求地址
        requestBuilder.url(url);
        // 请求header
        requestBuilder = addHeaders(requestBuilder, headers);
        // #########get请求数据#########
        OkHttpClient okHttpClient = getOkHttpClient();
        okHttpClient.newCall(requestBuilder.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "onFailure time: " + System.currentTimeMillis());
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                // TODO 子线程
                Log.e(TAG, "onResponse time: " + System.currentTimeMillis());

                Log.e(TAG, "callback thread name is " + Thread.currentThread().getName());
                Log.e(TAG, response.body().string());
            }
        });
        Log.e(TAG, "end time: " + System.currentTimeMillis());
    }

    /**
     * 同步post请求
     * <p>
     */
    public static String _post_Sync(String url, Map<String, String> params, HashMap<String, String> headers) throws IOException {

        Log.e(TAG, "_get_Async");
        Log.e(TAG, "url: " + url);
        Log.e(TAG, "params: " + params);
        Log.e(TAG, "params: " + params);

        // 请求开始时间
        long starttime = System.currentTimeMillis();
        /**
         * body
         */
        FormBody.Builder builder = new FormBody.Builder();
        if (null != params && params.size() != 0) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.add(entry.getKey(), entry.getValue().toString());
            }
        }
        // #########添加请求数据#########
        Request.Builder requestBuilder = new Request.Builder();
        // 请求地址
        requestBuilder.url(url);
        // 请求header
        requestBuilder = addHeaders(requestBuilder, headers);
        // 请求body 数据
        Request request = requestBuilder.post(builder.build()).build();
        // #########post请求数据#########
        OkHttpClient okHttpClient = getOkHttpClient();
        Response response = okHttpClient.newCall(request).execute();

        // 请求时间日志
        Log.e(TAG, "request time: " + (System.currentTimeMillis() - starttime));
        if (response.isSuccessful()) {
            String str = response.body().string();
            Log.e(TAG, "str: " + str);
            return str;
        }
        Log.e(TAG, "okHttp is request error");
        return "";
    }


    // ###########################################################################################

    // 30s
    private static final int TIME_OUT = 30000;

    /**
     * 构建OkHttpClient
     * 添加超时时间判断
     *
     * @return
     */
    private static OkHttpClient getOkHttpClient() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(TIME_OUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIME_OUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIME_OUT, TimeUnit.MILLISECONDS)
                .build();

        return okHttpClient;
    }


    /**
     * 向requestBuilder中添加header
     *
     * @param requestBuilder
     * @param headers
     * @return
     */
    private static Request.Builder addHeaders(Request.Builder requestBuilder, HashMap<String, String> headers) {
        // 请求header
        if (headers != null && headers.size() > 0) {
            //获取key和value的set
            Iterator iter = headers.entrySet().iterator();
            while (iter.hasNext()) {
                //把hashmap转成Iterator再迭代到entry
                Map.Entry<String, String> entry = (Map.Entry<String, String>) iter.next();
                String key = entry.getKey();        //从entry获取key
                String val = entry.getValue();    //从entry获取value
                requestBuilder.header(key, val);
            }
        }
        return requestBuilder;
    }


}
