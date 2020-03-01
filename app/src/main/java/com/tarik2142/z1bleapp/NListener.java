package com.tarik2142.z1bleapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import java.lang.reflect.Method;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class NListener extends NotificationListenerService {
    //        public static final class InterceptedNotificationCode {
//            public static final int FACEBOOK_CODE = 1;
//            public static final int WHATSAPP_CODE = 2;
//            public static final int INSTAGRAM_CODE = 3;
//            public static final int OTHER_NOTIFICATIONS_CODE = 4; // We ignore all notification with code == 4
//        }
    public  final UUID NOTIFY_SERV_UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e");
    public  final UUID TX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public  final UUID RX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public  final String MAC = "F1:64:4B:79:A7:85";
    public  final byte[] msgConnected = {0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10};
    public  final byte[] msgCancelCallAlert = {0x72, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x76};
    public  static final String STR_MANUALSEND = "com.tarik2142.z1bleapp.notificationListener.manualSend";

    public  String prevText = "";
    public static String ignore;
    public boolean screenOff = false;
    public boolean timeout = true;
    public static boolean connected = false;
    public static boolean leScanStarted = false;
    public boolean ringing = false;
    public boolean helloSended = false;

    public BluetoothAdapter adapter;
    public BluetoothGatt gatt;
    public BluetoothGattCharacteristic tx;
    public BluetoothGattCharacteristic rx;
    private ScreenReceiver newScreenReceiver;
    private manualSendBroadcastReceiver newManualSendBroadcastReceiver;
    public TelephonyManager tm;
    public CallStateListener callStateListener;

    SharedPreferences getPref(String name){//настройки
        return getSharedPreferences(name, MODE_PRIVATE);
    }

    public byte int2byte(final int val){
        int v = val / 10;
        return (byte) (val + (6 * v));
    }

    public byte[] syncTimeDate(){//возвращает отформатираваный пакет с дайтой и временем
        Calendar calandar = Calendar.getInstance();
        int cDay = calandar.get(Calendar.DAY_OF_MONTH);
        int cMonth = calandar.get(Calendar.MONTH) + 1;
        int cYear = calandar.get(Calendar.YEAR) % 100;
        int cHour = calandar.get(Calendar.HOUR_OF_DAY);
        int cMinute = calandar.get(Calendar.MINUTE);
        int cSecond = calandar.get(Calendar.SECOND);
        byte sum = 0;//crc
        byte[] result = {0x01, int2byte(cYear), int2byte(cMonth), int2byte(cDay), int2byte(cHour), int2byte(cMinute), int2byte(cSecond), 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        for(byte b : result)
            sum += b;
        result[result.length-1] = sum;
     return result;
    }

    public void startScan(){
        if(!leScanStarted){
            leScanStarted = true;
            sendLine("Scanning for devices...");
            final Timer t = new Timer();
            t.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    adapter = BluetoothAdapter.getDefaultAdapter();
                    if(adapter.isEnabled()){
                        if(adapter.startLeScan(scanCallback)){
                            t.cancel();
                        }
                    }
                }
            }, 0, 5000);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        newScreenReceiver = new ScreenReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(newScreenReceiver, filter);
        IntentFilter manualFilter = new IntentFilter();
        manualFilter.addAction(STR_MANUALSEND);
        newManualSendBroadcastReceiver = new manualSendBroadcastReceiver();
        registerReceiver(newManualSendBroadcastReceiver, manualFilter);
        tm = (TelephonyManager) getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
        callStateListener = new CallStateListener();
        tm.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        ignore = getPref("blacklist").getString("apps", "");
        startScan();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(newScreenReceiver);
        unregisterReceiver(newManualSendBroadcastReceiver);
        tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
    }

    public String bytes2str(byte[] arr){//массив байт в форматираваную строку
        String temp = "";
        for (byte b : arr) {
            String st = String.format("%02X", b);
            temp = temp + st + " ";
        }
        return temp;
    }

    public byte[] str2bytes(String data){
        return data.getBytes(StandardCharsets.UTF_8);
    }

    public void sendLine(String str){//строчка в лог в майн активити
        Intent intent = new  Intent(MainActivity.STR_LOGLISTENER);
        intent.putExtra("str", str);
        sendBroadcast(intent);
    }

    public boolean checkBLE(){//проверить не отвалилось ли устройство и включен ли блютус
        boolean result = false;
        if (adapter == null || tx == null){
            connected = false;
            startScan();
            result = false;
        }else{
            if (!adapter.isEnabled()){
                connected = false;
                startScan();
                result = false;
            }else {
                result = true;
            }
        }
        return result;
    }

    public boolean sendBytes(byte[] data){
        if (!checkBLE() || !connected) return false;
        tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        tx.setValue(data);
        if (gatt.writeCharacteristic(tx)) {
            sendLine("Sent: " + bytes2str(data));
            return true;
        }
        else {
            for (int i = 0; i < 10; i++){//повтор
                if (gatt.writeCharacteristic(tx)){
                    sendLine("Sended, retry: " + i);
                    return true;
                }else{
                    sendLine("RETRY: " + i + "FAIL!");
                }
            }
            sendLine("Couldn't write TX characteristic! " + bytes2str(data));
            connected = false;
            startScan();
            return false;
        }
    }

    public void handleChanges(final byte[] arr){
        if (arr[0] == 0x1d){//music
            Intent i = new Intent("com.android.music.musicservicecommand");
            if (arr[1] == 0x01 && arr[15] == 0x1e){//play/pause
                i.putExtra("command" , "togglepause" );
            }
            if (arr[1] == 0x02 && arr[15] == 0x1f){//back
                i.putExtra("command" , "previous" );
            }
            if (arr[1] == 0x03 && arr[15] == 0x20){//next
                i.putExtra("command" , "next" );
            }
            NListener.this.sendBroadcast(i);
        }
        if(arr[0] == 0x11 && arr[1] == 0x01 && arr[15] == 0x12) {//call reject
            ringing = false;
            sendLine("call reject");
            try {
                // Get the boring old TelephonyManager
                TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                // Get the getITelephony() method
                Class classTelephony = Class.forName(telephonyManager.getClass().getName());
                Method methodGetITelephony = classTelephony.getDeclaredMethod("getITelephony");
                // Ignore that the method is supposed to be private
                methodGetITelephony.setAccessible(true);
                // Invoke getITelephony() to get the ITelephony interface
                Object telephonyInterface = methodGetITelephony.invoke(telephonyManager);
                // Get the endCall method from ITelephony
                Class telephonyInterfaceClass = Class.forName(telephonyInterface.getClass().getName());
                Method methodEndCall = telephonyInterfaceClass.getDeclaredMethod("endCall");
                // Invoke endCall()
                methodEndCall.invoke(telephonyInterface);
            } catch (Exception ex) { // Many things can go wrong with reflection calls
                String error=ex.toString();
                Toast.makeText(getApplicationContext(), "error: "+ error , Toast.LENGTH_LONG).show();
            }
        }
    }

    public static List<byte[]> splitArr(byte[] source, int chunksize) {//сплит массив на куски
        List<byte[]> result = new ArrayList<byte[]>();
        int start = 0;
        while (start < source.length) {
            int end = Math.min(source.length, start + chunksize);
            result.add(Arrays.copyOfRange(source, start, end));
            start += chunksize;
        }
        return result;
    }

    public boolean sendMess(String str){
        boolean sended = false;
        if(!checkBLE() || !connected) return false;
        sendLine("---------send begin-------------");
        byte data[] = str2bytes(str);
        final int MTU = 11;//16 - 4(head) - 1(crc)
        List<byte[]> mess = splitArr(data, MTU);
        final int packetCount = mess.size();
        for (int i = 0; i < packetCount; i++){
            byte[] header = {(byte)0x72, 0x0e, (byte)(mess.size()),(byte)(i + 1)};
            byte[] toSend = new byte[16];
            System.arraycopy(header,0, toSend,0, header.length);
            System.arraycopy(mess.get(i),0, toSend, header.length, mess.get(i).length);
            byte sum = 0;//crc
            for(byte b : toSend)
                sum += b;
            toSend[toSend.length - 1] = sum;//в конце
            sended = sendBytes(toSend);
        }
        sendLine("---------send end-------------");
        return sended;
    }

    public boolean callAlert(String str){//отправить уведомлялку о входящем звонке с номером
        boolean sended = false;
        if(!checkBLE() || !connected) return false;
        sendLine("---------call alert send begin-------------");
        byte data[] = str2bytes(str);
        final int MTU = 11;//16 - 4(head) - 1(crc)
        List<byte[]> mess = splitArr(data, MTU);
        final int packetCount = mess.size();
        for (int i = 0; i < packetCount; i++){
            byte[] header = {(byte)0x72, 0x00, (byte)(mess.size()),(byte)(i + 1)};
            byte[] toSend = new byte[16];
            System.arraycopy(header,0, toSend,0, header.length);
            System.arraycopy(mess.get(i),0, toSend, header.length, mess.get(i).length);
            byte sum = 0;//crc
            for(byte b : toSend)
                sum += b;
            toSend[toSend.length - 1] = sum;//в конце
            sended = sendBytes(toSend);
        }
        sendLine("---------call alert send end-------------");
        return sended;
    }

    private BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            sendLine("RX: " + bytes2str(characteristic.getValue()));
            handleChanges(characteristic.getValue());
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                sendLine("Connected!");
                if (!gatt.discoverServices()) {
                    sendLine("Failed to start discovering services!");
                    connected = false;
                    gatt.close();
                    startScan();//реконнект
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                sendLine("Disconnected!");
                connected = false;
                startScan();//реконнект
            } else {
                sendLine("Connection state changed.  New state: " + newState);
            }
        }
        @Override
        public void onDescriptorWrite (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status){
            super.onDescriptorWrite(gatt, descriptor, status);
            sendBytes(syncTimeDate());
            helloSended = false;
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (!helloSended){
                helloSended = true;
                sendBytes(msgConnected);
            }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendLine("Service discovery completed!");
                connected = true;
                tx = gatt.getService(NOTIFY_SERV_UUID).getCharacteristic(TX_UUID);
                rx = gatt.getService(NOTIFY_SERV_UUID).getCharacteristic(RX_UUID);
                gatt.setCharacteristicNotification(rx, true);
                UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                BluetoothGattDescriptor descriptor = rx.getDescriptor(uuid);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean write = gatt.writeDescriptor(descriptor);
                sendLine("Write descriptor: " + write);
            }
            else {
                sendLine("Service discovery failed with status: " + status);
                connected = false;
                gatt.close();//реконнект
                startScan();
            }
        }
    };

    private BluetoothAdapter.LeScanCallback scanCallback = new BluetoothAdapter.LeScanCallback() {
        // Called when a device is found.
        @Override
        public void onLeScan(BluetoothDevice bluetoothDevice, int i, byte[] bytes) {
            //sendLine("Found device: " + bluetoothDevice.getAddress());
            if (bluetoothDevice.getAddress().startsWith(MAC)){
                adapter.stopLeScan(this);
                leScanStarted = false;
                sendLine("Stop scan");
                gatt = bluetoothDevice.connectGatt(getApplicationContext(), false, callback);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn){
        String pkgName = sbn.getPackageName();
        if (ignore.contains(pkgName)){//чс
            return;
        }
        final int max = 255;
        Bundle extras = sbn.getNotification().extras;
        Intent intent = new  Intent(MainActivity.STR_NLISTENER);
        String title = "";
        String text = "";
        if (extras.containsKey("android.title") && extras.getCharSequence("android.title") != null) {
            title = extras.getCharSequence("android.title").toString();
        }
        if (extras.containsKey("android.text") && extras.getCharSequence("android.text") != null) {
            text = extras.getCharSequence("android.text").toString();
        }
        intent.putExtra("pkgName", pkgName);
        intent.putExtra("appTitle", title);
        intent.putExtra("appText", text);
        sendBroadcast(intent);//в лог
        if (title.isEmpty() && text.isEmpty() || !checkBLE() || !connected){//пустые не отправлять
            return;
        }
        if (!screenOff){//при выключеном экране
            //return;
        }
        if (!timeout){
            return;
        }
        text = text.trim();
        text = text.replaceAll("\\p{Cntrl}", "");
        title += "\n";
        final int titleLen = title.length();
        final int textLen = text.length();
        String result = "";
        if (titleLen + textLen > max){
            result = title + text.substring(0, max - (titleLen + 4)) + "...";
        }else{
            result = title + text;
        }
        if (!text.equals(prevText)){
            sendLine( "Result len: " + result.length());
            if (!sendMess(result)){
                connected = false;
                startScan();
            }
            sendLine("--------------------------------");
            prevText = text;
            timeout = false;
            new CountDownTimer(500, 500) {//3 сек
                public void onTick(long millisUntilFinished) {
                }
                public void onFinish() {
                    timeout = true;
                }
            }.start();
        }
    }
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn){
        //
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public class ScreenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                screenOff = true;
                sendLine("Screen OFF");
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                screenOff = false;
                sendLine("Screen ON");
            }
        }
    }

    public String getContactName(final String phoneNumber, Context context)
    {
        Uri uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(phoneNumber));

        String[] projection = new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME};

        String contactName="";
        Cursor cursor=context.getContentResolver().query(uri,projection,null,null,null);

        if (cursor != null) {
            if(cursor.moveToFirst()) {
                contactName=cursor.getString(0);
            }
            cursor.close();
        }

        return contactName;
    }

    public class CallStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    if (!ringing){
                        ringing = true;
                        String contactName = getContactName(incomingNumber, getBaseContext());
                        if (contactName != null){
                            if (contactName.isEmpty()){//
                                contactName = "Невідомий номер \n" + incomingNumber;//номера нет в списке контактов
                            }
                        }else {
                            contactName = "Вхідний виклик";
                        }
                        callAlert(contactName);
                    }
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    ringing = false;
                    if (connected) sendBytes(msgCancelCallAlert);
                    break;
            }
        }
    }

    public class manualSendBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            sendMess(intent.getStringExtra("text"));
