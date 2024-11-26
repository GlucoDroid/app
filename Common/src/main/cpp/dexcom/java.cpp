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
/*      Fri Nov 22 12:21:09 CET 2024                                                 */


#ifdef DEXCOM
#include "streamdata.hpp"
#include "fromjava.h"
#include "jniclass.hpp"
#include "datbackup.hpp"
#include "destruct.hpp"
/*
bed-datatype:

Composed(19,19,0,11,[
  Digit(1,1,16,"",[],"type",1,1),
  Digit(1,1,16,"",[],"Status",1,1),
  Digit(4,4,10,"",[],"secs since start",1,1),
  Digit(2,2,10,"",[],"Sequence",1,1),
  Digit(2,2,10,"",[],"Bogus",1,1),
  Digit(2,2,10,"",[],"age",1,1),
  BitContain(3,2,4660,2,[
    Digit(2,2,10,"",[],"Glucose (mg/dL)",1,1),
    Digit(1,1,16,"",[],"DisplayOnly",1,1)],"",[Bitfield(2,3,"0:10:2:c;2,4;1,")],"",1,1),
  Digit(1,1,10,"",[],"State",1,1),
  Digit(1,1,10,"",[],"Trend",1,1),
  BitContain(3,2,4660,2,[
    Digit(2,2,10,"",[],"Predicted glucose",1,1),
    Digit(1,1,10,"",[],"unknown",1,1)],"",[Bitfield(2,3,"0:10:2:a;2,6;1,")],"",1,1),
  Digit(1,1,10,"",[],"Info",1,1)],"",[],"",1,1);
  */

static int rate2changeindex(float rate) {
        if (rate <= -2.0f) {
            return 1;
        }
        if (rate <= -1.0f) {
            return 2;
        }
        if (rate <= 1.0f) {
            return 3;
        }
        if (rate <= 2.0f) {
            return 4;
        }
        if(std::isnan(rate)) {
            return 0;
        }
        return 5;
    }
constexpr const int DEXSECONDS=5*60;
extern jlong glucoseback(uint32_t glval,float drate,SensorGlucoseData *hist);
extern void wakewithcurrent() ;
struct glucoseinput {
   uint8_t type;
   int8_t status;
   int32_t secsSinceStart;
   uint16_t sequence;
   uint16_t bogus;
   uint16_t age;
   uint16_t mgdL:12;
   uint16_t donly:4;
   int8_t state;
   int8_t trend;
   uint16_t predictedmgdL:10;
   uint16_t unknown:6;
   uint8_t info;
 //  uint8_t niets;
 [[nodiscard]]  uint32_t  gettime(uint32_t starttime)   const {
      return starttime+secsSinceStart-age;
      }
int getpredictedmgdL() const { 
   return predictedmgdL;
   }
[[nodiscard]]  int getindex() const {
   return (secsSinceStart-age)/DEXSECONDS;
   }
void showglucose(const time_t starttime) const {

   const struct glucoseinput *glucose=this;
	int glu=glucose->mgdL;
	int pre=glucose->predictedmgdL;
   time_t then=gettime(starttime);
//	auto starttime=now-glucose->secssincestartSensor;
	//LOGGER("type=%02x status=%d started %6d ago %.24s glucose=%3d %4.1f trend=% 3.2f predicted=%3d %4.1f state=%d info=%d unknown=%d\n",glucose->type,glucose->status,glucose->secssincestartSensor,ctime(&starttime),glu,glu/18.0f,((float)glucose->trend)/10.0f,pre,pre/18.0f,state,info,unknown);
	LOGGER("type=%02x status=%d sincestart=%d %.24s %2d ago glucose=%3d %4.1f trend=% 3.2f predicted=%3d %4.1f state=%d info=%d unknown=%d\n",glucose->type,glucose->status,secsSinceStart,ctime(&then),age,glu,glu/18.0f,((float)glucose->trend)/10.0f,pre,pre/18.0f,state,info,unknown);

	};


float getRateofChange() const {
	return (float)trend/10.0f;
	}

void save(SensorGlucoseData *sens,uint32_t wastime,int index) const {
   const auto rate=getRateofChange();
   sens->savepollallIDs<DEXSECONDS>(wastime,index,mgdL, rate2changeindex(rate),rate);
   }

void actual(SensorGlucoseData *sens,jlong *timeres) const {
  uint32_t nowsec=timeres[0]/1000L;
  time_t wasstart=sens->getstarttime();
  LOGGER("was starttime=%d %s",wasstart,ctime(&wasstart));
  if(wasstart<1605654000||wasstart>=nowsec||!sens->pollcount()) {
      sens->getinfo()->starttime=nowsec-secsSinceStart;
   #ifndef NOLOG
     wasstart=sens->getstarttime();
     LOGGER("new starttime=%d %s",wasstart,ctime(&wasstart));
  #endif
      }
   sens->sensorerror=false;
   const int index= getindex();
   const auto wastime=nowsec-age;

   save(sens,wastime,index);
   sens->saveDexFuture(index, wastime,getpredictedmgdL());
   const auto rate=getRateofChange();
   sens->consecutivelifecount();
	backup->wakebackup(Backup::wakestream);
	wakewithcurrent();
   if((nowsec-wastime)<maxbluetoothage)  {
      auto res=glucoseback(mgdL,rate,sens);
      timeres[1]=res;
      }
   else {
      timeres[1]=0LL;
      }
   timeres[0]-=age*1000L;
   }

} __attribute__ ((packed));





