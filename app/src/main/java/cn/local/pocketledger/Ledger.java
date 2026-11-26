package cn.local.pocketledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class Ledger extends SQLiteOpenHelper {
    static final int PENDING = 0, SAVED = 1, SKIPPED = 2;
    private static Ledger instance;
    private final List<Runnable> listeners = new ArrayList<>();

    static synchronized Ledger get(Context context) {
        if (instance == null) instance = new Ledger(context.getApplicationContext());
        return instance;
    }

    private Ledger(Context context) {
        super(context, "ledger.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, platform TEXT NOT NULL, cents INTEGER, "
                + "merchant TEXT NOT NULL, trade_no TEXT NOT NULL, time INTEGER NOT NULL, "
                + "sources INTEGER NOT NULL, state INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX entry_time ON entries(platform,time)");
        db.execSQL("CREATE UNIQUE INDEX entry_order ON entries(platform,trade_no) WHERE trade_no <> ''");
        db.execSQL("CREATE TABLE signals (key TEXT PRIMARY KEY, entry_id INTEGER NOT NULL REFERENCES entries(id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported schema " + oldVersion);
    }

    void observe(Runnable listener) { listeners.add(listener); }
    void forget(Runnable listener) { listeners.remove(listener); }

    void changed() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    static final class Entry {
        final long id, time;
        final Long cents;
        final String platform, merchant, order;
        final int sources, state;

        Entry(Cursor c) {
            id = c.getLong(0);
            platform = c.getString(1);
            cents = c.isNull(2) ? null : c.getLong(2);
            merchant = c.getString(3);
            order = c.getString(4);
            time = c.getLong(5);
            sources = c.getInt(6);
            state = c.getInt(7);
        }
    }

    static final class Result {
        final long id;
        final boolean fresh;
        final boolean enriched;

        Result(long id, boolean fresh, boolean enriched) {
            this.id = id;
            this.fresh = fresh;
            this.enriched = enriched;
        }
    }

    Result receive(Payment payment) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Result result = receive(db, payment);
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    int importAll(List<Payment> payments) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            for (Payment payment : payments) {
                if (receive(db, payment).fresh) added++;
            }
            db.setTransactionSuccessful();
            return added;
        } finally {
            db.endTransaction();
        }
    }

    private Result receive(SQLiteDatabase db, Payment p) {
        Entry match = null;
        if (!p.order.isEmpty()) {
            match = first(db, "platform=? AND trade_no=?", new String[]{p.platform, p.order});
        }
        if (match == null && !p.signal.isEmpty()) {
            match = first(db, "id IN (SELECT entry_id FROM signals WHERE key=?)", new String[]{p.signal});
            if (match != null && !match.order.isEmpty() && !p.order.isEmpty() && !match.order.equals(p.order)) {
                match = null;
            }
        }
        if (match == null) match = nearby(db, p);
        ContentValues values = new ContentValues();
        long id;
        boolean enriched = false;
        if (match == null) {
            values.put("platform", p.platform);
            values.put("cents", p.cents);
            values.put("merchant", p.merchant);
            values.put("trade_no", p.order);
            values.put("time", p.time);
            values.put("sources", p.source);
            values.put("state", p.source == Payment.MANUAL ? SAVED : PENDING);
            id = db.insertOrThrow("entries", null, values);
        } else {
            id = match.id;
            values.put("sources", match.sources | p.source);
            if (match.state == PENDING && match.cents == null && p.cents != null) {
                values.put("cents", p.cents);
                enriched = true;
            }
            if (match.merchant.isEmpty() && !p.merchant.isEmpty()) values.put("merchant", p.merchant);
            if (match.order.isEmpty() && !p.order.isEmpty()) values.put("trade_no", p.order);
            if (p.source == Payment.IMPORT) values.put("time", p.time);
            db.update("entries", values, "id=?", new String[]{String.valueOf(id)});
        }
        if (!p.signal.isEmpty()) {
            values.clear();
            values.put("key", p.signal);
            values.put("entry_id", id);
            db.insertWithOnConflict("signals", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
        return new Result(id, match == null, enriched);
    }

    private Entry nearby(SQLiteDatabase db, Payment p) {
        if (p.source == Payment.MANUAL || p.source == Payment.DEMO) return null;
        long range = p.source == Payment.IMPORT ? 120_000 : 20_000;
        String[] args = {p.platform, String.valueOf(p.time - range), String.valueOf(p.time + range)};
        Entry match = null;
        try (Cursor c = db.query("entries", null, "platform=? AND time BETWEEN ? AND ?", args, null, null, null)) {
            while (c.moveToNext()) {
                Entry e = new Entry(c);
                if ((e.sources & (p.source | Payment.DEMO)) != 0) continue;
                if (!e.order.isEmpty() && !p.order.isEmpty() && !e.order.equals(p.order)) continue;
                if (p.cents != null && e.cents != null && !p.cents.equals(e.cents)) continue;
                boolean sameMerchant = !p.merchant.isEmpty() && p.merchant.equals(e.merchant);
                if (!p.merchant.isEmpty() && !e.merchant.isEmpty() && !sameMerchant) continue;
                long distance = Math.abs(e.time - p.time);
                if (p.source == Payment.IMPORT || (e.sources & Payment.IMPORT) != 0) {
                    if (p.cents == null || e.cents == null || (!sameMerchant && distance > 8_000)) continue;
                } else if ((p.cents == null || e.cents == null) && !sameMerchant && distance > 6_000) {
                    continue;
                }
                // When several payments could match, keep the new one for the user to review.
                if (match != null) return null;
                match = e;
            }
        }
        return match;
    }

    private Entry first(SQLiteDatabase db, String condition, String[] args) {
        try (Cursor c = db.query("entries", null, condition, args, null, null, null, "1")) {
            return c.moveToFirst() ? new Entry(c) : null;
        }
    }

    Entry entry(long id) {
        return first(getReadableDatabase(), "id=?", new String[]{String.valueOf(id)});
    }

    boolean decide(long id, int state, Long cents, String merchant) {
        Entry row = entry(id);
        if (row == null || (state == SAVED && cents == null && row.cents == null)) return false;
        ContentValues values = new ContentValues();
        values.put("state", state);
        if (cents != null) values.put("cents", cents);
        if (merchant != null) values.put("merchant", merchant);
        if (getWritableDatabase().update("entries", values, "id=?", new String[]{String.valueOf(id)}) != 1) {
            return false;
        }
        changed();
        return true;
    }

    List<Entry> entries(int state, long from, long to) {
        List<Entry> results = new ArrayList<>();
        String[] args = {String.valueOf(state), String.valueOf(from), String.valueOf(to)};
        try (Cursor c = getReadableDatabase().query("entries", null, "state=? AND time>=? AND time<?", args,
                null, null, "time DESC,id DESC")) {
            while (c.moveToNext()) results.add(new Entry(c));
        }
        return results;
    }

    int countPending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE state=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
