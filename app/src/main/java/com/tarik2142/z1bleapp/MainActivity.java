package com.tarik2142.z1bleapp;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;

import static android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;

public class MainActivity extends AppCompatActivity {
    private TextView labelLog;
    private EditText edtBlackList;
    private EditText edtLastEvent;
    private newNotificationBroadcastReceiver NotificationBroadcastReceiver;
    private newLogBroadcastReceiver LogBroadcastReceiver;
    private final String BLACKLIST = "blacklist";
    private BluetoothAdapter bluetoothAdapter;
    public static final String STR_NLISTENER = "com.tarik2142.z1bleapp.notificationListener";
    public static final String STR_LOGLISTENER = "com.tarik2142.z1bleapp.notificationListener.log";

    SharedPreferences getPref(String name){
        return getSharedPreferences(name, MODE_PRIVATE);
    }

    public class newNotificationBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String pkgName = intent.getStringExtra("pkgName");
            String appTitle = intent.getStringExtra("appTitle");
            String appText = intent.getStringExtra("appText");
            writeLine("pkgName: " + pkgName);
            writeLine("appTitle: " + appTitle);
            writeLine("appText: " + appText);
            edtLastEvent.setText("");
            lastEvent("pkgName: " + pkgName);
            lastEvent("appTitle: " + appTitle);
            lastEvent("appText: " + appText);
        }
    }

    public class newLogBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            writeLine(intent.getStringExtra("str"));
        }
    }

    private void writeLine(final CharSequence text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //messages.setText("");
                labelLog.append(text);
                labelLog.append("\n");
            }
        });
    }

    private void lastEvent(final CharSequence text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                edtLastEvent.append(text);
                edtLastEvent.append("\n");
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        labelLog = findViewById(R.id.labelLog);
        edtLastEvent = findViewById(R.id.edtLastEvent);
        edtBlackList = findViewById(R.id.edtBlackList);
        edtBlackList.setText(getPref(BLACKLIST).getString("apps", ""));
        if(!isNotificationServiceEnabled()){
            startActivity(new Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
        NotificationBroadcastReceiver = new newNotificationBroadcastReceiver();
        LogBroadcastReceiver = new newLogBroadcastReceiver();
        IntentFilter notifiFilter = new IntentFilter();
        IntentFilter logFilter = new IntentFilter();
        notifiFilter.addAction(STR_NLISTENER);
        logFilter.addAction(STR_LOGLISTENER);
        registerReceiver(NotificationBroadcastReceiver, notifiFilter);
        registerReceiver(LogBroadcastReceiver, logFilter);
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(NotificationBroadcastReceiver);
        unregisterReceiver(LogBroadcastReceiver);
    }

    public void saveBlacklist(android.view.View view){
        getPref(BLACKLIST).edit().putString("apps", edtBlackList.getText().toString()).apply();
    }

    private boolean isNotificationServiceEnabled(){
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (int i = 0; i < names.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(names[i]);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void prompt (android.view.View view){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Введите текст для отправки на устройство");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);// | InputType.TYPE_TEXT_VARIATION_PASSWORD
        builder.setView(input);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent intent = new  Intent(NListener.STR_MANUALSEND);
                intent.putExtra("text", input.getText().toString());
                sendBroadcast(intent);
            }
        });
        builder.setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }
}
