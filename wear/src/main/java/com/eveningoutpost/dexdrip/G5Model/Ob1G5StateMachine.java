package com.eveningoutpost.dexdrip.G5Model;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.os.Build;
import android.os.PowerManager;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.ImportedLibraries.usbserial.util.HexDump;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Prediction;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.TransmitterData;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.Services.Ob1G5CollectionService;
import com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.UtilityModels.Constants;
import com.eveningoutpost.dexdrip.UtilityModels.Inevitable;
import com.eveningoutpost.dexdrip.UtilityModels.NotificationChannels;
import com.eveningoutpost.dexdrip.UtilityModels.PersistentStore;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;
import com.eveningoutpost.dexdrip.utils.PowerStateReceiver;
import com.eveningoutpost.dexdrip.wearintegration.WatchUpdaterService;
import com.eveningoutpost.dexdrip.xdrip;
import com.google.gson.reflect.TypeToken;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.exceptions.BleCannotSetCharacteristicNotificationException;
import com.polidea.rxandroidble.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble.exceptions.BleGattCharacteristicException;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import rx.schedulers.Schedulers;

import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.Authentication;
import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.Control;
import static com.eveningoutpost.dexdrip.G5Model.BluetoothServices.ProbablyBackfill;
import static com.eveningoutpost.dexdrip.Models.JoH.msSince;
import static com.eveningoutpost.dexdrip.Models.JoH.pratelimit;
import static com.eveningoutpost.dexdrip.Services.G5BaseService.G5_BATTERY_FROM_MARKER;
import static com.eveningoutpost.dexdrip.Services.G5BaseService.G5_BATTERY_LEVEL_MARKER;
import static com.eveningoutpost.dexdrip.Services.G5BaseService.G5_BATTERY_MARKER;
import static com.eveningoutpost.dexdrip.Services.G5BaseService.G5_BATTERY_WEARABLE_SEND;
import static com.eveningoutpost.dexdrip.Services.G5BaseService.G5_FIRMWARE_MARKER;
import static com.eveningoutpost.dexdrip.Services.Ob1G5CollectionService.G6_SCALING;
import static com.eveningoutpost.dexdrip.Services.Ob1G5CollectionService.android_wear;
import static com.eveningoutpost.dexdrip.Services.Ob1G5CollectionService.getTransmitterID;
import static com.eveningoutpost.dexdrip.Services.Ob1G5CollectionService.onlyUsingNativeMode;
import static com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder.DEXCOM_PERIOD;
import static com.eveningoutpost.dexdrip.UtilityModels.Constants.HOUR_IN_MS;
import static com.eveningoutpost.dexdrip.UtilityModels.Constants.MINUTE_IN_MS;
import static com.eveningoutpost.dexdrip.UtilityModels.Constants.SECOND_IN_MS;

/*
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.exceptions.BleCannotSetCharacteristicNotificationException;
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble2.exceptions.BleGattCharacteristicException;
*/


/**
 * Created by jamorham on 17/09/2017.
 * <p>
 * Handles OB1 G5 communication logic
 */

@SuppressWarnings("AccessStaticViaInstance")
public class Ob1G5StateMachine {

    private static final String TAG = "Ob1G5StateMachine";
    private static final String PREF_SAVED_QUEUE = "Ob1-saved-queue";

    public static final String PREF_QUEUE_DRAINED = "OB1-QUEUE-DRAINED";
    public static final String CLOSED_OK_TEXT = "Closed OK";

    private static final int LOW_BATTERY_WARNING_LEVEL = Pref.getStringToInt("g5-battery-warning-level", 300); // voltage a < this value raises warnings;
    private static final long BATTERY_READ_PERIOD_MS = HOUR_IN_MS * 12; // how often to poll battery data (12 hours)
    private static final long MAX_BACKFILL_PERIOD_MS = HOUR_IN_MS * 3; // how far back to request backfill data
    private static final int BACKFILL_CHECK_SMALL = 3;
    private static final int BACKFILL_CHECK_LARGE = (int) (MAX_BACKFILL_PERIOD_MS / DEXCOM_PERIOD);

    private static final boolean getVersionDetails = true; // try to load firmware version details
    private static final boolean getBatteryDetails = true; // try to load battery info details

    private static final LinkedBlockingDeque<Ob1Work> commandQueue = new LinkedBlockingDeque<>();

    private static boolean speakSlowly = false; // slow down bluetooth comms for android wear etc
    private static int nextBackFillCheckSize = BACKFILL_CHECK_SMALL;

    private static final boolean d = false;

    private static volatile long lastGlucosePacket = 0;
    private static volatile long lastUsableGlucosePacket = 0;
    private static volatile BgReading lastGlucoseBgReading;
    private static volatile boolean backup_loaded = false;

