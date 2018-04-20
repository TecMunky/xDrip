package com.eveningoutpost.dexdrip.Models;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ContextThemeWrapper;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.widget.Toast;

import com.activeandroid.ActiveAndroid;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.UtilityModels.Constants;
import com.eveningoutpost.dexdrip.UtilityModels.PersistentStore;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.UtilityModels.XdripNotificationCompat;
import com.eveningoutpost.dexdrip.utils.BestGZIPOutputStream;
import com.eveningoutpost.dexdrip.utils.CipherUtils;
import com.eveningoutpost.dexdrip.xdrip;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedInts;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import static android.bluetooth.BluetoothDevice.PAIRING_VARIANT_PIN;
import static android.content.Context.ALARM_SERVICE;
import static com.eveningoutpost.dexdrip.stats.StatsActivity.SHOW_STATISTICS_PRINT_COLOR;

/**
 * Created by jamorham on 06/01/16.
 * <p>
 * lazy helper class for utilities
 */
public class JoH {
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private final static String TAG = "jamorham JoH";
    private final static boolean debug_wakelocks = false;

    private static double benchmark_time = 0;
    private static Map<String, Double> benchmarks = new HashMap<String, Double>();
    private static final Map<String, Long> rateLimits = new HashMap<>();

    public static boolean buggy_samsung = false; // flag set when we detect samsung devices which do not perform to android specifications

    // qs = quick string conversion of double for printing
    public static String qs(double x) {
        return qs(x, 2);
    }

    // singletons to avoid repeated allocation
    private static DecimalFormatSymbols dfs;
    private static DecimalFormat df;
    public static String qs(double x, int digits) {

        if (digits == -1) {
            digits = 0;
            if (((int) x != x)) {
                digits++;
                if ((((int) x * 10) / 10 != x)) {
                    digits++;
                    if ((((int) x * 100) / 100 != x)) digits++;
                }
            }
        }

        if (dfs == null) {
            final DecimalFormatSymbols local_dfs = new DecimalFormatSymbols();
            local_dfs.setDecimalSeparator('.');
            dfs = local_dfs; // avoid race condition
        }

        final DecimalFormat this_df;
        // use singleton if on ui thread otherwise allocate new as DecimalFormat is not thread safe
        if (Thread.currentThread().getId() == 1) {
            if (df == null) {
                final DecimalFormat local_df = new DecimalFormat("#", dfs);
                local_df.setMinimumIntegerDigits(1);
                df = local_df; // avoid race condition
            }
            this_df = df;
        } else {
            this_df = new DecimalFormat("#", dfs);
        }

        this_df.setMaximumFractionDigits(digits);
        return this_df.format(x);
    }

    public static double ts() {
        return new Date().getTime();
    }

    public static long tsl() {
        return System.currentTimeMillis();
    }

    public static long uptime() {
        return SystemClock.uptimeMillis();
    }

    public static boolean upForAtLeastMins(int mins) {
        return uptime() > Constants.MINUTE_IN_MS * mins;
    }

    public static long msSince(long when) {
        return (tsl() - when);
    }

    public static long msTill(long when) {
        return (when - tsl());
    }

