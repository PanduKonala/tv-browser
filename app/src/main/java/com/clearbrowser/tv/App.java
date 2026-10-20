package com.clearbrowser.tv;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Blocker.get().init(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Blocker.get().trim();
    }
}