    // Auth Check + Request
    @SuppressLint("CheckResult")
    public static boolean doCheckAuth(Ob1G5CollectionService parent, RxBleConnection connection) {

        if (connection == null) return false;
        parent.msg("Authorizing");

        if (parent.android_wear) {
            speakSlowly = true;
            UserError.Log.d(TAG, "Setting speak slowly to true"); // WARN should be reactive or on named devices
        }

        final AuthRequestTxMessage authRequest = new AuthRequestTxMessage(getTokenSize(), JoH.areWeRunningOnAndroidWear() && !Pref.getBooleanDefaultFalse("only_ever_use_wear_collector"));
        UserError.Log.i(TAG, "AuthRequestTX: " + JoH.bytesToHex(authRequest.byteSequence));

        connection.setupNotification(Authentication)
                // .timeout(10, TimeUnit.SECONDS)
                .timeout(15, TimeUnit.SECONDS) // WARN
                // .observeOn(Schedulers.newThread()) // needed?
                .doOnNext(notificationObservable -> {
                    connection.writeCharacteristic(Authentication, nn(authRequest.byteSequence))
                            .subscribe(
                                    characteristicValue -> {
                                        // Characteristic value confirmed.
                                        if (d)
                                            UserError.Log.d(TAG, "Wrote authrequest, got: " + JoH.bytesToHex(characteristicValue));
                                        speakSlowly();
                                        connection.readCharacteristic(Authentication).subscribe(
                                                readValue -> {
                                                    PacketShop pkt = classifyPacket(readValue);
                                                    UserError.Log.d(TAG, "Read from auth request: " + pkt.type + " " + JoH.bytesToHex(readValue));

                                                    switch (pkt.type) {
                                                        case AuthChallengeRxMessage:
                                                            // Respond to the challenge request
                                                            byte[] challengeHash = calculateHash(((AuthChallengeRxMessage) pkt.msg).challenge);
                                                            if (d)
                                                                UserError.Log.d(TAG, "challenge hash" + Arrays.toString(challengeHash));
                                                            if (challengeHash != null) {
                                                                if (d)
                                                                    UserError.Log.d(TAG, "Transmitter trying auth challenge");

                                                                connection.writeCharacteristic(Authentication, nn(new AuthChallengeTxMessage(challengeHash).byteSequence))
                                                                        .subscribe(
                                                                                challenge_value -> {

                                                                                    speakSlowly();

                                                                                    connection.readCharacteristic(Authentication)
                                                                                            //.observeOn(Schedulers.io())
                                                                                            .subscribe(
                                                                                                    status_value -> {
                                                                                                        // interpret authentication response
                                                                                                        final PacketShop status_packet = classifyPacket(status_value);
                                                                                                        UserError.Log.d(TAG, status_packet.type + " " + JoH.bytesToHex(status_value));
                                                                                                        if (status_packet.type == PACKET.AuthStatusRxMessage) {
                                                                                                            final AuthStatusRxMessage status = (AuthStatusRxMessage) status_packet.msg;
                                                                                                            if (d)
                                                                                                                UserError.Log.d(TAG, ("Authenticated: " + status.isAuthenticated() + " " + status.isBonded()));
                                                                                                            if (status.isAuthenticated()) {
                                                                                                                if (status.isBonded()) {
                                                                                                                    parent.msg("Authenticated");
                                                                                                                    parent.authResult(true);
                                                                                                                    parent.changeState(Ob1G5CollectionService.STATE.GET_DATA);
                                                                                                                    throw new OperationSuccess("Authenticated");
                                                                                                                } else {
                                                                                                                    //parent.unBond(); // bond must be invalid or not existing // WARN
                                                                                                                    parent.changeState(Ob1G5CollectionService.STATE.PREBOND);
                                                                                                                    // TODO what to do here?
                                                                                                                }
                                                                                                            } else {
                                                                                                                parent.msg("Not Authorized! (Wrong TxID?)");
                                                                                                                UserError.Log.e(TAG, "Authentication failed!!!!");
                                                                                                                parent.incrementErrors();
                                                                                                                // TODO? try again?
                                                                                                            }
                                                                                                        } else {
                                                                                                            UserError.Log.e(TAG, "Got unexpected packet when looking for auth status: " + status_packet.type + " " + JoH.bytesToHex(status_value));
                                                                                                            parent.incrementErrors();
                                                                                                            // TODO what to do here?
                                                                                                        }

                                                                                                    }, throwable -> {
                                                                                                        if (throwable instanceof OperationSuccess) {
                                                                                                            UserError.Log.d(TAG, "Stopping auth challenge listener due to success");
                                                                                                        } else {
                                                                                                            UserError.Log.e(TAG, "Could not read reply to auth challenge: " + throwable);
                                                                                                            parent.incrementErrors();
                                                                                                            speakSlowly = true;
                                                                                                        }
                                                                                                    });
                                                                                }, throwable -> {
                                                                                    UserError.Log.e(TAG, "Could not write auth challenge reply: " + throwable);
                                                                                    parent.incrementErrors();
                                                                                });

                                                            } else {
                                                                UserError.Log.e(TAG, "Could not generate challenge hash! - resetting");
                                                                parent.changeState(Ob1G5CollectionService.STATE.INIT);
                                                                parent.incrementErrors();
                                                                return;
                                                            }

                                                            break;

                                                        default:
                                                            UserError.Log.e(TAG, "Unhandled packet type in reply: " + pkt.type + " " + JoH.bytesToHex(readValue));
                                                            parent.incrementErrors();
                                                            // TODO what to do here?
                                                            break;
                                                    }

                                                }, throwable -> {
                                                    UserError.Log.e(TAG, "Could not read after AuthRequestTX: " + throwable);
                                                });
                                        //parent.background_automata();
                                    },
                                    throwable -> {
                                        UserError.Log.e(TAG, "Could not write AuthRequestTX: " + throwable);
                                        parent.incrementErrors();
                                    }

                            );
                }).flatMap(notificationObservable -> notificationObservable)
                //.timeout(5, TimeUnit.SECONDS)
                //.observeOn(Schedulers.newThread())
                .subscribe(bytes -> {
                    // incoming notifications
                    UserError.Log.e(TAG, "Received Authentication notification bytes: " + JoH.bytesToHex(bytes));

                }, throwable -> {
                    if (!(throwable instanceof OperationSuccess)) {
                        if (((parent.getState() == Ob1G5CollectionService.STATE.CLOSED)
                                || (parent.getState() == Ob1G5CollectionService.STATE.CLOSE))
                                && (throwable instanceof BleDisconnectedException)) {
                            UserError.Log.d(TAG, "normal authentication notification throwable: (" + parent.getState() + ") " + throwable + " " + JoH.dateTimeText(JoH.tsl()));
                            parent.connectionStateChange(CLOSED_OK_TEXT);
                        } else if ((parent.getState() == Ob1G5CollectionService.STATE.BOND) && (throwable instanceof TimeoutException)) {
                            // TODO Trigger on Error count / Android wear metric
                            // UserError.Log.e(TAG,"Attempting to reset/create bond due to: "+throwable);
                            // parent.reset_bond(true);
                            // parent.unBond(); // WARN
                        } else {
                            UserError.Log.e(TAG, "authentication notification  throwable: (" + parent.getState() + ") " + throwable + " " + JoH.dateTimeText(JoH.tsl()));
                            parent.incrementErrors();
                            if (throwable instanceof BleCannotSetCharacteristicNotificationException
                                    || throwable instanceof BleGattCharacteristicException) {
                                parent.tryGattRefresh();
                                parent.changeState(Ob1G5CollectionService.STATE.SCAN);
                            }
                        }
                        if ((throwable instanceof BleDisconnectedException) || (throwable instanceof TimeoutException)) {
                            if ((parent.getState() == Ob1G5CollectionService.STATE.BOND) || (parent.getState() == Ob1G5CollectionService.STATE.CHECK_AUTH)) {

                                if (parent.getState() == Ob1G5CollectionService.STATE.BOND) {
                                    UserError.Log.d(TAG, "SLEEPING BEFORE RECONNECT");
                                    threadSleep(15000);
                                }
                                UserError.Log.d(TAG, "REQUESTING RECONNECT");
                                parent.changeState(Ob1G5CollectionService.STATE.SCAN);
                            }
                        }
                    }
                });
        return true;
    }

    private static final int SPEAK_SLOWLY_DELAY = 300;

    private static int speakSlowlyDelay() {
        return speakSlowly ? SPEAK_SLOWLY_DELAY : 0;
    }

    private static void speakSlowly() {
        if (speakSlowly) {
            UserError.Log.d(TAG, "Speaking slowly");
            threadSleep(SPEAK_SLOWLY_DELAY);
        }
    }