    public static long absMsSince(long when) {
        return Math.abs(tsl() - when);
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "<empty>";
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static byte[] hexStringToByteArray(String str) {
        try {
            str = str.toUpperCase().trim();
            if (str.length() == 0) return null;
            final int len = str.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(str.charAt(i), 16) << 4) + Character.digit(str.charAt(i + 1), 16));
            }
            return data;
        } catch (Exception e) {
            Log.e(TAG, "Exception processing hexString: " + e);
            return null;
        }
    }


    public static String compressString(String source) {
        try {

            Deflater deflater = new Deflater();
            deflater.setInput(source.getBytes(Charset.forName("UTF-8")));
            deflater.finish();

            byte[] buf = new byte[source.length() + 256];
            int count = deflater.deflate(buf);
            // check count
            deflater.end();
            return Base64.encodeToString(buf, 0, count, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] compressStringToBytes(String string) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(string.length());
            BestGZIPOutputStream gzipped_data = new BestGZIPOutputStream(output);
            gzipped_data.write(string.getBytes(Charset.forName("UTF-8")));
            gzipped_data.close();
            byte[] compressed = output.toByteArray();
            output.close();
            return compressed;
        } catch (Exception e) {
            Log.e(TAG, "Exception in compress: " + e.toString());
            return new byte[0];
        }
    }

    public static byte[] compressBytesforPayload(byte[] bytes) {
        return compressBytesToBytes(Bytes.concat(bytes, bchecksum(bytes)));
    }

    public static byte[] compressBytesToBytes(byte[] bytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
            BestGZIPOutputStream gzipped_data = new BestGZIPOutputStream(output);
            gzipped_data.write(bytes);
            gzipped_data.close();
            byte[] compressed = output.toByteArray();
            output.close();
            return compressed;
        } catch (Exception e) {
            Log.e(TAG, "Exception in compress: " + e.toString());
            return new byte[0];
        }
    }

    public static byte[] decompressBytesToBytes(byte[] bytes) {
        try {
            Log.d(TAG, "Decompressing  bytes size: " + bytes.length);
            byte[] buffer = new byte[8192];
            int bytes_read;
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
            GZIPInputStream gzipped_data = new GZIPInputStream(input, buffer.length);
            while ((bytes_read = gzipped_data.read(buffer)) != -1) {
                output.write(buffer, 0, bytes_read);
            }
            gzipped_data.close();
            input.close();
            // output.close();
            return output.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Exception in decompress: " + e.toString());
            return new byte[0];
        }
    }


    public static String uncompressString(String input) {
        try {
            byte[] bytes = Base64.decode(input, Base64.NO_WRAP);
            Inflater inflater = new Inflater();
            inflater.setInput(bytes);
            inflater.finished();

            byte[] buf = new byte[10000]; // max packet size because not using stream
            int count = inflater.inflate(buf);
            inflater.end();
            Log.d(TAG, "Inflated bytes: " + count);
            return new String(buf, 0, count, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Got exception uncompressing string");
            return null;
        }
    }

    public static String base64encode(String input) {
        try {
            return new String(Base64.encode(input.getBytes("UTF-8"), Base64.NO_WRAP), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Got unsupported encoding: " + e);
            return "encode-error";
        }
    }

    public static String base64decode(String input) {
        try {
            return new String(Base64.decode(input.getBytes("UTF-8"), Base64.NO_WRAP), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Log.e(TAG, "Got unsupported encoding: " + e);
            return "decode-error";
        }
    }


    public static String base64encodeBytes(byte[] input) {
        try {
            return new String(Base64.encode(input, Base64.NO_WRAP), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Got unsupported encoding: " + e);
            return "encode-error";
        }
    }

    public static byte[] base64decodeBytes(String input) {
        try {
            return Base64.decode(input.getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Log.e(TAG, "Got unsupported encoding: " + e);
            return new byte[0];
        }
    }


    public static String ucFirst(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }

    public static boolean isSamsung() {
        return Build.MANUFACTURER.toLowerCase().contains("samsung");
    }

    private static final String BUGGY_SAMSUNG_ENABLED = "buggy-samsung-enabled";
    public static void persistentBuggySamsungCheck() {
        if (!buggy_samsung) {
           if (JoH.isSamsung() && PersistentStore.getLong(BUGGY_SAMSUNG_ENABLED) > 4) {
               buggy_samsung = true;
               UserError.Log.d(TAG,"Enabling buggy samsung mode due to historical pattern");
           }
        }
    }

    public static void setBuggySamsungEnabled() {
        if (!buggy_samsung) {
            JoH.buggy_samsung = true;
            PersistentStore.incrementLong(BUGGY_SAMSUNG_ENABLED);
        }
    }


    public static class DecimalKeyListener extends DigitsKeyListener {
        private final char[] acceptedCharacters =
                new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                        new DecimalFormat().getDecimalFormatSymbols().getDecimalSeparator()};

        @Override
        protected char[] getAcceptedChars() {
            return acceptedCharacters;
        }

        public int getInputType() {
            return InputType.TYPE_CLASS_NUMBER;
        }

    }

    public static String backTrace() {
        return backTrace(1);
    }

    public static String backTrace(int depth) {
        try {
            StackTraceElement stack = new Exception().getStackTrace()[2 + depth];
            StackTraceElement stackb = new Exception().getStackTrace()[3 + depth];
            String[] stackclassa = stack.getClassName().split("\\.");
            String[] stackbclassa = stackb.getClassName().split("\\.");

            return stackbclassa[stackbclassa.length - 1] + "::" + stackb.getMethodName()
                    + " -> " + stackclassa[stackclassa.length - 1] + "::" + stack.getMethodName();
        } catch (Exception e) {
            return "unknown backtrace: " + e.toString();
        }
    }

    public static String backTraceShort(int depth) {
        try {
            final StackTraceElement stackb = new Exception().getStackTrace()[3 + depth];
            return stackb.getMethodName();
        } catch (Exception e) {
            return "unknown backtrace: " + e.toString();
        }
    }

    public static void benchmark(String name) {
        if (name == null) {
            if (benchmark_time == 0) {
                benchmark_time = ts();
            } else {
                Log.e(TAG, "Cannot start a benchmark as one is already running - cancelling");
                benchmark_time = 0;
            }
        } else {
            if (benchmark_time == 0) {
                Log.e(TAG, "Benchmark: " + name + " no benchmark set!");
            } else {
                Log.i(TAG, "Benchmark: " + name + " " + (ts() - benchmark_time) + " ms");
                benchmark_time = 0;
            }
        }
    }

    public static void dumpBundle(Bundle bundle, String tag) {
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                if (value != null) {
                    UserError.Log.d(tag, String.format("%s %s (%s)", key,
                            value.toString(), value.getClass().getName()));
                }
            }
        } else {
            UserError.Log.d(tag, "Bundle is empty");
        }
    }


    // compare stored byte array hashes
    public static synchronized boolean differentBytes(String name, byte[] bytes) {
        final String id = "differentBytes-" + name;
        final String last_hash = PersistentStore.getString(id);
        final String this_hash = CipherUtils.getSHA256(bytes);
        if (this_hash.equals(last_hash)) return false;
        PersistentStore.setString(id, this_hash);
        return true;
    }

    public static synchronized void clearRatelimit(final String name) {
        if (PersistentStore.getLong(name) > 0) {
            PersistentStore.setLong(name, 0);
        }
        if (rateLimits.containsKey(name)) {
            rateLimits.remove(name);
        }
    }

    // return true if below rate limit (persistent version)
    public static synchronized boolean pratelimit(String name, int seconds) {
        // check if over limit
        final long time_now = JoH.tsl();
        final long rate_time;
        if (!rateLimits.containsKey(name)) {
            rate_time = PersistentStore.getLong(name); // 0 if undef
        } else {
            rate_time = rateLimits.get(name);
        }
        if ((rate_time > 0) && (time_now - rate_time) < (seconds * 1000L)) {
            Log.d(TAG, name + " rate limited: " + seconds + " seconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, time_now);
        PersistentStore.setLong(name, time_now);
        return true;
    }

    // return true if below rate limit
    public static synchronized boolean ratelimit(String name, int seconds) {
        // check if over limit
        if ((rateLimits.containsKey(name)) && (JoH.tsl() - rateLimits.get(name) < (seconds * 1000L))) {
            Log.d(TAG, name + " rate limited: " + seconds + " seconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, JoH.tsl());
        return true;
    }

    // return true if below rate limit
    public static synchronized boolean quietratelimit(String name, int seconds) {
        // check if over limit
        if ((rateLimits.containsKey(name)) && (JoH.tsl() - rateLimits.get(name) < (seconds * 1000))) {
            return false;
        }
        // not over limit
        rateLimits.put(name, JoH.tsl());
        return true;
    }

    // return true if below rate limit
    public static synchronized boolean ratelimitmilli(String name, int milliseconds) {
        // check if over limit
        if ((rateLimits.containsKey(name)) && (JoH.tsl() - rateLimits.get(name) < (milliseconds))) {
            Log.d(TAG, name + " rate limited: " + milliseconds + " milliseconds");
            return false;
        }
        // not over limit
        rateLimits.put(name, JoH.tsl());
        return true;
    }

    public static String getDeviceDetails() {
        final String manufacturer = Build.MANUFACTURER.replace(" ", "_");
        final String model = Build.MODEL.replace(" ", "_");
        final String version = Integer.toString(Build.VERSION.SDK_INT) + " " + Build.VERSION.RELEASE + " " + Build.VERSION.INCREMENTAL;
        return manufacturer + " " + model + " " + version;
    }

    public static String getVersionDetails() {
        try {
            return xdrip.getAppContext().getPackageManager().getPackageInfo(xdrip.getAppContext().getPackageName(), PackageManager.GET_META_DATA).versionName;
        } catch (Exception e) {
            return "Unknown version";
        }
    }

    public static boolean isOldVersion(Context context) {
        try {
            final Signature[] pinfo = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            if (pinfo.length == 1) {
                final Checksum s = new CRC32();
                final byte[] ba = pinfo[0].toByteArray();
                s.update(ba, 0, ba.length);
                if (s.getValue() == 2009579833) return true;
            }
        } catch (Exception e) {
            Log.d(TAG, "exception: " + e);
        }
        return false;
    }

    public static boolean getWifiSleepPolicyNever() {
        try {
            int policy = Settings.Global.getInt(xdrip.getAppContext().getContentResolver(), android.provider.Settings.Global.WIFI_SLEEP_POLICY);
            Log.d(TAG, "Current WifiPolicy: " + ((policy == Settings.Global.WIFI_SLEEP_POLICY_NEVER) ? "Never" : Integer.toString(policy)) + " " + Settings.Global.WIFI_SLEEP_POLICY_DEFAULT + " " + Settings.Global.WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED);
            return (policy == Settings.Global.WIFI_SLEEP_POLICY_NEVER);
        } catch (Exception e) {
            Log.e(TAG, "Exception during global settings policy");
            return true; // we don't know anything
        }
    }

    public static void benchmark_method_start() {
        benchmarks.put(backTrace(0), ts());
    }

    public static void benchmark_method_end() {
        String name = backTrace(0);
        try {

            double timing = ts() - benchmarks.get(name);
            Log.i(TAG, "Benchmark: " + name + " " + timing + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Benchmark: " + name + " no benchmark set!");
        }
    }

    public static void fixActionBar(AppCompatActivity context) {
        try {
            context.getSupportActionBar().setDisplayShowHomeEnabled(true);
            context.getSupportActionBar().setIcon(R.drawable.ic_launcher);
        } catch (Exception e) {
            Log.e(TAG, "Got exception with supportactionbar: " + e.toString());

        }
    }

    public static HashMap<String, Object> JsonStringtoMap(String json) {
        return new Gson().fromJson(json, new TypeToken<HashMap<String, Object>>() {
        }.getType());
    }

    private static Gson gson_instance;
    public static Gson defaultGsonInstance() {
     if (gson_instance == null) {
         gson_instance = new GsonBuilder()
                 .excludeFieldsWithoutExposeAnnotation()
                 //.registerTypeAdapter(Date.class, new DateTypeAdapter())
                 // .serializeSpecialFloatingPointValues()
                 .create();
     }
     return gson_instance;
    }

    public static String hourMinuteString() {
        // Date date = new Date();
        // SimpleDateFormat sd = new SimpleDateFormat("HH:mm");
        //  return sd.format(date);
        return hourMinuteString(JoH.tsl());
    }

    public static String hourMinuteString(long timestamp) {
        return android.text.format.DateFormat.format("kk:mm", timestamp).toString();
    }

    public static String dateTimeText(long timestamp) {
        return android.text.format.DateFormat.format("yyyy-MM-dd kk:mm:ss", timestamp).toString();
    }

    public static String dateText(long timestamp) {
        return android.text.format.DateFormat.format("yyyy-MM-dd", timestamp).toString();
    }

    public static String niceTimeSince(long t) {
        return niceTimeScalar(msSince(t));
    }

    public static String niceTimeTill(long t) {
        return niceTimeScalar(-msSince(t));
    }

    // temporary
    public static String niceTimeScalar(long t) {
        String unit = "second";
        t = t / 1000;
        if (t > 59) {
            unit = "minute";
            t = t / 60;
            if (t > 59) {
                unit = "hour";
                t = t / 60;
                if (t > 24) {
                    unit = "day";
                    t = t / 24;
                    if (t > 28) {
                        unit = "week";
                        t = t / 7;
                    }
                }
            }
        }
        if (t != 1) unit = unit + "s";
        return qs((double) t, 0) + " " + unit;
    }

    public static String niceTimeScalarNatural(long t) {
        if (t > 3000000) t = t + 10000; // round up by 10 seconds if nearly an hour
        if ((t > Constants.DAY_IN_MS) && (t < Constants.WEEK_IN_MS * 2)) {
            final SimpleDateFormat df = new SimpleDateFormat("EEEE", Locale.getDefault());
            final String day = df.format(new Date(JoH.tsl() + t));
            return ((t > Constants.WEEK_IN_MS) ? "next " : "") + day;
        } else {
            return niceTimeScalar(t);
        }
    }

    public static String niceTimeScalarRedux(long t) {
        return niceTimeScalar(t).replaceFirst("^1 ", "");
    }

    public static String niceTimeScalarShort(long t) {
        return niceTimeScalar(t).replaceFirst("([A-z]).*$", "$1");
    }


    public static double tolerantParseDouble(String str) throws NumberFormatException {
        return Double.parseDouble(str.replace(",", "."));

    }

    public static String getRFC822String(long timestamp) {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        return dateFormat.format(new Date(timestamp));
    }

    public static PowerManager.WakeLock getWakeLock(final String name, int millis) {
        final PowerManager pm = (PowerManager) xdrip.getAppContext().getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
        wl.acquire(millis);
        if (debug_wakelocks) Log.d(TAG, "getWakeLock: " + name + " " + wl.toString());
        return wl;
    }

    public static void releaseWakeLock(PowerManager.WakeLock wl) {
        if (debug_wakelocks) Log.d(TAG, "releaseWakeLock: " + wl.toString());
        if (wl == null) return;
        if (wl.isHeld()) {
            try {
                wl.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing wakelock: " + e);
            }
        }
    }

    public static PowerManager.WakeLock fullWakeLock(final String name, long millis) {
        final PowerManager pm = (PowerManager) xdrip.getAppContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, name);
        wl.acquire(millis);
        if (debug_wakelocks) Log.d(TAG, "fullWakeLock: " + name + " " + wl.toString());
        return wl;
    }

    public static void fullDatabaseReset() {
        try {
            clearCache();
            ActiveAndroid.dispose();
            ActiveAndroid.initialize(xdrip.getAppContext());
        } catch (Exception e) {
            Log.e(TAG,"Error restarting active android db");
        }
    }

    public static void clearCache() {
        try {
            ActiveAndroid.clearCache();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing active android cache: " + e);
        }
    }

    public static boolean isLANConnected() {
        final ConnectivityManager cm =
                (ConnectivityManager) xdrip.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        final boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnected();
        return isConnected && ((activeNetwork.getType() == ConnectivityManager.TYPE_WIFI)
                || (activeNetwork.getType() == ConnectivityManager.TYPE_ETHERNET)
                || (activeNetwork.getType() == ConnectivityManager.TYPE_BLUETOOTH));
    }

    public static boolean isMobileDataOrEthernetConnected() {
        final ConnectivityManager cm =
                (ConnectivityManager) xdrip.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        final boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnected();
        return isConnected && ((activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) || (activeNetwork.getType() == ConnectivityManager.TYPE_ETHERNET));
    }

    public static boolean isAnyNetworkConnected() {
        final ConnectivityManager cm =
                (ConnectivityManager) xdrip.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null &&
                activeNetwork.isConnected();
    }

    public static boolean isScreenOn() {
        final PowerManager pm = (PowerManager) xdrip.getAppContext().getSystemService(Context.POWER_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return pm.isInteractive();
        } else {
            return pm.isScreenOn();
        }
    }

    public static boolean isOngoingCall() {
        try {
            AudioManager manager = (AudioManager) xdrip.getAppContext().getSystemService(Context.AUDIO_SERVICE);
            return (manager.getMode() == AudioManager.MODE_IN_CALL);
            // possibly should have MODE_IN_COMMUNICATION as well
        } catch (Exception e) {
            return false;
        }
    }

    public static String getWifiSSID() {
        try {
            final WifiManager wifi_manager = (WifiManager) xdrip.getAppContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi_manager.isWifiEnabled()) {
                final WifiInfo wifiInfo = wifi_manager.getConnectionInfo();
                if (wifiInfo != null) {
                    final NetworkInfo.DetailedState wifi_state = WifiInfo.getDetailedStateOf(wifiInfo.getSupplicantState());
                    if (wifi_state == NetworkInfo.DetailedState.CONNECTED
                            || wifi_state == NetworkInfo.DetailedState.OBTAINING_IPADDR
                            || wifi_state == NetworkInfo.DetailedState.CAPTIVE_PORTAL_CHECK) {
                        String ssid = wifiInfo.getSSID();
                        if (ssid.equals("<unknown ssid>")) return null; // WifiSsid.NONE;
                        if (ssid.charAt(0) == '"') ssid = ssid.substring(1);
                        if (ssid.charAt(ssid.length() - 1) == '"')
                            ssid = ssid.substring(0, ssid.length() - 1);
                        return ssid;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Got exception in getWifiSSID: " + e);
        }
        return null;
    }

    public static boolean getWifiFuzzyMatch(String local, String remote) {
        if ((local == null) || (remote == null) || (local.length() == 0) || (remote.length() == 0))
            return false;
        final int slen = Math.min(local.length(), remote.length());
        final int llen = Math.max(local.length(), remote.length());
        int matched = 0;
        for (int i = 0; i < slen; i++) {
            if (local.charAt(i) == (remote.charAt(i))) matched++;
        }
        boolean result = false;
        if (matched == slen) result = true; // shorter string is substring
        final double quota = (double) matched / (double) llen;
        final int dmatch = llen - matched;
        if (slen > 2) {
            if (dmatch < 3) result = true;
            if (quota > 0.80) result = true;
        }
        //Log.d(TAG, "l:" + local + " r:" + remote + " slen:" + slen + " llen:" + llen + " matched:" + matched + "  q:" + JoH.qs(quota, 2) + "  dm:" + dmatch + " RESULT: " + result);
        return result;
    }

    public static boolean runOnUiThread(Runnable theRunnable) {
        final Handler mainHandler = new Handler(xdrip.getAppContext().getMainLooper());
        return mainHandler.post(theRunnable);
    }

    public static boolean runOnUiThreadDelayed(Runnable theRunnable, long delay) {
        final Handler mainHandler = new Handler(xdrip.getAppContext().getMainLooper());
        return mainHandler.postDelayed(theRunnable, delay);
    }

    public static void removeUiThreadRunnable(Runnable theRunnable) {
        final Handler mainHandler = new Handler(xdrip.getAppContext().getMainLooper());
        mainHandler.removeCallbacks(theRunnable);
    }

    public static void hardReset() {
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Exception e) {
            // not much to do
        }
    }

    public static void static_toast(final Context context, final String msg, final int length) {
        try {
            if (!runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, msg, length).show();
                        Log.i(TAG, "Displaying toast using fallback");
                    } catch (Exception e) {
                        Log.e(TAG, "Exception processing runnable toast ui thread: " + e);
                        Home.toaststatic(msg);
                    }
                }
            })) {
                Log.e(TAG, "Couldn't display toast via ui thread: " + msg);
                Home.toaststatic(msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't display toast due to exception: " + msg + " e: " + e.toString());
            Home.toaststatic(msg);
        }
    }

    public static void static_toast_long(final String msg) {
        static_toast(xdrip.getAppContext(), msg, Toast.LENGTH_LONG);
    }

    public static void static_toast_short(final String msg) {
        static_toast(xdrip.getAppContext(), msg, Toast.LENGTH_SHORT);
    }

    public static void static_toast_long(Context context, final String msg) {
        static_toast(context, msg, Toast.LENGTH_LONG);
    }

    public static void show_ok_dialog(final Activity activity, String title, String message, final Runnable runnable) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(activity, R.style.AppTheme));
            builder.setTitle(title);
            builder.setMessage(message);
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        dialog.dismiss();
                    } catch (Exception e) {
                        //
                    }
                    if (runnable != null) {
                        runOnUiThreadDelayed(runnable, 10);
                    }
                }
            });

            builder.create().show();
        } catch (Exception e) {
            Log.wtf(TAG, "show_dialog exception: " + e);
            static_toast_long(message);
        }
    }

    public static synchronized void playResourceAudio(int id) {
        playSoundUri(getResourceURI(id));
    }

    public static String getResourceURI(int id) {
        return ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + xdrip.getAppContext().getPackageName() + "/" + id;
    }

    public static synchronized MediaPlayer playSoundUri(String soundUri) {
        try {
            JoH.getWakeLock("joh-playsound", 10000);
            final MediaPlayer player = MediaPlayer.create(xdrip.getAppContext(), Uri.parse(soundUri));
            player.setLooping(false);
            player.start();
            return player;
        } catch (Exception e) {
            Log.wtf(TAG, "Failed to play audio: " + soundUri + " exception:" + e);
            return null;
        }
    }

    public static String urlEncode(String source) {
        try {
            return URLEncoder.encode(source, "UTF-8");
        } catch (Exception e) {
            return "encoding-exception";
        }
    }

    public static Object cloneObject(Object obj) {
        try {
            Object clone = obj.getClass().newInstance();
            for (Field field : obj.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                field.set(clone, field.get(obj));
            }
            return clone;
        } catch (Exception e) {
            return null;
        }
    }

    public static void startService(Class c) {
        xdrip.getAppContext().startService(new Intent(xdrip.getAppContext(), c));
    }

    public static void startActivity(Class c) {
        xdrip.getAppContext().startActivity(getStartActivityIntent(c));
    }


    public static Intent getStartActivityIntent(Class c) {
        return new Intent(xdrip.getAppContext(), c).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }


    public static void goFullScreen(boolean fullScreen, View decorView) {

        if (fullScreen) {
            if (Build.VERSION.SDK_INT >= 19) {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } else {
            decorView.setSystemUiVisibility(0); // TODO will this need revisiting in later android vers?
        }
    }


    public static Bitmap screenShot(View view, String annotation) {

        if (view == null) {
            static_toast_long("View is null in screenshot!");
            return null;
        }
        final int width = view.getWidth();
        final int height = view.getHeight();
        Log.d(TAG, "Screenshot called: " + width + "," + height);
        final Bitmap bitmap = Bitmap.createBitmap(width,
                height, Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(bitmap);
        if (Pref.getBooleanDefaultFalse(SHOW_STATISTICS_PRINT_COLOR)) {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, width, height, paint);
        }


        view.destroyDrawingCache();
        view.layout(0, 0, width, height);
        view.draw(canvas);

        if (annotation != null) {
            final int offset = (annotation != null) ? 40 : 0;
            final Bitmap bitmapf = Bitmap.createBitmap(width,
                    height + offset, Bitmap.Config.ARGB_8888);
            final Canvas canvasf = new Canvas(bitmapf);

            Paint paint = new Paint();
            if (Pref.getBooleanDefaultFalse(SHOW_STATISTICS_PRINT_COLOR)) {
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL);
                canvasf.drawRect(0, 0, width, offset, paint);
                paint.setColor(Color.BLACK);
            } else {
                paint.setColor(Color.GRAY);
            }
            paint.setTextSize(20);
            // paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OUT));
            canvasf.drawBitmap(bitmap, 0, offset, paint);
            canvasf.drawText(annotation, 50, (offset / 2) + 5, paint);
            bitmap.recycle();
            return bitmapf;
        }

        return bitmap;
    }

    public static Bitmap screenShot2(View view) {
        Log.d(TAG, "Screenshot2 called: " + view.getWidth() + "," + view.getHeight());
        view.setDrawingCacheEnabled(true);
        view.buildDrawingCache(true);
        final Bitmap bitmap = view.getDrawingCache(true);
        return bitmap;
    }


    public static void bitmapToFile(Bitmap bitmap, String path, String fileName) {

        if (bitmap == null) return;
        Log.d(TAG, "bitmapToFile: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        File dir = new File(path);
        if (!dir.exists())
            dir.mkdirs();
        final File file = new File(path, fileName);
        try {
            FileOutputStream output = new FileOutputStream(file);
            final boolean result = bitmap.compress(Bitmap.CompressFormat.PNG, 80, output);
            output.flush();
            output.close();
            Log.d(TAG, "Bitmap compress result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "Got exception writing bitmap to file: " + e);
        }
    }

    public static void shareImage(Context context, File file) {
        Uri uri = Uri.fromFile(file);
        final Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);
        intent.setType("image/*");
        intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "");
        intent.putExtra(android.content.Intent.EXTRA_TEXT, "");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        try {
            context.startActivity(Intent.createChooser(intent, "Share"));
        } catch (ActivityNotFoundException e) {
            static_toast_long("No suitable app to show an image!");
        }
    }

    public static void cancelAlarm(Context context, PendingIntent serviceIntent) {
        // do we want a try catch block here?
        final AlarmManager alarm = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (serviceIntent != null) {
            Log.d(TAG, "Cancelling alarm " + serviceIntent.getCreatorPackage());
            alarm.cancel(serviceIntent);
        } else {
            Log.d(TAG, "Cancelling alarm: serviceIntent is null");
        }
    }

    public static long wakeUpIntent(Context context, long delayMs, PendingIntent pendingIntent) {
        final long wakeTime = JoH.tsl() + delayMs;
        if (pendingIntent != null) {
            Log.d(TAG, "Scheduling wakeup intent: " + dateTimeText(wakeTime));
            final AlarmManager alarm = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            try {
                alarm.cancel(pendingIntent);
            } catch (Exception e) {
                Log.e(TAG, "Exception cancelling alarm in wakeUpIntent: " + e);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (buggy_samsung) {
                    alarm.setAlarmClock(new AlarmManager.AlarmClockInfo(wakeTime, null), pendingIntent);
                } else {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
            } else
                alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else {
            Log.e(TAG, "wakeUpIntent - pending intent was null!");
        }
        return wakeTime;
    }

    public static void scheduleNotification(Context context, String title, String body, int delaySeconds, int notification_id) {
        final Intent notificationIntent = new Intent(context, Home.class).putExtra(Home.SHOW_NOTIFICATION, title).putExtra("notification_body", body).putExtra("notification_id", notification_id);
        final PendingIntent pendingIntent = PendingIntent.getActivity(context, notification_id, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Log.d(TAG, "Scheduling notification: " + title + " / " + body);
        wakeUpIntent(context, delaySeconds * 1000, pendingIntent);
    }

    public static void cancelNotification(int notificationId) {
        final NotificationManager mNotifyMgr = (NotificationManager) xdrip.getAppContext().getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.cancel(notificationId);
    }

    public static void showNotification(String title, String content, PendingIntent intent, int notificationId, boolean sound, boolean vibrate, boolean onetime) {
        showNotification(title, content, intent, notificationId, sound, vibrate, null, null);
    }

    public static void showNotification(String title, String content, PendingIntent intent, int notificationId, boolean sound, boolean vibrate, PendingIntent deleteIntent, Uri sound_uri) {
        showNotification(title, content, intent, notificationId, null, sound, vibrate, deleteIntent, sound_uri, null);
    }

    public static void showNotification(String title, String content, PendingIntent intent, int notificationId, boolean sound, boolean vibrate, PendingIntent deleteIntent, Uri sound_uri, String bigmsg) {
        showNotification(title, content, intent, notificationId, null, sound, vibrate, deleteIntent, sound_uri, bigmsg);
    }

    public static void showNotification(String title, String content, PendingIntent intent, int notificationId, String channelId, boolean sound, boolean vibrate, PendingIntent deleteIntent, Uri sound_uri, String bigmsg) {
        final NotificationCompat.Builder mBuilder = notificationBuilder(title, content, intent, channelId);
        final long[] vibratePattern = {0, 1000, 300, 1000, 300, 1000};
        if (vibrate) mBuilder.setVibrate(vibratePattern);
        if (deleteIntent != null) mBuilder.setDeleteIntent(deleteIntent);
        mBuilder.setLights(0xff00ff00, 300, 1000);
        if (sound) {
            Uri soundUri = (sound_uri != null) ? sound_uri : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            mBuilder.setSound(soundUri);
        }

        if (bigmsg != null) {
            mBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(bigmsg));
        }

        final NotificationManager mNotifyMgr = (NotificationManager) xdrip.getAppContext().getSystemService(Context.NOTIFICATION_SERVICE);
        // if (!onetime) mNotifyMgr.cancel(notificationId);

        mNotifyMgr.notify(notificationId, XdripNotificationCompat.build(mBuilder));
    }

    private static NotificationCompat.Builder notificationBuilder(String title, String content, PendingIntent intent, String channelId) {
        return new NotificationCompat.Builder(xdrip.getAppContext(), channelId)
                .setSmallIcon(R.drawable.ic_action_communication_invert_colors_on)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(intent);
    }


    public static void releaseOrientation(Activity activity) {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    public static void lockOrientation(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        int rotation = display.getRotation();
        int height;
        int width;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB_MR2) {
            height = display.getHeight();
            width = display.getWidth();
        } else {
            Point size = new Point();
            display.getSize(size);
            height = size.y;
            width = size.x;
        }
        switch (rotation) {
            case Surface.ROTATION_90:
                if (width > height)
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                else
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                break;
            case Surface.ROTATION_180:
                if (height > width)
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                else
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;
            case Surface.ROTATION_270:
                if (width > height)
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                else
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            default:
                if (height > width)
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                else
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }

    public static boolean areWeRunningOnAndroidWear() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                && (xdrip.getAppContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH;
    }

    public static boolean isAirplaneModeEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }


    public static byte[] convertPinToBytes(String pin) {
        if (pin == null) {
            return null;
        }
        byte[] pinBytes;
        try {
            pinBytes = pin.getBytes("UTF-8");
        } catch (UnsupportedEncodingException uee) {
            Log.e(TAG, "UTF-8 not supported?!?");  // this should not happen
            return null;
        }
        if (pinBytes.length <= 0 || pinBytes.length > 16) {
            return null;
        }
        return pinBytes;
    }

    public static boolean doPairingRequest(Context context, BroadcastReceiver broadcastReceiver, Intent intent, String mBluetoothDeviceAddress) {
        return doPairingRequest(context, broadcastReceiver, intent, mBluetoothDeviceAddress, null);
    }

    @TargetApi(19)
    public static boolean doPairingRequest(Context context, BroadcastReceiver broadcastReceiver, Intent intent, String mBluetoothDeviceAddress, String pinHint) {
        if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) {
                int type = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
                if ((mBluetoothDeviceAddress != null) && (device.getAddress().equals(mBluetoothDeviceAddress))) {
                    if ((type == PAIRING_VARIANT_PIN) && (pinHint != null)) {
                        device.setPin(convertPinToBytes(pinHint));
                        Log.d(TAG, "Setting pairing pin to " + pinHint);
                        broadcastReceiver.abortBroadcast();
                    }
                    try {
                        UserError.Log.e(TAG, "Pairing type: " + type);
                        if (type != PAIRING_VARIANT_PIN) {
                            device.setPairingConfirmation(true);
                            JoH.static_toast_short("xDrip Pairing");
                            broadcastReceiver.abortBroadcast();
                        } else {
                            Log.d(TAG,"Attempting to passthrough PIN pairing");
                        }

                    } catch (Exception e) {
                        UserError.Log.e(TAG, "Could not set pairing confirmation due to exception: " + e);
                        if (JoH.ratelimit("failed pair confirmation", 200)) {
                            // BluetoothDevice.PAIRING_VARIANT_CONSENT)
                            if (type == 3) {
                                JoH.static_toast_long("Please confirm the bluetooth pairing request");
                                return false;
                            } else {
                                JoH.static_toast_long("Failed to pair, may need to do it via Android Settings");
                                device.createBond(); // for what it is worth
                                return false;
                            }
                        }
                    }
                } else {
                    UserError.Log.e(TAG, "Received pairing request for not our device: " + device.getAddress());
                }
            } else {
                UserError.Log.e(TAG, "Device was null in pairing receiver");
            }
        }
        return true;
    }

    public static String getLocalBluetoothName() {
        try {
            final String name = BluetoothAdapter.getDefaultAdapter().getName();
            if (name == null) return "";
            return name;
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean refreshDeviceCache(String thisTAG, BluetoothGatt gatt){
        try {
            final Method method = gatt.getClass().getMethod("refresh", new Class[0]);
            if (method != null) {
                return (Boolean) method.invoke(gatt, new Object[0]);
            }
        }
        catch (Exception e) {
            Log.e(thisTAG, "An exception occured while refreshing gatt device cache: "+e);
        }
        return false;
    }

    public synchronized static void setBluetoothEnabled(Context context, boolean state) {
        try {

            if (isAirplaneModeEnabled(context)) {
                UserError.Log.e(TAG, "Not setting bluetooth to state: " + state + " due to airplane mode being enabled");
                return;
            }

            if (android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2) {

                final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bluetoothManager == null) {
                    UserError.Log.e(TAG, "Couldn't get bluetooth in setBluetoothEnabled");
                    return;
                }
                BluetoothAdapter mBluetoothAdapter = bluetoothManager.getAdapter(); // local scope only
                if (mBluetoothAdapter == null) {
                    UserError.Log.e(TAG, "Couldn't get bluetooth adapter in setBluetoothEnabled");
                    return;
                }
                try {
                    if (state) {
                        UserError.Log.i(TAG, "Setting bluetooth enabled");
                        mBluetoothAdapter.enable();
                    } else {
                        UserError.Log.i(TAG, "Setting bluetooth disabled");
                        mBluetoothAdapter.disable();

                    }
                } catch (Exception e) {
                    UserError.Log.e(TAG, "Exception when enabling/disabling bluetooth: " + e);
                }
            } else {
                UserError.Log.e(TAG, "Bluetooth low energy not supported");
            }
        } finally {
            //
        }
    }

    public static void niceRestartBluetooth(Context context) {
        if (!isOngoingCall()) {
            if (ratelimit("joh-restart-bluetooth", 600)) {
                restartBluetooth(context);
            }
        }
    }

    public synchronized static void restartBluetooth(final Context context) {
        restartBluetooth(context, 0);
    }

    public synchronized static void restartBluetooth(final Context context, final int startInMs) {
        new Thread() {
            @Override
            public void run() {
                final PowerManager.WakeLock wl = getWakeLock("restart-bluetooth", 60000);
                Log.d(TAG, "Restarting bluetooth");
                try {
                    if (startInMs > 0) {
                        try {
                            Thread.sleep(startInMs);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Got interrupted waiting to start resetBluetooth");
                        }
                    }
                    setBluetoothEnabled(context, false);
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Got interrupted in resetBluetooth");
                    }
                    setBluetoothEnabled(context, true);
                } finally {
                    releaseWakeLock(wl);
                }
            }
        }.start();
    }


    public static synchronized void unBond(String transmitterMAC) {

        UserError.Log.d(TAG, "unBond() start");
        if (transmitterMAC == null) return;
        try {
            final BluetoothAdapter mBluetoothAdapter = ((BluetoothManager) xdrip.getAppContext().getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

            final Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getAddress() != null) {
                        if (device.getAddress().equals(transmitterMAC)) {
                            try {
                                UserError.Log.e(TAG, "removingBond: " + transmitterMAC);
                                Method m = device.getClass().getMethod("removeBond", (Class[]) null);
                                m.invoke(device, (Object[]) null);

                            } catch (Exception e) {
                                UserError.Log.e(TAG, e.getMessage(), e);
                            }
                        }

                    }
                }
            }
        } catch (Exception e) {
            UserError.Log.e(TAG, "Exception during unbond! " + transmitterMAC, e);
        }
        UserError.Log.d(TAG, "unBond() finished");
    }


    public static Map<String, String> bundleToMap(Bundle bundle) {
        final HashMap<String, String> map = new HashMap<>();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value != null) {
                map.put(key, value.toString());
            }
        }
        return map;
    }

    public static void threadSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            //
        }
    }

    public static ByteBuffer bArrayAsBuffer(byte[] bytes) {
        final ByteBuffer bb = ByteBuffer.allocate(bytes.length);
        bb.put(bytes);
        return bb;
    }

    public static long checksum(byte[] bytes) {
        if (bytes == null) return 0;
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }



    public static byte[] bchecksum(byte[] bytes) {
        final long c = checksum(bytes);
        final byte[] buf = new byte[4];
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).putInt((int) c);
        return buf;
    }

    public static boolean checkChecksum(byte[] bytes) {
        if ((bytes == null) || (bytes.length < 4)) return false;
        final CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        final long buffer_crc = UnsignedInts.toLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(bytes.length - 4));
        return buffer_crc == crc.getValue();
    }

    public static int parseIntWithDefault(String number, int radix, int defaultVal) {
        try {
            return Integer.parseInt(number, radix);
       } catch (NumberFormatException e) {
           Log.e(TAG, "Error parsing integer number = " + number + " radix = " + radix);
           return defaultVal;
       }
    }

    public static double roundDouble(double value, int places) {
        if (places < 0) throw new IllegalArgumentException("Invalid decimal places");
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
    
    
    private final static long[] crc16table = {
            0, 4489, 8978, 12955, 17956, 22445, 25910, 29887, 35912,
            40385, 44890, 48851, 51820, 56293, 59774, 63735, 4225, 264,
            13203, 8730, 22181, 18220, 30135, 25662, 40137, 36160, 49115,
            44626, 56045, 52068, 63999, 59510, 8450, 12427, 528, 5017,
            26406, 30383, 17460, 21949, 44362, 48323, 36440, 40913, 60270,
            64231, 51324, 55797, 12675, 8202, 4753, 792, 30631, 26158,
            21685, 17724, 48587, 44098, 40665, 36688, 64495, 60006, 55549,
            51572, 16900, 21389, 24854, 28831, 1056, 5545, 10034, 14011,
            52812, 57285, 60766, 64727, 34920, 39393, 43898, 47859, 21125,
            17164, 29079, 24606, 5281, 1320, 14259, 9786, 57037, 53060,
            64991, 60502, 39145, 35168, 48123, 43634, 25350, 29327, 16404,
            20893, 9506, 13483, 1584, 6073, 61262, 65223, 52316, 56789,
            43370, 47331, 35448, 39921, 29575, 25102, 20629, 16668, 13731,
            9258, 5809, 1848, 65487, 60998, 56541, 52564, 47595, 43106,
            39673, 35696, 33800, 38273, 42778, 46739, 49708, 54181, 57662,
            61623, 2112, 6601, 11090, 15067, 20068, 24557, 28022, 31999,
            38025, 34048, 47003, 42514, 53933, 49956, 61887, 57398, 6337,
            2376, 15315, 10842, 24293, 20332, 32247, 27774, 42250, 46211,
            34328, 38801, 58158, 62119, 49212, 53685, 10562, 14539, 2640,
            7129, 28518, 32495, 19572, 24061, 46475, 41986, 38553, 34576,
            62383, 57894, 53437, 49460, 14787, 10314, 6865, 2904, 32743,
            28270, 23797, 19836, 50700, 55173, 58654, 62615, 32808, 37281,
            41786, 45747, 19012, 23501, 26966, 30943, 3168, 7657, 12146,
            16123, 54925, 50948, 62879, 58390, 37033, 33056, 46011, 41522,
            23237, 19276, 31191, 26718, 7393, 3432, 16371, 11898, 59150,
            63111, 50204, 54677, 41258, 45219, 33336, 37809, 27462, 31439,
            18516, 23005, 11618, 15595, 3696, 8185, 63375, 58886, 54429,
            50452, 45483, 40994, 37561, 33584, 31687, 27214, 22741, 18780,
            15843, 11370, 7921, 3960 };

    // first two bytes = crc16 included in data
    static long computeCRC16(byte[] data, int start, int size){
        long crc = 0xffff;
        for (int i = start + 2; i < start + size; i++) {
            crc = ((crc >> 8) ^ crc16table[(int)(crc ^   (data[i] & 0xFF) ) & 0xff]);
        }
        
        long reverseCrc = 0;
        for (int i=0; i <16; i++) {
            reverseCrc = (reverseCrc << 1) | (crc & 1);
            crc >>= 1;
        }
        return reverseCrc;
    }

    static boolean CheckCRC16(byte[] data, int start, int size) {
        long crc = computeCRC16(data, start, size);
        return crc == ((data[start+1]& 0xFF) * 256 + (data[start] & 0xff)); 
    }
    
    public static boolean LibreCrc(byte[] data) {
        if(data.length < 344) {
            Log.e(TAG, "Must have at least 344 bytes for libre data");
            return false;
        }
        boolean cheksum_ok = CheckCRC16(data, 0 ,24);
        cheksum_ok &= CheckCRC16(data, 24 ,296);
        cheksum_ok &= CheckCRC16(data, 320 ,24);
        return cheksum_ok;
        
    }
}