//            sendLine("--------------------");
//            sendBytes(new byte[]{0x72,0x0e ,0x08 ,0x01 ,0x66 ,0x72 ,0x65 ,0x65 ,0x2e ,0x73 ,0x6d ,0x73 ,0x2e ,0x6b ,0x73 ,(byte)0xb8});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 ,0x02 ,0x20 ,0x3a ,0x20 ,0x54 ,0x45 ,0x53 ,0x54 ,0x20 ,0x20 ,(byte)0xd0 ,(byte)0x9e ,(byte)0xf2});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 , 0x03, (byte)0xd0, (byte)0xbf, (byte)0xd0, (byte)0xb5, (byte)0xd1, (byte)0x80, (byte)0xd0 ,(byte)0xb0 ,(byte)0xd1 ,(byte)0x82 ,(byte)0xd0 ,(byte)0x93});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 ,0x04 ,(byte)0xbe ,(byte)0xd1 ,(byte)0x80 ,0x20 ,(byte)0xd0 ,(byte)0xbd ,(byte)0xd0 ,(byte)0xb5 ,0x20 ,(byte)0xd0 ,(byte)0xb2 ,0x6f});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 ,0x05 ,(byte)0xd1 ,(byte)0x96 ,(byte)0xd0 ,(byte)0xb4 ,(byte)0xd0 ,(byte)0xbf ,(byte)0xd0 ,(byte)0xbe ,(byte)0xd0 ,(byte)0xb2 ,(byte)0xd1 ,(byte)0xe8});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 ,0x06 ,(byte)0x96 ,(byte)0xd0 ,(byte)0xb4 ,(byte)0xd0 ,(byte)0xb0 ,(byte)0xd1 ,(byte)0x94 ,0x20 ,(byte)0xd0 ,(byte)0xb7 ,(byte)0xd0 ,0x04});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 ,0x07 ,(byte)0xb0 ,0x20 ,(byte)0xd0 ,(byte)0xb7 ,(byte)0xd0 ,(byte)0xbc ,(byte)0xd1 ,(byte)0x96 ,(byte)0xd1 ,(byte)0x81 ,(byte)0xd1 ,(byte)0xfc});
//            sendBytes(new byte[]{0x72 ,0x0e ,0x08 ,0x08 ,(byte)0x82 ,0x20 ,0x53 ,0x4d ,0x53 ,0x00 ,0x00 ,0x00 ,0x00 ,0x00 ,0x00 ,0x25});
        }
    }

}