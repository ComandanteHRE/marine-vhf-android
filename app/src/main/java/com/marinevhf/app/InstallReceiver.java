package com.marinevhf.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Результат PackageInstaller: просит подтверждение у пользователя и сообщает об итоге экрану. */
public final class InstallReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.marinevhf.INSTALL_RESULT";
    public static final String ACTION_DONE = "com.marinevhf.INSTALL_DONE";

    @Override
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
            return;
        }
        Intent done = new Intent(ACTION_DONE)
                .setPackage(c.getPackageName())
                .putExtra("ok", st == PackageInstaller.STATUS_SUCCESS)
                .putExtra("msg", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        c.sendBroadcast(done);
    }
}
