package com.vnimy.zerotier;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import com.vnimy.zerotier.model.DaoMaster;
import com.vnimy.zerotier.model.DaoSession;
import com.vnimy.zerotier.model.ZTOpenHelper;

/**
 * 主程序入口
 *
 * @author kaaass
 */
public class ZeroTierApplication extends MultiDexApplication {
    private DaoSession mDaoSession;

    public void onCreate() {
        super.onCreate();
        Log.i("Application", "Starting Application");
        // 创建 DAO 会话
        this.mDaoSession = new DaoMaster(
                new ZTOpenHelper(this, "zerotier", null)
                        .getWritableDatabase()
        ).newSession();
    }

    public DaoSession getDaoSession() {
        return this.mDaoSession;
    }
}
