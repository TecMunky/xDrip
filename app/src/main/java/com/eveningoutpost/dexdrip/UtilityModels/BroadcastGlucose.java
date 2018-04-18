package com.eveningoutpost.dexdrip.UtilityModels;

import android.content.Intent;
import android.os.Bundle;

import com.eveningoutpost.dexdrip.BestGlucose;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.Calibration;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Noise;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;
import com.eveningoutpost.dexdrip.utils.PowerStateReceiver;

import static com.eveningoutpost.dexdrip.xdrip.getAppContext;

// created by jamorham

public class BroadcastGlucose {


    public static void sendLocalBroadcast(final BgReading bgReading) {

        // TODO extract to separate class/method and put in to new data observer
        BestGlucose.DisplayGlucose dg = null;
        if (Pref.getBoolean("broadcast_data_through_intents", false)) {
            UserError.Log.i("SENSOR QUEUE:", "Broadcast data");
            final Bundle bundle = new Bundle();

            bundle.putString(Intents.XDRIP_DATA_SOURCE_DESCRIPTION, DexCollectionType.getBestCollectorHardwareName());

            // TODO this cannot handle out of sequence data due to displayGlucose taking most recent?!
            // TODO can we do something with munging for quick data and getDisplayGlucose for non quick?
            // use display glucose if enabled and available

            final int noiseBlockLevel = Noise.getNoiseBlockLevel();
            bundle.putInt(Intents.EXTRA_NOISE_BLOCK_LEVEL, noiseBlockLevel);
            bundle.putString(Intents.EXTRA_NS_NOISE_LEVEL, bgReading.noise);
            if ((Pref.getBoolean("broadcast_data_use_best_glucose", false)) && ((dg = BestGlucose.getDisplayGlucose()) != null)) {
                bundle.putDouble(Intents.EXTRA_NOISE, dg.noise);
                bundle.putInt(Intents.EXTRA_NOISE_WARNING, dg.warning);

                if (dg.noise <= noiseBlockLevel) {
                    bundle.putDouble(Intents.EXTRA_BG_ESTIMATE, dg.mgdl);
                    bundle.putDouble(Intents.EXTRA_BG_SLOPE, dg.slope);

                    // hide slope possibly needs to be handled properly
                    if (bgReading.hide_slope) {
                        bundle.putString(Intents.EXTRA_BG_SLOPE_NAME, "9"); // not sure if this is right has been this way for a long time
                    } else {
                        bundle.putString(Intents.EXTRA_BG_SLOPE_NAME, dg.delta_name);
                    }
                } else {
                    final String msg = "Not locally broadcasting due to noise block level of: " + noiseBlockLevel + " and noise of; " + JoH.roundDouble(dg.noise, 1);
                    UserError.Log.e("LocalBroadcast", msg);
                    JoH.static_toast_long(msg);
                }
            } else {

                // better to use the display glucose version above
                bundle.putDouble(Intents.EXTRA_NOISE, BgGraphBuilder.last_noise);
                if (BgGraphBuilder.last_noise <= noiseBlockLevel) {
                    // standard xdrip-classic data set
                    bundle.putDouble(Intents.EXTRA_BG_ESTIMATE, bgReading.calculated_value);


                    //TODO: change back to bgReading.calculated_value_slope if it will also get calculated for Share data
                    // bundle.putDouble(Intents.EXTRA_BG_SLOPE, bgReading.calculated_value_slope);
                    bundle.putDouble(Intents.EXTRA_BG_SLOPE, BgReading.currentSlope());
                    if (bgReading.hide_slope) {
                        bundle.putString(Intents.EXTRA_BG_SLOPE_NAME, "9"); // not sure if this is right but has been this way for a long time
                    } else {
                        bundle.putString(Intents.EXTRA_BG_SLOPE_NAME, bgReading.slopeName());
                    }
                } else {
                    final String msg = "Not locally broadcasting due to noise block level of: " + noiseBlockLevel + " and noise of; " + JoH.roundDouble(BgGraphBuilder.last_noise, 1);
                    UserError.Log.e("LocalBroadcast", msg);
                    JoH.static_toast_long(msg);
                }
            }

            bundle.putInt(Intents.EXTRA_SENSOR_BATTERY, PowerStateReceiver.getBatteryLevel());
            bundle.putLong(Intents.EXTRA_TIMESTAMP, bgReading.timestamp);

            //raw value
            double slope = 0, intercept = 0, scale = 0, filtered = 0, unfiltered = 0, raw = 0;
            final Calibration cal = Calibration.lastValid();
            if (cal != null) {
                // slope/intercept/scale like uploaded to NightScout (NightScoutUploader.java)
                if (cal.check_in) {
                    slope = cal.first_slope;
                    intercept = cal.first_intercept;
                    scale = cal.first_scale;
                } else {
                    slope = 1000 / cal.slope;
                    intercept = (cal.intercept * -1000) / (cal.slope);
                    scale = 1;
                }
                unfiltered = bgReading.usedRaw() * 1000;
                filtered = bgReading.ageAdjustedFiltered() * 1000;
            }
            //raw logic from https://github.com/nightscout/cgm-remote-monitor/blob/master/lib/plugins/rawbg.js#L59
            if (slope != 0 && intercept != 0 && scale != 0) {
                if (filtered == 0 || bgReading.calculated_value < 40) {
                    raw = scale * (unfiltered - intercept) / slope;
                } else {
                    double ratio = scale * (filtered - intercept) / slope / bgReading.calculated_value;
                    raw = scale * (unfiltered - intercept) / slope / ratio;
                }
            }
            bundle.putDouble(Intents.EXTRA_RAW, raw);
            final Intent intent = new Intent(Intents.ACTION_NEW_BG_ESTIMATE);
            intent.putExtras(bundle);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

            if (Pref.getBooleanDefaultFalse("broadcast_data_through_intents_without_permission")) {
                getAppContext().sendBroadcast(intent);
            } else {
                getAppContext().sendBroadcast(intent, Intents.RECEIVER_PERMISSION);
            }
        }
    }
}