extern "C" JNIEXPORT void JNICALL   fromjava(dexcomProcessData)(JNIEnv *envin, jclass _,jlong dataptr, jbyteArray bluetoothdata,jlongArray jtimeres) {
  CritArSave<jlong> timeres(envin,jtimeres);
if(!dataptr) {
   LOGAR("dexcomProcessData dataptr==null");
   timeres.data()[1]=0LL;
    }
 dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
  SensorGlucoseData *sens=sdata->hist;
  if(!sens) {
      LOGAR("dexcomProcessData SensorGlucoseData==null");
      timeres.data()[1]=0LL;
     }
   const auto arlen=envin->GetArrayLength(bluetoothdata);
  if(arlen< 19) {
      LOGGER("dexcomProcessData: too small %d<19\n", arlen) ;
      timeres.data()[1]=0LL;
      }
  sens->setbroadcastfrom(INT_MAX);

  const CritAr  bluedata(envin,bluetoothdata);
  const glucoseinput *glucose=reinterpret_cast<decltype(glucose)>(bluedata.data());

   glucose->actual(sens,timeres.data());
  }
struct askfilldata {
   uint8_t cmd=0x59;
   int32_t starttime;
   int32_t endtime;
   } __attribute__ ((packed));

/*
extern "C" JNIEXPORT  jbyteArray  JNICALL   fromjava(getDexbackfillcmd)(JNIEnv *envin, jclass _cl,jlong dataptr) {
    dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
    SensorGlucoseData *sens=sdata->hist;
    auto *info=sens->getinfo();
    const int last=info->pollcount-1;
   const int was=info->lastLifeCountReceived;
   int start,end;
   if(last>=0) {
        const uint32_t starttime=sens->getstarttime();
        time_t starts=sens->getstream(was)->gettime()+60;
        start= starts-starttime;
        const uint32_t lasttime= sens->getstream(last)->gettime();
        time_t ends=lasttime-60;
        end= ends-starttime;
        if(end<=start) {
           LOGGER("getDexbackfillcmd %d<=%d\n",end,start);
          end=start=1732379128;
          ; //After a backfill it seems to have less missing connections. So it always ask for backfill for the far future.
          }
        #ifndef NOLOG
        char buf1[27],buf2[27];
        LOGGER("getDexbackfillcmd %d-%d %.23s-%23s\n",end,start,ctime_r(&starts,buf1), ctime_r(&ends,buf2));
        #endif
        } 
      else  {
            start=end=DEXSECONDS;
            }
         constexpr const int len=sizeof(struct askfilldata);
        jbyteArray uit=envin->NewByteArray(len);
         struct askfilldata data {.starttime=start,.endtime=end};
         envin->SetByteArrayRegion(uit,0,len,reinterpret_cast<const jbyte*>(&data));

         return uit; 
   }
*/
extern "C" JNIEXPORT  jbyteArray  JNICALL   fromjava(getDexbackfillcmd)(JNIEnv *envin, jclass _cl,jlong dataptr) {
    dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
    SensorGlucoseData *sens=sdata->hist;
    auto *info=sens->getinfo();
    const int last=info->pollcount-1;
   const int was=info->lastLifeCountReceived;
   if(last>was) {
        uint32_t starttime=sens->getstarttime();
        time_t starts=sens->getstream(was)->gettime()+60;
        int start= starts-starttime;
        if(start<DEXSECONDS)
            start=DEXSECONDS;
        time_t ends=sens->getstream(last)->gettime()-60;
        const int end= ends-starttime;
        if(end<=start) {
            LOGGER("getDexbackfillcmd %d<=%d\n",end,start);
            return nullptr;
            } 
        #ifndef NOLOG
        char buf1[27],buf2[27];
        LOGGER("getDexbackfillcmd %d-%d %.23s-%23s\n",end,start,ctime_r(&starts,buf1), ctime_r(&ends,buf2));
        #endif
         constexpr const int len=sizeof(struct askfilldata);
        jbyteArray uit=envin->NewByteArray(len);
         struct askfilldata data {.starttime=start,.endtime=end};
         envin->SetByteArrayRegion(uit,0,len,reinterpret_cast<const jbyte*>(&data));
         return uit; 
         }
   LOGAR("getDexbackfillcmd not needed"); 
   return nullptr;
   }



   


