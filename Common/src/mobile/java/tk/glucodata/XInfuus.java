/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:32:11 CET 2023                                                 */


package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class XInfuus {
private static final String LOG_ID="XInfuus";
private static final long SENSOR_ACTIVATE_SUPPRESS_MS=60L*60_000L;
private static String[] librenames;
private static String lastSensorActivateSerial=null;
private static long lastSensorActivateStart=0L;
private static long lastSensorActivateSentAt=0L;
    private static void sendIntent(Context context,Intent intent) {
	intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
	for(var name:librenames) {
		intent.setPackage(name);
		context.sendBroadcast(intent);
		{if(doLog) {Log.i(LOG_ID,"send to "+name);};};
		}
    }

static public void setlibrenames() {
	librenames=Natives.librelinkRecepters();
	}

    private static Bundle setSerial(String serial) {
        Bundle bundle = new Bundle();
        bundle.putString("sensorSerial", serial);
        return bundle;
    }
/*
    private static Bundle getSensorFields(Sensor sensor) {
        Bundle bundle = new Bundle();
        bundle.putLong("sensorStartTime", sensor.getSensorStartTime().getTime());
        return bundle;
    } 

    private static Bundle setCurrent(boolean val) {
        Bundle bundle = new Bundle();
        bundle.putBundle("currentSensor", val);
        return bundle;
    }

    */
public static final String glucoseaction="com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING";
public static void sendGlucoseBroadcast(String serial, double currentGlucose,float rate,long mmsec) {
	final Context context=Applic.app;
        Intent intent = new Intent(glucoseaction);
        intent.putExtra("glucose", currentGlucose);
        intent.putExtra("timestamp", mmsec);
        intent.putExtra("bleManager", setSerial(serial));
        sendIntent(context,intent);
    }

    private static Bundle getSensorFields(long startmmsec) {
        Bundle bundle = new Bundle();
        bundle.putLong("sensorStartTime", startmmsec);
        return bundle;
    }

    private static boolean shouldSkipSensorActivate(String serial,long startsec) {
        if(serial==null||serial.isEmpty()||startsec<=0L)
            return true;
        final long now=System.currentTimeMillis();
        if(serial.equals(lastSensorActivateSerial)&&startsec==lastSensorActivateStart&&(now-lastSensorActivateSentAt)<SENSOR_ACTIVATE_SUPPRESS_MS) {
            if(doLog) {Log.i(LOG_ID,"Skip duplicate SENSOR_ACTIVATE "+serial+":"+startsec);};
            return true;
        }
        lastSensorActivateSerial=serial;
        lastSensorActivateStart=startsec;
        lastSensorActivateSentAt=now;
        return false;
    }

    public static void sendSensorActivateBroadcast(Context context,String serial,long startsec) {
        if(shouldSkipSensorActivate(serial,startsec)) {
            return;
        }
        Intent intent = new Intent("com.librelink.app.ThirdPartyIntegration.SENSOR_ACTIVATE");
        intent.putExtra("sensor", getSensorFields(startsec*1000L));
        intent.putExtra("bleManager", setSerial(serial));
        sendIntent(context, intent);
    } 
}
