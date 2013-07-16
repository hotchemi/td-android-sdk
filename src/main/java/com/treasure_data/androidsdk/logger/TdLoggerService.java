package com.treasure_data.androidsdk.logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.komamitsu.android.util.Log;

import com.treasure_data.androidsdk.apiclient.ApiClient;
import com.treasure_data.androidsdk.apiclient.DefaultApiClient;
import com.treasure_data.androidsdk.apiclient.DefaultApiClient.ApiError;
import com.treasure_data.androidsdk.apiclient.TdTableImporter;
import com.treasure_data.androidsdk.util.RepeatingWorker;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

public class TdLoggerService extends Service {
    private static final String TAG = TdLoggerService.class.getSimpleName();
    private static final String ACTION_FLUSH = TdLoggerService.class.getName() + ".ACTION_FLUSH";
    private static final String ACTION_CLOSE = TdLoggerService.class.getName() + ".ACTION_CLOSE";
    private static final String EXTRA_KEY_DB = "db";
    private static final String EXTRA_KEY_TBL = "tbl";
    private static final String EXTRA_KEY_DATA = "data";

    private static final String RES_DEFTYPE = "string";
    private static final String API_SERVER_HOST = "api.treasure-data.com";
    private static final int API_SERVER_PORT = 443;

    private Map<String, List<ByteBuffer>> msgpackMap = new HashMap<String, List<ByteBuffer>>();
    private final RepeatingWorker flushWorker = new RepeatingWorker();
    private LogReceiver logReceiver;
    private boolean isClosing;
    private String apikey;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate: " + this);

        final ApiClient apiClient= new DefaultApiClient();
        apikey = getString(getResources().getIdentifier("td_apikey", RES_DEFTYPE, getPackageName()));
        apiClient.init(apikey, API_SERVER_HOST, API_SERVER_PORT);

        final TdTableImporter tdTableImporter = new TdTableImporter(apiClient);

        flushWorker.setProcedure(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "flushWorker.run() " + this);

                for (Entry<String, List<ByteBuffer>> keyAndMsgpacks : msgpackMap.entrySet()) {
                    String[] databaseAndTable = fromMsgpackMapKey(keyAndMsgpacks.getKey());
                    String database = databaseAndTable[0];
                    String table = databaseAndTable[1];
                    Iterator<ByteBuffer> msgpacks = keyAndMsgpacks.getValue().iterator();
                    while (msgpacks.hasNext()) {
                        ByteBuffer buff = msgpacks.next();
                        try {
                            tdTableImporter.output(database, table, buff.array());
                            msgpacks.remove();
                            TimeUnit.SECONDS.sleep(5);
                        } catch (IOException e) {
                            Log.e(TAG, "import table error", e);
                        } catch (ApiError e) {
                            Log.e(TAG, "import table error", e);
                        } catch (InterruptedException e) {
                        }
                    }
                    if (keyAndMsgpacks.getValue().size() == 0) {
                        msgpackMap.remove(keyAndMsgpacks.getKey());
                    }
                }

                if (isClosing && msgpackMap.keySet().size() == 0) {
                    Log.d(TAG, "closing...");
                    flushWorker.stop();
                }
            }
        });

        IntentFilter intentFilterFlush = new IntentFilter(ACTION_FLUSH);
        IntentFilter intentFilterClose = new IntentFilter(ACTION_CLOSE);
        logReceiver = new LogReceiver();
        registerReceiver(logReceiver, intentFilterFlush);
        registerReceiver(logReceiver, intentFilterClose);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        isClosing = true;
        unregisterReceiver(logReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isClosing = false;

        synchronized (flushWorker) {
            if (!flushWorker.isRunning()) {
                flushWorker.start();
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private class LogReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive: " + intent);
            if (intent.getAction().equals(ACTION_FLUSH)) {
                String database = intent.getExtras().getString(EXTRA_KEY_DB);
                String table = intent.getExtras().getString(EXTRA_KEY_TBL);
                byte[] data = intent.getExtras().getByteArray(EXTRA_KEY_DATA);
                String msgpackMapKey = toMsgpackMapKey(database, table);
                List<ByteBuffer> msgpacks = msgpackMap.get(msgpackMapKey);
                if (msgpacks == null) {
                    synchronized (msgpackMap) {
                        msgpacks = msgpackMap.get(msgpackMapKey);
                        if (msgpacks == null) {
                            msgpacks = new LinkedList<ByteBuffer>();
                            msgpackMap.put(msgpackMapKey, msgpacks);
                        }
                    }
                }
                msgpacks.add(ByteBuffer.wrap(data));
            }
            else if (intent.getAction().equals(ACTION_CLOSE)) {
                isClosing = true;
            }
        }
    }

    private static String toMsgpackMapKey(String database, String table) {
        return new StringBuilder().append(database).append("#").append(table).toString();
    }

    private static String[] fromMsgpackMapKey(String key) {
        return key.split("#");
    }

    public static Intent createIntentForFlush(String database, String table, byte[] data) {
        Intent intent = new Intent();
        intent.setAction(ACTION_FLUSH);
        intent.putExtra(EXTRA_KEY_DB, database);
        intent.putExtra(EXTRA_KEY_TBL, table);
        intent.putExtra(EXTRA_KEY_DATA, data);
        return intent;
    }

    public static Intent createIntentForClose() {
        Intent intent = new Intent();
        intent.setAction(ACTION_CLOSE);
        return intent;
    }
}