struct dexbackfill {
   int32_t secsSinceStart;
   int16_t mgdL;
   uint8_t type;
   uint8_t extra;
   int8_t trend;

[[nodiscard]]  int getpredictedmgdL() const { 
   return 0;
   }
[[nodiscard]]  int getindex() const {
   return secsSinceStart/DEXSECONDS;
   }
[[nodiscard]]   bool usable() const {
      return type==0x6||type==0x7||type==0xe;
      }
[[nodiscard]] uint32_t gettime(const uint32_t sensorstarttime) const {
      return sensorstarttime+secsSinceStart;
      }
[[nodiscard]] float gettrend() const  {
      return ((float)trend)/10.0f;
      }
   void showdata(const uint32_t sensorstart) const {
      time_t was=gettime(sensorstart);
      LOGGER("%06d %03d %4.1f % 2.1f %s",secsSinceStart,mgdL,(float)mgdL/18.0,gettrend(),ctime(&was));
      };

float getRateofChange() const {
	return (float)trend/10.0f;
	}

void save(SensorGlucoseData *sens,uint32_t wastime,int index) const {
   const auto rate=getRateofChange();
   sens->savepollallIDs<DEXSECONDS>(wastime,index,mgdL, rate2changeindex(rate),rate);
   }
void backfill(SensorGlucoseData *sens) const {
   auto id=getindex();
   const auto starttime= sens->getstarttime();
   const auto wastime=gettime(starttime);
   save(sens,wastime,id);
   if(id<sens->getbroadcastfrom()) sens->setbroadcastfrom(id);
   sens->backstream(id);
/*   if(id>=(sens->pollcount()-2)) {
      backup->wakebackup(Backup::wakestream);
      }*/
   sens->fastupdatelifecount(id);
   }
   } __attribute__ ((packed));
