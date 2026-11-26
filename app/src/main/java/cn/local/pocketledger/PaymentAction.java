package cn.local.pocketledger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PaymentAction extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(PaymentPrompts.ENTRY, -1);
        int state = intent.getIntExtra("state", -1);
        if (id > 0 && (state == Ledger.SAVED || state == Ledger.SKIPPED)) {
            PaymentPrompts.decide(context, id, state);
        }
    }
}
