package com.xiaxl.okhttp;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "xiaxl: MainActivity";

    // 请求url地址
    private final String requestGetUrl = "http://apicloud.mob.com/v1/weather/query?key=146d30f8f3b93&city=北京&province=北京";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 同步get
        findViewById(R.id.get_sync_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                new Thread() {
                    @Override
                    public void run() {
                        try {
                            OkHttpAgent._get_Sync(requestGetUrl, null);
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e(TAG, e.getMessage());
                        }
                    }
                }.start();

            }
        });
        // 异步get
        findViewById(R.id.get_async_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // get
                OkHttpAgent._get_Async(requestGetUrl, null);
            }
        });

        // 同步post请求
        findViewById(R.id.post_sync_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                new Thread() {
                    @Override
                    public void run() {
                        try {
                            String url = "http://apicloud.mob.com/v1/weather/query";
                            HashMap<String, String> params = new HashMap<String, String>();
                            params.put("key", "146d30f8f3b93");
                            params.put("city", "北京");
                            params.put("province", "北京");
                            //
                            OkHttpAgent._post_Sync(url, params, null);
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e(TAG, e.getMessage());
                        }
                    }
                }.start();
            }
        });
    }
}