extern "C" JNIEXPORT  jboolean  JNICALL   fromjava(dexbackfill)(JNIEnv *envin, jclass cl,jlong dataptr, jbyteArray  bluetoothdata) {
  const auto arlen=envin->GetArrayLength(bluetoothdata);
  if(arlen< sizeof( struct dexbackfill )) {
      LOGGER("dexbackffill: too small %d<%d\n", arlen, sizeof( struct dexbackfill )) ;
      return false;
      }
  dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
  SensorGlucoseData *sens=sdata->hist;
  const CritAr  bluedata(envin,bluetoothdata);
  const dexbackfill *back=reinterpret_cast<decltype(back) >(bluedata.data());
   if(!back->usable()) {
       LOGAR("dexbackfill unusable");
      return false;
      }
   back->backfill(sens);
   LOGAR("dexbackfill saved");
   return true;
   }
extern bool validate12(const PCert &cert); //01?
extern bool validate3(const EC_POINT *g1,const EC_POINT *g2,const PCert &cert1,const PCert &cert3);

//Saves received keys from other side;

std::array<uint8_t,32>  mkSharedKey(const PCert &cert2,const PCert &cert3,const BIGNUM *pass,const BIGNUM *x2);
extern "C" JNIEXPORT  jboolean  JNICALL   fromjava(dexPutPubKey)(JNIEnv *envin, jclass cl,jlong dataptr,int which ,jbyteArray input) {
   if(which<0||which>2) {
       LOGGER("dexPutPubKey certnr=%d should be in [1,3]\n",which);
       return false;
   	}
   const auto arlen=envin->GetArrayLength(input);
   if(arlen!=160) {
      LOGGER("dexPutPubKey arlen=%d should be 160\n",arlen);
      return false;
      }

  const CritAr  bytes(envin,input); 
  dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
  PCert *certs=sdata->dexcontext.certs;
  certs[which].frombytes(bytes.data());
  if(which<2)   {
      bool res=validate12(certs[which]);
      LOGGER("dexPutPubKey %d %s\n",which,(res?"valid":"invalid"));
      return res;
      }
  if(validate3(sdata->dexcontext.key[0].getpublic(),sdata->dexcontext.key[1].getpublic(),certs[0],certs[2])) {
      auto key=mkSharedKey(certs[1],certs[2],sdata->dexcontext.pin,sdata->dexcontext.key[1].getprivate()) ;
      SensorGlucoseData *sens=sdata->hist;
      std::copy_n(key.data(),16,sens->getinfo()->sharedKey.data());
      LOGAR("dexPutPubKey 3 valid sharedkey");
      return true;
      }
   LOGAR("dexPutPubKey 3 invalid");
   return false;
  }
template <typename T>
jbyteArray getjbyteArray(JNIEnv *env,const T &cont) {
   static_assert(sizeof(cont[0])==sizeof(jbyte));
   jbyteArray uit=env->NewByteArray(cont.size());
   env->SetByteArrayRegion(uit, 0, cont.size(),reinterpret_cast<const jbyte*>(cont.data()));
   return uit;
   }

extern bool makeRound3Cert(PCert &cert,const EC_POINT * pub1,const EC_POINT * pub2,const EC_POINT *  pubA,const BIGNUM * privB,const BIGNUM * pass) ;
extern "C" JNIEXPORT  jbyteArray  JNICALL   fromjava(makeRound3bytes)(JNIEnv *envin, jclass cl,jlong dataptr) {
   if(!dataptr) {
      LOGAR("makeRound3bytes dataptr==null");
      return nullptr;
       }
  dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
  PCert *certs=sdata->dexcontext.certs;
  PCert certuit3;
 if(!makeRound3Cert(certuit3,certs[0].pubkey1,certs[1].pubkey1,sdata->dexcontext.key[0].getpublic(),sdata->dexcontext.key[1].getprivate(), sdata->dexcontext.pin))  {
      LOGAR("makeRound3bytes failed");
      return nullptr;
      }
  std::array<uint8_t,160> bytes=certuit3.byteify();
   LOGAR("makeRound3bytes success");
  return getjbyteArray(envin,bytes);
   }
	