    private static void threadSleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {
            UserError.Log.e(TAG, "Failed to sleep for " + ms + " due to: " + e);
        }
    }


    public synchronized static void doKeepAlive(Ob1G5CollectionService parent, RxBleConnection connection, Runnable runnable) {
        if (connection == null) return;
        connection.writeCharacteristic(Authentication, nn(new KeepAliveTxMessage(60).byteSequence))
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(
                        characteristicValue -> {
                            UserError.Log.d(TAG, "Sent keep-alive " + ((runnable != null) ? "Running runnable chain" : ""));
                            if (runnable != null) {
                                runnable.run();
                            }
                            throw new OperationSuccess("keep-alive runnable complete");
                        }, throwable -> {
                            if (!(throwable instanceof OperationSuccess)) {
                                UserError.Log.e(TAG, "Got error sending keepalive: " + throwable);
                            }
                        });
    }

    // Handle bonding
    @SuppressLint("CheckResult")
    public synchronized static boolean doKeepAliveAndBondRequest(Ob1G5CollectionService parent, RxBleConnection connection) {

        if (connection == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UserError.Log.d(TAG, "Requesting high priority");
            connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH, 500, TimeUnit.MILLISECONDS);
        }
        UserError.Log.e(TAG, "Sending keepalive..");
        connection.writeCharacteristic(Authentication, nn(new KeepAliveTxMessage(60).byteSequence))
                .subscribe(
                        characteristicValue -> {
                            UserError.Log.d(TAG, "Wrote keep-alive request successfully");
                            speakSlowly(); // is this really needed here?
                            parent.unBond();
                            parent.instantCreateBondIfAllowed();
                            speakSlowly();
                            connection.writeCharacteristic(Authentication, nn(new BondRequestTxMessage().byteSequence))
                                    .subscribe(
                                            bondRequestValue -> {
                                                UserError.Log.d(TAG, "Wrote bond request value: " + JoH.bytesToHex(bondRequestValue));
                                                speakSlowly();
                                                connection.readCharacteristic(Authentication)
                                                        .observeOn(Schedulers.io())
                                                        .timeout(10, TimeUnit.SECONDS)
                                                        .subscribe(
                                                                status_value -> {
                                                                    UserError.Log.d(TAG, "Got status read after keepalive " + JoH.bytesToHex(status_value));

                                                                    UserError.Log.d(TAG, "Wrote bond request successfully");
                                                                    parent.waitingBondConfirmation = 1; // waiting

                                                                    parent.instantCreateBondIfAllowed();
                                                                    UserError.Log.d(TAG, "Sleeping for bond");
                                                                    for (int i = 0; i < 9; i++) {
                                                                        if (parent.waitingBondConfirmation == 2) {
                                                                            UserError.Log.d(TAG, "Bond confirmation received - continuing!");
                                                                            break;
                                                                        }
                                                                        threadSleep(1000);
                                                                    }
                                                                    parent.changeState(Ob1G5CollectionService.STATE.BOND);
                                                                    throw new OperationSuccess("Bond requested");

//
                                                                }, throwable -> {
                                                                    UserError.Log.e(TAG, "Throwable when reading characteristic after keepalive: " + throwable);
                                                                });

                                                // Wrote bond request successfully was here moved above - is this right?
                                            }, throwable -> {
                                                // failed to write bond request retry?
                                                if (!(throwable instanceof OperationSuccess)) {
                                                    UserError.Log.e(TAG, "Failed to write bond request! " + throwable);
                                                }
                                            });

                        }, throwable -> {
                            // Could not write keep alive ? retry?
                            UserError.Log.e(TAG, "Failed writing keep-alive request! " + throwable);
                        });
        UserError.Log.d(TAG, "Exiting doKeepAliveBondRequest");
        final PowerManager.WakeLock linger = JoH.getWakeLock("jam-g5-bond-linger", 30000);
        return true;
    }

    @SuppressLint("CheckResult")
    public static boolean doReset(Ob1G5CollectionService parent, RxBleConnection connection) {
        if (connection == null) return false;
        parent.msg("Hard Resetting Transmitter");
        connection.writeCharacteristic(Control, nn(new ResetTxMessage().byteSequence))
                .subscribe(characteristicValue -> {
                    if (d)
                        UserError.Log.d(TAG, "Wrote ResetTxMessage request!!");
                    parent.msg("Hard Reset Sent");
                }, throwable -> {
                    parent.msg("Hard Reset maybe Failed");
                    UserError.Log.e(TAG, "Failed to write ResetTxMessage: " + throwable);
                    if (throwable instanceof BleGattCharacteristicException) {
                        final int status = ((BleGattCharacteristicException) throwable).getStatus();
                        UserError.Log.e(TAG, "Got status message: " + BluetoothServices.getStatusName(status));
                    }
                });
        return true;
    }

    private static void reReadGlucoseData() {
        enqueueUniqueCommand(new GlucoseTxMessage(), "Re-read glucose");
    }

    // Get Data
    @SuppressLint("CheckResult")
    public static boolean doGetData(Ob1G5CollectionService parent, RxBleConnection connection) {
        if (connection == null) return false;
        // TODO switch modes depending on conditions as to whether we are using internal
        final boolean use_g5_internal_alg = Pref.getBooleanDefaultFalse("ob1_g5_use_transmitter_alg");
        UserError.Log.d(TAG, use_g5_internal_alg ? "Requesting Glucose Data" : "Requesting Sensor Data");

        if (!use_g5_internal_alg) {
            parent.lastSensorStatus = null; // not applicable
            parent.lastUsableGlucosePacketTime = 0;
        }

        connection.setupIndication(Control)

                .doOnNext(notificationObservable -> {

                    if (d) UserError.Log.d(TAG, "Notifications enabled");
                    speakSlowly();

                    connection.writeCharacteristic(Control, nn(use_g5_internal_alg ? (usingG6() ? new EGlucoseTxMessage().byteSequence : new GlucoseTxMessage().byteSequence) : new SensorTxMessage().byteSequence))
                            .subscribe(
                                    characteristicValue -> {
                                        if (d)
                                            UserError.Log.d(TAG, "Wrote SensorTxMessage request");
                                    }, throwable -> {
                                        UserError.Log.e(TAG, "Failed to write SensorTxMessage: " + throwable);
                                        if (throwable instanceof BleGattCharacteristicException) {
                                            final int status = ((BleGattCharacteristicException) throwable).getStatus();
                                            UserError.Log.e(TAG, "Got status message: " + BluetoothServices.getStatusName(status));
                                            if (status == 8) {
                                                UserError.Log.e(TAG, "Request rejected due to Insufficient Authorization failure!");
                                                parent.authResult(false);
                                            }
                                        }
                                    });

                })
                .flatMap(notificationObservable -> notificationObservable)
                .timeout(6, TimeUnit.SECONDS)
                .subscribe(bytes -> {
                    // incoming data notifications
                    UserError.Log.d(TAG, "Received indication bytes: " + JoH.bytesToHex(bytes));
                    final PacketShop data_packet = classifyPacket(bytes);
                    switch (data_packet.type) {
                        case SensorRxMessage:

                            try {
                                if ((getVersionDetails) && (!haveFirmwareDetails())) {
                                    connection.writeCharacteristic(Control, nn(new VersionRequestTxMessage().byteSequence))
                                            .subscribe(versionValue -> {
                                                UserError.Log.d(TAG, "Wrote version request");
                                            }, throwable -> {
                                                UserError.Log.e(TAG, "Failed to write VersionRequestTxMessage: " + throwable);
                                            });
                                } else if ((getBatteryDetails) && (parent.getBatteryStatusNow || !haveCurrentBatteryStatus())) {


                                    enqueueUniqueCommand(new BatteryInfoTxMessage(), "Query battery");
                                    parent.getBatteryStatusNow = false;

                                  /*  connection.writeCharacteristic(Control, new BatteryInfoTxMessage().byteSequence)
                                            .subscribe(batteryValue -> {
                                                UserError.Log.d(TAG, "Wrote battery info request");
                                                parent.getBatteryStatusNow = false;
                                            }, throwable -> {
                                                UserError.Log.e(TAG, "Failed to write BatteryInfoRequestTxMessage: " + throwable);
                                            });*/
                                }
                            } finally {
                                processSensorRxMessage((SensorRxMessage) data_packet.msg);
                                parent.msg("Got data");
                                parent.updateLast(JoH.tsl());
                                parent.clearErrors();
                            }
                            break;

                        case VersionRequestRxMessage:
                            if (!setStoredFirmwareBytes(getTransmitterID(), bytes, true)) {
                                UserError.Log.e(TAG, "Could not save out firmware version!");
                            }
                            nextBackFillCheckSize = BACKFILL_CHECK_LARGE;
                            if (JoH.ratelimit("g6-evaluate", 600)) {
                                Inevitable.task("evaluteG6Settings", 10000, () -> evaluateG6Settings());
                            }
                            break;

                        case BatteryInfoRxMessage:
                            if (!setStoredBatteryBytes(getTransmitterID(), bytes)) {
                                UserError.Log.e(TAG, "Could not save out battery data!");
                            } else {
                                if (parent.android_wear) {
                                    PersistentStore.setBoolean(G5_BATTERY_WEARABLE_SEND, true);
                                }
                            }
                            nextBackFillCheckSize = BACKFILL_CHECK_LARGE;
                            break;

                        case SessionStartRxMessage:
                            final SessionStartRxMessage session_start = (SessionStartRxMessage) data_packet.msg;
                            if (session_start.isOkay()) {
                                // TODO persist this
                                parent.msg("Session Started Successfully: " + JoH.dateTimeText(session_start.getSessionStart()) + " " + JoH.dateTimeText(session_start.getRequestedStart()) + " " + JoH.dateTimeText(session_start.getTransmitterTime()));
                            } else {
                                final String msg = "Session Start Failed: " + session_start.message();
                                parent.msg(msg);
                                UserError.Log.ueh(TAG, msg);
                                JoH.showNotification("G5 Start Failed", msg, null, Constants.G5_START_REJECT, true, true, false);
                                UserError.Log.ueh(TAG, "Session Start failed info: " + JoH.dateTimeText(session_start.getSessionStart()) + " " + JoH.dateTimeText(session_start.getRequestedStart()) + " " + JoH.dateTimeText(session_start.getTransmitterTime()));
                                if (Pref.getBooleanDefaultFalse("ob1_g5_restart_sensor") && (Sensor.isActive())) {
                                    if (pratelimit("secondary-g5-start", 1800)) {
                                        UserError.Log.ueh(TAG, "Trying to Start sensor again");
                                        startSensor(JoH.tsl());
                                    }
                                }
                            }
                            reReadGlucoseData();

                            break;

                        case SessionStopRxMessage:
                            final SessionStopRxMessage session_stop = (SessionStopRxMessage) data_packet.msg;
                            if (session_stop.isOkay()) {
                                // TODO persist this
                                final String msg = "Session Stopped Successfully: " + JoH.dateTimeText(session_stop.getSessionStart()) + " " + JoH.dateTimeText(session_stop.getSessionStop());
                                parent.msg(msg);
                                UserError.Log.ueh(TAG, msg);
                                reReadGlucoseData();
                                enqueueUniqueCommand(new TransmitterTimeTxMessage(), "Query time after stop");
                            } else {
                                UserError.Log.e(TAG, "Session Stop Error!");
                            }
                            break;

                        case GlucoseRxMessage:
                            final GlucoseRxMessage glucose = (GlucoseRxMessage) data_packet.msg;
                            parent.processCalibrationState(glucose.calibrationState());

                            if (glucose.usable()) {
                                parent.msg("Got "+devName()+" glucose");
                            } else {
                                parent.msg("Got data from "+devName());
                            }
                            if (JoH.ratelimit("ob1-g5-also-read-raw", 20)) {
                                enqueueUniqueCommand(new SensorTxMessage(), "Also read raw");
                            }

                            if (JoH.pratelimit("g5-tx-time-since", 7200)
                                    || glucose.calibrationState().warmingUp()) {
                                if (JoH.ratelimit("g5-tx-time-governer", 30)) {
                                    enqueueUniqueCommand(new TransmitterTimeTxMessage(), "Periodic Query Time");
                                }
                            }

                            // TODO check firmware version
                            if (glucose.calibrationState().readyForBackfill() && !parent.getBatteryStatusNow) {
                                backFillIfNeeded(parent, connection);
                            }
                            processGlucoseRxMessage(parent, glucose);
                            parent.updateLast(JoH.tsl());
                            parent.clearErrors();
                            break;

                        // TODO base class duplication
                        case EGlucoseRxMessage:
                            final EGlucoseRxMessage eglucose = (EGlucoseRxMessage) data_packet.msg;
                            parent.processCalibrationState(eglucose.calibrationState());

                            if (eglucose.usable()) {
                                parent.msg("Got G6 glucose");
                            } else {
                                parent.msg("Got data from G6");
                            }
                            if (JoH.ratelimit("ob1-g5-also-read-raw", 20)) {
                                enqueueUniqueCommand(new SensorTxMessage(), "Also read raw");
                            }

                            if (JoH.pratelimit("g5-tx-time-since", 7200)
                                    || eglucose.calibrationState().warmingUp()) {
                                if (JoH.ratelimit("g5-tx-time-governer", 30)) {
                                    enqueueUniqueCommand(new TransmitterTimeTxMessage(), "Periodic Query Time");
                                }
                            }

                            // TODO check firmware version
                            if (eglucose.calibrationState().readyForBackfill() && !parent.getBatteryStatusNow) {
                                backFillIfNeeded(parent, connection);
                            }
                            processGlucoseRxMessage(parent, eglucose);
                            parent.updateLast(JoH.tsl());
                            parent.clearErrors();
                            break;


                        case CalibrateRxMessage:
                            final CalibrateRxMessage calibrate = (CalibrateRxMessage) data_packet.msg;
                            if (calibrate.accepted()) {
                                parent.msg("Calibration accepted");
                                UserError.Log.ueh(TAG, "Calibration accepted by G5 transmitter");
                            } else {
                                final String msg = "Calibration rejected: " + calibrate.message();
                                UserError.Log.wtf(TAG, msg);
                                parent.msg(msg);
                                JoH.showNotification("Calibration rejected", msg, null, Constants.G5_CALIBRATION_REJECT, true, true, false);
                            }
                            reReadGlucoseData();
                            break;

                        case BackFillRxMessage:
                            final BackFillRxMessage backfill = (BackFillRxMessage) data_packet.msg;
                            if (backfill.valid()) {
                                UserError.Log.d(TAG, "Backfill request confirmed");
                            } else {
                                UserError.Log.wtf(TAG, "Backfill request corrupted!");
                            }
                            break;

                        case TransmitterTimeRxMessage:
                            final TransmitterTimeRxMessage txtime = (TransmitterTimeRxMessage) data_packet.msg;
                            DexTimeKeeper.updateAge(getTransmitterID(), txtime.getCurrentTime());
                            if (txtime.sessionInProgress()) {
                                UserError.Log.e(TAG, "Session start time reports: "
                                        + JoH.dateTimeText(txtime.getRealSessionStartTime()) + " Duration: "
                                        + JoH.niceTimeScalar(txtime.getSessionDuration()));
                                DexSessionKeeper.setStart(txtime.getRealSessionStartTime());
                            } else {
                                UserError.Log.e(TAG, "Session start time reports: No session in progress");
                                DexSessionKeeper.clearStart();
                            }
                            if (Pref.getBooleanDefaultFalse("ob1_g5_preemptive_restart")) {
                                if (txtime.getSessionDuration() > Constants.DAY_IN_MS * (usingG6() ? 9 : 6)
                                        && txtime.getSessionDuration() < Constants.MONTH_IN_MS) {
                                    UserError.Log.uel(TAG, "Requesting preemptive session restart");
                                    restartSensorWithTimeTravel();
                                }
                            }
                            break;

                        default:
                            UserError.Log.e(TAG, "Got unknown packet rx: " + JoH.bytesToHex(bytes));
                            break;
                    }
                    if (!queued(parent, connection)) {
                        inevitableDisconnect(parent, connection);
                    }

                }, throwable -> {
                    if (!(throwable instanceof OperationSuccess)) {
                        if (throwable instanceof BleDisconnectedException) {
                            UserError.Log.d(TAG, "Disconnected when waiting to receive indication: " + throwable);
                            parent.changeState(Ob1G5CollectionService.STATE.CLOSE);
                        } else {
                            UserError.Log.e(TAG, "Error receiving indication: " + throwable);
                            throwable.printStackTrace();
                            disconnectNow(parent, connection);
                        }

                    }
                });


        return true;
    }

    private static void inevitableDisconnect(Ob1G5CollectionService parent, RxBleConnection connection) {
        inevitableDisconnect(parent, connection, speakSlowlyDelay());
    }

    private static void inevitableDisconnect(Ob1G5CollectionService parent, RxBleConnection connection, long guardTime) {
        Inevitable.task("Ob1G5 disconnect", 500 + guardTime + speakSlowlyDelay(), () -> disconnectNow(parent, connection));
    }

    @SuppressLint("CheckResult")
    private static void disconnectNow(Ob1G5CollectionService parent, RxBleConnection connection) {
        // tell device to disconnect now
        UserError.Log.d(TAG, "Disconnect NOW: " + JoH.dateTimeText(JoH.tsl()));
        speakSlowly();
        connection.writeCharacteristic(Control, nn(new DisconnectTxMessage().byteSequence))
                .timeout(2, TimeUnit.SECONDS)
                //  .observeOn(Schedulers.newThread())
                //  .subscribeOn(Schedulers.newThread())
                .subscribe(disconnectValue -> {
                    if (d) UserError.Log.d(TAG, "Wrote disconnect request");
                    parent.changeState(Ob1G5CollectionService.STATE.CLOSE);
                    throw new OperationSuccess("Requested Disconnect");
                }, throwable -> {
                    if (!(throwable instanceof OperationSuccess)) {
                        UserError.Log.d(TAG, "Disconnect NOW failure: " + JoH.dateTimeText(JoH.tsl()));
                        if (throwable instanceof BleDisconnectedException) {
                            UserError.Log.d(TAG, "Failed to write DisconnectTxMessage as already disconnected: " + throwable);

                        } else {
                            UserError.Log.e(TAG, "Failed to write DisconnectTxMessage: " + throwable);

                        }
                        parent.changeState(Ob1G5CollectionService.STATE.CLOSE);
                    }
                });
        UserError.Log.d(TAG, "Disconnect NOW exit: " + JoH.dateTimeText(JoH.tsl()));
    }

    private static void backFillIfNeeded(Ob1G5CollectionService parent, RxBleConnection connection) {
        final int check_readings = nextBackFillCheckSize;
        UserError.Log.d(TAG, "Checking " + check_readings + " for backfill requirement");
        final List<BgReading> lastReadings = BgReading.latest_by_size(check_readings);
        boolean ask_for_backfill = false;
        long earliest_timestamp = JoH.tsl() - MAX_BACKFILL_PERIOD_MS;
        long latest_timestamp = JoH.tsl();
        if ((lastReadings == null) || (lastReadings.size() != check_readings)) {
            ask_for_backfill = true;
        } else {
            for (int i = 0; i < lastReadings.size(); i++) {
                final BgReading reading = lastReadings.get(i);
                if ((reading == null) || (msSince(reading.timestamp) > ((DEXCOM_PERIOD * i) + Constants.MINUTE_IN_MS * 7))) {
                    ask_for_backfill = true;
                    if ((reading != null) && (msSince(reading.timestamp) <= MAX_BACKFILL_PERIOD_MS)) {
                        earliest_timestamp = reading.timestamp;
                    }
                    UserError.Log.d(TAG, "Flagging backfill tripped by reading: " + i + " at time: " + JoH.dateTimeText(reading.timestamp) + " creating backfill window: " + JoH.dateTimeText(earliest_timestamp));
                    break;
                } else {
                    // good record
                    latest_timestamp = reading.timestamp;
                }
            }
        }

        if (ask_for_backfill) {
            nextBackFillCheckSize = BACKFILL_CHECK_LARGE;
            monitorBackFill(parent, connection);
            final long startTime = earliest_timestamp - (Constants.MINUTE_IN_MS * 5);
            final long endTime = latest_timestamp + (Constants.MINUTE_IN_MS * 5);
            UserError.Log.d(TAG, "Requesting backfill between: " + JoH.dateTimeText(startTime) + " " + JoH.dateTimeText(endTime));
            enqueueUniqueCommand(
                    BackFillTxMessage.get(getTransmitterID(), startTime, endTime),
                    "Get backfill since: " + JoH.hourMinuteString(startTime));
        } else {
            nextBackFillCheckSize = BACKFILL_CHECK_SMALL;
        }
    }

    private static void enqueueCommand(TransmitterMessage tm, String msg) {
        if (tm != null) {
            final Ob1Work item = new Ob1Work(tm, msg);
            synchronized (commandQueue) {
                commandQueue.add(item);
            }
            streamCheck(item);
            backupCheck(item);
        }
    }

    private static void streamCheck(Ob1Work item) {
        if (item.streamable()) {
            Inevitable.task("check wear stream", 5000, WatchUpdaterService::checkOb1Queue);
        }
    }

    private static void backupCheck(Ob1Work item) {
        if (item.streamable()) {
            saveQueue();
        }
    }

    private static void enqueueUniqueCommand(TransmitterMessage tm, String msg) {
        if (tm != null) {
            final Class searchClass = tm.getClass();
            Ob1Work item;
            synchronized (commandQueue) {
                if (searchQueue(searchClass)) {
                    UserError.Log.d(TAG, "Not adding duplicate: " + searchClass.getSimpleName());
                    return;
                }
                item = new Ob1Work(tm, msg);
                if (d) {
                    UserError.Log.d(TAG, "Adding to queue packet: " + msg + " " + HexDump.dumpHexString(tm.byteSequence));
                }
                commandQueue.add(item);
                streamCheck(item);
            }
            backupCheck(item);
        }
    }

    private static boolean queueContains(TransmitterMessage tm) {
        final Class searchClass = tm.getClass();
        return queueContains(searchClass);
    }

    private static boolean queueContains(Class searchClass) {
        synchronized (commandQueue) {
            return searchQueue(searchClass);
        }
    }

    // note not synchronized here
    private static boolean searchQueue(Class searchClass) {
        for (Ob1Work item : commandQueue) {
            if (item.msg.getClass() == searchClass) {
                return true;
            }
        }
        return false;
    }

    public static void restoreQueue() {
        if (!backup_loaded) {
            loadQueue();
        }
    }

    private synchronized static void loadQueue() {
        if (commandQueue.size() == 0) {
            injectQueueJson(PersistentStore.getString(PREF_SAVED_QUEUE));
            UserError.Log.d(TAG, "Loaded queue stream backup.");
        }
        backup_loaded = true;
    }


    private static void saveQueue() {
        final String queue_json = extractQueueJson();
        if (!(queue_json == null ? "" : queue_json).equals(PersistentStore.getString(PREF_SAVED_QUEUE))) {
            PersistentStore.setString(PREF_SAVED_QUEUE, queue_json);
            UserError.Log.d(TAG, "Saved queue stream backup: " + queue_json);
        }
    }

    public static String extractQueueJson() {
        synchronized (commandQueue) {
            final List<Ob1Work> queue = new ArrayList<>(commandQueue.size());
            for (Ob1Work item : commandQueue) {
                if (item.streamable()) queue.add(item);
            }
            return JoH.defaultGsonInstance().toJson(queue);
        }
    }

    // used in backup restore and wear
    @SuppressWarnings("WeakerAccess")
    public static void injectQueueJson(String json) {
        if (json == null || json.length() == 0) return;
        final Type queueType = new TypeToken<ArrayList<Ob1Work>>() {
        }.getType();
        final List<Ob1Work> queue = JoH.defaultGsonInstance().fromJson(json, queueType);
        synchronized (commandQueue) {
            commandQueue.clear();
            commandQueue.addAll(queue);
        }
        UserError.Log.d(TAG, "Replaced queue with stream: " + json);
    }

    public static String extractDexTime() {
        return DexTimeKeeper.extractForStream(getTransmitterID());
    }

    @SuppressWarnings("unused")
    public static void injectDexTime(String stream) {
        DexTimeKeeper.injectFromStream(stream);
    }


    public static boolean pendingStop() {
        return queueContains(SessionStopTxMessage.class);
    }

    public static boolean pendingStart() {
        return queueContains(SessionStartTxMessage.class);
    }

    public static boolean pendingCalibration() {
        return queueContains(CalibrateTxMessage.class);
    }

    public static int queueSize() {
        return commandQueue.size();
    }

    public static void emptyQueue() {
        synchronized (commandQueue) {
            if (commandQueue.size() > 0) {
                UserError.Log.d(TAG, "Queue drained on wear, clearing: " + commandQueue.size() + " commands");
                commandQueue.clear();
                Inevitable.task("Save cleared G5 queue", 1000, Ob1G5StateMachine::saveQueue);
            } else {
                if (d) UserError.Log.d(TAG, "Local command queue is already empty");
            }
        }
    }

    public static String getFirstQueueItemName() {
        synchronized (commandQueue) {
            final Ob1Work item = commandQueue.peek();
            return item != null ? item.text : "";
        }
    }

    private static boolean acceptCommands() {
        return DexCollectionType.hasDexcomRaw() && Pref.getBooleanDefaultFalse("ob1_g5_use_transmitter_alg");
    }

    // actual limit is something like 20-30 mins but due to propagation delays its too risky to adjust
    private static final long MAX_START_TIME_REWIND = Constants.MINUTE_IN_MS * 5;

    public static void startSensor(long when) {
        if (acceptCommands()) {
            if (msSince(when) > MAX_START_TIME_REWIND) {
                when = JoH.tsl() - MAX_START_TIME_REWIND;
                UserError.Log.e(TAG, "Cannot rewind sensor start time beyond: " + JoH.dateTimeText(when));
            }
            if (usingG6()) {
                final String code = G6CalibrationParameters.getCurrentSensorCode();
                if (code == null) {
                    UserError.Log.wtf(TAG, "Cannot start G6 sensor as calibration code not set!");
                } else {
                    UserError.Log.ueh(TAG, "Starting G6 sensor using calibration code: " + code);
                    enqueueUniqueCommand(new SessionStartTxMessage(when,
                                    DexTimeKeeper.getDexTime(getTransmitterID(), when), code),
                            "Start G6 Sensor");
                }

            } else {
                UserError.Log.ueh(TAG, "Starting G5 sensor");
                enqueueUniqueCommand(new SessionStartTxMessage(when,
                                DexTimeKeeper.getDexTime(getTransmitterID(), when)),
                        "Start G5 Sensor");
            }
        }
    }

    private static void reprocessTxMessage(TransmitterMessage tm) {
        // rewrite session start messages in case our clock was wrong
        if (tm instanceof SessionStartTxMessage) {
            final SessionStartTxMessage ssm = (SessionStartTxMessage) tm;
            if (usingG6()) {
                final String code = G6CalibrationParameters.getCurrentSensorCode();
                if (code == null) {
                    UserError.Log.wtf(TAG, "Cannot reprocess start G6 sensor as calibration code not set!");
                } else {
                    // g6
                    tm.byteSequence = new SessionStartTxMessage(ssm.getStartTime(), DexTimeKeeper.getDexTime(getTransmitterID(), ssm.getStartTime()), code).byteSequence;
                }
            } else {
                // g5
                tm.byteSequence = new SessionStartTxMessage(ssm.getStartTime(), DexTimeKeeper.getDexTime(getTransmitterID(), ssm.getStartTime())).byteSequence;
            }
            UserError.Log.d(TAG, "New session start: " + ssm.getDexTime() + " for time: " + JoH.dateTimeText(ssm.getStartTime()));
            if (d) {
                UserError.Log.d(TAG, "New packet: " + HexDump.dumpHexString(tm.byteSequence));
            }
        }
    }


    public static void stopSensor() {
        if (acceptCommands()) {
            enqueueCommand(
                    new SessionStopTxMessage(
                            DexTimeKeeper.getDexTime(getTransmitterID(), JoH.tsl())),
                    "Stop Sensor");
        }
    }


    public static void restartSensorWithTimeTravel() {
        restartSensorWithTimeTravel(JoH.tsl() - HOUR_IN_MS * 2 - MINUTE_IN_MS * 10);
    }

    public static void restartSensorWithTimeTravel(long when) {
        if (acceptCommands()) {
            enqueueUniqueCommand(
                    new SessionStopTxMessage(
                            DexTimeKeeper.getDexTime(getTransmitterID(), when)),
                    "Auto Stop Sensor");
            final long when_started = when + SECOND_IN_MS;
            enqueueUniqueCommand(new SessionStartTxMessage(when,
                            DexTimeKeeper.getDexTime(getTransmitterID(), when_started)),
                    "Auto Start Sensor");
        }
    }

    public static void addCalibration(int glucose, long timestamp) {
        if (acceptCommands()) {
            long since = msSince(timestamp);
            if (since < 0) {
                final String msg = "Cannot send calibration in future to transmitter: " + glucose + " @ " + JoH.dateTimeText(timestamp);
                JoH.static_toast_long(msg);
                UserError.Log.wtf(TAG, msg);
                return;
            }
            if (since > HOUR_IN_MS) {
                final String msg = "Cannot send calibration older than 1 hour to transmitter: " + glucose + " @ " + JoH.dateTimeText(timestamp);
                JoH.static_toast_long(msg);
                UserError.Log.wtf(TAG, msg);
                return;
            }
            if ((glucose < 40 || glucose > 400)) {
                final String msg = "Calibration glucose value out of range: " + glucose;
                JoH.static_toast_long(msg);
                UserError.Log.wtf(TAG, msg);
                return;
            }


            enqueueCommand(new CalibrateTxMessage(
                            glucose, DexTimeKeeper.getDexTime(getTransmitterID(), timestamp)),
                    "Calibrate " + BgGraphBuilder.unitized_string_with_units_static_short(glucose));
        }
    }

    private static boolean queued(Ob1G5CollectionService parent, RxBleConnection connection) {
        if (!commandQueue.isEmpty()) {
            processQueueCommand(parent, connection);
            return true;
        }
        return false;
    }

    @SuppressLint("CheckResult")
    private static void processQueueCommand(Ob1G5CollectionService parent, RxBleConnection connection) {
        boolean changed = false;
        synchronized (commandQueue) {
            if (!commandQueue.isEmpty()) {
                final Ob1Work unit = commandQueue.poll();
                if (unit != null) {
                    changed = true;
                    reprocessTxMessage(unit.msg);
                    if (unit.retry < 5 && JoH.msSince(unit.timestamp) < HOUR_IN_MS * 8) {
                        connection.writeCharacteristic(Control, nn(unit.msg.byteSequence))
                                .timeout(2, TimeUnit.SECONDS)
                                .subscribe(value -> {
                                    UserError.Log.d(TAG, "Wrote Queue Message: " + unit.text);
                                    final long guardTime = unit.msg.guardTime();
                                    inevitableDisconnect(parent, connection, guardTime);
                                    if (guardTime > 0) {
                                        UserError.Log.d(TAG, "Sleeping post execute: " + unit.text + " " + guardTime + "ms");
                                        JoH.threadSleep(guardTime);
                                    }
                                    throw new OperationSuccess("Completed: " + unit.text);

                                }, throwable -> {
                                    if (!(throwable instanceof OperationSuccess)) {
                                        unit.retry++;
                                        UserError.Log.d(TAG, "Re-adding: " + unit.text);
                                        synchronized (commandQueue) {
                                            commandQueue.push(unit);
                                        }
                                        UserError.Log.d(TAG, "Failure: " + unit.text + " " + JoH.dateTimeText(JoH.tsl()));
                                        if (throwable instanceof BleDisconnectedException) {
                                            UserError.Log.d(TAG, "Disconnected: " + unit.text + " " + throwable);
                                            parent.changeState(Ob1G5CollectionService.STATE.CLOSE);
                                        } else {
                                            UserError.Log.e(TAG, "Failed to write: " + unit.text + " " + throwable);
                                        }
                                        parent.changeState(Ob1G5CollectionService.STATE.CLOSE);
                                    } else {
                                        queued(parent, connection); // turtles all the way down
                                    }
                                });
                    } else {
                        UserError.Log.e(TAG, "Ejected command from queue due to being too old: " + unit.text + " " + JoH.dateTimeText(unit.timestamp));
                    }
                }
                if (commandQueue.isEmpty()) {
                    if (d) UserError.Log.d(TAG, "Command Queue Drained");
                    if (android_wear) {
                        PersistentStore.setBoolean(PREF_QUEUE_DRAINED, true);
                    }
                }
            } else {
                UserError.Log.d(TAG, "Command Queue is Empty");
            }
        }
        if (changed) saveQueue();
    }

    private static void processGlucoseRxMessage(Ob1G5CollectionService parent, final BaseGlucoseRxMessage glucose) {
        if (glucose == null) return;
        lastGlucosePacket = JoH.tsl();
        DexTimeKeeper.updateAge(getTransmitterID(), glucose.timestamp);
        if (glucose.usable() || (glucose.insufficient() && Pref.getBooleanDefaultFalse("ob1_g5_use_insufficiently_calibrated"))) {
            UserError.Log.d(TAG, "Got usable glucose data from G5!!");
            final BgReading bgReading = BgReading.bgReadingInsertFromG5(glucose.glucose, JoH.tsl());
            if (bgReading != null) {
                try {
                    bgReading.calculated_value_slope = glucose.getTrend() / Constants.MINUTE_IN_MS; // note this is different to the typical calculated slope, (normally delta)
                } catch (Exception e) {
                    // not a good number
                }
                if (glucose.insufficient()) {
                    bgReading.appendSourceInfo("Insufficient").save();
                }
            } else {
                UserError.Log.wtf(TAG, "New BgReading was null in processGlucoseRxMessage!");
            }
            lastGlucoseBgReading = bgReading;
            lastUsableGlucosePacket = lastGlucosePacket;
            parent.lastUsableGlucosePacketTime = lastUsableGlucosePacket;
            if (glucose.getPredictedGlucose() != null) {
                // not really supported on wear yet
                if (!android_wear) {
                    Prediction.create(JoH.tsl(), glucose.getPredictedGlucose(), "EGlucoseRx").save();
                }
            }
        } else {
            // TODO this is duplicated in processCalibrationState()
            if (glucose.calibrationState().sensorFailed()) {
                if (JoH.pratelimit("G5 Sensor Failed", 3600 * 3)) {
                    JoH.showNotification("G5 SENSOR FAILED", "Sensor reporting failed", null, Constants.G5_SENSOR_ERROR, true, true, false);
                }
            }
        }
    }


    private static void processSensorRxMessage(SensorRxMessage sensorRx) {
        if (sensorRx == null) return;

        // TODO, is this accurate or needed?
        int sensor_battery_level = 0;
        if (sensorRx.status == TransmitterStatus.BRICKED) {
            sensor_battery_level = 206; //will give message "EMPTY"
        } else if (sensorRx.status == TransmitterStatus.LOW) {
            sensor_battery_level = 209; //will give message "LOW"
        } else {
            sensor_battery_level = 216; //no message, just system status "OK"
        }

        UserError.Log.d(TAG, "SUCCESS!! unfiltered: " + sensorRx.unfiltered + " filtered: " + sensorRx.filtered + " timestamp: " + sensorRx.timestamp + " " + JoH.qs((double) sensorRx.timestamp / 86400, 1) + " days");
        DexTimeKeeper.updateAge(getTransmitterID(), sensorRx.timestamp);
        if (sensorRx.unfiltered == 0) {
            UserError.Log.e(TAG, "Transmitter sent raw sensor value of 0 !! This isn't good. " + JoH.hourMinuteString());
        } else {
            final boolean g6 = usingG6();
            processNewTransmitterData(g6 ? sensorRx.unfiltered * G6_SCALING : sensorRx.unfiltered, g6 ? sensorRx.filtered * G6_SCALING : sensorRx.filtered, sensor_battery_level, new Date().getTime());
        }
    }

    // Save/process the data in xDrip style
    private static synchronized void processNewTransmitterData(int raw_data, int filtered_data, int sensor_battery_level, long captureTime) {

        final TransmitterData transmitterData = TransmitterData.create(raw_data, filtered_data, sensor_battery_level, captureTime);
        if (transmitterData == null) {
            UserError.Log.e(TAG, "TransmitterData.create failed: Duplicate packet");
            return;
        } else {
            UserError.Log.d(TAG, "Created transmitter data " + transmitterData.uuid + " " + JoH.dateTimeText(transmitterData.timestamp));
            // TODO timeInMillisecondsOfLastSuccessfulSensorRead = captureTime;
        }
        final Sensor sensor = Sensor.currentSensor();
        if (sensor == null) {
            UserError.Log.e(TAG, "setSerialDataToTransmitterRawData: No Active Sensor, Data only stored in Transmitter Data");
            return;
        }

        //TODO : LOG if unfiltered or filtered values are zero

        Sensor.updateBatteryLevel(sensor, transmitterData.sensor_battery_level);
        if (d)
            UserError.Log.i(TAG, "timestamp create: " + Long.toString(transmitterData.timestamp));

        if ((lastGlucoseBgReading != null) && (msSince(lastUsableGlucosePacket) < Constants.SECOND_IN_MS * 30)) {
            UserError.Log.d(TAG, "Updating BgReading provided by transmitter");
            // use sensor data to update previous record instead of trying to calculate with it
            lastGlucoseBgReading.raw_data = transmitterData.raw_data / 1000;
            lastGlucoseBgReading.filtered_data = transmitterData.filtered_data / 1000;
            // TODO calculate filtered calculated value from internal alg??
            lastGlucoseBgReading.calculateAgeAdjustedRawValue();
            lastGlucoseBgReading.save();
        } else {
            if (!Ob1G5CollectionService.usingNativeMode() || Ob1G5CollectionService.fallbackToXdripAlgorithm() || BgReading.latest(3).size() < 3) {
                final BgReading bgreading = BgReading.create(transmitterData.raw_data, transmitterData.filtered_data, xdrip.getAppContext(), transmitterData.timestamp);
                UserError.Log.d(TAG, "BgReading created: " + bgreading.uuid + " " + JoH.dateTimeText(bgreading.timestamp));
            }
        }

        //   UserError.Log.d(TAG, "Dex raw_data " + Double.toString(transmitterData.raw_data));//KS
        //   UserError.Log.d(TAG, "Dex filtered_data " + Double.toString(transmitterData.filtered_data));//KS
        //   UserError.Log.d(TAG, "Dex sensor_battery_level " + Double.toString(transmitterData.sensor_battery_level));//KS
        //   UserError.Log.d(TAG, "Dex timestamp " + JoH.dateTimeText(transmitterData.timestamp));//KS


        // TODO static_last_timestamp =  transmitterData.timestamp;

    }

    public static void evaluateG6Settings() {
        if (haveFirmwareDetails()) {
            if (FirmwareCapability.isTransmitterG6(getTransmitterID())) {
                if (!usingG6()) {
                    Ob1G5CollectionService.setG6Defaults();
                    JoH.showNotification("Enabled G6", "G6 Features and default settings automatically enabled", null, Constants.G6_DEFAULTS_MESSAGE, false, true, false);
                } else if (!onlyUsingNativeMode() && !Home.get_engineering_mode()) {
                    Ob1G5CollectionService.setG6Defaults();
                    JoH.showNotification("Enabled G6", "G6 Native mode enabled", null, Constants.G6_DEFAULTS_MESSAGE, false, true, false);
                }
            }
        }
    }

    private static boolean haveFirmwareDetails() {
        return getTransmitterID().length() == 6 && getStoredFirmwareBytes(getTransmitterID()).length >= 10;
    }


    private static boolean haveCurrentBatteryStatus() {
        return getTransmitterID().length() == 6 && (msSince(PersistentStore.getLong(G5_BATTERY_FROM_MARKER + getTransmitterID())) < BATTERY_READ_PERIOD_MS);
    }

    private static byte[] getStoredFirmwareBytes(String transmitterId) {
        if (transmitterId.length() != 6) return new byte[0];
        return PersistentStore.getBytes(G5_FIRMWARE_MARKER + transmitterId);
    }

    // from wear sync
    public static boolean setStoredFirmwareBytes(String transmitterId, byte[] data) {
        return setStoredFirmwareBytes(transmitterId, data, false);
    }

    public static boolean setStoredFirmwareBytes(String transmitterId, byte[] data, boolean from_bluetooth) {
        if (from_bluetooth) UserError.Log.e(TAG, "Store: VersionRX dbg: " + JoH.bytesToHex(data));
        if (transmitterId.length() != 6) return false;
        if (data.length < 10) return false;
        if (JoH.ratelimit("store-firmware-bytes", 60)) {
            PersistentStore.setBytes(G5_FIRMWARE_MARKER + transmitterId, data);
        }
        return true;
    }


    public synchronized static boolean setStoredBatteryBytes(String transmitterId, byte[] data) {
        UserError.Log.e(TAG, "Store: BatteryRX dbg: " + JoH.bytesToHex(data));
        if (transmitterId.length() != 6) return false;
        if (data.length < 10) return false;
        final BatteryInfoRxMessage batteryInfoRxMessage = new BatteryInfoRxMessage(data);
        UserError.Log.e(TAG, "Saving battery data: " + batteryInfoRxMessage.toString());
        PersistentStore.setBytes(G5_BATTERY_MARKER + transmitterId, data);
        PersistentStore.setLong(G5_BATTERY_FROM_MARKER + transmitterId, JoH.tsl());

        // TODO logic also needs to handle battery replacements of same transmitter id
        final long old_level = PersistentStore.getLong(G5_BATTERY_LEVEL_MARKER + transmitterId);
        if ((batteryInfoRxMessage.voltagea < old_level) || (old_level == 0)) {
            if (batteryInfoRxMessage.voltagea < LOW_BATTERY_WARNING_LEVEL) {
                if (JoH.pratelimit("g5-low-battery-warning", 40000)) {
                    final boolean loud = !PowerStateReceiver.is_power_connected();
                    JoH.showNotification("G5 Battery Low", "G5 Transmitter battery has dropped to: " + batteryInfoRxMessage.voltagea + " it may fail soon",
                            null, 770, NotificationChannels.LOW_TRANSMITTER_BATTERY_CHANNEL, loud, loud, null, null, null);
                }
            }
            PersistentStore.setLong(G5_BATTERY_LEVEL_MARKER + transmitterId, batteryInfoRxMessage.voltagea);
        }
        return true;
    }

    public static BatteryInfoRxMessage getBatteryDetails(String tx_id) {
        try {
            return new BatteryInfoRxMessage(PersistentStore.getBytes(G5_BATTERY_MARKER + tx_id));
        } catch (Exception e) {
            if (JoH.quietratelimit("bi-exception", 15))
                UserError.Log.e(TAG, "Exception in getBatteryDetails: " + e);
            return null;
        }
    }

    public static VersionRequestRxMessage getFirmwareDetails(String tx_id) {
        if (tx_id == null) {
            if (JoH.quietratelimit("txid-null", 15))
                UserError.Log.e(TAG, "TX ID is null in getFirmwareDetails");
            return null;
        }
        try {
            byte[] stored = getStoredFirmwareBytes(tx_id);
            if ((stored != null) && (stored.length > 9)) {
                return new VersionRequestRxMessage(stored);
            }
        } catch (Exception e) {
            if (JoH.quietratelimit("fi-exception", 15))
                UserError.Log.e(TAG, "Exception in getFirmwareDetails: " + e);
            return null;
        }
        return null;
    }

    public static String getRawFirmwareVersionString(String tx_id) {
        final VersionRequestRxMessage vr = getFirmwareDetails(tx_id);
        if (vr != null) {
            return vr.firmware_version_string;
        } else {
            return "";
        }
    }

    private static void updateStreamedTillTimeForBackfill() {
        // interact with ListenerService
        if (JoH.areWeRunningOnAndroidWear()) {
            final String pref_last_send_previous = "last_send_previous";
            final long last_send_previous = PersistentStore.getLong(pref_last_send_previous);
            PersistentStore.setLong(pref_last_send_previous, Math.min(last_send_previous, JoH.tsl() - MAX_BACKFILL_PERIOD_MS));
        }
    }

    private static void processBacksies(List<BackFillStream.Backsie> backsies) {
        boolean changed = false;
        for (BackFillStream.Backsie backsie : backsies) {
            final long time = DexTimeKeeper.fromDexTime(getTransmitterID(), backsie.getDextime());

            final long since = JoH.msSince(time);
            if ((since > HOUR_IN_MS * 6) || (since < 0)) {
                UserError.Log.wtf(TAG, "Backfill timestamp unrealistic: " + JoH.dateTimeText(time) + " (ignored)");
            } else {
                if (BgReading.getForPreciseTimestamp(time, Constants.MINUTE_IN_MS * 4) == null) {
                    final BgReading bgr = BgReading.bgReadingInsertFromG5(backsie.getGlucose(), time, "Backfill");
                    lastGlucoseBgReading = bgr;
                    UserError.Log.d(TAG, "Adding backfilled reading: " + JoH.dateTimeText(time) + " " + BgGraphBuilder.unitized_string_static(backsie.getGlucose()));
                    changed = true;
                }
                UserError.Log.d(TAG, "Backsie: " + JoH.dateTimeText(time) + " " + BgGraphBuilder.unitized_string_static(backsie.getGlucose()));
            }
        }
        if (changed) {
            updateStreamedTillTimeForBackfill();
        }
    }

    private static void monitorBackFill(Ob1G5CollectionService parent, RxBleConnection connection) {
        if (d) UserError.Log.d(TAG, "monitor backfill enter");

        final BackFillStream backfill = new BackFillStream();

        connection.setupNotification(ProbablyBackfill)
                .timeout(15, TimeUnit.SECONDS) // WARN
                .observeOn(Schedulers.newThread())
                .flatMap(notificationObservable -> notificationObservable)
                .subscribe(bytes -> {
                            UserError.Log.d(TAG, "Received backfill notification bytes: " + JoH.bytesToHex(bytes));
                            backfill.push(bytes);
                            inevitableDisconnect(parent, connection);
                            Inevitable.task("Process G5 backfill", 3000, () -> processBacksies(backfill.decode()));
                        }, throwable -> {
                            UserError.Log.d(TAG, "backfill throwable: " + throwable);
                        }
                );
        if (d) UserError.Log.d(TAG, "monitor backfill exit");
    }

    private static synchronized byte[] calculateHash(byte[] data) {
        if (data == null || data.length != 8) {
            UserError.Log.e(TAG, "Data length should be exactly 8.");
            return null;
        }

        final byte[] key = cryptKey();
        if (key == null)
            return null;

        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.put(data);
        bb.put(data);

        final byte[] doubleData = bb.array();

        final Cipher aesCipher;
        try {
            aesCipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
            final SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
            aesCipher.init(Cipher.ENCRYPT_MODE, skeySpec);
            byte[] aesBytes = aesCipher.doFinal(doubleData, 0, doubleData.length);

            bb = ByteBuffer.allocate(8);
            bb.put(aesBytes, 0, 8);

            return bb.array();
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException | InvalidKeyException e) {
            e.printStackTrace();
        }

        return null;
    }

    private static byte[] cryptKey() {
        final String transmitterId = getTransmitterID();
        if (transmitterId.length() != 6)
            UserError.Log.e(TAG, "cryptKey: Wrong transmitter id length!: " + transmitterId.length());
        try {
            return ("00" + transmitterId + "00" + transmitterId).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    // types of packet we receive
    private enum PACKET {
        NULL,
        UNKNOWN,
        AuthChallengeRxMessage,
        AuthStatusRxMessage,
        SensorRxMessage,
        VersionRequestRxMessage,
        BatteryInfoRxMessage,
        SessionStartRxMessage,
        SessionStopRxMessage,
        GlucoseRxMessage,
        EGlucoseRxMessage,
        CalibrateRxMessage,
        BackFillRxMessage,
        TransmitterTimeRxMessage,
        InvalidRxMessage,

    }

    // unified data structure
    private static class PacketShop {
        private PACKET type;
        private TransmitterMessage msg;

        PacketShop(PACKET type, TransmitterMessage msg) {
            this.type = type;
            this.msg = msg;
        }
    }

    // work out what type of packet we received and wrap it up nicely
    private static PacketShop classifyPacket(byte[] packet) {
        if ((packet == null) || (packet.length == 0)) return new PacketShop(PACKET.NULL, null);
        switch ((int) packet[0]) {
            case AuthChallengeRxMessage.opcode:
                return new PacketShop(PACKET.AuthChallengeRxMessage, new AuthChallengeRxMessage(packet));
            case AuthStatusRxMessage.opcode:
                return new PacketShop(PACKET.AuthStatusRxMessage, new AuthStatusRxMessage(packet));
            case SensorRxMessage.opcode:
                return new PacketShop(PACKET.SensorRxMessage, new SensorRxMessage(packet));
            case VersionRequestRxMessage.opcode:
                return new PacketShop(PACKET.VersionRequestRxMessage, new VersionRequestRxMessage(packet));
            case BatteryInfoRxMessage.opcode:
                return new PacketShop(PACKET.BatteryInfoRxMessage, new BatteryInfoRxMessage(packet));
            case SessionStartRxMessage.opcode:
                return new PacketShop(PACKET.SessionStartRxMessage, new SessionStartRxMessage(packet, getTransmitterID()));
            case SessionStopRxMessage.opcode:
                return new PacketShop(PACKET.SessionStopRxMessage, new SessionStopRxMessage(packet, getTransmitterID()));
            case GlucoseRxMessage.opcode:
                return new PacketShop(PACKET.GlucoseRxMessage, new GlucoseRxMessage(packet));
            case EGlucoseRxMessage.opcode:
                return new PacketShop(PACKET.EGlucoseRxMessage, new EGlucoseRxMessage(packet));
            case CalibrateRxMessage.opcode:
                return new PacketShop(PACKET.CalibrateRxMessage, new CalibrateRxMessage(packet));
            case BackFillRxMessage.opcode:
                return new PacketShop(PACKET.BackFillRxMessage, new BackFillRxMessage(packet));
            case TransmitterTimeRxMessage.opcode:
                return new PacketShop(PACKET.TransmitterTimeRxMessage, new TransmitterTimeRxMessage(packet));
            case InvalidRxMessage.opcode:
                return new PacketShop(PACKET.InvalidRxMessage, new InvalidRxMessage(packet));
        }
        return new PacketShop(PACKET.UNKNOWN, null);
    }

    private static int getTokenSize() {
        return 8;
    }

    public static boolean usingG6() {
        return Pref.getBooleanDefaultFalse("using_g6");
    }

    private static class OperationSuccess extends RuntimeException {
        private OperationSuccess(String message) {
            super(message);
            UserError.Log.d(TAG, "Operation Success: " + message);
        }
    }

    private static byte[] nn(final byte[] array) {
        if (array == null) {
            if (JoH.ratelimit("never-null", 60)) {
                UserError.Log.wtf("NeverNullOb1", "Attempt to pass null!!! " + JoH.backTrace());
                return new byte[1];
            }
        }
        return array;
    }

    private static String devName() {
        return usingG6() ? "G6" : "G5";
    }
}
