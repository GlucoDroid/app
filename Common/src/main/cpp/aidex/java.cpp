#include "SensorGlucoseData.hpp"
#include "datbackup.hpp"
#include "fromjava.h"
#include "glucose.hpp"
#include "jniclass.hpp"
#include "logs.hpp"
#include "streamdata.hpp"
#include <cmath>
#include <jni.h>

extern jlong glucoseback(uint32_t nu, uint32_t glval, float drate,
                         SensorGlucoseData *hist);
extern void wakewithcurrent();

static bool prepareAidexStore(jlong dataptr, jlong mmsec, jfloat glucose,
                              jfloat rawGlucose, aidexstream *&sdata,
                              SensorGlucoseData *&sens, uint32_t &timsec,
                              int &internalVal, int &rawVal, int &id) {
  sdata = reinterpret_cast<aidexstream *>(dataptr);
  if (!sdata) {
    LOGAR("aidex store sdata==null");
    return false;
  }
  sens = sdata->hist;
  if (!sens) {
    LOGAR("aidex store SensorGlucoseData==null");
    return false;
  }

  timsec = mmsec / 1000L;
  internalVal = (glucose > 0) ? (int)std::round(glucose) : 0;
  rawVal = 0;
  if (rawGlucose > 0) {
    constexpr float mgdlToMmol = 1.0f / 18.0182f;
    rawVal = (int)std::round(rawGlucose * mgdlToMmol * 10.0f);
  }

  id = 0;
  uint32_t start = sens->getinfo()->starttime;
  if (start > 0 && timsec >= start) {
    id = (timsec - start + 30) / 60;
  } else {
    id = sens->getinfo()->pollcount;
  }
  return true;
}

extern "C" JNIEXPORT jlong JNICALL fromjava(aidexProcessData)(
    JNIEnv *env, jclass cl, jlong dataptr, jbyteArray value, jlong mmsec,
    jfloat glucose, jfloat rawGlucose, jfloat calibrationFactor) {
  if (!value) {
    LOGAR("aidexProcessData value==null");
    return 1LL;
  }

  aidexstream *sdata = nullptr;
  SensorGlucoseData *sens = nullptr;
  uint32_t timsec = 0;
  int internalVal = 0;
  int rawVal = 0;
  int id = 0;
  if (!prepareAidexStore(dataptr, mmsec, glucose, rawGlucose, sdata, sens,
                         timsec, internalVal, rawVal, id)) {
    return 1LL;
  }

  // Trend/Rate (AiDex doesn't have a clear rate byte yet, using NAN)
  float change = NAN;

#ifndef NOLOG
  time_t tim = timsec;
  LOGGER("aidexProcessData glucose=%d %s", internalVal, ctime(&tim));
#endif

  // Unified persistence: store in both stream and history
  // Using 60 seconds interval for AiDex
  sens->savepollallIDs<60>(timsec, id, internalVal, 0, change, rawVal);

  // Trigger UI and Notification sync
  jlong res = glucoseback(timsec, internalVal, change, sens);
  if (res) {
    sensor *sensor = sensors->getsensor(sdata->sensorindex);
    sens->sensorerror = false;
    if (sensor && sensor->finished) {
      sensor->finished = 0;
      backup->resensordata(sdata->sensorindex);
    }
    if (backup) {
      backup->wakebackup(Backup::wakestream);
    }
  }
  wakewithcurrent();

  return res;
}

extern "C" JNIEXPORT void JNICALL fromjava(aidexStoreHistoryData)(
    JNIEnv *env, jclass cl, jlong dataptr, jlong mmsec, jfloat glucose,
    jfloat rawGlucose) {
  aidexstream *sdata = nullptr;
  SensorGlucoseData *sens = nullptr;
  uint32_t timsec = 0;
  int internalVal = 0;
  int rawVal = 0;
  int id = 0;
  if (!prepareAidexStore(dataptr, mmsec, glucose, rawGlucose, sdata, sens,
                         timsec, internalVal, rawVal, id)) {
    return;
  }

  sens->savepollallIDsQuiet<60>(timsec, id, internalVal, 0, NAN, rawVal);
}

extern "C" JNIEXPORT void JNICALL fromjava(aidexSetStartTime)(JNIEnv *env,
                                                              jclass cl,
                                                              jlong dataptr,
                                                              jlong timeMs) {
  aidexstream *sdata = reinterpret_cast<aidexstream *>(dataptr);
  if (sdata && sdata->hist) {
    auto *info = sdata->hist->getinfo();
    const uint32_t newstart = timeMs / 1000L;
    const uint32_t oldstart = info->starttime;
    const uint32_t pollcount = info->pollcount;
    if (pollcount > 0 && oldstart > 0 && newstart > oldstart + 30 * 60) {
      LOGGER("aidexSetStartTime: new session rebase oldstart=%u newstart=%u "
             "pollcount=%u\n",
             oldstart, newstart, pollcount);
      sdata->hist->rebaseDirectStreamWindow(newstart);
      info = sdata->hist->getinfo();
    } else {
      info->starttime = newstart;
    }
    if (info->days < 10 || info->days > maxdays) {
      info->days = 15;
    }
    if (!info->wearduration2) {
      info->wearduration2 = static_cast<uint16_t>(info->days * 24 * 60);
    }
    LOGGER("aidexSetStartTime: %ld -> starttime=%u days=%u wear=%u\n", timeMs,
           info->starttime, info->days, info->wearduration2);
  }
}

extern "C" JNIEXPORT void JNICALL fromjava(aidexSetWearDays)(JNIEnv *env,
                                                             jclass cl,
                                                             jlong dataptr,
                                                             jint days) {
  if (days < 10 || days > maxdays) {
    return;
  }
  aidexstream *sdata = reinterpret_cast<aidexstream *>(dataptr);
  if (sdata && sdata->hist) {
    auto *info = sdata->hist->getinfo();
    info->days = static_cast<uint8_t>(days);
    info->wearduration2 = static_cast<uint16_t>(days * 24 * 60);
    LOGGER("aidexSetWearDays: days=%d wear=%u\n", days, info->wearduration2);
  }
}