extern std::array<uint8_t,160>  mkround12(const KeyPair &keys);
extern "C" JNIEXPORT  jbyteArray  JNICALL   fromjava(makeRound12bytes)(JNIEnv *envin, jclass cl,jlong dataptr,int which) {
   if(!dataptr) {
      LOGAR("makeRound12Packet dataptr==null");
      return nullptr;
       }
  LOGGER("makeRound12bytes %d\n",which);
   return getjbyteArray(envin,mkround12(reinterpret_cast<dexcomstream *>(dataptr)->dexcontext.key[which])); //TODO make mkround12 not copy unnecessarily
   }



extern std::array<uint8_t,64> getchallenge(std::span<const uint8_t> input) ;
extern "C" JNIEXPORT  jbyteArray  JNICALL   fromjava(dexChallenger)(JNIEnv *envin, jclass cl,jbyteArray jdata) {
  LOGAR("dexChallenger");
  const auto arlen=envin->GetArrayLength(jdata);
//  const CritAr  bytes(envin,jdata);
  const uint8_t *bytes=(const uint8_t*)envin->GetPrimitiveArrayCritical(jdata, nullptr); 
  auto chall=getchallenge(std::span<const uint8_t>(bytes,arlen));
   envin->ReleasePrimitiveArrayCritical(jdata,(jbyte*)bytes,JNI_ABORT);
  return getjbyteArray(envin,chall); 
   }

extern "C" JNIEXPORT  jboolean  JNICALL   fromjava(isAuthenticated)(JNIEnv *envin, jclass cl,jlong dataptr) {
  const dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
  const SensorGlucoseData *sens=sdata->hist;
  jboolean res= *reinterpret_cast<const unsigned long long *>(sens->getinfo()->sharedKey.data())||sdata->dexcontext.certs[2].valid();
  LOGGER("isAuthenticated()=%d\n",res);
  return res;
   }

extern bool encrypt8AES(const uint8_t *keybytes,const uint8_t *data,unsigned char *uit);
extern "C" JNIEXPORT  jboolean  JNICALL   fromjava(dex8AES)(JNIEnv *envin, jclass cl,jlong dataptr,jbyteArray jdata,jint startdat,jbyteArray jout,jint startout) { 
  const SensorGlucoseData *sens=reinterpret_cast<dexcomstream *>(dataptr)->hist;
  const CritAr  bytes(envin,jdata); 
  CritArSave  out(envin,jout); 
  return encrypt8AES(sens->getinfo()->sharedKey.data(),reinterpret_cast<const unsigned char*>(bytes.data())+startdat,reinterpret_cast<unsigned char*>(out.data())+startout);
   }
extern "C" JNIEXPORT  void  JNICALL   fromjava(dexResetKeys)(JNIEnv *envin, jclass cl,jlong dataptr) { 
  dexcomstream *sdata=reinterpret_cast<dexcomstream *>(dataptr);
  SensorGlucoseData *sens=sdata->hist;
  PCert *certs=sdata->dexcontext.certs;
  LOGAR("dexResetKeys");
  for(int i=0;i<3;++i) { 
      certs[i].free();
      }
 *reinterpret_cast<unsigned long long *>(sens->getinfo()->sharedKey.data())=0LL;
  }



//Large pin possible?
extern "C" JNIEXPORT  jint  JNICALL   fromjava(getDexCertSize)(JNIEnv *envin, jclass cl,jbyteArray jar) { 
    const auto len=envin->GetArrayLength(jar);
    if(len!=7) {
      LOGGER("getDexCertSize len(%d)!=7\n",len);
      return -1;
      }
 struct  Certsize {
    uint8_t B;
    uint8_t zero;
    int8_t which;
    uint16_t size;
   } __attribute__ ((packed)) ;
  const CritAr  bytes(envin,jar);
  auto *siz=reinterpret_cast<const Certsize*>(bytes.data());
  LOGGER("getDexCertSize which=%d size=%d\n",siz->which,siz->size);
  if(siz->B!=0xB||siz->zero!=0||siz->which<0) {
      LOGAR("getDexCertSize wrong values");
      return -1;
      }
  return siz->size;
  }

extern "C" JNIEXPORT void JNICALL   fromjava(dexSaveDeviceName)(JNIEnv *env, jclass cl,jlong dataptr,jstring jdeviceName) {
   if(!dataptr) {
      LOGAR("dexSaveDeviceName dataptr==null");
      return;
      }
   if(jdeviceName==nullptr) {
      LOGAR("dexSaveDeviceName name==null");
      return;
      }
   streamdata *sdata=reinterpret_cast<streamdata *>(dataptr);
   jint getlen= env->GetStringUTFLength( jdeviceName);
   auto *sens= sdata->hist;
   auto *info=sens->getinfo();
   const int maxlen=sizeof(info->DexDeviceName);
   if((getlen+1)>maxlen) {
      LOGGER("deviceNamelen=%d toolarge\n",getlen);
      }	
   int len=std::min(maxlen-1,getlen);
   char *name=(char *)info->DexDeviceName;
   LOGGER("dexSaveDeviceName(%s)\n",name);
   env->GetStringUTFRegion( jdeviceName, 0,len, name);
   name[len]='\0';
   // sendstreaming(sens);  //TODO??
   }
extern "C" JNIEXPORT jstring JNICALL   fromjava(dexGetDeviceName)(JNIEnv *env, jclass cl,jlong dataptr) {
   if(!dataptr)
      return nullptr;
   const char *name=reinterpret_cast<const streamdata *>(dataptr)->hist->getinfo()->DexDeviceName;
   if(*name)
      return env->NewStringUTF(name);
   return nullptr;
   }

static bool isG7(const char *deviceName) {
   if(memcmp(deviceName,"DX",2))
      return false;
   const char * const rest=deviceName+2;
   const char conti[][3]={ "CM","02","01"};
   for(const char *el:conti) {
      if(!memcmp(el,rest,2))
         return true;
      }
    return false;
    }

extern "C" JNIEXPORT jboolean JNICALL   fromjava(dexCandidate)(JNIEnv *env, jclass cl,jlong dataptr,jstring jdeviceName,jstring jaddress) {
   if(!jaddress) return false;
   if(!jdeviceName) return false;
   const char *deviceName = env->GetStringUTFChars( jdeviceName, NULL);
   if(!deviceName) return false;
   destruct   _([jdeviceName,deviceName,env]() {env->ReleaseStringUTFChars(jdeviceName, deviceName);});
   const SensorGlucoseData *sens=reinterpret_cast<const streamdata *>(dataptr)->hist;
   const char *name=sens->getinfo()->DexDeviceName;
   if(*name) 
      return !strcmp(name,deviceName);
   if(!isG7(deviceName))
      return false;
   const char *address = env->GetStringUTFChars( jaddress, NULL);
   if(!address) return false;
   destruct   _([jaddress,address,env]() {env->ReleaseStringUTFChars(jaddress, address);});
   return !sensors->knownDex(deviceName,address) ;
   }
#include "EverSense.hpp"
extern "C" JNIEXPORT void JNICALL   fromjava(dexEndBackfill)(JNIEnv *env, jclass cl,jlong dataptr) {
      backup->wakebackup(Backup::wakestream);
#ifdef     OLDEVERSENSE
	if(!settings->data()->everSenseBroadcast.nr)
         return;
     sendEverSenseold(reinterpret_cast<const streamdata *>(dataptr)->hist,1);
#endif
      }
#endif



